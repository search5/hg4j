package io.github.search5.hg4j.revwalk;

import io.github.search5.hg4j.storage.Revlog;
import java.util.Collection;
import java.util.List;

/**
 * 여러 RevFilter들의 AND 논리 조합을 나타내는 필터.
 */
public class AndRevFilter implements RevFilter {
    private final List<RevFilter> filters;

    /**
     * Creates a filter that includes a revision only if every given filter includes it. Filters are
     * evaluated in iteration order and evaluation stops at the first one that rejects. With no filters
     * every revision is included.
     * @param filters the filters to combine; the collection is copied and neither it nor its elements may be null
     */
    public AndRevFilter(Collection<RevFilter> filters) {
        this.filters = List.copyOf(filters);
    }

    /**
     * Creates a filter that includes a revision only if every given filter includes it. Filters are
     * evaluated in order and evaluation stops at the first one that rejects. With no filters every
     * revision is included.
     * @param filters the filters to combine; neither the array nor its elements may be null
     */
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
