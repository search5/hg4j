package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;

/**
 * Porcelain command corresponding to {@code hg root} — prints the repository's root directory
 * (the working directory that contains {@code .hg}).
 */
public class RootCommand {
    private final HgRepository repository;

    /**
     * Creates the command.
     * @param repository the repository whose root is reported; must not be {@code null}
     */
    public RootCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Returns the repository root.
     * @return the absolute path of the working directory that contains {@code .hg}
     */
    public String call() {
        return repository.getDirectory().getAbsolutePath();
    }
}
