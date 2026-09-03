package io.github.search5.hg4j.revwalk;

import io.github.search5.hg4j.storage.Revlog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage tests for {@link MaxCountRevFilter}: constructor validation (negative vs. non-negative
 * maxCount), the {@code include} counting/threshold logic, and {@code reset()}.
 */
public class MaxCountRevFilterCoverageTest {

    // The Revlog argument is never inspected by MaxCountRevFilter, so null is safe here.
    private static final Revlog UNUSED_CHANGELOG = null;

    @Test
    @DisplayName("constructor: negative maxCount throws IllegalArgumentException")
    void constructorRejectsNegativeMaxCount() {
        assertThrows(IllegalArgumentException.class, () -> new MaxCountRevFilter(-1));
    }

    @Test
    @DisplayName("constructor: zero maxCount is accepted, and include() always returns false")
    void zeroMaxCountNeverIncludes() {
        MaxCountRevFilter filter = new MaxCountRevFilter(0);
        assertFalse(filter.include(1, UNUSED_CHANGELOG));
        assertFalse(filter.include(2, UNUSED_CHANGELOG));
    }

    @Test
    @DisplayName("include(): returns true until maxCount matches are reached, then false")
    void includeReturnsTrueUntilMaxCountThenFalse() {
        MaxCountRevFilter filter = new MaxCountRevFilter(2);
        assertTrue(filter.include(1, UNUSED_CHANGELOG));
        assertTrue(filter.include(2, UNUSED_CHANGELOG));
        assertFalse(filter.include(3, UNUSED_CHANGELOG));
        assertFalse(filter.include(4, UNUSED_CHANGELOG));
    }

    @Test
    @DisplayName("reset(): clears internal count so include() allows maxCount matches again")
    void resetAllowsCountingAgain() {
        MaxCountRevFilter filter = new MaxCountRevFilter(1);
        assertTrue(filter.include(1, UNUSED_CHANGELOG));
        assertFalse(filter.include(2, UNUSED_CHANGELOG));

        filter.reset();

        assertTrue(filter.include(3, UNUSED_CHANGELOG));
        assertFalse(filter.include(4, UNUSED_CHANGELOG));
    }
}
