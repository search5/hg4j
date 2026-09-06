package io.github.search5.hg4j.treewalk;

import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dedicated coverage tests for {@link WorkingDirWalk}, targeting branches not
 * touched by {@link TreeWalkTest#testWorkingDirWalk}: the {@code Entry} executable
 * and lastModified accessors, {@code getEntry()} before any {@code next()} call and
 * after {@code reset()}, the lazily-loaded {@code AbstractList} returned by
 * {@code getEntries()} (its incremental {@code get()} path, its
 * {@code IndexOutOfBoundsException}, and its {@code iterator()} override), and the
 * standalone {@code lazyEntries()} streaming iterator.
 */
public class WorkingDirWalkTest {

    private File tempRepoDir;
    private HgRepository repository;

    @BeforeEach
    public void setUp() throws Exception {
        tempRepoDir = Files.createTempDirectory("hg4j_workingdirwalk_test_").toFile();
        repository = Hg.init().setDirectory(tempRepoDir).call();
        assertNotNull(repository);
    }

    @AfterEach
    public void tearDown() {
        deleteDirRecursively(tempRepoDir);
    }

    @Test
    public void testEntryExecutableAndLastModifiedAccessors() throws Exception {
        try (Hg hg = Hg.open(tempRepoDir)) {
            File execFile = new File(tempRepoDir, "run.sh");
            Files.writeString(execFile.toPath(), "#!/bin/sh\necho hi", StandardCharsets.UTF_8);
            assertTrue(execFile.setExecutable(true, false));

            File plainFile = new File(tempRepoDir, "plain.txt");
            Files.writeString(plainFile.toPath(), "plain content", StandardCharsets.UTF_8);

            hg.add().addFile("run.sh").addFile("plain.txt").call();
            hg.commit().setAuthor("tester").setMessage("Commit 1").call();

            WorkingDirWalk walk = hg.walkWorkingDir();
            List<WorkingDirWalk.Entry> entries = walk.getEntries();
            assertEquals(2, entries.size());

            WorkingDirWalk.Entry plainEntry = entries.get(0);
            assertEquals("plain.txt", plainEntry.getPath());
            assertFalse(plainEntry.isExecutable());
            assertEquals(plainFile.lastModified() / 1000, plainEntry.getLastModified());
            assertTrue(plainEntry.getLastModified() > 0);

            WorkingDirWalk.Entry execEntry = entries.get(1);
            assertEquals("run.sh", execEntry.getPath());
            assertTrue(execEntry.isExecutable());
            assertEquals(execFile.lastModified() / 1000, execEntry.getLastModified());
        }
    }

    @Test
    public void testGetEntryThrowsBeforeAnyNextCall() throws Exception {
        try (Hg hg = Hg.open(tempRepoDir)) {
            File file1 = new File(tempRepoDir, "a.txt");
            Files.writeString(file1.toPath(), "A", StandardCharsets.UTF_8);
            hg.add().addFile("a.txt").call();
            hg.commit().setAuthor("tester").setMessage("Commit 1").call();

            WorkingDirWalk walk = hg.walkWorkingDir();
            assertThrows(NoSuchElementException.class, walk::getEntry);
        }
    }

    @Test
    public void testGetEntryThrowsAfterReset() throws Exception {
        try (Hg hg = Hg.open(tempRepoDir)) {
            File file1 = new File(tempRepoDir, "a.txt");
            Files.writeString(file1.toPath(), "A", StandardCharsets.UTF_8);
            hg.add().addFile("a.txt").call();
            hg.commit().setAuthor("tester").setMessage("Commit 1").call();

            WorkingDirWalk walk = hg.walkWorkingDir();
            assertTrue(walk.next());
            assertEquals("a.txt", walk.getEntry().getPath());

            walk.reset();
            assertThrows(NoSuchElementException.class, walk::getEntry);

            assertTrue(walk.next());
            assertEquals("a.txt", walk.getEntry().getPath());
        }
    }

    @Test
    public void testGetEntriesFreshListIncrementalGetLoadsOnlyAsNeeded() throws Exception {
        try (Hg hg = Hg.open(tempRepoDir)) {
            File fileA = new File(tempRepoDir, "a.txt");
            File fileB = new File(tempRepoDir, "b.txt");
            File fileC = new File(tempRepoDir, "c.txt");
            Files.writeString(fileA.toPath(), "A", StandardCharsets.UTF_8);
            Files.writeString(fileB.toPath(), "B", StandardCharsets.UTF_8);
            Files.writeString(fileC.toPath(), "C", StandardCharsets.UTF_8);
            hg.add().addFile("a.txt").addFile("b.txt").addFile("c.txt").call();
            hg.commit().setAuthor("tester").setMessage("Commit 1").call();

            WorkingDirWalk walk = hg.walkWorkingDir();
            // Calling get() directly on a freshly obtained list -- without first
            // calling size() -- forces the incremental "grow the cache one element
            // at a time" loop inside AbstractList.get(int) to actually execute.
            List<WorkingDirWalk.Entry> entries = walk.getEntries();
            assertEquals("b.txt", entries.get(1).getPath());
            assertEquals("a.txt", entries.get(0).getPath());
            assertEquals("c.txt", entries.get(2).getPath());
        }
    }

    @Test
    public void testGetEntriesGetThrowsIndexOutOfBoundsPastEnd() throws Exception {
        try (Hg hg = Hg.open(tempRepoDir)) {
            File fileA = new File(tempRepoDir, "a.txt");
            Files.writeString(fileA.toPath(), "A", StandardCharsets.UTF_8);
            hg.add().addFile("a.txt").call();
            hg.commit().setAuthor("tester").setMessage("Commit 1").call();

            WorkingDirWalk walk = hg.walkWorkingDir();
            List<WorkingDirWalk.Entry> entries = walk.getEntries();
            assertThrows(IndexOutOfBoundsException.class, () -> entries.get(5));
        }
    }

    @Test
    public void testGetEntriesIteratorOverrideUsedByForEach() throws Exception {
        try (Hg hg = Hg.open(tempRepoDir)) {
            File fileA = new File(tempRepoDir, "a.txt");
            File fileB = new File(tempRepoDir, "b.txt");
            Files.writeString(fileA.toPath(), "A", StandardCharsets.UTF_8);
            Files.writeString(fileB.toPath(), "B", StandardCharsets.UTF_8);
            hg.add().addFile("a.txt").addFile("b.txt").call();
            hg.commit().setAuthor("tester").setMessage("Commit 1").call();

            WorkingDirWalk walk = hg.walkWorkingDir();
            List<String> paths = new ArrayList<>();
            // A for-each loop calls List.iterator(), which the anonymous
            // AbstractList overrides to return a fresh lazyEntries() iterator
            // rather than driving its own cache.
            for (WorkingDirWalk.Entry entry : walk.getEntries()) {
                paths.add(entry.getPath());
            }
            assertEquals(List.of("a.txt", "b.txt"), paths);
        }
    }

    @Test
    public void testLazyEntriesStreamsWithoutPreloading() throws Exception {
        try (Hg hg = Hg.open(tempRepoDir)) {
            File fileA = new File(tempRepoDir, "a.txt");
            File fileB = new File(tempRepoDir, "b.txt");
            Files.writeString(fileA.toPath(), "A", StandardCharsets.UTF_8);
            Files.writeString(fileB.toPath(), "B", StandardCharsets.UTF_8);
            hg.add().addFile("a.txt").addFile("b.txt").call();
            hg.commit().setAuthor("tester").setMessage("Commit 1").call();

            WorkingDirWalk walk = hg.walkWorkingDir();
            Iterator<WorkingDirWalk.Entry> it = walk.lazyEntries();

            assertTrue(it.hasNext());
            WorkingDirWalk.Entry first = it.next();
            assertEquals("a.txt", first.getPath());

            assertTrue(it.hasNext());
            WorkingDirWalk.Entry second = it.next();
            assertEquals("b.txt", second.getPath());

            assertFalse(it.hasNext());
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
