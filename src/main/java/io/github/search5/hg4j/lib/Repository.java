package io.github.search5.hg4j.lib;
import io.github.search5.hg4j.dirstate.Dirstate;

import java.io.File;
import java.io.IOException;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgRepositoryNotFoundException;

/**
 * Interface representing a Mercurial repository.
 * Provides abstraction for core repository directory access, dirstate operations, branch state, and concurrency locks.
 */
public interface Repository extends AutoCloseable {

    /**
     * Returns the root of the working copy.
     * @return the working copy directory
     */
    File getDirectory();

    /**
     * Returns the {@code .hg} directory of the working copy.
     * @return the {@code .hg} directory
     */
    File getHgDir();

    /**
     * Returns the store directory: {@code .hg/store}, or the {@code store} directory of the source repository for a shared one.
     * @return the store directory
     */
    File getStoreDir();

    /**
     * Returns the dirstate of the working copy. In {@link HgRepository} the dirstate file is read on every call unless a changing context
     * is open on the current thread, in which case the context's instance is returned. No lock is taken by the call.
     * @return the dirstate, never {@code null}
     * @throws IOException if the dirstate cannot be read and cannot be rebuilt
     */
    Dirstate getDirstate() throws IOException;

    /**
     * Saves the dirstate. In {@link HgRepository} the write is deferred until the outermost changing context on the current thread ends
     * normally, or done at once when no such context is open. The caller is expected to hold the working copy lock.
     * @param dirstate the dirstate to save
     * @throws IOException if writing fails
     */
    void writeDirstate(Dirstate dirstate) throws IOException;

    /**
     * Returns the name of the working copy's branch.
     * @return the branch name, {@code "default"} if none was set
     */
    String getBranch();

    /**
     * Sets the working copy's branch.
     * @param branch the new branch name; must not be empty or {@code null}
     * @throws IOException if writing fails
     */
    void setBranch(String branch) throws IOException;

    /**
     * Takes the working copy lock ({@code .hg/wlock}), waiting as long as the implementation's timeout allows.
     * @return the held lock; close it to release it
     * @throws HgLockException if it could not be acquired in time
     */
    HgLock lockWorkingCopy() throws HgLockException;

    /**
     * Takes the store lock ({@code .hg/store/lock}), waiting as long as the implementation's timeout allows.
     * @return the held lock; close it to release it
     * @throws HgLockException if it could not be acquired in time
     */
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
