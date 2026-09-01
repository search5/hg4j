package com.github.search5.hg4j.errors;

import java.io.IOException;

/**
 * Exception thrown when a Mercurial repository cannot be found due to an invalid path or corruption.
 */
public class HgRepositoryNotFoundException extends IOException {
    private static final long serialVersionUID = 1L;

    private final String path;

    /**
     * @param path Path to the missing or corrupt repository
     */
    public HgRepositoryNotFoundException(String path) {
        super("Mercurial repository not found at path: " + path);
        this.path = path;
    }

    /**
     * @param path  Path to the missing or corrupt repository
     * @param cause The causing exception
     */
    public HgRepositoryNotFoundException(String path, Throwable cause) {
        super("Mercurial repository not found at path: " + path, cause);
        this.path = path;
    }

    /** Returns the repository path. */
    public String getPath() {
        return path;
    }
}
