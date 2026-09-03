package io.github.search5.hg4j.revwalk;

import io.github.search5.hg4j.storage.Revlog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage tests for the {@link RevFilter} functional interface: the {@code ALL} / {@code NONE}
 * constant filters and the default {@code and}, {@code or}, and {@code negate} combinators.
 *
 * <p>{@code RevFilter} is not itself a revset/CLI-facing feature that maps to a specific
 * {@code hg log -r} expression; it is a JGit-{@code RevFilter}-style predicate abstraction used
 * internally by {@link ChangesetGraph#setRevFilter(RevFilter)} /
 * {@link ChangesetGraph#lazyAncestors(int)} to gate which revisions a graph walk yields. The
 * combinator methods ({@code and}/{@code or}/{@code negate}) and the sibling composite classes
 * ({@link AndRevFilter}, {@link OrRevFilter}, {@link NotRevFilter}, {@link MaxCountRevFilter})
 * are not yet wired to any production call site beyond {@code RevFilter.ALL} itself, so these
 * tests exercise the interface's own logic directly (pure boolean algebra, no hg-specific wire
 * format or CLI behavior to verify against real {@code hg}).
 */
public class RevFilterCoverageTest {

    // The Revlog argument is never inspected by any filter under test, so null is safe here.
    private static final Revlog UNUSED_CHANGELOG = null;

    @Test
    @DisplayName("ALL includes every revision")
    void allIncludesEveryRevision() {
        assertTrue(RevFilter.ALL.include(0, UNUSED_CHANGELOG));
        assertTrue(RevFilter.ALL.include(42, UNUSED_CHANGELOG));
    }

    @Test
    @DisplayName("NONE excludes every revision")
    void noneExcludesEveryRevision() {
        assertFalse(RevFilter.NONE.include(0, UNUSED_CHANGELOG));
        assertFalse(RevFilter.NONE.include(42, UNUSED_CHANGELOG));
    }

    @Test
    @DisplayName("and(null) returns the same filter instance (identity)")
    void andWithNullReturnsSameInstance() {
        RevFilter filter = RevFilter.ALL;
        assertSame(filter, filter.and(null));
    }

    @Test
    @DisplayName("or(null) returns the same filter instance (identity)")
    void orWithNullReturnsSameInstance() {
        RevFilter filter = RevFilter.ALL;
        assertSame(filter, filter.or(null));
    }

    @Test
    @DisplayName("and() is true only when both operands are true, short-circuiting when the left side is false")
    void andCombinesBothOperands() {
        // left=false -> short-circuits, right never needs to be true for this case
        assertFalse(RevFilter.NONE.and(RevFilter.ALL).include(1, UNUSED_CHANGELOG));
        // left=true, right=true -> true
        assertTrue(RevFilter.ALL.and(RevFilter.ALL).include(1, UNUSED_CHANGELOG));
        // left=true, right=false -> false
        assertFalse(RevFilter.ALL.and(RevFilter.NONE).include(1, UNUSED_CHANGELOG));
        // left=false, right=false -> false
        assertFalse(RevFilter.NONE.and(RevFilter.NONE).include(1, UNUSED_CHANGELOG));
    }

    @Test
    @DisplayName("or() is true when either operand is true, short-circuiting when the left side is true")
    void orCombinesEitherOperand() {
        // left=true -> short-circuits, right never needs to be true for this case
        assertTrue(RevFilter.ALL.or(RevFilter.NONE).include(1, UNUSED_CHANGELOG));
        // left=false, right=true -> true
        assertTrue(RevFilter.NONE.or(RevFilter.ALL).include(1, UNUSED_CHANGELOG));
        // left=false, right=false -> false
        assertFalse(RevFilter.NONE.or(RevFilter.NONE).include(1, UNUSED_CHANGELOG));
        // left=true, right=true -> true
        assertTrue(RevFilter.ALL.or(RevFilter.ALL).include(1, UNUSED_CHANGELOG));
    }

    @Test
    @DisplayName("negate() flips the result of the wrapped filter")
    void negateFlipsResult() {
        assertFalse(RevFilter.ALL.negate().include(1, UNUSED_CHANGELOG));
        assertTrue(RevFilter.NONE.negate().include(1, UNUSED_CHANGELOG));
    }
}
