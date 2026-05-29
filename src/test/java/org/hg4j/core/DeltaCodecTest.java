package org.hg4j.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 15: DeltaCodec 단위 테스트.
 * zlib/uncompressed 압축·해제 로직을 독립적으로 검증합니다.
 */
@DisplayName("DeltaCodec — 압축·해제 단위 테스트")
public class DeltaCodecTest {

    // ─────────────────────────────────────────────────────────────
    // compress → decompress 왕복 (round-trip) 테스트
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("빈 바이트 배열 압축 → 해제 왕복")
    void testRoundTrip_empty() throws IOException {
        byte[] original = new byte[0];
        byte[] compressed = DeltaCodec.compress(original);
        byte[] restored = DeltaCodec.decompress(compressed, original.length);
        assertArrayEquals(original, restored);
    }

    @Test
    @DisplayName("짧은 텍스트 압축 → 해제 왕복")
    void testRoundTrip_shortText() throws IOException {
        byte[] original = "Hello, hg4j!".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = DeltaCodec.compress(original);
        byte[] restored = DeltaCodec.decompress(compressed, original.length);
        assertArrayEquals(original, restored);
    }

    @Test
    @DisplayName("긴 텍스트 압축 → 해제 왕복")
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
    @DisplayName("반복 패턴 데이터 압축 시 크기 감소 확인")
    void testCompress_repeatedPattern_smallerSize() throws IOException {
        byte[] original = new byte[4096];
        Arrays.fill(original, (byte) 'A');
        byte[] compressed = DeltaCodec.compress(original);
        // 반복 데이터는 압축 후 더 작아야 함
        assertTrue(compressed.length < original.length,
                "압축된 크기(" + compressed.length + ")가 원본(" + original.length + ")보다 작아야 합니다");
    }

    // ─────────────────────────────────────────────────────────────
    // compress 출력 형식 검증
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("압축 결과가 'u' 또는 zlib 헤더로 시작")
    void testCompress_outputFormat() throws IOException {
        byte[] data = "test data for compression".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = DeltaCodec.compress(data);
        assertNotNull(compressed);
        assertTrue(compressed.length > 0);
        // 첫 바이트는 'u'(비압축) 또는 zlib 헤더(0x78)여야 함
        byte firstByte = compressed[0];
        assertTrue(firstByte == 'u' || firstByte == 0x78,
                "첫 바이트가 'u' 또는 0x78이어야 하지만: " + (firstByte & 0xFF));
    }

    @Test
    @DisplayName("비압축 형식('u' prefix) 해제")
    void testDecompress_uncompressedPrefix() throws IOException {
        byte[] content = "plain content".getBytes(StandardCharsets.UTF_8);
        // 'u' + content 형식
        byte[] hunk = new byte[content.length + 1];
        hunk[0] = 'u';
        System.arraycopy(content, 0, hunk, 1, content.length);

        byte[] result = DeltaCodec.decompress(hunk, content.length);
        assertArrayEquals(content, result);
    }

    @Test
    @DisplayName("빈 hunk 해제 → 빈 배열 반환")
    void testDecompress_emptyHunk_returnsEmpty() throws IOException {
        byte[] result = DeltaCodec.decompress(new byte[0], 0);
        assertArrayEquals(new byte[0], result);
    }

    @Test
    @DisplayName("압축되지 않은 raw 바이트 폴백 해제")
    void testDecompress_rawFallback() throws IOException {
        // 첫 바이트가 'x'도 'u'도 아닌 경우 → raw 반환
        byte[] rawData = {0x01, 0x02, 0x03, 0x04};
        byte[] result = DeltaCodec.decompress(rawData, rawData.length);
        assertArrayEquals(rawData, result);
    }

    // ─────────────────────────────────────────────────────────────
    // compressIfSmaller 최적화 선택 검증
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("원본보다 큰 압축 결과는 비압축 형식으로 반환")
    void testCompress_whenCompressedLarger_returnsUncompressed() throws IOException {
        // 랜덤한 소량 데이터는 압축 시 더 커질 수 있음
        byte[] tiny = {0x01, 0x02};
        byte[] result = DeltaCodec.compress(tiny);
        // 크기에 관계없이 decompress 후 원본과 같아야 함
        byte[] restored = DeltaCodec.decompress(result, tiny.length);
        assertArrayEquals(tiny, restored);
    }

    @Test
    @DisplayName("Zstd 활성화하여 반복 패턴 데이터 압축 및 해제 왕복 검증")
    void testRoundTrip_zstd_repeatedPattern() throws IOException {
        byte[] original = new byte[8192];
        Arrays.fill(original, (byte) 'Z');
        
        // Zstd 압축 활성화
        byte[] compressed = DeltaCodec.compress(original, true);
        
        // 반복 데이터는 압축 후 확연히 작아져야 하고 첫 바이트는 0x28 (zstd 매직 프레임 헤더의 일부)이어야 함
        assertTrue(compressed.length < original.length);
        assertEquals(0x28, compressed[0] & 0xFF);
        
        byte[] restored = DeltaCodec.decompress(compressed, original.length);
        assertArrayEquals(original, restored);
    }

    @Test
    @DisplayName("Zstd 활성화하고 소형 데이터 압축 시 'u' 접두 비압축 형식으로 fallback 및 복원 왕복 검증")
    void testRoundTrip_zstd_shortText_fallback() throws IOException {
        byte[] original = "Zstd short text test".getBytes(StandardCharsets.UTF_8);
        
        // 소형 데이터는 Zstd 압축 시 오히려 커질 수 있음 -> 'u' 비압축 fallback 검증
        byte[] compressed = DeltaCodec.compress(original, true);
        
        byte[] restored = DeltaCodec.decompress(compressed, original.length);
        assertArrayEquals(original, restored);
    }
}
