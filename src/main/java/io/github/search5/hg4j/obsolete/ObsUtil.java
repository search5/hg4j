package io.github.search5.hg4j.obsolete;

import io.github.search5.hg4j.util.NodeIdUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** The parts of {@code mercurial/obsutil.py} that read the obsolescence graph. */
public final class ObsUtil {

    private ObsUtil() {
    }

    /**
     * The markers of a store, by the hex of the predecessor ({@code obsstore.successors}); the markers of each predecessor are sorted as
     * {@code sortedmarkers} does.
     * @param markers the markers of the store
     * @return a map from the predecessor's hex node to its sorted markers
     */
    public static Map<String, List<ObsStore.Marker>> bySuccessor(List<ObsStore.Marker> markers) {
        Map<String, List<ObsStore.Marker>> result = new HashMap<>();
        for (ObsStore.Marker marker : markers) {
            result.computeIfAbsent(NodeIdUtil.toHex(marker.pre()), k -> new ArrayList<>()).add(marker);
        }
        result.values().forEach(Collections::sort);
        return result;
    }

    /**
     * {@code successorssets(repo, initialnode)}: the sets of the latest successors of a changeset. A pruned changeset (obsoleted with no
     * successor, or with only successors that are not known locally) has none, a changeset with no marker is its own successors set,
     * a split gives one set of several changesets and a divergence several sets.
     *
     * @param succMarkers the markers by predecessor, see {@link #bySuccessor(List)}
     * @param known whether the changeset (a hex node) is in the repository
     * @param initial the hex node to start from
     * @return the successors sets, the smaller ones first
     */
    public static List<List<String>> successorsSets(Map<String, List<ObsStore.Marker>> succMarkers, Predicate<String> known, String initial) {
        Deque<String> toProceed = new ArrayDeque<>();
        toProceed.push(initial);
        Set<String> stacked = new HashSet<>(toProceed);
        Map<String, List<List<String>>> cache = new HashMap<>();

        while (!toProceed.isEmpty()) {
            String current = toProceed.peek();
            if (cache.containsKey(current)) {
                stacked.remove(toProceed.pop());
            } else if (!succMarkers.containsKey(current)) {
                // the end of the walk: a node of this repository is its own successors set, an unknown final successor counts as pruned
                if (known.test(current)) {
                    cache.put(current, List.of(List.of(current)));
                } else {
                    cache.put(current, List.of());
                }
            } else {
                boolean pushed = false;
                for (ObsStore.Marker mark : succMarkers.get(current)) {
                    for (byte[] sucNode : mark.sucs()) {
                        String suc = NodeIdUtil.toHex(sucNode);
                        if (!cache.containsKey(suc)) {
                            if (stacked.contains(suc)) {
                                // an obsolescence cycle: the node counts as pruned
                                cache.put(suc, List.of());
                            } else {
                                toProceed.push(suc);
                                stacked.add(suc);
                                pushed = true;
                                break;
                            }
                        }
                    }
                    if (pushed) {
                        break;
                    }
                }
                if (pushed) {
                    continue;
                }
                // every direct successor has its successors sets: the sets that each marker contributes, as a product over its successors
                List<List<String>> succssets = new ArrayList<>();
                for (ObsStore.Marker mark : succMarkers.get(current)) {
                    List<List<String>> markss = new ArrayList<>();
                    markss.add(new ArrayList<>());
                    for (byte[] sucNode : mark.sucs()) {
                        List<List<String>> product = new ArrayList<>();
                        for (List<String> prefix : markss) {
                            for (List<String> suffix : cache.get(NodeIdUtil.toHex(sucNode))) {
                                List<String> newss = new ArrayList<>(prefix);
                                for (String part : suffix) {
                                    if (!newss.contains(part)) {
                                        newss.add(part);
                                    }
                                }
                                product.add(newss);
                            }
                        }
                        if (!product.isEmpty()) {
                            markss = product;
                        }
                    }
                    succssets.addAll(markss);
                }
                // drop the duplicates and the sets that are a subset of another one, longest first, then the small ones lead
                List<List<String>> candidates = new ArrayList<>();
                for (List<String> s : succssets) {
                    if (!s.isEmpty()) {
                        candidates.add(s);
                    }
                }
                candidates.sort((a, b) -> Integer.compare(b.size(), a.size()));
                List<List<String>> seen = new ArrayList<>();
                List<List<String>> result = new ArrayList<>();
                for (List<String> cand : candidates) {
                    boolean merged = false;
                    for (List<String> seenSet : seen) {
                        if (new HashSet<>(seenSet).containsAll(cand)) {
                            merged = true;
                            break;
                        }
                    }
                    if (!merged) {
                        result.add(cand);
                        seen.add(cand);
                    }
                }
                Collections.reverse(result);
                cache.put(current, result);
            }
        }
        return cache.get(initial);
    }
}
