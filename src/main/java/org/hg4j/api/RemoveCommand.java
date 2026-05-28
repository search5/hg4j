package org.hg4j.api;

import org.hg4j.core.Dirstate;
import org.hg4j.core.HgLock;
import org.hg4j.core.HgRepository;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Porcelain command to untrack and remove files from the working directory and staging area.
 */
public class RemoveCommand {

    private final HgRepository repository;
    private String file;

    public RemoveCommand(HgRepository repository) {
        this.repository = repository;
    }

    public RemoveCommand setFile(String file) {
        this.file = file;
        return this;
    }

    public boolean call() throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalStateException("File path must be specified.");
        }

        try (HgLock storeLock = repository.lockStore();
             HgLock wlock = repository.lockWorkingCopy()) {

            Dirstate dirstate = repository.getDirstate();
            Dirstate.Entry entry = dirstate.getEntries().get(file);

            if (entry == null) {
                throw new IOException("File is not tracked: " + file);
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
