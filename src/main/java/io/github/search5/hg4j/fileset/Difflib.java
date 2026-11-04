package io.github.search5.hg4j.fileset;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** {@code difflib.SequenceMatcher(None, a, b).ratio()} for short strings (no junk heuristics), used for "did you mean" hints. */
final class Difflib {

    private Difflib() {
    }

    static double ratio(String a, String b) {
        int total = a.length() + b.length();
        if (total == 0) {
            return 1.0;
        }
        return 2.0 * matches(a, b) / total;
    }

    private static int matches(String a, String b) {
        Map<Character, List<Integer>> b2j = new HashMap<>();
        for (int j = 0; j < b.length(); j++) {
            b2j.computeIfAbsent(b.charAt(j), k -> new ArrayList<>()).add(j);
        }
        int total = 0;
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[] {0, a.length(), 0, b.length()});
        while (!queue.isEmpty()) {
            int[] q = queue.pop();
            int alo = q[0];
            int ahi = q[1];
            int blo = q[2];
            int bhi = q[3];
            int[] best = longest(a, b2j, alo, ahi, blo, bhi);
            int i = best[0];
            int j = best[1];
            int k = best[2];
            if (k > 0) {
                total += k;
                if (alo < i && blo < j) {
                    queue.add(new int[] {alo, i, blo, j});
                }
                if (i + k < ahi && j + k < bhi) {
                    queue.add(new int[] {i + k, ahi, j + k, bhi});
                }
            }
        }
        return total;
    }

    /** {@code find_longest_match}: the earliest longest block in {@code a[alo:ahi]} and {@code b[blo:bhi]}. */
    private static int[] longest(String a, Map<Character, List<Integer>> b2j, int alo, int ahi, int blo, int bhi) {
        int besti = alo;
        int bestj = blo;
        int bestsize = 0;
        Map<Integer, Integer> j2len = new HashMap<>();
        for (int i = alo; i < ahi; i++) {
            Map<Integer, Integer> newj2len = new HashMap<>();
            for (int j : b2j.getOrDefault(a.charAt(i), List.of())) {
                if (j < blo) {
                    continue;
                }
                if (j >= bhi) {
                    break;
                }
                int k = j2len.getOrDefault(j - 1, 0) + 1;
                newj2len.put(j, k);
                if (k > bestsize) {
                    besti = i - k + 1;
                    bestj = j - k + 1;
                    bestsize = k;
                }
            }
            j2len = newj2len;
        }
        return new int[] {besti, bestj, bestsize};
    }
}
