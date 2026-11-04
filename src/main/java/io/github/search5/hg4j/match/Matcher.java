package io.github.search5.hg4j.match;

import java.util.List;

/**
 * Decides whether a repository-relative path (slash separated) is selected: Mercurial's {@code match.basematcher}.
 */
public interface Matcher {

    /** Whether the file is selected. */
    boolean matches(String path);

    /** Whether the file was named exactly (not found by a glob or a regular expression). */
    default boolean exact(String path) {
        return files().contains(path);
    }

    /** The paths the patterns name explicitly (roots of globs, {@code path:} names, ...); used to know where to look. */
    default List<String> files() {
        return List.of();
    }

    /** True if every file matches (so a caller may skip matching). */
    default boolean always() {
        return false;
    }

    /** True if the matcher only selects the files it lists in {@link #files()}. */
    default boolean isExact() {
        return false;
    }

    /**
     * Whether a directory may contain selected files (a walk may skip it when this is false). Never a false negative; the default
     * says yes.
     */
    default boolean visitDir(String dir) {
        return true;
    }
}
