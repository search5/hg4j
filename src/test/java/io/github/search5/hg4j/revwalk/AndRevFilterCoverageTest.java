package io.github.search5.hg4j.revwalk;

import io.github.search5.hg4j.storage.Revlog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage tests for {@link AndRevFilter}: both constructors (varargs and {@link java.util.Collection})
 * and the {@code include} short-circuiting AND-combination logic.
 */
public class AndRevFilterCoverageTest {

    // The Revlog argument is never inspected by any filter under test, so null is safe here.
    private static final Revlog UNUSED_CHANGELOG = null;

    @Test
    @DisplayName("varargs constructor: include() is true when all wrapped filters match")
    void varargsConstructorTrueWhenAllMatch() {
        AndRevFilter filter = new AndRevFilter(RevFilter.ALL, RevFilter.ALL);
        assertTrue(filter.include(1, UNUSED_CHANGELOG));
    }

    @Test
    @DisplayName("varargs constructor: include() is false when any wrapped filter fails to match")
    void varargsConstructorFalseWhenAnyFails() {
        AndRevFilter filter = new AndRevFilter(RevFilter.ALL, RevFilter.NONE);
        assertFalse(filter.include(1, UNUSED_CHANGELOG));
    }

    @Test
    @DisplayName("Collection constructor: include() is true when all wrapped filters match")
    void collectionConstructorTrueWhenAllMatch() {
        AndRevFilter filter = new AndRevFilter(List.of(RevFilter.ALL, RevFilter.ALL, RevFilter.ALL));
        assertTrue(filter.include(1, UNUSED_CHANGELOG));
    }

    @Test
    @DisplayName("Collection constructor: include() is false when any wrapped filter fails to match")
    void collectionConstructorFalseWhenAnyFails() {
        AndRevFilter filter = new AndRevFilter(List.of(RevFilter.ALL, RevFilter.NONE));
        assertFalse(filter.include(1, UNUSED_CHANGELOG));
    }

    @Test
    @DisplayName("include() short-circuits: a non-match on the first filter is returned without consulting later filters")
    void shortCircuitsOnFirstNonMatch() {
        RevFilter blowsUpIfCalled = (rev, cl) -> {
            throw new AssertionError("should not be reached due to short-circuit");
        };
        AndRevFilter filter = new AndRevFilter(RevFilter.NONE, blowsUpIfCalled);
        assertFalse(filter.include(1, UNUSED_CHANGELOG));
    }

    @Test
    @DisplayName("no filters at all: include() is true (empty AND is identity/true)")
    void emptyFilterListReturnsTrue() {
        AndRevFilter filter = new AndRevFilter();
        assertTrue(filter.include(1, UNUSED_CHANGELOG));
    }
}
