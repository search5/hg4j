package io.github.search5.hg4j.revwalk;

import io.github.search5.hg4j.storage.Revlog;

/**
 * A filter that limits the number of revisions returned.
 *
 * @apiNote Stateful — each instance counts how many revisions it has included so far and stops
 *     including more once {@code maxCount} is reached, so a single instance must not be shared
 *     across concurrent or repeated walks without calling {@link #reset()} first.
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

    /** Resets the filter's internal counter back to zero. */
    public void reset() {
        this.count = 0;
    }
}
