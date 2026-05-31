package io.github.search5.hg4j.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RevlogIndex — Index Management and Parsing Unit Tests")
public class RevlogIndexTest {

    @TempDir
    Path tempDir;

    private File createTempFile(String suffix) {
        return tempDir.resolve("test_revlog" + suffix).toFile();
    }

    private byte[] createMockIndexRecord(long offset, int flags, int compLen, int uncompLen,
                                         int baseRev, int linkRev, int parent1, int parent2,
                                         byte[] nodeId, boolean isFirstRecord, boolean inline) {
        ByteBuffer buf = ByteBuffer.allocate(64);
        long offsetFlags;
        if (isFirstRecord) {
            long formatFlags = inline ? 0x0003L : 0x0002L; // 0x0001 is inline, 0x0002 is generaldelta
            long version = 1L;
            offsetFlags = (formatFlags << 48) | (version << 32) | (flags & 0xFFFF);
        } else {
            offsetFlags = (offset << 16) | (flags & 0xFFFF);
        }

        buf.putLong(offsetFlags);
        buf.putInt(compLen);
        buf.putInt(uncompLen);
        buf.putInt(baseRev);
        buf.putInt(linkRev);
        buf.putInt(parent1);
        buf.putInt(parent2);

        byte[] nodeId32 = new byte[32];
        if (nodeId != null) {
            System.arraycopy(nodeId, 0, nodeId32, 0, Math.min(nodeId.length, 20));
        }
        buf.put(nodeId32);

        return buf.array();
    }

    @Test
    @DisplayName("Loads as empty index when index file does not exist")
    void testLoadIndex_nonExistentFile() throws IOException {
        File file = new File(tempDir.toFile(), "non_existent.i");
        RevlogIndex index = new RevlogIndex(file);
        assertEquals(0, index.getRevisionCount());
    }

    @Test
    @DisplayName("Loads as empty index when index file size is 0")
    void testLoadIndex_emptyFile() throws IOException {
        File file = createTempFile(".i");
        assertTrue(file.createNewFile());

        RevlogIndex index = new RevlogIndex(file);
        assertEquals(0, index.getRevisionCount());
    }

    @Test
    @DisplayName("Throws IOException if index file size is less than 64 bytes")
    void testLoadIndex_invalidShortFile() throws IOException {
        File file = createTempFile(".i");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[30]); // 30 bytes only
        }

        assertThrows(IOException.class, () -> new RevlogIndex(file));
    }

    @Test
    @DisplayName("Correctly parses a single record (version 1, not inline)")
    void testLoadIndex_validSingleRecord() throws IOException {
        File file = createTempFile(".i");
        byte[] mockNodeId = new byte[20];
        mockNodeId[0] = 0x12;
        mockNodeId[19] = 0x34;

        byte[] record = createMockIndexRecord(0, 0, 100, 200, 0, 5, -1, -1, mockNodeId, true, false);

        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(record);
        }

        RevlogIndex index = new RevlogIndex(file);
        assertEquals(1, index.getRevisionCount());
        assertFalse(index.isInline());

        Revlog.IndexRecord rec = index.getIndexRecord(0);
        assertNotNull(rec);
        assertEquals(0, rec.getRevision());
        assertEquals(0, rec.getOffset());
        assertEquals(100, rec.getCompLen());
        assertEquals(200, rec.getUncompLen());
        assertEquals(0, rec.getBaseRev());
        assertEquals(5, rec.getLinkRev());
        assertEquals(-1, rec.getParent1());
        assertEquals(-1, rec.getParent2()); // Need to see how parents are handled if they are null in the IndexRecord constructor, but...
        // Wait, there is logic in the IndexRecord constructor that clips the NodeID using Arrays.copyOf.
        // Let's verify if NodeID is correctly populated.
        byte[] clippedNode = Arrays.copyOf(rec.getNodeId(), 20);
        assertArrayEquals(mockNodeId, clippedNode);
    }

    @Test
    @DisplayName("Correctly parses multiple records")
    void testLoadIndex_validMultipleRecords() throws IOException {
        File file = createTempFile(".i");
        byte[] node0 = new byte[20]; node0[0] = 0x0A;
        byte[] node1 = new byte[20]; node1[0] = 0x0B;

        byte[] rec0 = createMockIndexRecord(0, 0, 100, 200, 0, 10, -1, -1, node0, true, false);
        byte[] rec1 = createMockIndexRecord(100, 0, 150, 300, 0, 11, 0, -1, node1, false, false);

        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(rec0);
            out.write(rec1);
        }

        RevlogIndex index = new RevlogIndex(file);
        assertEquals(2, index.getRevisionCount());

        Revlog.IndexRecord r0 = index.getIndexRecord(0);
        assertEquals(0, r0.getOffset());
        assertEquals(100, r0.getCompLen());

        Revlog.IndexRecord r1 = index.getIndexRecord(1);
        assertEquals(100, r1.getOffset());
        assertEquals(150, r1.getCompLen());
        assertEquals(0, r1.getParent1());
        assertEquals(-1, r1.getParent2());
    }

    @Test
    @DisplayName("Parses by skipping inline data when the inline flag is set")
    void testLoadIndex_inlineSkip() throws IOException {
        File file = createTempFile(".i");
        byte[] node0 = new byte[20]; node0[0] = 0x0A;
        byte[] node1 = new byte[20]; node1[0] = 0x0B;

        byte[] rec0 = createMockIndexRecord(0, 0, 10, 20, 0, 10, -1, -1, node0, true, true); // inline = true, compLen = 10
        byte[] inlineData = new byte[10]; // 10 bytes inline data for rec0
        Arrays.fill(inlineData, (byte) 'X');

        byte[] rec1 = createMockIndexRecord(10, 0, 15, 30, 0, 11, 0, -1, node1, false, true);
        byte[] inlineData1 = new byte[15]; // 15 bytes inline data for rec1
        Arrays.fill(inlineData1, (byte) 'Y');

        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(rec0);
            out.write(inlineData);
            out.write(rec1);
            out.write(inlineData1);
        }

        RevlogIndex index = new RevlogIndex(file);
        assertEquals(2, index.getRevisionCount());
        assertTrue(index.isInline());

        Revlog.IndexRecord r0 = index.getIndexRecord(0);
        assertEquals(0, r0.getOffset());
        assertEquals(10, r0.getCompLen());

        Revlog.IndexRecord r1 = index.getIndexRecord(1);
        assertEquals(10, r1.getOffset());
        assertEquals(15, r1.getCompLen());
    }

    @Test
    @DisplayName("Search for correct revision using findRevision")
    void testFindRevision() throws IOException {
        File file = createTempFile(".i");
        byte[] node0 = new byte[20]; node0[0] = 0x0A;
        byte[] node1 = new byte[20]; node1[0] = 0x0B;

        byte[] rec0 = createMockIndexRecord(0, 0, 100, 200, 0, 10, -1, -1, node0, true, false);
        byte[] rec1 = createMockIndexRecord(100, 0, 150, 300, 0, 11, 0, -1, node1, false, false);

        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(rec0);
            out.write(rec1);
        }

        RevlogIndex index = new RevlogIndex(file);
        assertEquals(0, index.findRevision(node0));
        assertEquals(1, index.findRevision(node1));

        byte[] nodeFake = new byte[20];
        nodeFake[0] = (byte) 0xFF;
        assertEquals(-1, index.findRevision(nodeFake));
        assertEquals(-1, index.findRevision(null));
    }

    @Test
    @DisplayName("Add a new record in memory using addRecord")
    void testAddRecord() throws IOException {
        File file = createTempFile(".i");
        RevlogIndex index = new RevlogIndex(file);
        assertEquals(0, index.getRevisionCount());

        byte[] node = new byte[20];
        node[0] = 0x0A;
        Revlog.IndexRecord record = new Revlog.IndexRecord(0, 0, 0, 100, 200, 0, 10, -1, -1, node);
        index.addRecord(record);

        assertEquals(1, index.getRevisionCount());
        assertEquals(0, index.findRevision(node));
        assertSame(record, index.getIndexRecord(0));
    }
}
