package com.github.search5.hg4j.storage;

import com.github.search5.hg4j.bundle.ChangegroupParser;
import com.github.search5.hg4j.errors.HgCensoredContentException;
import com.github.search5.hg4j.errors.HgCorruptDataException;
import com.github.search5.hg4j.errors.HgRevisionNotFoundException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage for {@link Revlog}, focused on branches not already exercised by
 * RevlogTest/RevlogV2ParserTest/CensorRealHgInteropTest/CensorChangegroupTransferTest: delta-chain
 * cycle/length guards, censorRevision's inline vs non-inline rewrite paths, appendChangeGroupEntry's
 * cg1/cg2/cg3 deltabase handling and censored-parent/censored-content special cases, the raw
 * revlog-v2 (non-changelog) write guard, and the inline-append paths of appendRevision /
 * appendOptimizedRevision / appendRawRevision.
 */
public class RevlogCoverageTest {

    private static byte[] zlib(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('x');
        Deflater def = new Deflater();
        def.setInput(data);
        def.finish();
        byte[] buf = new byte[256];
        while (!def.finished()) {
            out.write(buf, 0, def.deflate(buf));
        }
        def.end();
        return out.toByteArray();
    }

    /** Mirrors Revlog's own compareBytes() so tests can pre-compute the same node hash it does. */
    private static int compareBytesLikeRevlog(byte[] a, byte[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int x = a[i] & 0xFF;
            int y = b[i] & 0xFF;
            if (x != y) return x - y;
        }
        return a.length - b.length;
    }

    private static byte[] nodeHash(byte[] p1, byte[] p2, byte[] content) throws Exception {
        byte[] first = p1, second = p2;
        if (compareBytesLikeRevlog(p1, p2) > 0) {
            first = p2;
            second = p1;
        }
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        md.update(first);
        md.update(second);
        md.update(content);
        return Arrays.copyOf(md.digest(), 20);
    }

    /** Builds a minimal on-disk inline revlog (a single fulltext revision 0) for tests that need
     * {@code Revlog}'s {@code inline} field to actually be {@code true} -- which only happens when
     * loading a pre-existing inline-format file, since hg4j itself never creates new inline revlogs. */
    private static File buildInlineSingleRevision(Path tempDir, String name, byte[] content0) throws IOException {
        File idxFile = tempDir.resolve(name).toFile();
        byte[] zip0 = zlib(content0);
        ByteBuffer rec0 = ByteBuffer.allocate(64);
        rec0.putLong(0x0001000100000000L); // version 1, inline=1, offset=0
        rec0.putInt(zip0.length);
        rec0.putInt(content0.length);
        rec0.putInt(0); // baseRev
        rec0.putInt(0); // linkRev
        rec0.putInt(-1);
        rec0.putInt(-1);
        rec0.put(new byte[32]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(rec0.array());
        out.write(zip0);
        Files.write(idxFile.toPath(), out.toByteArray());
        return idxFile;
    }

    /** Builds a minimal synthetic generic revlog-v2 (NOT changelog-v2) docket: real magic/header
     * layout (verified against RevlogV2ParserTest's real-hg fixtures) but a synthetic empty
     * companion .idx, since no real-hg-producible fixture exists for generic revlog-v2 in this
     * environment (see decisions/revlog-v2-support-plan.md) and none is needed here -- this test
     * only needs construction to succeed so the write-guard in appendRevisionV2 can be exercised. */
    private static File buildGenericRevlogV2Docket(Path tempDir, String baseName) throws IOException {
        File idxFile = tempDir.resolve(baseName + ".i").toFile();
        String indexUuid = "aaaa", dataUuid = "bbbb", sidedataUuid = "cccc";
        ByteBuffer header = ByteBuffer.allocate(59);
        header.putInt(0x0000DEAD); // MAGIC_REVLOGV2 (generic, not changelog-v2)
        header.put((byte) indexUuid.length());
        header.put((byte) 0);
        header.put((byte) dataUuid.length());
        header.put((byte) 0);
        header.put((byte) sidedataUuid.length());
        header.put((byte) 0);
        header.putLong(0);
        header.putLong(0);
        header.putLong(0);
        header.putLong(0);
        header.putLong(0);
        header.putLong(0);
        header.put((byte) 0);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header.array());
        out.write(indexUuid.getBytes(StandardCharsets.US_ASCII));
        out.write(dataUuid.getBytes(StandardCharsets.US_ASCII));
        out.write(sidedataUuid.getBytes(StandardCharsets.US_ASCII));
        Files.write(idxFile.toPath(), out.toByteArray());

        File companionIndex = tempDir.resolve(baseName + "-" + indexUuid + ".idx").toFile();
        Files.write(companionIndex.toPath(), new byte[0]);

        return idxFile;
    }

    private static Revlog buildLongDeltaChain(File idxFile, File datFile, int length) throws Exception {
        Revlog revlog = new Revlog(idxFile, datFile);
        byte[] zero = new byte[20];
        StringBuilder sb = new StringBuilder("base line\n");
        byte[] content = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] prevNode = revlog.appendRevision(content, -1, -1, zero, zero, 0);
        for (int i = 1; i < length; i++) {
            sb.append("line ").append(i).append("\n");
            content = sb.toString().getBytes(StandardCharsets.UTF_8);
            prevNode = revlog.appendRevision(content, i - 1, -1, prevNode, zero, i);
        }
        return revlog;
    }

    // ---------------------------------------------------------------------
    // getRawRevisionContent / getRevisionContent input validation and cycle detection
    // ---------------------------------------------------------------------

    @Test
    public void testOutOfRangeRevisionsAreRejected(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("range.i").toFile();
        File datFile = tempDir.resolve("range.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);
        assertNotNull(revlog.getIndex());
        byte[] p = new byte[20];
        revlog.appendRevision("only revision".getBytes(StandardCharsets.UTF_8), -1, -1, p, p, 0);

        assertThrows(HgRevisionNotFoundException.class, () -> revlog.getRawRevisionContent(-2));
        assertThrows(HgRevisionNotFoundException.class, () -> revlog.getRawRevisionContent(1));
        assertThrows(HgRevisionNotFoundException.class, () -> revlog.getRevisionContent(-2));
        assertThrows(HgRevisionNotFoundException.class, () -> revlog.getRevisionContent(1));
        assertEquals(0, revlog.getRawRevisionContent(-1).length);
        assertEquals(0, revlog.getRevisionContent(-1).length);
    }

    @Test
    public void testDeltaChainCycleIsDetectedAndReportedAsCorruption(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("cycle.i").toFile();
        File datFile = tempDir.resolve("cycle.d").toFile();

        // Two records whose baseRev pointers form a 2-cycle (0 -> 1 -> 0) without either ever
        // being self-referential or -1 -- getRawRevisionContent()'s chain walk must detect this
        // as corruption rather than looping forever.
        ByteBuffer rec0 = ByteBuffer.allocate(64);
        rec0.putLong(0x0000000100000000L); // version 1, non-inline
        rec0.putInt(0); // compLen
        rec0.putInt(0); // uncompLen
        rec0.putInt(1); // baseRev = 1 (points forward!)
        rec0.putInt(0);
        rec0.putInt(-1);
        rec0.putInt(-1);
        rec0.put(new byte[32]);

        ByteBuffer rec1 = ByteBuffer.allocate(64);
        rec1.putLong(0L);
        rec1.putInt(0);
        rec1.putInt(0);
        rec1.putInt(0); // baseRev = 0 (points back!)
        rec1.putInt(0);
        rec1.putInt(0);
        rec1.putInt(-1);
        rec1.put(new byte[32]);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(rec0.array());
        out.write(rec1.array());
        Files.write(idxFile.toPath(), out.toByteArray());

        Revlog revlog = new Revlog(idxFile, datFile);
        assertEquals(2, revlog.getRevisionCount());
        assertThrows(HgCorruptDataException.class, () -> revlog.getRawRevisionContent(0));
        assertThrows(HgCorruptDataException.class, () -> revlog.getRawRevisionContent(1));
    }

    // ---------------------------------------------------------------------
    // getRevisionContent / getRevisionMetadata metadata-marker edge cases
    // ---------------------------------------------------------------------

    @Test
    public void testGetRevisionContentReturnsRawWhenNoClosingMetaMarker(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("nomarker.i").toFile();
        File datFile = tempDir.resolve("nomarker.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);

        byte[] raw = ("\n" + "text that never closes the metadata marker").getBytes(StandardCharsets.UTF_8);
        byte[] node = new byte[20];
        byte[] p = new byte[20];
        revlog.appendRawRevision(raw, node, -1, -1, p, p, 0);

        assertArrayEquals(raw, revlog.getRevisionContent(0),
                "Without a closing \\x01\\n marker, content must be returned unmodified");
    }

    @Test
    public void testGetRevisionContentDoublePrefixesContentAlreadyStartingWithMetaMarker(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("doubleprefix.i").toFile();
        File datFile = tempDir.resolve("doubleprefix.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);
        byte[] p = new byte[20];

        byte[] trickyContent = ("\n" + "looks like metadata but is payload\n").getBytes(StandardCharsets.UTF_8);
        revlog.appendRevision(trickyContent, -1, -1, p, p, 0);

        byte[] raw = revlog.getRawRevisionContent(0);
        byte[] expectedRaw = new byte[4 + trickyContent.length];
        expectedRaw[0] = '';
        expectedRaw[1] = '\n';
        expectedRaw[2] = '';
        expectedRaw[3] = '\n';
        System.arraycopy(trickyContent, 0, expectedRaw, 4, trickyContent.length);
        assertArrayEquals(expectedRaw, raw, "Payload starting with the marker must be defensively double-wrapped");

        assertArrayEquals(trickyContent, revlog.getRevisionContent(0),
                "De-escaping must still recover the exact original payload");
    }

    @Test
    public void testGetRevisionContentServesClonedBytesFromCache(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("cachehit.i").toFile();
        File datFile = tempDir.resolve("cachehit.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);
        byte[] p = new byte[20];
        byte[] content = "cached content\n".getBytes(StandardCharsets.UTF_8);
        revlog.appendRevision(content, -1, -1, p, p, 0);

        byte[] first = revlog.getRevisionContent(0);
        byte[] second = revlog.getRevisionContent(0);
        assertArrayEquals(content, first);
        assertArrayEquals(content, second);
        assertNotSame(first, second, "Cached content must be defensively cloned on every call");
    }

    @Test
    public void testContentCacheStaysCorrectPastItsHundredEntryCap(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("cacheevict.i").toFile();
        File datFile = tempDir.resolve("cacheevict.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);
        byte[] p = new byte[20];
        byte[] prev = p;
        int total = 105;
        for (int i = 0; i < total; i++) {
            byte[] content = ("rev " + i).getBytes(StandardCharsets.UTF_8);
            prev = revlog.appendRevision(content, i - 1, -1, prev, p, i);
            revlog.getRevisionContent(i); // populate the LRU content cache beyond its 100-entry cap
        }
        for (int i = total - 10; i < total; i++) {
            assertArrayEquals(("rev " + i).getBytes(StandardCharsets.UTF_8), revlog.getRevisionContent(i));
        }
        // Revisions evicted from the cache must still decode correctly straight from disk.
        assertArrayEquals("rev 0".getBytes(StandardCharsets.UTF_8), revlog.getRevisionContent(0));
    }

    @Test
    public void testGetRevisionMetadataParsesAndSkipsMalformedLines(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("meta.i").toFile();
        File datFile = tempDir.resolve("meta.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);
        byte[] node = new byte[20];
        byte[] p = new byte[20];

        revlog.appendRawRevision("plain content, no marker".getBytes(StandardCharsets.UTF_8), node, -1, -1, p, p, 0);
        assertTrue(revlog.getRevisionMetadata(0).isEmpty());

        byte[] emptyMeta = "\n\nbody".getBytes(StandardCharsets.UTF_8);
        revlog.appendRawRevision(emptyMeta, node, 0, -1, p, p, 1);
        assertTrue(revlog.getRevisionMetadata(1).isEmpty(), "An immediately-closed (empty) metadata section yields no entries");

        String metaBody = "\n" + "\n" + "malformed-no-colon" + "\n" + "copy: file.txt\n" + "\nrest";
        byte[] mixed = metaBody.getBytes(StandardCharsets.UTF_8);
        revlog.appendRawRevision(mixed, node, 1, -1, p, p, 2);
        Map<String, String> meta = revlog.getRevisionMetadata(2);
        assertEquals(1, meta.size());
        assertEquals("file.txt", meta.get("copy"));
    }

    // ---------------------------------------------------------------------
    // isCensoredText (_peek_iscensored equivalent) — static marker sniffing
    // ---------------------------------------------------------------------

    @Test
    public void testIsCensoredTextRecognizesRealHgTombstoneMarkerAndRejectsLookalikes() {
        assertFalse(Revlog.isCensoredText(null));
        assertFalse(Revlog.isCensoredText(new byte[]{1}));
        assertFalse(Revlog.isCensoredText("plain text, no marker at all".getBytes(StandardCharsets.UTF_8)));
        assertFalse(Revlog.isCensoredText("\ncensored:admin\nno closing marker".getBytes(StandardCharsets.UTF_8)));
        assertFalse(Revlog.isCensoredText("\n\nbody text".getBytes(StandardCharsets.UTF_8)),
                "An immediately-closed (empty) metadata section can't carry a censored key");
        assertFalse(Revlog.isCensoredText("\ncopy: file.txt\n\nbody".getBytes(StandardCharsets.UTF_8)));
        assertFalse(Revlog.isCensoredText("\ncensoredby: admin\n\nbody".getBytes(StandardCharsets.UTF_8)),
                "The key must match \"censored\" exactly, not merely as a prefix");
        assertTrue(Revlog.isCensoredText(
                "\ncensored:Test User <test@example.com>\n\ntombstone".getBytes(StandardCharsets.UTF_8)));
    }

    // ---------------------------------------------------------------------
    // censorRevision
    // ---------------------------------------------------------------------

    @Test
    public void testCensorRevisionRejectsOutOfRangeRevision(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("censor-range.i").toFile();
        File datFile = tempDir.resolve("censor-range.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);
        byte[] p = new byte[20];
        revlog.appendRevision("a".getBytes(StandardCharsets.UTF_8), -1, -1, p, p, 0);

        assertThrows(HgRevisionNotFoundException.class, () -> revlog.censorRevision(-1, new byte[0]));
        assertThrows(HgRevisionNotFoundException.class, () -> revlog.censorRevision(1, new byte[0]));
    }

    @Test
    public void testCensorRevisionInlineRoundTripPreservesNodeIdentityAndOtherRevisions(@TempDir Path tempDir) throws Exception {
        byte[] content0 = "keep me\n".getBytes(StandardCharsets.UTF_8);
        byte[] content1 = "secret content\n".getBytes(StandardCharsets.UTF_8);

        File idxFile = tempDir.resolve("censor-inline.i").toFile();
        byte[] zip0 = zlib(content0);
        byte[] zip1 = zlib(content1);
        byte[] node0Full = new byte[32];
        Arrays.fill(node0Full, (byte) 0xAA);
        byte[] node1Full = new byte[32];
        Arrays.fill(node1Full, (byte) 0xBB);

        ByteBuffer rec0 = ByteBuffer.allocate(64);
        rec0.putLong(0x0001000100000000L);
        rec0.putInt(zip0.length);
        rec0.putInt(content0.length);
        rec0.putInt(0);
        rec0.putInt(0);
        rec0.putInt(-1);
        rec0.putInt(-1);
        rec0.put(node0Full);

        long offset1 = zip0.length;
        ByteBuffer rec1 = ByteBuffer.allocate(64);
        rec1.putLong(offset1 << 16);
        rec1.putInt(zip1.length);
        rec1.putInt(content1.length);
        rec1.putInt(1); // baseRev = 1 (fulltext, not a delta)
        rec1.putInt(1);
        rec1.putInt(0);
        rec1.putInt(-1);
        rec1.put(node1Full);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(rec0.array());
        out.write(zip0);
        out.write(rec1.array());
        out.write(zip1);
        Files.write(idxFile.toPath(), out.toByteArray());

        Revlog revlog = new Revlog(idxFile, new File(tempDir.toFile(), "unused.d"));
        assertEquals(2, revlog.getRevisionCount());
        assertArrayEquals(content0, revlog.getRevisionContent(0));
        assertArrayEquals(content1, revlog.getRevisionContent(1));

        byte[] tombstone = "censor tombstone payload".getBytes(StandardCharsets.UTF_8);
        revlog.censorRevision(1, tombstone);

        assertFalse(revlog.isCensored(0));
        assertTrue(revlog.isCensored(1));
        assertArrayEquals(content0, revlog.getRevisionContent(0), "Untouched revision must remain readable");
        assertArrayEquals(tombstone, revlog.getRawRevisionContent(1));
        HgCensoredContentException ex = assertThrows(HgCensoredContentException.class, () -> revlog.getRevisionContent(1));
        assertArrayEquals(tombstone, ex.getTombstone());

        assertArrayEquals(Arrays.copyOf(node1Full, 20), Arrays.copyOf(revlog.getIndexRecord(1).getNodeId(), 20),
                "Node identity must be preserved across censorship");
        assertEquals(0, revlog.getIndexRecord(1).getParent1());

        // Persisted correctly: a freshly-opened instance sees the same state.
        Revlog reopened = new Revlog(idxFile, new File(tempDir.toFile(), "unused.d"));
        assertTrue(reopened.isCensored(1));
        assertArrayEquals(content0, reopened.getRevisionContent(0));
        assertArrayEquals(tombstone, reopened.getRawRevisionContent(1));
    }

    @Test
    public void testCensorRevisionNonInlinePreservesDagShapeOfOtherRevisions(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("censor.i").toFile();
        File datFile = tempDir.resolve("censor.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);
        byte[] p = new byte[20];

        byte[] node0 = revlog.appendRevision("v1\n".getBytes(StandardCharsets.UTF_8), -1, -1, p, p, 0);
        byte[] node1 = revlog.appendRevision("v1\nv2\n".getBytes(StandardCharsets.UTF_8), 0, -1, node0, p, 1);
        byte[] node2 = revlog.appendRevision("v1\nv2\nv3\n".getBytes(StandardCharsets.UTF_8), 1, -1, node1, p, 2);

        byte[] tombstone = "tombstone-payload".getBytes(StandardCharsets.UTF_8);
        revlog.censorRevision(1, tombstone);

        assertFalse(revlog.isCensored(0));
        assertTrue(revlog.isCensored(1));
        assertFalse(revlog.isCensored(2));

        assertArrayEquals("v1\n".getBytes(StandardCharsets.UTF_8), revlog.getRevisionContent(0));
        assertArrayEquals(tombstone, revlog.getRawRevisionContent(1));
        assertThrows(HgCensoredContentException.class, () -> revlog.getRevisionContent(1));
        // rev2 was originally stored as a delta against rev1; after censorship every revision is
        // rewritten as fulltext, so rev2 must still reconstruct correctly.
        assertArrayEquals("v1\nv2\nv3\n".getBytes(StandardCharsets.UTF_8), revlog.getRevisionContent(2));

        assertArrayEquals(node0, Arrays.copyOf(revlog.getIndexRecord(0).getNodeId(), 20));
        assertArrayEquals(node2, Arrays.copyOf(revlog.getIndexRecord(2).getNodeId(), 20));
        assertEquals(1, revlog.getIndexRecord(2).getParent1());
        assertEquals(2, revlog.getIndexRecord(2).getBaseRev(), "censorRevision always rewrites as full (non-delta) entries");
    }

    // ---------------------------------------------------------------------
    // appendChangeGroupEntry — cg1/cg2/cg3 deltabase handling, censorship, chain limits
    // ---------------------------------------------------------------------

    @Test
    public void testAppendChangeGroupEntrySkipsAlreadyPresentNode(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("dup.i").toFile();
        File datFile = tempDir.resolve("dup.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);
        byte[] p = new byte[20];
        byte[] node0 = revlog.appendRevision("hello\n".getBytes(StandardCharsets.UTF_8), -1, -1, p, p, 0);

        ChangegroupParser.ChangeGroupEntry entry = new ChangegroupParser.ChangeGroupEntry();
        entry.node = node0;
        entry.p1 = p;
        entry.p2 = p;
        entry.deltabase = null;
        entry.delta = Revlog.createSimpleDelta(new byte[0], "hello\n".getBytes(StandardCharsets.UTF_8));

        revlog.appendChangeGroupEntry(entry, 0);
        assertEquals(1, revlog.getRevisionCount(), "Re-adding an already-present node must be a silent no-op");
    }

    @Test
    public void testAppendChangeGroupEntryHandlesExplicitAllZeroDeltabase(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("zerobase.i").toFile();
        File datFile = tempDir.resolve("zerobase.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);

        byte[] zero = new byte[20];
        byte[] content = "root content\n".getBytes(StandardCharsets.UTF_8);
        byte[] delta = Revlog.createSimpleDelta(new byte[0], content);
        byte[] node = nodeHash(zero, zero, content);

        ChangegroupParser.ChangeGroupEntry entry = new ChangegroupParser.ChangeGroupEntry();
        entry.node = node;
        entry.p1 = zero;
        entry.p2 = zero;
        entry.deltabase = zero; // cg2/cg3-style explicit all-zero deltabase, not present in the (empty) index
        entry.delta = delta;

        revlog.appendChangeGroupEntry(entry, 0);
        assertEquals(1, revlog.getRevisionCount());
        assertArrayEquals(content, revlog.getRevisionContent(0));
    }

    @Test
    public void testAppendChangeGroupEntryThrowsWhenDeltabaseMissing(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("missingbase.i").toFile();
        File datFile = tempDir.resolve("missingbase.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);

        byte[] zero = new byte[20];
        byte[] unknownBase = new byte[20];
        Arrays.fill(unknownBase, (byte) 0x42);
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        byte[] delta = Revlog.createSimpleDelta(new byte[0], content);
        byte[] node = nodeHash(zero, zero, content);

        ChangegroupParser.ChangeGroupEntry entry = new ChangegroupParser.ChangeGroupEntry();
        entry.node = node;
        entry.p1 = zero;
        entry.p2 = zero;
        entry.deltabase = unknownBase;
        entry.delta = delta;

        assertThrows(HgCorruptDataException.class, () -> revlog.appendChangeGroupEntry(entry, 0));
    }

    @Test
    public void testAppendChangeGroupEntryCg1FirstEntryDeltaAgainstEmptyBase(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("cg1first.i").toFile();
        File datFile = tempDir.resolve("cg1first.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);
        byte[] zero = new byte[20];
        byte[] content = "first ever content\n".getBytes(StandardCharsets.UTF_8);
        byte[] delta = Revlog.createSimpleDelta(new byte[0], content);
        byte[] node = nodeHash(zero, zero, content);

        ChangegroupParser.ChangeGroupEntry entry = new ChangegroupParser.ChangeGroupEntry();
        entry.node = node;
        entry.p1 = zero;
        entry.p2 = zero;
        entry.deltabase = null; // cg1
        entry.delta = delta;

        revlog.appendChangeGroupEntry(entry, 0);
        assertEquals(1, revlog.getRevisionCount());
        assertArrayEquals(content, revlog.getRevisionContent(0));
    }

    @Test
    public void testAppendChangeGroupEntryCg1UsesPositionalPreviousEntryAsBase(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("cg1.i").toFile();
        File datFile = tempDir.resolve("cg1.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);
        byte[] zero = new byte[20];

        byte[] content0 = "root\n".getBytes(StandardCharsets.UTF_8);
        revlog.appendRevision(content0, -1, -1, zero, zero, 0);

        // cg1's positional rule: even though this entry's DAG parent (p1) is an unrelated root
        // (all-zero, not rev0), the delta must still be applied against rev0 -- the immediately
        // preceding entry in the stream -- since cg1 carries no deltabase field at all.
        byte[] content1 = "unrelated second root\n".getBytes(StandardCharsets.UTF_8);
        byte[] delta = Revlog.createSimpleDelta(content0, content1);
        byte[] node1 = nodeHash(zero, zero, content1);

        ChangegroupParser.ChangeGroupEntry entry = new ChangegroupParser.ChangeGroupEntry();
        entry.node = node1;
        entry.p1 = zero;
        entry.p2 = zero;
        entry.deltabase = null;
        entry.delta = delta;

        revlog.appendChangeGroupEntry(entry, 1);

        assertEquals(2, revlog.getRevisionCount());
        assertArrayEquals(content1, revlog.getRevisionContent(1));
        assertEquals(-1, revlog.getIndexRecord(1).getParent1());
    }

    @Test
    public void testAppendChangeGroupEntryRejectsHashMismatch(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("hashmismatch.i").toFile();
        File datFile = tempDir.resolve("hashmismatch.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);
        byte[] zero = new byte[20];
        byte[] content = "correct content\n".getBytes(StandardCharsets.UTF_8);
        byte[] delta = Revlog.createSimpleDelta(new byte[0], content);
        byte[] wrongNode = new byte[20];
        Arrays.fill(wrongNode, (byte) 0x99);

        ChangegroupParser.ChangeGroupEntry entry = new ChangegroupParser.ChangeGroupEntry();
        entry.node = wrongNode;
        entry.p1 = zero;
        entry.p2 = zero;
        entry.deltabase = null;
        entry.delta = delta;

        assertThrows(HgCorruptDataException.class, () -> revlog.appendChangeGroupEntry(entry, 0));
        assertEquals(0, revlog.getRevisionCount(), "A hash-mismatched entry must not be persisted");
    }

    @Test
    public void testAppendChangeGroupEntrySkipsDeltaWhenParentIsCensored(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("censoredparent.i").toFile();
        File datFile = tempDir.resolve("censoredparent.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);
        byte[] zero = new byte[20];

        byte[] content0 = "will be censored\n".getBytes(StandardCharsets.UTF_8);
        byte[] node0 = revlog.appendRevision(content0, -1, -1, zero, zero, 0);
        revlog.censorRevision(0, "tombstone".getBytes(StandardCharsets.UTF_8));
        assertTrue(revlog.isCensored(0));

        byte[] tombstoneRaw = revlog.getRawRevisionContent(0);
        byte[] content1 = "brand new unrelated content\n".getBytes(StandardCharsets.UTF_8);
        byte[] delta = Revlog.createSimpleDelta(tombstoneRaw, content1);
        byte[] node1 = nodeHash(node0, zero, content1);

        ChangegroupParser.ChangeGroupEntry entry = new ChangegroupParser.ChangeGroupEntry();
        entry.node = node1;
        entry.p1 = node0;
        entry.p2 = zero;
        entry.deltabase = node0;
        entry.delta = delta;

        revlog.appendChangeGroupEntry(entry, 1);

        assertEquals(2, revlog.getRevisionCount());
        assertArrayEquals(content1, revlog.getRevisionContent(1));
        assertEquals(1, revlog.getIndexRecord(1).getBaseRev(),
                "Must never be stored as a delta against a censored parent, even though flags==0");
    }

    @Test
    public void testAppendChangeGroupEntryAlwaysFulltextForMetadataLogs(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("00changelog.i").toFile();
        File datFile = tempDir.resolve("00changelog.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);
        byte[] zero = new byte[20];

        byte[] content0 = "root changeset\n".getBytes(StandardCharsets.UTF_8);
        byte[] node0 = revlog.appendRevision(content0, -1, -1, zero, zero, 0);

        byte[] content1 = "root changeset\nsecond changeset\n".getBytes(StandardCharsets.UTF_8);
        byte[] delta = Revlog.createSimpleDelta(content0, content1);
        byte[] node1 = nodeHash(node0, zero, content1);

        ChangegroupParser.ChangeGroupEntry entry = new ChangegroupParser.ChangeGroupEntry();
        entry.node = node1;
        entry.p1 = node0;
        entry.p2 = zero;
        entry.deltabase = node0;
        entry.delta = delta;

        revlog.appendChangeGroupEntry(entry, 1);

        assertEquals(1, revlog.getIndexRecord(1).getBaseRev(),
                "00changelog.i must always be stored fulltext, never delta-chained, regardless of parent similarity");
        assertArrayEquals(content1, revlog.getRevisionContent(1));
    }

    @Test
    public void testAppendChangeGroupEntryFallsBackToFulltextWhenDeltaChainTooLong(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("cglongchain.i").toFile();
        File datFile = tempDir.resolve("cglongchain.d").toFile();
        Revlog revlog = buildLongDeltaChain(idxFile, datFile, 100);
        byte[] zero = new byte[20];
        byte[] node99 = Arrays.copyOf(revlog.getIndexRecord(99).getNodeId(), 20);
        byte[] base99 = revlog.getRawRevisionContent(99);

        byte[] content100 = "hundredth changegroup entry\n".getBytes(StandardCharsets.UTF_8);
        byte[] delta = Revlog.createSimpleDelta(base99, content100);
        byte[] node100 = nodeHash(node99, zero, content100);

        ChangegroupParser.ChangeGroupEntry entry = new ChangegroupParser.ChangeGroupEntry();
        entry.node = node100;
        entry.p1 = node99;
        entry.p2 = zero;
        entry.deltabase = node99;
        entry.delta = delta;

        revlog.appendChangeGroupEntry(entry, 100);

        Revlog.IndexRecord rec = revlog.getIndexRecord(100);
        assertEquals(100, rec.getBaseRev(),
                "A delta chain of length >= 100 must fall back to a fulltext write even for changegroup entries");
        assertArrayEquals(content100, revlog.getRevisionContent(100));
    }

    // ---------------------------------------------------------------------
    // appendRevision / appendOptimizedRevision — chain-length fallback and metadata-log rule
    // ---------------------------------------------------------------------

    @Test
    public void testAppendRevisionFallsBackToFulltextWhenDeltaChainTooLong(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("longchain.i").toFile();
        File datFile = tempDir.resolve("longchain.d").toFile();
        Revlog revlog = buildLongDeltaChain(idxFile, datFile, 100);
        byte[] node99 = revlog.getIndexRecord(99).getNodeId();
        byte[] zero = new byte[20];

        byte[] content = "one hundredth revision content\n".getBytes(StandardCharsets.UTF_8);
        revlog.appendRevision(content, 99, -1, Arrays.copyOf(node99, 20), zero, 100);

        Revlog.IndexRecord rec = revlog.getIndexRecord(100);
        assertEquals(100, rec.getBaseRev(), "A delta chain of length >= 100 must fall back to a fulltext write");
        assertArrayEquals(content, revlog.getRevisionContent(100));
    }

    @Test
    public void testAppendOptimizedRevisionFallsBackToFulltextWhenDeltaChainTooLong(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("optlongchain.i").toFile();
        File datFile = tempDir.resolve("optlongchain.d").toFile();
        Revlog revlog = buildLongDeltaChain(idxFile, datFile, 100);
        byte[] node99 = revlog.getIndexRecord(99).getNodeId();
        byte[] zero = new byte[20];
        byte[] nodeId100 = new byte[32];
        Arrays.fill(nodeId100, (byte) 0x5C);

        byte[] content = "one hundredth optimized revision\n".getBytes(StandardCharsets.UTF_8);
        revlog.appendOptimizedRevision(content, nodeId100, 99, -1, Arrays.copyOf(node99, 20), zero, 100);

        Revlog.IndexRecord rec = revlog.getIndexRecord(100);
        assertEquals(100, rec.getBaseRev(), "A delta chain of length >= 100 must fall back to a fulltext write");
        assertArrayEquals(content, revlog.getRevisionContent(100));
    }

    @Test
    public void testAppendRevisionAlwaysFulltextForChangelogAndManifest(@TempDir Path tempDir) throws Exception {
        for (String name : new String[]{"00changelog.i", "00manifest.i"}) {
            File idxFile = tempDir.resolve(name).toFile();
            File datFile = tempDir.resolve(name.replace(".i", ".d")).toFile();
            Revlog revlog = new Revlog(idxFile, datFile);
            byte[] p = new byte[20];
            byte[] content0 = "root\n".getBytes(StandardCharsets.UTF_8);
            byte[] node0 = revlog.appendRevision(content0, -1, -1, p, p, 0);
            byte[] content1 = "root\nsecond\n".getBytes(StandardCharsets.UTF_8);
            revlog.appendRevision(content1, 0, -1, node0, p, 1);

            assertEquals(1, revlog.getIndexRecord(1).getBaseRev(),
                    name + " must always store fulltext (never delta), regardless of parent similarity");
        }
    }

    @Test
    public void testAppendOptimizedRevisionAlwaysFulltextForMetadataLogs(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("00changelog.i").toFile();
        File datFile = tempDir.resolve("00changelog.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);
        byte[] p = new byte[20];
        byte[] nodeId0 = new byte[32];
        Arrays.fill(nodeId0, (byte) 0x11);
        byte[] content0 = "root\n".getBytes(StandardCharsets.UTF_8);
        revlog.appendOptimizedRevision(content0, nodeId0, -1, -1, p, p, 0);

        byte[] nodeId1 = new byte[32];
        Arrays.fill(nodeId1, (byte) 0x22);
        byte[] content1 = "root\nsecond\n".getBytes(StandardCharsets.UTF_8);
        revlog.appendOptimizedRevision(content1, nodeId1, 0, -1, p, p, 1);

        assertEquals(1, revlog.getIndexRecord(1).getBaseRev());
    }

    @Test
    public void testAppendOptimizedRevisionUsesDeltaWhenSmallerAndPersists(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("optimized.i").toFile();
        File datFile = tempDir.resolve("optimized.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);
        byte[] p = new byte[20];
        byte[] node0 = new byte[32];
        Arrays.fill(node0, (byte) 0xAA);
        byte[] content0 = "Line one\nLine two\n".getBytes(StandardCharsets.UTF_8);
        revlog.appendOptimizedRevision(content0, node0, -1, -1, p, p, 0);

        byte[] node1 = new byte[32];
        Arrays.fill(node1, (byte) 0xBB);
        byte[] content1 = "Line one\nLine two\nLine three\n".getBytes(StandardCharsets.UTF_8);
        revlog.appendOptimizedRevision(content1, node1, 0, -1, Arrays.copyOf(node0, 20), p, 1);

        assertEquals(2, revlog.getRevisionCount());
        assertEquals(0, revlog.getIndexRecord(1).getBaseRev(), "Should choose delta storage since it's smaller");
        assertArrayEquals(content1, revlog.getRevisionContent(1));
        assertArrayEquals(Arrays.copyOf(node1, 20), Arrays.copyOf(revlog.getIndexRecord(1).getNodeId(), 20));

        Revlog reopened = new Revlog(idxFile, datFile);
        assertArrayEquals(content1, reopened.getRevisionContent(1));
    }

    // ---------------------------------------------------------------------
    // Inline-append paths of appendRevision / appendOptimizedRevision / appendRawRevision
    // ---------------------------------------------------------------------

    @Test
    public void testAppendRevisionWritesInlineFormatForExistingInlineRevlog(@TempDir Path tempDir) throws Exception {
        byte[] content0 = "Hello\n".getBytes(StandardCharsets.UTF_8);
        File idxFile = buildInlineSingleRevision(tempDir, "inlineappend.i", content0);
        Revlog revlog = new Revlog(idxFile, new File(tempDir.toFile(), "unused.d"));
        assertEquals(1, revlog.getRevisionCount());
        assertArrayEquals(content0, revlog.getRevisionContent(0));

        byte[] p = new byte[20];
        byte[] content1 = "Hello\nWorld\n".getBytes(StandardCharsets.UTF_8);
        revlog.appendRevision(content1, 0, -1, p, p, 1);

        assertEquals(2, revlog.getRevisionCount());
        assertArrayEquals(content1, revlog.getRevisionContent(1));

        Revlog reopened = new Revlog(idxFile, new File(tempDir.toFile(), "unused.d"));
        assertEquals(2, reopened.getRevisionCount());
        assertArrayEquals(content1, reopened.getRevisionContent(1));
    }

    @Test
    public void testAppendOptimizedRevisionWritesInlineFormatForExistingInlineRevlog(@TempDir Path tempDir) throws Exception {
        byte[] content0 = "Alpha\n".getBytes(StandardCharsets.UTF_8);
        File idxFile = buildInlineSingleRevision(tempDir, "inlineopt.i", content0);
        Revlog revlog = new Revlog(idxFile, new File(tempDir.toFile(), "unused.d"));

        byte[] p = new byte[20];
        byte[] nodeId = new byte[32];
        Arrays.fill(nodeId, (byte) 0x55);
        byte[] content1 = "Alpha\nBeta\n".getBytes(StandardCharsets.UTF_8);
        revlog.appendOptimizedRevision(content1, nodeId, 0, -1, p, p, 1);

        assertEquals(2, revlog.getRevisionCount());
        assertArrayEquals(content1, revlog.getRevisionContent(1));

        Revlog reopened = new Revlog(idxFile, new File(tempDir.toFile(), "unused.d"));
        assertArrayEquals(content1, reopened.getRevisionContent(1));
    }

    /**
     * REAL BUG (found while writing this test, fixed in Revlog.java): {@code appendRawRevision}
     * unconditionally wrote its compressed payload to {@code datFile} and a bare 64-byte header to
     * {@code idxFile}, ignoring this revlog's own {@code inline} flag. For a revlog loaded from an
     * on-disk *inline* layout (as real hg produces for small files -- exactly what {@code
     * RebaseCommand} reopens and calls {@code appendRawRevision} on when restoring filelog/manifest/
     * changelog backups), this appended a revision whose data was never actually written where the
     * inline read path (which ignores the per-record offset field and instead seeks to {@code
     * index.getFileOffset(rev) + 64} inside {@code idxFile}) would look for it -- so the very next
     * read of that revision failed with {@link HgCorruptDataException} (truncated hunk at EOF).
     * Fixed by branching on {@code inline} exactly like {@code appendRevision}/{@code
     * appendOptimizedRevision} already do.
     */
    @Test
    public void testAppendRawRevisionWritesInlineFormatForExistingInlineRevlog(@TempDir Path tempDir) throws Exception {
        byte[] content0 = "Root\n".getBytes(StandardCharsets.UTF_8);
        File idxFile = buildInlineSingleRevision(tempDir, "inlineraw.i", content0);
        Revlog revlog = new Revlog(idxFile, new File(tempDir.toFile(), "unused.d"));

        byte[] p = new byte[20];
        byte[] node1 = new byte[20];
        Arrays.fill(node1, (byte) 0x77);
        byte[] rawContent1 = "Root\nChild raw content\n".getBytes(StandardCharsets.UTF_8);
        revlog.appendRawRevision(rawContent1, node1, 0, -1, p, p, 1);

        assertEquals(2, revlog.getRevisionCount());
        assertArrayEquals(rawContent1, revlog.getRevisionContent(1),
                "appendRawRevision must honor this revlog's existing inline layout");

        Revlog reopened = new Revlog(idxFile, new File(tempDir.toFile(), "unused.d"));
        assertEquals(2, reopened.getRevisionCount());
        assertArrayEquals(rawContent1, reopened.getRevisionContent(1));
    }

    @Test
    public void testAppendRawRevisionNonInlineRoundTrip(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("rawnoninline.i").toFile();
        File datFile = tempDir.resolve("rawnoninline.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);
        byte[] node0 = new byte[20];
        Arrays.fill(node0, (byte) 0x33);
        byte[] content0 = "First raw revision\n".getBytes(StandardCharsets.UTF_8);
        byte[] p = new byte[20];
        revlog.appendRawRevision(content0, node0, -1, -1, p, p, 0);

        assertEquals(1, revlog.getRevisionCount());
        assertArrayEquals(content0, revlog.getRevisionContent(0));
        assertArrayEquals(node0, Arrays.copyOf(revlog.getIndexRecord(0).getNodeId(), 20));
        assertEquals(0, revlog.getIndexRecord(0).getBaseRev());
    }

    // ---------------------------------------------------------------------
    // readHunk mmap path (compLen > 5MB)
    // ---------------------------------------------------------------------

    @Test
    public void testReadHunkUsesMemoryMappingForLargeIncompressibleContent(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("bigmmap.i").toFile();
        File datFile = tempDir.resolve("bigmmap.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);

        byte[] content = new byte[6 * 1024 * 1024];
        new SecureRandom().nextBytes(content); // incompressible -> stored uncompressed, compLen > 5MB
        byte[] p = new byte[20];
        revlog.appendRevision(content, -1, -1, p, p, 0);

        Revlog.IndexRecord rec = revlog.getIndexRecord(0);
        assertTrue(rec.getCompLen() > 5 * 1024 * 1024, "Test setup must actually exceed the mmap threshold");

        assertArrayEquals(content, revlog.getRevisionContent(0));
    }

    // ---------------------------------------------------------------------
    // Generic (non-changelog) revlog-v2 write support
    // ---------------------------------------------------------------------

    @Test
    public void testAppendRevisionSucceedsForGenericNonChangelogRevlogV2(@TempDir Path tempDir) throws Exception {
        File idxFile = buildGenericRevlogV2Docket(tempDir, "00manifest");
        Revlog revlog = new Revlog(idxFile, new File(tempDir.toFile(), "00manifest.d"));
        assertTrue(revlog.getIndex().isV2());
        assertFalse(revlog.getIndex().isChangelogV2());

        byte[] p = new byte[20];
        byte[] content = "content".getBytes(StandardCharsets.UTF_8);
        // linkRev (7) deliberately differs from rev (0) to prove it's genuinely threaded through
        // rather than silently synthesized from rev, the way changelog-v2 does.
        revlog.appendRevision(content, -1, -1, p, p, 7);

        assertEquals(1, revlog.getRevisionCount());
        assertArrayEquals(content, revlog.getRawRevisionContent(0));
        assertEquals(7, revlog.getIndexRecord(0).getLinkRev(),
                "general v2 must store the real linkRev, not synthesize it from rev like changelog-v2 does");
    }

    // ---------------------------------------------------------------------
    // IndexRecord compact constructor
    // ---------------------------------------------------------------------

    @Test
    public void testIndexRecordCompactConstructorHandlesNullAndOversizedNodeId() {
        Revlog.IndexRecord withNull = new Revlog.IndexRecord(0, 0, 0, 1, 1, 0, 0, -1, -1, null);
        assertNull(withNull.getNodeId());

        byte[] oversized = new byte[32];
        Arrays.fill(oversized, (byte) 7);
        Revlog.IndexRecord truncated = new Revlog.IndexRecord(0, 0, 0, 1, 1, 0, 0, -1, -1, oversized);
        assertEquals(20, truncated.getNodeId().length);
    }

    // ---------------------------------------------------------------------
    // clearCache() swallowing a failed reload
    // ---------------------------------------------------------------------

    @Test
    public void testClearCacheSwallowsCorruptIndexReloadException(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("clearcache-corrupt.i").toFile();
        File datFile = tempDir.resolve("clearcache-corrupt.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);
        byte[] p = new byte[20];
        revlog.appendRevision("hello\n".getBytes(StandardCharsets.UTF_8), -1, -1, p, p, 0);
        assertEquals(1, revlog.getRevisionCount());

        // Truncate the on-disk index to an invalid (too-short) length after the Revlog instance
        // already holds a valid in-memory index -- clearCache()'s reload (RevlogIndex.clearCache()
        // -> loadIndex()) must then throw HgCorruptDataException internally, which Revlog.clearCache()
        // is documented to swallow rather than propagate.
        Files.write(idxFile.toPath(), new byte[10]);

        assertDoesNotThrow(revlog::clearCache);
        assertEquals(0, revlog.getRevisionCount(),
                "A failed reload resets the in-memory index to empty rather than leaving stale state");
    }

    // ---------------------------------------------------------------------
    // rev==0 inline write path of appendRevision/appendRawRevision/appendOptimizedRevision
    // ---------------------------------------------------------------------

    /**
     * hg4j never creates a brand-new inline revlog on its own (see {@link
     * #buildInlineSingleRevision}'s javadoc: inline-ness is only ever discovered by loading an
     * existing inline-format file, which by construction already has at least one revision) -- so
     * the {@code rev == 0 && inline} branch of each append method's write path can't actually be
     * reached through the public API. It's still real production code guarding real on-disk format
     * correctness, so it's exercised here by forcing the private {@code inline} field directly (the
     * same white-box technique {@code RevlogTest#testDecompressHunkHeuristic} already uses via
     * reflection), then verified end-to-end by reopening the file through a brand new,
     * non-reflective {@code Revlog} instance.
     */
    private static void forceInline(Revlog revlog) throws Exception {
        Field f = Revlog.class.getDeclaredField("inline");
        f.setAccessible(true);
        f.setBoolean(revlog, true);
    }

    @Test
    public void testAppendRevisionEncodesInlineFormatFlagsForFreshRevisionZero(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("forcedinline.i").toFile();
        File datFile = tempDir.resolve("forcedinline.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);
        forceInline(revlog);

        byte[] p = new byte[20];
        byte[] content0 = "Forced inline revision zero\n".getBytes(StandardCharsets.UTF_8);
        revlog.appendRevision(content0, -1, -1, p, p, 0);
        assertEquals(1, revlog.getRevisionCount());

        Revlog reopened = new Revlog(idxFile, datFile);
        assertTrue(reopened.getIndex().isInline(),
                "The written rev0 record's format flags must actually encode inline=1");
        assertEquals(1, reopened.getRevisionCount());
        assertArrayEquals(content0, reopened.getRevisionContent(0));
    }

    @Test
    public void testAppendRawRevisionEncodesInlineFormatFlagsForFreshRevisionZero(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("forcedinlineraw.i").toFile();
        File datFile = tempDir.resolve("forcedinlineraw.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);
        forceInline(revlog);

        byte[] p = new byte[20];
        byte[] node0 = new byte[20];
        Arrays.fill(node0, (byte) 0x66);
        byte[] rawContent0 = "Forced inline raw revision zero\n".getBytes(StandardCharsets.UTF_8);
        revlog.appendRawRevision(rawContent0, node0, -1, -1, p, p, 0);
        assertEquals(1, revlog.getRevisionCount());

        Revlog reopened = new Revlog(idxFile, datFile);
        assertTrue(reopened.getIndex().isInline());
        assertArrayEquals(rawContent0, reopened.getRevisionContent(0));
        assertArrayEquals(node0, Arrays.copyOf(reopened.getIndexRecord(0).getNodeId(), 20));
    }

    @Test
    public void testAppendOptimizedRevisionEncodesInlineFormatFlagsForFreshRevisionZero(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("forcedinlineopt.i").toFile();
        File datFile = tempDir.resolve("forcedinlineopt.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);
        forceInline(revlog);

        byte[] p = new byte[20];
        byte[] nodeId0 = new byte[32];
        Arrays.fill(nodeId0, (byte) 0x77);
        byte[] content0 = "Forced inline optimized revision zero\n".getBytes(StandardCharsets.UTF_8);
        revlog.appendOptimizedRevision(content0, nodeId0, -1, -1, p, p, 0);
        assertEquals(1, revlog.getRevisionCount());

        Revlog reopened = new Revlog(idxFile, datFile);
        assertTrue(reopened.getIndex().isInline());
        assertArrayEquals(content0, reopened.getRevisionContent(0));
        assertArrayEquals(Arrays.copyOf(nodeId0, 20), Arrays.copyOf(reopened.getIndexRecord(0).getNodeId(), 20));
    }

    // ---------------------------------------------------------------------
    // Defensive baseRev==-1 guard in the chain-length probe loops
    // ---------------------------------------------------------------------

    @Test
    public void testChainLengthWalkBreaksOnDefensiveBaseRevMinusOne(@TempDir Path tempDir) throws Exception {
        // Every append path's chain-length probe walks parent1's baseRev pointers to bound delta
        // chain depth. Real hg4j writes never produce a record whose baseRev is -1 (full/self and
        // delta/parent-rev are the only cases it ever emits), but the loop still guards against it
        // defensively (`currRec.getBaseRev() == -1`) in case of on-disk corruption. Exercised here
        // via a hand-crafted corrupt rev0, isolated behind a "00changelog.i" filename so the
        // metadata-log rule (always fulltext) keeps every append below from ever needing to *read*
        // rev0's content -- which would otherwise recurse into the same corrupt baseRev inside
        // getRawRevisionContent's own (separate) chain walk and fail for unrelated reasons.
        File idxFile = tempDir.resolve("00changelog.i").toFile();
        File datFile = tempDir.resolve("00changelog.d").toFile();

        byte[] node0 = new byte[32];
        Arrays.fill(node0, 0, 20, (byte) 0xAA);
        ByteBuffer rec0 = ByteBuffer.allocate(64);
        rec0.putLong(0x0000000100000000L); // version 1, non-inline
        rec0.putInt(0); // compLen
        rec0.putInt(0); // uncompLen
        rec0.putInt(-1); // baseRev = -1 (the defensive case under test)
        rec0.putInt(0); // linkRev
        rec0.putInt(-1); // parent1
        rec0.putInt(-1); // parent2
        rec0.put(node0);
        Files.write(idxFile.toPath(), rec0.array());

        Revlog revlog = new Revlog(idxFile, datFile);
        assertEquals(1, revlog.getRevisionCount());
        byte[] node0Short = Arrays.copyOf(node0, 20);
        byte[] zero = new byte[20];

        byte[] content1 = "changelog rev via appendRevision\n".getBytes(StandardCharsets.UTF_8);
        revlog.appendRevision(content1, 0, -1, node0Short, zero, 1);
        assertEquals(1, revlog.getIndexRecord(1).getBaseRev());
        assertArrayEquals(content1, revlog.getRevisionContent(1));

        byte[] content2 = "changelog rev via appendChangeGroupEntry\n".getBytes(StandardCharsets.UTF_8);
        byte[] delta2 = Revlog.createSimpleDelta(new byte[0], content2);
        byte[] node2 = nodeHash(node0Short, zero, content2);
        ChangegroupParser.ChangeGroupEntry entry = new ChangegroupParser.ChangeGroupEntry();
        entry.node = node2;
        entry.p1 = node0Short;
        entry.p2 = zero;
        entry.deltabase = zero; // explicit all-zero deltabase avoids reading rev0's (unreadable) content
        entry.delta = delta2;
        revlog.appendChangeGroupEntry(entry, 2);
        assertEquals(2, revlog.getIndexRecord(2).getBaseRev());
        assertEquals(0, revlog.getIndexRecord(2).getParent1());
        assertArrayEquals(content2, revlog.getRevisionContent(2));

        byte[] content3 = "changelog rev via appendOptimizedRevision\n".getBytes(StandardCharsets.UTF_8);
        byte[] nodeId3 = new byte[32];
        Arrays.fill(nodeId3, (byte) 0x99);
        revlog.appendOptimizedRevision(content3, nodeId3, 0, -1, node0Short, zero, 3);
        assertEquals(3, revlog.getIndexRecord(3).getBaseRev());
        assertArrayEquals(content3, revlog.getRevisionContent(3));

        assertEquals(4, revlog.getRevisionCount());
    }
}
