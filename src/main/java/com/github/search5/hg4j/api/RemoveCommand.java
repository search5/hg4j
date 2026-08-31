package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.core.HgLock;
import com.github.search5.hg4j.core.HgRepository;
import com.github.search5.hg4j.errors.HgLockException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Porcelain command to untrack and remove files from the working directory and staging area.
 */
public class RemoveCommand {

    private final HgRepository repository;
    private String file;
    private boolean force = false;

    public RemoveCommand(HgRepository repository) {
        this.repository = repository;
    }

    public RemoveCommand setFile(String file) {
        this.file = file;
        return this;
    }

    public RemoveCommand setForce(boolean force) {
        this.force = force;
        return this;
    }

    public boolean call() throws IOException, HgLockException {
        if (file == null || file.isEmpty()) {
            throw new IllegalStateException("File path must be specified.");
        }

        try (HgLock wlock = repository.lockWorkingCopy();
             HgLock storeLock = repository.lockStore()) {

            Dirstate dirstate = repository.getDirstate();
            Dirstate.Entry entry = dirstate.getEntries().get(file);

            if (entry == null) {
                throw new com.github.search5.hg4j.errors.HgValidationException("File is not tracked: " + file);
            }

            if (!force) {
                char state = entry.getState();
                if (state == 'a') {
                    throw new com.github.search5.hg4j.errors.HgValidationException("File has uncommitted changes (added): " + file + ". Use force to remove.");
                } else if (state == 'n' || state == 'm') {
                    File diskFile = new File(repository.getDirectory(), file);
                    if (diskFile.exists() && diskFile.isFile()) {
                        long diskSize = diskFile.length();
                        long diskTime = diskFile.lastModified() / 1000;
                        boolean isDirty = entry.getSize() != diskSize || entry.getTime() != diskTime;
                        if (!isDirty) {
                            // Racy-hg check: content level comparison
                            File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), file);
                            File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
                            if (flIdx.exists()) {
                                try {
                                    com.github.search5.hg4j.core.Revlog filelog = repository.getRevlog(flIdx, flDat);
                                    if (filelog.getRevisionCount() > 0) {
                                        byte[] fileContent = Files.readAllBytes(diskFile.toPath());
                                        byte[] lastContent = filelog.getRevisionContent(filelog.getRevisionCount() - 1);
                                        if (!java.util.Arrays.equals(fileContent, lastContent)) {
                                            isDirty = true;
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                        if (isDirty) {
                            throw new com.github.search5.hg4j.errors.HgValidationException("File has uncommitted changes (modified): " + file + ". Use force to remove.");
                        }
                    }
                }
            }

            File diskFile = new File(repository.getDirectory(), file);
            if (diskFile.exists() && diskFile.isFile()) {
                Files.delete(diskFile.toPath());
            }

            if (entry.getState() == 'a') {
                // If it was newly added, we just untrack it completely
                dirstate.removeEntry(file);
            } else {
                // Mark as removed for the next commit
                dirstate.addEntry(file, new Dirstate.Entry('r', 0, 0, 0));
            }

            repository.writeDirstate(dirstate);
            return true;
        }
    }
}
