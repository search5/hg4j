package io.github.search5.hg4j.revwalk;

import io.github.search5.hg4j.core.Revlog;

/**
 * 반환할 리비전의 최대 개수를 제약하는 필터.
 */
public class MaxCountRevFilter implements RevFilter {
    private final int maxCount;
    private int count = 0;

    public MaxCountRevFilter(int maxCount) {
        if (maxCount < 0) {
            throw new IllegalArgumentException("Max count must be non-negative");
        }
        this.maxCount = maxCount;
    }

    @Override
    public boolean include(int revision, Revlog changelog) {
        if (count < maxCount) {
            count++;
            return true;
        }
        return false;
    }

    /**
     * 필터 상태를 리셋합니다.
     */
    public void reset() {
        this.count = 0;
    }
}
