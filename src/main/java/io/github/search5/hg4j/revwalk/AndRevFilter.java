package io.github.search5.hg4j.revwalk;

import io.github.search5.hg4j.storage.Revlog;
import java.util.Collection;
import java.util.List;

/**
 * A filter representing the logical AND combination of several {@link RevFilter}s.
 *
 * @apiNote Equivalent to chaining {@link RevFilter#and(RevFilter)} calls; provided as a
 *     convenience for combining a {@link java.util.Collection} or varargs list of filters at
 *     once, e.g. before passing the result to {@link ChangesetGraph#setRevFilter}.
 */
public class AndRevFilter implements RevFilter {
    private final List<RevFilter> filters;

    public AndRevFilter(Collection<RevFilter> filters) {
        this.filters = List.copyOf(filters);
    }

    public AndRevFilter(RevFilter... filters) {
        this.filters = List.of(filters);
    }

    @Override
    public boolean include(int revision, Revlog changelog) {
        for (RevFilter f : filters) {
            if (!f.include(revision, changelog)) {
                return false;
            }
        }
        return true;
    }
}
