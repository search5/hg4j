package com.github.search5.hg4j.dirstate;
import com.github.search5.hg4j.lib.HgRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DirstateTest {

    @Test
    public void testEmptyDirstate() {
        Dirstate dirstate = new Dirstate();
        byte[] expectedParent = new byte[20];
        
        assertArrayEquals(expectedParent, dirstate.getParent1());
        assertArrayEquals(expectedParent, dirstate.getParent2());
        assertTrue(dirstate.getEntries().isEmpty());
    }

    @Test
    public void testSetParents() {
        Dirstate dirstate = new Dirstate();
        byte[] p1 = new byte[20];
        byte[] p2 = new byte[20];
        p1[0] = 0x12;
        p2[0] = 0x34;

        dirstate.setParents(p1, p2);
        assertArrayEquals(p1, dirstate.getParent1());
        assertArrayEquals(p2, dirstate.getParent2());

        // Test invalid parent lengths
        assertThrows(IllegalArgumentException.class, () -> dirstate.setParents(new byte[19], p2));
        assertThrows(IllegalArgumentException.class, () -> dirstate.setParents(p1, new byte[21]));
    }

    @Test
    public void testDirstateNodeIdParentMethods() {
        Dirstate dirstate = new Dirstate();
        com.github.search5.hg4j.lib.NodeId n1 = com.github.search5.hg4j.lib.NodeId.fromHex("abcdef0123456789abcdef0123456789abcdef01");
        com.github.search5.hg4j.lib.NodeId n2 = com.github.search5.hg4j.lib.NodeId.fromHex("1234567890123456789012345678901234567890");

        dirstate.setParents(n1, n2);
        assertEquals(n1, dirstate.getParent1Node());
        assertEquals(n2, dirstate.getParent2Node());
        assertArrayEquals(n1.getBytes(), dirstate.getParent1());
        assertArrayEquals(n2.getBytes(), dirstate.getParent2());
    }

    @Test
    public void testAddAndGetEntries() {
        Dirstate dirstate = new Dirstate();
        Dirstate.Entry entry = new Dirstate.Entry('n', 0644, 1500, 162222222);
        
        dirstate.addEntry("src/Main.java", entry);
        assertEquals(1, dirstate.getEntries().size());
        
        Dirstate.Entry retrieved = dirstate.getEntries().get("src/Main.java");
        assertNotNull(retrieved);
        assertEquals('n', retrieved.getState());
        assertEquals(0644, retrieved.getMode());
        assertEquals(1500, retrieved.getSize());
        assertEquals(162222222, retrieved.getTime());

        // Test removing entry
        dirstate.removeEntry("src/Main.java");
        assertTrue(dirstate.getEntries().isEmpty());
    }

    @Test
    public void testSerializeAndDeserialize() throws Exception {
        Dirstate dirstate = new Dirstate();
        byte[] p1 = new byte[20];
        byte[] p2 = new byte[20];
        Arrays.fill(p1, (byte) 0xAA);
        Arrays.fill(p2, (byte) 0xBB);
        dirstate.setParents(p1, p2);

        Dirstate.Entry e1 = new Dirstate.Entry('n', 0644, 50, 100);
        Dirstate.Entry e2 = new Dirstate.Entry('a', 0755, 120, 200);
        dirstate.addEntry("file1.txt", e1);
        dirstate.addEntry("dir/file2.sh", e2);

        byte[] serialized = dirstate.serialize();
        
        // Header (40 bytes) + e1 (1 + 4 + 4 + 4 + 4 + 9 = 26 bytes) + e2 (1 + 4 + 4 + 4 + 4 + 12 = 29 bytes) = 95 bytes
        assertEquals(95, serialized.length);

        Dirstate parsed = new Dirstate();
        parsed.read(serialized);

        assertArrayEquals(p1, parsed.getParent1());
        assertArrayEquals(p2, parsed.getParent2());
        
        Map<String, Dirstate.Entry> entries = parsed.getEntries();
        assertEquals(2, entries.size());

        Dirstate.Entry parsedE1 = entries.get("file1.txt");
        assertNotNull(parsedE1);
        assertEquals('n', parsedE1.getState());
        assertEquals(0644, parsedE1.getMode());
        assertEquals(50, parsedE1.getSize());
        assertEquals(100, parsedE1.getTime());

        Dirstate.Entry parsedE2 = entries.get("dir/file2.sh");
        assertNotNull(parsedE2);
        assertEquals('a', parsedE2.getState());
        assertEquals(0755, parsedE2.getMode());
        assertEquals(120, parsedE2.getSize());
        assertEquals(200, parsedE2.getTime());
    }

    @Test
    public void testReadWriteFile(@TempDir Path tempDir) throws IOException {
        File dirstateFile = tempDir.resolve("dirstate").toFile();
        Dirstate dirstate = new Dirstate();
        dirstate.addEntry("test.txt", new Dirstate.Entry('n', 0644, 10, 10));
        
        dirstate.write(dirstateFile);
        assertTrue(dirstateFile.exists());

        Dirstate parsed = new Dirstate();
        parsed.read(dirstateFile);
        assertEquals(1, parsed.getEntries().size());
        assertNotNull(parsed.getEntries().get("test.txt"));
    }

    @Test
    public void testReadInvalidBytes() {
        Dirstate dirstate = new Dirstate();
        // Too short header
        assertThrows(IOException.class, () -> dirstate.read(new byte[39]));
        
        // Header ok, but truncated entry
        byte[] truncated = new byte[45]; // header + 5 bytes
        assertThrows(IOException.class, () -> dirstate.read(truncated));
    }

    @Test
    public void testReadInvalidBytesMore() {
        Dirstate dirstate = new Dirstate();
        
        // 1. null bytes check
        assertThrows(IOException.class, () -> dirstate.read((byte[]) null));
        
        // 2. pathLen is truncated
        byte[] headerOkButTruncatedPath = new byte[40 + 17]; // Header 40 + entry 17 (but path is missing)
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(headerOkButTruncatedPath);
        buf.position(40);
        buf.put((byte) 'n'); // state
        buf.putInt(0); // mode
        buf.putInt(0); // size
        buf.putInt(0); // time
        buf.putInt(10); // pathLen is 10, but we have 0 remaining
        assertThrows(IOException.class, () -> dirstate.read(headerOkButTruncatedPath));
    }

    @Test
    public void testWriteNullFile() {
        Dirstate dirstate = new Dirstate();
        assertThrows(IllegalArgumentException.class, () -> dirstate.write(null));
    }

    @Test
    public void testHgIgnoreAndScanning(@TempDir Path tempDir) throws IOException {
        File repoDir = tempDir.toFile();
        try (HgRepository repository = new HgRepository(repoDir)) {
            // Create files
            File f1 = new File(repoDir, "keep.txt");
            Files.writeString(f1.toPath(), "content");
            
            File f2 = new File(repoDir, "ignore_me.tmp");
            Files.writeString(f2.toPath(), "content");
            
            File subDir = new File(repoDir, "build");
            subDir.mkdirs();
            File f3 = new File(subDir, "output.class");
            Files.writeString(f3.toPath(), "class content");
            
            File nestedDir = new File(repoDir, "src/nested");
            nestedDir.mkdirs();
            File nestedTmp = new File(nestedDir, "sub_temp.tmp");
            Files.writeString(nestedTmp.toPath(), "nested temp");
            
            // 1. Without .hgignore - all should be found
            java.util.List<String> files1 = repository.scanWorkingCopy();
            assertTrue(files1.contains("keep.txt"));
            assertTrue(files1.contains("ignore_me.tmp"));
            assertTrue(files1.contains("build/output.class"));
            assertTrue(files1.contains("src/nested/sub_temp.tmp"));
            
            // 2. Create .hgignore
            File ignoreFile = new File(repoDir, ".hgignore");
            Files.writeString(ignoreFile.toPath(), "syntax: glob\n*.tmp\nbuild/\n");
            
            // Reload ignore patterns (it will load automatically on next isIgnored / scan)
            java.util.List<String> files2 = repository.scanWorkingCopy();
            assertTrue(files2.contains("keep.txt"));
            assertFalse(files2.contains("ignore_me.tmp"));
            assertFalse(files2.contains("build/output.class"));
            assertFalse(files2.contains("src/nested/sub_temp.tmp")); // verified subdirectory match!
        }
    }

    @Test
    public void testDirstateV2Detection(@TempDir Path tempDir) throws IOException {
        File hgDir = tempDir.resolve(".hg").toFile();
        hgDir.mkdirs();
        File dirstateFile = new File(hgDir, "dirstate");

        // Write V2 data file
        File dataFile = new File(hgDir, "dirstate.d.123456");
        Files.write(dataFile.toPath(), new byte[0]);

        // Write V2 Docket file (Strict 122+ bytes)
        byte[] v2Magic = "dirstate-v2\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] uidBytes = "123456".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int docketSize = 12 + 32 + 32 + 44 + 4 + 1 + uidBytes.length;
        ByteBuffer buf = ByteBuffer.allocate(docketSize).order(ByteOrder.BIG_ENDIAN);
        buf.put(v2Magic);
        buf.put(new byte[32]); // p1
        buf.put(new byte[32]); // p2
        buf.put(new byte[44]); // tree metadata
        buf.putInt(0); // dataLength = 0
        buf.put((byte) uidBytes.length);
        buf.put(uidBytes);
        Files.write(dirstateFile.toPath(), buf.array());

        Dirstate dirstate = new Dirstate();
        dirstate.read(dirstateFile);
        assertTrue(dirstate.isV2());
    }

    @Test
    public void testDirstateV2DynamicRebuild(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repository = new HgRepository(repoDir)) {
            repository.getHgDir().mkdirs();
            repository.getStoreDir().mkdirs();
            
            File dirstateFile = new File(repository.getHgDir(), "dirstate");
            
            // Write V2 data file
            File dataFile = new File(repository.getHgDir(), "dirstate.d.123456");
            Files.write(dataFile.toPath(), new byte[0]);

            // Write V2 Docket file (Strict 122+ bytes)
            byte[] v2Magic = "dirstate-v2\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            byte[] uidBytes = "123456".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            int docketSize = 12 + 32 + 32 + 44 + 4 + 1 + uidBytes.length;
            ByteBuffer buf = ByteBuffer.allocate(docketSize).order(ByteOrder.BIG_ENDIAN);
            buf.put(v2Magic);
            buf.put(new byte[32]); // p1
            buf.put(new byte[32]); // p2
            buf.put(new byte[44]); // tree metadata
            buf.putInt(0); // dataLength = 0
            buf.put((byte) uidBytes.length);
            buf.put(uidBytes);
            Files.write(dirstateFile.toPath(), buf.array());
            
            // When reading, as there is no changelog, it should reconstruct with zero parents
            Dirstate d = repository.getDirstate();
            assertTrue(d.isV2()); // rebuilt to v2 (format preserved)
            assertArrayEquals(new byte[20], d.getParent1());
            assertArrayEquals(new byte[20], d.getParent2());
        }
    }

    @Test
    public void testDirstateV2Integration(@TempDir Path tempDir) throws Exception {
        File dirstateFile = tempDir.resolve("dirstate").toFile();

        Dirstate original = new Dirstate();
        original.setV2(true);
        assertTrue(original.isV2());

        byte[] p1 = new byte[20];
        byte[] p2 = new byte[20];
        p1[0] = 0x1F;
        p2[0] = 0x2E;
        original.setParents(p1, p2);

        original.addEntry("file1.txt", new Dirstate.Entry('n', 0100644, 50, 1000L));
        original.addEntry("dir/file2.txt", new Dirstate.Entry('a', 0100755, 120, 2000L));

        // 1. Write using Dirstate.write which splits docket and data file
        original.write(dirstateFile);
        assertTrue(dirstateFile.exists());

        // 2. Deserialize using Dirstate.read which loads both docket and datafile
        Dirstate decoded = new Dirstate();
        decoded.read(dirstateFile);

        assertTrue(decoded.isV2());
        assertArrayEquals(p1, decoded.getParent1());
        assertArrayEquals(p2, decoded.getParent2());
        assertEquals(2, decoded.getEntries().size());

        Dirstate.Entry e1 = decoded.getEntries().get("file1.txt");
        assertNotNull(e1);
        assertEquals('n', e1.getState());
        assertEquals(0100644, e1.getMode());
        assertEquals(50, e1.getSize());
        assertEquals(1000L, e1.getTime());

        // 3. Exception coverages
        assertThrows(IOException.class, () -> decoded.read((File) null));
    }

    @Test
    public void testDirstateV2ReadDataFileNotFound(@TempDir Path tempDir) throws IOException {
        File dirstateFile = tempDir.resolve("dirstate").toFile();
        byte[] v2Magic = "dirstate-v2\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] uidBytes = "missing_uid".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int docketSize = 12 + 32 + 32 + 44 + 4 + 1 + uidBytes.length;
        ByteBuffer buf = ByteBuffer.allocate(docketSize).order(ByteOrder.BIG_ENDIAN);
        buf.put(v2Magic);
        buf.put(new byte[32]); // p1
        buf.put(new byte[32]); // p2
        buf.put(new byte[44]); // metadata
        buf.putInt(100); // dataLength = 100
        buf.put((byte) uidBytes.length);
        buf.put(uidBytes);
        Files.write(dirstateFile.toPath(), buf.array());

        Dirstate dirstate = new Dirstate();
        assertThrows(IOException.class, () -> dirstate.read(dirstateFile));
    }

    @Test
    public void testDirstateV2ReadLengthMismatch(@TempDir Path tempDir) throws IOException {
        File dirstateFile = tempDir.resolve("dirstate").toFile();
        File dataFile = tempDir.resolve("dirstate.d.bad_len").toFile();
        Files.write(dataFile.toPath(), new byte[50]); // actual len is 50

        byte[] v2Magic = "dirstate-v2\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] uidBytes = "bad_len".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int docketSize = 12 + 32 + 32 + 44 + 4 + 1 + uidBytes.length;
        ByteBuffer buf = ByteBuffer.allocate(docketSize).order(ByteOrder.BIG_ENDIAN);
        buf.put(v2Magic);
        buf.put(new byte[32]); // p1
        buf.put(new byte[32]); // p2
        buf.put(new byte[44]); // metadata
        buf.putInt(100); // dataLength = 100 (but actual is 50)
        buf.put((byte) uidBytes.length);
        buf.put(uidBytes);
        Files.write(dirstateFile.toPath(), buf.array());

        Dirstate dirstate = new Dirstate();
        assertThrows(IOException.class, () -> dirstate.read(dirstateFile));
    }

    @Test
    public void testDirstateV2WriteOldUidCleanup(@TempDir Path tempDir) throws IOException {
        File dirstateFile = tempDir.resolve("dirstate").toFile();

        // 1. First V2 write (will create first uid datafile)
        Dirstate d1 = new Dirstate();
        d1.setV2(true);
        d1.addEntry("file1.txt", new Dirstate.Entry('n', 0644, 10, 1000L));
        d1.write(dirstateFile);

        // Verify first uid file exists
        byte[] docketBytes = Files.readAllBytes(dirstateFile.toPath());
        ByteBuffer docketBuf = ByteBuffer.wrap(docketBytes).order(ByteOrder.BIG_ENDIAN);
        int uidSize = docketBuf.get(124) & 0xFF;
        byte[] uidBytes = new byte[uidSize];
        docketBuf.position(125);
        docketBuf.get(uidBytes);
        String oldUid = new String(uidBytes, java.nio.charset.StandardCharsets.US_ASCII);
        File oldDataFile = new File(dirstateFile.getParentFile(), "dirstate.d." + oldUid);
        assertTrue(oldDataFile.exists());

        // 2. Second V2 write (will create second uid datafile and clean up the old one)
        Dirstate d2 = new Dirstate();
        d2.setV2(true);
        d2.addEntry("file1.txt", new Dirstate.Entry('n', 0644, 10, 2000L)); // modified
        d2.write(dirstateFile);

        // Verify new uid file exists and old uid file is cleaned up!
        byte[] docketBytes2 = Files.readAllBytes(dirstateFile.toPath());
        ByteBuffer docketBuf2 = ByteBuffer.wrap(docketBytes2).order(ByteOrder.BIG_ENDIAN);
        int uidSize2 = docketBuf2.get(124) & 0xFF;
        byte[] uidBytes2 = new byte[uidSize2];
        docketBuf2.position(125);
        docketBuf2.get(uidBytes2);
        String newUid = new String(uidBytes2, java.nio.charset.StandardCharsets.US_ASCII);
        File newDataFile = new File(dirstateFile.getParentFile(), "dirstate.d." + newUid);

        assertNotEquals(oldUid, newUid);
        assertTrue(newDataFile.exists());
        assertFalse(oldDataFile.exists()); // CLEANED UP!
    }

    @Test
    public void testDirstateV2WriteWithCorruptedOldDocket(@TempDir Path tempDir) throws IOException {
        File dirstateFile = tempDir.resolve("dirstate").toFile();
        // Write invalid corrupted bytes to docket
        Files.write(dirstateFile.toPath(), new byte[]{1, 2, 3, 4});

        Dirstate d = new Dirstate();
        d.setV2(true);
        d.addEntry("file1.txt", new Dirstate.Entry('n', 0644, 10, 1000L));
        // Should write atomic without crashing, ignoring corrupted docket
        assertDoesNotThrow(() -> d.write(dirstateFile));
    }
}
