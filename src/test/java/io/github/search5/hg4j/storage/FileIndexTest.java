package io.github.search5.hg4j.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code fileindex-v1} docket/list/meta/tree parsing and rebuild-writing — verified against a real
 * hg-generated fixture (see src/test/resources/fixtures/revlogv2-general/README.md, generated with
 * the Rust-extension-enabled Mercurial build in {@code docker/hg-rust-7.2.4/Dockerfile}) and, for
 * the write path, round-tripped through hg4j's own reader (hg4j-internal only, below).
 *
 * <p><b>2026-09-04</b>: live write-direction verification against a REAL Rust-enabled {@code hg}
 * (not just hg4j's own reader) for this exact combination ({@code fileindex-v1}, which real hg
 * always bundles together with {@code exp-revlogv2.2}/{@code persistent-nodemap} — confirmed
 * empirically, see {@code llm-wiki/decisions/exhaustive-interop-matrix-plan.md} §1-1) now lives in
 * {@link RevlogV2GeneralParserTest#realHgRustAcceptsHg4jWrittenGeneralV2Repository} — a single live
 * hg4j-writes/real-hg-verifies repo exercises the fileindex list/meta/tree files this class parses
 * too, since real hg always writes all three (general-v2 index, fileindex, nodemap) together as one
 * unit. Not duplicated here to avoid a second, redundant Docker container/write-corruption-avoidance
 * harness for the exact same underlying repository state.
 */
@DisplayName("fileindex-v1 read/write")
public class FileIndexTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("reads real hg-generated fileindex docket/list/meta/tree")
    void readsRealFixture() throws IOException {
        File storeDir = copyFixtureCompanionsRenamedByRealUids();
        Set<String> paths = FileIndex.readTrackedPaths(storeDir);
        assertEquals(new LinkedHashSet<>(Arrays.asList("a.txt", "sub/b.txt")), paths);
    }

    @Test
    @DisplayName("no fileindex file present -> empty set, no error")
    void missingFileIndexReadsAsEmpty() throws IOException {
        File storeDir = tempDir.toFile();
        assertTrue(FileIndex.readTrackedPaths(storeDir).isEmpty());
    }

    @Test
    @DisplayName("truncated docket (shorter than the fixed 60-byte header) is rejected")
    void truncatedDocketThrowsCorruptData() throws IOException {
        File storeDir = tempDir.toFile();
        Files.write(new File(storeDir, "fileindex").toPath(), new byte[10]);
        assertThrows(io.github.search5.hg4j.errors.HgCorruptDataException.class,
                () -> FileIndex.snapshot(storeDir));
    }

    @Test
    @DisplayName("writeTrackedPaths with enough paths forces GrowableBuffer past its initial 256-byte capacity")
    void writeTrackedPathsWithManyPathsGrowsInternalBuffer() throws IOException {
        File storeDir = tempDir.toFile();
        List<String> manyPaths = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            manyPaths.add("some/reasonably/long/directory/structure/file" + i + ".txt");
        }
        FileIndex.writeTrackedPaths(storeDir, manyPaths);
        assertEquals(new LinkedHashSet<>(manyPaths), FileIndex.readTrackedPaths(storeDir));
    }

    @Test
    @DisplayName("writeTrackedPaths rejects an empty-string path (TrieBuilder.insert's empty-path guard)")
    void writeTrackedPathsRejectsEmptyStringPath() {
        File storeDir = tempDir.toFile();
        assertThrows(IllegalArgumentException.class,
                () -> FileIndex.writeTrackedPaths(storeDir, List.of("")));
    }

    @Test
    @DisplayName("writeTrackedPaths(emptyList) over an existing fileindex actually rewrites it to empty, unlike the no-fileindex-yet no-op")
    void writeTrackedPathsWithEmptyListOverExistingFileIndexRewritesToEmpty() throws IOException {
        File storeDir = tempDir.toFile();
        FileIndex.writeTrackedPaths(storeDir, Arrays.asList("a.txt", "b.txt"));
        assertTrue(new File(storeDir, "fileindex").exists());

        FileIndex.writeTrackedPaths(storeDir, List.of());

        assertTrue(new File(storeDir, "fileindex").exists(), "an empty rewrite must still leave a (now-empty) fileindex behind");
        assertTrue(FileIndex.readTrackedPaths(storeDir).isEmpty());
    }

    @Test
    @DisplayName("readTrackedPaths throws when a companion file the docket references is missing from disk")
    void readTrackedPathsThrowsWhenCompanionFileMissing() throws IOException {
        File storeDir = tempDir.toFile();
        FileIndex.writeTrackedPaths(storeDir, Arrays.asList("a.txt", "b.txt"));

        File[] metaFiles = storeDir.listFiles((dir, name) -> name.startsWith("fileindex-meta."));
        assertNotNull(metaFiles);
        assertEquals(1, metaFiles.length);
        assertTrue(metaFiles[0].delete());

        assertThrows(io.github.search5.hg4j.errors.HgCorruptDataException.class,
                () -> FileIndex.readTrackedPaths(storeDir));
    }

    @Test
    @DisplayName("readTrackedPaths throws when a companion file on disk is shorter than the docket's declared size")
    void readTrackedPathsThrowsWhenCompanionFileTruncated() throws IOException {
        File storeDir = tempDir.toFile();
        FileIndex.writeTrackedPaths(storeDir, Arrays.asList("a.txt", "b.txt"));

        File[] metaFiles = storeDir.listFiles((dir, name) -> name.startsWith("fileindex-meta."));
        assertNotNull(metaFiles);
        assertEquals(1, metaFiles.length);
        byte[] full = Files.readAllBytes(metaFiles[0].toPath());
        Files.write(metaFiles[0].toPath(), Arrays.copyOf(full, full.length - 1));

        assertThrows(io.github.search5.hg4j.errors.HgCorruptDataException.class,
                () -> FileIndex.readTrackedPaths(storeDir));
    }

    @Test
    @DisplayName("readTrackedPaths tolerates a companion file padded longer than the docket's declared size")
    void readTrackedPathsToleratesPaddedCompanionFile() throws IOException {
        File storeDir = tempDir.toFile();
        FileIndex.writeTrackedPaths(storeDir, Arrays.asList("a.txt", "b.txt"));

        File[] listFiles = storeDir.listFiles((dir, name) -> name.startsWith("fileindex-list."));
        assertNotNull(listFiles);
        assertEquals(1, listFiles.length);
        byte[] full = Files.readAllBytes(listFiles[0].toPath());
        byte[] padded = Arrays.copyOf(full, full.length + 16); // extra trailing garbage bytes
        Files.write(listFiles[0].toPath(), padded);

        assertEquals(new LinkedHashSet<>(Arrays.asList("a.txt", "b.txt")), FileIndex.readTrackedPaths(storeDir));
    }

    @Test
    @DisplayName("docket with a wrong magic marker is rejected")
    void wrongMarkerDocketThrowsCorruptData() throws IOException {
        File storeDir = tempDir.toFile();
        // 60 bytes total, correct length but the first 12 bytes are not "fileindex-v1".
        byte[] bogus = new byte[60];
        System.arraycopy("not-the-marker".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, bogus, 0, 12);
        Files.write(new File(storeDir, "fileindex").toPath(), bogus);
        assertThrows(io.github.search5.hg4j.errors.HgCorruptDataException.class,
                () -> FileIndex.snapshot(storeDir));
    }

    @Test
    @DisplayName("write-then-read round trip preserves a simple path set")
    void writeThenReadRoundTrip() throws IOException {
        File storeDir = Files.createDirectory(tempDir.resolve("store")).toFile();
        List<String> paths = Arrays.asList("a.txt", "sub/b.txt", "sub/c.txt");
        FileIndex.writeTrackedPaths(storeDir, paths);

        Set<String> readBack = FileIndex.readTrackedPaths(storeDir);
        assertEquals(new LinkedHashSet<>(paths), readBack);
    }

    @Test
    @DisplayName("write-then-read round trip exercises trie prefix-splitting (shared prefixes, nested dirs)")
    void writeThenReadRoundTripWithSharedPrefixes() throws IOException {
        File storeDir = Files.createDirectory(tempDir.resolve("store")).toFile();
        List<String> paths = Arrays.asList(
                "foo", "foobar", "foo/bar", "bar", "baz", "ba",
                "dir/a", "dir/ab", "dir/sub/x", "dir/sub/y", "z"
        );
        FileIndex.writeTrackedPaths(storeDir, paths);

        Set<String> readBack = FileIndex.readTrackedPaths(storeDir);
        assertEquals(new LinkedHashSet<>(paths), readBack);
    }

    @Test
    @DisplayName("re-writing replaces the whole tracked set and drops stale companion files")
    void rewriteReplacesEntireSetAndCleansUpOldCompanionFiles() throws IOException {
        File storeDir = Files.createDirectory(tempDir.resolve("store")).toFile();
        FileIndex.writeTrackedPaths(storeDir, Arrays.asList("a.txt", "b.txt"));
        String[] firstGenCompanions = storeDir.list((dir, name) -> name.startsWith("fileindex-"));
        assertNotNull(firstGenCompanions);
        assertEquals(3, firstGenCompanions.length); // list, meta, tree

        FileIndex.writeTrackedPaths(storeDir, Arrays.asList("a.txt", "c.txt"));
        Set<String> readBack = FileIndex.readTrackedPaths(storeDir);
        assertEquals(new LinkedHashSet<>(Arrays.asList("a.txt", "c.txt")), readBack);

        String[] secondGenCompanions = storeDir.list((dir, name) -> name.startsWith("fileindex-"));
        assertNotNull(secondGenCompanions);
        assertEquals(3, secondGenCompanions.length, "stale companion files from the previous generation must be removed");
        for (String name : firstGenCompanions) {
            assertFalse(Arrays.asList(secondGenCompanions).contains(name), "old companion file should have been deleted: " + name);
        }
    }

    @Test
    @DisplayName("writing an empty path set when no fileindex exists yet is a no-op")
    void writingEmptySetWithNoExistingFileIndexIsNoOp() throws IOException {
        File storeDir = Files.createDirectory(tempDir.resolve("store")).toFile();
        FileIndex.writeTrackedPaths(storeDir, java.util.Collections.emptyList());
        assertFalse(new File(storeDir, "fileindex").exists());
    }

    @Test
    @DisplayName("snapshot+restore rolls back a later writeTrackedPaths, including deleted old companion files")
    void snapshotRestoreRollsBackLaterWrite() throws IOException {
        File storeDir = Files.createDirectory(tempDir.resolve("store")).toFile();
        FileIndex.writeTrackedPaths(storeDir, Arrays.asList("a.txt", "b.txt"));
        String[] gen1Companions = storeDir.list((dir, name) -> name.startsWith("fileindex-"));
        assertNotNull(gen1Companions);

        FileIndex.Snapshot snapshot = FileIndex.snapshot(storeDir);

        // Simulate a transaction that got further before failing: another write happened on top.
        FileIndex.writeTrackedPaths(storeDir, Arrays.asList("a.txt", "b.txt", "c.txt", "sub/d.txt"));
        assertEquals(new LinkedHashSet<>(Arrays.asList("a.txt", "b.txt", "c.txt", "sub/d.txt")),
                FileIndex.readTrackedPaths(storeDir));

        FileIndex.restore(storeDir, snapshot);

        assertEquals(new LinkedHashSet<>(Arrays.asList("a.txt", "b.txt")), FileIndex.readTrackedPaths(storeDir));
        String[] restoredCompanions = storeDir.list((dir, name) -> name.startsWith("fileindex-"));
        assertNotNull(restoredCompanions);
        assertEquals(new java.util.HashSet<>(Arrays.asList(gen1Companions)), new java.util.HashSet<>(Arrays.asList(restoredCompanions)),
                "restore should bring back exactly the pre-snapshot companion files and remove the newer generation's");
    }

    @Test
    @DisplayName("snapshot of a store with no fileindex yet, then restore, leaves no fileindex behind")
    void snapshotOfNoFileIndexThenRestoreLeavesNoFileIndex() throws IOException {
        File storeDir = Files.createDirectory(tempDir.resolve("store")).toFile();
        FileIndex.Snapshot snapshot = FileIndex.snapshot(storeDir);
        assertTrue(FileIndex.readTrackedPaths(storeDir).isEmpty());

        FileIndex.writeTrackedPaths(storeDir, Arrays.asList("a.txt"));
        assertTrue(new File(storeDir, "fileindex").exists());

        FileIndex.restore(storeDir, snapshot);
        assertFalse(new File(storeDir, "fileindex").exists());
        assertTrue(FileIndex.readTrackedPaths(storeDir).isEmpty());
    }

    @Test
    @DisplayName("snapshot() tolerates a docket that references a companion file missing from disk")
    void snapshotToleratesMissingCompanionFile() throws IOException {
        File storeDir = Files.createDirectory(tempDir.resolve("store")).toFile();
        FileIndex.writeTrackedPaths(storeDir, Arrays.asList("a.txt", "b.txt"));

        // Simulate partial/corrupted store state: the docket is intact, but one companion file
        // (e.g. deleted by a concurrent process, or a prior crash mid-cleanup) is gone.
        File[] listFiles = storeDir.listFiles((dir, name) -> name.startsWith("fileindex-tree."));
        assertNotNull(listFiles);
        assertEquals(1, listFiles.length);
        assertTrue(listFiles[0].delete());

        FileIndex.Snapshot snapshot = FileIndex.snapshot(storeDir);
        assertNotNull(snapshot);
    }

    @Test
    @DisplayName("restore() on a store that never had a fileindex at all takes the docketFile.exists()==false branch")
    void restoreOnAStoreThatNeverHadAFileIndexIsANoOp() throws IOException {
        // Distinct from snapshotOfNoFileIndexThenRestoreLeavesNoFileIndex above, which writes a
        // real fileindex between the empty snapshot and the restore call -- here restore() itself
        // is invoked while docketFile still has never existed at all.
        File storeDir = Files.createDirectory(tempDir.resolve("store")).toFile();
        FileIndex.Snapshot emptySnapshot = FileIndex.snapshot(storeDir);

        FileIndex.restore(storeDir, emptySnapshot);

        assertFalse(new File(storeDir, "fileindex").exists());
        assertTrue(FileIndex.readTrackedPaths(storeDir).isEmpty());
    }

    @Test
    @DisplayName("restoring the same snapshot twice in a row is idempotent (companion names already match, nothing to delete)")
    void restoringTheSameSnapshotTwiceIsIdempotent() throws IOException {
        // Companion UIDs are fresh random UUIDs on every write, so a companion name only ever
        // coincides between "current on-disk state" and "the snapshot being restored" when the
        // snapshot was itself the thing most recently written -- i.e. a second restore() of the
        // same snapshot, which must recognize the names already match and delete nothing.
        File storeDir = Files.createDirectory(tempDir.resolve("store")).toFile();
        FileIndex.writeTrackedPaths(storeDir, Arrays.asList("a.txt", "b.txt"));
        FileIndex.Snapshot snapshot = FileIndex.snapshot(storeDir);

        FileIndex.restore(storeDir, snapshot);
        String[] afterFirstRestore = storeDir.list((dir, name) -> name.startsWith("fileindex-"));
        FileIndex.restore(storeDir, snapshot);
        String[] afterSecondRestore = storeDir.list((dir, name) -> name.startsWith("fileindex-"));

        assertNotNull(afterFirstRestore);
        assertEquals(new java.util.HashSet<>(Arrays.asList(afterFirstRestore)),
                new java.util.HashSet<>(Arrays.asList(afterSecondRestore)));
        assertEquals(new LinkedHashSet<>(Arrays.asList("a.txt", "b.txt")), FileIndex.readTrackedPaths(storeDir));
    }

    /** Copies the checked-in fixture's simply-named {@code docket/list/meta/tree} files into a
     * temp store directory, renaming the companion files to the real {@code fileindex-{kind}.{uid}}
     * convention using the UIDs embedded in the (unmodified) real docket bytes. */
    private File copyFixtureCompanionsRenamedByRealUids() throws IOException {
        File storeDir = Files.createDirectory(tempDir.resolve("store")).toFile();
        byte[] docketBytes = readFixtureResource("docket");
        Files.write(storeDir.toPath().resolve("fileindex"), docketBytes);

        // Docket layout: 12-byte marker + 3x uint32 sizes + 3x 8-byte ascii uid, in that order.
        String listUid = new String(docketBytes, 24, 8, java.nio.charset.StandardCharsets.US_ASCII);
        String metaUid = new String(docketBytes, 32, 8, java.nio.charset.StandardCharsets.US_ASCII);
        String treeUid = new String(docketBytes, 40, 8, java.nio.charset.StandardCharsets.US_ASCII);

        Files.write(storeDir.toPath().resolve("fileindex-list." + listUid), readFixtureResource("list"));
        Files.write(storeDir.toPath().resolve("fileindex-meta." + metaUid), readFixtureResource("meta"));
        Files.write(storeDir.toPath().resolve("fileindex-tree." + treeUid), readFixtureResource("tree"));
        return storeDir;
    }

    private byte[] readFixtureResource(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/revlogv2-general/fileindex/" + name)) {
            assertNotNull(in, "fixture resource missing: " + name);
            return in.readAllBytes();
        }
    }
}
