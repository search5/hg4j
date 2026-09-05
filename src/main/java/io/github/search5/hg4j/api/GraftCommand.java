package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.util.SafeFileIO;
import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgMergeConflictException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.merge.MergeState;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import io.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.lib.HgLock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Graft command (equivalent to git cherry-pick) for Mercurial repositories.
 * Copies the changes of a source revision and commits them on top of the current parent.
 *
 * <p>Since 2026-09-05 this performs a real 3-way merge (via {@link
 * RebaseCommand#attemptThreeWayMerge}, the same hardened engine {@link RebaseCommand}'s own
 * cherry-pick path uses since 2026-09-04) whenever the destination and the graft source have both
 * changed the same file differently since their common ancestor -- verified live against real
 * {@code hg graft} 7.2, which performs the exact same kind of 3-way merge here rather than
 * blindly taking the source's content (this class's own pre-2026-09-05 behavior, a real data-loss
 * bug: grafting a source revision onto a destination that had independently diverged on the same
 * path silently discarded the destination's content instead of merging it or flagging a
 * conflict). A genuine conflict writes standard {@code <<<<<<< dest ... ======= ...
 * >>>>>>> source} markers into the working file and leaves the file's conflict bookkeeping in
 * {@code .hg/merge/state2} (the same real-hg-compatible format {@link MergeCommand} and {@link
 * RebaseCommand} already write), and pauses the graft in a resumable state: call {@link
 * #continueGraft()} after resolving (and re-staging the resolved content on disk, same as {@code
 * hg resolve --mark}) to finish the commit, or {@link #abort()} to discard the in-progress graft
 * and return the working copy/dirstate to exactly their pre-graft state. Like {@link
 * RebaseCommand}, this is a purely hg4j-private pause/continue/abort protocol -- mid-flight
 * interop with real hg's own {@code hg graft --continue}/{@code --abort} bookkeeping is not a
 * goal, only the end state (conflict markers via {@code .hg/merge/state2}) needs to round-trip
 * through real hg.
 *
 * <p>Also since 2026-09-05: this class no longer writes an obsolescence marker linking the graft
 * source to the new grafted commit. Verified live against real {@code hg graft} 7.2: a plain
 * {@code hg graft REV} does NOT create any obsmarker at all -- the source revision stays fully
 * visible in a plain {@code hg log} right alongside the new grafted duplicate (graft is a copy
 * operation, not a rewrite). Writing one anyway (this class's own pre-2026-09-05 behavior) made
 * the source spuriously disappear from a plain {@code hg log} once read back by real hg -- a real
 * bug, verified by manually reproducing the exact marker real hg 7.2 itself refused to write and
 * observing it hide the precursor.
 */
public class GraftCommand {
    private static final Logger LOGGER = Logger.getLogger(GraftCommand.class.getName());
    private final HgRepository repository;
    private String sourceRevision;
    private final List<HgHook> postGraftHooks = new ArrayList<>();

    public GraftCommand(HgRepository repository) {
        this.repository = repository;
    }

    public GraftCommand setSource(String sourceRevision) {
        this.sourceRevision = sourceRevision;
        return this;
    }

    public GraftCommand registerPostGraftHook(HgHook hook) {
        if (hook != null) {
            postGraftHooks.add(hook);
        }
        return this;
    }

    /** Small, purely-metadata snapshot of the graft source's own changelog revision, plus the
     * list of paths it touched -- shared by {@link #call()} and {@link #continueGraft()} (the
     * latter re-derives it deterministically from the same, unchanged source revision rather than
     * persisting it, mirroring {@link RebaseCommand}'s own paused-state design). */
    private static final class GraftMeta {
        String author = "graft";
        String message = "";
        Long dateSecs;
        Integer dateOffset;
        List<String> filesModified = new ArrayList<>();
    }

    private GraftMeta parseGraftMeta(Revlog changelog, int rev) throws IOException {
        GraftMeta meta = new GraftMeta();
        byte[] origClContent = changelog.getRevisionContent(rev);
        String origClText = new String(origClContent, StandardCharsets.UTF_8);
        String[] origClLines = origClText.split("\n");

        if (origClLines.length > 1) {
            meta.author = origClLines[1].trim();
        }
        // Real hg (mercurial/cmdutil.py graft logic, verified against `hg graft` v7.2) copies
        // the source changeset's exact date onto the grafted commit unless --currentdate/--date
        // is given; parse "secs offset[ extra]" from the 3rd changelog line (date line) so we can
        // pass it through to CommitCommand instead of letting it default to "now".
        if (origClLines.length > 2) {
            String[] dateParts = origClLines[2].trim().split(" ");
            if (dateParts.length >= 2) {
                try {
                    meta.dateSecs = Long.parseLong(dateParts[0]);
                    meta.dateOffset = Integer.parseInt(dateParts[1]);
                } catch (NumberFormatException ignored) {
                    meta.dateSecs = null;
                    meta.dateOffset = null;
                }
            }
        }
        StringBuilder msgBuilder = new StringBuilder();
        int msgStartIdx = -1;
        for (int i = 3; i < origClLines.length; i++) {
            if (origClLines[i].isEmpty()) {
                msgStartIdx = i + 1;
                break;
            }
            meta.filesModified.add(origClLines[i]);
        }
        if (msgStartIdx != -1) {
            for (int i = msgStartIdx; i < origClLines.length; i++) {
                if (msgBuilder.length() > 0) msgBuilder.append("\n");
                msgBuilder.append(origClLines[i]);
            }
        }
        // Real hg only appends "(grafted from CHANGESETHASH)" to the description when --log is
        // passed (`hg help graft`); a plain `hg graft REV` leaves the description byte-for-byte
        // equal to the source's message (verified with `hg log -r tip` against real hg v7.2).
        // This command doesn't implement --log, so the description is always left untouched.
        meta.message = msgBuilder.toString();
        return meta;
    }

    /**
     * Executes the graft operation.
     * Extracts source file contents and commits them to the current parent, updating the workspace.
     *
     * @return hex node ID of the newly grafted commit
     * @throws IOException if history traversal or file write fails
     * @throws HgMergeConflictException if the destination and the source diverged on the same
     *         file and a real 3-way merge could not resolve it cleanly -- see this class's own
     *         javadoc for the {@link #continueGraft()}/{@link #abort()} resumption protocol
     */
    public String call() throws IOException, HgLockException, HgMergeConflictException {
        if (sourceRevision == null || sourceRevision.isEmpty()) {
            throw new IllegalArgumentException("Source revision must be specified for graft");
        }
        repository.clearRevlogCache();

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
        File mfDat = new File(repository.getStoreDir(), "00manifest.d");
        Revlog manifestRevlog = repository.getRevlog(mfIdx, mfDat);

        byte[] origNode = NodeIdUtil.resolveRevision(changelog, sourceRevision);
        if (origNode == null) {
            throw new IOException("Graft source revision not found: " + sourceRevision);
        }
        int origRev = changelog.findRevision(origNode);

        GraftMeta meta = parseGraftMeta(changelog, origRev);
        Map<String, String> originalManifest = getManifestForCommit(changelog, manifestRevlog, origNode);

        int parent1Rev = changelog.getIndexRecord(origRev).getParent1();
        byte[] parent1Node = parent1Rev == -1 ? null : changelog.getIndexRecord(parent1Rev).getNodeId();
        Map<String, String> ancestorManifest = getManifestForCommit(changelog, manifestRevlog, parent1Node);

        // Acquire lock explicitly to restore files and commit safely in a transaction
        try (HgLock storeLock = repository.lockStore();
             HgLock wlock = repository.lockWorkingCopy()) {

            Dirstate dirstate = repository.getDirstate();
            byte[] destParentNode = dirstate.getParent1();
            Map<String, String> destManifest = getManifestForCommit(changelog, manifestRevlog, destParentNode);

            MergeCommand mergeHelper = new MergeCommand(repository);
            List<String> conflictedPaths = new ArrayList<>();
            MergeState pendingMergeState = null;

            // 2. For each modified file in the source revision, copy contents and write to working copy.
            // The dirstate is also updated here (mirroring AddCommand/RemoveCommand's own bookkeeping):
            // a file the source revision touched but that isn't already tracked on the current branch
            // must be marked 'a' (added) or CommitCommand's WorkingDirTreeIterator reports it as
            // untracked ('?') and silently drops it from the grafted commit's manifest; a file the
            // source revision removed must be marked 'r' (removed) or CommitCommand throws
            // "Tracked file not found on disk" once the physical file is deleted below.
            for (String path : meta.filesModified) {
                String hexAndFlag = originalManifest.get(path);
                Dirstate.Entry existingEntry = dirstate.getEntries().get(path);
                if (hexAndFlag == null) {
                    // File deleted in source revision -> delete in working copy too
                    File wFile = new File(repository.getDirectory(), path);
                    if (wFile.exists()) {
                        wFile.delete();
                    }
                    if (existingEntry != null) {
                        if (existingEntry.getState() == 'a') {
                            dirstate.removeEntry(path);
                        } else {
                            dirstate.addEntry(path, new Dirstate.Entry('r', 0, 0, 0));
                        }
                    }
                    continue;
                }

                String hAnc = ancestorManifest.get(path);
                String hLocalDest = destManifest.get(path);

                if (Objects.equals(hLocalDest, hexAndFlag)) {
                    continue; // dest already matches the source -- nothing to do
                }

                if (hLocalDest != null && !Objects.equals(hLocalDest, hAnc)) {
                    // Genuine divergence: the destination changed (or independently added) this
                    // path differently than the graft source did, since their common ancestor --
                    // attempt a real 3-way merge instead of blindly overwriting dest's content
                    // (verified against real `hg graft` 7.2 -- see this class's own javadoc).
                    byte[] ancestorLinkNode = parent1Node != null ? parent1Node : new byte[20];
                    RebaseCommand.ThreeWayMergeOutcome mergeOutcome = RebaseCommand.attemptThreeWayMerge(
                            repository, mergeHelper, path, hAnc, hLocalDest, hexAndFlag,
                            ancestorLinkNode, destParentNode, origNode, dirstate, pendingMergeState);
                    pendingMergeState = mergeOutcome.mergeState;
                    if (mergeOutcome.conflicted) {
                        conflictedPaths.add(path);
                    }
                    continue;
                }

                // Fast path: dest doesn't have this path at all, or is unchanged since the graft
                // source's own parent -- the common, non-conflicting case (preserves prior
                // behavior exactly).
                String fileHex = hexAndFlag.substring(0, 40);
                // Manifest entries are "<40-hex-nodeid><flag>": flag is "x" (executable),
                // "l" (symlink) or empty (verified against `hg manifest --debug`, e.g.
                // "<hash> 755 * script.sh" / "<hash> 644 @ link.txt"). CommitCommand.getRevisionContent
                // only needs the node hex, but real `hg graft` (verified against `hg graft` v7.2:
                // grafting an executable script or a symlink onto a branch that never had it
                // restores the exact mode/symlink-ness in the working copy) also restores this
                // flag onto the copied working-copy file -- previously dropped here, which meant a
                // grafted executable script or symlink silently lost its mode/symlink-ness.
                String flag = hexAndFlag.length() > 40 ? hexAndFlag.substring(40) : "";
                boolean symlink = flag.contains("l");
                boolean executable = flag.contains("x");
                byte[] fileContent = getFileRevisionContent(repository, path, fileHex);

                File wFile = new File(repository.getDirectory(), path);
                wFile.getParentFile().mkdirs();
                if (symlink) {
                    if (wFile.exists() || Files.isSymbolicLink(wFile.toPath())) {
                        Files.delete(wFile.toPath());
                    }
                    String target = new String(fileContent, StandardCharsets.UTF_8).trim();
                    try {
                        Files.createSymbolicLink(wFile.toPath(), java.nio.file.Path.of(target));
                    } catch (Exception e) {
                        Files.write(wFile.toPath(), fileContent);
                    }
                } else {
                    Files.write(wFile.toPath(), fileContent);
                    wFile.setExecutable(executable, false);
                }

                if (existingEntry == null) {
                    int mode = symlink ? 0120000 : (executable ? 0755 : 0644);
                    int size = fileContent.length;
                    long time = SafeFileIO.lastModifiedSeconds(wFile);
                    dirstate.addEntry(path, new Dirstate.Entry('a', mode, size, time));
                }
            }
            repository.writeDirstate(dirstate);

            if (!conflictedPaths.isEmpty()) {
                pendingMergeState.write(RebaseCommand.mergeStateFile(repository));
                writeGraftState(origNode, destParentNode);
                throw new HgMergeConflictException(conflictedPaths,
                        "graft halted: " + conflictedPaths.size()
                                + " conflicting file(s) require resolution (see 'hg resolve --list')");
            }

            byte[] newCommitNode = commitGraftedRevision(origNode, meta);
            return NodeIdUtil.toHex(newCommitNode);
        }
    }

    /**
     * Resumes a graft paused by {@link #call()} on a conflict, after the caller has resolved
     * every unresolved file reported by {@code hg resolve --list} (or hg4j's own reading of
     * {@code .hg/merge/state2}) and staged the resolution on disk (same as a real {@code hg
     * resolve --mark} session). Completes the paused revision's commit.
     *
     * @return hex node ID of the newly grafted commit
     * @throws HgValidationException if no graft is in progress, or unresolved files remain
     */
    public String continueGraft() throws IOException, HgLockException {
        repository.clearRevlogCache();
        File stateFile = graftStateFile();
        if (!stateFile.exists()) {
            throw new HgValidationException("no graft in progress");
        }

        try (HgLock storeLock = repository.lockStore();
             HgLock wlock = repository.lockWorkingCopy()) {

            PausedGraftState state = readGraftState();

            MergeState ms = MergeState.read(RebaseCommand.mergeStateFile(repository));
            if (!ms.unresolvedFiles().isEmpty()) {
                throw new HgValidationException(
                        "unresolved merge conflicts (see 'hg resolve --list'): " + ms.unresolvedFiles());
            }

            File clIdx = new File(repository.getStoreDir(), "00changelog.i");
            File clDat = new File(repository.getStoreDir(), "00changelog.d");
            Revlog changelog = repository.getRevlog(clIdx, clDat);
            int origRev = changelog.findRevision(state.origNode);
            if (origRev == -1) {
                throw new HgRevisionNotFoundException("Paused graft's source revision is gone from the changelog.");
            }

            GraftMeta meta = parseGraftMeta(changelog, origRev);
            byte[] newCommitNode = commitGraftedRevision(state.origNode, meta);

            RebaseCommand.cleanMergeDir(repository);
            deleteGraftStateFile();

            return NodeIdUtil.toHex(newCommitNode);
        }
    }

    /**
     * Aborts an in-progress (paused-on-conflict) graft, mirroring real hg's {@code hg graft
     * --abort}: discards every working-copy/dirstate change the paused attempt already staged
     * (nothing was ever appended to the changelog/manifest/filelogs for a paused graft -- that
     * only happens once {@link #call()}/{@link #continueGraft()} actually commits) and restores
     * the working copy/dirstate to exactly what was checked out before {@link #call()} started.
     */
    public void abort() throws IOException, HgLockException {
        repository.clearRevlogCache();
        File stateFile = graftStateFile();
        if (!stateFile.exists()) {
            throw new HgValidationException("no graft in progress");
        }

        try (HgLock storeLock = repository.lockStore();
             HgLock wlock = repository.lockWorkingCopy()) {

            PausedGraftState state = readGraftState();
            RebaseCommand.restoreWorkingCopyCleanTo(repository, state.destParentNode);
            RebaseCommand.cleanMergeDir(repository);
            deleteGraftStateFile();
        }
    }

    /**
     * Delegates the actual changelog/manifest/filelog write to {@link CommitCommand} (ensuring
     * fncache registry, phase draft transition, etc. are fully handled) and, since {@code
     * CommitCommand} is called with {@code setSkipLockAndJournal(true)} (this class owns the
     * transaction instead, matching {@link RebaseCommand}/{@link HisteditCommand}), wraps it in
     * its own crash-safety journal/backup so a real crash mid-commit can be rolled back on the
     * next repository open via {@link HgRepository#checkAndPerformAutoRollback()} -- {@code
     * GraftCommand} previously had no such protection at all (unlike every sibling
     * history-rewriting command), a real gap this pass closes alongside the 3-way-merge fix.
     */
    private byte[] commitGraftedRevision(byte[] origNode, GraftMeta meta) throws IOException, HgLockException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
        File mfDat = new File(repository.getStoreDir(), "00manifest.d");

        Map<File, Long> fileSizes = new LinkedHashMap<>();
        File dirstateFile = new File(repository.getDirectory(), ".hg/dirstate");
        byte[] dirstateBackup = dirstateFile.exists() ? Files.readAllBytes(dirstateFile.toPath()) : null;
        File journalFile = new File(repository.getStoreDir(), "journal");
        File dirstateBackupFile = new File(repository.getDirectory(), ".hg/dirstate.backup");

        Files.deleteIfExists(journalFile.toPath());
        if (dirstateFile.exists()) {
            Files.copy(dirstateFile.toPath(), dirstateBackupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            appendToJournal(journalFile, "dirstate");
        }
        recordAndJournal(clIdx, fileSizes, journalFile);
        recordAndJournal(clDat, fileSizes, journalFile);
        recordAndJournal(mfIdx, fileSizes, journalFile);
        recordAndJournal(mfDat, fileSizes, journalFile);
        for (String path : meta.filesModified) {
            File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
            File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
            recordAndJournal(flIdx, fileSizes, journalFile);
            recordAndJournal(flDat, fileSizes, journalFile);
        }

        try {
            // Delegate execution to CommitCommand to ensure fncache registry, phase draft
            // transition, and hooks are fully executed.
            CommitCommand commitCmd = new CommitCommand(repository);
            commitCmd.setAuthor(meta.author);
            commitCmd.setMessage(meta.message);
            commitCmd.setSkipLockAndJournal(true);
            if (meta.dateSecs != null && meta.dateOffset != null) {
                commitCmd.setDate(meta.dateSecs, meta.dateOffset);
            }

            byte[] newCommitNode = commitCmd.call();

            // POST_GRAFT hooks trigger
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("sourceRevision", NodeIdUtil.toHex(origNode));
            ctx.put("graftedNode", NodeIdUtil.toHex(newCommitNode));
            ctx.put("repository", repository);
            for (HgHook hook : postGraftHooks) {
                try {
                    hook.run(ctx);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Post-graft hook execution failed", e);
                }
            }

            Files.deleteIfExists(journalFile.toPath());
            Files.deleteIfExists(dirstateBackupFile.toPath());
            return newCommitNode;
        } catch (Exception e) {
            // Roll every touched revlog back to its pre-commit size and restore dirstate, same
            // recovery strategy as CommitCommand/HisteditCommand/StripCommand.
            for (Map.Entry<File, Long> sizeEntry : fileSizes.entrySet()) {
                File file = sizeEntry.getKey();
                long origSize = sizeEntry.getValue();
                if (origSize == 0) {
                    Files.deleteIfExists(file.toPath());
                } else if (file.exists()) {
                    try (FileChannel outChan = FileChannel.open(file.toPath(), StandardOpenOption.WRITE)) {
                        outChan.truncate(origSize);
                        outChan.force(true);
                    }
                }
            }
            if (dirstateBackup != null) {
                SafeFileIO.writeAtomic(dirstateFile, dirstateBackup);
            }
            Files.deleteIfExists(journalFile.toPath());
            Files.deleteIfExists(dirstateBackupFile.toPath());
            repository.clearRevlogCache();
            throw e;
        }
    }

    private void recordAndJournal(File file, Map<File, Long> fileSizes, File journalFile) throws IOException {
        if (fileSizes.containsKey(file)) {
            return;
        }
        long size = file.exists() ? file.length() : 0L;
        fileSizes.put(file, size);
        String relPath = "store/" + repository.getStoreDir().toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/');
        appendToJournal(journalFile, relPath + "\t" + size);
    }

    private void appendToJournal(File journalFile, String entry) throws IOException {
        Files.writeString(journalFile.toPath(), entry + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try (FileChannel fc = FileChannel.open(journalFile.toPath(), StandardOpenOption.WRITE)) {
            fc.force(true);
        }
    }

    // ------------------------------------------------------------------
    // Paused-graft state persistence (own hg4j-private format, mirroring RebaseCommand's own
    // rebasestate-hg4j; see this class's own javadoc for why mid-flight interop with real hg's
    // own `hg graft --continue`/`--abort` bookkeeping is not a goal)
    // ------------------------------------------------------------------

    private static final class PausedGraftState {
        byte[] origNode;
        byte[] destParentNode;
    }

    private File graftStateFile() {
        return new File(repository.getHgDir(), "graftstate-hg4j");
    }

    private void writeGraftState(byte[] origNode, byte[] destParentNode) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("origNode=").append(NodeIdUtil.toHex(origNode)).append('\n');
        sb.append("destParentNode=").append(NodeIdUtil.toHex(destParentNode)).append('\n');
        Files.writeString(graftStateFile().toPath(), sb.toString(), StandardCharsets.UTF_8);
    }

    private PausedGraftState readGraftState() throws IOException {
        PausedGraftState state = new PausedGraftState();
        for (String line : Files.readAllLines(graftStateFile().toPath(), StandardCharsets.UTF_8)) {
            int eq = line.indexOf('=');
            if (eq == -1) continue;
            String key = line.substring(0, eq);
            String val = line.substring(eq + 1);
            switch (key) {
                case "origNode" -> state.origNode = NodeIdUtil.fromHex(val);
                case "destParentNode" -> state.destParentNode = NodeIdUtil.fromHex(val);
                default -> { /* forward-compatible: ignore unknown keys */ }
            }
        }
        return state;
    }

    private void deleteGraftStateFile() throws IOException {
        Files.deleteIfExists(graftStateFile().toPath());
    }

    private Map<String, String> getManifestForCommit(Revlog changelog, Revlog manifestRevlog, byte[] commitNode) throws IOException {
        Map<String, String> manifestMap = new LinkedHashMap<>();
        if (commitNode == null || NodeIdUtil.isAllZero(commitNode)) {
            return manifestMap;
        }
        int rev = changelog.findRevision(commitNode);
        if (rev == -1) {
            return manifestMap;
        }
        byte[] content = changelog.getRevisionContent(rev);
        String text = new String(content, StandardCharsets.UTF_8);
        String[] lines = text.split("\n");
        if (lines.length == 0) return manifestMap;

        String manifestHex = lines[0].trim();
        byte[] manifestNode = NodeIdUtil.fromHex(manifestHex);
        int mRev = manifestRevlog.findRevision(manifestNode);
        if (mRev != -1) {
            byte[] mContent = manifestRevlog.getRevisionContent(mRev);
            String mText = new String(mContent, StandardCharsets.UTF_8);
            for (String line : mText.split("\n")) {
                if (line.isEmpty()) continue;
                int nullIdx = line.indexOf('\0');
                if (nullIdx != -1) {
                    manifestMap.put(line.substring(0, nullIdx), line.substring(nullIdx + 1));
                }
            }
        }
        return manifestMap;
    }

    private byte[] getFileRevisionContent(HgRepository repository, String path, String nodeHex) throws IOException {
        File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        if (!flIdx.exists()) {
            throw new HgRepositoryNotFoundException("Filelog index does not exist for: " + path);
        }
        Revlog filelog = repository.getRevlog(flIdx, flDat);
        int rev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(nodeHex.substring(0, 40)));
        if (rev == -1) {
            throw new HgRevisionNotFoundException("File revision not found: " + path + " @ " + nodeHex);
        }
        return filelog.getRevisionContent(rev);
    }
}
