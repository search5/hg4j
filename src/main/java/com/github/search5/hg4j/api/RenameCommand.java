package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.lib.HgLock;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.errors.HgLockException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import com.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import com.github.search5.hg4j.util.SafeFileIO;
import java.nio.file.StandardOpenOption;

/**
 * Porcelain command to move or rename a file or directory inside the repository.
 * Correctly registers SCM copy metadata in the dirstate copyMap.
 */
public final class RenameCommand {
    private final HgRepository repository;
    private String sourcePath;
    private String targetPath;

    public RenameCommand(HgRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("Repository cannot be null");
        }
        this.repository = repository;
    }

    public RenameCommand setSource(String sourcePath) {
        this.sourcePath = sourcePath;
        return this;
    }

    public RenameCommand setTarget(String targetPath) {
        this.targetPath = targetPath;
        return this;
    }

    /**
     * Executes the file rename and SCM copy history registration.
     *
     * @throws IOException if physical file move or dirstate write fails
     */
    public void call() throws IOException, HgLockException {
        if (sourcePath == null || targetPath == null) {
            throw new IllegalStateException("Source and target paths must be specified.");
        }

        File srcFile = new File(repository.getDirectory(), sourcePath);
        File destFile = new File(repository.getDirectory(), targetPath);

        if (!srcFile.exists()) {
            throw new HgRepositoryNotFoundException("Source file does not exist: " + sourcePath);
        }

        // 1. Lock repository for working directory changes
        File dirstateFile = new File(repository.getDirectory(), ".hg/dirstate");
        byte[] dirstateBackup = dirstateFile.exists() ? Files.readAllBytes(dirstateFile.toPath()) : null;
        File journalFile = new File(repository.getStoreDir(), "journal");

        try (HgLock lock = repository.lockWorkingCopy()) {
            // Create physical journal and backups for Crash Resilience
            Files.deleteIfExists(journalFile.toPath());
            if (dirstateFile.exists()) {
                File dirstateBackupFile = new File(repository.getDirectory(), ".hg/dirstate.backup");
                Files.copy(dirstateFile.toPath(), dirstateBackupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                appendToJournal(journalFile, "dirstate");
            }

            try {
                // 2. Perform physical move
                destFile.getParentFile().mkdirs();
                Files.move(srcFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

                // 3. Update dirstate copy map and state entries
                Dirstate dirstate = repository.getDirstate();
                
                // Source: marked as removed ('r')
                dirstate.addEntry(sourcePath, new Dirstate.Entry('r', 0, 0, 0));
                
                // Target: marked as added ('a')
                int mode = destFile.canExecute() ? 0755 : 0644;
                dirstate.addEntry(targetPath, new Dirstate.Entry('a', mode, (int) destFile.length(), destFile.lastModified() / 1000));
                
                // Register Copy-rename linkage in copyMap
                dirstate.addCopy(targetPath, sourcePath);

                repository.writeDirstate(dirstate);

                // Clean up crash backups on success
                Files.deleteIfExists(journalFile.toPath());
                File dirstateBackupFile = new File(repository.getDirectory(), ".hg/dirstate.backup");
                Files.deleteIfExists(dirstateBackupFile.toPath());
            } catch (Exception e) {
                // Restore dirstate on failure
                if (dirstateBackup != null) {
                    try {
                        SafeFileIO.writeAtomic(dirstateFile, dirstateBackup);
                    } catch (Exception ignored) {}
                }
                try {
                    Files.deleteIfExists(journalFile.toPath());
                } catch (Exception ignored) {}
                throw e;
            }
        }
    }

    private void appendToJournal(File journal, String entry) throws IOException {
        Files.write(journal.toPath(), (entry + "\n").getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
