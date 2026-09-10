package io.github.search5.hg4j.revwalk;

import io.github.search5.hg4j.storage.Revlog;

/**
 * A filter representing the logical negation (NOT) of another {@link RevFilter}.
 *
 * @apiNote Equivalent to {@link RevFilter#negate()}; provided for symmetry with {@link
 *     AndRevFilter}/{@link OrRevFilter} as an explicit, named type.
 */
public class NotRevFilter implements RevFilter {
    private final RevFilter filter;

    /**
     * Wraps the given filter so this filter's {@link #include} negates its result.
     *
     * @param filter the filter to negate; must not be {@code null}
     */
    public NotRevFilter(RevFilter filter) {
        if (filter == null) {
            throw new IllegalArgumentException("Filter cannot be null");
        }
        this.filter = filter;
    }

    @Override
    public boolean include(int revision, Revlog changelog) {
        return !filter.include(revision, changelog);
    }
}
