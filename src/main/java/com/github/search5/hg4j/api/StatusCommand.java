package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.github.search5.hg4j.util.SafeFileIO;
import com.github.search5.hg4j.storage.Revlog;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.github.search5.hg4j.treewalk.HgTreeFilter;
import com.github.search5.hg4j.treewalk.ManifestTreeIterator;
import com.github.search5.hg4j.treewalk.ManifestWalk;
import com.github.search5.hg4j.treewalk.TreeWalk;
import com.github.search5.hg4j.treewalk.WorkingDirTreeIterator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Computes differences between working directory, dirstate, and parent commits.
 */
public class StatusCommand {
    private final HgRepository repository;
    private HgTreeFilter treeFilter = HgTreeFilter.ALL;

    public StatusCommand(HgRepository repository) {
        this.repository = repository;
    }

    public StatusCommand setTreeFilter(HgTreeFilter treeFilter) {
        if (treeFilter != null) {
            this.treeFilter = treeFilter;
        }
        return this;
    }

    public Status call() throws IOException {
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
                        if (dEntry.getSize() != diskSize || dEntry.getTime() != diskTime) {
                            status.getModified().add(path);
                        } else {
                            boolean isRacyModified = false;
                            if (diskTime == dirstateMtime) {
                                try {
                                    byte[] parentContent = getParentCommitFileContent(dirstate, path);
                                    if (parentContent != null) {
                                        byte[] fileContent = effectiveContent(diskFile, isSymlink);
                                        if (!Arrays.equals(fileContent, parentContent)) {
                                            isRacyModified = true;
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }

                            if (isRacyModified) {
                                status.getModified().add(path);
                            } else {
                                status.getClean().add(path);
                            }
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
                            if (dEntry.getSize() != diskSize || dEntry.getTime() != diskTime) {
                                status.getModified().add(path);
                            } else {
                                boolean isRacyModified = false;
                                if (diskTime == dirstateMtime) {
                                    try {
                                        byte[] parentContent = getParentCommitFileContent(dirstate, path);
                                        if (parentContent != null) {
                                            byte[] fileContent = effectiveContent(diskFile, isSymlink);
                                            if (!Arrays.equals(fileContent, parentContent)) {
                                                isRacyModified = true;
                                            }
                                        }
                                    } catch (Exception ignored) {}
                                }

                                if (isRacyModified) {
                                    status.getModified().add(path);
                                } else {
                                    status.getClean().add(path);
                                }
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
                return filelog.getRevisionContent(fileRev);
            }
        }
        return null;
    }
}
