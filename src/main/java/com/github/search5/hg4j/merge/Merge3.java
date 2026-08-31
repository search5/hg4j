package com.github.search5.hg4j.merge;

import java.util.ArrayList;
import java.util.List;

/**
 * A robust line-level 3-way merge engine based on Sync Points via LCS Index Mapping.
 * Optimized with Hirschberg's Algorithm for O(N) spatial complexity to protect against OutOfMemory errors.
 */
public class Merge3 {

    public static class MergeResult {
        private final boolean conflicted;
        private final List<String> mergedLines;

        public MergeResult(boolean conflicted, List<String> mergedLines) {
            this.conflicted = conflicted;
            this.mergedLines = mergedLines;
        }

        public boolean isConflicted() {
            return conflicted;
        }

        public List<String> getMergedLines() {
            return mergedLines;
        }
    }

    /**
     * Performs a 3-way merge on three lists of lines representing the common base, yours, and theirs.
     */
    public static MergeResult merge(List<String> base, List<String> yours, List<String> theirs) {
        int[] baseToYours = getLcsMapping(base, yours);
        int[] baseToTheirs = getLcsMapping(base, theirs);

        // Find monotonic sync points where base, yours, and theirs all match
        List<Integer> syncPoints = new ArrayList<>();
        int lastY = -1;
        int lastT = -1;
        for (int b = 0; b < base.size(); b++) {
            int y = baseToYours[b];
            int t = baseToTheirs[b];
            if (y != -1 && t != -1) {
                if (y > lastY && t > lastT) {
                    syncPoints.add(b);
                    lastY = y;
                    lastT = t;
                }
            }
        }

        List<String> result = new ArrayList<>();
        boolean conflicted = false;

        int bCurr = 0, yCurr = 0, tCurr = 0;

        for (int bSync : syncPoints) {
            int ySync = baseToYours[bSync];
            int tSync = baseToTheirs[bSync];

            // Merge block from last pointer up to sync point
            MergeBlockRes blockRes = mergeBlock(
                    base.subList(bCurr, bSync),
                    yours.subList(yCurr, ySync),
                    theirs.subList(tCurr, tSync)
            );
            result.addAll(blockRes.lines);
            if (blockRes.conflicted) {
                conflicted = true;
            }

            // Sync point itself matches in all three files
            result.add(base.get(bSync));

            bCurr = bSync + 1;
            yCurr = ySync + 1;
            tCurr = tSync + 1;
        }

        // Merge remaining block after the last sync point
        MergeBlockRes lastBlockRes = mergeBlock(
                base.subList(bCurr, base.size()),
                yours.subList(yCurr, yours.size()),
                theirs.subList(tCurr, theirs.size())
        );
        result.addAll(lastBlockRes.lines);
        if (lastBlockRes.conflicted) {
            conflicted = true;
        }

        return new MergeResult(conflicted, result);
    }

    private static class MergeBlockRes {
        final boolean conflicted;
        final List<String> lines;
        MergeBlockRes(boolean conflicted, List<String> lines) {
            this.conflicted = conflicted;
            this.lines = lines;
        }
    }

    private static MergeBlockRes mergeBlock(List<String> bSub, List<String> ySub, List<String> tSub) {
        boolean yChanged = !bSub.equals(ySub);
        boolean tChanged = !bSub.equals(tSub);

        if (yChanged && tChanged) {
            if (ySub.equals(tSub)) {
                // Both modified the section identically
                return new MergeBlockRes(false, ySub);
            } else {
                // Both modified the section but differently -> Conflict!
                List<String> conflictLines = new ArrayList<>();
                conflictLines.add("<<<<<<< Yours");
                conflictLines.addAll(ySub);
                conflictLines.add("=======");
                conflictLines.addAll(tSub);
                conflictLines.add(">>>>>>> Theirs");
                return new MergeBlockRes(true, conflictLines);
            }
        } else if (yChanged) {
            // Only Yours modified this block
            return new MergeBlockRes(false, ySub);
        } else {
            // Only Theirs modified this block (or no one changed)
            return new MergeBlockRes(false, tSub);
        }
    }

    /**
     * Maps index of list 'a' to index of list 'b' using Hirschberg's Algorithm.
     * Yields exact LCS mapping with optimal O(min(m, n)) space complexity instead of O(m*n).
     */
    private static int[] getLcsMapping(List<String> a, List<String> b) {
        int m = a.size();
        int n = b.size();
        int[] mapping = new int[m];
        java.util.Arrays.fill(mapping, -1);
        
        if (m == 0 || n == 0) {
            return mapping;
        }
        
        hirschberg(a, 0, m, b, 0, n, mapping);
        return mapping;
    }

    private static void hirschberg(List<String> a, int aStart, int aEnd, List<String> b, int bStart, int bEnd, int[] mapping) {
        int m = aEnd - aStart;
        int n = bEnd - bStart;

        if (m == 0) {
            return;
        }
        if (m == 1) {
            for (int j = bStart; j < bEnd; j++) {
                if (a.get(aStart).equals(b.get(j))) {
                    mapping[aStart] = j;
                    break;
                }
            }
            return;
        }

        int aMid = aStart + m / 2;

        int[] l1 = lcsLength(a.subList(aStart, aMid), b.subList(bStart, bEnd));
        int[] l2 = lcsLengthReverse(a.subList(aMid, aEnd), b.subList(bStart, bEnd));

        int maxVal = -1;
        int split = bStart;
        for (int j = 0; j <= n; j++) {
            if (l1[j] + l2[n - j] > maxVal) {
                maxVal = l1[j] + l2[n - j];
                split = bStart + j;
            }
        }

        hirschberg(a, aStart, aMid, b, bStart, split, mapping);
        hirschberg(a, aMid, aEnd, b, split, bEnd, mapping);
    }

    private static int[] lcsLength(List<String> a, List<String> b) {
        int m = a.size();
        int n = b.size();
        int[] curr = new int[n + 1];
        int[] prev = new int[n + 1];

        for (int i = 1; i <= m; i++) {
            System.arraycopy(curr, 0, prev, 0, n + 1);
            for (int j = 1; j <= n; j++) {
                if (a.get(i - 1).equals(b.get(j - 1))) {
                    curr[j] = prev[j - 1] + 1;
                } else {
                    curr[j] = Math.max(curr[j - 1], prev[j]);
                }
            }
        }
        return curr;
    }

    private static int[] lcsLengthReverse(List<String> a, List<String> b) {
        int m = a.size();
        int n = b.size();
        int[] curr = new int[n + 1];
        int[] prev = new int[n + 1];

        for (int i = m; i >= 1; i--) {
            System.arraycopy(curr, 0, prev, 0, n + 1);
            for (int j = n; j >= 1; j--) {
                if (a.get(i - 1).equals(b.get(j - 1))) {
                    curr[n - j + 1] = prev[n - j] + 1;
                } else {
                    curr[n - j + 1] = Math.max(curr[n - j], prev[n - j + 1]);
                }
            }
        }
        return curr;
    }
}
