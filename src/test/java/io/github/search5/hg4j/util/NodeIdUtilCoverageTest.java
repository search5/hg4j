package io.github.search5.hg4j.util;

import io.github.search5.hg4j.storage.Revlog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targeted coverage tests for {@link NodeIdUtil}, filling gaps left by
 * {@link NodeIdUtilTest}: short-hash resolution/ambiguity, node-id hashing edge
 * cases, and store-path encoding branches (Windows-reserved names, trailing
 * dot/space, control/high bytes, and the hashed-fallback scheme's dir-truncation
 * and filler/space-left arithmetic).
 *
 * <p>Every {@code encodeFname} expectation below was cross-checked against real
 * hg 7.2's own implementation by invoking {@code mercurial.store._pathencode}
 * directly (Python 3, dist-packages mercurial), e.g.:
 * <pre>
 * python3 -c "from mercurial import store; print(store._pathencode(b'data/aux.txt'))"
 * </pre>
 * so none of the expected strings here are guessed.</p>
 */
public class NodeIdUtilCoverageTest {

    // ---------------------------------------------------------------
    // fromHex: invalid character in either nibble position
    // ---------------------------------------------------------------

    @Test
    public void testFromHexInvalidHighNibbleThrows() {
        // 'g' in the even (high-nibble) position is not a valid hex digit.
        assertThrows(IllegalArgumentException.class, () -> NodeIdUtil.fromHex("g0"));
    }

    @Test
    public void testFromHexInvalidLowNibbleThrows() {
        // 'g' in the odd (low-nibble) position is not a valid hex digit.
        assertThrows(IllegalArgumentException.class, () -> NodeIdUtil.fromHex("0g"));
    }

    // ---------------------------------------------------------------
    // findRevisionByNodeId
    // ---------------------------------------------------------------

    @Test
    public void testFindRevisionByNodeIdNullArgs() {
        assertEquals(-1, NodeIdUtil.findRevisionByNodeId(null, new byte[20]));
        assertEquals(-1, NodeIdUtil.findRevisionByNodeId(null, null));
    }

    @Test
    public void testFindRevisionByNodeIdDelegatesToRevlog(@TempDir Path tempDir) throws IOException {
        File idxFile = tempDir.resolve("find.i").toFile();
        File datFile = tempDir.resolve("find.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);

        byte[] nullParent = new byte[20];
        byte[] content0 = "rev0\n".getBytes(StandardCharsets.UTF_8);
        byte[] node0 = revlog.appendRevision(content0, -1, -1, nullParent, nullParent, 0);

        // null nodeId short-circuits to -1 even with a real, non-null revlog.
        assertEquals(-1, NodeIdUtil.findRevisionByNodeId(revlog, null));

        // Existing node resolves to its revision index.
        assertEquals(0, NodeIdUtil.findRevisionByNodeId(revlog, node0));

        // Unknown node resolves to -1 via the real Revlog#findRevision delegation.
        byte[] unknown = new byte[20];
        unknown[0] = (byte) 0xAB;
        assertEquals(-1, NodeIdUtil.findRevisionByNodeId(revlog, unknown));
    }

    // ---------------------------------------------------------------
    // encodeFname: reserved/control/high bytes (isReservedStoreByte, encodeFnameBytes)
    // ---------------------------------------------------------------

    @Test
    public void testEncodeFnameWindowsSpecialChar() {
        // Verified: python3 -c "from mercurial import store;
        // print(store._pathencode(b'data/file:name.txt'))" -> b'data/file~3aname.txt'
        assertEquals("data/file~3aname.txt", NodeIdUtil.encodeFname("file:name.txt"));
    }

    @Test
    public void testEncodeFnameControlByte() {
        // Verified against real hg: store._pathencode(b'data/a\\x01b.txt') -> b'data/a~01b.txt'
        assertEquals("data/a~01b.txt", NodeIdUtil.encodeFname("ab.txt"));
    }

    @Test
    public void testEncodeFnameHighByte() {
        // Verified against real hg: store._pathencode(b'data/a\\x7fb.txt') -> b'data/a~7fb.txt'
        assertEquals("data/a~7fb.txt", NodeIdUtil.encodeFname("ab.txt"));
    }

    @Test
    public void testEncodeFnameMultiByteUtf8IsEscapedPerByte() {
        // 'é' is UTF-8 0xC3 0xA9; real hg escapes each byte independently since the
        // encoder operates byte-wise, not char-wise.
        // Verified: store._pathencode(b'data/caf\\xc3\\xa9.txt') -> b'data/caf~c3~a9.txt'
        assertEquals("data/caf~c3~a9.txt", NodeIdUtil.encodeFname("café.txt"));
    }

    // ---------------------------------------------------------------
    // encodeFname: Windows-reserved device names + trailing dot/space (auxEncode)
    // ---------------------------------------------------------------

    @Test
    public void testEncodeFnameWindowsReserved3LetterNames() {
        // Straight from mercurial/store.py's own _auxencode doctest (verified again
        // directly against the installed hg 7.2):
        //   s = '.foo/aux.txt/txt.aux/con/prn/nul/foo.'
        //   _auxencode(s.split('/'), True)
        //   -> ['~2efoo', 'au~78.txt', 'txt.aux', 'co~6e', 'pr~6e', 'nu~6c', 'foo~2e']
        String relPath = ".foo/aux.txt/txt.aux/con/prn/nul/foo.";
        String expected = "data/~2efoo/au~78.txt/txt.aux/co~6e/pr~6e/nu~6c/foo~2e";
        assertEquals(expected, NodeIdUtil.encodeFname(relPath));
    }

    @Test
    public void testEncodeFnameWindowsReserved4LetterNames() {
        // Verified: store._pathencode(b'data/com1/lpt9/com0/abc1/file.txt')
        // -> b'data/co~6d1/lp~749/com0/abc1/file.txt'
        // com0 is NOT masked (trailing digit must be 1-9, not 0) and abc1 is not
        // masked either (prefix is neither com nor lpt).
        String relPath = "com1/lpt9/com0/abc1/file.txt";
        String expected = "data/co~6d1/lp~749/com0/abc1/file.txt";
        assertEquals(expected, NodeIdUtil.encodeFname(relPath));
    }

    @Test
    public void testEncodeFnameTrailingSpaceIsEscaped() {
        // Straight from mercurial/store.py's own _auxencode doctest:
        //   _auxencode([b'foo. '], True) -> ['foo.~20']
        // Only the final space is escaped; the dot right before it is left alone,
        // since the reserved-name check only looks at the basename before the
        // first dot ("foo", not reserved).
        // Verified: store._pathencode(b'data/foo. ') -> b'data/foo.~20'
        assertEquals("data/foo.~20", NodeIdUtil.encodeFname("foo. "));
    }

    // ---------------------------------------------------------------
    // encodeFname: hashed fallback (hashEncode) — dirs-empty, no-extension,
    // dir-truncation trailing dot/space, spaceLeft <= 0, and basename-fits-whole.
    // ---------------------------------------------------------------

    @Test
    public void testEncodeFnameHashedFallbackRootLevelNoExtensionNoDirs() {
        // Single very long path component with no '/' and no '.': exercises the
        // "dirs is empty" branch (no directories to prepend) and the "basename has
        // no extension" branch (dotIdx == -1) of hashEncode at the same time.
        String longName = "x".repeat(200);
        // Verified: store._pathencode(b'data/' + b'x'*200) ->
        // b'dh/' + 'x'*77 + sha1('data/'+'x'*200 as bytes).hexdigest()
        String expected = "dh/" + "x".repeat(77) + "013adb113d6776c436dc1a93e34b6dbcf424e31f";
        assertEquals(expected, NodeIdUtil.encodeFname(longName));
    }

    @Test
    public void testEncodeFnameHashedFallbackDirTruncationTrailingDot() {
        // Directory component "abcdefg." + filler, truncated to its first 8 chars
        // is "abcdefg." (ends in a dot) which real hg (and hashEncode) must then
        // repair to "abcdefg_" since Windows can't have a directory ending in '.'.
        String dirName = "abcdefg." + "z".repeat(30);
        String fileName = "y".repeat(150) + ".txt";
        String longPath = dirName + "/" + fileName;
        // Verified: store._pathencode(b'data/' + dirName + b'/' + fileName)
        String expected = "dh/abcdefg_/" + "y".repeat(64) + "901f1026b0a45e557f892cc3b5a6db5ee827dc0e.txt";
        assertEquals(expected, NodeIdUtil.encodeFname(longPath));
    }

    @Test
    public void testEncodeFnameHashedFallbackDirTruncationTrailingSpace() {
        // Same as the trailing-dot case above, but the character landing at
        // (truncated) position 8 is a space instead of a dot -- exercises the
        // other half of the "d ends in '.' or ' '" OR condition in hashEncode's
        // sdirs loop.
        String dirName = "abcdefg " + "z".repeat(30);
        String fileName = "y".repeat(150) + ".txt";
        String longPath = dirName + "/" + fileName;
        // Verified: store._pathencode(b'data/' + dirName + b'/' + fileName)
        String expected = "dh/abcdefg_/" + "y".repeat(64) + "28d0c1166a2351ae7e755eb765a4757a2cb9ba45.txt";
        assertEquals(expected, NodeIdUtil.encodeFname(longPath));
    }

    @Test
    public void testEncodeFnameHashedFallbackReservedByteInDirIsEscaped() {
        // A reserved byte (':') inside a directory component that goes through the
        // hashed fallback exercises lowerEncodeBytes' own reserved-byte escaping
        // (distinct from encodeFnameBytes' — lowerEncodeBytes lowercases instead of
        // using the "_x" uppercase marker, and is only reachable via hashEncode).
        // The colon is escaped to "~3a" *before* the 8-char directory truncation,
        // so "ab:cdefg..." becomes "ab~3acde" once cut to 8 characters.
        String dirName = "ab:cdefg" + "z".repeat(30);
        String fileName = "y".repeat(150) + ".txt";
        String longPath = dirName + "/" + fileName;
        // Verified: store._pathencode(b'data/' + dirName + b'/' + fileName)
        String expected = "dh/ab~3acde/" + "y".repeat(64) + "574b87707cbf59496caabe7fd26d829923e553ac.txt";
        assertEquals(expected, NodeIdUtil.encodeFname(longPath));
    }

    @Test
    public void testEncodeFnameHashedFallbackUppercaseLetterInDirIsLowered() {
        // lowerEncodeBytes (used only by the hashed fallback) lowercases uppercase
        // ASCII letters outright -- unlike encodeFnameBytes' reversible "_x"
        // marker used by the default (non-hashed) encoding.
        String dirName = "AbCdefg" + "z".repeat(30);
        String fileName = "y".repeat(150) + ".txt";
        String longPath = dirName + "/" + fileName;
        // Verified: store._pathencode(b'data/' + dirName + b'/' + fileName)
        String expected = "dh/abcdefgz/" + "y".repeat(64) + "527a0e00134dd5cef9fae891a2a40ec113c589d4.txt";
        assertEquals(expected, NodeIdUtil.encodeFname(longPath));
    }

    @Test
    public void testEncodeFnameHashedFallbackEmptyDirSegment() {
        // A leading "//" produces two empty path components; hashEncode's sdirs
        // loop must treat an empty directory component as-is (skipping the
        // trailing dot/space repair, which would otherwise index out of bounds on
        // an empty string).
        String longPath = "//" + "y".repeat(150) + ".txt";
        // Verified: store._pathencode(b'data/' + longPath)
        String expected = "dh///" + "y".repeat(71) + "f9166900884f31f4c8e1e9ba0236c5b167abeacc.txt";
        assertEquals(expected, NodeIdUtil.encodeFname(longPath));
    }

    @Test
    public void testEncodeFnameHashedFallbackBasenameFitsWithoutTruncation() {
        // Many short (already-8-char) directory components push the default
        // encoding over 120 bytes, but the short basename "f.txt" comfortably fits
        // in the remaining space left after "dh/" + dirs + digest -- exercising the
        // basename.length() <= spaceLeft side of the filler ternary.
        StringBuilder dirs = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            if (i > 0) dirs.append('/');
            dirs.append(String.format("dirseg%02d", i));
        }
        String longPath = dirs + "/f.txt";
        // Verified: store._pathencode(b'data/' + longPath)
        String expected = "dh/dirseg00/dirseg01/dirseg02/dirseg03/dirseg04/dirseg05/dirseg06/"
                + "f.txt28ceaf01e4246be50caed348c4b28dd1c0663b80.txt";
        assertEquals(expected, NodeIdUtil.encodeFname(longPath));
    }

    @Test
    public void testEncodeFnameHashedFallbackSpaceLeftExhausted() {
        // An enormous extension alone pushes "dh/" + dirs + digest + ext past 120
        // bytes, leaving no room at all for a basename filler -- exercising the
        // spaceLeft <= 0 branch (filler is skipped entirely).
        StringBuilder dirs = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            if (i > 0) dirs.append('/');
            dirs.append(String.format("dirseg%02d", i));
        }
        String longExt = "e".repeat(100);
        String longPath = dirs + "/f." + longExt;
        // Verified: store._pathencode(b'data/' + longPath)
        String expected = "dh/dirseg00/dirseg01/dirseg02/dirseg03/dirseg04/dirseg05/dirseg06/"
                + "bd676f6e0922a3ab370a68d96fc03fb1219d6a53." + longExt;
        String actual = NodeIdUtil.encodeFname(longPath);
        assertEquals(expected, actual);
        // The basename filler ("f") must be entirely absent: nothing is inserted
        // between the digest and the (untruncated) extension.
        assertFalse(actual.contains("53f."));
    }

    // ---------------------------------------------------------------
    // computeNodeId: null-parent handling and parent ordering
    // ---------------------------------------------------------------

    @Test
    public void testComputeNodeIdNullP1TreatedAsAllZero() {
        byte[] content = "hello\n".getBytes(StandardCharsets.UTF_8);
        byte[] p2 = new byte[20];
        p2[0] = 0x42;
        byte[] withNull = NodeIdUtil.computeNodeId(content, null, p2);
        byte[] withZero = NodeIdUtil.computeNodeId(content, new byte[20], p2);
        assertArrayEquals(withZero, withNull);
    }

    @Test
    public void testComputeNodeIdNullP2TreatedAsAllZero() {
        byte[] content = "hello\n".getBytes(StandardCharsets.UTF_8);
        byte[] p1 = new byte[20];
        p1[0] = 0x42;
        byte[] withNull = NodeIdUtil.computeNodeId(content, p1, null);
        byte[] withZero = NodeIdUtil.computeNodeId(content, p1, new byte[20]);
        assertArrayEquals(withZero, withNull);
    }

    @Test
    public void testComputeNodeIdIsOrderIndependentOnParents() {
        // Real hg (storageutil.hashrevisionsha1) always hashes the lexicographically
        // smaller parent first, regardless of which one is passed as p1 vs p2 --
        // so swapping the two parents at the call site must not change the result.
        byte[] content = "merge content\n".getBytes(StandardCharsets.UTF_8);
        byte[] small = new byte[20];
        small[0] = 0x01;
        byte[] large = new byte[20];
        large[0] = (byte) 0xFF;

        byte[] a = NodeIdUtil.computeNodeId(content, small, large);
        byte[] b = NodeIdUtil.computeNodeId(content, large, small);
        assertArrayEquals(a, b);
    }

    // ---------------------------------------------------------------
    // resolveRevision: tip/empty/null, integer index, hex-prefix resolution and
    // ambiguity, and out-of-range numeric fallback to hex lookup.
    // ---------------------------------------------------------------

    private Revlog buildChangelogWithRevisions(Path tempDir, int count) throws IOException {
        File idxFile = tempDir.resolve("resolve.i").toFile();
        File datFile = tempDir.resolve("resolve.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);
        byte[] nullParent = new byte[20];
        byte[] prevNode = nullParent;
        for (int i = 0; i < count; i++) {
            byte[] content = ("revision body " + i + "\n").getBytes(StandardCharsets.UTF_8);
            int parent1 = i == 0 ? -1 : i - 1;
            prevNode = revlog.appendRevision(content, parent1, -1, prevNode, nullParent, i);
        }
        return revlog;
    }

    @Test
    public void testResolveRevisionTipVariants(@TempDir Path tempDir) throws IOException {
        Revlog revlog = buildChangelogWithRevisions(tempDir, 3);
        byte[] expectedTip = revlog.getIndexRecord(2).getNodeId();

        assertArrayEquals(expectedTip, NodeIdUtil.resolveRevision(revlog, null));
        assertArrayEquals(expectedTip, NodeIdUtil.resolveRevision(revlog, ""));
        assertArrayEquals(expectedTip, NodeIdUtil.resolveRevision(revlog, "tip"));
        assertArrayEquals(expectedTip, NodeIdUtil.resolveRevision(revlog, "TIP"));
    }

    @Test
    public void testResolveRevisionTipOnEmptyChangelogReturnsNull(@TempDir Path tempDir) throws IOException {
        File idxFile = tempDir.resolve("empty.i").toFile();
        File datFile = tempDir.resolve("empty.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);
        assertNull(NodeIdUtil.resolveRevision(revlog, null));
        assertNull(NodeIdUtil.resolveRevision(revlog, "tip"));
    }

    @Test
    public void testResolveRevisionByIntegerIndex(@TempDir Path tempDir) throws IOException {
        Revlog revlog = buildChangelogWithRevisions(tempDir, 3);
        assertArrayEquals(revlog.getIndexRecord(0).getNodeId(), NodeIdUtil.resolveRevision(revlog, "0"));
        assertArrayEquals(revlog.getIndexRecord(2).getNodeId(), NodeIdUtil.resolveRevision(revlog, "2"));
    }

    @Test
    public void testResolveRevisionIntegerOutOfRangeFallsThroughToHexLookup(@TempDir Path tempDir) throws IOException {
        Revlog revlog = buildChangelogWithRevisions(tempDir, 3);
        // "999" parses fine as an int but is out of range, and it is also not a
        // valid/matching hex prefix of any node -> falls through to the hex-prefix
        // branch and comes back empty (no match), not an exception.
        assertNull(NodeIdUtil.resolveRevision(revlog, "999"));
        // Negative index: same story.
        assertNull(NodeIdUtil.resolveRevision(revlog, "-1"));
    }

    @Test
    public void testResolveRevisionByFullHexNodeId(@TempDir Path tempDir) throws IOException {
        Revlog revlog = buildChangelogWithRevisions(tempDir, 5);
        byte[] node = revlog.getIndexRecord(3).getNodeId();
        String fullHex = NodeIdUtil.toHex(node);
        assertArrayEquals(node, NodeIdUtil.resolveRevision(revlog, fullHex));
    }

    @Test
    public void testResolveRevisionUnmatchedHexPrefixReturnsNull(@TempDir Path tempDir) throws IOException {
        Revlog revlog = buildChangelogWithRevisions(tempDir, 5);
        // Ten leading 'f's: with only 5 revisions, virtually certain not to match
        // any real SHA-1-derived node id, and "ffffffffff" is not parseable as an
        // int either, so this must go through the hex-prefix branch and come back
        // empty.
        assertNull(NodeIdUtil.resolveRevision(revlog, "ffffffffff"));
    }

    @Test
    public void testResolveRevisionAmbiguousHexPrefixThrows(@TempDir Path tempDir) throws IOException {
        // Generate enough revisions that, by pigeonhole over the 16 possible
        // leading hex nibbles, at least one non-digit nibble ('a'-'f') is shared by
        // two or more real node ids computed by the Revlog itself (no hash value is
        // hand-picked or guessed here).
        Revlog revlog = buildChangelogWithRevisions(tempDir, 120);
        int count = revlog.getRevisionCount();

        List<String> hexes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            hexes.add(NodeIdUtil.toHex(revlog.getIndexRecord(i).getNodeId()));
        }

        Character ambiguousPrefix = null;
        for (char c = 'a'; c <= 'f'; c++) {
            int matches = 0;
            for (String hex : hexes) {
                if (hex.charAt(0) == c) {
                    matches++;
                }
            }
            if (matches > 1) {
                ambiguousPrefix = c;
                break;
            }
        }
        assertNotNull(ambiguousPrefix,
                "Expected at least one letter nibble ('a'-'f') shared by >1 of " + count + " generated node ids");

        String prefix = String.valueOf(ambiguousPrefix);
        IOException ex = assertThrows(IOException.class, () -> NodeIdUtil.resolveRevision(revlog, prefix));
        assertTrue(ex.getMessage().contains("Ambiguous"));
    }
}
