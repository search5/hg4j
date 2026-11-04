package io.github.search5.hg4j.revwalk;

import io.github.search5.hg4j.storage.Revlog;

/**
 * 반환할 리비전의 최대 개수를 제약하는 필터.
 */
public class MaxCountRevFilter implements RevFilter {
    private final int maxCount;
    private int count = 0;

    /**
     * Creates a filter that accepts the first {@code maxCount} revisions it is asked about and rejects all
     * later ones, regardless of the revision. The filter is stateful, so use a fresh instance (or call
     * {@link #reset()}) for each traversal; it is not thread-safe.
     * @param maxCount the maximum number of revisions to accept; must not be negative
     * @throws IllegalArgumentException if {@code maxCount} is negative
     */
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
