package io.github.search5.hg4j.revwalk;

/**
 * Enumerates the traversal order used by {@link ChangesetGraph}.
 *
 * @apiNote Set via {@link ChangesetGraph#setSortOrder}, consulted only by {@link
 *     ChangesetGraph#lazyAncestors}. No porcelain command currently sets a non-default sort
 *     order — every caller of {@code ChangesetGraph} (e.g. {@code LogCommand}, {@code
 *     BackoutCommand}, {@code MergeCommand}, {@code RebaseCommand}, {@code BookmarkCommand}) uses
 *     the default breadth-first order today, or calls {@link
 *     ChangesetGraph#getAllAncestors}/{@link ChangesetGraph#isAncestor}/{@link
 *     ChangesetGraph#getLcaCandidates} instead, which are order-independent.
 */
public enum SortOrder {
    /** The default breadth-first traversal order. */
    DEFAULT,

    /** Topological order, where a child always comes before its parent. */
    TOPO
}
