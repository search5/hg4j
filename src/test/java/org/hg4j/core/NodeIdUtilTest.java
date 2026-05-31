package org.hg4j.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class NodeIdUtilTest {

    @Test
    public void testHexConversions() {
        byte[] original = new byte[]{ 0x00, 0x01, 0x0f, (byte) 0xff };
        String hex = NodeIdUtil.toHex(original);
        assertEquals("00010fff", hex);

        byte[] restored = NodeIdUtil.fromHex(hex);
        assertArrayEquals(original, restored);

        assertEquals("", NodeIdUtil.toHex(null));
        assertArrayEquals(new byte[0], NodeIdUtil.fromHex(null));
        assertArrayEquals(new byte[0], NodeIdUtil.fromHex(""));
    }

    @Test
    public void testIsAllZero() {
        assertTrue(NodeIdUtil.isAllZero(new byte[20]));
        assertTrue(NodeIdUtil.isAllZero(null));
        assertFalse(NodeIdUtil.isAllZero(new byte[]{ 0, 0, 1, 0 }));
    }

    @Test
    public void testEncodeFnameLongPathsAndStandard() {
        // Standard dotencode test
        assertEquals("data/~2efile.txt", NodeIdUtil.encodeFname(".file.txt"));
        assertEquals("data/~20file.txt", NodeIdUtil.encodeFname(" file.txt"));

        // Windows reserved name escape test
        assertEquals("data/au~78", NodeIdUtil.encodeFname("aux"));

        // Hybrid long path test (exceeding 120 bytes but total path <= 255 bytes)
        String dirPath = "a/".repeat(55) + "longdir";
        String fileName = "test.txt";
        String longPath = dirPath + "/" + fileName;
        
        String encoded = NodeIdUtil.encodeFname(longPath);
        assertTrue(encoded.startsWith("dh/"));
        assertTrue(encoded.contains("/test.txt"));

        // Full hash long path test (exceeding 255 bytes)
        String superLongDir = "very/deep/path/to/some/excessively/long/nested/directory/structure/that/goes/on/and/on/".repeat(3);
        String superLongFileName = "extremely-long-filename-that-will-surely-push-the-entire-store-path-limit-beyond-two-hundred-and-fifty-five-characters.txt";
        String superLongPath = superLongDir + "/" + superLongFileName;
        String fullEncoded = NodeIdUtil.encodeFname(superLongPath);
        assertTrue(fullEncoded.startsWith("dh/"));
        assertFalse(fullEncoded.substring(3).contains("/"));
        assertTrue(fullEncoded.endsWith("characters.txt"));
    }

    @Test
    public void testEncodeFnameWithExtensionsAndStorePrefix() {
        // Verify prevention of duplicate data/ prefix addition when it already has a store prefix
        String metaPath = "meta/testdir/00manifest.i";
        String encodedMeta = NodeIdUtil.encodeFname(metaPath);
        assertTrue(encodedMeta.startsWith("meta/"));
        assertFalse(encodedMeta.startsWith("data/meta/"));

        // Verify hybrid encoding for long paths with extensions
        String dirPath = "a/".repeat(55) + "longdir";
        String fileName = "test.txt.i";
        String longPath = dirPath + "/" + fileName;

        String encoded = NodeIdUtil.encodeFname(longPath);
        assertTrue(encoded.startsWith("dh/"));
        assertTrue(encoded.endsWith("/test.txt.i"));
    }
}
