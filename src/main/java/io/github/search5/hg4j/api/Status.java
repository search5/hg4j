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

    public Set<String> getAdded() {
        return added;
    }

    public Set<String> getModified() {
        return modified;
    }

    public Set<String> getRemoved() {
        return removed;
    }

    public Set<String> getClean() {
        return clean;
    }

    public Set<String> getUntracked() {
        return untracked;
    }
}
