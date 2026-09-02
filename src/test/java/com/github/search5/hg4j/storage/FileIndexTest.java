package com.github.search5.hg4j.storage;

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
 * the write path, round-tripped through hg4j's own reader.
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
