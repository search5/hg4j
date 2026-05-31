package com.github.search5.hg4j.revwalk;

import com.github.search5.hg4j.core.Revlog;

/**
 * 특정 RevFilter의 부정(NOT) 논리 조합을 나타내는 필터.
 */
public class NotRevFilter implements RevFilter {
    private final RevFilter filter;

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
