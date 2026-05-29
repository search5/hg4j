package org.hg4j.api;

import org.hg4j.core.Dirstate;
import org.hg4j.core.HgRepository;
import org.hg4j.core.NodeIdUtil;
import org.hg4j.core.Revlog;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Computes differences between working directory, dirstate, and parent commits.
 */
public class StatusCommand {
    private final HgRepository repository;

    public StatusCommand(HgRepository repository) {
        this.repository = repository;
    }

    public Status call() throws IOException {
        Status status = new Status();
        Dirstate dirstate = repository.getDirstate();
        File repoDir = repository.getDirectory();
        
        File dirstateFile = new File(repository.getHgDir(), "dirstate");
        long dirstateMtime = dirstateFile.exists() ? dirstateFile.lastModified() / 1000 : 0;

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

        org.hg4j.treewalk.TreeWalk tw = new org.hg4j.treewalk.TreeWalk();
        tw.addTree(new org.hg4j.treewalk.ManifestTreeIterator(repository, parentRev));
        tw.addTree(new org.hg4j.treewalk.WorkingDirTreeIterator(repository));

        tw.reset();
        while (tw.next()) {
            String path = tw.getPath();
            boolean inParent = tw.isTracked(0);
            boolean inWorking = tw.isTracked(1);
            
            char workingState = tw.getState(1);

            if (!inParent && inWorking) {
                if (workingState == 'a') {
                    status.getAdded().add(path);
                } else if (workingState == '?') {
                    status.getUntracked().add(path);
                }
            } else if (inParent && !inWorking) {
                status.getRemoved().add(path);
            } else if (inParent && inWorking) {
                if (workingState == 'r') {
                    status.getRemoved().add(path);
                } else if (workingState == 'n' || workingState == 'm') {
                    File diskFile = new File(repoDir, path);
                    if (!diskFile.exists() || !diskFile.isFile()) {
                        status.getRemoved().add(path);
                    } else {
                        Dirstate.Entry dEntry = dirstate.getEntries().get(path);
                        if (dEntry != null) {
                            long diskSize = diskFile.length();
                            long diskTime = diskFile.lastModified() / 1000;
                            if (dEntry.getSize() != diskSize || dEntry.getTime() != diskTime) {
                                status.getModified().add(path);
                            } else {
                                boolean isRacyModified = false;
                                if (diskTime == dirstateMtime) {
                                    File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
                                    File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
                                    if (flIdx.exists()) {
                                        try {
                                            Revlog filelog = repository.getRevlog(flIdx, flDat);
                                            if (filelog.getRevisionCount() > 0) {
                                                byte[] fileContent = java.nio.file.Files.readAllBytes(diskFile.toPath());
                                                byte[] lastContent = filelog.getRevisionContent(filelog.getRevisionCount() - 1);
                                                if (!java.util.Arrays.equals(fileContent, lastContent)) {
                                                    isRacyModified = true;
                                                }
                                            }
                                        } catch (Exception ignored) {}
                                    }
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
}
