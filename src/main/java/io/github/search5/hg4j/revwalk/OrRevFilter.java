package io.github.search5.hg4j.revwalk;

import io.github.search5.hg4j.storage.Revlog;
import java.util.Collection;
import java.util.List;

/**
 * 여러 RevFilter들의 OR 논리 조합을 나타내는 필터.
 */
public class OrRevFilter implements RevFilter {
    private final List<RevFilter> filters;

    /**
     * Creates a filter that includes a revision if at least one given filter includes it. Filters are
     * evaluated in iteration order and evaluation stops at the first one that accepts. With no filters
     * no revision is included.
     * @param filters the filters to combine; the collection is copied and neither it nor its elements may be null
     */
    public OrRevFilter(Collection<RevFilter> filters) {
        this.filters = List.copyOf(filters);
    }

    /**
     * Creates a filter that includes a revision if at least one given filter includes it. Filters are
     * evaluated in order and evaluation stops at the first one that accepts. With no filters no
     * revision is included.
     * @param filters the filters to combine; neither the array nor its elements may be null
     */
    public OrRevFilter(RevFilter... filters) {
        this.filters = List.of(filters);
    }

    @Override
    public boolean include(int revision, Revlog changelog) {
        for (RevFilter f : filters) {
            if (f.include(revision, changelog)) {
                return true;
            }
        }
        return false;
    }
}
