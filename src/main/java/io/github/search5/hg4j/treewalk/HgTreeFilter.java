package io.github.search5.hg4j.treewalk;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import io.github.search5.hg4j.treewalk.PathFilter;

/**
 * Filter interface for pruning and matching file paths during SCM tree walks.
 * Inspired by JGit's TreeFilter api to support narrow/sparse operations.
 */
public abstract class HgTreeFilter implements PathFilter {

    /**
     * Creates a bridge filter that wraps a generic PathFilter.
     */
    public static HgTreeFilter fromPathFilter(final PathFilter pathFilter) {
        if (pathFilter == null) {
            return ALL;
        }
        if (pathFilter instanceof HgTreeFilter) {
            return (HgTreeFilter) pathFilter;
        }
        return new HgTreeFilter() {
            @Override
            public boolean accept(String path) {
                return pathFilter.accept(path);
            }
        };
    }

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
