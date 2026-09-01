package com.github.search5.hg4j.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.search5.hg4j.errors.HgCorruptDataException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RevlogIndex — Index Management and Parsing Unit Tests")
public class RevlogIndexTest {

    @TempDir
    Path tempDir;

    private File createTempFile(String suffix) {
        return tempDir.resolve("test_revlog" + suffix).toFile();
    }

    /** checkAndUpdate() throttles disk re-checks to once per 200ms; tests exercising a second
     *  on-disk-change detection must clear that window first. */
    private void sleepPastThrottleWindow() {
        try {
            Thread.sleep(210);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    // ------------------------------------------------------------------
    // v2 docket fixture builders
    // ------------------------------------------------------------------

    private byte[] buildV2Header(int magic, int indexUuidSize, int olderIndexCount,
                                  int dataUuidSize, int olderDataCount,
                                  int sidedataUuidSize, int olderSidedataCount,
                                  long indexEnd, long pendingIndexEnd,
                                  long dataEnd, long pendingDataEnd,
                                  long sidedataEnd, long pendingSidedataEnd,
                                  byte compression) {
        ByteBuffer buf = ByteBuffer.allocate(RevlogIndex.V2_HEADER_SIZE);
        buf.putInt(magic);
        buf.put((byte) indexUuidSize);
        buf.put((byte) olderIndexCount);
        buf.put((byte) dataUuidSize);
        buf.put((byte) olderDataCount);
        buf.put((byte) sidedataUuidSize);
        buf.put((byte) olderSidedataCount);
        buf.putLong(indexEnd);
        buf.putLong(pendingIndexEnd);
        buf.putLong(dataEnd);
        buf.putLong(pendingDataEnd);
        buf.putLong(sidedataEnd);
        buf.putLong(pendingSidedataEnd);
        buf.put(compression);
        return buf.array();
    }

    /** S_OLD_UID layout: N * (1-byte size + 4-byte int) header entries, followed by the N concatenated blobs. */
    private byte[] buildOldUidBlock(String[] blobs) {
        int headerBytes = blobs.length * 5;
        int blobBytes = 0;
        for (String blob : blobs) blobBytes += blob.length();
        ByteBuffer buf = ByteBuffer.allocate(headerBytes + blobBytes);
        for (String blob : blobs) {
            buf.put((byte) blob.length());
            buf.putInt(0);
        }
        for (String blob : blobs) {
            buf.put(blob.getBytes(StandardCharsets.US_ASCII));
        }
        return buf.array();
    }

    private byte[] buildV2Record(long offset, int flags, int compLen, int uncompLen,
                                  int baseRev, int linkRev, int parent1, int parent2,
                                  byte[] nodeId20, boolean isChangelogV2) {
        ByteBuffer buf = ByteBuffer.allocate(RevlogIndex.V2_RECORD_SIZE);
        long offsetFlags = (offset << 16) | (flags & 0xFFFF);
        buf.putLong(offsetFlags);
        buf.putInt(compLen);
        buf.putInt(uncompLen);
        if (isChangelogV2) {
            buf.putInt(parent1);
            buf.putInt(parent2);
        } else {
            buf.putInt(baseRev);
            buf.putInt(linkRev);
            buf.putInt(parent1);
            buf.putInt(parent2);
        }
        byte[] node32 = new byte[32];
        System.arraycopy(nodeId20, 0, node32, 0, 20);
        buf.put(node32);
        return buf.array();
    }

    private byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
    }

    @Test
    @DisplayName("deriveRadix() uses the whole filename as radix when it has no extension (v2 docket file with no dot)")
    void testLoadIndex_v2DocketFileNameWithoutExtensionUsesWholeNameAsRadix() throws IOException {
        File file = tempDir.resolve("docket_no_extension").toFile();
        byte[] header = buildV2Header(RevlogIndex.MAGIC_CHANGELOGV2, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, (byte) 0);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(header);
        }

        File companionIndex = new File(tempDir.toFile(), "docket_no_extension-.idx");
        byte[] node0 = new byte[20];
        node0[0] = 0x66;
        byte[] rec0 = buildV2Record(0, 0, 12, 22, 0, 0, -1, -1, node0, true);
        try (FileOutputStream out = new FileOutputStream(companionIndex)) {
            out.write(rec0);
        }

        RevlogIndex index = new RevlogIndex(file);
        assertEquals("docket_no_extension-.idx", index.getResolvedIndexFile().getName());
        assertEquals(1, index.getRevisionCount());
        assertEquals(0, index.findRevision(node0));
    }

    @Test
    @DisplayName("Throws when a v2 docket header is shorter than the fixed 59-byte S_HEADER")
    void testLoadIndex_v2DocketTooShortThrows() throws IOException {
        File file = createTempFile(".i");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(ByteBuffer.allocate(4).putInt(RevlogIndex.MAGIC_REVLOGV2).array());
            out.write(new byte[10]); // total 14 bytes, well short of the 59-byte S_HEADER
        }

        HgCorruptDataException ex = assertThrows(HgCorruptDataException.class, () -> new RevlogIndex(file));
        assertTrue(ex.getMessage().contains("too short"));
    }

    @Test
    @DisplayName("Throws when a v2 docket points at a companion index file that does not exist on disk")
    void testLoadIndex_v2DocketMissingCompanionFileThrows() throws IOException {
        File file = createTempFile(".i");
        byte[] header = buildV2Header(RevlogIndex.MAGIC_CHANGELOGV2, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, (byte) 0);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(header);
        }

        HgCorruptDataException ex = assertThrows(HgCorruptDataException.class, () -> new RevlogIndex(file));
        assertTrue(ex.getMessage().contains("companion index file missing"));
    }

    @Test
    @DisplayName("Skips older uuid entries and resolves current index/data/sidedata companions (plain REVLOGV2, non-changelog record layout)")
    void testLoadIndex_v2SkipsOlderUuidsAndDecodesNonChangelogRecord() throws IOException {
        File file = createTempFile(".i");
        String indexUuid = "aaaaaaaa";
        String dataUuid = "bbbbbbbb";
        String sidedataUuid = "cccccccc";

        byte[] header = buildV2Header(RevlogIndex.MAGIC_REVLOGV2,
                indexUuid.length(), 1, dataUuid.length(), 0, sidedataUuid.length(), 1,
                0xc0L, 0xc0L, 0xd0L, 0xd0L, 0x50L, 0x50L, (byte) 7);
        byte[] tail = concat(
                indexUuid.getBytes(StandardCharsets.US_ASCII),
                buildOldUidBlock(new String[]{"zzzz"}),
                dataUuid.getBytes(StandardCharsets.US_ASCII),
                sidedataUuid.getBytes(StandardCharsets.US_ASCII),
                buildOldUidBlock(new String[]{"yyy"})
        );
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(header);
            out.write(tail);
        }

        String radix = file.getName().substring(0, file.getName().lastIndexOf('.'));
        File companionIndex = new File(tempDir.toFile(), radix + "-" + indexUuid + ".idx");
        byte[] node0 = new byte[20];
        node0[0] = 0x55;
        byte[] rec0 = buildV2Record(0, 0, 40, 50, 0, 3, -1, -1, node0, false);
        try (FileOutputStream out = new FileOutputStream(companionIndex)) {
            out.write(rec0);
        }

        RevlogIndex index = new RevlogIndex(file);

        assertTrue(index.isV2());
        assertFalse(index.isChangelogV2());
        assertEquals(0xc0L, index.getDocketIndexEnd());
        assertEquals(0xd0L, index.getDocketDataEnd());
        assertEquals(0x50L, index.getDocketSidedataEnd());
        assertEquals((byte) 7, index.getDocketCompression());
        assertEquals(radix + "-" + indexUuid + ".idx", index.getResolvedIndexFile().getName());
        assertEquals(radix + "-" + dataUuid + ".dat", index.getResolvedDataFile().getName());
        assertEquals(radix + "-" + sidedataUuid + ".sda", index.getResolvedSidedataFile().getName());

        assertEquals(1, index.getRevisionCount());
        assertEquals(0, index.findRevision(node0));

        Revlog.IndexRecord rec = index.getIndexRecord(0);
        assertEquals(0, rec.baseRev());
        assertEquals(3, rec.linkRev());
        assertEquals(-1, rec.parent1());
        assertEquals(-1, rec.parent2());
        assertEquals(40, rec.compLen());
        assertEquals(50, rec.uncompLen());
    }

    @Test
    @DisplayName("loadIndex() grows the internal fileOffsets array past its initial 1024 capacity")
    void testLoadIndex_growsInternalFileOffsetsArrayBeyondInitialCapacity() throws IOException {
        File file = createTempFile(".i");
        int total = 1025;
        try (FileOutputStream out = new FileOutputStream(file)) {
            for (int i = 0; i < total; i++) {
                byte[] node = new byte[20];
                node[0] = (byte) (i & 0xFF);
                node[1] = (byte) ((i >> 8) & 0xFF);
                byte[] rec = createMockIndexRecord(0, 0, 10, 20, 0, i, i > 0 ? 0 : -1, -1, node, i == 0, false);
                out.write(rec);
            }
        }

        RevlogIndex index = new RevlogIndex(file);
        assertEquals(total, index.getRevisionCount());

        byte[] lastNode = new byte[20];
        lastNode[0] = (byte) ((total - 1) & 0xFF);
        lastNode[1] = (byte) (((total - 1) >> 8) & 0xFF);
        assertEquals(total - 1, index.findRevision(lastNode));

        Revlog.IndexRecord lastRec = index.getIndexRecord(total - 1);
        assertEquals(total - 1, lastRec.linkRev());
    }

    @Test
    @DisplayName("A checkAndUpdate() triggered by on-disk growth incrementally parses newly appended records")
    void testCheckAndUpdate_incrementalGrowthParsesNewlyAppendedRecords() throws IOException {
        File file = createTempFile(".i");
        byte[] node0 = new byte[20];
        node0[0] = 0x0A;
        byte[] rec0 = createMockIndexRecord(0, 0, 10, 20, 0, 5, -1, -1, node0, true, false);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(rec0);
        }

        RevlogIndex index = new RevlogIndex(file);
        assertEquals(1, index.getRevisionCount());

        byte[] node1 = new byte[20];
        node1[0] = 0x0B;
        byte[] rec1 = createMockIndexRecord(64, 0, 15, 30, 0, 6, 0, -1, node1, false, false);
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(rec1); // written directly to disk, bypassing addRecord() so addedRecords stays empty
        }
        sleepPastThrottleWindow();

        assertEquals(2, index.getRevisionCount(), "checkAndUpdate() must detect the on-disk growth and incrementally load rev 1");
        assertEquals(1, index.findRevision(node1));
        Revlog.IndexRecord loaded = index.getIndexRecord(1);
        assertEquals(15, loaded.compLen());
        assertEquals(6, loaded.linkRev());
    }

    @Test
    @DisplayName("A checkAndUpdate() triggered by on-disk growth incrementally parses a newly appended inline record's data span")
    void testCheckAndUpdate_incrementalGrowthHandlesInlineFormat() throws IOException {
        File file = createTempFile(".i");
        byte[] node0 = new byte[20];
        node0[0] = 0x0A;
        byte[] rec0 = createMockIndexRecord(0, 0, 10, 20, 0, 5, -1, -1, node0, true, true);
        byte[] inlineData0 = new byte[10];
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(rec0);
            out.write(inlineData0);
        }

        RevlogIndex index = new RevlogIndex(file);
        assertEquals(1, index.getRevisionCount());
        assertTrue(index.isInline());

        byte[] node1 = new byte[20];
        node1[0] = 0x0B;
        byte[] rec1 = createMockIndexRecord(74, 0, 15, 30, 0, 6, 0, -1, node1, false, true);
        byte[] inlineData1 = new byte[15];
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(rec1);
            out.write(inlineData1); // full inline payload present -> incremental parse succeeds
        }
        sleepPastThrottleWindow();

        assertEquals(2, index.getRevisionCount(), "checkAndUpdate() must incrementally parse the appended inline record");
        assertEquals(1, index.findRevision(node1));
        assertEquals(15, index.getIndexRecord(1).compLen());
    }

    @Test
    @DisplayName("checkAndUpdate() silently swallows a truncated-inline-data corruption discovered during incremental growth parsing")
    void testCheckAndUpdate_incrementalGrowthWithTruncatedInlineDataIsSwallowed() throws IOException {
        File file = createTempFile(".i");
        byte[] node0 = new byte[20];
        node0[0] = 0x0A;
        byte[] rec0 = createMockIndexRecord(0, 0, 10, 20, 0, 5, -1, -1, node0, true, true);
        byte[] inlineData0 = new byte[10];
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(rec0);
            out.write(inlineData0);
        }

        RevlogIndex index = new RevlogIndex(file);
        assertEquals(1, index.getRevisionCount());

        // Append only the 64-byte record header (declaring compLen=15) without its inline payload,
        // so loadIndexIncremental() computes nextPos beyond the actual file length.
        byte[] node1 = new byte[20];
        node1[0] = 0x0B;
        byte[] rec1 = createMockIndexRecord(74, 0, 15, 30, 0, 6, 0, -1, node1, false, true);
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(rec1);
        }
        sleepPastThrottleWindow();

        assertEquals(1, index.getRevisionCount(),
                "the truncated-inline-data corruption raised inside loadIndexIncremental() must be swallowed by checkAndUpdate(), leaving rev count unchanged");
    }

    @Test
    @DisplayName("checkAndUpdate() silently swallows corruption detected when the index file shrinks below the minimum record size")
    void testCheckAndUpdate_shrunkFileIsSwallowedAndResetsToEmpty() throws IOException {
        File file = createTempFile(".i");
        byte[] node0 = new byte[20];
        node0[0] = 0x0A;
        byte[] node1 = new byte[20];
        node1[0] = 0x0B;
        byte[] rec0 = createMockIndexRecord(0, 0, 10, 20, 0, 5, -1, -1, node0, true, false);
        byte[] rec1 = createMockIndexRecord(64, 0, 15, 30, 0, 6, 0, -1, node1, false, false);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(rec0);
            out.write(rec1);
        }

        RevlogIndex index = new RevlogIndex(file);
        assertEquals(2, index.getRevisionCount());

        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[10]); // truncate below the 64-byte minimum record size
        }
        sleepPastThrottleWindow();

        assertEquals(0, index.getRevisionCount(),
                "on-disk truncation caught inside checkAndUpdate() must reset the in-memory index to empty rather than propagate");
    }

    @Test
    @DisplayName("getIndexRecord() falls back to addedRecords when the SoftReference cache entry has been evicted")
    void testGetIndexRecord_fallsBackToAddedRecordsWhenCacheEvicted() throws Exception {
        File file = new File(tempDir.toFile(), "non_existent_added.i");
        RevlogIndex index = new RevlogIndex(file);

        byte[] node = new byte[20];
        node[0] = 0x22;
        Revlog.IndexRecord record = new Revlog.IndexRecord(0, 0, 0, 50, 60, 0, 3, -1, -1, node);
        index.addRecord(record);

        // Simulate what happens when the JVM has reclaimed the SoftReference-cached copy but the
        // record is still pending disk flush: only addRecord()'s addedRecords bookkeeping should survive.
        Field recordCacheField = RevlogIndex.class.getDeclaredField("recordCache");
        recordCacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Integer, SoftReference<Revlog.IndexRecord>> recordCache =
                (Map<Integer, SoftReference<Revlog.IndexRecord>>) recordCacheField.get(index);
        recordCache.remove(0);

        assertSame(record, index.getIndexRecord(0));
    }

    @Test
    @DisplayName("getIndexRecord() wraps a disk read failure as RuntimeException when the index file disappears after load")
    void testGetIndexRecord_wrapsIOExceptionWhenFileDeletedAfterLoad() throws IOException {
        File file = createTempFile(".i");
        byte[] node = new byte[20];
        node[0] = 0x33;
        byte[] rec = createMockIndexRecord(0, 0, 10, 20, 0, 1, -1, -1, node, true, false);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(rec);
        }

        RevlogIndex index = new RevlogIndex(file);
        assertEquals(1, index.getRevisionCount());
        assertTrue(file.delete());

        RuntimeException ex = assertThrows(RuntimeException.class, () -> index.getIndexRecord(0));
        assertTrue(ex.getMessage().contains("Failed to lazy load"));
        assertNotNull(ex.getCause());
        assertInstanceOf(IOException.class, ex.getCause());
    }

    @Test
    @DisplayName("getFileOffset() throws IndexOutOfBoundsException for negative or too-large revisions")
    void testGetFileOffset_throwsForOutOfBoundsRevision() throws IOException {
        File file = createTempFile(".i");
        byte[] node = new byte[20];
        byte[] rec = createMockIndexRecord(0, 0, 10, 20, 0, 1, -1, -1, node, true, false);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(rec);
        }

        RevlogIndex index = new RevlogIndex(file);
        assertThrows(IndexOutOfBoundsException.class, () -> index.getFileOffset(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> index.getFileOffset(5));
    }

    @Test
    @DisplayName("addRecord() on an inline revlog computes the physical offset from the previous record's compLen")
    void testAddRecord_inlineFormatComputesOffsetFromPreviousRecord() throws IOException {
        File file = createTempFile(".i");
        byte[] node0 = new byte[20];
        node0[0] = 0x0A;
        byte[] node1 = new byte[20];
        node1[0] = 0x0B;
        byte[] rec0 = createMockIndexRecord(0, 0, 10, 20, 0, 10, -1, -1, node0, true, true);
        byte[] inlineData0 = new byte[10];
        byte[] rec1 = createMockIndexRecord(10, 0, 15, 30, 0, 11, 0, -1, node1, false, true);
        byte[] inlineData1 = new byte[15];
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(rec0);
            out.write(inlineData0);
            out.write(rec1);
            out.write(inlineData1);
        }

        RevlogIndex index = new RevlogIndex(file);
        assertEquals(2, index.getRevisionCount());
        assertTrue(index.isInline());

        byte[] node2 = new byte[20];
        node2[0] = 0x0C;
        Revlog.IndexRecord newRec = new Revlog.IndexRecord(2, 0, 0, 25, 40, 0, 12, 1, -1, node2);
        long expectedOffset = index.getFileOffset(1) + 64 + index.getIndexRecord(1).compLen();
        index.addRecord(newRec);

        assertEquals(3, index.getRevisionCount());
        assertEquals(expectedOffset, index.getFileOffset(2));
        assertSame(newRec, index.getIndexRecord(2));
    }

    @Test
    @DisplayName("addRecord() grows the internal fileOffsets array when given a revision far beyond its initial capacity")
    void testAddRecord_growsFileOffsetsArrayForLargeRevisionNumber() throws IOException {
        File file = new File(tempDir.toFile(), "non_existent_growth.i");
        RevlogIndex index = new RevlogIndex(file);
        assertEquals(0, index.getRevisionCount());

        byte[] node = new byte[20];
        node[0] = 0x77;
        Revlog.IndexRecord farRecord = new Revlog.IndexRecord(2000, 0, 0, 500, 600, 0, 5, -1, -1, node);
        index.addRecord(farRecord);

        assertEquals(2001, index.getRevisionCount());
        assertEquals(2000L * 64, index.getFileOffset(2000));
        assertSame(farRecord, index.getIndexRecord(2000));
    }

    @Test
    @DisplayName("findByHexPrefix() matches by prefix and returns an empty list for no-match, null, or empty prefixes")
    void testFindByHexPrefix() throws IOException {
        File file = createTempFile(".i");
        byte[] node0 = new byte[20];
        node0[0] = 0x0A;
        byte[] node1 = new byte[20];
        node1[0] = 0x0B;
        byte[] rec0 = createMockIndexRecord(0, 0, 100, 200, 0, 10, -1, -1, node0, true, false);
        byte[] rec1 = createMockIndexRecord(100, 0, 150, 300, 0, 11, 0, -1, node1, false, false);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(rec0);
            out.write(rec1);
        }

        RevlogIndex index = new RevlogIndex(file);

        String prefix0 = com.github.search5.hg4j.util.NodeIdUtil.toHex(node0).substring(0, 2);
        assertEquals(1, index.findByHexPrefix(prefix0).size());
        assertArrayEquals(node0, index.findByHexPrefix(prefix0).get(0));

        assertTrue(index.findByHexPrefix("ffffffff").isEmpty());
        assertTrue(index.findByHexPrefix(null).isEmpty());
        assertTrue(index.findByHexPrefix("").isEmpty());
    }
}
