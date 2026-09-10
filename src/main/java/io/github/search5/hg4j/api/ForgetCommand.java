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

    public ForgetCommand(HgRepository repository) {
        this.repository = repository;
    }

    public ForgetCommand setFile(String file) {
        this.file = file;
        return this;
    }

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
