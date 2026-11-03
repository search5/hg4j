package io.github.search5.hg4j.diff;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The line delta that Mercurial's Rust implementation computes for revlog storage and for the deltas it exchanges
 * ({@code hg::revlog::diff::text_delta}, 7.2.4), ported so that hg4j produces the same bytes: {@code mdiff.storage_diff} is this
 * function when the Rust extensions are built in, which is how the reference {@code hg} is built.
 *
 * <p>The steps are those of the original: a line-aligned common prefix and suffix are cut off (in blocks of 512 bytes), the
 * remaining lines are diffed with the Myers algorithm of {@code imara-diff 0.2.0} ({@code Algorithm::Myers}, no minimising, no
 * post-processing: lines that cannot match are pruned first, the search is cut off after a cost limit), and the hunks that touch
 * each other are written as one piece {@code (start, end, length, data)}. Where the original has an oddity it is kept on purpose,
 * because the output has to be identical (for example the parity test {@code is_odd} of the middle-snake search is always false).</p>
 */
public final class LineDelta {

    private static final int CMP_BLK_SIZE = 512;
    private static final int HEUR_MIN_COST = 256;
    private static final int MAX_COST_MIN = 256;
    private static final int SNAKE_CNT = 20;
    private static final int K_HEUR = 4;
    private static final int MAX_EQLIMIT = 1024;
    private static final int WINDOW_SIZE = 100;

    private LineDelta() {
    }

    /** The delta that turns {@code m1} into {@code m2}; empty when they are equal. */
    public static byte[] textDelta(byte[] m1, byte[] m2) {
        int prefix = linesPrefixSizeLow(m1, 0, m2, 0);
        int suffix = linesSuffixSizeLow(m1, prefix, m2, prefix);
        int end1 = m1.length - suffix;
        int end2 = m2.length - suffix;
        ByteArrayOutputStream delta = new ByteArrayOutputStream();
        boolean empty1 = end1 - prefix == 0;
        boolean empty2 = end2 - prefix == 0;
        if (empty1 && empty2) {
            return new byte[0];
        } else if (empty2) {
            writePiece(delta, prefix, prefix + (end1 - prefix), m2, 0, 0);
        } else if (empty1) {
            writePiece(delta, prefix, prefix, m2, prefix, end2);
        } else {
            diffLines(prefix, java.util.Arrays.copyOfRange(m1, prefix, end1), java.util.Arrays.copyOfRange(m2, prefix, end2), delta);
        }
        return delta.toByteArray();
    }

    // ------------------------------------------------------------------ prefix and suffix

    /** A lower bound of the size of a line-aligned identical prefix (compared in blocks of 512 bytes). */
    private static int linesPrefixSizeLow(byte[] left, int leftFrom, byte[] right, int rightFrom) {
        int blocks = 0;
        int leftLength = left.length - leftFrom;
        int rightLength = right.length - rightFrom;
        while ((blocks + 1) * CMP_BLK_SIZE <= leftLength && (blocks + 1) * CMP_BLK_SIZE <= rightLength
                && sameRange(left, leftFrom + blocks * CMP_BLK_SIZE, right, rightFrom + blocks * CMP_BLK_SIZE, CMP_BLK_SIZE)) {
            blocks++;
        }
        int chunkEnd = blocks * CMP_BLK_SIZE;
        for (int i = chunkEnd - 1; i >= 0; i--) {
            if (left[leftFrom + i] == '\n') {
                return i + 1;
            }
        }
        return 0;
    }

    /** The size of a line-aligned identical suffix of {@code left[leftFrom..]} and {@code right[rightFrom..]} (not necessarily the longest). */
    private static int linesSuffixSizeLow(byte[] left, int leftFrom, byte[] right, int rightFrom) {
        int leftLength = left.length - leftFrom;
        int rightLength = right.length - rightFrom;
        int blocks = 0;
        while ((blocks + 1) * CMP_BLK_SIZE <= leftLength && (blocks + 1) * CMP_BLK_SIZE <= rightLength
                && sameRange(left, left.length - (blocks + 1) * CMP_BLK_SIZE, right, right.length - (blocks + 1) * CMP_BLK_SIZE, CMP_BLK_SIZE)) {
            blocks++;
        }
        int size = blocks * CMP_BLK_SIZE;
        for (int i = left.length - size; i < left.length; i++) {
            if (left[i] == '\n') {
                return size - ((i - (left.length - size)) + 1);
            }
        }
        return 0;
    }

    private static boolean sameRange(byte[] a, int aFrom, byte[] b, int bFrom, int length) {
        for (int i = 0; i < length; i++) {
            if (a[aFrom + i] != b[bFrom + i]) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ lines, tokens, hunks

    /** Line starts of a text: {@code offsets[i]} is where line {@code i} starts, the last entry is the end of the text. */
    private static int[] lineOffsets(byte[] data) {
        List<Integer> offsets = new ArrayList<>();
        offsets.add(0);
        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\n') {
                offsets.add(i + 1);
            }
        }
        if (data.length > 0 && data[data.length - 1] != '\n') {
            offsets.add(data.length);
        }
        int[] out = new int[offsets.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = offsets.get(i);
        }
        return out;
    }

    private static void diffLines(int skip, byte[] m1, byte[] m2, ByteArrayOutputStream delta) {
        int[] offsets1 = lineOffsets(m1);
        int[] offsets2 = lineOffsets(m2);
        // tokens: equal lines get the same id, numbered in order of first appearance (the before text first)
        Map<String, Integer> ids = new HashMap<>();
        int[] before = tokens(m1, offsets1, ids);
        int[] after = tokens(m2, offsets2, ids);

        boolean[] removed = new boolean[before.length];
        boolean[] added = new boolean[after.length];

        // Diff::compute_with: cut the common prefix and suffix tokens, then run Myers on what is left
        int commonPrefix = 0;
        while (commonPrefix < before.length && commonPrefix < after.length && before[commonPrefix] == after[commonPrefix]) {
            commonPrefix++;
        }
        int commonSuffix = 0;
        while (commonSuffix < before.length - commonPrefix && commonSuffix < after.length - commonPrefix
                && before[before.length - 1 - commonSuffix] == after[after.length - 1 - commonSuffix]) {
            commonSuffix++;
        }
        int[] b = java.util.Arrays.copyOfRange(before, commonPrefix, before.length - commonSuffix);
        int[] a = java.util.Arrays.copyOfRange(after, commonPrefix, after.length - commonSuffix);
        new Myers(b, a, removed, commonPrefix, added, commonPrefix).diff();

        // the hunks, those that touch in the old text are one piece
        int posBefore = 0;
        int posAfter = 0;
        int removedAt = 0;
        int addedAt = 0;
        long[] cursor = null; // old start, old end, new start, new end
        while (true) {
            int removedCount = 0;
            while (removedAt < removed.length) {
                boolean flag = removed[removedAt++];
                if (!flag) {
                    break;
                }
                removedCount++;
            }
            int addedCount = 0;
            while (addedAt < added.length) {
                boolean flag = added[addedAt++];
                if (!flag) {
                    break;
                }
                addedCount++;
            }
            if (removedCount != 0 || addedCount != 0) {
                int beforeStart = posBefore;
                int afterStart = posAfter;
                posBefore += removedCount;
                posAfter += addedCount;
                long start = (long) skip + offsets1[beforeStart];
                long end = (long) skip + offsets1[posBefore];
                long contentStart = offsets2[afterStart];
                long contentEnd = offsets2[posAfter];
                if (cursor != null && cursor[1] == start) {
                    cursor[1] += end - start;
                    cursor[3] += contentEnd - contentStart;
                } else {
                    if (cursor != null) {
                        writePiece(delta, (int) cursor[0], (int) cursor[1], m2, (int) cursor[2], (int) cursor[3]);
                    }
                    cursor = new long[]{start, end, contentStart, contentEnd};
                }
                posBefore++;
                posAfter++;
            } else if (removedAt >= removed.length && addedAt >= added.length) {
                break;
            } else {
                posBefore++;
                posAfter++;
            }
        }
        if (cursor != null) {
            writePiece(delta, (int) cursor[0], (int) cursor[1], m2, (int) cursor[2], (int) cursor[3]);
        }
    }

    private static int[] tokens(byte[] data, int[] offsets, Map<String, Integer> ids) {
        int lines = offsets.length - 1;
        int[] out = new int[Math.max(lines, 0)];
        for (int i = 0; i < lines; i++) {
            String key = new String(data, offsets[i], offsets[i + 1] - offsets[i], StandardCharsets.ISO_8859_1);
            Integer id = ids.get(key);
            if (id == null) {
                id = ids.size();
                ids.put(key, id);
            }
            out[i] = id;
        }
        return out;
    }

    /** {@code PlainDeltaPiece::write}: start, end and length as big-endian 32-bit integers, then the data (taken from {@code data[from..to]}). */
    private static void writePiece(ByteArrayOutputStream out, int start, int end, byte[] data, int from, int to) {
        out.writeBytes(ByteBuffer.allocate(12).putInt(start).putInt(end).putInt(to - from).array());
        out.write(data, from, to - from);
    }

    // ------------------------------------------------------------------ Myers (imara-diff 0.2.0)

    private static int sqrt(int value) {
        int bits = 32 - Integer.numberOfLeadingZeros(value); // usize::BITS is 64 in the original, the count of used bits is the same
        return 1 << (bits / 2);
    }

    /** A view of a token sequence together with the indices (in the unpruned file) of its tokens. */
    private static final class Slice {
        final int[] tokens;
        final int[] indices;
        final int from;
        final int to;
        final boolean[] changed;
        final int changedBase;

        Slice(int[] tokens, int[] indices, int from, int to, boolean[] changed, int changedBase) {
            this.tokens = tokens;
            this.indices = indices;
            this.from = from;
            this.to = to;
            this.changed = changed;
            this.changedBase = changedBase;
        }

        int len() {
            return to - from;
        }

        int token(int i) {
            return tokens[from + i];
        }

        Slice slice(int start, int end) {
            return new Slice(tokens, indices, from + start, from + end, changed, changedBase);
        }

        void markChanged() {
            for (int i = from; i < to; i++) {
                changed[changedBase + indices[i]] = true;
            }
        }
    }

    private static final class Myers {
        private final int[] kvec;
        private final int forwardBase;
        private final int backwardBase;
        private final int maxCost;
        private final int[] beforeAll;
        private final int[] afterAll;
        private final boolean[] removed;
        private final int removedBase;
        private final boolean[] added;
        private final int addedBase;

        Myers(int[] before, int[] after, boolean[] removed, int removedBase, boolean[] added, int addedBase) {
            this.beforeAll = before;
            this.afterAll = after;
            this.removed = removed;
            this.removedBase = removedBase;
            this.added = added;
            this.addedBase = addedBase;
            // preprocessing prunes the tokens, so the sizes of the search arrays follow the pruned lengths
            int[][] pruned = preprocess();
            this.prunedBefore = pruned[0];
            this.beforeIndices = pruned[1];
            this.prunedAfter = pruned[2];
            this.afterIndices = pruned[3];
            int ndiags = prunedBefore.length + prunedAfter.length + 3;
            this.kvec = new int[2 * ndiags + 2];
            this.forwardBase = prunedAfter.length + 1;
            this.backwardBase = ndiags + prunedAfter.length + 1;
            this.maxCost = Math.max(sqrt(ndiags), MAX_COST_MIN);
        }

        private final int[] prunedBefore;
        private final int[] beforeIndices;
        private final int[] prunedAfter;
        private final int[] afterIndices;

        void diff() {
            Slice file1 = new Slice(prunedBefore, beforeIndices, 0, prunedBefore.length, removed, removedBase);
            Slice file2 = new Slice(prunedAfter, afterIndices, 0, prunedAfter.length, added, addedBase);
            run(file1, file2, false);
        }

        // ---- preprocess.rs

        private int[][] preprocess() {
            int[] count1 = new int[maxToken() + 1];
            int[] count2 = new int[count1.length];
            for (int t : beforeAll) {
                count1[t]++;
            }
            for (int t : afterAll) {
                count2[t]++;
            }
            int eqlimit1 = Math.min(sqrt(beforeAll.length), MAX_EQLIMIT);
            int eqlimit2 = Math.min(sqrt(afterAll.length), MAX_EQLIMIT);
            // 0: none, 1: some, 2: common
            int[] status1 = new int[beforeAll.length];
            for (int i = 0; i < beforeAll.length; i++) {
                status1[i] = occurrences(count2[beforeAll[i]], eqlimit1);
            }
            int[] status2 = new int[afterAll.length];
            for (int i = 0; i < afterAll.length; i++) {
                status2[i] = occurrences(count1[afterAll[i]], eqlimit2);
            }
            List<int[]> kept1 = prune(beforeAll, status1, removed, removedBase);
            List<int[]> kept2 = prune(afterAll, status2, added, addedBase);
            return new int[][]{column(kept1, 0), column(kept1, 1), column(kept2, 0), column(kept2, 1)};
        }

        private int maxToken() {
            int max = 0;
            for (int t : beforeAll) {
                max = Math.max(max, t);
            }
            for (int t : afterAll) {
                max = Math.max(max, t);
            }
            return max;
        }

        private static int occurrences(int occurrences, int eqlimit) {
            if (occurrences == 0) {
                return 0;
            }
            return occurrences >= eqlimit ? 2 : 1;
        }

        private static List<int[]> prune(int[] file, int[] status, boolean[] changed, int base) {
            List<int[]> kept = new ArrayList<>();
            for (int i = 0; i < file.length; i++) {
                boolean prune = switch (status[i]) {
                    case 0 -> true;
                    case 1 -> false;
                    default -> shouldPruneCommonLine(status, i);
                };
                if (prune) {
                    changed[base + i] = true;
                } else {
                    kept.add(new int[]{file[i], i});
                }
            }
            return kept;
        }

        private static int[] column(List<int[]> pairs, int which) {
            int[] out = new int[pairs.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = pairs.get(i)[which];
            }
            return out;
        }

        private static boolean shouldPruneCommonLine(int[] status, int pos) {
            int unmatchedBefore = 0;
            int commonBefore = 0;
            int start = pos > WINDOW_SIZE ? WINDOW_SIZE : 0; // as in the original (not pos - WINDOW_SIZE)
            for (int i = pos - 1; i >= start; i--) {
                if (status[i] == 0) {
                    unmatchedBefore++;
                } else if (status[i] == 2) {
                    commonBefore++;
                } else {
                    break;
                }
            }
            if (unmatchedBefore == 0) {
                return false;
            }
            int end = Math.min(status.length, pos + WINDOW_SIZE);
            int unmatchedAfter = 0;
            int commonAfter = 0;
            for (int i = pos; i < end; i++) {
                if (status[i] == 0) {
                    unmatchedAfter++;
                } else if (status[i] == 2) {
                    commonAfter++;
                } else {
                    break;
                }
            }
            if (unmatchedAfter == 0) {
                return false;
            }
            return unmatchedBefore + unmatchedAfter > 3 * (commonBefore + commonAfter);
        }

        // ---- myers.rs

        private void run(Slice file1, Slice file2, boolean needMin) {
            while (true) {
                // strip_common
                int start = 0;
                int limit = Math.min(file1.len(), file2.len());
                while (start < limit && file1.token(start) == file2.token(start)) {
                    start++;
                }
                int post = 0;
                while (post < file1.len() - start && post < file2.len() - start
                        && file1.token(file1.len() - 1 - post) == file2.token(file2.len() - 1 - post)) {
                    post++;
                }
                file1 = file1.slice(start, file1.len() - post);
                file2 = file2.slice(start, file2.len() - post);

                if (file1.len() == 0) {
                    file2.markChanged();
                    return;
                } else if (file2.len() == 0) {
                    file1.markChanged();
                    return;
                }

                int[] split = split(file1, file2, needMin); // idx1, idx2, minimizedLo, minimizedHi
                run(file1.slice(0, split[0]), file2.slice(0, split[1]), split[2] != 0);
                file1 = file1.slice(split[0], file1.len());
                file2 = file2.slice(split[1], file2.len());
                needMin = split[3] != 0;
            }
        }

        private int[] split(Slice file1, Slice file2, boolean needMin) {
            Search forward = new Search(false, forwardBase, file1, file2);
            Search backward = new Search(true, backwardBase, file1, file2);
            // `(file2.len() - file2.len()) & 1 != 0` in the original: always false
            boolean isOdd = false;
            int ec = 0;
            while (ec <= maxCost) {
                boolean foundSnake = false;
                forward.nextD();
                if (isOdd) {
                    throw new IllegalStateException("unreachable: is_odd is always false in the original");
                }
                foundSnake |= forward.run(file1, file2, null) != null;

                backward.nextD();
                final Search forwardRef = forward;
                int[] res = backward.run(file1, file2, (k, idx1) -> forwardRef.contains(k) && idx1 <= forwardRef.xPos(k));
                if (res != null) {
                    if (res[0] == 1) {
                        return new int[]{res[1], res[2], 1, 1};
                    }
                    foundSnake = true;
                }

                if (needMin) {
                    continue; // as in the original: no cost limit while the halves are being minimised (ec does not grow)
                }
                if (foundSnake && ec > HEUR_MIN_COST) {
                    int[] snake = forward.foundSnake(ec, file1, file2);
                    if (snake != null) {
                        return new int[]{snake[0], snake[1], 1, 0};
                    }
                    snake = backward.foundSnake(ec, file1, file2);
                    if (snake != null) {
                        return new int[]{snake[0], snake[1], 0, 1};
                    }
                }
                ec++;
            }
            long[] fwd = forward.bestPosition(file1, file2);
            long[] bwd = backward.bestPosition(file1, file2);
            if (fwd[0] > (long) file1.len() + file2.len() - bwd[0]) {
                return new int[]{(int) fwd[1], (int) (fwd[0] - fwd[1]), 1, 0};
            }
            return new int[]{(int) bwd[1], (int) (bwd[0] - bwd[1]), 0, 1};
        }

        @FunctionalInterface
        private interface Stop {
            boolean test(int k, int tokenIdx1);
        }

        /** MiddleSnakeSearch: one direction of the bidirectional search. */
        private final class Search {
            private final boolean back;
            private final int base;
            private int kmin;
            private int kmax;
            private final int dmin;
            private final int dmax;

            Search(boolean back, int base, Slice file1, Slice file2) {
                this.back = back;
                this.base = base;
                this.dmin = -file2.len();
                this.dmax = file1.len();
                int kmid = back ? dmin + dmax : 0;
                this.kmin = kmid;
                this.kmax = kmid;
                write(kmid, back ? file1.len() : 0);
            }

            boolean contains(int k) {
                return k >= kmin && k <= kmax;
            }

            private void write(int k, int value) {
                kvec[base + k] = value;
            }

            int xPos(int k) {
                return kvec[base + k];
            }

            void nextD() {
                int initVal = back ? Integer.MAX_VALUE : Integer.MIN_VALUE;
                if (kmin > dmin) {
                    kmin--;
                    write(kmin - 1, initVal);
                } else {
                    kmin++;
                }
                if (kmax < dmax) {
                    kmax++;
                    write(kmax + 1, initVal);
                } else {
                    kmax--;
                }
            }

            /** Returns null (nothing), {0} for a long snake, or {1, idx1, idx2} when the two searches met. */
            int[] run(Slice file1, Slice file2, Stop stop) {
                int[] res = null;
                int k = kmax;
                while (k >= kmin) {
                    int idx1;
                    if (back) {
                        idx1 = xPos(k - 1) < xPos(k + 1) ? xPos(k - 1) : xPos(k + 1) - 1;
                    } else {
                        idx1 = xPos(k - 1) >= xPos(k + 1) ? xPos(k - 1) + 1 : xPos(k + 1);
                    }
                    int idx2 = idx1 - k;
                    int off = 0;
                    if (back) {
                        if (idx1 > 0 && idx2 > 0) {
                            while (off < idx1 && off < idx2 && file1.token(idx1 - 1 - off) == file2.token(idx2 - 1 - off)) {
                                off++;
                            }
                        }
                    } else if (idx1 < file1.len() && idx2 < file2.len()) {
                        while (idx1 + off < file1.len() && idx2 + off < file2.len() && file1.token(idx1 + off) == file2.token(idx2 + off)) {
                            off++;
                        }
                    }
                    if (off > SNAKE_CNT) {
                        res = new int[]{0};
                    }
                    if (back) {
                        idx1 -= off;
                        idx2 -= off;
                    } else {
                        idx1 += off;
                        idx2 += off;
                    }
                    write(k, idx1);
                    if (stop != null && stop.test(k, idx1)) {
                        return new int[]{1, idx1, idx2};
                    }
                    k -= 2;
                }
                return res;
            }

            /** {distance, token index in file1} of the furthest (or, backwards, nearest) diagonal end. */
            long[] bestPosition(Slice file1, Slice file2) {
                long bestDistance = back ? Long.MAX_VALUE : -1;
                long bestIdx1 = back ? Integer.MAX_VALUE : -1;
                int k = kmax;
                while (k >= kmin) {
                    int idx1 = xPos(k);
                    idx1 = back ? Math.max(idx1, 0) : Math.min(idx1, file1.len());
                    int idx2 = idx1 - k;
                    if (back) {
                        if (idx2 < 0) {
                            idx1 = k;
                            idx2 = 0;
                        }
                    } else if (idx2 > file2.len()) {
                        idx1 = file2.len() + k;
                        idx2 = file2.len();
                    }
                    long distance = (long) idx1 + idx2;
                    if (back && distance < bestDistance || !back && distance > bestDistance) {
                        bestDistance = distance;
                        bestIdx1 = idx1;
                    }
                    k -= 2;
                }
                return new long[]{bestDistance, bestIdx1};
            }

            /** A long run of equal tokens far enough from the ends to split there, or null. */
            int[] foundSnake(int ec, Slice file1, Slice file2) {
                long bestScore = 0;
                int bestIdx1 = 0;
                int bestIdx2 = 0;
                int k = kmax;
                while (k >= kmin) {
                    int idx1 = xPos(k);
                    int idx2 = idx1 - k;
                    boolean inRange;
                    if (back) {
                        inRange = idx1 >= 0 && idx1 < file1.len() - SNAKE_CNT && idx2 >= 0 && idx2 < file2.len() - SNAKE_CNT;
                    } else {
                        inRange = idx1 >= SNAKE_CNT && idx1 < file1.len() && idx2 >= SNAKE_CNT && idx2 < file2.len();
                    }
                    if (!inRange) {
                        k -= 2;
                        continue;
                    }
                    long mainDiagonalDistance = Math.abs((long) k);
                    long distance = back ? (long) (file1.len() - idx1) + (file2.len() - idx2) : (long) idx1 + idx2;
                    long score = distance + mainDiagonalDistance;
                    if (score > (long) K_HEUR * ec && score > bestScore) {
                        boolean isSnake = true;
                        if (back) {
                            int n = Math.min(SNAKE_CNT, Math.min(file1.len() - idx1, file2.len() - idx2));
                            for (int j = 0; j < n; j++) {
                                if (file1.token(idx1 + j) != file2.token(idx2 + j)) {
                                    isSnake = false;
                                    break;
                                }
                            }
                        } else {
                            // `zip(..).rev()` pairs the tokens by position from the front, as the original does
                            int shorter = Math.min(idx1, idx2);
                            int n = Math.min(SNAKE_CNT, shorter);
                            for (int j = 0; j < n; j++) {
                                int i = shorter - 1 - j;
                                if (file1.token(i) != file2.token(i)) {
                                    isSnake = false;
                                    break;
                                }
                            }
                        }
                        if (isSnake) {
                            bestIdx1 = idx1;
                            bestIdx2 = idx2;
                            bestScore = score;
                        }
                    }
                    k -= 2;
                }
                return bestScore > 0 ? new int[]{bestIdx1, bestIdx2} : null;
            }
        }
    }
}
