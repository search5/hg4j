package com.github.search5.hg4j.treewalk;

import com.github.search5.hg4j.api.Hg;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dedicated coverage tests for {@link WorkingDirTreeIterator}, targeting the
 * out-of-range fallback branches of {@code getEntryPath()}, {@code isExecutable()}
 * and {@code getEntryState()} (index == -1 before any {@code next()} call, and
 * index == entries.size() after exhaustion) plus the never-otherwise-invoked
 * {@code getEntryNodeId()} method.
 */
public class WorkingDirTreeIteratorCoverageTest {

    private File tempRepoDir;

    @BeforeEach
    public void setUp() throws Exception {
        tempRepoDir = Files.createTempDirectory("hg4j_workingdirtreeiterator_test_").toFile();
        Hg.init().setDirectory(tempRepoDir).call();
    }

    @AfterEach
    public void tearDown() {
        deleteDirRecursively(tempRepoDir);
    }

    @Test
    public void testAccessorsBeforeFirstNextReturnFallbackValues() throws Exception {
        File file = new File(tempRepoDir, "a.txt");
        Files.writeString(file.toPath(), "A", StandardCharsets.UTF_8);

        try (Hg hg = Hg.open(tempRepoDir)) {
            hg.add().addFile("a.txt").call();
            hg.commit().setAuthor("tester").setMessage("Commit 1").call();

            WorkingDirTreeIterator it = new WorkingDirTreeIterator(hg.getRepository());
            it.reset();

            // index == -1 here: index >= 0 is false, so every accessor must take
            // the out-of-range fallback branch rather than the entries.get(index) path.
            assertNull(it.getEntryPath());
            assertFalse(it.isExecutable());
            assertEquals('?', it.getEntryState());
            assertNull(it.getEntryNodeId());
        }
    }

    @Test
    public void testAccessorsAfterExhaustionReturnFallbackValues() throws Exception {
        File file = new File(tempRepoDir, "a.txt");
        Files.writeString(file.toPath(), "A", StandardCharsets.UTF_8);

        try (Hg hg = Hg.open(tempRepoDir)) {
            hg.add().addFile("a.txt").call();
            hg.commit().setAuthor("tester").setMessage("Commit 1").call();

            WorkingDirTreeIterator it = new WorkingDirTreeIterator(hg.getRepository());
            it.reset();

            assertTrue(it.next());
            assertEquals("a.txt", it.getEntryPath());
            assertNull(it.getEntryNodeId());

            // Advancing past the last entry sets index = entries.size(), so
            // index < entries.size() is now false: the out-of-range fallback
            // branch must be taken again, this time via the "past the end" path.
            assertFalse(it.next());

            assertNull(it.getEntryPath());
            assertFalse(it.isExecutable());
            assertEquals('?', it.getEntryState());
            assertNull(it.getEntryNodeId());
        }
    }

    @Test
    public void testTrackedEntryMissingFromDiskSkipsExecutableCheck() throws Exception {
        File file = new File(tempRepoDir, "a.txt");
        Files.writeString(file.toPath(), "A", StandardCharsets.UTF_8);

        try (Hg hg = Hg.open(tempRepoDir)) {
            hg.add().addFile("a.txt").call();
            hg.commit().setAuthor("tester").setMessage("Commit 1").call();

            // Delete the file directly from disk without an `hg remove`, so the
            // dirstate still tracks it (dEntry != null) but diskFile.exists() is
            // false: this exercises the tracked-branch's "file missing from disk"
            // fallthrough in loadEntries() (WorkingDirTreeIterator.java:60), which
            // otherwise always short-circuits to `executable = diskFile.canExecute()`.
            assertTrue(file.delete());

            WorkingDirTreeIterator it = new WorkingDirTreeIterator(hg.getRepository());
            it.reset();

            assertTrue(it.next());
            assertEquals("a.txt", it.getEntryPath());
            assertFalse(it.isExecutable());
        }
    }

    @Test
    public void testTrackedEntryReplacedByDirectorySkipsExecutableCheck() throws Exception {
        File file = new File(tempRepoDir, "a.txt");
        Files.writeString(file.toPath(), "A", StandardCharsets.UTF_8);

        try (Hg hg = Hg.open(tempRepoDir)) {
            hg.add().addFile("a.txt").call();
            hg.commit().setAuthor("tester").setMessage("Commit 1").call();

            // Replace the tracked file with a directory of the same name: dEntry
            // != null (still tracked) and diskFile.exists() is true, but
            // diskFile.isFile() is false. This is the remaining branch outcome of
            // the tracked-branch check in loadEntries() (WorkingDirTreeIterator
            // .java:60) that a plain "file was deleted" scenario does not reach.
            assertTrue(file.delete());
            assertTrue(file.mkdir());

            WorkingDirTreeIterator it = new WorkingDirTreeIterator(hg.getRepository());
            it.reset();

            assertTrue(it.next());
            assertEquals("a.txt", it.getEntryPath());
            assertFalse(it.isExecutable());
        }
    }

    private void deleteDirRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirRecursively(child);
                }
            }
        }
        file.delete();
    }
}
