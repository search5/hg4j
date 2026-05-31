package io.github.search5.hg4j.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DeltaEngine.
 * Independently verifies the algorithms for applyDelta, createDelta, and createSimpleDelta.
 */
@DisplayName("DeltaEngine — Delta Algorithm Unit Tests")
public class DeltaEngineTest {

    // ─────────────────────────────────────────────────────────────
    // applyDelta Tests
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Apply insertion delta to empty base text")
    void testApplyDelta_insertIntoEmpty() throws IOException {
        byte[] base = new byte[0];
        // 델타: start=0, end=0, length=5, data="Hello"
        byte[] delta = buildDelta(0, 0, "Hello".getBytes(StandardCharsets.UTF_8));
        byte[] result = DeltaEngine.applyDelta(base, delta);
        assertArrayEquals("Hello".getBytes(StandardCharsets.UTF_8), result);
    }

    @Test
    @DisplayName("Replace prefix section of base text")
    void testApplyDelta_replacePrefixSection() throws IOException {
        byte[] base = "Hello World".getBytes(StandardCharsets.UTF_8);
        // Replace "Hello" (0~5) with "Hi"
        byte[] delta = buildDelta(0, 5, "Hi".getBytes(StandardCharsets.UTF_8));
        byte[] result = DeltaEngine.applyDelta(base, delta);
        assertArrayEquals("Hi World".getBytes(StandardCharsets.UTF_8), result);
    }

    @Test
    @DisplayName("Empty delta -> return base text as is")
    void testApplyDelta_emptyDelta_returnsCopy() throws IOException {
        byte[] base = "unchanged".getBytes(StandardCharsets.UTF_8);
        byte[] result = DeltaEngine.applyDelta(base, new byte[0]);
        assertArrayEquals(base, result);
    }

    @Test
    @DisplayName("Truncated delta header -> throw IOException")
    void testApplyDelta_truncatedHeader_throwsIOException() {
        byte[] base = "data".getBytes(StandardCharsets.UTF_8);
        byte[] badDelta = new byte[]{0, 0, 0, 5}; // Less than 12 bytes
        assertThrows(IOException.class, () -> DeltaEngine.applyDelta(base, badDelta));
    }

    @Test
    @DisplayName("Truncated delta data -> throw IOException")
    void testApplyDelta_truncatedData_throwsIOException() {
        byte[] base = "data".getBytes(StandardCharsets.UTF_8);
        // Header: start=0, end=0, length=100 (no actual data)
        byte[] badDelta = buildDeltaHeaderOnly(0, 0, 100);
        assertThrows(IOException.class, () -> DeltaEngine.applyDelta(base, badDelta));
    }

    @Test
    @DisplayName("Invalid offset range -> throw IOException")
    void testApplyDelta_invalidOffsets_throwsIOException() {
        byte[] base = "data".getBytes(StandardCharsets.UTF_8);
        // start > baseText.length
        byte[] delta = buildDelta(100, 200, new byte[0]);
        assertThrows(IOException.class, () -> DeltaEngine.applyDelta(base, delta));
    }

    // ─────────────────────────────────────────────────────────────
    // createDelta Tests (createDelta -> applyDelta Round-trip)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("createDelta -> applyDelta round-trip: short text")
    void testCreateAndApplyDelta_shortText() throws IOException {
        byte[] base = "Hello World".getBytes(StandardCharsets.UTF_8);
        byte[] target = "Hello hg4j!".getBytes(StandardCharsets.UTF_8);

        byte[] delta = DeltaEngine.createDelta(base, target);
        byte[] result = DeltaEngine.applyDelta(base, delta);
        assertArrayEquals(target, result);
    }

    @Test
    @DisplayName("createDelta -> applyDelta round-trip: long text")
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
    @DisplayName("createDelta -> applyDelta round-trip: empty base -> new content")
    void testCreateAndApplyDelta_emptyBase() throws IOException {
        byte[] base = new byte[0];
        byte[] target = "Brand new content".getBytes(StandardCharsets.UTF_8);

        byte[] delta = DeltaEngine.createDelta(base, target);
        byte[] result = DeltaEngine.applyDelta(base, delta);
        assertArrayEquals(target, result);
    }

    @Test
    @DisplayName("createDelta -> applyDelta round-trip: delete content")
    void testCreateAndApplyDelta_deleteContent() throws IOException {
        byte[] base = "Line 1\nLine 2\nLine 3\n".getBytes(StandardCharsets.UTF_8);
        byte[] target = "Line 1\nLine 3\n".getBytes(StandardCharsets.UTF_8);

        byte[] delta = DeltaEngine.createDelta(base, target);
        byte[] result = DeltaEngine.applyDelta(base, delta);
        assertArrayEquals(target, result);
    }

    @Test
    @DisplayName("Identical content -> identical result after applying delta")
    void testCreateAndApplyDelta_identical() throws IOException {
        byte[] content = "Same content unchanged".getBytes(StandardCharsets.UTF_8);
        byte[] delta = DeltaEngine.createDelta(content, content);
        byte[] result = DeltaEngine.applyDelta(content, delta);
        assertArrayEquals(content, result);
    }

    // ─────────────────────────────────────────────────────────────
    // createSimpleDelta Tests
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("createSimpleDelta -> applyDelta round-trip")
    void testCreateSimpleDelta_roundTrip() throws IOException {
        byte[] base = "base content here".getBytes(StandardCharsets.UTF_8);
        byte[] target = "base modified here".getBytes(StandardCharsets.UTF_8);

        byte[] delta = DeltaEngine.createSimpleDelta(base, target);
        byte[] result = DeltaEngine.applyDelta(base, delta);
        assertArrayEquals(target, result);
    }

    @Test
    @DisplayName("createSimpleDelta: empty base -> new content")
    void testCreateSimpleDelta_emptyBase() throws IOException {
        byte[] base = new byte[0];
        byte[] target = "new content".getBytes(StandardCharsets.UTF_8);

        byte[] delta = DeltaEngine.createSimpleDelta(base, target);
        byte[] result = DeltaEngine.applyDelta(base, delta);
        assertArrayEquals(target, result);
    }

    @Test
    @DisplayName("createSimpleDelta: completely different content")
    void testCreateSimpleDelta_completelyDifferent() throws IOException {
        byte[] base = "AAAAAAAAAA".getBytes(StandardCharsets.UTF_8);
        byte[] target = "BBBBBBBBBB".getBytes(StandardCharsets.UTF_8);

        byte[] delta = DeltaEngine.createSimpleDelta(base, target);
        byte[] result = DeltaEngine.applyDelta(base, delta);
        assertArrayEquals(target, result);
    }

    // ─────────────────────────────────────────────────────────────
    // Helper Methods
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

    @Test
    @DisplayName("Line inner class equals / hashCode consistency test")
    public void testLineEqualsAndHashCode() throws Exception {
        java.util.List<?> lines = (java.util.List<?>) DeltaEngine.class.getDeclaredMethod("splitLines", byte[].class)
                .invoke(null, (Object) "Line 1\n".getBytes(StandardCharsets.UTF_8));
        
        assertNotNull(lines);
        assertEquals(1, lines.size());
        Object lineObj1 = lines.get(0);
        
        java.util.List<?> lines2 = (java.util.List<?>) DeltaEngine.class.getDeclaredMethod("splitLines", byte[].class)
                .invoke(null, (Object) "Line 1\n".getBytes(StandardCharsets.UTF_8));
        Object lineObj2 = lines2.get(0);
        
        assertEquals(lineObj1, lineObj2);
        assertEquals(lineObj1.hashCode(), lineObj2.hashCode());
        
        java.util.List<?> lines3 = (java.util.List<?>) DeltaEngine.class.getDeclaredMethod("splitLines", byte[].class)
                .invoke(null, (Object) "Line 2\n".getBytes(StandardCharsets.UTF_8));
        Object lineObj3 = lines3.get(0);
        
        assertNotEquals(lineObj1, lineObj3);
        assertNotEquals("string", lineObj1);
        assertNotEquals(null, lineObj1);
    }

    @Test
    @DisplayName("createDelta edge cases: base null/empty and target null/empty")
    public void testCreateDeltaEdgeCases() throws Exception {
        // 1. base null / target null
        byte[] d1 = DeltaEngine.createDelta(null, null);
        assertArrayEquals(new byte[12], d1);
        
        // 2. base empty / target empty
        byte[] d2 = DeltaEngine.createDelta(new byte[0], new byte[0]);
        assertArrayEquals(new byte[12], d2);
        
        // 3. base null / target non-empty
        byte[] target = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] d3 = DeltaEngine.createDelta(null, target);
        assertArrayEquals(target, DeltaEngine.applyDelta(new byte[0], d3));
        
        // 4. base non-empty / target null
        byte[] base = "world".getBytes(StandardCharsets.UTF_8);
        byte[] d4 = DeltaEngine.createDelta(base, null);
        assertArrayEquals(new byte[0], DeltaEngine.applyDelta(base, d4));
        
        // 5. base non-empty / target empty
        byte[] d5 = DeltaEngine.createDelta(base, new byte[0]);
        assertArrayEquals(new byte[0], DeltaEngine.applyDelta(base, d5));
    }
}

