package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.lib.HgLock;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.errors.HgLockException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

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
            throw new com.github.search5.hg4j.errors.HgRepositoryNotFoundException("Source file does not exist: " + sourcePath);
        }

        // 1. Lock repository for working directory changes
        try (HgLock lock = repository.lockWorkingCopy()) {
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
        }
    }
}
