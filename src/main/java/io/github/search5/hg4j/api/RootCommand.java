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

    public RootCommand(HgRepository repository) {
        this.repository = repository;
    }

    public String call() {
        return repository.getDirectory().getAbsolutePath();
    }
}
