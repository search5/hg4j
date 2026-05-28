package org.hg4j.api;

import org.hg4j.core.Dirstate;
import org.hg4j.core.HgRepository;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Adds untracked files to the repository tracking list.
 */
public class AddCommand {

    private final HgRepository repository;
    private final List<String> files = new ArrayList<>();

    public AddCommand(HgRepository repository) {
        this.repository = repository;
    }

    public AddCommand addFile(String file) {
        if (file != null && !file.isEmpty()) {
            files.add(file.replace('\\', '/'));
        }
        return this;
    }

    public void call() throws IOException {
        try (org.hg4j.core.HgLock wlock = repository.lockWorkingCopy()) {
            Dirstate dirstate = repository.getDirstate();

            List<String> filesToAdd = new ArrayList<>(files);
            if (filesToAdd.isEmpty()) {
                // Scan for all untracked files
                List<String> allFiles = repository.scanWorkingCopy();
                for (String relPath : allFiles) {
                    if (!dirstate.getEntries().containsKey(relPath)) {
                        filesToAdd.add(relPath);
                    }
                }
            }

            for (String relPath : filesToAdd) {
                File diskFile = new File(repository.getDirectory(), relPath);
                if (!diskFile.exists() || !diskFile.isFile()) {
                    throw new IOException("File not found or is not a standard file: " + relPath);
                }

                boolean executable = diskFile.canExecute();
                int mode = executable ? 0755 : 0644;
                int size = (int) diskFile.length();
                long time = diskFile.lastModified() / 1000;

                Dirstate.Entry entry = new Dirstate.Entry('a', mode, size, time);
                dirstate.addEntry(relPath, entry);
            }

            repository.writeDirstate(dirstate);
        }
    }
}
