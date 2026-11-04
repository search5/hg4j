package io.github.search5.hg4j.api;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Represents the status of files in the working directory compared to the repository state.
 */
public class Status {
    private final Set<String> added = new LinkedHashSet<>();
    private final Set<String> modified = new LinkedHashSet<>();
    private final Set<String> removed = new LinkedHashSet<>();
    private final Set<String> clean = new LinkedHashSet<>();
    private final Set<String> untracked = new LinkedHashSet<>();

    /**
     * Returns the added files.
     * @return the live, mutable set of paths scheduled to be added, in insertion order; never {@code null}
     */
    public Set<String> getAdded() {
        return added;
    }

    /**
     * Returns the modified files.
     * @return the live, mutable set of tracked paths whose working copy content differs from the parent, in insertion
     *         order; never {@code null}
     */
    public Set<String> getModified() {
        return modified;
    }

    /**
     * Returns the removed files.
     * @return the live, mutable set of paths marked as removed in the dirstate or tracked but missing from the working
     *         copy, in insertion order; never {@code null}
     */
    public Set<String> getRemoved() {
        return removed;
    }

    /**
     * Returns the clean files.
     * @return the live, mutable set of tracked paths whose content is unchanged, in insertion order; never {@code null}
     */
    public Set<String> getClean() {
        return clean;
    }

    /**
     * Returns the untracked files.
     * @return the live, mutable set of repository-relative paths present in the working copy but not tracked, in
     *         insertion order (sorted by UTF-8 byte order when produced by {@link StatusCommand}); never {@code null}
     */
    public Set<String> getUntracked() {
        return untracked;
    }
}
