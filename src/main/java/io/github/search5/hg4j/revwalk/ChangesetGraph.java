package io.github.search5.hg4j.revwalk;

import io.github.search5.hg4j.storage.Revlog;
import java.util.*;
import java.util.function.Function;

/**
 * 리비전 로그의 DAG 그래프 탐색 및 LCA(최근공통조상) 후보 연산을 전담하는 클래스.
 */
public class ChangesetGraph {

    private final Revlog changelog;
    private final Map<Long, Boolean> ancestorCache = new HashMap<>();
    private RevFilter revFilter = RevFilter.ALL;
    private SortOrder sortOrder = SortOrder.DEFAULT;

    /**
     * Creates a graph over the given changelog.
     * <p>The instance caches ancestor queries and its settings are not synchronized.
     *
     * @param changelog the changelog whose index supplies parent links when no custom parent lookup is
     *        passed; if null, the default lookup reports no parents. It is also handed to the revision filter
     */
    public ChangesetGraph(Revlog changelog) {
        this.changelog = changelog;
    }

    /**
     * Sets the order used by {@link #lazyAncestors(int)}.
     * @param sortOrder the order; null selects {@link SortOrder#DEFAULT}
     */
    public void setSortOrder(SortOrder sortOrder) {
        this.sortOrder = sortOrder != null ? sortOrder : SortOrder.DEFAULT;
    }

    /**
     * Returns the current traversal order.
     * @return the order; never null
     */
    public SortOrder getSortOrder() {
        return this.sortOrder;
    }

    /**
     * Sets the filter applied by {@link #lazyAncestors(int)}. It does not affect the other queries.
     * @param revFilter the filter; null selects {@link RevFilter#ALL}
     */
    public void setRevFilter(RevFilter revFilter) {
        this.revFilter = revFilter != null ? revFilter : RevFilter.ALL;
    }

    /**
     * Returns the current revision filter.
     * @return the filter; never null
     */
    public RevFilter getRevFilter() {
        return this.revFilter;
    }

    /**
     * 필터 조건에 구애받지 않고 지정한 리비전의 모든 조상 노드를 반환합니다 (내부 DAG 연산용).
     */
    public Set<Integer> getAllAncestors(int startRev) {
        return getAllAncestors(startRev, getRevlogLookup());
    }

    /**
     * Returns the given revision and all of its ancestors, ignoring the revision filter.
     * @param startRev the revision to start from; {@code -1} (the null revision) yields an empty set
     * @param parentLookup maps a revision number to its parent revision numbers, using {@code -1} for a
     *        missing parent; it may return null for a revision without parents. Must not be null
     * @return a new set containing {@code startRev} and its ancestors, in breadth-first discovery order
     */
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
     * 지정한 리비전부터 조상을 lazy하게 순회하는 Iterator를 반환하며, RevFilter를 반영합니다.
     * 대형 저장소에서 메모리를 효율적으로 유지하고 JGit RevWalk 스타일의 스트리밍을 제공합니다.
     */
    public Iterator<Integer> lazyAncestors(int startRev) {
        return lazyAncestors(startRev, getRevlogLookup());
    }

    /**
     * Returns an iterator over the given revision and its ancestors that pass the revision filter.
     * <p>With {@link SortOrder#DEFAULT} the order is breadth-first from {@code startRev} and revisions are
     * produced lazily. With {@link SortOrder#TOPO} the whole ancestry is computed up front and every
     * revision precedes all of its parents. Each revision appears once. The iterator is single-use and
     * not thread-safe.
     * @param startRev the revision to start from; {@code -1} yields nothing
     * @param parentLookup maps a revision number to its parent revision numbers, using {@code -1} for a
     *        missing parent; it may return null for a revision without parents. Must not be null
     * @return a new iterator of revision numbers
     */
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
     * Tells whether one revision is an ancestor of another, using the changelog's parent links. A revision
     * is considered an ancestor of itself.
     * @param ancestor the candidate ancestor revision number
     * @param descendant the candidate descendant revision number
     * @return true if {@code ancestor} equals {@code descendant} or is reachable from it through parent links
     */
    public boolean isAncestor(int ancestor, int descendant) {
        return isAncestor(ancestor, descendant, getRevlogLookup());
    }

    /**
     * Tells whether one revision is an ancestor of another, using the given parent lookup. A revision is
     * considered an ancestor of itself.
     * <p>Results are cached per pair of revisions in this instance, independent of the lookup function
     * passed, so use one lookup consistently. The cache access is synchronized.
     * @param ancestor the candidate ancestor revision number
     * @param descendant the candidate descendant revision number
     * @param parentLookup maps a revision number to its parent revision numbers, using {@code -1} for a
     *        missing parent; it may return null for a revision without parents. Must not be null
     * @return true if {@code ancestor} equals {@code descendant} or is reachable from it through parent links
     */
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
     * Computes the least common ancestor candidates of two revisions using the changelog's parent links.
     * See {@link #getLcaCandidates(int, int, Function)}.
     * @param revA the first revision number
     * @param revB the second revision number
     * @return a new set of candidate revision numbers
     */
    public Set<Integer> getLcaCandidates(int revA, int revB) {
        return getLcaCandidates(revA, revB, getRevlogLookup());
    }

    /**
     * Computes the least common ancestor candidates of two revisions: the common ancestors (a revision
     * counts as its own ancestor) that are not ancestors of another candidate. There can be more than one
     * after criss-cross merges.
     * @param revA the first revision number
     * @param revB the second revision number
     * @param parentLookup maps a revision number to its parent revision numbers, using {@code -1} for a
     *        missing parent; it may return null for a revision without parents. Must not be null
     * @return a new set of candidate revision numbers; contains just {@code revA} when both are equal
     */
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
