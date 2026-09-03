package com.github.search5.hg4j.dirstate;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import com.github.search5.hg4j.errors.HgCorruptDataException;

/**
 * Targeted coverage tests for {@link DirstateV2Parser}, filling in branches that
 * {@link DirstateV2ParserTest} and the real-fixture tests do not exercise:
 * <ul>
 *     <li>the {@code parse(byte[], int, int)} overload called directly with {@code null} bytes</li>
 *     <li>the path/data-block overflow check reached via a direct 3-arg call (as opposed to via
 *     the legacy 2-arg overload, which -- for malformed input -- actually fails its own node-count
 *     layout detection first and throws a different exception before ever reaching this check)</li>
 *     <li>the children-segment overflow check</li>
 *     <li>copy-source parsing: both the happy path (in-range copy source recorded into the copy
 *     map, verified round-trip through the real {@link DirstateV2Serializer}) and a corrupt/
 *     out-of-range copy source, which real Mercurial's own pure-Python v2 parser
 *     ({@code mercurial/dirstateutils/v2.py}'s {@code parse_nodes}) also does not hard-fail on --
 *     it only ever guards on {@code if copy_source_start:} and then slices, and Python slicing of
 *     an out-of-range range silently yields truncated/empty bytes rather than raising. Skipping the
 *     copy entry (instead of throwing) on an out-of-range copy source matches that same leniency</li>
 *     <li>the legacy "relative path offset" node-count detection fallback (old, pre-fix hg4j
 *     format; never produced by real hg, kept only for backward compatibility with old hg4j data)</li>
 * </ul>
 */
public class DirstateV2ParserCoverageTest {

    @Test
    public void testParseThreeArgOverload_withNullBytes_returnsEmptyDirstate() throws Exception {
        DirstateV2Parser parser = new DirstateV2Parser();

        Dirstate decoded = parser.parse(null, 5, 3);

        assertNotNull(decoded);
        assertTrue(decoded.isV2());
        assertTrue(decoded.getEntries().isEmpty());
    }

    @Test
    public void testParseThreeArgOverload_pathOverflow_throwsHgCorruptDataException() {
        // A single 44-byte node struct with no data block appended at all: path_start/path_len
        // point past the end of the buffer, so reading the path must be rejected rather than
        // read out of bounds.
        byte[] bytes = new byte[DirstateV2Node.NODE_SIZE];
        DirstateV2Node node = new DirstateV2Node(bytes, 0);
        node.setPathOffset(40);
        node.setPathLen((short) 10); // 40 + 10 = 50 > 44 (buffer capacity)

        DirstateV2Parser parser = new DirstateV2Parser();
        assertThrows(HgCorruptDataException.class, () -> parser.parse(bytes, 0, 1));
    }

    @Test
    public void testParseThreeArgOverload_childrenOverflow_throwsHgCorruptDataException() {
        // A single root node claiming 5 children starting right after itself, but the buffer
        // only actually contains the root node -- children_start + children_count * NODE_SIZE
        // overflows the buffer and must be rejected.
        byte[] bytes = new byte[DirstateV2Node.NODE_SIZE];
        DirstateV2Node node = new DirstateV2Node(bytes, 0);
        node.setPathOffset(0);
        node.setPathLen((short) 0);
        node.setChildrenStart(DirstateV2Node.NODE_SIZE);
        node.setChildrenCount(5); // 44 + 5*44 = 264 > 44 (buffer capacity)

        DirstateV2Parser parser = new DirstateV2Parser();
        assertThrows(HgCorruptDataException.class, () -> parser.parse(bytes, 0, 1));
    }

    @Test
    public void testParseNodeWithCopySource_roundTripsThroughRealSerializer() throws Exception {
        // Real hg records a copy source whenever `hg add`/`hg mv` establishes provenance for a
        // new path (e.g. `hg cp a.txt b.txt` followed by `hg add b.txt`). Build such a Dirstate
        // via the actual serializer (not hand-crafted bytes) so this exercises the real on-disk
        // layout the parser must decode.
        Dirstate original = new Dirstate();
        original.addEntry("b.txt", new Dirstate.Entry('a', 0100644, 128, 1690000000L));
        original.addCopy("b.txt", "a.txt");

        byte[] v2Bytes = DirstateV2Serializer.serialize(original);

        DirstateV2Parser parser = new DirstateV2Parser();
        Dirstate decoded = parser.parse(v2Bytes);

        assertTrue(decoded.getEntries().containsKey("b.txt"));
        assertEquals('a', decoded.getEntries().get("b.txt").getState());
        assertEquals("a.txt", decoded.getCopyMap().get("b.txt"), "Copy source must round-trip");
    }

    @Test
    public void testParseNodeWithOutOfRangeCopySource_isSkippedWithoutException() throws Exception {
        // Hand-craft a single valid, in-range path node but with a corrupt copy_source_start
        // that points far past the end of the buffer. Real hg's own pure-Python v2 parser
        // (mercurial/dirstateutils/v2.py: `if copy_source_start: copy_map[path] =
        // slice_with_len(data, copy_source_start, copy_source_len)`) never bounds-checks this
        // field either -- Python slicing an out-of-range span just yields fewer/zero bytes
        // rather than raising -- so a lenient "skip the copy, keep the entry" response here (as
        // opposed to hard-failing the whole node) is consistent with that real behavior.
        byte[] path = "a.txt".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[DirstateV2Node.NODE_SIZE + path.length];
        System.arraycopy(path, 0, bytes, DirstateV2Node.NODE_SIZE, path.length);

        DirstateV2Node node = new DirstateV2Node(bytes, 0);
        node.setState('a');
        node.setPathOffset(DirstateV2Node.NODE_SIZE);
        node.setPathLen((short) path.length);
        node.setCopySourceOffset(1000); // far out of range
        node.setCopySourceLen((short) 5);

        DirstateV2Parser parser = new DirstateV2Parser();
        Dirstate decoded = parser.parse(bytes, 0, 1);

        assertEquals(1, decoded.getEntries().size());
        assertTrue(decoded.getEntries().containsKey("a.txt"));
        assertEquals('a', decoded.getEntries().get("a.txt").getState());
        assertTrue(decoded.getCopyMap().isEmpty(), "Out-of-range copy source must be skipped, not fabricated");
    }

    @Test
    public void testParseLegacyTwoArgOverload_allZeroSingleNodeBuffer_usesRelativeFallbackDetection() throws Exception {
        // A single all-zero 44-byte NODE struct (path_start=0, path_len=0, no data block
        // appended). The primary "absolute offset" layout probe fails (0 + 0 != 44), so this
        // must fall through to the legacy "relative offset" probe (old, pre-fix hg4j format,
        // documented in DirstateV2Parser#parse(byte[]) as kept only for backward compatibility --
        // real hg never produces this layout): data_offset (1*44) + path_start(0) + path_len(0)
        // == 44 == bytes.length, so it resolves node_count=1 and parses successfully into an
        // intermediate-directory placeholder node (state '\0') with no entries.
        byte[] bytes = new byte[DirstateV2Node.NODE_SIZE];

        DirstateV2Parser parser = new DirstateV2Parser();
        Dirstate decoded = parser.parse(bytes);

        assertNotNull(decoded);
        assertTrue(decoded.isV2());
        assertTrue(decoded.getEntries().isEmpty());
    }

    @Test
    public void testParseLegacyTwoArgOverload_singleNodeWithCopySource_usesRelativeFallbackDetectionCopySourceBranch() throws Exception {
        // Same legacy "relative offset" fallback as the all-zero test above, but with
        // copySourceLen > 0 so the fallback loop's own copy-source ternary
        // (`copySourceLen > 0 ? copySourceOffset+copySourceLen : pathOffset+pathLen`) takes its
        // true branch, which the all-zero case above never exercises (copySourceLen there is 0).
        //
        // node struct: pathOffset=0/pathLen=0 (no path bytes), copySourceOffset=0/copySourceLen=5.
        // Primary "absolute offset" probe (n=1): expectedEnd = copySourceOffset+copySourceLen = 5,
        // which != bytes.length (49), so it fails and falls through as intended.
        // Fallback "relative offset" probe (n=1): dataOffset=44; expectedEnd =
        // 44 + copySourceOffset(0) + copySourceLen(5) = 49 == bytes.length -- resolves nodeCount=1.
        //
        // copySourceOffset=0 is absolute (the actual node-walk always reads copy_source_start as
        // an absolute buffer position, never relative to a data block -- see
        // DirstateV2Parser#parse(byte[],int,int)'s own comment), so the "copy source" bytes it
        // reads back are simply the node struct's own leading zero bytes (pathOffset's 4 zero
        // bytes + pathLen's first zero byte) decoded as 5 NUL characters. That is nonsense as
        // *content*, but harmless (UTF-8 decoding never throws on it) -- this test only needs to
        // prove the fallback detection's copy-source branch is taken and the resulting parse
        // completes without exception, not that the recovered bytes are meaningful.
        byte[] bytes = new byte[49];

        DirstateV2Node node = new DirstateV2Node(bytes, 0);
        node.setState('a');
        node.setPathOffset(0);
        node.setPathLen((short) 0);
        node.setCopySourceOffset(0);
        node.setCopySourceLen((short) 5);

        DirstateV2Parser parser = new DirstateV2Parser();
        Dirstate decoded = parser.parse(bytes);

        assertNotNull(decoded);
        assertTrue(decoded.isV2());
        assertEquals(1, decoded.getEntries().size());
        assertTrue(decoded.getEntries().containsKey(""));
        assertEquals(1, decoded.getCopyMap().size(), "Copy-source branch of the fallback detection must have been exercised");
    }
}
