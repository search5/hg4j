package io.github.search5.hg4j.revwalk;

import io.github.search5.hg4j.storage.Revlog;
import java.util.*;
import java.util.function.Function;

/**
 * Class dedicated to DAG graph traversal of a revision log and computing LCA (lowest common
 * ancestor) candidates.
 *
 * @apiNote The shared revision-graph engine behind history/merge-base logic. {@link
 *     #getAllAncestors} is used by {@code LogCommand} to restrict output to a starting
 *     revision's own history; {@link #isAncestor} is used by {@code BackoutCommand}, {@code
 *     MergeCommand}, {@code BookmarkCommand}, and {@code RebaseCommand} for fast-forward/
 *     ancestry checks; {@link #getLcaCandidates} is used by {@code MergeCommand} to find the
 *     merge base(s) of two revisions. {@link #lazyAncestors} (with {@link #setSortOrder}) exists
 *     for streaming, memory-conscious traversal of large histories but has no callers yet.
 */
public class ChangesetGraph {

    private final Revlog changelog;
    private final Map<Long, Boolean> ancestorCache = new HashMap<>();
    private RevFilter revFilter = RevFilter.ALL;
    private SortOrder sortOrder = SortOrder.DEFAULT;

    public ChangesetGraph(Revlog changelog) {
        this.changelog = changelog;
    }

    public void setSortOrder(SortOrder sortOrder) {
        this.sortOrder = sortOrder != null ? sortOrder : SortOrder.DEFAULT;
    }

    public SortOrder getSortOrder() {
        return this.sortOrder;
    }

    public void setRevFilter(RevFilter revFilter) {
        this.revFilter = revFilter != null ? revFilter : RevFilter.ALL;
    }

    public RevFilter getRevFilter() {
        return this.revFilter;
    }

    /**
     * Returns every ancestor of the given revision, regardless of any filter condition (for
     * internal DAG computations).
     *
     * @apiNote Used by {@code LogCommand} to compute the full ancestor set of a starting
     *     revision, so history output can be restricted to it.
     */
    public Set<Integer> getAllAncestors(int startRev) {
        return getAllAncestors(startRev, getRevlogLookup());
    }

    public Set<Integer> getAllAncestors(int startRev, Function<Integer, int[]> parentLookup) {
        Set<Integer> ancestors = new LinkedHashSet<>();
        Queue<Integer> queue = new ArrayDeque<>();
        queue.add(startRev);

        while (!queue.isEmpty()) {
            int current = queue.poll();
            if (current == -1) {
                continue;
            }
            if (ancestors.add(current)) {
                int[] parents = parentLookup.apply(current);
                if (parents != null) {
                    for (int p : parents) {
                        if (p != -1) {
                            queue.add(p);
                        }
                    }
                }
            }
        }
        return ancestors;
    }

    /**
     * Returns an {@link Iterator} that lazily walks ancestors starting from the given revision,
     * honoring the configured {@link RevFilter}. Keeps memory usage low on large repositories and
     * provides JGit-{@code RevWalk}-style streaming.
     *
     * @apiNote Has no callers today (see the class-level {@code apiNote}); provided as a
     *     ready-made streaming alternative to {@link #getAllAncestors}, which materializes the
     *     entire ancestor set at once.
     */
    public Iterator<Integer> lazyAncestors(int startRev) {
        return lazyAncestors(startRev, getRevlogLookup());
    }

    public Iterator<Integer> lazyAncestors(int startRev, Function<Integer, int[]> parentLookup) {
        if (sortOrder == SortOrder.TOPO) {
            List<Integer> result = new ArrayList<>();
            Set<Integer> visited = new HashSet<>();
            dfs(startRev, parentLookup, visited, result);
            Collections.reverse(result);

            // Filter the elements in the buffered topological order list
            List<Integer> filtered = new ArrayList<>();
            for (int r : result) {
                if (revFilter.include(r, changelog)) {
                    filtered.add(r);
                }
            }
            return filtered.iterator();
        }

        // DEFAULT BFS Iterator
        return new Iterator<Integer>() {
            private final Queue<Integer> queue = new ArrayDeque<>(List.of(startRev));
            private final Set<Integer> visited = new HashSet<>();
            private Integer nextVal = null;

            @Override
            public boolean hasNext() {
                if (nextVal != null) {
                    return true;
                }
                while (!queue.isEmpty()) {
                    int current = queue.poll();
                    if (current == -1) {
                        continue;
                    }
                    if (visited.add(current)) {
                        int[] parents = parentLookup.apply(current);
                        if (parents != null) {
                            for (int p : parents) {
                                if (p != -1) {
                                    queue.add(p);
                                }
                            }
                        }
                        if (revFilter.include(current, changelog)) {
                            nextVal = current;
                            return true;
                        }
                    }
                }
                return false;
            }

            @Override
            public Integer next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Integer val = nextVal;
                nextVal = null;
                return val;
            }
        };
    }

    private void dfs(int u, Function<Integer, int[]> parentLookup, Set<Integer> visited, List<Integer> result) {
        if (u == -1 || visited.contains(u)) {
            return;
        }
        visited.add(u);
        int[] parents = parentLookup.apply(u);
        if (parents != null) {
            for (int p : parents) {
                if (p != -1) {
                    dfs(p, parentLookup, visited, result);
                }
            }
        }
        result.add(u);
    }

    /**
     * Checks whether {@code ancestor} is an ancestor of (or equal to) {@code descendant}.
     * Results are cached per instance.
     *
     * @apiNote The workhorse ancestry check used by {@code BackoutCommand} (verifying the target
     *     is reachable from the current parent), {@code MergeCommand} (detecting a trivial
     *     fast-forward merge), {@code BookmarkCommand} (deciding whether moving a bookmark is a
     *     fast-forward), and {@code RebaseCommand} (checking whether a revision is already an
     *     ancestor of the destination).
     */
    public boolean isAncestor(int ancestor, int descendant) {
        return isAncestor(ancestor, descendant, getRevlogLookup());
    }

    public boolean isAncestor(int ancestor, int descendant, Function<Integer, int[]> parentLookup) {
        if (ancestor == descendant) {
            return true;
        }
        if (ancestor > descendant) {
            return false;
        }

        long cacheKey = ((long) ancestor << 32) | (descendant & 0xFFFFFFFFL);
        synchronized (ancestorCache) {
            if (ancestorCache.containsKey(cacheKey)) {
                return ancestorCache.get(cacheKey);
            }
        }

        Queue<Integer> queue = new ArrayDeque<>();
        queue.add(descendant);
        Set<Integer> visited = new HashSet<>();
        boolean result = false;

        while (!queue.isEmpty()) {
            int current = queue.poll();
            if (current == ancestor) {
                result = true;
                break;
            }
            if (current == -1 || current < ancestor) {
                continue;
            }
            if (visited.add(current)) {
                int[] parents = parentLookup.apply(current);
                if (parents != null) {
                    for (int p : parents) {
                        if (p != -1) {
                            queue.add(p);
                        }
                    }
                }
            }
        }

        synchronized (ancestorCache) {
            ancestorCache.put(cacheKey, result);
        }
        return result;
    }

    /**
     * Computes the LCA (lowest common ancestor / merge base) candidate set of two revisions,
     * using a flag-propagation graph walk.
     *
     * @apiNote Used by {@code MergeCommand} to find the merge base(s) for a 3-way merge; may
     *     return more than one candidate for a criss-cross merge history, matching real hg's own
     *     ambiguous-ancestor handling.
     */
    public Set<Integer> getLcaCandidates(int revA, int revB) {
        return getLcaCandidates(revA, revB, getRevlogLookup());
    }

    public Set<Integer> getLcaCandidates(int revA, int revB, Function<Integer, int[]> parentLookup) {
        if (revA == revB) {
            return Set.of(revA);
        }

        int PARENT1 = 1;
        int PARENT2 = 2;
        int MERGE_BASE = 4;

        PriorityQueue<Integer> queue = new PriorityQueue<>((a, b) -> Integer.compare(b, a));
        Map<Integer, Integer> flags = new HashMap<>();
        Set<Integer> inQueue = new HashSet<>();

        flags.put(revA, PARENT1);
        queue.add(revA);
        inQueue.add(revA);

        int fB = flags.getOrDefault(revB, 0);
        flags.put(revB, fB | PARENT2);
        if (inQueue.add(revB)) {
            queue.add(revB);
        }

        Set<Integer> candidates = new HashSet<>();

        while (!queue.isEmpty()) {
            int curr = queue.poll();
            inQueue.remove(curr);

            int f = flags.getOrDefault(curr, 0);

            if ((f & (PARENT1 | PARENT2)) == (PARENT1 | PARENT2)) {
                if ((f & MERGE_BASE) == 0) {
                    candidates.add(curr);
                    f |= MERGE_BASE;
                    flags.put(curr, f);
                }
            }

            int[] parents = parentLookup.apply(curr);
            if (parents != null) {
                for (int p : parents) {
                    if (p != -1) {
                        int pf = flags.getOrDefault(p, 0);
                        int newPf = pf | f;
                        
                        if (newPf != pf) {
                            flags.put(p, newPf);
                            if (inQueue.add(p)) {
                                queue.add(p);
                            }
                        }
                    }
                }
            }
        }

        return candidates;
    }

    private Function<Integer, int[]> getRevlogLookup() {
        return rev -> {
            if (changelog == null || rev == -1) {
                return new int[]{-1, -1};
            }
            Revlog.IndexRecord rec = changelog.getIndexRecord(rev);
            return new int[]{rec.getParent1(), rec.getParent2()};
        };
    }
}
