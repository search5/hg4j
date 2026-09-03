package io.github.search5.hg4j.revwalk;

import io.github.search5.hg4j.storage.Revlog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage tests for {@link NotRevFilter}: the null-check guard in the constructor and the
 * negation logic in {@code include}.
 */
public class NotRevFilterCoverageTest {

    // The Revlog argument is never inspected by any filter under test, so null is safe here.
    private static final Revlog UNUSED_CHANGELOG = null;

    @Test
    @DisplayName("constructor: null filter throws IllegalArgumentException")
    void constructorRejectsNullFilter() {
        assertThrows(IllegalArgumentException.class, () -> new NotRevFilter(null));
    }

    @Test
    @DisplayName("include() negates a matching wrapped filter to false")
    void includeNegatesTrueToFalse() {
        NotRevFilter filter = new NotRevFilter(RevFilter.ALL);
        assertFalse(filter.include(1, UNUSED_CHANGELOG));
    }

    @Test
    @DisplayName("include() negates a non-matching wrapped filter to true")
    void includeNegatesFalseToTrue() {
        NotRevFilter filter = new NotRevFilter(RevFilter.NONE);
        assertTrue(filter.include(1, UNUSED_CHANGELOG));
    }
}
