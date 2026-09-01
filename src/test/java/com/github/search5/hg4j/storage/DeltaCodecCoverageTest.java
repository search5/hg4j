package com.github.search5.hg4j.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.github.search5.hg4j.errors.HgCorruptDataException;
import com.github.luben.zstd.Zstd;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage tests for {@link DeltaCodec}, focused on branches not exercised by
 * {@link DeltaCodecTest}: null-input handling, the real Mercurial header-byte formats
 * (REVLOG_COMP_NONE {@code 0x00} and REVLOG_COMP_ZSTD {@code 0x28}, per
 * mercurial/interfaces/compression.py), and malformed/truncated compressed data.
 *
 * <p>Some tests here pin down behavior verified against real hg (v7.2) and its Python
 * source (mercurial/revlog.py {@code Revlog.compress()}/{@code decompress()} and
 * mercurial/interfaces/compression.py) that the original implementation got wrong:
 * <ul>
 *   <li>A leading {@code 0x00} byte means "raw data, returned verbatim" (REVLOG_COMP_NONE) —
 *       it is NOT a prefix for a Zstd-magic payload. Real hg only ever emits a bare {@code 0x00}
 *       as the very first byte of already-raw (non-restartable) content; it never prepends
 *       {@code 0x00} in front of an actual Zstd frame.</li>
 *   <li>Truncated/corrupted zlib and zstd streams must be reported as
 *       {@link HgCorruptDataException}, matching real hg's behavior of raising
 *       {@code RevlogError} when {@code zlib.decompress}/the zstd decompressor fails on
 *       incomplete data — not silently returned as a partial or garbage result.</li>
 * </ul>
 */
@DisplayName("DeltaCodec — Additional Coverage Tests")
public class DeltaCodecCoverageTest {

    // ─────────────────────────────────────────────────────────────
    // Null-input branches
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("compress(null) returns empty array")
    void testCompress_null_returnsEmpty() throws IOException {
        byte[] result = DeltaCodec.compress(null);
        assertArrayEquals(new byte[0], result);
    }

    @Test
    @DisplayName("compress(null, true) returns empty array (useZstd path)")
    void testCompress_nullWithZstd_returnsEmpty() throws IOException {
        byte[] result = DeltaCodec.compress(null, true);
        assertArrayEquals(new byte[0], result);
    }

    @Test
    @DisplayName("decompress(null, ...) returns empty array")
    void testDecompress_null_returnsEmpty() throws IOException {
        byte[] result = DeltaCodec.decompress(null, 100);
        assertArrayEquals(new byte[0], result);
    }

    @Test
    @DisplayName("decompress with uncompLen == 0 short-circuits to empty, even with non-empty hunk")
    void testDecompress_uncompLenZero_returnsEmpty() throws IOException {
        byte[] hunk = {0x78, (byte) 0x9c, 0x01, 0x02};
        byte[] result = DeltaCodec.decompress(hunk, 0);
        assertArrayEquals(new byte[0], result);
    }

    // ─────────────────────────────────────────────────────────────
    // REVLOG_COMP_NONE (0x00 prefix) — real hg semantics
    // Verified against mercurial/interfaces/compression.py: REVLOG_COMP_NONE = b'\0'
    // and mercurial/revlog.py Revlog.decompress(): `elif t == b'\0': return data`
    // (the data is returned completely unmodified, including the leading NUL byte).
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Data whose first byte is 0x00 is returned verbatim (REVLOG_COMP_NONE), unmodified")
    void testDecompress_nullBytePrefix_returnsRawVerbatim() throws IOException {
        byte[] raw = {0x00, 0x01, 0x02, 0x03, 0x04};
        byte[] result = DeltaCodec.decompress(raw, raw.length);
        assertArrayEquals(raw, result, "REVLOG_COMP_NONE data must be returned as-is, including the leading 0x00");
    }

    @Test
    @DisplayName("0x00-prefixed data that coincidentally resembles a Zstd magic must still be raw passthrough, not Zstd-decoded")
    void testDecompress_nullBytePrefix_coincidentalZstdMagicSuffix_isStillRawPassthrough() throws IOException {
        // This is the exact shape of hunk the original (buggy) implementation misclassified as
        // "Mercurial V2/Zstd standard: 0x00 prefix + Zstd magic" and tried to Zstd-decode.
        // There is no such format in real hg: a leading 0x00 always means raw passthrough.
        byte[] raw = {0x00, 0x28, (byte) 0xB5, 0x2F, (byte) 0xFD, 0x11, 0x22};
        byte[] result = DeltaCodec.decompress(raw, raw.length);
        assertArrayEquals(raw, result);
    }

    @Test
    @DisplayName("Single 0x00 byte hunk is returned verbatim")
    void testDecompress_singleNullByte_returnsVerbatim() throws IOException {
        byte[] raw = {0x00};
        byte[] result = DeltaCodec.decompress(raw, raw.length);
        assertArrayEquals(raw, result);
    }

    // ─────────────────────────────────────────────────────────────
    // REVLOG_COMP_ZSTD (0x28 header) round-trip and error handling
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Real Zstd-compressed hunk (header 0x28) decompresses correctly without any prefix")
    void testDecompress_realZstdHunk_directHeaderByte() throws IOException {
        byte[] original = "Mercurial zstd revlog content test payload, repeated. ".repeat(50)
                .getBytes(StandardCharsets.UTF_8);
        byte[] compressed = Zstd.compress(original);
        assertEquals(0x28, compressed[0] & 0xFF, "Zstd frame magic must start with 0x28 (REVLOG_COMP_ZSTD)");

        byte[] restored = DeltaCodec.decompress(compressed, original.length);
        assertArrayEquals(original, restored);
    }

    @Test
    @DisplayName("Corrupted Zstd data (valid magic, truncated payload) raises HgCorruptDataException")
    void testDecompress_corruptedZstdData_throwsCorruptDataException() {
        // Valid Zstd frame magic (0x28 B5 2F FD) but no real frame content behind it.
        byte[] corrupt = {0x28, (byte) 0xB5, 0x2F, (byte) 0xFD, 0x11, 0x22, 0x33, 0x44};
        assertThrows(HgCorruptDataException.class, () -> DeltaCodec.decompress(corrupt, 100));
    }

    @Test
    @DisplayName("Truncated real Zstd hunk raises HgCorruptDataException rather than returning garbage")
    void testDecompress_truncatedRealZstdHunk_throwsCorruptDataException() throws IOException {
        byte[] original = "some reasonably sized content to compress with zstd for truncation test purposes"
                .repeat(20).getBytes(StandardCharsets.UTF_8);
        byte[] compressed = Zstd.compress(original);
        byte[] truncated = Arrays.copyOf(compressed, compressed.length / 2);

        assertThrows(HgCorruptDataException.class, () -> DeltaCodec.decompress(truncated, original.length));
    }

    // ─────────────────────────────────────────────────────────────
    // Truncated/corrupted zlib ('x') data
    // Verified against real hg: mercurial/revlog.py uses `_zlibdecompress = zlib.decompress`,
    // which raises `zlib.error` (mapped to RevlogError) on incomplete/truncated streams —
    // it never silently returns a partial result.
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Mid-stream truncated zlib hunk raises HgCorruptDataException instead of returning a partial result")
    void testDecompress_truncatedZlibHunk_throwsCorruptDataException() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            sb.append("line ").append(i).append(" some mercurial revlog content here\n");
        }
        byte[] original = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] compressed = DeltaCodec.compress(original);
        assertEquals(0x78, compressed[0] & 0xFF, "expected zlib header for this compressible input");

        byte[] truncated = Arrays.copyOf(compressed, compressed.length / 2);
        assertThrows(HgCorruptDataException.class, () -> DeltaCodec.decompress(truncated, original.length));
    }

    @Test
    @DisplayName("A lone zlib header byte with no payload raises HgCorruptDataException")
    void testDecompress_zlibHeaderByteOnly_throwsCorruptDataException() {
        byte[] truncated = {0x78};
        assertThrows(HgCorruptDataException.class, () -> DeltaCodec.decompress(truncated, 10));
    }

    @Test
    @DisplayName("Data starting with 'x' but with an invalid zlib header check raises HgCorruptDataException")
    void testDecompress_invalidZlibHeader_throwsCorruptDataException() {
        // 'x' (0x78) selects the zlib decompressor, but the bytes that follow are not a
        // valid zlib stream (fails the header check performed internally by Inflater) —
        // this reproduces DataFormatException("incorrect header check"), the same class of
        // error real hg's zlib.decompress() reports as a zlib.error/RevlogError.
        byte[] corrupt = {0x78, 0x00, 0x00, 0x00, 0x00};
        assertThrows(HgCorruptDataException.class, () -> DeltaCodec.decompress(corrupt, 10));
    }

    @Test
    @DisplayName("Doubled 'x' header where neither offset-0 nor offset-1 forms a valid zlib header check still raises HgCorruptDataException")
    void testDecompress_doubledXHeader_bothOffsetChecksFail_throwsCorruptDataException() {
        // hunk[0]=='x' selects the zlib decompressor. hunk[0..1]=(0x78,0x78) fails the
        // standard zlib header check (fails the mod-31 check), as does hunk[1..2]=(0x78,0x00) —
        // this exercises decompressZlib's "hunk[1] == 'x'" fallback offset-detection branch.
        // Regardless of which offset is chosen, this is not a valid zlib stream, so decoding
        // must still fail with HgCorruptDataException, not succeed or hang.
        byte[] corrupt = {0x78, 0x78, 0x00, 0x00, 0x00};
        assertThrows(HgCorruptDataException.class, () -> DeltaCodec.decompress(corrupt, 10));
    }

    // ─────────────────────────────────────────────────────────────
    // 'u' prefix and raw-fallback edge cases
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("'u'-prefixed hunk with no content after the prefix decompresses to empty array")
    void testDecompress_uPrefixOnly_returnsEmpty() throws IOException {
        byte[] hunk = {'u'};
        byte[] result = DeltaCodec.decompress(hunk, 0);
        // uncompLen == 0 short-circuits before the 'u' branch is even reached.
        assertArrayEquals(new byte[0], result);
    }

    @Test
    @DisplayName("Unrecognized non-empty header byte other than x/u/0x00/0x28 falls back to raw passthrough")
    void testDecompress_unrecognizedHeaderByte_rawFallback() throws IOException {
        byte[] rawData = {0x05, 0x06, 0x07};
        byte[] result = DeltaCodec.decompress(rawData, rawData.length);
        assertArrayEquals(rawData, result);
    }
}
