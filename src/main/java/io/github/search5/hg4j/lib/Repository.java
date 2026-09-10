package io.github.search5.hg4j.lib;
import io.github.search5.hg4j.dirstate.Dirstate;

import java.io.File;
import java.io.IOException;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgRepositoryNotFoundException;

/**
 * Interface representing a Mercurial repository.
 * Provides abstraction for core repository directory access, dirstate operations, branch state, and concurrency locks.
 *
 * @apiNote {@link HgRepository} is the sole implementation; nearly every porcelain command
 *     (e.g. {@code CommitCommand}, {@code UpdateCommand}, {@code ShelveCommand}) is constructed
 *     with a {@code Repository}/{@code HgRepository} instance obtained via {@link #open} or
 *     {@code io.github.search5.hg4j.api.Hg}. Depend on this interface rather than {@link
 *     HgRepository} directly where practical, matching JGit's {@code Repository} abstraction.
 */
public interface Repository extends AutoCloseable {

    /** The repository's working directory (the parent of {@link #getHgDir()}). */
    File getDirectory();

    /** The repository's {@code .hg} control directory. */
    File getHgDir();

    /** The repository's {@code .hg/store} directory, where revlogs and the store live. */
    File getStoreDir();

    /**
     * Reads and parses the current dirstate (working-copy tracking state).
     *
     * @apiNote Called by any command that needs to know which files are tracked/added/removed
     *     or their recorded mtimes (e.g. {@code StatusCommand}, {@code AddCommand}, {@code
     *     CommitCommand}) before it does its own work.
     */
    Dirstate getDirstate() throws IOException;

    /**
     * Persists the given dirstate as the repository's new working-copy tracking state.
     *
     * @apiNote Called by any command that changes tracked-file state (e.g. {@code AddCommand},
     *     {@code RemoveCommand}, {@code CommitCommand}, {@code UpdateCommand}) after computing
     *     the new dirstate contents. Must be called while holding {@link #lockWorkingCopy()}.
     */
    void writeDirstate(Dirstate dirstate) throws IOException;

    /** The working directory's current named branch (from {@code .hg/branch}). */
    String getBranch();

    /**
     * Sets the working directory's current named branch, persisting it to {@code .hg/branch}.
     *
     * @apiNote Called by {@code BranchCommand} to switch branches, and internally by commands
     *     that update the working copy to a revision on a different branch.
     */
    void setBranch(String branch) throws IOException;

    /**
     * Acquires the working-copy lock ({@code .hg/wlock}), failing fast if already held.
     *
     * @apiNote Use in a try-with-resources block around any working-directory mutation (file
     *     add/remove/checkout); see {@link HgLock} for details.
     */
    HgLock lockWorkingCopy() throws HgLockException;

    /**
     * Acquires the store lock ({@code .hg/store/lock}), failing fast if already held.
     *
     * @apiNote Use in a try-with-resources block around any store mutation (writing revlogs,
     *     committing); see {@link HgLock} for details.
     */
    HgLock lockStore() throws HgLockException;

    /**
     * Opens an existing Mercurial repository.
     *
     * @apiNote For creating a brand-new repository, use {@code
     *     io.github.search5.hg4j.api.InitCommand} instead — this only opens one whose {@code
     *     .hg} directory already exists.
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

    /** Releases any resources (e.g. open file handles) held by this repository instance. */
    @Override
    void close();
}
