package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.merge.Merge3;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.ArrayList;

/**
 * A working-copy-free 3-way merge computation -- JGit {@code ThreeWayMerger} parity. Takes two
 * commit nodes ("ours"/"theirs"), computes their common ancestor and per-file merge purely from
 * the changelog/manifest/filelog store, and returns the result as data instead of writing to disk
 * or touching dirstate/the working directory. {@link MergeCommand} stays the working-copy-mutating
 * porcelain command (real {@code hg merge}); this class reuses its already-verified LCA/criss-cross
 * base resolution and per-file {@link Merge3} logic as a pure computation, so a caller (e.g. a PR
 * merge-preview, or a server-side merge that commits its result directly without ever checking out
 * a working copy) can get a merge result without mutating any repository state.
 *
 * @apiNote Typically obtained via {@link Hg#treeMerge()} on an open {@link Hg}
 *     instance rather than constructed directly.
 */
public class TreeMergeCommand {
    private final HgRepository repository;
    private byte[] ours;
    private byte[] theirs;

    public TreeMergeCommand(HgRepository repository) {
        this.repository = repository;
    }

    public TreeMergeCommand setOurs(byte[] ours) {
        this.ours = ours;
        return this;
    }

    public TreeMergeCommand setTheirs(byte[] theirs) {
        this.theirs = theirs;
        return this;
    }

    public static class TreeMergeResult {
        private final boolean conflicted;
        private final List<String> conflicts;
        private final Map<String, byte[]> changedFiles;
        private final Map<String, Integer> changedModes;
        private final Set<String> removedFiles;
        private final Map<String, Map<String, String>> copiedFiles;

        TreeMergeResult(boolean conflicted, List<String> conflicts, Map<String, byte[]> changedFiles,
                Map<String, Integer> changedModes, Set<String> removedFiles,
                Map<String, Map<String, String>> copiedFiles) {
            this.conflicted = conflicted;
            this.conflicts = conflicts;
            this.changedFiles = changedFiles;
            this.changedModes = changedModes;
            this.removedFiles = removedFiles;
            this.copiedFiles = copiedFiles;
        }

        public boolean isConflicted() {
            return conflicted;
        }

        /** Paths whose merged content still contains unresolved conflict markers. */
        public List<String> getConflicts() {
            return conflicts;
        }

        /** Paths that must be added or overwritten (relative to "ours") to reach the merged result, mapped to their final content. */
        public Map<String, byte[]> getChangedFiles() {
            return changedFiles;
        }

        /**
         * The POSIX-style mode every {@link #getChangedFiles()} path should be applied with
         * ({@code 0644} regular, {@code 0755} executable, {@code 0120000} symlink -- the same
         * convention {@link TreeCommand.TreeEntry#getMode()} and {@link ArchiveCommand} use).
         * A caller applying {@link #getChangedFiles()} verbatim must consult this map rather than
         * re-deriving a mode from stat'd disk state -- there is no disk state here -- since an
         * executable-bit or symlink-vs-regular change introduced by "theirs" (or picked during a
         * conflict) can leave the underlying bytes identical to what only the mode flag changed.
         */
        public Map<String, Integer> getChangedModes() {
            return changedModes;
        }

        /** Paths that must be deleted (relative to "ours") to reach the merged result. */
        public Set<String> getRemovedFiles() {
            return removedFiles;
        }

        /**
         * For a path in {@link #getChangedFiles()} that was cleanly adopted from "theirs"
         * (added-by-theirs, or ours-unmodified/theirs-modified -- the two cases where the merged
         * content is exactly one source revision's content, not a synthesized 3-way blend), the
         * {@code "copy"}/{@code "copyrev"} metadata that source revision's filelog entry already
         * carried (i.e. it was originally created via {@code hg cp}/{@code hg mv}), so a caller
         * writing this result as a new changeset (see {@code MergeCommitCommand}) can preserve
         * {@code hg log --follow} rename history across the merge instead of silently starting a
         * fresh, provenance-less filelog history for the path. Never populated for a path whose
         * content came from an actual line-level 3-way merge (real hg does not invent fresh copy
         * records there either -- only forwards metadata a revision already recorded at its own
         * commit time). Paths with no copy metadata are simply absent from this map (never mapped
         * to an empty value).
         */
        public Map<String, Map<String, String>> getCopiedFiles() {
            return copiedFiles;
        }
    }

    public TreeMergeResult call() throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);
        Revlog manifestRevlog = repository.getManifestRevlog();

        int p1Rev = NodeIdUtil.findRevisionByNodeId(changelog, ours);
        if (p1Rev == -1) {
            throw new HgRevisionNotFoundException(NodeIdUtil.toHex(ours));
        }
        int p2Rev = NodeIdUtil.findRevisionByNodeId(changelog, theirs);
        if (p2Rev == -1) {
            throw new HgRevisionNotFoundException(NodeIdUtil.toHex(theirs));
        }

        // A throwaway MergeCommand instance is used purely as a computation backend -- only its
        // pure store-reading helpers (LCA resolution, manifest/file-content loading) are called;
        // call() (the working-copy-mutating porcelain command) is never invoked on it.
        MergeCommand helper = new MergeCommand(repository);
        MergeCommand.MergeBase lca = helper.getMergeBase(changelog, manifestRevlog, p1Rev, p2Rev, 0);

        Map<String, String> manifestLca = lca.manifest;
        Map<String, String> manifestP1 = helper.loadManifestAtCommit(changelog, manifestRevlog, p1Rev);
        Map<String, String> manifestP2 = helper.loadManifestAtCommit(changelog, manifestRevlog, p2Rev);

        Set<String> allPaths = new TreeSet<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        allPaths.addAll(manifestLca.keySet());
        allPaths.addAll(manifestP1.keySet());
        allPaths.addAll(manifestP2.keySet());

        Map<String, byte[]> changedFiles = new LinkedHashMap<>();
        Map<String, Integer> changedModes = new LinkedHashMap<>();
        Set<String> removedFiles = new LinkedHashSet<>();
        Map<String, Map<String, String>> copiedFiles = new LinkedHashMap<>();
        List<String> conflicts = new ArrayList<>();
        boolean conflicted = false;

        for (String path : allPaths) {
            String hLca = manifestLca.get(path);
            String hP1 = manifestP1.get(path);
            String hP2 = manifestP2.get(path);

            if (Objects.equals(hP1, hP2)) {
                continue; // Same on both sides -- no delta relative to ours either way.
            }

            if (hP1 == null && hP2 != null) {
                if (hLca == null) {
                    // Added by theirs only.
                    changedFiles.put(path, helper.getFileRevisionContent(path, hP2));
                    changedModes.put(path, helper.getModeFromManifestHex(hP2));
                    recordCopyMetadataIfAny(helper, copiedFiles, path, hP2);
                }
                // else: base had it, ours removed it, theirs left it alone -- stays removed, no delta.
            } else if (hP1 != null && hP2 == null) {
                if (hLca != null) {
                    // Base had it, theirs removed it, ours left it alone -- must be removed.
                    removedFiles.add(path);
                }
                // else: added by ours only -- already correct, no delta.
            } else {
                if (Objects.equals(hP1, hLca)) {
                    // Ours unmodified, theirs modified -- take theirs (content AND mode/flag,
                    // since a flag-only change, e.g. chmod +x with identical bytes, would
                    // otherwise be silently dropped if only content were returned).
                    changedFiles.put(path, helper.getFileRevisionContent(path, hP2));
                    changedModes.put(path, helper.getModeFromManifestHex(hP2));
                    recordCopyMetadataIfAny(helper, copiedFiles, path, hP2);
                } else if (Objects.equals(hP2, hLca)) {
                    // Theirs unmodified, ours modified -- ours already correct, no delta.
                } else {
                    byte[] baseContent = lca.getFileContent(helper, path, hLca);
                    byte[] mineContent = helper.getFileRevisionContent(path, hP1);
                    byte[] theirsContent = helper.getFileRevisionContent(path, hP2);

                    Merge3.MergeResult mergeRes = Merge3.merge(
                            helper.readLines(baseContent), helper.readLines(mineContent), helper.readLines(theirsContent));
                    StringBuilder sb = new StringBuilder();
                    for (String line : mergeRes.getMergedLines()) {
                        sb.append(line).append('\n');
                    }
                    changedFiles.put(path, sb.toString().getBytes(StandardCharsets.UTF_8));
                    // Mode preference mirrors RebaseCommand.attemptThreeWayMerge's own convention
                    // for a merged/conflicted path: prefer "ours" (local/p1) when both sides have
                    // it, matching the already-hardened cherry-pick/rebase merge logic.
                    changedModes.put(path, helper.getModeFromManifestHex(hP1 != null ? hP1 : hP2));
                    if (mergeRes.isConflicted()) {
                        conflicted = true;
                        conflicts.add(path);
                    }
                }
            }
        }

        return new TreeMergeResult(conflicted, conflicts, changedFiles, changedModes, removedFiles, copiedFiles);
    }

    /**
     * Populates {@code copiedFiles.get(path)} with {@code hex}'s own {@code copy}/{@code copyrev}
     * filelog metadata, if it carries any -- see {@link TreeMergeResult#getCopiedFiles()}'s javadoc
     * for why only the two clean-adoption call sites above ever call this.
     */
    private static void recordCopyMetadataIfAny(MergeCommand helper, Map<String, Map<String, String>> copiedFiles,
            String path, String hex) throws IOException {
        Map<String, String> meta = helper.getFileMetadata(path, hex);
        if (meta != null && meta.containsKey("copy")) {
            copiedFiles.put(path, meta);
        }
    }
}
