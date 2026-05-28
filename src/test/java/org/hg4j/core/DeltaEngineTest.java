package org.hg4j.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 15: DeltaEngine 단위 테스트.
 * applyDelta / createDelta / createSimpleDelta 알고리즘을 독립적으로 검증합니다.
 */
@DisplayName("DeltaEngine — 델타 알고리즘 단위 테스트")
public class DeltaEngineTest {

    // ─────────────────────────────────────────────────────────────
    // applyDelta 테스트
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("빈 기본 텍스트에 삽입 델타 적용")
    void testApplyDelta_insertIntoEmpty() throws IOException {
        byte[] base = new byte[0];
        // 델타: start=0, end=0, length=5, data="Hello"
        byte[] delta = buildDelta(0, 0, "Hello".getBytes(StandardCharsets.UTF_8));
        byte[] result = DeltaEngine.applyDelta(base, delta);
        assertArrayEquals("Hello".getBytes(StandardCharsets.UTF_8), result);
    }

    @Test
    @DisplayName("기본 텍스트 앞부분 교체")
    void testApplyDelta_replacePrefixSection() throws IOException {
        byte[] base = "Hello World".getBytes(StandardCharsets.UTF_8);
        // "Hello"(0~5) → "Hi"로 교체
        byte[] delta = buildDelta(0, 5, "Hi".getBytes(StandardCharsets.UTF_8));
        byte[] result = DeltaEngine.applyDelta(base, delta);
        assertArrayEquals("Hi World".getBytes(StandardCharsets.UTF_8), result);
    }

    @Test
    @DisplayName("빈 델타 → 기본 텍스트 그대로 반환")
    void testApplyDelta_emptyDelta_returnsCopy() throws IOException {
        byte[] base = "unchanged".getBytes(StandardCharsets.UTF_8);
        byte[] result = DeltaEngine.applyDelta(base, new byte[0]);
        assertArrayEquals(base, result);
    }

    @Test
    @DisplayName("잘린 델타 헤더 → IOException 발생")
    void testApplyDelta_truncatedHeader_throwsIOException() {
        byte[] base = "data".getBytes(StandardCharsets.UTF_8);
        byte[] badDelta = new byte[]{0, 0, 0, 5}; // 12바이트 미만
        assertThrows(IOException.class, () -> DeltaEngine.applyDelta(base, badDelta));
    }

    @Test
    @DisplayName("잘린 델타 데이터 → IOException 발생")
    void testApplyDelta_truncatedData_throwsIOException() {
        byte[] base = "data".getBytes(StandardCharsets.UTF_8);
        // 헤더: start=0, end=0, length=100 (실제 데이터 없음)
        byte[] badDelta = buildDeltaHeaderOnly(0, 0, 100);
        assertThrows(IOException.class, () -> DeltaEngine.applyDelta(base, badDelta));
    }

    @Test
    @DisplayName("잘못된 오프셋 범위 → IOException 발생")
    void testApplyDelta_invalidOffsets_throwsIOException() {
        byte[] base = "data".getBytes(StandardCharsets.UTF_8);
        // start > baseText.length
        byte[] delta = buildDelta(100, 200, new byte[0]);
        assertThrows(IOException.class, () -> DeltaEngine.applyDelta(base, delta));
    }

    // ─────────────────────────────────────────────────────────────
    // createDelta 테스트 (createDelta → applyDelta 왕복)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("createDelta → applyDelta 왕복: 짧은 텍스트")
    void testCreateAndApplyDelta_shortText() throws IOException {
        byte[] base = "Hello World".getBytes(StandardCharsets.UTF_8);
        byte[] target = "Hello hg4j!".getBytes(StandardCharsets.UTF_8);

        byte[] delta = DeltaEngine.createDelta(base, target);
        byte[] result = DeltaEngine.applyDelta(base, delta);
        assertArrayEquals(target, result);
    }

    @Test
    @DisplayName("createDelta → applyDelta 왕복: 긴 텍스트")
    void testCreateAndApplyDelta_longText() throws IOException {
        StringBuilder sb1 = new StringBuilder();
        StringBuilder sb2 = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb1.append("Line ").append(i).append(": original content\n");
            sb2.append("Line ").append(i).append(": modified content\n");
        }
        byte[] base = sb1.toString().getBytes(StandardCharsets.UTF_8);
        byte[] target = sb2.toString().getBytes(StandardCharsets.UTF_8);

        byte[] delta = DeltaEngine.createDelta(base, target);
        byte[] result = DeltaEngine.applyDelta(base, delta);
        assertArrayEquals(target, result);
    }

    @Test
    @DisplayName("createDelta → applyDelta 왕복: 빈 base → 새 내용")
    void testCreateAndApplyDelta_emptyBase() throws IOException {
        byte[] base = new byte[0];
        byte[] target = "Brand new content".getBytes(StandardCharsets.UTF_8);

        byte[] delta = DeltaEngine.createDelta(base, target);
        byte[] result = DeltaEngine.applyDelta(base, delta);
        assertArrayEquals(target, result);
    }

    @Test
    @DisplayName("createDelta → applyDelta 왕복: 내용 삭제")
    void testCreateAndApplyDelta_deleteContent() throws IOException {
        byte[] base = "Line 1\nLine 2\nLine 3\n".getBytes(StandardCharsets.UTF_8);
        byte[] target = "Line 1\nLine 3\n".getBytes(StandardCharsets.UTF_8);

        byte[] delta = DeltaEngine.createDelta(base, target);
        byte[] result = DeltaEngine.applyDelta(base, delta);
        assertArrayEquals(target, result);
    }

    @Test
    @DisplayName("동일한 내용 → 델타 적용 후 동일 결과")
    void testCreateAndApplyDelta_identical() throws IOException {
        byte[] content = "Same content unchanged".getBytes(StandardCharsets.UTF_8);
        byte[] delta = DeltaEngine.createDelta(content, content);
        byte[] result = DeltaEngine.applyDelta(content, delta);
        assertArrayEquals(content, result);
    }

    // ─────────────────────────────────────────────────────────────
    // createSimpleDelta 테스트
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("createSimpleDelta → applyDelta 왕복")
    void testCreateSimpleDelta_roundTrip() throws IOException {
        byte[] base = "base content here".getBytes(StandardCharsets.UTF_8);
        byte[] target = "base modified here".getBytes(StandardCharsets.UTF_8);

        byte[] delta = DeltaEngine.createSimpleDelta(base, target);
        byte[] result = DeltaEngine.applyDelta(base, delta);
        assertArrayEquals(target, result);
    }

    @Test
    @DisplayName("createSimpleDelta: 빈 base → 새 내용")
    void testCreateSimpleDelta_emptyBase() throws IOException {
        byte[] base = new byte[0];
        byte[] target = "new content".getBytes(StandardCharsets.UTF_8);

        byte[] delta = DeltaEngine.createSimpleDelta(base, target);
        byte[] result = DeltaEngine.applyDelta(base, delta);
        assertArrayEquals(target, result);
    }

    @Test
    @DisplayName("createSimpleDelta: 완전히 다른 내용")
    void testCreateSimpleDelta_completelyDifferent() throws IOException {
        byte[] base = "AAAAAAAAAA".getBytes(StandardCharsets.UTF_8);
        byte[] target = "BBBBBBBBBB".getBytes(StandardCharsets.UTF_8);

        byte[] delta = DeltaEngine.createSimpleDelta(base, target);
        byte[] result = DeltaEngine.applyDelta(base, delta);
        assertArrayEquals(target, result);
    }

    // ─────────────────────────────────────────────────────────────
    // 헬퍼 메서드
    // ─────────────────────────────────────────────────────────────

    private byte[] buildDelta(int start, int end, byte[] data) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(12 + data.length);
        buf.putInt(start);
        buf.putInt(end);
        buf.putInt(data.length);
        buf.put(data);
        return buf.array();
    }

    private byte[] buildDeltaHeaderOnly(int start, int end, int length) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(12);
        buf.putInt(start);
        buf.putInt(end);
        buf.putInt(length);
        return buf.array();
    }
}
