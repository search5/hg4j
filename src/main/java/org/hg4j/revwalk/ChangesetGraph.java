package org.hg4j.revwalk;

import org.hg4j.core.Revlog;
import java.util.*;
import java.util.function.Function;

/**
 * 리비전 로그의 DAG 그래프 탐색 및 LCA(최근공통조상) 후보 연산을 전담하는 클래스.
 */
public class ChangesetGraph {

    private final Revlog changelog;
    private final Map<Long, Boolean> ancestorCache = new HashMap<>();

    public ChangesetGraph(Revlog changelog) {
        this.changelog = changelog;
    }

    public Set<Integer> getAllAncestors(int startRev) {
        return getAllAncestors(startRev, getRevlogLookup());
    }

    public Set<Integer> getAllAncestors(int startRev, Function<Integer, int[]> parentLookup) {
        Set<Integer> ancestors = new HashSet<>();
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

    public boolean isAncestor(int ancestor, int descendant) {
        return isAncestor(ancestor, descendant, getRevlogLookup());
    }

    public boolean isAncestor(int ancestor, int descendant, Function<Integer, int[]> parentLookup) {
        if (ancestor == descendant) {
            return true;
        }
        if (ancestor > descendant) {
            // Mercurial 리비전 특성상 조상은 항상 자손보다 작거나 같은 리비전 번호를 가집니다.
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
