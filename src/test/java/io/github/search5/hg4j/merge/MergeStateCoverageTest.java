package io.github.search5.hg4j.merge;

import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targeted unit coverage for {@link MergeState}, exercising branches not already hit by
 * {@link MergeStateInteropTest} or the merge-command integration tests: reading a missing/absent
 * state file, {@code isActive}/{@code isEmpty}/{@code hasFile}, {@code markResolved}/{@code
 * markUnresolved} over both regular and path conflicts (including no-op cases), {@code
 * unresolvedFiles} filtering, {@code getLocalKey} hashing, and the write-side record-kind
 * derivation (F/C/P) plus labels/extras/error-path handling.
 *
 * <p>All record-kind derivation and field-layout assertions below are cross-checked against real
 * Mercurial's {@code mergestate._makerecords}/{@code _readrecordsv2} (mercurial/mergestate.py).</p>
 */
public class MergeStateCoverageTest {

    private static byte[] node(String hex) {
        return NodeIdUtil.fromHex(hex);
    }

    private static final byte[] LOCAL_NODE =
            node("1111111111111111111111111111111111111111");
    private static final byte[] OTHER_NODE =
            node("2222222222222222222222222222222222222222");

    // ---- read() on a missing/absent state file ----------------------------------------------

    @Test
    public void readNullFileReturnsInactiveEmptyState() throws Exception {
        MergeState ms = MergeState.read(null);
        assertFalse(ms.isActive());
        assertTrue(ms.isEmpty());
        assertNull(ms.local);
        assertNull(ms.other);
    }

    @Test
    public void readNonexistentFileReturnsInactiveEmptyState(@TempDir Path tempDir) throws Exception {
        File missing = tempDir.resolve("does-not-exist/state2").toFile();
        assertFalse(missing.exists());
        MergeState ms = MergeState.read(missing);
        assertFalse(ms.isActive());
        assertTrue(ms.isEmpty());
    }

    // ---- isActive() / isEmpty() / hasFile() --------------------------------------------------

    @Test
    public void isActiveRequiresBothLocalAndOther() {
        MergeState ms = new MergeState();
        assertFalse(ms.isActive(), "neither local nor other set");

        ms.local = LOCAL_NODE;
        assertFalse(ms.isActive(), "only local set");

        ms.local = null;
        ms.other = OTHER_NODE;
        assertFalse(ms.isActive(), "only other set");

        ms.local = LOCAL_NODE;
        assertTrue(ms.isActive(), "both set");
    }

    @Test
    public void isEmptyReflectsStateMap() {
        MergeState ms = new MergeState();
        assertTrue(ms.isEmpty());
        ms.addMergedFile("f.txt", "key", "f.txt", "f.txt", MergeState.NULL_HEX, "f.txt", MergeState.NULL_HEX, "");
        assertFalse(ms.isEmpty());
    }

    @Test
    public void hasFileTracksStateMapMembership() {
        MergeState ms = new MergeState();
        assertFalse(ms.hasFile("f.txt"));
        ms.addMergedFile("f.txt", "key", "f.txt", "f.txt", MergeState.NULL_HEX, "f.txt", MergeState.NULL_HEX, "");
        assertTrue(ms.hasFile("f.txt"));
        assertFalse(ms.hasFile("other.txt"));
    }

    // ---- addMergedFile() ----------------------------------------------------------------------

    @Test
    public void addMergedFileWithNullFlagsStoresEmptyString() {
        MergeState ms = new MergeState();
        ms.addMergedFile("f.txt", "key", "lfile", "afile", "anode", "ofile", "onode", null);
        List<String> fields = ms.state.get("f.txt");
        assertEquals("", fields.get(7));
    }

    // ---- markResolved() / markUnresolved() ----------------------------------------------------

    @Test
    public void markResolvedOnUnknownPathIsNoop() {
        MergeState ms = new MergeState();
        ms.markResolved("nope.txt");
        ms.markUnresolved("nope.txt");
        assertFalse(ms.hasFile("nope.txt"));
    }

    @Test
    public void markResolvedOnEmptyFieldListIsNoop() {
        MergeState ms = new MergeState();
        ms.state.put("empty.txt", new ArrayList<>());
        ms.markResolved("empty.txt");
        assertTrue(ms.state.get("empty.txt").isEmpty(), "empty field list must be left untouched");
    }

    @Test
    public void markResolvedAndUnresolvedToggleRegularConflict() {
        MergeState ms = new MergeState();
        ms.addMergedFile("f.txt", "key", "f.txt", "f.txt", MergeState.NULL_HEX, "f.txt", MergeState.NULL_HEX, "");
        assertEquals(MergeState.UNRESOLVED, ms.state.get("f.txt").get(0));

        ms.markResolved("f.txt");
        assertEquals(MergeState.RESOLVED, ms.state.get("f.txt").get(0));

        ms.markUnresolved("f.txt");
        assertEquals(MergeState.UNRESOLVED, ms.state.get("f.txt").get(0));
    }

    @Test
    public void markResolvedAndUnresolvedToggleShapeConflict() {
        MergeState ms = new MergeState();
        ms.state.put("dir", new ArrayList<>(Arrays.asList(MergeState.UNRESOLVED_PATH, "dir~1", "dir")));

        ms.markResolved("dir");
        assertEquals(MergeState.RESOLVED_PATH, ms.state.get("dir").get(0));
        // Non-state fields must survive the toggle untouched.
        assertEquals("dir~1", ms.state.get("dir").get(1));
        assertEquals("dir", ms.state.get("dir").get(2));

        ms.markUnresolved("dir");
        assertEquals(MergeState.UNRESOLVED_PATH, ms.state.get("dir").get(0));
    }

    // ---- unresolvedFiles() ---------------------------------------------------------------------

    @Test
    public void unresolvedFilesIncludesBothConflictKindsAndExcludesResolvedAndEmpty() {
        MergeState ms = new MergeState();
        ms.addMergedFile("unresolved.txt", "k1", "unresolved.txt", "unresolved.txt", MergeState.NULL_HEX,
                "unresolved.txt", MergeState.NULL_HEX, "");
        ms.addMergedFile("resolved.txt", "k2", "resolved.txt", "resolved.txt", MergeState.NULL_HEX,
                "resolved.txt", MergeState.NULL_HEX, "");
        ms.markResolved("resolved.txt");
        ms.state.put("unresolved-dir", new ArrayList<>(Arrays.asList(MergeState.UNRESOLVED_PATH, "a", "b")));
        ms.state.put("resolved-dir", new ArrayList<>(Arrays.asList(MergeState.RESOLVED_PATH, "a", "b")));
        ms.state.put("blank", new ArrayList<>());

        List<String> unresolved = ms.unresolvedFiles();
        assertEquals(2, unresolved.size());
        assertTrue(unresolved.contains("unresolved.txt"));
        assertTrue(unresolved.contains("unresolved-dir"));
        assertFalse(unresolved.contains("resolved.txt"));
        assertFalse(unresolved.contains("resolved-dir"));
        assertFalse(unresolved.contains("blank"));
    }

    // ---- getLocalKey() ---------------------------------------------------------------------------

    @Test
    public void getLocalKeyMatchesSha1OfPath() {
        // Independently verified: python3 -c "import hashlib;
        // print(hashlib.sha1(b'f.txt').hexdigest())" -> 7ad4af83b511907a1db3f4d18c33c63d9b6c4d9e
        assertEquals("7ad4af83b511907a1db3f4d18c33c63d9b6c4d9e", MergeState.getLocalKey("f.txt"));
        // python3 -c "import hashlib;
        // print(hashlib.sha1(b'dir/sub/file.txt').hexdigest())"
        // -> a551bae145c51b08a4237c0a1eea581438bd3560
        assertEquals("a551bae145c51b08a4237c0a1eea581438bd3560", MergeState.getLocalKey("dir/sub/file.txt"));
    }

    // ---- write() error path -------------------------------------------------------------------

    @Test
    public void writeThrowsWhenLocalOrOtherMissing(@TempDir Path tempDir) {
        File stateFile = tempDir.resolve("state2").toFile();

        MergeState neither = new MergeState();
        assertThrows(IllegalStateException.class, () -> neither.write(stateFile));

        MergeState onlyLocal = new MergeState();
        onlyLocal.local = LOCAL_NODE;
        assertThrows(IllegalStateException.class, () -> onlyLocal.write(stateFile));

        MergeState onlyOther = new MergeState();
        onlyOther.other = OTHER_NODE;
        assertThrows(IllegalStateException.class, () -> onlyOther.write(stateFile));
    }

    // ---- write()/read() round trip: record-kind derivation (F / C / P) ------------------------

    @Test
    public void roundTripsRegularMergedFileRecord(@TempDir Path tempDir) throws Exception {
        MergeState ms = new MergeState();
        ms.local = LOCAL_NODE;
        ms.other = OTHER_NODE;
        ms.addMergedFile("f.txt", MergeState.getLocalKey("f.txt"), "f.txt", "f.txt",
                "3333333333333333333333333333333333333333", "f.txt",
                "4444444444444444444444444444444444444444", "l");

        File stateFile = tempDir.resolve("state2").toFile();
        ms.write(stateFile);
        MergeState read = MergeState.read(stateFile);

        assertArrayEquals(LOCAL_NODE, read.local);
        assertArrayEquals(OTHER_NODE, read.other);
        assertEquals(ms.state.get("f.txt"), read.state.get("f.txt"));
    }

    @Test
    public void roundTripsChangeDeleteConflictRecordWhenLocalKeyIsNullHex(@TempDir Path tempDir) throws Exception {
        MergeState ms = new MergeState();
        ms.local = LOCAL_NODE;
        ms.other = OTHER_NODE;
        // localKey == NULL_HEX -> deleted locally -> must derive to a 'C' (changedelete) record.
        ms.addMergedFile("gone.txt", MergeState.NULL_HEX, "gone.txt", "gone.txt",
                "3333333333333333333333333333333333333333", "gone.txt",
                "4444444444444444444444444444444444444444", "");

        File stateFile = tempDir.resolve("state2").toFile();
        ms.write(stateFile);
        MergeState read = MergeState.read(stateFile);

        assertEquals(ms.state.get("gone.txt"), read.state.get("gone.txt"));
    }

    @Test
    public void roundTripsChangeDeleteConflictRecordWhenOtherNodeIsNullHex(@TempDir Path tempDir) throws Exception {
        MergeState ms = new MergeState();
        ms.local = LOCAL_NODE;
        ms.other = OTHER_NODE;
        // otherNodeHex == NULL_HEX -> deleted remotely -> must also derive to a 'C' record.
        ms.addMergedFile("gone2.txt", MergeState.getLocalKey("gone2.txt"), "gone2.txt", "gone2.txt",
                "3333333333333333333333333333333333333333", "gone2.txt",
                MergeState.NULL_HEX, "");

        File stateFile = tempDir.resolve("state2").toFile();
        ms.write(stateFile);
        MergeState read = MergeState.read(stateFile);

        assertEquals(ms.state.get("gone2.txt"), read.state.get("gone2.txt"));
    }

    @Test
    public void roundTripsPathConflictRecord(@TempDir Path tempDir) throws Exception {
        MergeState ms = new MergeState();
        ms.local = LOCAL_NODE;
        ms.other = OTHER_NODE;
        ms.state.put("dir", Arrays.asList(MergeState.UNRESOLVED_PATH, "dir~1", "dir"));

        File stateFile = tempDir.resolve("state2").toFile();
        ms.write(stateFile);
        MergeState read = MergeState.read(stateFile);

        assertEquals(Arrays.asList(MergeState.UNRESOLVED_PATH, "dir~1", "dir"), read.state.get("dir"));
    }

    @Test
    public void roundTripsResolvedPathConflictRecord(@TempDir Path tempDir) throws Exception {
        MergeState ms = new MergeState();
        ms.local = LOCAL_NODE;
        ms.other = OTHER_NODE;
        // fields.get(0) == RESOLVED_PATH must also derive to a 'P' record (deriveKind's
        // isPathConflict check matches both 'pu' and 'pr', mirroring real hg's _makerecords check
        // against MERGE_RECORD_UNRESOLVED_PATH/MERGE_RECORD_RESOLVED_PATH).
        ms.state.put("dir", Arrays.asList(MergeState.RESOLVED_PATH, "dir~1", "dir"));

        File stateFile = tempDir.resolve("state2").toFile();
        ms.write(stateFile);
        MergeState read = MergeState.read(stateFile);

        assertEquals(Arrays.asList(MergeState.RESOLVED_PATH, "dir~1", "dir"), read.state.get("dir"));
    }

    @Test
    public void writeAndReadRoundTripsEmptyFieldListAsDefaultMergedRecord(@TempDir Path tempDir) throws Exception {
        MergeState ms = new MergeState();
        ms.local = LOCAL_NODE;
        ms.other = OTHER_NODE;
        // Neither a path conflict nor long enough (>= 7 fields) to be judged a changedelete
        // conflict -> deriveKind must fall through to the default RECORD_MERGED ('F') kind.
        ms.state.put("weird.txt", new ArrayList<>());

        File stateFile = tempDir.resolve("state2").toFile();
        ms.write(stateFile);
        MergeState read = MergeState.read(stateFile);

        assertTrue(read.hasFile("weird.txt"));
        assertTrue(read.state.get("weird.txt").isEmpty());
    }

    // ---- read() with a record type this reader does not understand ----------------------------

    @Test
    public void readSkipsCompletelyUnrecognizedRecordType(@TempDir Path tempDir) throws Exception {
        // Real hg's mergestate has other record kinds this class does not model (e.g. the legacy
        // 'm'/'D' merge-driver records) and treats any lowercase type as safely ignorable by old
        // readers. This reader is deliberately lenient about ALL unrecognized types (see the
        // comment at the end of MergeState.read): it must skip over them without throwing, while
        // still parsing the L/O records that surround them.
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        writeRawRecord(raw, 'L', NodeIdUtil.toHex(LOCAL_NODE).getBytes(StandardCharsets.US_ASCII));
        writeRawRecord(raw, 'O', NodeIdUtil.toHex(OTHER_NODE).getBytes(StandardCharsets.US_ASCII));
        writeRawRecord(raw, 'z', "whatever-unknown-payload".getBytes(StandardCharsets.UTF_8));

        File stateFile = tempDir.resolve("state2").toFile();
        Files.write(stateFile.toPath(), raw.toByteArray());

        MergeState ms = MergeState.read(stateFile);
        assertArrayEquals(LOCAL_NODE, ms.local);
        assertArrayEquals(OTHER_NODE, ms.other);
        assertTrue(ms.isEmpty());
    }

    private static void writeRawRecord(ByteArrayOutputStream out, char type, byte[] data) {
        out.write((byte) type);
        out.write((data.length >>> 24) & 0xFF);
        out.write((data.length >>> 16) & 0xFF);
        out.write((data.length >>> 8) & 0xFF);
        out.write(data.length & 0xFF);
        out.write(data, 0, data.length);
    }

    // ---- write()/read() round trip: state extras (RECORD_FILE_VALUES) -------------------------

    @Test
    public void writeSkipsEmptyExtrasButKeepsNonEmptyOnes(@TempDir Path tempDir) throws Exception {
        MergeState ms = new MergeState();
        ms.local = LOCAL_NODE;
        ms.other = OTHER_NODE;
        ms.addMergedFile("f.txt", MergeState.getLocalKey("f.txt"), "f.txt", "f.txt", MergeState.NULL_HEX,
                "f.txt", MergeState.NULL_HEX, "");
        ms.stateExtras.put("f.txt", Map.of("ancestorlinknode", "5555555555555555555555555555555555555555"));
        ms.stateExtras.put("untouched.txt", Map.of());

        File stateFile = tempDir.resolve("state2").toFile();
        ms.write(stateFile);
        MergeState read = MergeState.read(stateFile);

        assertEquals("5555555555555555555555555555555555555555",
                read.stateExtras.get("f.txt").get("ancestorlinknode"));
        assertFalse(read.stateExtras.containsKey("untouched.txt"),
                "an entry with no actual extras must not produce an on-disk record");
    }

    // ---- write()/read() round trip: labels (RECORD_LABELS) -------------------------------------

    @Test
    public void writeOmitsLabelsRecordWhenLabelsListIsEmpty(@TempDir Path tempDir) throws Exception {
        MergeState ms = new MergeState();
        ms.local = LOCAL_NODE;
        ms.other = OTHER_NODE;

        File stateFile = tempDir.resolve("state2").toFile();
        ms.write(stateFile);
        MergeState read = MergeState.read(stateFile);

        assertTrue(read.labels.isEmpty());
    }

    @Test
    public void roundTripsLabelsAndFiltersEmptySegments(@TempDir Path tempDir) throws Exception {
        MergeState ms = new MergeState();
        ms.local = LOCAL_NODE;
        ms.other = OTHER_NODE;
        // An empty label segment (e.g. an unset "base" label) must be dropped on read, matching
        // real hg's `[l for l in labels if len(l) > 0]` filter in _readrecordsv2.
        ms.labels.add("working copy");
        ms.labels.add("merge rev");
        ms.labels.add("");

        File stateFile = tempDir.resolve("state2").toFile();
        ms.write(stateFile);
        MergeState read = MergeState.read(stateFile);

        assertEquals(Arrays.asList("working copy", "merge rev"), read.labels);
    }

    // ---- clean() --------------------------------------------------------------------------------

    @Test
    public void cleanDeletesStateFileAndIsNoopWhenAlreadyAbsent(@TempDir Path tempDir) throws Exception {
        File stateFile = tempDir.resolve("state2").toFile();
        MergeState ms = new MergeState();
        ms.local = LOCAL_NODE;
        ms.other = OTHER_NODE;
        ms.write(stateFile);
        assertTrue(stateFile.exists());

        MergeState.clean(stateFile);
        assertFalse(stateFile.exists());

        // Deleting again (no file present) must not throw.
        assertDoesNotThrow(() -> MergeState.clean(stateFile));
    }
}
