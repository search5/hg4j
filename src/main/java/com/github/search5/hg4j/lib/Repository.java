package com.github.search5.hg4j.lib;
import com.github.search5.hg4j.dirstate.Dirstate;

import java.io.File;
import java.io.IOException;
import com.github.search5.hg4j.errors.HgLockException;
import com.github.search5.hg4j.errors.HgRepositoryNotFoundException;

/**
 * Interface representing a Mercurial repository.
 * Provides abstraction for core repository directory access, dirstate operations, branch state, and concurrency locks.
 */
public interface Repository extends AutoCloseable {

    File getDirectory();

    File getHgDir();

    File getStoreDir();

    Dirstate getDirstate() throws IOException;

    void writeDirstate(Dirstate dirstate) throws IOException;

    String getBranch();

    void setBranch(String branch) throws IOException;

    HgLock lockWorkingCopy() throws HgLockException;

    HgLock lockStore() throws HgLockException;

    /**
     * Opens an existing Mercurial repository.
     * 
     * @param directory the repository directory
     * @return the {@link Repository} instance
     * @throws IOException if the repository does not exist or cannot be opened
     */
    static Repository open(File directory) throws IOException {
        if (directory == null) {
            throw new IllegalArgumentException("Directory cannot be null");
        }
        File hgDir = new File(directory, ".hg");
        if (!hgDir.exists() || !hgDir.isDirectory()) {
            throw new HgRepositoryNotFoundException("Repository not found at: " + directory.getAbsolutePath());
        }
        return new HgRepository(directory);
    }

    @Override
    void close();
}
