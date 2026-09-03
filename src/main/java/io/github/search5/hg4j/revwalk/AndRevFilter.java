package io.github.search5.hg4j.revwalk;

import io.github.search5.hg4j.storage.Revlog;
import java.util.Collection;
import java.util.List;

/**
 * 여러 RevFilter들의 AND 논리 조합을 나타내는 필터.
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
