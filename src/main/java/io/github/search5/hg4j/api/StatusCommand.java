package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.util.SafeFileIO;
import io.github.search5.hg4j.storage.Revlog;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import io.github.search5.hg4j.treewalk.HgTreeFilter;
import io.github.search5.hg4j.treewalk.ManifestTreeIterator;
import io.github.search5.hg4j.treewalk.ManifestWalk;
import io.github.search5.hg4j.treewalk.TreeWalk;
import io.github.search5.hg4j.treewalk.WorkingDirTreeIterator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Computes differences between working directory, dirstate, and parent commits.
 */
public class StatusCommand {
    private final HgRepository repository;
    private HgTreeFilter treeFilter = HgTreeFilter.ALL;

    // Real Mercurial can mark a dirstate entry's cached stat ambiguous -- most commonly when the
    // entry was (re)written within the very same wall-clock second as the dirstate file itself,
    // so a same-second edit right afterward would otherwise be indistinguishable from "unchanged"
    // by size/mtime alone (mercurial/dirstate.py's classic ambiguous-stat handling; real hg's own
    // internal `up_impl.update()` -- used by, among others, `hg shelve`'s revert-to-parent step --
    // writes this sentinel routinely, not just in rare corner cases). See
    // Dirstate.Entry#isStatAmbiguous() (backlog #39 wave 4, 2026-09-05) for the full sentinel
    // shape -- originally this class only special-cased the mtime half of it (ShelveRealHgInteropTest,
    // 2026-09-04), which left the paired size=-1 sentinel unguarded and looking permanently
    // "modified" on its own.

    public StatusCommand(HgRepository repository) {
        this.repository = repository;
    }

    public StatusCommand setTreeFilter(HgTreeFilter treeFilter) {
        if (treeFilter != null) {
            this.treeFilter = treeFilter;
        }
        return this;
    }

    private final FileSelection selection = new FileSelection();

    /** The files to report, as {@code hg status [FILE]...} takes them (patterns, {@code kind:pattern}, {@code set:EXPRESSION}). */
    public StatusCommand setPatterns(String... patterns) {
        selection.patterns(patterns);
        return this;
    }

    /** {@code hg status -I}. */
    public StatusCommand setInclude(String... patterns) {
        selection.include(patterns);
        return this;
    }

    /** {@code hg status -X}. */
    public StatusCommand setExclude(String... patterns) {
        selection.exclude(patterns);
        return this;
    }

    /** The current directory relative to the repository root, against which relative patterns are read. */
    public StatusCommand setCwd(String cwd) {
        selection.cwd(cwd);
        return this;
    }

    public Status call() throws IOException {
        Status all = callAll();
        if (selection.isEmpty()) {
            return all;
        }
        io.github.search5.hg4j.match.Matcher matcher = selection.matcher(repository, null);
        Status kept = new Status();
        for (String f : all.getAdded()) {
            if (matcher.matches(f)) {
                kept.getAdded().add(f);
            }
        }
        for (String f : all.getModified()) {
            if (matcher.matches(f)) {
                kept.getModified().add(f);
            }
        }
        for (String f : all.getRemoved()) {
            if (matcher.matches(f)) {
                kept.getRemoved().add(f);
            }
        }
        for (String f : all.getClean()) {
            if (matcher.matches(f)) {
                kept.getClean().add(f);
            }
        }
        for (String f : all.getUntracked()) {
            if (matcher.matches(f)) {
                kept.getUntracked().add(f);
            }
        }
        return kept;
    }

    private final Map<String, Dirstate.Entry> fixups = new java.util.LinkedHashMap<>();

    /**
     * The dirstate's verdict on a tracked file, as `dirstate.status` gives it: a different size is modified; the same size and mtime is
     * clean; anything else (a different mtime, an ambiguous or racy stat) is "lookup": the content is compared with the parent's, and a
     * file that turns out clean has its stat refreshed in the dirstate (`_poststatusfixup`) so the next status needs no comparison.
     */
    private boolean lookupModified(Dirstate dirstate, String path, Dirstate.Entry dEntry, File diskFile, boolean isSymlink, long diskSize,
                                   long diskTime, long dirstateMtime) {
        if (dEntry.getState() == 'm') {
            return true; // `entry.merged`: a file that took part in a merge is modified until it is committed
        }
        boolean statAmbiguous = dEntry.isStatAmbiguous();
        if (!statAmbiguous && dEntry.getSize() != diskSize) {
            return true;
        }
        if (!statAmbiguous && dEntry.getTime() == diskTime && diskTime != dirstateMtime) {
            return false;
        }
        boolean compared = false;
        try {
            byte[] parentContent = getParentCommitFileContent(dirstate, path);
            if (parentContent != null) {
                compared = true;
                if (!Arrays.equals(effectiveContent(diskFile, isSymlink), parentContent)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // no content to compare with: the stat decides
        }
        // a stat of the current second can still change within it: not reliable enough to record
        if (compared && (statAmbiguous || dEntry.getTime() != diskTime) && diskTime < System.currentTimeMillis() / 1000) {
            fixups.put(path, new Dirstate.Entry(dEntry.getState(), dEntry.getMode(), (int) diskSize, (int) diskTime));
        }
        return false;
    }

    /**
     * `_poststatusfixup`: updating the dirstate is optional, so the working copy lock is not waited for (`wlock(False)`); if it is not
     * available the fixes are dropped. The entries are applied to the dirstate as it is under the lock, only where it still holds what
     * the status saw.
     */
    private void writeFixups(Map<String, Dirstate.Entry> seen) {
        if (fixups.isEmpty()) {
            return;
        }
        Map<String, Dirstate.Entry> pending = new java.util.LinkedHashMap<>(fixups);
        fixups.clear();
        try (HgLock lock = repository.lockWorkingCopy(0)) {
            Dirstate fresh = repository.getDirstate();
            boolean changed = false;
            for (Map.Entry<String, Dirstate.Entry> fix : pending.entrySet()) {
                Dirstate.Entry now = fresh.getEntries().get(fix.getKey());
                Dirstate.Entry before = seen.get(fix.getKey());
                if (now != null && before != null && now.getState() == before.getState() && now.getSize() == before.getSize()
                        && now.getTime() == before.getTime()) {
                    fresh.addEntry(fix.getKey(), fix.getValue());
                    changed = true;
                }
            }
            if (changed) {
                repository.writeDirstate(fresh);
            }
        } catch (Exception unavailable) {
            // LockError: the dirstate is simply left as it was
        }
    }

    private Status callAll() throws IOException {
        fixups.clear();
        Status result = computeAll();
        writeFixups(new java.util.HashMap<>(repository.getDirstate().getEntries()));
        return result;
    }

    private Status computeAll() throws IOException {
        // Backlog #39: guard against a long-lived HgRepository handle serving a stale cached
        // changelog-v2 revlog after an external process appended a revision -- see
        // DescribeCommand#call()'s javadoc for the full root-cause writeup. Cheap no-op in the
        // common (freshly-opened-per-call) case.
        repository.refreshIfChangedOnDisk();
        Status status = new Status();
        Dirstate dirstate = repository.getDirstate();
        File repoDir = repository.getDirectory();
        
        File dirstateFile = new File(repository.getHgDir(), "dirstate");
        long dirstateMtime = dirstateFile.exists() ? dirstateFile.lastModified() / 1000 : 0;

        // Fast Path: When treeFilter is null or ALL (dirstate-based path)
        if (treeFilter == null || treeFilter == HgTreeFilter.ALL) {
            Map<String, Dirstate.Entry> tracked = dirstate.getEntries();
            List<String> trackedKeys = new ArrayList<>(tracked.keySet());
            trackedKeys.sort(NodeIdUtil.UTF8_STRING_COMPARATOR);

            for (String path : trackedKeys) {
                Dirstate.Entry dEntry = tracked.get(path);
                char state = dEntry.getState();
                
                if (state == 'a') {
                    status.getAdded().add(path);
                } else if (state == 'r') {
                    status.getRemoved().add(path);
                } else if (state == 'n' || state == 'm') {
                    File diskFile = new File(repoDir, path);
                    boolean isSymlink = Files.isSymbolicLink(diskFile.toPath());
                    if ((!isSymlink && (!diskFile.exists() || !diskFile.isFile()))) {
                        status.getRemoved().add(path);
                    } else {
                        long diskSize = effectiveSize(diskFile, isSymlink);
                        long diskTime = SafeFileIO.lastModifiedSeconds(diskFile);
                        // Real hg's dirstate can mark an entry's ENTIRE cached stat ambiguous
                        // (not just mtime) -- see Dirstate.Entry#isStatAmbiguous(): a same-second
                        // racy commit lands a 'n' entry with size=-1/mtime=AMBIGUOUS_TIME
                        // together (verified live: `hg add; hg commit` within the same
                        // wall-clock second produces mode=0/size=-1/mtime=-1 all at once; also
                        // independently confirmed via BackoutCommand's own precondition check
                        // tripping on a freshly, cleanly committed repo). Gating only on mtime
                        // here left the size-sentinel (-1) comparison unguarded, so it never
                        // equaled a real on-disk size and every such entry looked permanently
                        // "modified" instead of falling through to the real content comparison
                        // below.
                        if (lookupModified(dirstate, path, dEntry, diskFile, isSymlink, diskSize, diskTime, dirstateMtime)) {
                            status.getModified().add(path);
                        } else {
                            status.getClean().add(path);
                        }
                    }
                }
            }

            // Collect untracked files
            List<String> physicalFiles = repository.scanWorkingCopy();
            List<String> untrackedList = new ArrayList<>();
            for (String path : physicalFiles) {
                if (!tracked.containsKey(path)) {
                    untrackedList.add(path);
                }
            }
            untrackedList.sort(NodeIdUtil.UTF8_STRING_COMPARATOR);
            for (String path : untrackedList) {
                status.getUntracked().add(path);
            }

            return status;
        }

        // Slow Path: Via TreeWalk (when filters are specified)
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        // Resolve parent revision string for TreeWalk comparison
        String parentRev = "";
        if (changelog.getRevisionCount() > 0) {
            byte[] parentNode = dirstate.getParent1();
            int parentRevNum = NodeIdUtil.findRevisionByNodeId(changelog, parentNode);
            if (parentRevNum != -1) {
                parentRev = String.valueOf(parentRevNum);
            }
        }

        TreeWalk tw = new TreeWalk();
        tw.addTree(new ManifestTreeIterator(repository, parentRev));
        tw.addTree(new WorkingDirTreeIterator(repository));

        tw.reset();
        while (tw.next()) {
            String path = tw.getPath();
            if (treeFilter != null && !treeFilter.accept(path)) {
                continue;
            }
            boolean inParent = tw.isTracked(0);
            boolean inWorking = tw.isTracked(1);
            
            char workingState = tw.getState(1);

            if (workingState == '?') {
                status.getUntracked().add(path);
            } else if (!inParent && inWorking) {
                if (workingState == 'a') {
                    status.getAdded().add(path);
                }
            } else if (inParent && inWorking) {
                if (workingState == 'r') {
                    status.getRemoved().add(path);
                } else if (workingState == 'n' || workingState == 'm') {
                    File diskFile = new File(repoDir, path);
                    boolean isSymlink = Files.isSymbolicLink(diskFile.toPath());
                    if (!isSymlink && (!diskFile.exists() || !diskFile.isFile())) {
                        status.getRemoved().add(path);
                    } else {
                        Dirstate.Entry dEntry = dirstate.getEntries().get(path);
                        if (dEntry != null) {
                            long diskSize = effectiveSize(diskFile, isSymlink);
                            long diskTime = SafeFileIO.lastModifiedSeconds(diskFile);
                            // See the identical comment on this same pattern in the fast-path
                            // branch above -- an entry's cached stat can be fully ambiguous
                            // (size AND mtime sentinel together), not just mtime alone.
                            if (lookupModified(dirstate, path, dEntry, diskFile, isSymlink, diskSize, diskTime, dirstateMtime)) {
                                status.getModified().add(path);
                            } else {
                                status.getClean().add(path);
                            }
                        } else {
                            status.getClean().add(path);
                        }
                    }
                }
            }
        }

        return status;
    }

    /**
     * Returns the "effective content" of a working-copy file the way Mercurial itself
     * represents it: for a symlink this is the UTF-8 bytes of the link target path
     * (matching {@code os.lstat}/{@code os.readlink} semantics and what is actually
     * stored in the filelog), never the bytes of whatever the link happens to point at.
     * A plain {@code File.length()}/{@code Files.readAllBytes()} on a symlink follows it
     * to the target file instead, which is a different (and unrelated) size/content —
     * comparing that against the dirstate/filelog entry produces false "modified" results
     * for any symlink whose target's content size differs from its own path string length.
     */
    private static byte[] effectiveContent(File diskFile, boolean isSymlink) throws IOException {
        if (isSymlink) {
            return Files.readSymbolicLink(diskFile.toPath()).toString()
                    .getBytes(StandardCharsets.UTF_8);
        }
        return Files.readAllBytes(diskFile.toPath());
    }

    private static long effectiveSize(File diskFile, boolean isSymlink) throws IOException {
        if (isSymlink) {
            return effectiveContent(diskFile, true).length;
        }
        return diskFile.length();
    }

    /**
     * Returns the content of {@code path} as recorded in the manifest of the working
     * copy's current parent commit (dirstate parent1), or {@code null} if it cannot be
     * resolved (no commits yet, or the path is not present at that revision).
     * <p>
     * This is deliberately NOT "the file's latest filelog revision" — after {@code
     * hg update} to a non-tip revision, later revisions still exist in the filelog but
     * are not what the working copy is currently checked out against, so comparing
     * against them would produce false "modified" results for untouched files.
     */
    private byte[] getParentCommitFileContent(Dirstate dirstate, String path) throws IOException {
        byte[] p1 = dirstate.getParent1();
        if (p1 == null || NodeIdUtil.isAllZero(p1)) {
            return null;
        }
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        if (!clIdx.exists()) {
            return null;
        }
        Revlog changelog = repository.getRevlog(clIdx, clDat);
        int parentRev = changelog.findRevision(p1);
        if (parentRev == -1) {
            return null;
        }

        ManifestWalk mw =
                new ManifestWalk(repository, String.valueOf(parentRev));
        while (mw.next()) {
            ManifestWalk.Entry entry = mw.getEntry();
            if (entry.getPath().equals(path)) {
                File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
                File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
                if (!flIdx.exists()) {
                    return null;
                }
                Revlog filelog = repository.getRevlog(flIdx, flDat);
                int fileRev = NodeIdUtil.findRevisionByNodeId(filelog, entry.getNodeId());
                if (fileRev == -1) {
                    return null;
                }
                // the REAL content: an LFS file's stored bytes are only the pointer text
                return io.github.search5.hg4j.lfs.HgLfsFactory.getInstance().readContent(repository, path, filelog, fileRev);
            }
        }
        return null;
    }
}
