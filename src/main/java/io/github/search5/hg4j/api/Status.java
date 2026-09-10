package io.github.search5.hg4j.api;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Represents the status of files in the working directory compared to the repository state.
 *
 * @apiNote Returned by {@link StatusCommand#call()} (obtained via {@link Hg#status()}); each set
 *     contains repository-relative paths, mirroring real hg's own {@code hg status} categories.
 */
public class Status {
    private final Set<String> added = new LinkedHashSet<>();
    private final Set<String> modified = new LinkedHashSet<>();
    private final Set<String> removed = new LinkedHashSet<>();
    private final Set<String> clean = new LinkedHashSet<>();
    private final Set<String> untracked = new LinkedHashSet<>();

    /** Creates an empty status with no paths in any category. */
    public Status() {
    }

    /**
     * Returns the repository-relative paths newly added (staged for the next commit).
     *
     * @return the mutable set of added paths
     */
    public Set<String> getAdded() {
        return added;
    }

    /**
     * Returns the repository-relative paths modified relative to their tracked revision.
     *
     * @return the mutable set of modified paths
     */
    public Set<String> getModified() {
        return modified;
    }

    /**
     * Returns the repository-relative paths marked for removal.
     *
     * @return the mutable set of removed paths
     */
    public Set<String> getRemoved() {
        return removed;
    }

    /**
     * Returns the repository-relative paths that are tracked and unchanged.
     *
     * @return the mutable set of clean paths
     */
    public Set<String> getClean() {
        return clean;
    }

    /**
     * Returns the repository-relative paths present in the working directory but not tracked.
     *
     * @return the mutable set of untracked paths
     */
    public Set<String> getUntracked() {
        return untracked;
    }
}
