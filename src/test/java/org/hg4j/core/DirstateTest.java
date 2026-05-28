package org.hg4j.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
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
        HgRepository repository = new HgRepository(repoDir);
        
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

    @Test
    public void testDirstateV2Detection() throws IOException {
        Dirstate dirstate = new Dirstate();
        byte[] v2Bytes = new byte[40];
        System.arraycopy("# dirstate-v2\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, v2Bytes, 0, 13);
        dirstate.read(v2Bytes);
        assertTrue(dirstate.isV2());
        
        byte[] v2AltBytes = new byte[40];
        System.arraycopy("dirstate2".getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, v2AltBytes, 0, 9);
        Dirstate dirstateAlt = new Dirstate();
        dirstateAlt.read(v2AltBytes);
        assertTrue(dirstateAlt.isV2());
    }

    @Test
    public void testDirstateV2DynamicRebuild(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repository = new HgRepository(repoDir);
        repository.getHgDir().mkdirs();
        repository.getStoreDir().mkdirs();
        
        // Write dirstate file with v2 magic
        File dirstateFile = new File(repository.getHgDir(), "dirstate");
        byte[] v2Header = new byte[40];
        System.arraycopy("# dirstate-v2\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, v2Header, 0, 13);
        Files.write(dirstateFile.toPath(), v2Header);
        
        // When reading, as there is no changelog, it should reconstruct with zero parents
        Dirstate d = repository.getDirstate();
        assertFalse(d.isV2()); // rebuilt to v1
        assertArrayEquals(new byte[20], d.getParent1());
        assertArrayEquals(new byte[20], d.getParent2());
    }

    @Test
    public void testDirstateV2Integration() throws Exception {
        Dirstate original = new Dirstate();
        original.setV2(true);
        assertTrue(original.isV2());

        byte[] p1 = new byte[20];
        byte[] p2 = new byte[20];
        p1[0] = 0x1F;
        p2[0] = 0x2E;
        original.setParents(p1, p2);

        original.addEntry("file1.txt", new Dirstate.Entry('n', 0644, 50, 1000L));
        original.addEntry("dir/file2.txt", new Dirstate.Entry('a', 0755, 120, 2000L));

        // 1. Serialize using V2 Serializer via Facade
        byte[] v2Bytes = original.serialize();
        assertNotNull(v2Bytes);

        // 2. Deserialize using V2 Parser via Facade
        Dirstate decoded = new Dirstate();
        decoded.read(v2Bytes);

        assertTrue(decoded.isV2());
        assertArrayEquals(p1, decoded.getParent1());
        assertArrayEquals(p2, decoded.getParent2());
        assertEquals(2, decoded.getEntries().size());

        Dirstate.Entry e1 = decoded.getEntries().get("file1.txt");
        assertNotNull(e1);
        assertEquals('n', e1.getState());
        assertEquals(0644, e1.getMode());
        assertEquals(50, e1.getSize());
        assertEquals(1000L, e1.getTime());

        // 3. Exception coverages
        assertThrows(IOException.class, () -> decoded.read((byte[]) null));
        assertThrows(IOException.class, () -> decoded.read(new byte[5])); // Too short
    }
}
