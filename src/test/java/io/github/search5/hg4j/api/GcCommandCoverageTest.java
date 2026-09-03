package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage-focused tests for {@link GcCommand}, exercising branches not touched by the
 * existing end-to-end tests (PorcelainExtraCommandsTest, EscapeAndMetadataTest):
 * missing store directory, orphaned temp/backup file cleanup, empty repositories,
 * nested data/meta directory scanning, and merge (parent2) revisions.
 */
public class GcCommandCoverageTest {

    @Test
    public void testThrowsWhenStoreDirectoryMissing(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File storeDir = repo.getStoreDir();
        // Remove the store directory entirely to simulate a corrupted/missing store.
        assertTrue(storeDir.exists());
        Files.delete(storeDir.toPath());
        assertFalse(storeDir.exists());

        GcCommand gc = new GcCommand(repo);
        IOException ex = assertThrows(IOException.class, gc::call);
        assertTrue(ex.getMessage().contains("does not exist"));
    }

    @Test
    public void testCleansOrphanedBackupTmpAndJournalFiles(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File storeDir = repo.getStoreDir();

        File backupFile = new File(storeDir, "00changelog.i.backup");
        File tmpFile = new File(storeDir, "00manifest.i.tmp");
        File journalFile = new File(storeDir, "journal");
        assertTrue(backupFile.createNewFile());
        assertTrue(tmpFile.createNewFile());
        assertTrue(journalFile.createNewFile());

        GcCommand gc = new GcCommand(repo);
        String report = gc.call();

        assertFalse(backupFile.exists());
        assertFalse(tmpFile.exists());
        assertFalse(journalFile.exists());
        assertTrue(report.contains("cleaned 3 orphaned temp files"), report);
    }

    @Test
    public void testEmptyRepositoryProducesNoValidStorePaths(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // A freshly initialized repository has no 00changelog.i / 00manifest.i / data / meta yet.
        GcCommand gc = new GcCommand(repo);
        String report = gc.call();

        assertTrue(report.contains("defragmented and re-delta optimized 0 store revlogs"), report);
        assertFalse(new File(repo.getStoreDir(), "fncache").exists());
    }

    @Test
    public void testNestedDataDirectoryIsRecursivelyScanned(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File storeDir = repo.getStoreDir();

        File nestedIdx = new File(storeDir, "data/aa/bb/nested.i");
        File nestedDat = new File(storeDir, "data/aa/bb/nested.d");
        nestedIdx.getParentFile().mkdirs();
        Revlog nested = new Revlog(nestedIdx, nestedDat);
        byte[] zeroNode = new byte[20];
        byte[] content = "nested content".getBytes(StandardCharsets.UTF_8);
        nested.appendRevision(content, -1, -1, zeroNode, zeroNode, 0);

        GcCommand gc = new GcCommand(repo);
        String report = gc.call();
        assertTrue(report.contains("defragmented and re-delta optimized 1 store revlogs"), report);

        String fncache = Files.readString(new File(storeDir, "fncache").toPath());
        assertTrue(fncache.contains("data/aa/bb/nested.i"), fncache);

        repo.clearRevlogCache();
        Revlog compacted = new Revlog(nestedIdx, nestedDat);
        assertEquals(1, compacted.getRevisionCount());
        assertArrayEquals(content, compacted.getRevisionContent(0));
    }

    @Test
    public void testMetaDirectoryIsScanned(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File storeDir = repo.getStoreDir();

        // "meta" holds directory-manifest revlogs (treemanifest layout).
        File metaIdx = new File(storeDir, "meta/subdir/00manifest.i");
        File metaDat = new File(storeDir, "meta/subdir/00manifest.d");
        metaIdx.getParentFile().mkdirs();
        Revlog metaRevlog = new Revlog(metaIdx, metaDat);
        byte[] zeroNode = new byte[20];
        byte[] content = "dir manifest content".getBytes(StandardCharsets.UTF_8);
        metaRevlog.appendRevision(content, -1, -1, zeroNode, zeroNode, 0);

        GcCommand gc = new GcCommand(repo);
        String report = gc.call();
        assertTrue(report.contains("defragmented and re-delta optimized 1 store revlogs"), report);

        String fncache = Files.readString(new File(storeDir, "fncache").toPath());
        assertTrue(fncache.contains("meta/subdir/00manifest.i"), fncache);
    }

    @Test
    public void testMergeRevisionParent2IsPreservedThroughCompaction(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File storeDir = repo.getStoreDir();

        File idxFile = new File(storeDir, "data/merge.i");
        File datFile = new File(storeDir, "data/merge.d");
        idxFile.getParentFile().mkdirs();
        Revlog revlog = new Revlog(idxFile, datFile);
        byte[] zeroNode = new byte[20];

        byte[] content0 = "base content".getBytes(StandardCharsets.UTF_8);
        byte[] node0 = revlog.appendRevision(content0, -1, -1, zeroNode, zeroNode, 0);

        byte[] content1 = "branch content".getBytes(StandardCharsets.UTF_8);
        byte[] node1 = revlog.appendRevision(content1, -1, -1, zeroNode, zeroNode, 1);

        // Merge revision: parent1 = rev0, parent2 = rev1
        byte[] content2 = "merged content".getBytes(StandardCharsets.UTF_8);
        revlog.appendRevision(content2, 0, 1, node0, node1, 2);

        File fncacheFile = new File(storeDir, "fncache");
        Files.writeString(fncacheFile.toPath(), "data/merge.i\n");

        GcCommand gc = new GcCommand(repo);
        String report = gc.call();
        assertTrue(report.contains("GC / Compaction complete"), report);

        repo.clearRevlogCache();
        Revlog compacted = new Revlog(idxFile, datFile);
        assertEquals(3, compacted.getRevisionCount());
        Revlog.IndexRecord mergedRecord = compacted.getIndexRecord(2);
        assertEquals(0, mergedRecord.getParent1());
        assertEquals(1, mergedRecord.getParent2());
        assertArrayEquals(content2, compacted.getRevisionContent(2));
    }

    @Test
    public void testStoreDirAsRegularFileSkipsOrphanFileScan(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File storeDir = repo.getStoreDir();

        // Simulate a corrupted repository where .hg/store is a plain file rather than a
        // directory: File.listFiles() then returns null instead of an empty array.
        Files.delete(storeDir.toPath());
        assertTrue(storeDir.createNewFile());

        GcCommand gc = new GcCommand(repo);
        String report = gc.call();
        assertTrue(report.contains("defragmented and re-delta optimized 0 store revlogs"), report);
        assertTrue(report.contains("cleaned 0 orphaned temp files"), report);
    }

    @Test
    public void testDataPathExistsButIsNotDirectory(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File storeDir = repo.getStoreDir();

        // "data" exists but as a regular file, not a directory: must not be scanned/recursed into.
        File dataAsFile = new File(storeDir, "data");
        assertTrue(dataAsFile.createNewFile());

        GcCommand gc = new GcCommand(repo);
        String report = gc.call();
        assertTrue(report.contains("defragmented and re-delta optimized 0 store revlogs"), report);
        assertTrue(dataAsFile.isFile());
    }

    @Test
    public void testMetaPathExistsButIsNotDirectory(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File storeDir = repo.getStoreDir();

        // "meta" exists but as a regular file, not a directory: must not be scanned/recursed into.
        File metaAsFile = new File(storeDir, "meta");
        assertTrue(metaAsFile.createNewFile());

        GcCommand gc = new GcCommand(repo);
        String report = gc.call();
        assertTrue(report.contains("defragmented and re-delta optimized 0 store revlogs"), report);
        assertTrue(metaAsFile.isFile());
    }

    @Test
    public void testZeroRevisionIndexFileDoesNotCrashCompaction(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File storeDir = repo.getStoreDir();

        // A zero-byte index file (e.g. leftover from an aborted write, or an empty
        // revlog) has 0 revisions but still exists as a ".i" file that GC discovers.
        File emptyIdx = new File(storeDir, "data/empty.i");
        emptyIdx.getParentFile().mkdirs();
        assertTrue(emptyIdx.createNewFile());
        assertEquals(0, emptyIdx.length());

        GcCommand gc = new GcCommand(repo);
        String report = gc.call();
        assertTrue(report.contains("GC / Compaction complete"), report);
        assertTrue(emptyIdx.exists());
        assertEquals(0, emptyIdx.length());

        String fncache = Files.readString(new File(storeDir, "fncache").toPath());
        assertTrue(fncache.contains("data/empty.i"), fncache);
    }
}
