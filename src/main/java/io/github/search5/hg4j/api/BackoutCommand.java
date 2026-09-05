package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgMergeConflictException;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.merge.MergeState;
import io.github.search5.hg4j.revwalk.ChangesetGraph;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.util.SafeFileIO;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Porcelain command corresponding to {@code hg backout REV} — creates a new changeset that undoes
 * the changes introduced by {@code REV}, without altering history. Only single-parent changesets
 * are supported (matching real hg's default behavior — backing out a merge requires {@code
 * --parent} to disambiguate, which this command does not yet expose), and only the default
 * (non-{@code --merge}) mode: the result always has one parent, maintaining a linear history.
 *
 * <p>Ported and verified live against real {@code hg} 7.2 (2026-09-05, backlog #39 wave 4; see
 * {@code mercurial/commands.py}'s {@code _dobackout} and {@code mercurial/merge.py}'s {@code
 * back_out}). Two cases exist, both requiring a clean working copy (matching real hg's own {@code
 * scmutil.bail_if_changed}) and that {@code REV} be an ancestor of the working copy's parent
 * (real hg: "cannot backout change that is not an ancestor"):
 * <ul>
 *   <li><b>{@code REV} is the working copy's own parent</b> (the common case, e.g. backing out
 *   the tip): the diff between {@code REV} and its own parent is applied directly — this can
 *   never conflict, since the working copy already exactly matches {@code REV}.</li>
 *   <li><b>{@code REV} is an older ancestor</b>: real hg performs a genuine 3-way merge —
 *   ancestor = {@code REV} itself, "local" = the current working copy, "other" = {@code REV}'s
 *   own parent (the desired reverted state) — so that later, unrelated changes made after {@code
 *   REV} are preserved and only {@code REV}'s own effect is undone. A file touched independently
 *   by both {@code REV} and later history conflicts exactly like a real merge would (verified
 *   live: {@code hg backout -r <old-ancestor>} on a file also edited afterward produces real
 *   conflict markers and leaves {@code hg resolve --list} showing {@code U <path>}) — this reuses
 *   {@link RebaseCommand#attemptThreeWayMerge}, the same hardened 3-way-merge engine {@link
 *   MergeCommand}/{@link RebaseCommand}/{@link GraftCommand} share, so a conflict here is written
 *   to {@code .hg/merge/state2} exactly like any other conflicted merge and can be resolved via
 *   {@link ResolveCommand} (or real {@code hg resolve}) before retrying the commit — real hg has
 *   no {@code backout --continue}; once resolved, the caller simply commits normally, same as
 *   this class's own final step.</li>
 * </ul>
 *
 * <p>On a conflict, this throws {@link HgMergeConflictException} and does <em>not</em> commit —
 * exactly matching real hg's own behavior ({@code hg backout} exits 1 and prints "use 'hg resolve'
 * to retry unresolved file merges" without creating any changeset). The working copy's dirstate
 * parent is left unchanged (single-parent) in both cases; unlike a real two-parent {@code hg
 * merge}, a paused conflicted backout is not itself a merge from dirstate's point of view.
 */
public class BackoutCommand {
    private final HgRepository repository;
    private String revision;
    private String message;
    private String author = "hg4j";

    public BackoutCommand(HgRepository repository) {
        this.repository = repository;
    }

    public BackoutCommand setRevision(String revision) {
        this.revision = revision;
        return this;
    }

    public BackoutCommand setMessage(String message) {
        this.message = message;
        return this;
    }

    public BackoutCommand setAuthor(String author) {
        this.author = author;
        return this;
    }

    public byte[] call() throws IOException, HgLockException, HgMergeConflictException {
        if (revision == null || revision.isEmpty()) {
            throw new IllegalStateException("Revision to back out must be specified.");
        }
        repository.clearRevlogCache();

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        byte[] targetNode = NodeIdUtil.resolveRevision(changelog, revision);
        if (targetNode == null) {
            throw new HgValidationException("Unknown revision: " + revision);
        }
        int targetRev = changelog.findRevision(targetNode);
        Revlog.IndexRecord targetRec = changelog.getIndexRecord(targetRev);
        if (targetRec.getParent2() != -1) {
            throw new HgValidationException(
                    "cannot backout a merge changeset -- --parent selection is not supported yet");
        }
        int parentRev = targetRec.getParent1();
        if (parentRev == -1) {
            throw new HgValidationException("cannot backout a change with no parents");
        }
        byte[] parentNode = changelog.getIndexRecord(parentRev).getNodeId();

        try (HgLock storeLock = repository.lockStore();
             HgLock wlock = repository.lockWorkingCopy()) {

            Dirstate dirstate = repository.getDirstate();
            byte[] op1 = dirstate.getParent1();
            byte[] op2 = dirstate.getParent2();
            if (op1 == null || NodeIdUtil.isAllZero(op1)) {
                throw new HgValidationException("cannot backout in an empty repository");
            }
            if (op2 != null && !NodeIdUtil.isAllZero(op2)) {
                // Matches real hg's scmutil.bail_if_changed: "outstanding uncommitted merge".
                throw new HgValidationException("outstanding uncommitted merge");
            }

            Status status = new StatusCommand(repository).call();
            if (!status.getModified().isEmpty() || !status.getAdded().isEmpty() || !status.getRemoved().isEmpty()) {
                // Matches real hg's scmutil.bail_if_changed: "uncommitted changes".
                throw new HgValidationException("uncommitted changes");
            }

            int op1Rev = changelog.findRevision(op1);
            if (op1Rev == -1) {
                throw new HgRevisionNotFoundException(NodeIdUtil.toHex(op1));
            }
            ChangesetGraph graph = new ChangesetGraph(changelog);
            if (!graph.isAncestor(targetRev, op1Rev)) {
                throw new HgValidationException("cannot backout change that is not an ancestor");
            }

            Map<String, String> targetManifest = repository.getManifestAtCommit(targetNode);
            Map<String, String> parentManifest = repository.getManifestAtCommit(parentNode);

            if (op1Rev == targetRev) {
                // The common case: REV is the working copy's own parent. The working copy
                // already exactly matches REV, so applying REV-vs-its-parent's diff directly can
                // never conflict.
                applyDirectRevert(targetManifest, parentManifest, dirstate);
            } else {
                Map<String, String> localManifest = repository.getManifestAtCommit(op1);
                List<String> conflicts = applyThreeWayBackout(targetManifest, localManifest, parentManifest,
                        dirstate, op1, parentNode, targetNode);
                if (!conflicts.isEmpty()) {
                    repository.writeDirstate(dirstate);
                    throw new HgMergeConflictException(conflicts,
                            "backout halted: " + conflicts.size()
                                    + " conflicting file(s) require resolution (see 'hg resolve --list')");
                }
            }

            repository.writeDirstate(dirstate);

            String shortHex = NodeIdUtil.toHex(targetNode).substring(0, 12);
            String commitMessage = (message != null && !message.isEmpty())
                    ? message
                    : "Backed out changeset " + shortHex;

            return new CommitCommand(repository)
                    .setAuthor(author)
                    .setMessage(commitMessage)
                    .call();
        }
    }

    /**
     * Applies {@code REV}'s own diff (against its parent) directly to the working copy, for the
     * case where the working copy's parent already <em>is</em> {@code REV} — no merge is possible
     * since there is no independent "local" state to diverge.
     */
    private void applyDirectRevert(Map<String, String> targetManifest, Map<String, String> parentManifest,
            Dirstate dirstate) throws IOException {
        var allPaths = new TreeSet<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        allPaths.addAll(targetManifest.keySet());
        allPaths.addAll(parentManifest.keySet());

        for (String path : allPaths) {
            String hTarget = targetManifest.get(path);
            String hParent = parentManifest.get(path);
            if (Objects.equals(hTarget, hParent)) {
                continue; // REV never touched this path
            }

            if (hTarget != null && hParent != null) {
                // Modified by REV -> restore the pre-REV (parent) content.
                writeManifestEntryToWorkingCopy(path, hParent, dirstate, 'n');
            } else if (hTarget != null) {
                // Added by REV -> remove it.
                deleteFileFromWorkingCopy(path);
                dirstate.addEntry(path, new Dirstate.Entry('r', 0, 0, 0));
            } else {
                // Removed by REV -> restore it (freshly, from the single-parent commit's point
                // of view, since the working copy currently lacks it).
                writeManifestEntryToWorkingCopy(path, hParent, dirstate, 'a');
            }
        }
    }

    /**
     * Genuine 3-way merge for backing out an older ancestor: ancestor = {@code targetManifest}
     * (REV itself), local = {@code localManifest} (the clean working copy, i.e. {@code op1}),
     * other = {@code parentManifest} (REV's own parent — the desired reverted state). Returns the
     * list of paths left genuinely conflicted (empty if the backout applied cleanly).
     */
    private List<String> applyThreeWayBackout(Map<String, String> targetManifest, Map<String, String> localManifest,
            Map<String, String> parentManifest, Dirstate dirstate, byte[] localCommitNode, byte[] otherCommitNode,
            byte[] ancestorLinkNode) throws IOException {
        var allPaths = new TreeSet<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        allPaths.addAll(targetManifest.keySet());
        allPaths.addAll(localManifest.keySet());
        allPaths.addAll(parentManifest.keySet());

        MergeCommand mergeHelper = new MergeCommand(repository);
        MergeState pendingMergeState = null;
        List<String> conflicts = new ArrayList<>();

        for (String path : allPaths) {
            String hAnc = targetManifest.get(path);
            String hLocal = localManifest.get(path);
            String hOther = parentManifest.get(path);

            if (Objects.equals(hAnc, hOther)) {
                continue; // REV itself never touched this path -- nothing to back out
            }
            if (Objects.equals(hLocal, hOther)) {
                continue; // already at (or coincidentally matches) the desired reverted state
            }

            if (hLocal == null && hOther != null) {
                if (hAnc == null) {
                    // Genuinely new from the reverted state's perspective (neither REV nor the
                    // working copy ever had it) -> add it fresh.
                    writeManifestEntryToWorkingCopy(path, hOther, dirstate, 'a');
                }
                // else: REV had it but the working copy already independently lacks it -- local's
                // deletion wins, nothing further to do (already absent on disk).
            } else if (hLocal != null && hOther == null) {
                if (hAnc != null && Objects.equals(hLocal, hAnc)) {
                    // Working copy still has exactly REV's own version -- REV's parent doesn't
                    // want this path at all, so back it out by deleting it.
                    deleteFileFromWorkingCopy(path);
                    dirstate.addEntry(path, new Dirstate.Entry('r', 0, 0, 0));
                }
                // else: either REV never had it (working copy added it independently, keep it)
                // or the working copy diverged from REV's own version of a file the backout would
                // otherwise delete -- conservatively keep the working copy's content untouched.
            } else if (hLocal != null) {
                if (hAnc != null && Objects.equals(hLocal, hAnc)) {
                    // Working copy unchanged since REV's own version -> clean backout: adopt the
                    // reverted (parent) content outright.
                    writeManifestEntryToWorkingCopy(path, hOther, dirstate, 'n');
                } else {
                    // Genuine divergence: both REV's own effect and later history touched this
                    // path differently -- real 3-way content merge (or conflict), reusing the
                    // same hardened engine RebaseCommand/GraftCommand already use.
                    RebaseCommand.ThreeWayMergeOutcome outcome = RebaseCommand.attemptThreeWayMerge(
                            repository, mergeHelper, path, hAnc, hLocal, hOther, ancestorLinkNode,
                            localCommitNode, otherCommitNode, dirstate, pendingMergeState);
                    pendingMergeState = outcome.mergeState;
                    if (outcome.conflicted) {
                        conflicts.add(path);
                    }
                }
            }
        }

        if (!conflicts.isEmpty()) {
            pendingMergeState.write(RebaseCommand.mergeStateFile(repository));
        }
        return conflicts;
    }

    private void writeManifestEntryToWorkingCopy(String path, String hexAndFlag, Dirstate dirstate, char state)
            throws IOException {
        byte[] content = getFileRevisionContent(path, hexAndFlag);
        int mode = getModeFromManifestHex(hexAndFlag);
        writeFileToWorkingCopy(path, content, mode);
        // For a 'n' (normal, tracked-and-unchanged) entry, use real hg's own dirstate
        // ambiguous-mtime sentinel (mercurial/dirstate.py; the 32-bit "-1", i.e. 0xFFFFFFFF)
        // rather than a freshly-stat'd real mtime: this content was just fabricated by backout
        // itself (from history), not genuinely re-typed by a user at this instant, so a same-size
        // backout to different content executed fast enough to land in the same wall-clock second
        // as the file's own previous recorded state is otherwise indistinguishable from
        // "unmodified" by a naive size+mtime dirstate check alone -- the exact same race
        // confirmed live and fixed in RevertCommand (2026-09-05, backlog #39 wave 4; see its
        // javadoc and ShelveCommand's matching fix). A freshly-added ('a') entry carries no such
        // risk since StatusCommand never content-compares 'a' entries against a parent manifest.
        long time = state == 'n' ? 0xFFFFFFFFL : SafeFileIO.lastModifiedSeconds(new File(repository.getDirectory(), path));
        dirstate.addEntry(path, new Dirstate.Entry(state, mode, content.length, time));
    }

    private int getModeFromManifestHex(String hex) {
        if (hex != null && hex.length() > 40) {
            char flag = hex.charAt(40);
            if (flag == 'x') {
                return 0755;
            } else if (flag == 'l') {
                return 0120000;
            }
        }
        return 0644;
    }

    private byte[] getFileRevisionContent(String path, String hexAndFlag) throws IOException {
        File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        if (!flIdx.exists()) {
            throw new HgValidationException("Filelog not found for path: " + path);
        }
        Revlog filelog = repository.getRevlog(flIdx, flDat);
        String cleanHex = hexAndFlag.length() > 40 ? hexAndFlag.substring(0, 40) : hexAndFlag;
        int rev = filelog.findRevision(NodeIdUtil.fromHex(cleanHex));
        if (rev == -1) {
            throw new HgValidationException("File revision not found in filelog: " + path);
        }
        return filelog.getRevisionContent(rev);
    }

    private void writeFileToWorkingCopy(String path, byte[] content, int mode) throws IOException {
        File f = new File(repository.getDirectory(), path);
        f.getParentFile().mkdirs();
        if (f.exists() || Files.isSymbolicLink(f.toPath())) {
            Files.delete(f.toPath());
        }
        if (mode == 0120000) {
            String target = new String(content, StandardCharsets.UTF_8).trim();
            try {
                Files.createSymbolicLink(f.toPath(), Path.of(target));
            } catch (Exception e) {
                Files.write(f.toPath(), content);
            }
        } else {
            Files.write(f.toPath(), content);
            f.setExecutable(mode == 0755, false);
        }
    }

    private void deleteFileFromWorkingCopy(String path) {
        File f = new File(repository.getDirectory(), path);
        if (f.exists() || isSymlinkQuietly(f)) {
            f.delete();
        }
    }

    private static boolean isSymlinkQuietly(File f) {
        try {
            return Files.isSymbolicLink(f.toPath());
        } catch (Exception e) {
            return false;
        }
    }
}
