package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;

/**
 * Porcelain command corresponding to {@code hg root} — prints the repository's root directory
 * (the working directory that contains {@code .hg}).
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
