package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgValidationException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Where each changeset of a rebase goes and with which parents, as {@code hgext/rebase.py} works it out: {@code sortsource},
 * {@code adjustdest} and {@code defineparents}. Pure functions over the revision graph; see {@code llm-wiki/concepts/rebase-original.md},
 * section 6. Revisions are numbers, {@code -1} is the null revision; obsolescence is off, so a changeset has no successors.
 */
final class RebasePlanner {

    /** {@code revtodo}. */
    static final int TODO = -1;
    static final int NULL = -1;

    /** The graph: parents of a revision, {@code -1} for none. */
    interface Graph {
        int size();

        int[] parents(int rev);
    }

    /** {@code defineparents}' answer. {@code base} is {@code -1} for the null revision. */
    record Parents(int p1, int p2, int base) {
    }

    private final Graph graph;
    private final Map<Integer, Set<Integer>> ancestorCache = new HashMap<>();

    RebasePlanner(Graph graph) {
        this.graph = graph;
    }

    /** The parents of a revision. */
    int[] parentsOf(int rev) {
        return rev < 0 ? new int[] {NULL, NULL} : graph.parents(rev);
    }

    /** All ancestors of {@code rev}, itself included. */
    Set<Integer> ancestors(int rev) {
        if (rev < 0) {
            return Set.of();
        }
        Set<Integer> cached = ancestorCache.get(rev);
        if (cached != null) {
            return cached;
        }
        Set<Integer> out = new HashSet<>();
        out.add(rev);
        for (int p : graph.parents(rev)) {
            if (p >= 0) {
                out.addAll(ancestors(p));
            }
        }
        ancestorCache.put(rev, out);
        return out;
    }

    /** {@code changelog.isancestorrev(a, b)}: {@code a} is {@code b} or one of its ancestors; the null revision is everyone's ancestor. */
    boolean isAncestor(int a, int b) {
        if (a == NULL) {
            return true;
        }
        if (b == NULL || a > b) {
            return false;
        }
        return ancestors(b).contains(a);
    }

    // ---------------------------------------------------------------- sort(%ld, -topo)

    /**
     * {@code sort(revs, -topo)}: {@code dagop.toposort} (heads to roots, one branch at a time, branches that end in the same parent kept
     * together) read backwards, so the ancestors come first and a branch is finished before the next one starts.
     */
    List<Integer> topoOrder(java.util.Collection<Integer> revs) {
        List<Integer> descending = new ArrayList<>(revs);
        descending.sort(java.util.Collections.reverseOrder());
        List<Integer> out = new ArrayList<>();
        // groups: {revs waiting to be shown, revs they are blocked on}; the first one is the already shown (it has no revs)
        List<List<Integer>> groupRevs = new ArrayList<>();
        List<Set<Integer>> groupBlocked = new ArrayList<>();
        Set<Integer> unblocked = new HashSet<>();
        groupRevs.add(new ArrayList<>());
        groupBlocked.add(unblocked);
        java.util.PriorityQueue<Integer> pending = new java.util.PriorityQueue<>(java.util.Collections.reverseOrder());
        Set<Integer> pendingSet = new HashSet<>();
        for (int currentRev : descending) {
            if (pendingSet.add(currentRev)) {
                pending.add(currentRev);
            }
            int rev = Integer.MIN_VALUE;
            while (rev != currentRev) {
                rev = pending.poll();
                pendingSet.remove(rev);
                List<Integer> matching = new ArrayList<>();
                for (int i = 0; i < groupBlocked.size(); i++) {
                    if (groupBlocked.get(i).contains(rev)) {
                        matching.add(i);
                    }
                }
                int targetIdx;
                if (!matching.isEmpty()) {
                    targetIdx = matching.remove(0);
                    for (int i : matching) {
                        groupRevs.get(targetIdx).addAll(groupRevs.get(i));
                        groupBlocked.get(targetIdx).addAll(groupBlocked.get(i));
                    }
                    for (int k = matching.size() - 1; k >= 0; k--) {
                        groupRevs.remove((int) matching.get(k));
                        groupBlocked.remove((int) matching.get(k));
                    }
                } else {
                    targetIdx = groupRevs.size();
                    groupRevs.add(new ArrayList<>());
                    Set<Integer> blocked = new HashSet<>();
                    blocked.add(rev);
                    groupBlocked.add(blocked);
                }
                List<Integer> gRevs = groupRevs.get(targetIdx);
                Set<Integer> gBlocked = groupBlocked.get(targetIdx);
                if (rev == currentRev) {
                    gRevs.add(rev);
                }
                gBlocked.remove(rev);
                for (int p : graph.parents(rev)) {
                    if (p > NULL) {
                        gBlocked.add(p);
                        if (pendingSet.add(p)) {
                            pending.add(p);
                        }
                    }
                }
                boolean show = true;
                if (unblocked.isEmpty()) {
                    if (groupRevs.size() > 1) {
                        targetIdx = 1;
                        gRevs = groupRevs.get(1);
                        gBlocked = groupBlocked.get(1);
                    }
                } else {
                    Set<Integer> common = new HashSet<>(gBlocked);
                    common.retainAll(unblocked);
                    show = !common.isEmpty();
                }
                if (show) {
                    unblocked.addAll(gBlocked);
                    out.addAll(gRevs);
                    if (targetIdx != 0) {
                        groupRevs.remove(targetIdx);
                        groupBlocked.remove(targetIdx);
                    } else {
                        gRevs.clear();
                    }
                }
            }
        }
        for (List<Integer> g : groupRevs) {
            out.addAll(g);
        }
        java.util.Collections.reverse(out);
        return out;
    }

    // ---------------------------------------------------------------- sortsource

    /**
     * {@code sortsource}: batches of sources so that nothing is rebased before the changeset it is going onto; within a batch, ascending.
     *
     * @throws HgValidationException {@code source and destination form a cycle}
     */
    static List<List<Integer>> sortSource(Map<Integer, Integer> destMap) throws HgValidationException {
        Set<Integer> srcset = new TreeSet<>(destMap.keySet());
        List<List<Integer>> batches = new ArrayList<>();
        while (!srcset.isEmpty()) {
            List<Integer> result = new ArrayList<>();
            for (int r : srcset) {
                if (!srcset.contains(destMap.get(r))) {
                    result.add(r);
                }
            }
            if (result.isEmpty()) {
                throw new HgValidationException("source and destination form a cycle");
            }
            srcset.removeAll(result);
            batches.add(result);
        }
        return batches;
    }

    // ---------------------------------------------------------------- adjustdest

    /**
     * {@code adjustdest}: the destinations for the first and the second parent of {@code rev}, given what was already rebased. A parent
     * that is the null revision keeps {@code dest}. A source of the same destination that is an ancestor of the parent and was already
     * rebased (the highest such) stands in for it.
     */
    int[] adjustDest(int rev, Map<Integer, Integer> destMap, Map<Integer, Integer> state, Set<Integer> skipped) {
        int dest = destMap.get(rev);
        List<Integer> source = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : state.entrySet()) {
            if (e.getValue() > 0 && destMap.get(e.getKey()) == dest && !skipped.contains(e.getKey())) {
                source.add(e.getKey());
            }
        }
        int[] result = new int[2];
        int[] parents = graph.parents(rev);
        for (int i = 0; i < 2; i++) {
            int prev = parents[i];
            int adjusted = dest;
            if (prev != NULL) {
                Integer candidate = null;
                Set<Integer> up = ancestors(prev);
                for (int s : source) {
                    if (up.contains(s) && (candidate == null || s > candidate)) {
                        candidate = s;
                    }
                }
                if (candidate != null) {
                    adjusted = state.get(candidate);
                }
            }
            if (adjusted == dest && state.containsKey(dest)) {
                adjusted = state.get(dest);
                if (adjusted == TODO) {
                    throw new IllegalStateException("rev " + dest + " should be rebased already at this time");
                }
            }
            result[i] = adjusted;
        }
        return result;
    }

    // ---------------------------------------------------------------- defineparents

    /** {@code revs(%ld % (%d+%d))}: the ancestors of {@code others} that are not ancestors of {@code a} or {@code b}, ascending. */
    private List<Integer> only(Set<Integer> others, int a, int b) {
        Set<Integer> reach = new TreeSet<>();
        for (int o : others) {
            reach.addAll(ancestors(o));
        }
        reach.removeAll(ancestors(a));
        reach.removeAll(ancestors(b));
        return new ArrayList<>(reach);
    }

    /**
     * {@code defineparents}: the parents {@code rev} gets and the merge base. The merge case moves each parent to the adjusted destination
     * or to its rebased result, drops a parent that became the ancestor of the other, and refuses a rebase that moves no parent or that
     * would carry unwanted changes.
     *
     * @throws HgValidationException with Mercurial's messages
     */
    Parents defineParents(int rev, Map<Integer, Integer> destMap, Map<Integer, Integer> state, Set<Integer> skipped,
                          Set<Integer> obsSkipped) throws HgValidationException {
        int dest = destMap.get(rev);
        int[] oldps = graph.parents(rev);
        int[] newps = {NULL, NULL};
        int[] dests = adjustDest(rev, destMap, state, skipped);
        int[] bases = oldps.clone();
        if (oldps[1] == NULL) {
            newps[0] = dests[0]; // a non-merge changeset just moves to the adjusted destination
        } else {
            for (int i = 0; i < 2; i++) {
                int p = oldps[i];
                int np = p;
                // `successorrevs` yields the changeset itself too (without obsolescence markers it is the only one)
                if (isAncestor(p, dests[i])) {
                    np = dests[i];
                } else if (state.containsKey(p) && state.get(p) > 0) {
                    np = state.get(p);
                }
                for (int j = 0; j < i; j++) {
                    int x = newps[j];
                    if (x == NULL) {
                        continue;
                    }
                    if (isAncestor(np, x)) { // CASE-1
                        np = NULL;
                    } else if (isAncestor(x, np)) { // CASE-2
                        newps[j] = np;
                        np = NULL;
                        int t = bases[j];
                        bases[j] = bases[i];
                        bases[i] = t;
                    }
                }
                newps[i] = np;
            }
            // `rebasenode` updates to the new p1 and uses the old p1 as the merge base: if only p2 changed, swap
            if (newps[1] != NULL && oldps[0] == newps[0]) {
                int t = newps[0];
                newps[0] = newps[1];
                newps[1] = t;
                t = bases[0];
                bases[0] = bases[1];
                bases[1] = t;
            }
            Set<Integer> oldSet = new HashSet<>(Arrays.asList(oldps[0], oldps[1]));
            Set<Integer> newSet = new HashSet<>(Arrays.asList(newps[0], newps[1]));
            if (oldSet.equals(newSet) && dest != newps[0] && dest != newps[1]) {
                throw new HgValidationException("cannot rebase " + rev + ":" + describe(rev) + " without moving at least one of its parents");
            }
        }
        for (int p : newps) {
            if (p != NULL && isAncestor(rev, p)) {
                throw new HgValidationException("source is ancestor of destination");
            }
        }
        Set<Integer> baseSet = new HashSet<>();
        for (int b : bases) {
            if (b != NULL && b != newps[0] && b != newps[1]) {
                baseSet.add(b);
            }
        }
        if (baseSet.size() > 1) {
            List<Integer>[] unwanted = new List[2];
            for (int i = 0; i < 2; i++) {
                int base = bases[i];
                if (base == NULL || base == newps[0] || base == newps[1]) {
                    continue;
                }
                Set<Integer> otherBases = new HashSet<>();
                for (int b : bases) {
                    otherBases.add(b);
                }
                otherBases.remove(base);
                List<Integer> siderevs = only(otherBases, base, dest);
                if (!siderevs.isEmpty()) {
                    List<Integer> rebaseset = new ArrayList<>();
                    for (Map.Entry<Integer, Integer> e : state.entrySet()) {
                        if (e.getValue() > 0 && !obsSkipped.contains(e.getKey())) {
                            rebaseset.add(e.getKey());
                        }
                    }
                    List<Integer> merges = new ArrayList<>();
                    for (int r : rebaseset) {
                        if (graph.parents(r)[1] != NULL) {
                            merges.add(r);
                        }
                    }
                    Set<Integer> covered = new HashSet<>();
                    for (int m : merges) {
                        covered.addAll(ancestors(m));
                    }
                    List<Integer> list = new ArrayList<>();
                    for (int r : siderevs) {
                        if (!covered.contains(r) && !rebaseset.contains(r)) {
                            list.add(r);
                        }
                    }
                    unwanted[i] = list;
                }
            }
            if (unwanted[0] != null || unwanted[1] != null) {
                int bestLength = Integer.MAX_VALUE;
                int bestIndex = -1;
                for (int i = 0; i < 2; i++) {
                    if (unwanted[i] != null && (unwanted[i].size() < bestLength)) {
                        bestLength = unwanted[i].size();
                        bestIndex = i;
                    }
                }
                if (bestLength > 0) {
                    List<String> described = new ArrayList<>();
                    for (List<Integer> revs : unwanted) {
                        if (revs != null) {
                            List<String> one = new ArrayList<>();
                            revs.forEach(r -> one.add(r + ":" + describe(r)));
                            described.add(String.join(", ", one));
                        }
                    }
                    throw new HgValidationException("rebasing " + rev + ":" + describe(rev) + " will include unwanted changes from "
                            + String.join(" or ", described));
                }
                if (bestIndex != 0) {
                    if (newps[bestIndex] != NULL) {
                        int t = newps[0];
                        newps[0] = newps[bestIndex];
                        newps[bestIndex] = t;
                    }
                    int t = bases[0];
                    bases[0] = bases[bestIndex];
                    bases[bestIndex] = t;
                }
            }
        }
        return new Parents(newps[0], newps[1], bases[0]);
    }

    /** How a changeset is named in a message ({@code %d:%s}); the planner only knows numbers, the caller can name them. */
    String describe(int rev) {
        return namer == null ? String.valueOf(rev) : namer.apply(rev);
    }

    private java.util.function.IntFunction<String> namer;

    /** Names changesets in messages (the short hash); without it the number is repeated. */
    RebasePlanner withNamer(java.util.function.IntFunction<String> namer) {
        this.namer = namer;
        return this;
    }
}
