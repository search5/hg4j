package com.github.search5.hg4j.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DeltaCodec.
 * Independently verifies compression and decompression logic for zlib and uncompressed formats.
 */
@DisplayName("DeltaCodec — Compression and Decompression Unit Tests")
public class DeltaCodecTest {

    // ─────────────────────────────────────────────────────────────
    // compress -> decompress Round-trip Tests
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Empty byte array compression -> decompression round-trip")
    void testRoundTrip_empty() throws IOException {
        byte[] original = new byte[0];
        byte[] compressed = DeltaCodec.compress(original);
        byte[] restored = DeltaCodec.decompress(compressed, original.length);
        assertArrayEquals(original, restored);
    }

    @Test
    @DisplayName("Short text compression -> decompression round-trip")
    void testRoundTrip_shortText() throws IOException {
        byte[] original = "Hello, hg4j!".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = DeltaCodec.compress(original);
        byte[] restored = DeltaCodec.decompress(compressed, original.length);
        assertArrayEquals(original, restored);
    }

    @Test
    @DisplayName("Long text compression -> decompression round-trip")
    void testRoundTrip_longText() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("Mercurial revision ").append(i).append(" content line\n");
        }
        byte[] original = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] compressed = DeltaCodec.compress(original);
        byte[] restored = DeltaCodec.decompress(compressed, original.length);
        assertArrayEquals(original, restored);
    }

    @Test
    @DisplayName("Verify size reduction when compressing repeated pattern data")
    void testCompress_repeatedPattern_smallerSize() throws IOException {
        byte[] original = new byte[4096];
        Arrays.fill(original, (byte) 'A');
        byte[] compressed = DeltaCodec.compress(original);
        // Repeated data should be smaller after compression
        assertTrue(compressed.length < original.length,
                "Compressed size (" + compressed.length + ") should be smaller than original size (" + original.length + ")");
    }

    // ─────────────────────────────────────────────────────────────
    // Verify compress Output Format
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Compression output starts with 'u' or zlib header")
    void testCompress_outputFormat() throws IOException {
        byte[] data = "test data for compression".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = DeltaCodec.compress(data);
        assertNotNull(compressed);
        assertTrue(compressed.length > 0);
        // First byte must be 'u' (uncompressed) or zlib header (0x78)
        byte firstByte = compressed[0];
        assertTrue(firstByte == 'u' || firstByte == 0x78,
                "First byte must be 'u' or 0x78, but was: " + (firstByte & 0xFF));
    }

    @Test
    @DisplayName("Decompress uncompressed format ('u' prefix)")
    void testDecompress_uncompressedPrefix() throws IOException {
        byte[] content = "plain content".getBytes(StandardCharsets.UTF_8);
        // 'u' + content format
        byte[] hunk = new byte[content.length + 1];
        hunk[0] = 'u';
        System.arraycopy(content, 0, hunk, 1, content.length);

        byte[] result = DeltaCodec.decompress(hunk, content.length);
        assertArrayEquals(content, result);
    }

    @Test
    @DisplayName("Decompress empty hunk -> return empty array")
    void testDecompress_emptyHunk_returnsEmpty() throws IOException {
        byte[] result = DeltaCodec.decompress(new byte[0], 0);
        assertArrayEquals(new byte[0], result);
    }

    @Test
    @DisplayName("Fallback decompression of raw uncompressed bytes")
    void testDecompress_rawFallback() throws IOException {
        // If the first byte is neither 'x' nor 'u' -> return raw bytes
        byte[] rawData = {0x01, 0x02, 0x03, 0x04};
        byte[] result = DeltaCodec.decompress(rawData, rawData.length);
        assertArrayEquals(rawData, result);
    }

    // ─────────────────────────────────────────────────────────────
    // Verify compressIfSmaller Optimization Choice
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Return uncompressed format if compressed result is larger than original")
    void testCompress_whenCompressedLarger_returnsUncompressed() throws IOException {
        // Random small data can become larger when compressed
        byte[] tiny = {0x01, 0x02};
        byte[] result = DeltaCodec.compress(tiny);
        // Decompressing should yield identical content to original regardless of size
        byte[] restored = DeltaCodec.decompress(result, tiny.length);
        assertArrayEquals(tiny, restored);
    }

    @Test
    @DisplayName("Verify compression and decompression round-trip of repeated pattern data with Zstd enabled")
    void testRoundTrip_zstd_repeatedPattern() throws IOException {
        byte[] original = new byte[8192];
        Arrays.fill(original, (byte) 'Z');
        
        // Enable Zstd compression
        byte[] compressed = DeltaCodec.compress(original, true);
        
        // Repeated data should be significantly smaller after compression, and the first byte should be 0x28 (part of zstd magic frame header)
        assertTrue(compressed.length < original.length);
        assertEquals(0x28, compressed[0] & 0xFF);
        
        byte[] restored = DeltaCodec.decompress(compressed, original.length);
        assertArrayEquals(original, restored);
    }

    @Test
    @DisplayName("Verify fallback to 'u'-prefixed uncompressed format and recovery round-trip when compressing small data with Zstd enabled")
    void testRoundTrip_zstd_shortText_fallback() throws IOException {
        byte[] original = "Zstd short text test".getBytes(StandardCharsets.UTF_8);
        
        // Small data can become larger when compressed with Zstd -> verify fallback to uncompressed 'u' format
        byte[] compressed = DeltaCodec.compress(original, true);
        
        byte[] restored = DeltaCodec.decompress(compressed, original.length);
        assertArrayEquals(original, restored);
    }
}
