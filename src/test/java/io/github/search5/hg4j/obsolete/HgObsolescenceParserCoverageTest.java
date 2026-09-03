package io.github.search5.hg4j.obsolete;

import io.github.search5.hg4j.errors.HgCorruptDataException;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage-focused tests for {@link HgObsolescenceParser}: obsstore FM1 branches not already
 * exercised by {@link HgObsolescenceTest} (synthetic error paths) or
 * {@link HgObsolescenceRealHgInteropTest} (the plain amend happy path).
 *
 * <p>Two fixtures under {@code src/test/resources/fixtures/obsstore-fm1-parents/} were generated
 * with the real {@code hg} CLI (7.2) via
 * {@code hg --config experimental.evolution.createmarkers=true debugobsolete --record-parents},
 * which is how real hg records a prune marker's parent nodes (see
 * {@code mercurial/obsolete.py}'s {@code createmarkers()}: when a marker has no successors,
 * {@code npare = tuple(p.node() for p in prec.parents())}). Their exact byte layout was cross
 * checked against {@code mercurial/obsolete.py}'s {@code _fm1purereadmarkers} (README.md in that
 * directory records the decoded fields).</p>
 */
public class HgObsolescenceParserCoverageTest {

    private static final String SINGLE_PARENT_FIXTURE = "/fixtures/obsstore-fm1-parents/obsstore-single-parent";
    private static final String MERGE_PARENTS_FIXTURE = "/fixtures/obsstore-fm1-parents/obsstore-merge-parents";

    private static byte[] readFixture(String resourcePath) throws IOException {
        try (InputStream in = HgObsolescenceParserCoverageTest.class.getResourceAsStream(resourcePath)) {
            assertNotNull(in, "missing test fixture: " + resourcePath);
            return in.readAllBytes();
        }
    }

    /**
     * Builds a single FM1 record (version byte + one marker) with full control over node size,
     * flags, and an explicit (possibly recorded) parent list — mirroring
     * {@code mercurial/obsolete.py}'s {@code _fm1fixed = '>IdhHBBB'} fixed header followed by
     * predecessor, successors, parents (only when {@code numpar != 3}), the metadata length
     * table, then the raw metadata bytes.
     */
    private static byte[] buildFm1Record(int flags, byte[] predecessor, List<byte[]> successors,
                                          List<byte[]> parents, Map<String, String> metadata) {
        int numsuc = successors.size();
        int numpar = parents.isEmpty() ? 3 : parents.size();
        int nodeSize = predecessor.length;

        int fixedSize = 19;
        int nodesSection = nodeSize * (1 + numsuc + (numpar == 3 ? 0 : numpar));
        int metaPairsSection = 2 * metadata.size();
        int metaBytesLen = 0;
        for (Map.Entry<String, String> e : metadata.entrySet()) {
            metaBytesLen += e.getKey().getBytes(StandardCharsets.UTF_8).length
                    + e.getValue().getBytes(StandardCharsets.UTF_8).length;
        }
        int totalSize = fixedSize + nodesSection + metaPairsSection + metaBytesLen;

        ByteBuffer buf = ByteBuffer.allocate(1 + totalSize).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 1); // FM1 version byte (file-level, not part of the record itself)
        buf.putInt(totalSize);
        buf.putDouble(1_700_000_000.0);
        buf.putShort((short) 0); // tz minutes
        buf.putShort((short) flags);
        buf.put((byte) numsuc);
        buf.put((byte) numpar);
        buf.put((byte) metadata.size());
        buf.put(predecessor);
        for (byte[] s : successors) {
            buf.put(s);
        }
        if (numpar != 3) {
            for (byte[] p : parents) {
                buf.put(p);
            }
        }
        for (Map.Entry<String, String> e : metadata.entrySet()) {
            buf.put((byte) e.getKey().getBytes(StandardCharsets.UTF_8).length);
            buf.put((byte) e.getValue().getBytes(StandardCharsets.UTF_8).length);
        }
        for (Map.Entry<String, String> e : metadata.entrySet()) {
            buf.put(e.getKey().getBytes(StandardCharsets.UTF_8));
            buf.put(e.getValue().getBytes(StandardCharsets.UTF_8));
        }
        return buf.array();
    }

    private static byte[] fixedNode(int size, int firstByte) {
        byte[] node = new byte[size];
        node[0] = (byte) firstByte;
        node[size - 1] = (byte) (firstByte + 1);
        return node;
    }

    // -----------------------------------------------------------------
    // <init>: implicit no-arg constructor of this final utility class.
    // -----------------------------------------------------------------

    @Test
    public void testUtilityClassIsInstantiable() {
        // HgObsolescenceParser declares no explicit constructor, so javac emits a plain public
        // default one; exercising it is not a "guess" about behavior (there is none) but it does
        // close off the only otherwise-dead instruction range reported by JaCoCo.
        assertNotNull(new HgObsolescenceParser());
    }

    // -----------------------------------------------------------------
    // usingsha256 flag / 32-byte node size (mercurial/obsutil.py: usingsha256 = 2, i.e. 1<<1 —
    // NOT 1<<2). Verified against /usr/lib/python3/dist-packages/mercurial/obsutil.py.
    // -----------------------------------------------------------------

    @Test
    public void testUsingSha256FlagSelects32ByteNodeSize() throws IOException {
        byte[] predecessor32 = fixedNode(32, 0x01);
        byte[] successor32 = fixedNode(32, 0x50);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("operation", "amend");

        // flags = 2 == mercurial.obsutil.usingsha256 (real hg constant, confirmed from source).
        byte[] raw = buildFm1Record(2, predecessor32, List.of(successor32), List.of(), metadata);

        List<HgObsMarker> markers = HgObsolescenceParser.parse(raw);
        assertEquals(1, markers.size());
        HgObsMarker marker = markers.get(0);
        assertEquals(2, marker.getFlags());

        // hg4j's HgObsMarker model only carries 20-byte (sha1) node identifiers; for a sha256
        // record the parser keeps the leading 20 bytes of the 32-byte node so callers at least
        // get a stable, non-garbled prefix rather than misaligned/truncated node bytes.
        byte[] expectedPredecessorPrefix = new byte[20];
        System.arraycopy(predecessor32, 0, expectedPredecessorPrefix, 0, 20);
        byte[] expectedSuccessorPrefix = new byte[20];
        System.arraycopy(successor32, 0, expectedSuccessorPrefix, 0, 20);

        assertArrayEquals(expectedPredecessorPrefix, marker.getPredecessor());
        assertEquals(1, marker.getSuccessors().size());
        assertArrayEquals(expectedSuccessorPrefix, marker.getSuccessors().get(0));
        assertEquals("amend", marker.getMetadata().get("operation"));
    }

    @Test
    public void testFlagsWithoutSha256BitStillUses20ByteNodes() throws IOException {
        // bumpedfix (mercurial.obsutil.bumpedfix = 1) must NOT be confused with usingsha256 (2):
        // flags=1 alone still means 20-byte sha1 nodes.
        byte[] predecessor20 = fixedNode(20, 0x11);
        byte[] successor20 = fixedNode(20, 0x22);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("k", "v");

        byte[] raw = buildFm1Record(1, predecessor20, List.of(successor20), List.of(), metadata);

        List<HgObsMarker> markers = HgObsolescenceParser.parse(raw);
        assertEquals(1, markers.size());
        assertEquals(1, markers.get(0).getFlags());
        assertArrayEquals(predecessor20, markers.get(0).getPredecessor());
        assertArrayEquals(successor20, markers.get(0).getSuccessors().get(0));
    }

    // -----------------------------------------------------------------
    // numpar != _fm1parentnone(3): recorded-parent (prune-style) markers, real hg fixtures.
    // -----------------------------------------------------------------

    @Test
    public void testRealHgPruneMarkerWithSingleRecordedParentIsSkippedCorrectly() throws IOException {
        byte[] raw = readFixture(SINGLE_PARENT_FIXTURE);
        List<HgObsMarker> markers = HgObsolescenceParser.parse(raw);

        assertEquals(1, markers.size());
        HgObsMarker marker = markers.get(0);
        assertEquals("5cf8ae3e0524261c722dc44fd837cc8d9ebf9b5d", NodeIdUtil.toHex(marker.getPredecessor()));
        assertTrue(marker.getSuccessors().isEmpty(), "prune marker has no successors");
        assertEquals(0, marker.getFlags());
        assertEquals("jiho@jiho-asus", marker.getMetadata().get("user"));
        assertNull(marker.getMetadata().get("operation"));
    }

    @Test
    public void testRealHgPruneMarkerWithTwoRecordedParentsIsSkippedCorrectly() throws IOException {
        byte[] raw = readFixture(MERGE_PARENTS_FIXTURE);
        List<HgObsMarker> markers = HgObsolescenceParser.parse(raw);

        assertEquals(1, markers.size());
        HgObsMarker marker = markers.get(0);
        assertEquals("e24d603786b1ab4c567a47625d2e0e1c6d435187", NodeIdUtil.toHex(marker.getPredecessor()));
        assertTrue(marker.getSuccessors().isEmpty(), "prune marker has no successors");
        assertEquals(0, marker.getFlags());
        assertEquals("jiho@jiho-asus", marker.getMetadata().get("user"));
    }

    @Test
    public void testSyntheticRecordedTwoParentsAreSkippedNotTreatedAsSuccessors() throws IOException {
        byte[] predecessor = fixedNode(20, 0x30);
        byte[] parent1 = fixedNode(20, 0x40);
        byte[] parent2 = fixedNode(20, 0x41);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("user", "hg4j");

        byte[] raw = buildFm1Record(0, predecessor, List.of(), List.of(parent1, parent2), metadata);

        List<HgObsMarker> markers = HgObsolescenceParser.parse(raw);
        assertEquals(1, markers.size());
        HgObsMarker marker = markers.get(0);
        assertArrayEquals(predecessor, marker.getPredecessor());
        assertTrue(marker.getSuccessors().isEmpty(), "recorded parents must not surface as successors");
    }

    // -----------------------------------------------------------------
    // Declared totalsize vs. actually-consumed byte integrity check (hg4j-internal defensive
    // check; real hg's own reader does not use the field for bounds-checking, so this branch has
    // no real-hg fixture — it is exercised with a deliberately corrupted, otherwise-valid record).
    // -----------------------------------------------------------------

    @Test
    public void testDeclaredTotalSizeMismatchThrowsCorruptData() {
        byte[] predecessor = fixedNode(20, 0x05);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("op", "amend");
        byte[] raw = buildFm1Record(0, predecessor, List.of(), List.of(), metadata);

        // Corrupt the 4-byte big-endian totalsize field (immediately after the version byte) so
        // it no longer matches the number of bytes the parser actually consumes for this record.
        raw[1] += 1;

        HgCorruptDataException ex = assertThrows(HgCorruptDataException.class,
                () -> HgObsolescenceParser.parse(raw));
        assertTrue(ex.getMessage().contains("size mismatch"), "unexpected message: " + ex.getMessage());
    }

    // -----------------------------------------------------------------
    // Non-HgCorruptDataException failures mid-record (e.g. BufferUnderflowException) must be
    // wrapped, not propagated raw.
    // -----------------------------------------------------------------

    @Test
    public void testBufferUnderflowMidRecordIsWrappedAsCorruptData() {
        // Fixed header declares numsuc=1 (one successor expected) but the buffer is truncated
        // right after the predecessor node, before any successor bytes are present.
        ByteBuffer buf = ByteBuffer.allocate(1 + 19 + 20).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 1); // version
        buf.putInt(999); // totalsize (never reached: failure happens before the check)
        buf.putDouble(0.0);
        buf.putShort((short) 0);
        buf.putShort((short) 0); // flags
        buf.put((byte) 1); // numsuc = 1, but no successor bytes follow
        buf.put((byte) 3); // numpar = none
        buf.put((byte) 0); // nummeta
        buf.put(fixedNode(20, 0x09)); // predecessor only — buffer ends here
        byte[] raw = buf.array();

        HgCorruptDataException ex = assertThrows(HgCorruptDataException.class,
                () -> HgObsolescenceParser.parse(raw));
        assertEquals("Failed to parse obsstore binary content", ex.getMessage());
        assertInstanceOf(BufferUnderflowException.class, ex.getCause());
    }
}
