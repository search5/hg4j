package io.github.search5.hg4j.util;

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

        // Hybrid long path test (exceeding 120 bytes) — 실제 hg(mercurial.store._pathencode)로
        // 직접 검증한 정확한 출력 (2026-09-01): 디렉터리마다 앞 8글자만 남기고, 파일명은
        // 남는 공간만큼만 채운 뒤 전체 경로의 sha1과 원래 확장자를 붙인다.
        String dirPath = "a/".repeat(55) + "longdir";
        String fileName = "test.txt";
        String longPath = dirPath + "/" + fileName;

        String encoded = NodeIdUtil.encodeFname(longPath);
        assertEquals("dh/" + "a/".repeat(34) + "test.1780dcef6d48df828d07692ca10d1abe5e7c0f7f.txt", encoded);

        // 실제 hg는 255바이트를 넘어도 별도의 "디렉터리 없는 전체 해시" 형식으로 전환하지
        // 않는다 — 동일한 dh/<잘린 디렉터리들>/<필러><sha1><확장자> 스킴을 그대로 쓴다.
        String superLongDir = "very/deep/path/to/some/excessively/long/nested/directory/structure/that/goes/on/and/on/".repeat(3);
        String superLongFileName = "extremely-long-filename-that-will-surely-push-the-entire-store-path-limit-beyond-two-hundred-and-fifty-five-characters.txt";
        String superLongPath = superLongDir + "/" + superLongFileName;
        String fullEncoded = NodeIdUtil.encodeFname(superLongPath);
        assertEquals("dh/very/deep/path/to/some/excessiv/long/nested/director/structur/that/"
                + "extrem7aa0569738a4b24f3a34b2b6ea73acb81f0565cd.txt", fullEncoded);
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
        assertEquals("dh/" + "a/".repeat(34) + "test.tx4956939b3b4bd71a7714b78ed49b4b1c6e666f2e.i", encoded);
    }
}
