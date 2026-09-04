package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.util.SafeFileIO;
import io.github.search5.hg4j.storage.Revlog;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import io.github.search5.hg4j.errors.HgMergeConflictException;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.merge.Merge3;
import io.github.search5.hg4j.merge.MergeState;
import io.github.search5.hg4j.obsolete.HgObsMarker;
import io.github.search5.hg4j.revwalk.ChangesetGraph;
import io.github.search5.hg4j.treewalk.ManifestWalk;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Porcelain command to rebase revisions on top of another base revision.
 *
 * <p>Performs linear revision cherry-picking with clean manifest integration and dirstate
 * updating. Since 2026-09-04 this is <b>evolution-only</b>: a cherry-picked original revision is
 * never physically stripped from the changelog/manifest/filelogs -- it stays fully readable
 * forever, and only an obsolescence marker (predecessor -&gt; successor, via {@link HgObsMarker})
 * records that it has been superseded. This matches real hg's own two mutually-exclusive rebase
 * strategies (plain strip with no marker, or evolution's marker with no strip -- never both,
 * unlike this class's pre-2026-09-04 behavior) and specifically fixes {@code hg log --hidden}
 * reporting "unknown revision" for a rebased-away commit instead of showing it as hidden.
 *
 * <p>The cherry-pick path also now performs a real 3-way merge (via {@link Merge3}, the same
 * engine {@link MergeCommand} uses) whenever the destination and the revision being cherry-picked
 * have both changed the same file differently since the revision's own parent. A genuine conflict
 * writes standard {@code <<<<<<< dest ... ======= ... >>>>>>> source} markers into the working
 * file (byte-for-byte matching real hg's default {@code internal:merge} tool, verified live
 * against real hg 7.2) and leaves the file's conflict bookkeeping in {@code .hg/merge/state2} --
 * the exact same real-hg-compatible format {@link MergeCommand} already writes, so real
 * {@code hg resolve --list} can see it -- and pauses the whole rebase in a resumable state: call
 * {@link #continueRebase()} after resolving (and re-staging the resolved content on disk) to
 * finish that revision's commit and proceed to the next queued one, or {@link #abort()} to
 * discard the entire in-progress rebase and return the repository/working copy to exactly its
 * pre-rebase state.
 */
public class RebaseCommand {
    private static final Logger LOGGER = Logger.getLogger(RebaseCommand.class.getName());

    private final HgRepository repository;
    private byte[] sourceNode;
    private byte[] targetNode;
    private final List<HgHook> preRebaseHooks = new ArrayList<>();
    private final List<HgHook> postRebaseHooks = new ArrayList<>();

    /** Small, purely-metadata snapshot of a changelog revision -- author/message/date/branch. */
    private static final class RevisionMeta {
        String author;
        String message;
        long time;
        int offsetSeconds;
        String branch;
    }

    /** Result of attempting to cherry-pick a single original revision onto {@code currentBase}. */
    private static final class CherryPickOutcome {
        final byte[] originalNode;
        final byte[] newNode; // null when conflicted
        final List<String> conflictedPaths;

        CherryPickOutcome(byte[] originalNode, byte[] newNode, List<String> conflictedPaths) {
            this.originalNode = originalNode;
            this.newNode = newNode;
            this.conflictedPaths = conflictedPaths;
        }
    }

    /** On-disk (hg4j-private) record of a paused, resumable rebase -- see {@link #rebaseStateFile()}. */
    private static final class PausedRebaseState {
        byte[] originalWdParent;
        byte[] currentBase;
        List<byte[]> remaining = new ArrayList<>();
    }

    public RebaseCommand(HgRepository repository) {
        this.repository = repository;
    }

    public RebaseCommand registerPreRebaseHook(HgHook hook) {
        if (hook != null) {
            preRebaseHooks.add(hook);
        }
        return this;
    }

    public RebaseCommand registerPostRebaseHook(HgHook hook) {
        if (hook != null) {
            postRebaseHooks.add(hook);
        }
        return this;
    }

    public RebaseCommand setSource(byte[] sourceNode) {
        this.sourceNode = sourceNode;
        return this;
    }

    public RebaseCommand setTarget(byte[] targetNode) {
        this.targetNode = targetNode;
        return this;
    }

    public byte[] call() throws IOException, HgLockException, HgMergeConflictException {
        repository.clearRevlogCache();
        if (sourceNode == null || targetNode == null) {
            throw new IllegalStateException("Source and Target nodes must be specified for rebase.");
        }

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        int srcRev = NodeIdUtil.findRevisionByNodeId(changelog, sourceNode);
        int tgtRev = NodeIdUtil.findRevisionByNodeId(changelog, targetNode);

        if (srcRev == -1 || tgtRev == -1) {
            throw new HgRevisionNotFoundException("Source or target revision not found in history.");
        }

        // Collect all descendant revisions of source revision (inclusive), in ascending order --
        // ascending order guarantees every original parent is processed (or at least readable, it
        // is NEVER stripped) before any of its children.
        ChangesetGraph graph = new ChangesetGraph(changelog);
        List<Integer> revisionsToRebase = new ArrayList<>();
        revisionsToRebase.add(srcRev);
        for (int r = srcRev + 1; r < changelog.getRevisionCount(); r++) {
            if (graph.isAncestor(srcRev, r)) {
                revisionsToRebase.add(r);
            }
        }

        byte[] originalWdParent = repository.getDirstate().getParent1Node().getBytes();

        File backupDir = new File(repository.getStoreDir(), "rebase-backup");
        Map<File, File> backupMapping = new HashMap<>();

        try (HgLock storeLock = repository.lockStore();
             HgLock wlock = repository.lockWorkingCopy()) {

            try {
                // PRE_REBASE hooks trigger
                if (!preRebaseHooks.isEmpty()) {
                    Map<String, Object> ctx = new HashMap<>();
                    ctx.put("repository", repository);
                    ctx.put("sourceNode", sourceNode);
                    ctx.put("targetNode", targetNode);
                    for (HgHook hook : preRebaseHooks) {
                        if (!hook.run(ctx)) {
                            throw new HgValidationException("Rebase rejected by PRE_REBASE hook");
                        }
                    }
                }

                deleteDirRecursively(backupDir);
                backupStoreFiles(backupDir, backupMapping);

                // Create journal for crash recovery
                writeRebaseJournal(backupMapping);

                return processQueue(revisionsToRebase, targetNode, originalWdParent, backupDir);
            } catch (HgMergeConflictException e) {
                // Paused, resumable state has already been persisted by processQueue -- do NOT
                // roll back (that would discard the pause and any already-completed cherry-picks
                // in this same call()), just propagate.
                throw e;
            } catch (Exception t) {
                performPhysicalRollback(backupMapping, backupDir);
                throw t;
            }
        }
    }

    /**
     * Resumes a rebase paused by {@link #call()} (or a previous {@link #continueRebase()}) on a
     * conflict, after the caller has resolved every unresolved file reported by
     * {@code hg resolve --list} (or hg4j's own reading of {@code .hg/merge/state2}) and staged the
     * resolution on disk. Completes the currently-paused revision's commit and proceeds through
     * any further queued revisions, pausing again on the next conflict if there is one.
     *
     * @return the final rebased tip node, once every queued revision has been committed
     * @throws HgValidationException     if no rebase is in progress, or unresolved files remain
     * @throws HgMergeConflictException  if a later queued revision itself conflicts
     */
    public byte[] continueRebase() throws IOException, HgLockException, HgMergeConflictException {
        repository.clearRevlogCache();
        File stateFile = rebaseStateFile();
        if (!stateFile.exists()) {
            throw new HgValidationException("no rebase in progress");
        }

        try (HgLock storeLock = repository.lockStore();
             HgLock wlock = repository.lockWorkingCopy()) {

            PausedRebaseState state = readRebaseState();

            MergeState ms = MergeState.read(mergeStateFile());
            if (!ms.unresolvedFiles().isEmpty()) {
                throw new HgValidationException(
                        "unresolved merge conflicts (see 'hg resolve --list'): " + ms.unresolvedFiles());
            }

            File backupDir = new File(repository.getStoreDir(), "rebase-backup");
            try {
                File clIdx = new File(repository.getStoreDir(), "00changelog.i");
                File clDat = new File(repository.getStoreDir(), "00changelog.d");
                Revlog changelog = repository.getRevlog(clIdx, clDat);

                int firstOrigRev = NodeIdUtil.findRevisionByNodeId(changelog, state.remaining.get(0));
                if (firstOrigRev == -1) {
                    throw new HgRevisionNotFoundException("Paused rebase's original revision is gone from the changelog.");
                }
                byte[] firstOrigNode = changelog.getIndexRecord(firstOrigRev).getNodeId();

                RevisionMeta meta = readRevisionMeta(firstOrigRev, changelog);
                if (meta.branch != null && !meta.branch.isEmpty() && !"default".equals(meta.branch)) {
                    repository.setBranch(meta.branch);
                } else {
                    repository.setBranch("default");
                }

                CommitCommand commitCmd = new CommitCommand(repository)
                        .setAuthor(meta.author)
                        .setMessage("[rebase] " + meta.message)
                        .setDate(meta.time, meta.offsetSeconds)
                        .setSkipLockAndJournal(true);
                byte[] newNode = commitCmd.call();

                cleanMergeDir();
                try {
                    HgObsMarker.writeMarker(repository.getStoreDir(), firstOrigNode, List.of(newNode), "rebase");
                } catch (Exception e) {
                    // non-blocking
                }

                List<Integer> remainingOrigRevs = new ArrayList<>();
                for (int i = 1; i < state.remaining.size(); i++) {
                    int rev = NodeIdUtil.findRevisionByNodeId(changelog, state.remaining.get(i));
                    if (rev == -1) {
                        throw new HgRevisionNotFoundException("Paused rebase's queued revision is gone from the changelog.");
                    }
                    remainingOrigRevs.add(rev);
                }

                return processQueue(remainingOrigRevs, newNode, state.originalWdParent, backupDir);
            } catch (HgMergeConflictException e) {
                throw e;
            } catch (Exception t) {
                Map<File, File> backupMapping = reconstructBackupMapping(backupDir);
                performPhysicalRollback(backupMapping, backupDir);
                throw t;
            }
        }
    }

    /**
     * Aborts an in-progress (paused-on-conflict) rebase, mirroring real hg's
     * {@code hg rebase --abort} (verified live against real hg 7.2, 2026-09-04): every cherry-pick
     * already committed during this rebase attempt is discarded (the changelog/manifest/filelogs
     * are restored byte-for-byte to their pre-rebase content), and the working copy plus dirstate
     * are restored to exactly whatever was checked out before {@link #call()} started.
     */
    public void abort() throws IOException, HgLockException {
        repository.clearRevlogCache();
        File stateFile = rebaseStateFile();
        if (!stateFile.exists()) {
            throw new HgValidationException("no rebase in progress");
        }

        try (HgLock storeLock = repository.lockStore();
             HgLock wlock = repository.lockWorkingCopy()) {

            PausedRebaseState state = readRebaseState();
            File backupDir = new File(repository.getStoreDir(), "rebase-backup");
            Map<File, File> backupMapping = reconstructBackupMapping(backupDir);
            performPhysicalRollback(backupMapping, backupDir);

            restoreWorkingCopyCleanTo(state.originalWdParent);
            cleanMergeDir();
            deleteRebaseStateFile();
        }
    }

    // ------------------------------------------------------------------
    // Queue processing shared by call() and continueRebase()
    // ------------------------------------------------------------------

    private byte[] processQueue(List<Integer> origRevs, byte[] startBase, byte[] originalWdParent,
                                 File backupDir) throws IOException, HgLockException, HgMergeConflictException {
        byte[] currentBase = startBase;
        for (int i = 0; i < origRevs.size(); i++) {
            int origRev = origRevs.get(i);
            CherryPickOutcome outcome = cherryPickRevision(origRev, currentBase);

            if (!outcome.conflictedPaths.isEmpty()) {
                File clIdx = new File(repository.getStoreDir(), "00changelog.i");
                File clDat = new File(repository.getStoreDir(), "00changelog.d");
                Revlog changelog = repository.getRevlog(clIdx, clDat);

                List<byte[]> remainingNodes = new ArrayList<>();
                for (int j = i; j < origRevs.size(); j++) {
                    remainingNodes.add(changelog.getIndexRecord(origRevs.get(j)).getNodeId());
                }
                writeRebaseState(originalWdParent, currentBase, remainingNodes);

                throw new HgMergeConflictException(outcome.conflictedPaths,
                        "rebase halted: " + outcome.conflictedPaths.size()
                                + " conflicting file(s) require resolution (see 'hg resolve --list')");
            }

            try {
                HgObsMarker.writeMarker(repository.getStoreDir(), outcome.originalNode, List.of(outcome.newNode), "rebase");
            } catch (Exception e) {
                // non-blocking
            }
            currentBase = outcome.newNode;
        }
        return finalizeRebase(currentBase, backupDir);
    }

    private byte[] finalizeRebase(byte[] currentBase, File backupDir) throws IOException {
        checkoutNode(currentBase);

        deleteRebaseJournal();
        deleteDirRecursively(backupDir);
        deleteRebaseStateFile();

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("sourceNode", sourceNode);
        ctx.put("targetNode", targetNode);
        ctx.put("rebasedTipNode", NodeIdUtil.toHex(currentBase));
        ctx.put("repository", repository);
        for (HgHook hook : postRebaseHooks) {
            try {
                hook.run(ctx);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Post-rebase hook execution failed", e);
            }
        }

        return currentBase;
    }

    // ------------------------------------------------------------------
    // Cherry-pick of a single original revision, with real 3-way-merge conflict detection
    // ------------------------------------------------------------------

    private CherryPickOutcome cherryPickRevision(int origRev, byte[] currentBase) throws IOException, HgLockException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);
        Revlog manifestRevlog = repository.getManifestRevlog();
        MergeCommand helper = new MergeCommand(repository);

        Revlog.IndexRecord rec = changelog.getIndexRecord(origRev);
        byte[] originalNode = rec.getNodeId();
        int parent1Rev = rec.getParent1();
        int parent2Rev = rec.getParent2();

        // 1. Reset the working copy fully to the destination's currently-checked-out state.
        checkoutNode(currentBase);
        Dirstate dirstate = repository.getDirstate();

        int currentBaseRev = NodeIdUtil.findRevisionByNodeId(changelog, currentBase);
        Map<String, String> localManifest = helper.loadManifestAtCommit(changelog, manifestRevlog, currentBaseRev);
        Map<String, String> otherManifest = helper.loadManifestAtCommit(changelog, manifestRevlog, origRev);

        List<String> conflicts = new ArrayList<>();
        MergeState mergeState = null;

        if (parent2Rev != -1) {
            // The original revision is itself a merge commit. Rebasing it here means flattening it
            // into a single-parent commit onto currentBase -- a simplification predating this
            // 2026-09-04 change that this pass intentionally preserves as-is (a real recursive
            // re-merge of both original parents is out of scope for this change): its own final,
            // fully-resolved manifest is authoritative for every path it differs from currentBase
            // on, with no 3-way merge/conflict detection attempted.
            Set<String> allPaths = new TreeSet<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
            allPaths.addAll(localManifest.keySet());
            allPaths.addAll(otherManifest.keySet());
            for (String path : allPaths) {
                String hLocal = localManifest.get(path);
                String hOther = otherManifest.get(path);
                if (Objects.equals(hLocal, hOther)) {
                    continue;
                }
                if (hOther == null) {
                    deleteFileFromWorkingCopy(path);
                    dirstate.addEntry(path, new Dirstate.Entry('r', 0, 0, 0));
                } else {
                    applyResolvedContent(dirstate, helper, path, hOther, 'n');
                }
            }
        } else {
            Map<String, String> ancestorManifest = parent1Rev == -1
                    ? Collections.emptyMap()
                    : helper.loadManifestAtCommit(changelog, manifestRevlog, parent1Rev);

            Set<String> changed = new TreeSet<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
            Set<String> unionKeys = new TreeSet<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
            unionKeys.addAll(ancestorManifest.keySet());
            unionKeys.addAll(otherManifest.keySet());
            for (String path : unionKeys) {
                if (!Objects.equals(ancestorManifest.get(path), otherManifest.get(path))) {
                    changed.add(path);
                }
            }

            for (String path : changed) {
                String hAnc = ancestorManifest.get(path);
                String hOther = otherManifest.get(path);
                String hLocal = localManifest.get(path);

                if (hOther == null) {
                    // The original revision removed this path.
                    if (hLocal != null && Objects.equals(hLocal, hAnc)) {
                        deleteFileFromWorkingCopy(path);
                        dirstate.addEntry(path, new Dirstate.Entry('r', 0, 0, 0));
                    }
                    // Otherwise dest never had it, or dest's own content has diverged from the
                    // ancestor -- leave dest's file untouched rather than guess.
                    continue;
                }

                if (hLocal == null || Objects.equals(hLocal, hAnc)) {
                    // Dest doesn't have this path at all, or is unchanged since the original
                    // revision's own parent -- fast-forward to the original revision's content
                    // (the common, non-conflicting case; preserves prior behavior exactly).
                    applyResolvedContent(dirstate, helper, path, hOther, 'n');
                    continue;
                }

                if (Objects.equals(hLocal, hOther)) {
                    continue; // already identical, nothing to do
                }

                // Dest and the original revision changed this path differently since their common
                // point of reference (or both independently added it): attempt a real 3-way merge.
                byte[] baseContent = hAnc == null ? new byte[0] : helper.getFileRevisionContent(path, hAnc);
                byte[] localContent = helper.getFileRevisionContent(path, hLocal);
                byte[] otherContent = helper.getFileRevisionContent(path, hOther);

                List<String> baseLines = helper.readLines(baseContent);
                List<String> localLines = helper.readLines(localContent);
                List<String> otherLines = helper.readLines(otherContent);

                Merge3.MergeResult mergeRes = Merge3.merge(baseLines, localLines, otherLines, "dest", "source");
                StringBuilder sb = new StringBuilder();
                for (String line : mergeRes.getMergedLines()) {
                    sb.append(line).append('\n');
                }
                byte[] mergedBytes = sb.toString().getBytes(StandardCharsets.UTF_8);
                int mode = helper.getModeFromManifestHex(hLocal != null ? hLocal : hOther);

                writeFileToWorkingCopy(path, mergedBytes, mode);
                dirstate.addEntry(path, new Dirstate.Entry('m', mode, mergedBytes.length,
                        SafeFileIO.lastModifiedSeconds(new File(repository.getDirectory(), path))));

                if (mergeRes.isConflicted()) {
                    conflicts.add(path);
                    if (mergeState == null) {
                        mergeState = new MergeState();
                        mergeState.local = currentBase;
                        mergeState.other = originalNode;
                    }
                    String localKey = MergeState.getLocalKey(path);
                    File localBackup = new File(repository.getHgDir(), "merge/" + localKey);
                    localBackup.getParentFile().mkdirs();
                    Files.write(localBackup.toPath(), localContent);

                    byte[] ancestorLinkNode = parent1Rev != -1
                            ? changelog.getIndexRecord(parent1Rev).getNodeId()
                            : new byte[20];
                    mergeState.addMergedFile(path, localKey, path, path, cleanHexOf(hAnc), path, cleanHexOf(hOther),
                            flagOf(hLocal != null ? hLocal : hOther));
                    mergeState.stateExtras
                            .computeIfAbsent(path, k -> new LinkedHashMap<>())
                            .put("ancestorlinknode", NodeIdUtil.toHex(ancestorLinkNode));
                }
            }
        }

        dirstate.setParents(currentBase, new byte[20]);
        repository.writeDirstate(dirstate);

        if (!conflicts.isEmpty()) {
            mergeState.write(mergeStateFile());
            return new CherryPickOutcome(originalNode, null, conflicts);
        }

        // No leftover conflict state from a previous, now-superseded attempt at this same revision.
        cleanMergeDir();

        RevisionMeta meta = readRevisionMeta(origRev, changelog);
        if (meta.branch != null && !meta.branch.isEmpty() && !"default".equals(meta.branch)) {
            repository.setBranch(meta.branch);
        } else {
            repository.setBranch("default");
        }

        CommitCommand commitCmd = new CommitCommand(repository)
                .setAuthor(meta.author)
                .setMessage("[rebase] " + meta.message)
                .setDate(meta.time, meta.offsetSeconds)
                .setSkipLockAndJournal(true);

        byte[] newNode = commitCmd.call();
        return new CherryPickOutcome(originalNode, newNode, Collections.emptyList());
    }

    private void applyResolvedContent(Dirstate dirstate, MergeCommand helper, String path, String hexFlag, char state)
            throws IOException {
        byte[] content = helper.getFileRevisionContent(path, hexFlag);
        int mode = helper.getModeFromManifestHex(hexFlag);
        writeFileToWorkingCopy(path, content, mode);
        dirstate.addEntry(path, new Dirstate.Entry(state, mode, content.length,
                SafeFileIO.lastModifiedSeconds(new File(repository.getDirectory(), path))));
    }

    private static String cleanHexOf(String manifestHex) {
        if (manifestHex == null) {
            return MergeState.NULL_HEX;
        }
        return manifestHex.length() > 40 ? manifestHex.substring(0, 40) : manifestHex;
    }

    private static String flagOf(String manifestHex) {
        if (manifestHex != null && manifestHex.length() > 40) {
            return String.valueOf(manifestHex.charAt(40));
        }
        return "";
    }

    private RevisionMeta readRevisionMeta(int rev, Revlog changelog) throws IOException {
        RevisionMeta meta = new RevisionMeta();

        byte[] clContent = changelog.getRevisionContent(rev);
        String clText = new String(clContent, StandardCharsets.UTF_8);
        String[] clLines = clText.split("\n");

        meta.author = clLines[1];

        String dateLine = clLines[2].trim();
        long time = 0;
        int offset = 0;
        int firstSpace = dateLine.indexOf(' ');
        if (firstSpace != -1) {
            try {
                time = Long.parseLong(dateLine.substring(0, firstSpace));
            } catch (NumberFormatException ignored) {
            }
            int secondSpace = dateLine.indexOf(' ', firstSpace + 1);
            if (secondSpace != -1) {
                try {
                    offset = Integer.parseInt(dateLine.substring(firstSpace + 1, secondSpace));
                } catch (NumberFormatException ignored) {
                }
            } else {
                try {
                    offset = Integer.parseInt(dateLine.substring(firstSpace + 1));
                } catch (NumberFormatException ignored) {
                }
            }
        } else {
            try {
                time = Long.parseLong(dateLine);
            } catch (NumberFormatException ignored) {
            }
        }
        meta.time = time;
        meta.offsetSeconds = offset;
        meta.branch = CommitCommand.getBranchOfRevision(changelog, rev);

        int msgStartIdx = 3;
        while (msgStartIdx < clLines.length && !clLines[msgStartIdx].isEmpty()) {
            msgStartIdx++;
        }
        msgStartIdx++; // Skip empty line separator

        StringBuilder msgSb = new StringBuilder();
        for (int i = msgStartIdx; i < clLines.length; i++) {
            if (i > msgStartIdx) msgSb.append("\n");
            msgSb.append(clLines[i]);
        }
        meta.message = msgSb.toString();

        return meta;
    }

    // ------------------------------------------------------------------
    // Working copy helpers
    // ------------------------------------------------------------------

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
                // Fallback for OS/filesystem limits (e.g. target exceeding PATH_MAX) or missing
                // privilege -- verified against real hg 7.2, which does the same.
                Files.write(f.toPath(), content);
            }
        } else {
            Files.write(f.toPath(), content);
            f.setExecutable(mode == 0755, false);
        }
    }

    private void deleteFileFromWorkingCopy(String path) throws IOException {
        File f = new File(repository.getDirectory(), path);
        if (f.exists() || Files.isSymbolicLink(f.toPath())) {
            Files.delete(f.toPath());
        }
    }

    /**
     * Restores the working copy and dirstate to exactly what {@code targetNode} had checked out,
     * removing any path that is tracked now but isn't part of {@code targetNode}'s manifest. Unlike
     * {@link #checkoutNode}/{@link #applyManifestToWorkingCopy} (which only ever add/overwrite
     * paths, since every other caller only ever moves forward across cherry-picks that never leave
     * stray added paths behind), {@link #abort()} must also clean up any path that only exists
     * because of the aborted rebase's already-applied-but-uncommitted cherry-pick.
     */
    private void restoreWorkingCopyCleanTo(byte[] targetNode) throws IOException {
        Map<String, String> targetManifest;
        if (targetNode == null || NodeIdUtil.isAllZero(targetNode)) {
            targetManifest = Collections.emptyMap();
        } else {
            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");
            Revlog changelog = repository.getRevlog(clIdx, clDat);
            int rev = NodeIdUtil.findRevisionByNodeId(changelog, targetNode);
            if (rev == -1) {
                throw new HgRevisionNotFoundException("Pre-rebase working directory parent not found: "
                        + NodeIdUtil.toHex(targetNode));
            }
            targetManifest = new MergeCommand(repository).loadManifestAtCommit(changelog, repository.getManifestRevlog(), rev);
        }

        Dirstate dirstate = repository.getDirstate();
        Set<String> allPaths = new TreeSet<>(NodeIdUtil.UTF8_STRING_COMPARATOR);
        allPaths.addAll(dirstate.getEntries().keySet());
        allPaths.addAll(targetManifest.keySet());

        MergeCommand helper = new MergeCommand(repository);
        for (String path : allPaths) {
            String hexFlag = targetManifest.get(path);
            if (hexFlag == null) {
                deleteFileFromWorkingCopy(path);
                dirstate.removeEntry(path);
            } else {
                byte[] content = helper.getFileRevisionContent(path, hexFlag);
                int mode = helper.getModeFromManifestHex(hexFlag);
                writeFileToWorkingCopy(path, content, mode);
                dirstate.addEntry(path, new Dirstate.Entry('n', mode, content.length,
                        SafeFileIO.lastModifiedSeconds(new File(repository.getDirectory(), path))));
            }
        }

        byte[] parent1 = targetNode == null ? new byte[20] : targetNode;
        dirstate.setParents(parent1, new byte[20]);
        repository.writeDirstate(dirstate);
    }

    private void applyManifestToWorkingCopy(byte[] manifestNode) throws IOException {
        Revlog manifest = repository.getManifestRevlog();

        int mfRev = NodeIdUtil.findRevisionByNodeId(manifest, manifestNode);
        if (mfRev == -1) {
            throw new HgRevisionNotFoundException("Manifest revision not found for node: " + NodeIdUtil.toHex(manifestNode));
        }

        Map<String, String> entries = new HashMap<>();
        ManifestWalk mw = new ManifestWalk(repository, manifestNode);
        while (mw.next()) {
            ManifestWalk.Entry entry = mw.getEntry();
            String flag = entry.isExecutable() ? "x" : (entry.isSymlink() ? "l" : "");
            entries.put(entry.getPath(), entry.getNodeIdHex() + flag);
        }

        Dirstate dirstate = repository.getDirstate();

        for (Map.Entry<String, String> entry : entries.entrySet()) {
            String path = entry.getKey();
            String nodeWithFlags = entry.getValue();
            String hexNode = nodeWithFlags.substring(0, 40);
            String flags = nodeWithFlags.length() > 40 ? nodeWithFlags.substring(40) : "";

            File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
            File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
            Revlog filelog = repository.getRevlog(flIdx, flDat);

            int fileRev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(hexNode));
            byte[] fileContent = filelog.getRevisionContent(fileRev);

            File diskFile = new File(repository.getDirectory(), path);
            diskFile.getParentFile().mkdirs();
            if (diskFile.exists() || Files.isSymbolicLink(diskFile.toPath())) {
                Files.delete(diskFile.toPath());
            }

            int mode = 0644;
            if (flags.contains("l")) {
                mode = 0120000;
                String target = new String(fileContent, StandardCharsets.UTF_8).trim();
                try {
                    Files.createSymbolicLink(diskFile.toPath(), Path.of(target));
                } catch (Exception e) {
                    Files.write(diskFile.toPath(), fileContent);
                }
            } else {
                Files.write(diskFile.toPath(), fileContent);
                if (flags.contains("x")) {
                    diskFile.setExecutable(true, false);
                    mode = 0755;
                } else {
                    diskFile.setExecutable(false, false);
                    mode = 0644;
                }
            }

            dirstate.addEntry(path, new Dirstate.Entry('n', mode, fileContent.length, SafeFileIO.lastModifiedSeconds(diskFile)));
        }

        repository.writeDirstate(dirstate);
    }

    private void checkoutNode(byte[] node) throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        int rev = NodeIdUtil.findRevisionByNodeId(changelog, node);
        byte[] clContent = changelog.getRevisionContent(rev);
        String clText = new String(clContent, StandardCharsets.UTF_8);
        String firstLine = clText.split("\n")[0];
        byte[] mfNode = NodeIdUtil.fromHex(firstLine.trim().substring(0, 40));

        applyManifestToWorkingCopy(mfNode);

        Dirstate dirstate = repository.getDirstate();
        dirstate.setParents(node, new byte[20]);
        repository.writeDirstate(dirstate);
    }

    // ------------------------------------------------------------------
    // Physical store backup / rollback (crash-safety net for the whole rebase attempt)
    // ------------------------------------------------------------------

    private void backupStoreFiles(File backupDir, Map<File, File> backupMapping) throws IOException {
        backupDir.mkdirs();
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
        File mfDat = new File(repository.getStoreDir(), "00manifest.d");

        copyToBackup(clIdx, backupDir, backupMapping);
        copyToBackup(clDat, backupDir, backupMapping);
        copyToBackup(mfIdx, backupDir, backupMapping);
        copyToBackup(mfDat, backupDir, backupMapping);

        // Every cherry-pick commit is written with CommitCommand.setSkipLockAndJournal(true) (this
        // class owns the transaction instead), so a filelog a cherry-pick might append a revision
        // to needs to be backed up here too. Rather than compute exactly which paths the queued
        // revisions touch (redundant with the per-revision diffing cherryPickRevision already does,
        // and easy to under-approximate), just back up every filelog fncache knows about -- a plain
        // byte copy, safe even for a corrupt/stale entry (unlike loading it as a Revlog).
        File fncacheFile = new File(repository.getStoreDir(), "fncache");
        if (fncacheFile.exists()) {
            copyToBackup(fncacheFile, backupDir, backupMapping);
            List<String> fncachePaths = Files.readAllLines(fncacheFile.toPath(), StandardCharsets.UTF_8);
            for (String relPath : fncachePaths) {
                if (relPath.isEmpty() || !relPath.endsWith(".i")) {
                    continue;
                }
                File flIdx = new File(repository.getStoreDir(), relPath);
                File flDat = new File(repository.getStoreDir(), relPath.substring(0, relPath.length() - 2) + ".d");
                copyToBackup(flIdx, backupDir, backupMapping);
                copyToBackup(flDat, backupDir, backupMapping);
            }
        }
    }

    private void copyToBackup(File sourceFile, File backupDir, Map<File, File> backupMapping) throws IOException {
        if (!sourceFile.exists()) return;
        String relPath = repository.getStoreDir().toPath().relativize(sourceFile.toPath()).toString();
        File targetFile = new File(backupDir, relPath);
        targetFile.getParentFile().mkdirs();
        Files.copy(sourceFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        backupMapping.put(sourceFile, targetFile);
    }

    /**
     * Rebuilds the {@code storeFile -> backupCopy} mapping {@link #copyToBackup} would have
     * produced, purely from {@code backupDir}'s own layout -- needed because {@link #abort()}/
     * {@link #continueRebase()} may run on a brand-new {@link RebaseCommand} instance (a fresh
     * process, even) that never ran the original {@link #call()} and so never populated the
     * in-memory mapping itself.
     */
    private Map<File, File> reconstructBackupMapping(File backupDir) throws IOException {
        Map<File, File> mapping = new HashMap<>();
        if (!backupDir.exists()) {
            return mapping;
        }
        Path base = backupDir.toPath();
        try (var stream = Files.walk(base)) {
            List<Path> files = stream.filter(Files::isRegularFile).toList();
            for (Path p : files) {
                Path rel = base.relativize(p);
                File orig = new File(repository.getStoreDir(), rel.toString().replace('\\', '/'));
                mapping.put(orig, p.toFile());
            }
        }
        return mapping;
    }

    private void deleteDirRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirRecursively(child);
                }
            }
        }
        file.delete();
    }

    private void writeRebaseJournal(Map<File, File> backupMapping) throws IOException {
        File journalFile = new File(repository.getStoreDir(), "journal");
        Files.deleteIfExists(journalFile.toPath());

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<File, File> entry : backupMapping.entrySet()) {
            File origFile = entry.getKey();
            File backupFile = entry.getValue();

            String origRel = repository.getHgDir().toPath().relativize(origFile.toPath()).toString().replace('\\', '/');
            String backupRel = repository.getHgDir().toPath().relativize(backupFile.toPath()).toString().replace('\\', '/');

            sb.append("backup ").append(origRel).append("\t").append(backupRel).append("\n");
        }
        Files.writeString(journalFile.toPath(), sb.toString(), StandardCharsets.UTF_8);
        try (var fc = java.nio.channels.FileChannel.open(journalFile.toPath(), StandardOpenOption.WRITE)) {
            fc.force(true);
        }
    }

    private void deleteRebaseJournal() throws IOException {
        File journalFile = new File(repository.getStoreDir(), "journal");
        Files.deleteIfExists(journalFile.toPath());
    }

    private void performPhysicalRollback(Map<File, File> backupMapping, File backupDir) {
        for (Map.Entry<File, File> entry : backupMapping.entrySet()) {
            File originalFile = entry.getKey();
            File backupCopy = entry.getValue();
            if (backupCopy.exists()) {
                try {
                    originalFile.getParentFile().mkdirs();
                    Files.copy(backupCopy.toPath(), originalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ignored) {}
            } else {
                try {
                    Files.deleteIfExists(originalFile.toPath());
                } catch (Exception ignored) {}
            }
        }
        try {
            deleteRebaseJournal();
        } catch (Exception ignored) {}
        deleteDirRecursively(backupDir);
        repository.clearRevlogCache();
    }

    // ------------------------------------------------------------------
    // Paused-rebase state persistence (own hg4j-private format; NOT real hg's own binary
    // .hg/rebasestate -- mid-flight resume interop with real hg's own `hg rebase --continue` is
    // not a goal, only the end state (visible-vs-hidden revisions, obsstore, conflict markers via
    // .hg/merge/state2) needs to round-trip through real hg, and that's handled elsewhere)
    // ------------------------------------------------------------------

    private File rebaseStateFile() {
        return new File(repository.getHgDir(), "rebasestate-hg4j");
    }

    private File mergeStateFile() {
        return new File(repository.getHgDir(), "merge/state2");
    }

    /**
     * Fully clears {@code .hg/merge} (both {@code state2} and any per-file local-content backups
     * -- and, importantly, real hg's own legacy {@code .hg/merge/state} v1 file, which a real
     * {@code hg resolve --mark} run against an hg4j-paused rebase writes alongside {@code state2}).
     * A plain {@link MergeState#clean} of just {@code state2} is not enough: real hg's own {@code
     * mergestate.read()} falls back to parsing the v1 {@code state} file whenever {@code state2}
     * is absent, so leaving it behind made a completed rebase's {@code hg resolve --list} keep
     * reporting the just-resolved file as {@code R <path>} instead of nothing at all (caught live
     * against real hg 7.2 in {@code RebaseRealHgInteropTest}).
     */
    private void cleanMergeDir() {
        deleteDirRecursively(new File(repository.getHgDir(), "merge"));
    }

    private void writeRebaseState(byte[] originalWdParent, byte[] currentBase, List<byte[]> remaining) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("originalWdParent=").append(NodeIdUtil.toHex(originalWdParent)).append('\n');
        sb.append("currentBase=").append(NodeIdUtil.toHex(currentBase)).append('\n');
        sb.append("remaining=");
        for (int i = 0; i < remaining.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(NodeIdUtil.toHex(remaining.get(i)));
        }
        sb.append('\n');
        Files.writeString(rebaseStateFile().toPath(), sb.toString(), StandardCharsets.UTF_8);
    }

    private PausedRebaseState readRebaseState() throws IOException {
        PausedRebaseState state = new PausedRebaseState();
        for (String line : Files.readAllLines(rebaseStateFile().toPath(), StandardCharsets.UTF_8)) {
            int eq = line.indexOf('=');
            if (eq == -1) continue;
            String key = line.substring(0, eq);
            String val = line.substring(eq + 1);
            switch (key) {
                case "originalWdParent" -> state.originalWdParent = NodeIdUtil.fromHex(val);
                case "currentBase" -> state.currentBase = NodeIdUtil.fromHex(val);
                case "remaining" -> {
                    if (!val.isEmpty()) {
                        for (String hex : val.split(",")) {
                            state.remaining.add(NodeIdUtil.fromHex(hex));
                        }
                    }
                }
                default -> { /* forward-compatible: ignore unknown keys */ }
            }
        }
        return state;
    }

    private void deleteRebaseStateFile() throws IOException {
        Files.deleteIfExists(rebaseStateFile().toPath());
    }
}
