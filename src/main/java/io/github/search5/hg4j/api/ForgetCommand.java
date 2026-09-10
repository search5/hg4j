package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;

import java.io.IOException;
import io.github.search5.hg4j.errors.HgValidationException;

/**
 * Porcelain command corresponding to {@code hg forget} — stops tracking a file without touching
 * it on disk (unlike {@link RemoveCommand}, which deletes the working copy file).
 *
 * @apiNote Typically obtained via {@link Hg#forget()} on an open {@link Hg}
 *     instance rather than constructed directly.
 */
public class ForgetCommand {
    private final HgRepository repository;
    private String file;

    /**
     * Creates a forget command bound to the given repository.
     *
     * @param repository repository whose dirstate will be updated
     */
    public ForgetCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Sets the repository-relative path of the file to stop tracking.
     *
     * @param file repository-relative path of the tracked file to forget
     * @return this command, for chaining
     */
    public ForgetCommand setFile(String file) {
        this.file = file;
        return this;
    }

    /**
     * Executes the command, marking the configured file as no longer tracked in the dirstate.
     *
     * @throws IOException if the dirstate cannot be read or written
     * @throws HgLockException if the working copy lock cannot be acquired
     * @throws IllegalStateException if no file was configured via {@link #setFile}
     * @throws io.github.search5.hg4j.errors.HgValidationException if the configured file is not
     *     currently tracked
     */
    public void call() throws IOException, HgLockException {
        if (file == null || file.isEmpty()) {
            throw new IllegalStateException("File path must be specified.");
        }
        try (HgLock wlock = repository.lockWorkingCopy()) {
            Dirstate dirstate = repository.getDirstate();
            Dirstate.Entry entry = dirstate.getEntries().get(file);
            if (entry == null) {
                throw new HgValidationException("File is not tracked: " + file);
            }

            if (entry.getState() == 'a') {
                // A file never committed yet -- just stop tracking it entirely.
                dirstate.removeEntry(file);
            } else {
                // An already-committed file -- record it as removed at the next commit, while
                // leaving the working copy untouched.
                dirstate.addEntry(file, new Dirstate.Entry('r', 0, 0, 0));
            }
            repository.writeDirstate(dirstate);
        }
    }
}
