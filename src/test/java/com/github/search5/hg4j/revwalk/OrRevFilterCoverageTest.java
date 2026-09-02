package com.github.search5.hg4j.revwalk;

import com.github.search5.hg4j.storage.Revlog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage tests for {@link OrRevFilter}: both constructors (varargs and {@link java.util.Collection})
 * and the {@code include} short-circuiting OR-combination logic.
 */
public class OrRevFilterCoverageTest {

    // The Revlog argument is never inspected by any filter under test, so null is safe here.
    private static final Revlog UNUSED_CHANGELOG = null;

    @Test
    @DisplayName("varargs constructor: include() is true when any wrapped filter matches")
    void varargsConstructorTrueWhenAnyMatches() {
        OrRevFilter filter = new OrRevFilter(RevFilter.NONE, RevFilter.ALL);
        assertTrue(filter.include(1, UNUSED_CHANGELOG));
    }

    @Test
    @DisplayName("varargs constructor: include() is false when no wrapped filter matches")
    void varargsConstructorFalseWhenNoneMatch() {
        OrRevFilter filter = new OrRevFilter(RevFilter.NONE, RevFilter.NONE);
        assertFalse(filter.include(1, UNUSED_CHANGELOG));
    }

    @Test
    @DisplayName("Collection constructor: include() is true when any wrapped filter matches")
    void collectionConstructorTrueWhenAnyMatches() {
        OrRevFilter filter = new OrRevFilter(List.of(RevFilter.NONE, RevFilter.NONE, RevFilter.ALL));
        assertTrue(filter.include(1, UNUSED_CHANGELOG));
    }

    @Test
    @DisplayName("Collection constructor: include() is false when no wrapped filter matches")
    void collectionConstructorFalseWhenNoneMatch() {
        OrRevFilter filter = new OrRevFilter(List.of(RevFilter.NONE, RevFilter.NONE));
        assertFalse(filter.include(1, UNUSED_CHANGELOG));
    }

    @Test
    @DisplayName("include() short-circuits: a match on the first filter is returned without consulting later filters")
    void shortCircuitsOnFirstMatch() {
        RevFilter blowsUpIfCalled = (rev, cl) -> {
            throw new AssertionError("should not be reached due to short-circuit");
        };
        OrRevFilter filter = new OrRevFilter(RevFilter.ALL, blowsUpIfCalled);
        assertTrue(filter.include(1, UNUSED_CHANGELOG));
    }

    @Test
    @DisplayName("no filters at all: include() is false (empty OR is identity/false)")
    void emptyFilterListReturnsFalse() {
        OrRevFilter filter = new OrRevFilter();
        assertFalse(filter.include(1, UNUSED_CHANGELOG));
    }
}
