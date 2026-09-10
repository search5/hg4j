package io.github.search5.hg4j.errors;

import java.io.IOException;

/**
 * Exception thrown when a Mercurial repository cannot be found due to an invalid path or corruption.
 *
 * @apiNote Thrown by {@link io.github.search5.hg4j.lib.Repository} and {@link
 *     io.github.search5.hg4j.api.Hg} when opening a repository whose {@code .hg/} directory is
 *     missing or unreadable, and by porcelain commands that require an existing repository
 *     (e.g. {@code CloneCommand}, {@code UpdateCommand}, {@code CommitCommand}, {@code
 *     ShelveCommand}, {@code GraftCommand}) before they attempt any work. Distinguish it from
 *     {@link HgCorruptDataException}: this means "no repository here at all", not "found one but
 *     its data is malformed".
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
