package org.hg4j.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Revlog 델타 알고리즘을 전담하는 컴포넌트 (SRP 분리).
 *
 * <p>책임:
 * <ul>
 *   <li>{@link #applyDelta(byte[], byte[])} — 기본 텍스트에 델타를 적용하여 새 텍스트를 복원</li>
 *   <li>{@link #createDelta(byte[], byte[])} — LCS 기반 멀티-hunk 델타 생성</li>
 *   <li>{@link #createSimpleDelta(byte[], byte[])} — prefix/suffix 매칭 기반 단순 델타 생성</li>
 * </ul>
 *
 * <p>이 클래스는 상태를 갖지 않으며 모든 메서드는 정적입니다.
 */
public final class DeltaEngine {

    private DeltaEngine() {}

    // ─────────────────────────────────────────────────────────────────────
    // applyDelta
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 기본 텍스트에 Mercurial 형식의 델타를 적용하여 새 텍스트를 반환합니다.
     *
     * <p>델타 hunk 형식: {@code [start(4B)][end(4B)][length(4B)][data(length bytes)]}
     *
     * @param baseText 기본(이전) 텍스트
     * @param delta    적용할 델타 바이트 배열
     * @return 델타 적용 결과 텍스트
     * @throws IOException 델타 포맷이 잘못된 경우
     */
    public static byte[] applyDelta(byte[] baseText, byte[] delta) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(delta);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int lastCopied = 0;

        while (buf.hasRemaining()) {
            if (buf.remaining() < 12) {
                throw new org.hg4j.errors.HgCorruptDataException("Truncated delta hunk header");
            }
            int start = buf.getInt();
            int end = buf.getInt();
            int length = buf.getInt();

            if (length < 0 || buf.remaining() < length) {
                throw new org.hg4j.errors.HgCorruptDataException("Truncated delta hunk data");
            }
            byte[] insertData = new byte[length];
            buf.get(insertData);

            if (start < lastCopied || start > baseText.length
                    || end < start || end > baseText.length) {
                throw new org.hg4j.errors.HgCorruptDataException("Invalid delta hunk offsets: start=" + start
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
     * prefix/suffix 매칭 방식의 단순 단일-hunk 델타를 생성합니다.
     * 검증 비교 또는 대형 파일 폴백 용도로 사용됩니다.
     *
     * @param baseText 기본(이전) 텍스트
     * @param newText  새 텍스트
     * @return 단일 hunk 델타 바이트 배열
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
     * LCS(최장 공통 부분수열) 라인 diff 알고리즘을 사용하여 최적화된 멀티-hunk 델타를 생성합니다.
     * 파일 크기가 커서 O(N*M) DP 연산이 너무 비싼 경우 {@link #createSimpleDelta}로 폴백합니다.
     *
     * @param baseText 기본(이전) 텍스트
     * @param newText  새 텍스트
     * @return 멀티-hunk 델타 바이트 배열
     */
    public static byte[] createDelta(byte[] baseText, byte[] newText) {
        if (baseText == null || baseText.length == 0) {
            if (newText == null || newText.length == 0) {
                return new byte[12]; // 빈 hunk: start=0, end=0, length=0
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

        // O(N*M) 대신 Myers Diff O(ND) 탐색
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
                    for (int k2 = -d; k2 <= d; k2 += 2) {
                        currentVCopy[k2 + d] = v[offset + k2];
                    }
                    history.add(currentVCopy);
                    break;
                }
            }
            if (targetD != -1) {
                break;
            }
            int[] currentVCopy = new int[2 * d + 1];
            for (int k2 = -d; k2 <= d; k2 += 2) {
                currentVCopy[k2 + d] = v[offset + k2];
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

        // 변경 구간을 hunk로 직렬화
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
        // 단순 델타가 더 작으면 폴백
        return (multiHunkDelta.length <= simpleDelta.length) ? multiHunkDelta : simpleDelta;
    }

    // ─────────────────────────────────────────────────────────────────────
    // 내부 헬퍼
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
