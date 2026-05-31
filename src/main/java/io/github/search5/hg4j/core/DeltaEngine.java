package io.github.search5.hg4j.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Component dedicated to the revlog delta algorithm (SRP separation).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>{@link #applyDelta(byte[], byte[])} — Applies a delta to a base text to restore the new text</li>
 *   <li>{@link #createDelta(byte[], byte[])} — Generates an LCS-based multi-hunk delta</li>
 *   <li>{@link #createSimpleDelta(byte[], byte[])} — Generates a simple delta based on prefix/suffix matching</li>
 * </ul>
 *
 * <p>This class is stateless and all methods are static.
 */
public final class DeltaEngine {

    private DeltaEngine() {}

    // ─────────────────────────────────────────────────────────────────────
    // applyDelta
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Applies a Mercurial-formatted delta to a base text and returns the new text.
     *
     * <p>Delta hunk format: {@code [start(4B)][end(4B)][length(4B)][data(length bytes)]}
     *
     * @param baseText The base (previous) text
     * @param delta    The delta byte array to apply
     * @return The text after applying the delta
     * @throws IOException If the delta format is invalid
     */
    public static byte[] applyDelta(byte[] baseText, byte[] delta) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(delta);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int lastCopied = 0;

        while (buf.hasRemaining()) {
            if (buf.remaining() < 12) {
                throw new io.github.search5.hg4j.errors.HgCorruptDataException("Truncated delta hunk header");
            }
            int start = buf.getInt();
            int end = buf.getInt();
            int length = buf.getInt();

            if (length < 0 || buf.remaining() < length) {
                throw new io.github.search5.hg4j.errors.HgCorruptDataException("Truncated delta hunk data");
            }
            byte[] insertData = new byte[length];
            buf.get(insertData);

            if (start < lastCopied || start > baseText.length
                    || end < start || end > baseText.length) {
                throw new io.github.search5.hg4j.errors.HgCorruptDataException("Invalid delta hunk offsets: start=" + start
                        + ", end=" + end + ", baseLen=" + baseText.length);
            }
            out.write(baseText, lastCopied, start - lastCopied);
            out.write(insertData);
            lastCopied = end;
        }
        if (lastCopied < baseText.length) {
            out.write(baseText, lastCopied, baseText.length - lastCopied);
        }
        return out.toByteArray();
    }

    // ─────────────────────────────────────────────────────────────────────
    // createSimpleDelta
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Generates a simple single-hunk delta using prefix/suffix matching.
     * Used for validation comparison or large file fallback.
     *
     * @param baseText The base (previous) text
     * @param newText  The new text
     * @return A single-hunk delta byte array
     */
    public static byte[] createSimpleDelta(byte[] baseText, byte[] newText) {
        int prefixLen = 0;
        int maxLen = Math.min(baseText.length, newText.length);
        while (prefixLen < maxLen && baseText[prefixLen] == newText[prefixLen]) {
            prefixLen++;
        }

        int suffixLen = 0;
        int maxSuffix = maxLen - prefixLen;
        while (suffixLen < maxSuffix
                && baseText[baseText.length - 1 - suffixLen]
                        == newText[newText.length - 1 - suffixLen]) {
            suffixLen++;
        }

        int start = prefixLen;
        int end = baseText.length - suffixLen;
        int insertLen = newText.length - suffixLen - prefixLen;

        byte[] insertData = new byte[Math.max(0, insertLen)];
        if (insertLen > 0) {
            System.arraycopy(newText, prefixLen, insertData, 0, insertLen);
        }

        ByteBuffer buf = ByteBuffer.allocate(12 + insertData.length);
        buf.putInt(start);
        buf.putInt(end);
        buf.putInt(insertData.length);
        buf.put(insertData);
        return buf.array();
    }

    // ─────────────────────────────────────────────────────────────────────
    // createDelta (LCS 기반 멀티-hunk)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Generates an optimized multi-hunk delta using the LCS (Longest Common Subsequence) line diff algorithm.
     * Falls back to {@link #createSimpleDelta} if the file size is too large and O(N*M) DP computation is too expensive.
     *
     * @param baseText The base (previous) text
     * @param newText  The new text
     * @return A multi-hunk delta byte array
     */
    public static byte[] createDelta(byte[] baseText, byte[] newText) {
        if (baseText == null || baseText.length == 0) {
            if (newText == null || newText.length == 0) {
                return new byte[12]; // Empty hunk: start=0, end=0, length=0
            }
            ByteBuffer buf = ByteBuffer.allocate(12 + newText.length);
            buf.putInt(0);
            buf.putInt(0);
            buf.putInt(newText.length);
            buf.put(newText);
            return buf.array();
        }
        if (newText == null || newText.length == 0) {
            ByteBuffer buf = ByteBuffer.allocate(12);
            buf.putInt(0);
            buf.putInt(baseText.length);
            buf.putInt(0);
            return buf.array();
        }

        List<Line> baseLines = splitLines(baseText);
        List<Line> newLines = splitLines(newText);

        int n = baseLines.size();
        int m = newLines.size();

        // Myers Diff O(ND) search instead of O(N*M)
        int max = n + m;
        int offset = max;
        int[] v = new int[2 * max + 1];
        java.util.Arrays.fill(v, -1);
        v[offset + 1] = 0;

        List<int[]> history = new java.util.ArrayList<>();

        int targetD = -1;
        for (int d = 0; d <= max; d++) {
            for (int k = -d; k <= d; k += 2) {
                int idx = offset + k;
                int x;
                if (k == -d || (k != d && v[idx - 1] < v[idx + 1])) {
                    x = v[idx + 1];
                } else {
                    x = v[idx - 1] + 1;
                }
                int y = x - k;
                while (x < n && y < m && baseLines.get(x).equals(newLines.get(y))) {
                    x++;
                    y++;
                }
                v[idx] = x;
                if (x >= n && y >= m) {
                    targetD = d;
                    int[] currentVCopy = new int[2 * d + 1];
                    java.util.Arrays.fill(currentVCopy, -1);
                    for (int k2 = -d; k2 <= d; k2++) {
                        if (Math.abs(offset + k2) < v.length) {
                            currentVCopy[k2 + d] = v[offset + k2];
                        }
                    }
                    history.add(currentVCopy);
                    break;
                }
            }
            if (targetD != -1) {
                break;
            }
            int[] currentVCopy = new int[2 * d + 1];
            java.util.Arrays.fill(currentVCopy, -1);
            for (int k2 = -d; k2 <= d; k2++) {
                if (Math.abs(offset + k2) < v.length) {
                    currentVCopy[k2 + d] = v[offset + k2];
                }
            }
            history.add(currentVCopy);
        }

        boolean[] baseMatched = new boolean[n];
        boolean[] newMatched = new boolean[m];

        // Myers Diff Backtracking
        int currX = n;
        int currY = m;
        for (int d = targetD; d > 0; d--) {
            int k = currX - currY;
            int[] vPrev = history.get(d - 1);

            int kPrev;
            int dPrev = d - 1;
            int idxMinus = k - 1 + dPrev;
            int idxPlus = k + 1 + dPrev;
            
            boolean moveDown = false;
            if (k == -d) {
                moveDown = true;
            } else if (k == d) {
                moveDown = false;
            } else {
                int valMinus = (idxMinus >= 0 && idxMinus < vPrev.length) ? vPrev[idxMinus] : -1;
                int valPlus = (idxPlus >= 0 && idxPlus < vPrev.length) ? vPrev[idxPlus] : -1;
                moveDown = valMinus < valPlus;
            }

            if (moveDown) {
                kPrev = k + 1;
            } else {
                kPrev = k - 1;
            }

            int idx = kPrev + dPrev;
            int xPrev = (idx >= 0 && idx < vPrev.length) ? vPrev[idx] : 0;
            int yPrev = xPrev - kPrev;

            while (currX > xPrev && currY > yPrev) {
                if (currX - 1 < n && currY - 1 < m && baseLines.get(currX - 1).equals(newLines.get(currY - 1))) {
                    baseMatched[currX - 1] = true;
                    newMatched[currY - 1] = true;
                }
                currX--;
                currY--;
            }

            currX = xPrev;
            currY = yPrev;
        }

        while (currX > 0 && currY > 0) {
            if (baseLines.get(currX - 1).equals(newLines.get(currY - 1))) {
                baseMatched[currX - 1] = true;
                newMatched[currY - 1] = true;
            }
            currX--;
            currY--;
        }

        // Serialize the changed segments into hunks
        ByteArrayOutputStream deltaOut = new ByteArrayOutputStream();
        int b = 0, g = 0;
        while (b < n || g < m) {
            if (b < n && g < m && baseMatched[b] && newMatched[g]) {
                b++;
                g++;
                continue;
            }

            int bStart = b;
            while (b < n && !baseMatched[b]) b++;
            int bEnd = b;

            int gStart = g;
            while (g < m && !newMatched[g]) g++;
            int gEnd = g;

            if (bEnd > bStart || gEnd > gStart) {
                int byteStart = (bStart < n)
                        ? baseLines.get(bStart).start
                        : baseText.length;
                int byteEnd;
                if (bEnd > bStart) {
                    byteEnd = (bEnd == n)
                            ? baseText.length
                            : baseLines.get(bEnd - 1).end;
                } else {
                    byteEnd = byteStart;
                }

                ByteArrayOutputStream insertBuf = new ByteArrayOutputStream();
                for (int k = gStart; k < gEnd; k++) {
                    try {
                        insertBuf.write(newLines.get(k).bytes);
                    } catch (IOException ignored) {}
                }
                byte[] insertData = insertBuf.toByteArray();

                ByteBuffer hunkHeader = ByteBuffer.allocate(12);
                hunkHeader.putInt(byteStart);
                hunkHeader.putInt(byteEnd);
                hunkHeader.putInt(insertData.length);
                try {
                    deltaOut.write(hunkHeader.array());
                    deltaOut.write(insertData);
                } catch (IOException ignored) {}
            }
        }

        byte[] multiHunkDelta = deltaOut.toByteArray();
        byte[] simpleDelta = createSimpleDelta(baseText, newText);
        // Fallback if the simple delta is smaller
        return (multiHunkDelta.length <= simpleDelta.length) ? multiHunkDelta : simpleDelta;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Internal Helper
    // ─────────────────────────────────────────────────────────────────────

    private static class Line {
        final byte[] bytes;
        final int start;
        final int end;

        Line(byte[] bytes, int start, int end) {
            this.bytes = bytes;
            this.start = start;
            this.end = end;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof Line other) {
                return Arrays.equals(this.bytes, other.bytes);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }

    static List<Line> splitLines(byte[] text) {
        List<Line> lines = new ArrayList<>();
        int start = 0;
        for (int k = 0; k < text.length; k++) {
            if (text[k] == '\n') {
                lines.add(new Line(Arrays.copyOfRange(text, start, k + 1), start, k + 1));
                start = k + 1;
            }
        }
        if (start < text.length) {
            lines.add(new Line(Arrays.copyOfRange(text, start, text.length), start, text.length));
        }
        return lines;
    }
}
