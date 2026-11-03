package io.github.search5.hg4j.diff;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import io.github.search5.hg4j.errors.HgCorruptDataException;

/**
 * Component dedicated to the revlog delta algorithm (SRP separation).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>{@link #applyDelta(byte[], byte[])} — Applies a delta to a base text to restore the new text</li>
 *   <li>{@link #createDelta(byte[], byte[])} — Generates the line delta Mercurial itself computes ({@link LineDelta})</li>
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
                throw new HgCorruptDataException("Truncated delta hunk header");
            }
            int start = buf.getInt();
            int end = buf.getInt();
            int length = buf.getInt();

            if (length < 0 || buf.remaining() < length) {
                throw new HgCorruptDataException("Truncated delta hunk data");
            }
            byte[] insertData = new byte[length];
            buf.get(insertData);

            if (start < lastCopied || start > baseText.length
                    || end < start || end > baseText.length) {
                throw new HgCorruptDataException("Invalid delta hunk offsets: start=" + start
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
    // createDelta (Mercurial's own line delta)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Generates the delta that Mercurial computes for a revision against its base: a line-aligned diff (Myers, as
     * {@code mdiff.storage_diff} of the Rust build does), see {@link LineDelta}. The bytes are those of the original.
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

        // The delta Mercurial itself computes (mdiff.storage_diff of the Rust build), so that deltas are byte for byte the same.
        return LineDelta.textDelta(baseText, newText);
    }
}
