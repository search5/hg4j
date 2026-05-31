package io.github.search5.hg4j.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

public class RevlogTest {

    @Test
    public void testDeltaApplication() throws Exception {
        byte[] base = "Hello World\n".getBytes(StandardCharsets.UTF_8);
        
        // Hunk to replace "World\n" (offset 6 to 12) with "Mercurial\n" (length 10)
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer buf = ByteBuffer.allocate(12);
        buf.putInt(6); // start
        buf.putInt(12); // end
        buf.putInt(10); // length
        out.write(buf.array());
        out.write("Mercurial\n".getBytes(StandardCharsets.UTF_8));
        
        byte[] delta = out.toByteArray();
        byte[] result = Revlog.applyDelta(base, delta);
        
        assertEquals("Hello Mercurial\n", new String(result, StandardCharsets.UTF_8));

        // Hunk to replace "World" (offset 6 to 11) with "Mercurial" (length 9), leaving "\n" (index 11 to 12)
        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        ByteBuffer buf2 = ByteBuffer.allocate(12);
        buf2.putInt(6); // start
        buf2.putInt(11); // end
        buf2.putInt(9); // length
        out2.write(buf2.array());
        out2.write("Mercurial".getBytes(StandardCharsets.UTF_8));
        
        byte[] delta2 = out2.toByteArray();
        byte[] result2 = Revlog.applyDelta(base, delta2);
        
        assertEquals("Hello Mercurial\n", new String(result2, StandardCharsets.UTF_8));
    }

    @Test
    public void testDeltaApplicationThrowsExceptions() {
        byte[] base = "Hello World\n".getBytes(StandardCharsets.UTF_8);
        
        // Truncated hunk header
        assertThrows(IOException.class, () -> Revlog.applyDelta(base, new byte[5]));
        
        // Truncated insertion data
        ByteBuffer buf = ByteBuffer.allocate(12);
        buf.putInt(6);
        buf.putInt(12);
        buf.putInt(10); // claims 10 bytes, but we only supply 2
        byte[] truncated = new byte[14];
        System.arraycopy(buf.array(), 0, truncated, 0, 12);
        truncated[12] = 'A';
        truncated[13] = 'B';
        assertThrows(IOException.class, () -> Revlog.applyDelta(base, truncated));

        // Invalid offsets (start > end)
        ByteBuffer buf2 = ByteBuffer.allocate(12);
        buf2.putInt(10);
        buf2.putInt(6);
        buf2.putInt(2);
        byte[] invalidOffsets = new byte[14];
        System.arraycopy(buf2.array(), 0, invalidOffsets, 0, 12);
        assertThrows(IOException.class, () -> Revlog.applyDelta(base, invalidOffsets));
    }

    @Test
    public void testEmptyRevlogAndAppend(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("test.i").toFile();
        File datFile = tempDir.resolve("test.d").toFile();

        // 1. Initialize and verify it is empty
        Revlog revlog = new Revlog(idxFile, datFile);
        assertEquals(0, revlog.getRevisionCount());

        // 2. Append first revision (fulltext snapshot)
        byte[] p1Node = new byte[20];
        byte[] p2Node = new byte[20];
        byte[] content0 = "Line 1\nLine 2\n".getBytes(StandardCharsets.UTF_8);
        
        byte[] node0 = revlog.appendRevision(content0, -1, -1, p1Node, p2Node, 0);
        assertEquals(1, revlog.getRevisionCount());
        assertNotNull(node0);
        assertEquals(20, node0.length);

        // Verify loaded revision 0
        byte[] loadedContent0 = revlog.getRevisionContent(0);
        assertArrayEquals(content0, loadedContent0);

        // 3. Append second revision as fulltext snapshot
        byte[] content1 = "Line 1\nLine 2\nLine 3\n".getBytes(StandardCharsets.UTF_8);
        revlog.appendRevision(content1, 0, -1, node0, p2Node, 1);
        assertEquals(2, revlog.getRevisionCount());

        // Verify loaded revision 1
        byte[] loadedContent1 = revlog.getRevisionContent(1);
        assertArrayEquals(content1, loadedContent1);

        // Verify index records
        Revlog.IndexRecord rec0 = revlog.getIndexRecord(0);
        assertEquals(0, rec0.getRevision());
        assertEquals(-1, rec0.getParent1());
        assertEquals(-1, rec0.getParent2());
        assertEquals(0, rec0.getBaseRev());

        Revlog.IndexRecord rec1 = revlog.getIndexRecord(1);
        assertEquals(1, rec1.getRevision());
        assertEquals(0, rec1.getParent1());
        assertEquals(-1, rec1.getParent2());
        // Since we decided to store revision as a robust delta chain
        assertEquals(0, rec1.getBaseRev()); 
    }

    @Test
    public void testLoadExistingRevlog(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("existing.i").toFile();
        File datFile = tempDir.resolve("existing.d").toFile();

        // Create and write an entry using Revlog class
        Revlog writer = new Revlog(idxFile, datFile);
        byte[] content = "Hello Mercurial Revlog\n".getBytes(StandardCharsets.UTF_8);
        byte[] pNode = new byte[20];
        writer.appendRevision(content, -1, -1, pNode, pNode, 100);

        // Now load a new instance from those files
        Revlog reader = new Revlog(idxFile, datFile);
        assertEquals(1, reader.getRevisionCount());
        assertArrayEquals(content, reader.getRevisionContent(0));
        
        Revlog.IndexRecord rec = reader.getIndexRecord(0);
        assertEquals(100, rec.getLinkRev());
    }

    @Test
    public void testLoadInvalidHeader(@TempDir Path tempDir) throws IOException {
        File idxFile = tempDir.resolve("invalid.i").toFile();
        File datFile = tempDir.resolve("invalid.d").toFile();

        // Write an invalid version (version 2) in a 64-byte record
        ByteBuffer buf = ByteBuffer.allocate(64);
        buf.putInt(0x00020000);
        java.nio.file.Files.write(idxFile.toPath(), buf.array());

        assertThrows(IOException.class, () -> new Revlog(idxFile, datFile));
    }

    @Test
    public void testLoadTooShortIndex(@TempDir Path tempDir) throws IOException {
        File idxFile = tempDir.resolve("short.i").toFile();
        File datFile = tempDir.resolve("short.d").toFile();

        // Write only 10 bytes
        java.nio.file.Files.write(idxFile.toPath(), new byte[10]);

        assertThrows(IOException.class, () -> new Revlog(idxFile, datFile));
    }

    @Test
    public void testGetIndexRecordOutOfBounds(@TempDir Path tempDir) throws IOException {
        File idxFile = tempDir.resolve("bounds.i").toFile();
        File datFile = tempDir.resolve("bounds.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);

        assertThrows(IndexOutOfBoundsException.class, () -> revlog.getIndexRecord(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> revlog.getIndexRecord(0));
    }

    @Test
    public void testMissingDataFile(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("missing.i").toFile();
        File datFile = tempDir.resolve("missing.d").toFile();

        Revlog revlog = new Revlog(idxFile, datFile);
        byte[] content = "Hello".getBytes(StandardCharsets.UTF_8);
        byte[] p = new byte[20];
        revlog.appendRevision(content, -1, -1, p, p, 0);

        // Delete data file to trigger IOException on content read
        assertTrue(datFile.delete());
        assertThrows(IOException.class, () -> revlog.getRevisionContent(0));
    }

    @Test
    public void testIndexRecordGettersAndCoverages(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("getters.i").toFile();
        File datFile = tempDir.resolve("getters.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);

        // Test empty revision content read (covers line 168)
        assertArrayEquals(new byte[0], revlog.getRevisionContent(-1));

        // 1. Uncompressible append ('u')
        byte[] content = new byte[] { 'A' }; 
        byte[] p = new byte[20];
        byte[] node = revlog.appendRevision(content, -1, -1, p, p, 50);

        Revlog.IndexRecord rec = revlog.getIndexRecord(0);
        assertEquals(0, rec.getRevision());
        assertEquals(0, rec.getFlags());
        assertEquals(1, rec.getUncompLen());
        assertEquals(2, rec.getCompLen()); // 'u' + 'A' = 2 bytes
        assertArrayEquals(node, Arrays.copyOf(rec.getNodeId(), 20));

        // Read uncompressible content back
        byte[] loaded = revlog.getRevisionContent(0);
        assertArrayEquals(content, loaded);

        // 2. Compressible append ('x', covers line 278)
        byte[] largeContent = "A".repeat(1000).getBytes(StandardCharsets.UTF_8);
        revlog.appendRevision(largeContent, 0, -1, node, p, 51);

        Revlog.IndexRecord rec1 = revlog.getIndexRecord(1);
        assertEquals(1, rec1.getRevision());
        assertTrue(rec1.getCompLen() < rec1.getUncompLen(), "Should be compressed");
        
        byte[] loaded1 = revlog.getRevisionContent(1);
        assertArrayEquals(largeContent, loaded1);

        // 3. Test empty hunk (compLen = 0, covers line 210)
        // Overwrite the index file to set rec1's compLen to 0
        ByteBuffer buf0 = ByteBuffer.allocate(64);
        long offsetFlags0 = (0L << 48) | (1L << 32) | (0 & 0xFFFF);
        buf0.putLong(offsetFlags0);
        buf0.putInt(rec.getCompLen());
        buf0.putInt(rec.getUncompLen());
        buf0.putInt(rec.getBaseRev());
        buf0.putInt(rec.getLinkRev());
        buf0.putInt(rec.getParent1());
        buf0.putInt(rec.getParent2());
        buf0.put(rec.getNodeId());

        ByteBuffer buf1 = ByteBuffer.allocate(64);
        buf1.putLong(rec1.getOffset() << 16);
        buf1.putInt(0); // compLen = 0!
        buf1.putInt(0); // uncompLen = 0
        buf1.putInt(1);
        buf1.putInt(51);
        buf1.putInt(0);
        buf1.putInt(-1);
        buf1.put(rec1.getNodeId());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(buf0.array());
        out.write(buf1.array());
        java.nio.file.Files.write(idxFile.toPath(), out.toByteArray());

        Revlog reader = new Revlog(idxFile, datFile);
        byte[] emptyHunkResult = reader.getRevisionContent(1);
        assertEquals(0, emptyHunkResult.length);
    }

    @Test
    public void testSeekAndReadFailures(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("seek.i").toFile();
        File datFile = tempDir.resolve("seek.d").toFile();

        Revlog revlog = new Revlog(idxFile, datFile);
        byte[] p = new byte[20];
        revlog.appendRevision("Hello".getBytes(StandardCharsets.UTF_8), -1, -1, p, p, 0);
        revlog.appendRevision("World".getBytes(StandardCharsets.UTF_8), 0, -1, p, p, 1);

        // Read index records
        Revlog.IndexRecord rec0 = revlog.getIndexRecord(0);
        Revlog.IndexRecord rec1 = revlog.getIndexRecord(1);
        
        // Rewrite index with huge offset (e.g. 10000) for revision 1
        ByteBuffer buf0 = ByteBuffer.allocate(64);
        buf0.putLong(0x0000000100000000L); // standard version 1, separate
        buf0.putInt(rec0.getCompLen());
        buf0.putInt(rec0.getUncompLen());
        buf0.putInt(rec0.getBaseRev());
        buf0.putInt(rec0.getLinkRev());
        buf0.putInt(rec0.getParent1());
        buf0.putInt(rec0.getParent2());
        buf0.put(rec0.getNodeId());

        ByteBuffer buf1 = ByteBuffer.allocate(64);
        long offsetFlags = (10000L << 16) | (0 & 0xFFFF);
        buf1.putLong(offsetFlags);
        buf1.putInt(rec1.getCompLen());
        buf1.putInt(rec1.getUncompLen());
        buf1.putInt(rec1.getBaseRev());
        buf1.putInt(rec1.getLinkRev());
        buf1.putInt(rec1.getParent1());
        buf1.putInt(rec1.getParent2());
        buf1.put(rec1.getNodeId());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(buf0.array());
        out.write(buf1.array());
        java.nio.file.Files.write(idxFile.toPath(), out.toByteArray());

        Revlog reader = new Revlog(idxFile, datFile);
        assertThrows(IOException.class, () -> reader.getRevisionContent(1));

        // Test huge compLen
        ByteBuffer buf2 = ByteBuffer.allocate(64);
        buf2.putLong(rec1.getOffset() << 16); // normal offset
        buf2.putInt(10000); // huge compLen
        buf2.putInt(rec1.getUncompLen());
        buf2.putInt(rec1.getBaseRev());
        buf2.putInt(rec1.getLinkRev());
        buf2.putInt(rec1.getParent1());
        buf2.putInt(rec1.getParent2());
        buf2.put(rec1.getNodeId());

        out.reset();
        out.write(buf0.array());
        out.write(buf2.array());
        java.nio.file.Files.write(idxFile.toPath(), out.toByteArray());
        
        Revlog reader2 = new Revlog(idxFile, datFile);
        assertThrows(IOException.class, () -> reader2.getRevisionContent(1));
    }

    @Test
    public void testDecompressHunkEdgeCases(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("decomp.i").toFile();
        File datFile = tempDir.resolve("decomp.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);

        // 1. Zlib decompression failure (starts with 'x' but has garbage)
        byte[] p = new byte[20];
        revlog.appendRevision("A".getBytes(StandardCharsets.UTF_8), -1, -1, p, p, 0);

        // Overwrite data file with invalid zlib data
        byte[] invalidZlib = new byte[] { 'x', 0, 1, 2, 3 };
        java.nio.file.Files.write(datFile.toPath(), invalidZlib);

        // Mutate index record to match invalidZlib length
        ByteBuffer buf = ByteBuffer.allocate(64);
        buf.putLong(0x0000000100000000L);
        buf.putInt(5); // compLen
        buf.putInt(1); // uncompLen
        buf.putInt(0);
        buf.putInt(0);
        buf.putInt(-1);
        buf.putInt(-1);
        buf.put(new byte[32]);
        java.nio.file.Files.write(idxFile.toPath(), buf.array());

        Revlog reader = new Revlog(idxFile, datFile);
        assertThrows(IOException.class, () -> reader.getRevisionContent(0));

        // 2. Data starts with 0 (empty data)
        byte[] emptyHunk = new byte[] { 0 };
        java.nio.file.Files.write(datFile.toPath(), emptyHunk);
        buf.clear();
        buf.putLong(0x0000000100000000L);
        buf.putInt(1);
        buf.putInt(0);
        buf.putInt(0);
        buf.putInt(0);
        buf.putInt(-1);
        buf.putInt(-1);
        buf.put(new byte[32]);
        java.nio.file.Files.write(idxFile.toPath(), buf.array());

        Revlog reader2 = new Revlog(idxFile, datFile);
        byte[] empty = reader2.getRevisionContent(0);
        assertEquals(0, empty.length);

        // 3. Fallback uncompressed (starts with other bytes)
        byte[] rawHunk = new byte[] { 'y', 'e', 's' };
        java.nio.file.Files.write(datFile.toPath(), rawHunk);
        buf.clear();
        buf.putLong(0x0000000100000000L);
        buf.putInt(3);
        buf.putInt(3);
        buf.putInt(0);
        buf.putInt(0);
        buf.putInt(-1);
        buf.putInt(-1);
        buf.put(new byte[32]);
        java.nio.file.Files.write(idxFile.toPath(), buf.array());

        Revlog reader3 = new Revlog(idxFile, datFile);
        byte[] raw = reader3.getRevisionContent(0);
        assertEquals("yes", new String(raw, StandardCharsets.UTF_8));
    }

    @Test
    public void testInlineRevlogAndDeltaChain(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("inline.i").toFile();

        // Let's manually construct an inline index record and revision content
        // Revision 0: Fulltext snapshot, length 12
        byte[] content0 = "Hello World\n".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream zipOut0 = new ByteArrayOutputStream();
        zipOut0.write('x');
        Deflater def0 = new Deflater();
        def0.setInput(content0);
        def0.finish();
        byte[] zBuf = new byte[128];
        while (!def0.finished()) {
            zipOut0.write(zBuf, 0, def0.deflate(zBuf));
        }
        def0.end();
        byte[] zip0 = zipOut0.toByteArray();

        // Revision 1: Delta replacing "World\n" (offset 6 to 12) with "Mercurial\n" (length 10)
        ByteArrayOutputStream deltaOut = new ByteArrayOutputStream();
        ByteBuffer dbuf = ByteBuffer.allocate(12);
        dbuf.putInt(6);
        dbuf.putInt(12);
        dbuf.putInt(10);
        deltaOut.write(dbuf.array());
        deltaOut.write("Mercurial\n".getBytes(StandardCharsets.UTF_8));
        byte[] deltaBytes = deltaOut.toByteArray();

        ByteArrayOutputStream zipOut1 = new ByteArrayOutputStream();
        zipOut1.write('x');
        Deflater def1 = new Deflater();
        def1.setInput(deltaBytes);
        def1.finish();
        while (!def1.finished()) {
            zipOut1.write(zBuf, 0, def1.deflate(zBuf));
        }
        def1.end();
        byte[] zip1 = zipOut1.toByteArray();

        // Let's write both as inline records
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        
        // Rec 0: inline, offset is 0, but since inline = 1, first 4 bytes is 0x00010001
        ByteBuffer rec0 = ByteBuffer.allocate(64);
        rec0.putLong(0x0001000100000000L); // version 1, inline = 1, offset = 0, flags = 0
        rec0.putInt(zip0.length);
        rec0.putInt(content0.length);
        rec0.putInt(0); // baseRev = 0
        rec0.putInt(0); // linkRev
        rec0.putInt(-1);
        rec0.putInt(-1);
        rec0.put(new byte[32]);
        out.write(rec0.array());
        out.write(zip0);

        // Rec 1: inline, offset is zip0.length
        long offset1 = zip0.length;
        ByteBuffer rec1 = ByteBuffer.allocate(64);
        rec1.putLong((offset1 << 16) | 0);
        rec1.putInt(zip1.length);
        rec1.putInt(deltaBytes.length); // uncompLen is delta size
        rec1.putInt(0); // baseRev = 0
        rec1.putInt(0);
        rec1.putInt(0);
        rec1.putInt(-1);
        rec1.put(new byte[32]);
        out.write(rec1.array());
        out.write(zip1);

        java.nio.file.Files.write(idxFile.toPath(), out.toByteArray());

        Revlog inlineReader = new Revlog(idxFile, new File("/nonexistent"));
        assertEquals(2, inlineReader.getRevisionCount());

        byte[] loaded0 = inlineReader.getRevisionContent(0);
        assertEquals("Hello World\n", new String(loaded0, StandardCharsets.UTF_8));

        // Reconstruct delta chain
        byte[] loaded1 = inlineReader.getRevisionContent(1);
        assertEquals("Hello Mercurial\n", new String(loaded1, StandardCharsets.UTF_8));

        // Test truncated inline revlog error path
        // Truncate the file mid-zip1 data
        byte[] truncatedBytes = Arrays.copyOf(out.toByteArray(), out.size() - 5);
        File truncFile = tempDir.resolve("trunc_inline.i").toFile();
        java.nio.file.Files.write(truncFile.toPath(), truncatedBytes);
        assertThrows(IOException.class, () -> new Revlog(truncFile, new File("/nonexistent")));
    }

    @Test
    public void testDeltaApplicationInvalidHunkOffset() {
        byte[] base = "Hello World\n".getBytes(StandardCharsets.UTF_8);
        
        // Out of bounds hunk offset
        ByteBuffer buf = ByteBuffer.allocate(12);
        buf.putInt(100); // start = 100 > baseText.length
        buf.putInt(105);
        buf.putInt(1);
        byte[] delta = new byte[13];
        System.arraycopy(buf.array(), 0, delta, 0, 12);
        delta[12] = 'X';
        assertThrows(IOException.class, () -> Revlog.applyDelta(base, delta));
    }

    @Test
    public void testMultiLevelDeltaChain(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("multichain.i").toFile();

        // 1. Snapshot revision 0: "Hello\n"
        byte[] content0 = "Hello\n".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream zipOut0 = new ByteArrayOutputStream();
        zipOut0.write('x');
        Deflater def0 = new Deflater();
        def0.setInput(content0);
        def0.finish();
        byte[] zBuf = new byte[128];
        while (!def0.finished()) {
            zipOut0.write(zBuf, 0, def0.deflate(zBuf));
        }
        def0.end();
        byte[] zip0 = zipOut0.toByteArray();

        // 2. Revision 1: Delta replacing "Hello\n" with "Hello World\n"
        // Replace "" at end of "Hello\n" (index 6 to 6) with "World\n"
        ByteArrayOutputStream delta1 = new ByteArrayOutputStream();
        ByteBuffer dbuf1 = ByteBuffer.allocate(12);
        dbuf1.putInt(6);
        dbuf1.putInt(6);
        dbuf1.putInt(6);
        delta1.write(dbuf1.array());
        delta1.write("World\n".getBytes(StandardCharsets.UTF_8));
        byte[] dBytes1 = delta1.toByteArray();

        ByteArrayOutputStream zipOut1 = new ByteArrayOutputStream();
        zipOut1.write('x');
        Deflater def1 = new Deflater();
        def1.setInput(dBytes1);
        def1.finish();
        while (!def1.finished()) {
            zipOut1.write(zBuf, 0, def1.deflate(zBuf));
        }
        def1.end();
        byte[] zip1 = zipOut1.toByteArray();

        // 3. Revision 2: Delta replacing "World\n" with "Mercurial\n"
        // In "Hello World\n", "World\n" starts at offset 6 and ends at 12
        ByteArrayOutputStream delta2 = new ByteArrayOutputStream();
        ByteBuffer dbuf2 = ByteBuffer.allocate(12);
        dbuf2.putInt(6);
        dbuf2.putInt(12);
        dbuf2.putInt(10);
        delta2.write(dbuf2.array());
        delta2.write("Mercurial\n".getBytes(StandardCharsets.UTF_8));
        byte[] dBytes2 = delta2.toByteArray();

        ByteArrayOutputStream zipOut2 = new ByteArrayOutputStream();
        zipOut2.write('x');
        Deflater def2 = new Deflater();
        def2.setInput(dBytes2);
        def2.finish();
        while (!def2.finished()) {
            zipOut2.write(zBuf, 0, def2.deflate(zBuf));
        }
        def2.end();
        byte[] zip2 = zipOut2.toByteArray();

        // Let's write as inline
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Rec 0: inline, base = 0
        ByteBuffer rec0 = ByteBuffer.allocate(64);
        rec0.putLong(0x0001000100000000L); // version 1, inline = 1, offset = 0
        rec0.putInt(zip0.length);
        rec0.putInt(content0.length);
        rec0.putInt(0); // base = 0
        rec0.putInt(0);
        rec0.putInt(-1);
        rec0.putInt(-1);
        rec0.put(new byte[32]);
        out.write(rec0.array());
        out.write(zip0);

        // Rec 1: inline, base = 0 (points to 0)
        long offset1 = zip0.length;
        ByteBuffer rec1 = ByteBuffer.allocate(64);
        rec1.putLong((offset1 << 16) | 0);
        rec1.putInt(zip1.length);
        rec1.putInt(dBytes1.length);
        rec1.putInt(0); // base = 0
        rec1.putInt(0);
        rec1.putInt(0);
        rec1.putInt(-1);
        rec1.put(new byte[32]);
        out.write(rec1.array());
        out.write(zip1);

        // Rec 2: inline, base = 1 (points to 1!) -> Multi-level chain (2 -> 1 -> 0)
        long offset2 = offset1 + zip1.length;
        ByteBuffer rec2 = ByteBuffer.allocate(64);
        rec2.putLong((offset2 << 16) | 0);
        rec2.putInt(zip2.length);
        rec2.putInt(dBytes2.length);
        rec2.putInt(1); // base = 1
        rec2.putInt(0);
        rec2.putInt(1);
        rec2.putInt(-1);
        rec2.put(new byte[32]);
        out.write(rec2.array());
        out.write(zip2);

        java.nio.file.Files.write(idxFile.toPath(), out.toByteArray());

        Revlog reader = new Revlog(idxFile, new File("/nonexistent"));
        assertEquals(3, reader.getRevisionCount());

        assertEquals("Hello\n", new String(reader.getRevisionContent(0), StandardCharsets.UTF_8));
        assertEquals("Hello\nWorld\n", new String(reader.getRevisionContent(1), StandardCharsets.UTF_8));
        assertEquals("Hello\nMercurial\n", new String(reader.getRevisionContent(2), StandardCharsets.UTF_8));
    }

    @Test
    public void testCreateAndApplyDelta() throws Exception {
        byte[] base = "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8);
        byte[] newText = "The quick clever brown fox jumps over the active lazy dog".getBytes(StandardCharsets.UTF_8);
        byte[] delta = Revlog.createDelta(base, newText);
        byte[] applied = Revlog.applyDelta(base, delta);
        assertArrayEquals(newText, applied);

        // Edge case: empty texts
        byte[] baseEmpty = new byte[0];
        byte[] newEmpty = new byte[0];
        byte[] deltaEmpty = Revlog.createDelta(baseEmpty, newEmpty);
        byte[] appliedEmpty = Revlog.applyDelta(baseEmpty, deltaEmpty);
        assertArrayEquals(newEmpty, appliedEmpty);

        // Edge case: one empty
        byte[] deltaOne = Revlog.createDelta(base, baseEmpty);
        byte[] appliedOne = Revlog.applyDelta(base, deltaOne);
        assertArrayEquals(baseEmpty, appliedOne);
    }

    @Test
    public void testEncodeFname() {
        assertEquals("data/test", io.github.search5.hg4j.core.NodeIdUtil.encodeFname("test"));
        assertEquals("data/~2etest", io.github.search5.hg4j.core.NodeIdUtil.encodeFname(".test"));
        assertEquals("data/test__with__underscores", io.github.search5.hg4j.core.NodeIdUtil.encodeFname("test_with_underscores"));
        assertEquals("data/au~78", io.github.search5.hg4j.core.NodeIdUtil.encodeFname("aux"));
        assertEquals("data/au~78.i", io.github.search5.hg4j.core.NodeIdUtil.encodeFname("aux.i"));
        assertEquals("data/_c_o_n", io.github.search5.hg4j.core.NodeIdUtil.encodeFname("CON"));
        assertEquals("data/com~35", io.github.search5.hg4j.core.NodeIdUtil.encodeFname("com5"));
        assertEquals("data/test space", io.github.search5.hg4j.core.NodeIdUtil.encodeFname("test space"));
        assertEquals("data/~2ehello world", io.github.search5.hg4j.core.NodeIdUtil.encodeFname(".hello world"));
        
        // Non-ASCII Korean encoding verification
        String encodedKorean = io.github.search5.hg4j.core.NodeIdUtil.encodeFname("안녕");
        assertTrue(encodedKorean.contains("~"));
        assertFalse(encodedKorean.contains("%"));
    }

    @Test
    public void testMyersDeltaCompression() throws Exception {
        byte[] base = "Line 1\nLine 2\nLine 3\nLine 4\nLine 5\n".getBytes(StandardCharsets.UTF_8);
        byte[] target = "Line 1\nLine 2 Modified\nLine 3\nLine 4 Modified\nLine 5\n".getBytes(StandardCharsets.UTF_8);

        byte[] delta = Revlog.createDelta(base, target);
        byte[] applied = Revlog.applyDelta(base, delta);

        assertArrayEquals(target, applied);

        // Since Myers Diff should not redundantly store the unchanged "Line 3\n" in the middle,
        // the delta size must be significantly smaller than that of a single hunk approach.
        // Traditional single hunk: start=7, end=28, length=32 -> 12 + 32 = 44 bytes
        // Myers Diff multi-hunk:
        // Hunk 1: start=7, end=14, length=16 ("Line 2 Modified\n") -> 12 + 16 = 28 bytes
        // Hunk 2: start=21, end=28, length=16 ("Line 4 Modified\n") -> 12 + 16 = 28 bytes
        // Total is about 56 bytes. Although hunk overhead (12 bytes) is considered, 
        // it shows a substantial difference in larger and more complex files.
        // Here, we verify the correct application (LCS and Myers Diff) and error-free restoration.
        
        byte[] complexBase = ("Lorem ipsum dolor sit amet,\n" +
                "consectetur adipiscing elit.\n" +
                "Sed do eiusmod tempor incididunt\n" +
                "ut labore et dolore magna aliqua.\n" +
                "Ut enim ad minim veniam,\n" +
                "quis nostrud exercitation ullamco\n" +
                "laboris nisi ut aliquip ex ea\n" +
                "commodo consequat.").getBytes(StandardCharsets.UTF_8);

        byte[] complexTarget = ("Lorem ipsum dolor sit amet,\n" +
                "consectetur adipiscing elit.\n" +
                "Sed do EXTREME tempor incididunt\n" +
                "ut labore et dolore magna aliqua.\n" +
                "Ut enim ad minim veniam,\n" +
                "quis nostrud EXCEPTIONAL ullamco\n" +
                "laboris nisi ut aliquip ex ea\n" +
                "commodo consequat.").getBytes(StandardCharsets.UTF_8);

        byte[] complexDelta = Revlog.createDelta(complexBase, complexTarget);
        byte[] complexApplied = Revlog.applyDelta(complexBase, complexDelta);
        assertArrayEquals(complexTarget, complexApplied);

        // Verify that the delta byte count is smaller than the single hunk approach because duplicated blocks are preserved.
        // A single hunk delta replaces everything from the start of the change "Sed do..." to "quis nostrud..." entirely,
        // which redundantly includes large unchanged segments like "ut labore et dolore magna aliqua.\nUt enim ad minim veniam,\n".
        int singleHunkLength = Revlog.createSimpleDelta(complexBase, complexTarget).length;
        assertTrue(complexDelta.length < singleHunkLength, 
                "Myers Delta size (" + complexDelta.length + ") should be smaller than simple delta (" + singleHunkLength + ")");
    }

    @Test
    public void testMemoryMappedDataReader(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("mmap.i").toFile();
        File datFile = tempDir.resolve("mmap.d").toFile();

        Revlog revlog = new Revlog(idxFile, datFile);
        byte[] pNode = new byte[20];

        // 1. Verify initial write and Mmap-based read
        byte[] content1 = "First large content chunk for testing mmap performance.\n".repeat(100).getBytes(StandardCharsets.UTF_8);
        byte[] node1 = revlog.appendRevision(content1, -1, -1, pNode, pNode, 0);

        byte[] readContent1 = revlog.getRevisionContent(0);
        assertArrayEquals(content1, readContent1);

        // 2. Verify Mmap cache invalidation and update when an additional write occurs
        byte[] content2 = "Second large content chunk, appended later.\n".repeat(50).getBytes(StandardCharsets.UTF_8);
        revlog.appendRevision(content2, 0, -1, node1, pNode, 1);

        // Even after clearing the previous cache, reloading should automatically remap to the latest size and read successfully
        revlog.clearCache();
        byte[] readContent2 = revlog.getRevisionContent(1);
        assertArrayEquals(content2, readContent2);
    }

    @Test
    public void testDecompressHunkHeuristic() throws Exception {
        byte[] uncompressed = "Hello Heuristic Zlib Check!".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Deflater deflater = new Deflater();
        deflater.setInput(uncompressed);
        deflater.finish();
        byte[] buf = new byte[1024];
        while (!deflater.finished()) {
            int count = deflater.deflate(buf);
            baos.write(buf, 0, count);
        }
        deflater.end();
        byte[] zlibData = baos.toByteArray();

        File idxFile = File.createTempFile("test_decompress", ".i");
        File datFile = File.createTempFile("test_decompress", ".d");
        idxFile.deleteOnExit();
        datFile.deleteOnExit();
        Revlog revlog = new Revlog(idxFile, datFile);

        java.lang.reflect.Method decompressMethod = Revlog.class.getDeclaredMethod("decompressHunk", byte[].class, Revlog.IndexRecord.class);
        decompressMethod.setAccessible(true);

        Revlog.IndexRecord dummyRecord = new Revlog.IndexRecord(0, 0L, 0, zlibData.length, uncompressed.length, 0, 0, -1, -1, new byte[20]);

        byte[] decompressedA = (byte[]) decompressMethod.invoke(revlog, zlibData, dummyRecord);
        assertArrayEquals(uncompressed, decompressedA);

        byte[] zlibWithXPrefix = new byte[zlibData.length + 1];
        zlibWithXPrefix[0] = 'x';
        System.arraycopy(zlibData, 0, zlibWithXPrefix, 1, zlibData.length);
        Revlog.IndexRecord dummyRecordB = new Revlog.IndexRecord(0, 0L, 0, zlibWithXPrefix.length, uncompressed.length, 0, 0, -1, -1, new byte[20]);

        byte[] decompressedB = (byte[]) decompressMethod.invoke(revlog, zlibWithXPrefix, dummyRecordB);
        assertArrayEquals(uncompressed, decompressedB);
    }

    @Test
    public void testRevlogThreadSafetyConcurrentAccess(@TempDir Path tempDir) throws Exception {
        File idx = tempDir.resolve("threadsafety.i").toFile();
        File dat = tempDir.resolve("threadsafety.d").toFile();
        Revlog revlog = new Revlog(idx, dat);

        int threadCount = 8;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger failureCount = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    latch.await();
                    byte[] parent = new byte[20];
                    byte[] content = ("Concurrent Append " + index).getBytes(StandardCharsets.UTF_8);
                    revlog.appendRevision(content, -1, -1, parent, parent, index);
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                }
            });
        }

        latch.countDown();
        executor.shutdown();
        boolean finished = executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

        assertTrue(finished);
        assertEquals(0, failureCount.get(), "Concurrent appends had exceptions: " + failureCount.get());
        assertEquals(threadCount, revlog.getRevisionCount());
    }

    @Test
    public void testAppendChangeGroupEntryWithCopyMetadata(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("copy.i").toFile();
        File datFile = tempDir.resolve("copy.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);

        // 1. Parent revision with copy metadata
        java.util.Map<String, String> metadata = new java.util.HashMap<>();
        metadata.put("copy", "source.txt");
        metadata.put("copyrev", "0000000000000000000000000000000000000000");

        String baseLogical = "Hello World\n";
        byte[] contentBytes = baseLogical.getBytes(StandardCharsets.UTF_8);

        byte[] p1Node = new byte[20];
        byte[] p2Node = new byte[20];
        byte[] parentNode = revlog.appendRevision(contentBytes, metadata, -1, -1, p1Node, p2Node, 0);

        // Verify logical and raw contents
        byte[] loadedLogical = revlog.getRevisionContent(0);
        assertEquals(baseLogical, new String(loadedLogical, StandardCharsets.UTF_8));
        byte[] rawParentContent = revlog.getRawRevisionContent(0);

        // 2. Child revision with copy metadata (appended logical content)
        String childLogical = "Hello World\nModified!\n";
        byte[] childLogicalBytes = childLogical.getBytes(StandardCharsets.UTF_8);

        // Reconstruct expected rawChildContent with identical metadata prefix
        StringBuilder msb = new StringBuilder();
        msb.append('\u0001').append('\n');
        msb.append("copy: source.txt\n");
        msb.append("copyrev: 0000000000000000000000000000000000000000\n");
        msb.append('\u0001').append('\n');
        byte[] metaBytes = msb.toString().getBytes(StandardCharsets.UTF_8);

        byte[] rawChildContent = new byte[metaBytes.length + childLogicalBytes.length];
        System.arraycopy(metaBytes, 0, rawChildContent, 0, metaBytes.length);
        System.arraycopy(childLogicalBytes, 0, rawChildContent, metaBytes.length, childLogicalBytes.length);

        // Create delta between rawParentContent and rawChildContent
        byte[] delta = Revlog.createSimpleDelta(rawParentContent, rawChildContent);

        // Calculate expected node hash: SHA-1(p1_node + p2_node + raw_child_content) where parents are sorted lexicographically
        byte[] first = parentNode;
        byte[] second = p2Node;
        boolean swap = false;
        for (int i = 0; i < 20; i++) {
            int byteA = first[i] & 0xFF;
            int byteB = second[i] & 0xFF;
            if (byteA != byteB) {
                if (byteA > byteB) {
                    swap = true;
                }
                break;
            }
        }
        if (swap) {
            first = p2Node;
            second = parentNode;
        }

        byte[] childNode = new byte[20];
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            md.update(first);
            md.update(second);
            md.update(rawChildContent);
            byte[] digest = md.digest();
            System.arraycopy(digest, 0, childNode, 0, 20);
        } catch (Exception e) {
            fail(e);
        }

        // 3. Build ChangeGroupEntry
        ChangegroupParser.ChangeGroupEntry entry = new ChangegroupParser.ChangeGroupEntry();
        entry.node = childNode;
        entry.p1 = parentNode;
        entry.p2 = p2Node;
        entry.deltabase = parentNode;
        entry.delta = delta;

        // 4. Try appending: this must succeed!
        revlog.appendChangeGroupEntry(entry, 1);

        // Verify that the child revision was successfully written and can be read back
        assertEquals(2, revlog.getRevisionCount());
        byte[] readBackLogical = revlog.getRevisionContent(1);
        assertEquals(childLogical, new String(readBackLogical, StandardCharsets.UTF_8));
        byte[] readBackRaw = revlog.getRawRevisionContent(1);
        assertArrayEquals(rawChildContent, readBackRaw);
    }

    @Test
    public void testAppendRevisionUsesDeltaCompression(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("delta.i").toFile();
        File datFile = tempDir.resolve("delta.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);

        byte[] p1 = new byte[20];
        byte[] p2 = new byte[20];

        // 1. Revision 0
        String line1 = "Alice Line 1\nAlice Line 2\n";
        byte[] parentNode = revlog.appendRevision(line1.getBytes(StandardCharsets.UTF_8), -1, -1, p1, p2, 0);

        // 2. Revision 1 (slight modification, delta is much smaller)
        String line2 = "Alice Line 1\nAlice Line 2\nModified!\n";
        byte[] childNode = revlog.appendRevision(line2.getBytes(StandardCharsets.UTF_8), 0, -1, parentNode, p2, 1);

        // Verify read back succeeds
        assertEquals(2, revlog.getRevisionCount());
        assertEquals(line2, new String(revlog.getRevisionContent(1), StandardCharsets.UTF_8));

        // Get index record of revision 1 and verify baseRev is indeed 0 (written as a delta against revision 0)
        Revlog.IndexRecord rec = revlog.getIndexRecord(1);
        assertEquals(0, rec.getBaseRev());
    }
}



