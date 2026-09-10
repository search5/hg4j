package io.github.search5.hg4j.revwalk;

import io.github.search5.hg4j.storage.Revlog;

/**
 * Revision filter interface corresponding to JGit's {@code RevFilter}.
 * Decides whether a specific revision should be included while walking the revision graph.
 *
 * @apiNote {@link io.github.search5.hg4j.revwalk.ChangesetGraph#setRevFilter} is the only
 *     consumer of this interface today; no porcelain command currently constructs a custom
 *     filter (they call {@code ChangesetGraph}'s traversal methods directly without narrowing
 *     the walk). {@code AndRevFilter}/{@code OrRevFilter}/{@code NotRevFilter}/{@code
 *     MaxCountRevFilter} exist as ready-made combinators for callers that do want to filter a
 *     graph walk.
 */
@FunctionalInterface
public interface RevFilter {
    /**
     * Decides whether the given revision satisfies this filter and should be included in the
     * result.
     *
     * @param revision revision number being evaluated
     * @param changelog changelog revlog the revision belongs to
     * @return {@code true} if the revision should be included in the walk result
     */
    boolean include(int revision, Revlog changelog);

    /** The default filter that includes every revision. */
    RevFilter ALL = (rev, cl) -> true;

    /** The default filter that includes no revision. */
    RevFilter NONE = (rev, cl) -> false;

    /**
     * Creates the AND combination of this filter with another.
     *
     * @param other filter to combine with; a {@code null} value leaves this filter unchanged
     * @return a filter that includes a revision only when both this filter and {@code other}
     *     include it (or just this filter if {@code other} is {@code null})
     */
    default RevFilter and(RevFilter other) {
        if (other == null) return this;
        return (rev, cl) -> this.include(rev, cl) && other.include(rev, cl);
    }

    /**
     * Creates the OR combination of this filter with another.
     *
     * @param other filter to combine with; a {@code null} value leaves this filter unchanged
     * @return a filter that includes a revision when either this filter or {@code other}
     *     includes it (or just this filter if {@code other} is {@code null})
     */
    default RevFilter or(RevFilter other) {
        if (other == null) return this;
        return (rev, cl) -> this.include(rev, cl) || other.include(rev, cl);
    }

    /**
     * Creates the negation (NOT) of this filter.
     *
     * @return a filter that includes a revision exactly when this filter excludes it
     */
    default RevFilter negate() {
        return (rev, cl) -> !this.include(rev, cl);
    }
}
