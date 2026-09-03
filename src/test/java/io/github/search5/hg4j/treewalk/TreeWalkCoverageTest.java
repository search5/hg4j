package io.github.search5.hg4j.treewalk;

import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage-focused tests for {@link TreeWalk}, exercising branches not
 * touched by {@link TreeWalkTest}: non-recursive traversal, out-of-range tree
 * indices, symlink delegation, default values for untracked trees, and reset().
 */
public class TreeWalkCoverageTest {

    private File tempRepoDir;
    private HgRepository repository;

    @BeforeEach
    public void setUp() throws Exception {
        tempRepoDir = Files.createTempDirectory("hg4j_treewalk_cov_test_").toFile();
        repository = Hg.init().setDirectory(tempRepoDir).call();
        assertNotNull(repository);
    }

    @AfterEach
    public void tearDown() {
        deleteDirRecursively(tempRepoDir);
    }

    @Test
    public void testIsTrackedFalseBeforeFirstNext() throws Exception {
        try (Hg hg = Hg.open(tempRepoDir)) {
            File file1 = new File(tempRepoDir, "a.txt");
            Files.writeString(file1.toPath(), "A", StandardCharsets.UTF_8);
            hg.add().addFile("a.txt").call();
            hg.commit().setAuthor("tester").setMessage("Commit 1").call();

            TreeWalk walk = new TreeWalk();
            walk.addTree(new ManifestTreeIterator(repository, "0"));

            // currentPath is still null: isTracked must short-circuit to false
            // without ever dereferencing the tree's entry path.
            assertFalse(walk.isTracked(0));
            assertNull(walk.getPath());
        }
    }

    @Test
    public void testIsTrackedRejectsOutOfRangeIndices() throws Exception {
        try (Hg hg = Hg.open(tempRepoDir)) {
            File file1 = new File(tempRepoDir, "a.txt");
            Files.writeString(file1.toPath(), "A", StandardCharsets.UTF_8);
            hg.add().addFile("a.txt").call();
            hg.commit().setAuthor("tester").setMessage("Commit 1").call();

            TreeWalk walk = new TreeWalk();
            walk.addTree(new ManifestTreeIterator(repository, "0"));
            assertTrue(walk.next());
            assertEquals("a.txt", walk.getPath());

            // Negative index and index >= trees.size() must both be rejected.
            assertFalse(walk.isTracked(-1));
            assertFalse(walk.isTracked(1));
            assertFalse(walk.isTracked(99));

            // The other accessors delegate through isTracked(), so out-of-range
            // indices must yield their safe defaults rather than throwing.
            assertNull(walk.getNodeId(-1));
            assertFalse(walk.isExecutable(-1));
            assertFalse(walk.isSymlink(-1));
            assertEquals('?', walk.getState(-1));
            assertNull(walk.getNodeId(5));
            assertFalse(walk.isExecutable(5));
            assertFalse(walk.isSymlink(5));
            assertEquals('?', walk.getState(5));
        }
    }

    @Test
    public void testAccessorsReturnDefaultsWhenTreeIsUntrackedAtCurrentPath() throws Exception {
        try (Hg hg = Hg.open(tempRepoDir)) {
            // Rev 0: a.txt only. Rev 1: b.txt only (a.txt removed).
            File fileA = new File(tempRepoDir, "a.txt");
            Files.writeString(fileA.toPath(), "Content A", StandardCharsets.UTF_8);
            hg.add().addFile("a.txt").call();
            hg.commit().setAuthor("tester").setMessage("Commit 1").call();

            hg.remove().setFile("a.txt").call();
            File fileB = new File(tempRepoDir, "b.txt");
            Files.writeString(fileB.toPath(), "Content B", StandardCharsets.UTF_8);
            hg.add().addFile("b.txt").call();
            hg.commit().setAuthor("tester").setMessage("Commit 2").call();

            TreeWalk walk = new TreeWalk();
            walk.addTree(new ManifestTreeIterator(repository, "0"));
            walk.addTree(new ManifestTreeIterator(repository, "1"));

            // "a.txt": tree 0 tracks it, tree 1 does not.
            assertTrue(walk.next());
            assertEquals("a.txt", walk.getPath());
            assertTrue(walk.isTracked(0));
            assertFalse(walk.isTracked(1));

            assertNotNull(walk.getNodeId(0));
            assertNull(walk.getNodeId(1));

            assertFalse(walk.isExecutable(0));
            assertFalse(walk.isExecutable(1));

            assertEquals('n', walk.getState(0));
            assertEquals('?', walk.getState(1));

            assertFalse(walk.isSymlink(0));
            assertFalse(walk.isSymlink(1));

            // "b.txt": tree 0 does not track it, tree 1 does.
            assertTrue(walk.next());
            assertEquals("b.txt", walk.getPath());
            assertFalse(walk.isTracked(0));
            assertTrue(walk.isTracked(1));

            assertNull(walk.getNodeId(0));
            assertNotNull(walk.getNodeId(1));

            assertEquals('?', walk.getState(0));
            assertEquals('n', walk.getState(1));

            assertFalse(walk.next());
        }
    }

    @Test
    public void testIsSymlinkTrueForManifestSymlinkEntry() throws Exception {
        try (Hg hg = Hg.open(tempRepoDir)) {
            File target = new File(tempRepoDir, "target.txt");
            Files.writeString(target.toPath(), "Target content", StandardCharsets.UTF_8);

            File link = new File(tempRepoDir, "link.txt");
            Files.createSymbolicLink(link.toPath(), new File("target.txt").toPath());

            hg.add().addFile("target.txt").addFile("link.txt").call();
            hg.commit().setAuthor("tester").setMessage("Commit symlink").call();

            TreeWalk walk = new TreeWalk();
            walk.addTree(new ManifestTreeIterator(repository, "0"));

            assertTrue(walk.next());
            assertEquals("link.txt", walk.getPath());
            assertTrue(walk.isTracked(0));
            assertTrue(walk.isSymlink(0));

            assertTrue(walk.next());
            assertEquals("target.txt", walk.getPath());
            assertFalse(walk.isSymlink(0));

            assertFalse(walk.next());
        }
    }

    @Test
    public void testIsSymlinkFalseForNonManifestTreeIterator() throws Exception {
        try (Hg hg = Hg.open(tempRepoDir)) {
            File file1 = new File(tempRepoDir, "sample.txt");
            Files.writeString(file1.toPath(), "Content", StandardCharsets.UTF_8);
            hg.add().addFile("sample.txt").call();
            hg.commit().setAuthor("tester").setMessage("Commit 1").call();

            TreeWalk walk = new TreeWalk();
            walk.addTree(new WorkingDirTreeIterator(repository));

            assertTrue(walk.next());
            assertEquals("sample.txt", walk.getPath());
            assertTrue(walk.isTracked(0));
            // WorkingDirTreeIterator is not a ManifestTreeIterator, so isSymlink
            // must fall back to false regardless of tracking state.
            assertFalse(walk.isSymlink(0));
        }
    }

    @Test
    public void testResetRewindsTraversalToBeginning() throws Exception {
        try (Hg hg = Hg.open(tempRepoDir)) {
            File fileA = new File(tempRepoDir, "a.txt");
            Files.writeString(fileA.toPath(), "A", StandardCharsets.UTF_8);
            File fileB = new File(tempRepoDir, "b.txt");
            Files.writeString(fileB.toPath(), "B", StandardCharsets.UTF_8);
            hg.add().addFile("a.txt").addFile("b.txt").call();
            hg.commit().setAuthor("tester").setMessage("Commit 1").call();

            TreeWalk walk = new TreeWalk();
            walk.addTree(new ManifestTreeIterator(repository, "0"));

            assertTrue(walk.next());
            assertEquals("a.txt", walk.getPath());
            assertTrue(walk.next());
            assertEquals("b.txt", walk.getPath());
            assertFalse(walk.next());
            // Calling next() again after exhaustion must keep returning false
            // (currentPath stays null, so the "currentPath != null" guard in
            // the advance loop short-circuits to false).
            assertFalse(walk.next());
            assertNull(walk.getPath());

            walk.reset();
            assertNull(walk.getPath());

            assertTrue(walk.next());
            assertEquals("a.txt", walk.getPath());
            assertTrue(walk.next());
            assertEquals("b.txt", walk.getPath());
            assertFalse(walk.next());
        }
    }

    @Test
    public void testSetRecursiveFalseWithoutFilterSkipsNestedPaths() throws Exception {
        try (Hg hg = Hg.open(tempRepoDir)) {
            File topFile = new File(tempRepoDir, "top.txt");
            Files.writeString(topFile.toPath(), "Top", StandardCharsets.UTF_8);

            File nestedDir = new File(tempRepoDir, "src/main");
            nestedDir.mkdirs();
            File nestedFile = new File(nestedDir, "a.java");
            Files.writeString(nestedFile.toPath(), "class A {}", StandardCharsets.UTF_8);

            hg.add().addFile("top.txt").addFile("src/main/a.java").call();
            hg.commit().setAuthor("tester").setMessage("Commit 1").call();

            TreeWalk walk = new TreeWalk();
            walk.addTree(new ManifestTreeIterator(repository, "0"));
            walk.setRecursive(false);

            // Without a filter, baseDir is always "": only root-level entries
            // (no '/' in their path) survive; nested entries are skipped.
            assertTrue(walk.next());
            assertEquals("top.txt", walk.getPath());
            assertFalse(walk.next());
        }
    }

    @Test
    public void testSetRecursiveFalseWithDirectoryPrefixFilterStillYieldsNestedEntries() throws Exception {
        try (Hg hg = Hg.open(tempRepoDir)) {
            File nestedDir = new File(tempRepoDir, "src/main/java");
            nestedDir.mkdirs();
            Files.writeString(new File(nestedDir, "a.java").toPath(), "class A {}", StandardCharsets.UTF_8);
            File deeperDir = new File(tempRepoDir, "src/main/java/sub");
            deeperDir.mkdirs();
            Files.writeString(new File(deeperDir, "b.java").toPath(), "class B {}", StandardCharsets.UTF_8);

            File docDir = new File(tempRepoDir, "doc");
            docDir.mkdirs();
            Files.writeString(new File(docDir, "readme.md").toPath(), "Readme", StandardCharsets.UTF_8);

            hg.add().addFile("src/main/java/a.java")
                    .addFile("src/main/java/sub/b.java")
                    .addFile("doc/readme.md").call();
            hg.commit().setAuthor("tester").setMessage("Commit 1").call();

            TreeWalk walk = new TreeWalk();
            walk.addTree(new ManifestTreeIterator(repository, "0"));
            walk.setFilter(new SparsePathFilter("src/main/**"));
            walk.setRecursive(false);

            // SparsePathFilter("src/main/**") accepts every directory prefix
            // under src/main, so the baseDir search in the non-recursive branch
            // always resolves to the entry's own immediate parent directory,
            // making the remainder a bare filename with no further '/'. Both
            // nested files therefore still pass, even though setRecursive(false)
            // was requested -- doc/readme.md remains excluded by the filter itself.
            assertTrue(walk.next());
            assertEquals("src/main/java/a.java", walk.getPath());
            assertTrue(walk.next());
            assertEquals("src/main/java/sub/b.java", walk.getPath());
            assertFalse(walk.next());
        }
    }

    @Test
    public void testSetRecursiveFalseWithLeafOnlyFilterExcludesUnrootedEntries() throws Exception {
        try (Hg hg = Hg.open(tempRepoDir)) {
            File nestedDir = new File(tempRepoDir, "src/main");
            nestedDir.mkdirs();
            Files.writeString(new File(nestedDir, "a.txt").toPath(), "A", StandardCharsets.UTF_8);

            hg.add().addFile("src/main/a.txt").call();
            hg.commit().setAuthor("tester").setMessage("Commit 1").call();

            TreeWalk walk = new TreeWalk();
            walk.addTree(new ManifestTreeIterator(repository, "0"));
            // This glob matches the leaf file path itself ("src/main/a.txt")
            // but does not match either of its ancestor directories
            // ("src/main" or "src") since the single '*' segments cannot
            // cross a '/'. So the top-level filter check accepts the entry,
            // yet the non-recursive baseDir search walks all the way up to
            // the root without finding an accepted ancestor directory,
            // baseDir stays "", the remainder is the full (slash-containing)
            // path, and the entry is skipped.
            walk.setFilter(new SparsePathFilter("src/*/*.txt"));
            walk.setRecursive(false);

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
