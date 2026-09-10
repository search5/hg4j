package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;

/**
 * Porcelain command corresponding to {@code hg root} — prints the repository's root directory
 * (the working directory that contains {@code .hg}).
 *
 * @apiNote Typically obtained via {@link Hg#root()} on an open {@link Hg}
 *     instance rather than constructed directly.
 */
public class RootCommand {
    private final HgRepository repository;

    /**
     * Creates a root command bound to the given repository.
     *
     * @param repository repository whose root directory will be reported
     */
    public RootCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Executes the command.
     *
     * @return the absolute path of the repository's working directory
     */
    public String call() {
        return repository.getDirectory().getAbsolutePath();
    }
}
