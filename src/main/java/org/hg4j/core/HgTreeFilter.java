package org.hg4j.core;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Filter interface for pruning and matching file paths during SCM tree walks.
 * Inspired by JGit's TreeFilter api to support narrow/sparse operations.
 */
public abstract class HgTreeFilter {

    /**
     * Determines whether the specified path matches the filter criteria.
     *
     * @param path relative file path from repository root
     * @return true if the path is matched and should be traversed
     */
    public abstract boolean accept(String path);

    /**
     * Default filter that accepts all paths.
     */
    public static final HgTreeFilter ALL = new HgTreeFilter() {
        @Override
        public boolean accept(String path) {
            return true;
        }
    };

    /**
     * Creates a filter that matches only paths matching specific prefix rules.
     * Useful for sparse checkout or narrow clone scenarios.
     */
    public static HgTreeFilter createPathPrefixFilter(Collection<String> includePrefixes, Collection<String> excludePrefixes) {
        final Set<String> includes = includePrefixes != null ? new HashSet<>(includePrefixes) : Set.of();
        final Set<String> excludes = excludePrefixes != null ? new HashSet<>(excludePrefixes) : Set.of();

        return new HgTreeFilter() {
            @Override
            public boolean accept(String path) {
                if (path == null) return false;
                
                // Exclude matches first
                for (String ex : excludes) {
                    if (path.startsWith(ex)) {
                        return false;
                    }
                }

                // If no includes are specified, accept all (except those excluded)
                if (includes.isEmpty()) {
                    return true;
                }

                // Check include matches
                for (String inc : includes) {
                    if (path.startsWith(inc)) {
                        return true;
                    }
                }

                return false;
            }
        };
    }
}
