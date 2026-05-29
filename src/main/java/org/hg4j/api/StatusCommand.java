package org.hg4j.api;

import org.hg4j.core.Dirstate;
import org.hg4j.core.HgRepository;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

        // 1. Scan working directory recursively for disk files
        List<String> diskFilesList = repository.scanWorkingCopy();
        Set<String> diskFiles = new HashSet<>(diskFilesList);

        // 2. Load entries from the dirstate
        Map<String, Dirstate.Entry> entries = dirstate.getEntries();

        // 3. Check tracked entries
        for (Map.Entry<String, Dirstate.Entry> item : entries.entrySet()) {
            String path = item.getKey();
            Dirstate.Entry entry = item.getValue();
            char state = entry.getState();

            if (state == 'a') {
                status.getAdded().add(path);
            } else if (state == 'r') {
                status.getRemoved().add(path);
            } else if (state == 'n' || state == 'm') {
                File diskFile = new File(repoDir, path);
                if (!diskFile.exists() || !diskFile.isFile()) {
                    // Tracked but deleted/missing on disk
                    status.getRemoved().add(path);
                } else {
                    // Compare size and timestamp to check if modified
                    long diskSize = diskFile.length();
                    long diskTime = diskFile.lastModified() / 1000;
                    
                    if (entry.getSize() != diskSize || entry.getTime() != diskTime) {
                        status.getModified().add(path);
                    } else {
                        // Racy-hg check: same size and timestamp within fast execution (Only executed if modification coincides with dirstate's time)
                        boolean isRacyModified = false;
                        if (diskTime == dirstateMtime) {
                            File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), path);
                            File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
                            if (flIdx.exists()) {
                                try {
                                    org.hg4j.core.Revlog filelog = new org.hg4j.core.Revlog(flIdx, flDat);
                                    if (filelog.getRevisionCount() > 0) {
                                        byte[] fileContent = java.nio.file.Files.readAllBytes(diskFile.toPath());
                                        byte[] lastContent = filelog.getRevisionContent(filelog.getRevisionCount() - 1);
                                        if (!java.util.Arrays.equals(fileContent, lastContent)) {
                                            isRacyModified = true;
                                        }
                                    }
                                } catch (Exception ignored) {
                                }
                            }
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

        // 4. Untracked files: files on disk that are NOT in dirstate entries
        for (String path : diskFiles) {
            if (!entries.containsKey(path)) {
                status.getUntracked().add(path);
            }
        }

        return status;
    }
}
