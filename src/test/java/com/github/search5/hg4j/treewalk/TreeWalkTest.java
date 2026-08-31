package com.github.search5.hg4j.treewalk;

import com.github.search5.hg4j.api.Hg;
import com.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TreeWalkTest {

    private File tempRepoDir;
    private HgRepository repository;

    @BeforeEach
    public void setUp() throws Exception {
        tempRepoDir = Files.createTempDirectory("hg4j_treewalk_test_").toFile();
        repository = Hg.init().setDirectory(tempRepoDir).call();
        assertNotNull(repository);
    }

    @AfterEach
    public void tearDown() {
        deleteDirRecursively(tempRepoDir);
    }

    @Test
    public void testManifestWalkAcrossRevisions() throws Exception {
        try (Hg hg = Hg.open(tempRepoDir)) {
            // 1. Commit 1: sample.txt
            File file1 = new File(tempRepoDir, "sample.txt");
            Files.writeString(file1.toPath(), "Content 1", StandardCharsets.UTF_8);
            hg.add().addFile("sample.txt").call();
            byte[] rev0 = hg.commit().setAuthor("tester <test@example.com>").setMessage("Commit 1").call();
            assertNotNull(rev0);
            assertEquals(20, rev0.length);

            // 2. Commit 2: another.txt
            File file2 = new File(tempRepoDir, "another.txt");
            Files.writeString(file2.toPath(), "Content 2", StandardCharsets.UTF_8);
            hg.add().addFile("another.txt").call();
            byte[] rev1 = hg.commit().setAuthor("tester <test@example.com>").setMessage("Commit 2").call();
            assertNotNull(rev1);
            assertEquals(20, rev1.length);

            // 3. Verify ManifestWalk on Rev 0
            ManifestWalk walk0 = hg.walkManifest("0");
            assertTrue(walk0.next());
            ManifestWalk.Entry entry0 = walk0.getEntry();
            assertEquals("sample.txt", entry0.getPath());
            assertFalse(entry0.isExecutable());
            assertNotNull(entry0.getNodeId());
            assertFalse(walk0.next());

            // 4. Verify ManifestWalk on Rev 1
            ManifestWalk walk1 = hg.walkManifest("1");
            List<ManifestWalk.Entry> entries1 = walk1.getEntries();
            assertEquals(2, entries1.size());
            
            // Alphabetical sorting check
            assertEquals("another.txt", entries1.get(0).getPath());
            assertEquals("sample.txt", entries1.get(1).getPath());

            // Check individual entries via next()
            walk1.reset();
            assertTrue(walk1.next());
            assertEquals("another.txt", walk1.getEntry().getPath());
            assertTrue(walk1.next());
            assertEquals("sample.txt", walk1.getEntry().getPath());
            assertFalse(walk1.next());
        }
    }

    @Test
    public void testWorkingDirWalk() throws Exception {
        try (Hg hg = Hg.open(tempRepoDir)) {
            // 1. Commit sample.txt
            File file1 = new File(tempRepoDir, "sample.txt");
            Files.writeString(file1.toPath(), "Normal Content", StandardCharsets.UTF_8);
            hg.add().addFile("sample.txt").call();
            hg.commit().setAuthor("tester <test@example.com>").setMessage("Commit 1").call();

            // 2. Add removed.txt then remove it
            File file2 = new File(tempRepoDir, "removed.txt");
            Files.writeString(file2.toPath(), "To be removed", StandardCharsets.UTF_8);
            hg.add().addFile("removed.txt").call();
            hg.commit().setAuthor("tester <test@example.com>").setMessage("Commit 2").call();
            hg.remove().setFile("removed.txt").call(); // State 'r', physically deleted

            // 3. Create untracked file
            File file3 = new File(tempRepoDir, "untracked.txt");
            Files.writeString(file3.toPath(), "Untracked", StandardCharsets.UTF_8);

            // 4. Verify WorkingDirWalk
            WorkingDirWalk walk = hg.walkWorkingDir();
            List<WorkingDirWalk.Entry> entries = walk.getEntries();
            
            // Alphabetical: "removed.txt" -> "sample.txt" -> "untracked.txt"
            assertEquals(3, entries.size());

            // removed.txt
            WorkingDirWalk.Entry e1 = entries.get(0);
            assertEquals("removed.txt", e1.getPath());
            assertEquals('r', e1.getState());
            assertFalse(e1.getFile().exists());

            // sample.txt
            WorkingDirWalk.Entry e2 = entries.get(1);
            assertEquals("sample.txt", e2.getPath());
            assertEquals('n', e2.getState());
            assertTrue(e2.getFile().exists());
            assertEquals("Normal Content".length(), e2.getSize());

            // untracked.txt
            WorkingDirWalk.Entry e3 = entries.get(2);
            assertEquals("untracked.txt", e3.getPath());
            assertEquals('?', e3.getState());
            assertTrue(e3.getFile().exists());

            // Test Iterator pattern
            walk.reset();
            assertTrue(walk.next());
            assertEquals("removed.txt", walk.getEntry().getPath());
            assertTrue(walk.next());
            assertEquals("sample.txt", walk.getEntry().getPath());
            assertTrue(walk.next());
            assertEquals("untracked.txt", walk.getEntry().getPath());
            assertFalse(walk.next());
        }
    }

    @Test
    public void testTreeWalkParallelManifests() throws Exception {
        try (Hg hg = Hg.open(tempRepoDir)) {
            // Commit 1: a.txt, c.txt
            File fileA = new File(tempRepoDir, "a.txt");
            Files.writeString(fileA.toPath(), "Content A", StandardCharsets.UTF_8);
            File fileC = new File(tempRepoDir, "c.txt");
            Files.writeString(fileC.toPath(), "Content C", StandardCharsets.UTF_8);
            hg.add().addFile("a.txt").addFile("c.txt").call();
            hg.commit().setAuthor("tester").setMessage("Commit 1").call();

            // Commit 2: b.txt, c.txt
            hg.remove().setFile("a.txt").call();
            File fileB = new File(tempRepoDir, "b.txt");
            Files.writeString(fileB.toPath(), "Content B", StandardCharsets.UTF_8);
            hg.add().addFile("b.txt").call();
            hg.commit().setAuthor("tester").setMessage("Commit 2").call();

            // Create TreeWalk with two manifest iterators: Rev 0 (a, c) and Rev 1 (b, c)
            TreeWalk walk = new TreeWalk();
            walk.addTree(new ManifestTreeIterator(repository, "0"));
            walk.addTree(new ManifestTreeIterator(repository, "1"));

            // 1. Next should yield "a.txt"
            assertTrue(walk.next());
            assertEquals("a.txt", walk.getPath());
            assertTrue(walk.isTracked(0));  // Present in Rev 0
            assertFalse(walk.isTracked(1)); // Missing in Rev 1

            // 2. Next should yield "b.txt"
            assertTrue(walk.next());
            assertEquals("b.txt", walk.getPath());
            assertFalse(walk.isTracked(0)); // Missing in Rev 0
            assertTrue(walk.isTracked(1));  // Present in Rev 1

            // 3. Next should yield "c.txt"
            assertTrue(walk.next());
            assertEquals("c.txt", walk.getPath());
            assertTrue(walk.isTracked(0));  // Present in Rev 0
            assertTrue(walk.isTracked(1));  // Present in Rev 1

            assertFalse(walk.next());
        }
    }

    @Test
    public void testTreeWalkManifestAndWorkingDir() throws Exception {
        try (Hg hg = Hg.open(tempRepoDir)) {
            // Commit 1: sample.txt
            File file1 = new File(tempRepoDir, "sample.txt");
            Files.writeString(file1.toPath(), "Content 1", StandardCharsets.UTF_8);
            hg.add().addFile("sample.txt").call();
            hg.commit().setAuthor("tester").setMessage("Commit 1").call();

            // Edit sample.txt, create untracked.txt
            Files.writeString(file1.toPath(), "Content Edited", StandardCharsets.UTF_8);
            File file2 = new File(tempRepoDir, "untracked.txt");
            Files.writeString(file2.toPath(), "Untracked", StandardCharsets.UTF_8);

            TreeWalk walk = new TreeWalk();
            walk.addTree(new ManifestTreeIterator(repository, "0"));
            walk.addTree(new WorkingDirTreeIterator(repository));

            // 1. sample.txt
            assertTrue(walk.next());
            assertEquals("sample.txt", walk.getPath());
            assertTrue(walk.isTracked(0));
            assertTrue(walk.isTracked(1));
            assertEquals('n', walk.getState(1));

            // 2. untracked.txt
            assertTrue(walk.next());
            assertEquals("untracked.txt", walk.getPath());
            assertFalse(walk.isTracked(0));
            assertTrue(walk.isTracked(1));
            assertEquals('?', walk.getState(1));

            assertFalse(walk.next());
        }
    }

    @Test
    public void testTreeWalkWithSparsePathFilter() throws Exception {
        try (Hg hg = Hg.open(tempRepoDir)) {
            // 1. Create directory structure
            File srcMainDir = new File(tempRepoDir, "src/main/java");
            srcMainDir.mkdirs();
            File srcTestDir = new File(tempRepoDir, "src/test/java");
            srcTestDir.mkdirs();
            File docDir = new File(tempRepoDir, "doc");
            docDir.mkdirs();

            Files.writeString(new File(srcMainDir, "a.java").toPath(), "class A {}", StandardCharsets.UTF_8);
            Files.writeString(new File(srcTestDir, "b.java").toPath(), "class B {}", StandardCharsets.UTF_8);
            Files.writeString(new File(docDir, "readme.md").toPath(), "Readme", StandardCharsets.UTF_8);

            hg.add().addFile("src/main/java/a.java")
                    .addFile("src/test/java/b.java")
                    .addFile("doc/readme.md").call();
            hg.commit().setAuthor("tester").setMessage("Commit 1").call();

            // 2. Create TreeWalk and mount SparsePathFilter (matching only src/main/**)
            TreeWalk walk = new TreeWalk();
            walk.addTree(new ManifestTreeIterator(repository, "0"));
            
            SparsePathFilter filter = new SparsePathFilter("src/main/**");
            walk.setFilter(filter);

            // 3. Traversal must skip doc/readme.md and src/test/java/b.java and only yield src/main/java/a.java
            assertTrue(walk.next());
            assertEquals("src/main/java/a.java", walk.getPath());
            assertFalse(walk.next());
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
