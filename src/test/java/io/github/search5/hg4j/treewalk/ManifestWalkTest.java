package io.github.search5.hg4j.treewalk;

import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Targeted coverage for {@link ManifestWalk}: the outer class's caching {@code next()}/{@code
 * getEntry()}/{@code reset()} state machine, both constructors, out-of-range accessor behavior,
 * the anonymous {@code List} returned by {@code getEntries()}, and the anonymous {@code Iterator}
 * returned by {@code lazyEntries()}.
 */
public class ManifestWalkTest {

    private File tempRepoDir;
    private HgRepository repository;

    @BeforeEach
    public void setUp() throws Exception {
        tempRepoDir = Files.createTempDirectory("hg4j_mw_test_").toFile();
        repository = Hg.init().setDirectory(tempRepoDir).call();
        assertNotNull(repository);
    }

    @AfterEach
    public void tearDown() {
        deleteDirRecursively(tempRepoDir);
    }

    private void deleteDirRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteDirRecursively(child);
            }
        }
        file.delete();
    }

    private byte[] commitSingleFile(String path, String content) throws Exception {
        try (Hg hg = Hg.open(tempRepoDir)) {
            File f = new File(tempRepoDir, path);
            Files.writeString(f.toPath(), content, StandardCharsets.UTF_8);
            hg.add().addFile(path).call();
            return hg.commit().setAuthor("tester <test@example.com>").setMessage("commit " + path).call();
        }
    }

    // ------------------------------------------------------------------
    // next() / getEntry() / reset() state machine
    // ------------------------------------------------------------------

    @Test
    public void testNextAndGetEntry_SingleFileNoFlags() throws Exception {
        commitSingleFile("sample.txt", "hello");

        ManifestWalk walk = new ManifestWalk(repository, "0");
        assertTrue(walk.next());

        ManifestWalk.Entry entry = walk.getEntry();
        assertEquals("sample.txt", entry.getPath());
        assertNotNull(entry.getNodeId());
        assertEquals(40, entry.getNodeIdHex().length());
        assertFalse(entry.isExecutable());
        assertFalse(entry.isSymlink());

        assertFalse(walk.next());
    }

    @Test
    public void testGetEntry_ThrowsBeforeFirstNext() {
        ManifestWalk walk = new ManifestWalk(repository, "-1");
        NoSuchElementException ex = assertThrows(NoSuchElementException.class, walk::getEntry);
        assertEquals("No current entry", ex.getMessage());
    }

    @Test
    public void testGetEntry_AfterExhaustionKeepsReturningLastEntry() throws Exception {
        // next() never advances cachedIndex past the last valid position -- a false return
        // just means "no further advance happened", so getEntry() keeps reflecting the last
        // entry that was successfully advanced to, rather than throwing.
        commitSingleFile("sample.txt", "hello");

        ManifestWalk walk = new ManifestWalk(repository, "0");
        assertTrue(walk.next());
        assertFalse(walk.next());

        assertEquals("sample.txt", walk.getEntry().getPath());
    }

    @Test
    public void testReset_RewindsToBeginningWithoutReloading() throws Exception {
        commitSingleFile("a.txt", "a-content");
        commitSingleFile("b.txt", "b-content");

        ManifestWalk walk = new ManifestWalk(repository, "1");
        assertTrue(walk.next());
        assertEquals("a.txt", walk.getEntry().getPath());
        assertTrue(walk.next());
        assertEquals("b.txt", walk.getEntry().getPath());
        assertFalse(walk.next());

        walk.reset();
        assertTrue(walk.next());
        assertEquals("a.txt", walk.getEntry().getPath());
        assertTrue(walk.next());
        assertEquals("b.txt", walk.getEntry().getPath());
        assertFalse(walk.next());
    }

    // ------------------------------------------------------------------
    // Constructors
    // ------------------------------------------------------------------

    @Test
    public void testDirectManifestNodeConstructor_MatchesRevisionBasedLookup() throws Exception {
        commitSingleFile("a.txt", "content");

        File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
        File mfDat = new File(repository.getStoreDir(), "00manifest.d");
        Revlog manifestRevlog = repository.getRevlog(mfIdx, mfDat);
        byte[] manifestNode = manifestRevlog.getIndexRecord(0).getNodeId();

        ManifestWalk direct = new ManifestWalk(repository, manifestNode);
        assertTrue(direct.next());
        assertEquals("a.txt", direct.getEntry().getPath());
        assertFalse(direct.next());
    }

    // ------------------------------------------------------------------
    // Executable / symlink flag propagation through the outer Entry
    // ------------------------------------------------------------------

    @Test
    public void testExecutableAndSymlinkFlagsPropagateThroughEntry() throws Exception {
        try (Hg hg = Hg.open(tempRepoDir)) {
            File target = new File(tempRepoDir, "target.txt");
            Files.writeString(target.toPath(), "target content", StandardCharsets.UTF_8);

            File script = new File(tempRepoDir, "run.sh");
            Files.writeString(script.toPath(), "#!/bin/sh\necho hi\n", StandardCharsets.UTF_8);
            assertTrue(script.setExecutable(true));

            File link = new File(tempRepoDir, "link.txt");
            Files.createSymbolicLink(link.toPath(), new File("target.txt").toPath());

            hg.add().addFile("target.txt").addFile("run.sh").addFile("link.txt").call();
            hg.commit().setAuthor("tester <test@example.com>").setMessage("mixed flags").call();
        }

        ManifestWalk walk = new ManifestWalk(repository, "0");
        List<ManifestWalk.Entry> entries = walk.getEntries();
        assertEquals(3, entries.size());

        ManifestWalk.Entry linkEntry = entries.stream().filter(e -> e.getPath().equals("link.txt")).findFirst().orElseThrow();
        assertTrue(linkEntry.isSymlink());
        assertFalse(linkEntry.isExecutable());

        ManifestWalk.Entry scriptEntry = entries.stream().filter(e -> e.getPath().equals("run.sh")).findFirst().orElseThrow();
        assertTrue(scriptEntry.isExecutable());
        assertFalse(scriptEntry.isSymlink());

        ManifestWalk.Entry targetEntry = entries.stream().filter(e -> e.getPath().equals("target.txt")).findFirst().orElseThrow();
        assertFalse(targetEntry.isExecutable());
        assertFalse(targetEntry.isSymlink());
    }

    // ------------------------------------------------------------------
    // getEntries(): the anonymous AbstractList (ManifestWalk$1)
    // ------------------------------------------------------------------

    @Test
    public void testGetEntries_SortedRandomAccessAndRepeatedSizeUsesCache() throws Exception {
        commitSingleFile("b.txt", "b-content");
        commitSingleFile("a.txt", "a-content");

        ManifestWalk walk = new ManifestWalk(repository, "1");
        List<ManifestWalk.Entry> entries = walk.getEntries();

        assertEquals(2, entries.size());
        assertEquals("a.txt", entries.get(0).getPath());
        assertEquals("b.txt", entries.get(1).getPath());

        // size() called again after full materialization must reuse the cache, not re-iterate.
        assertEquals(2, entries.size());
        assertEquals("a.txt", entries.get(0).getPath());
    }

    @Test
    public void testGetEntries_ForEachIterationCoversAnonymousIterator() throws Exception {
        commitSingleFile("b.txt", "b-content");
        commitSingleFile("a.txt", "a-content");

        ManifestWalk walk = new ManifestWalk(repository, "1");
        List<ManifestWalk.Entry> entries = walk.getEntries();

        List<String> paths = new ArrayList<>();
        for (ManifestWalk.Entry entry : entries) {
            paths.add(entry.getPath());
        }
        assertEquals(List.of("a.txt", "b.txt"), paths);
    }

    @Test
    public void testGetEntries_IndexOutOfBoundsThrows() throws Exception {
        commitSingleFile("only.txt", "content");

        ManifestWalk walk = new ManifestWalk(repository, "0");
        List<ManifestWalk.Entry> entries = walk.getEntries();

        IndexOutOfBoundsException ex = assertThrows(IndexOutOfBoundsException.class, () -> entries.get(5));
        assertTrue(ex.getMessage().contains("Index: 5"));
        assertTrue(ex.getMessage().contains("Size: 1"));
    }

    @Test
    public void testGetEntries_EmptyManifestRevisionYieldsEmptyList() throws Exception {
        ManifestWalk walk = new ManifestWalk(repository, "-1");
        List<ManifestWalk.Entry> entries = walk.getEntries();

        assertTrue(entries.isEmpty());
        assertThrows(IndexOutOfBoundsException.class, () -> entries.get(0));
    }

    @Test
    public void testGetEntries_PartialGetThenSizeStillCoversFullList() throws Exception {
        commitSingleFile("a.txt", "a-content");
        commitSingleFile("b.txt", "b-content");

        ManifestWalk walk = new ManifestWalk(repository, "1");
        List<ManifestWalk.Entry> entries = walk.getEntries();

        // Request only the first element first: size() must still lazily drain the rest.
        assertEquals("a.txt", entries.get(0).getPath());
        assertEquals(2, entries.size());
    }

    // ------------------------------------------------------------------
    // lazyEntries(): the anonymous Iterator (ManifestWalk$2)
    // ------------------------------------------------------------------

    @Test
    public void testLazyEntries_DirectIteratorUsageMatchesGetEntries() throws Exception {
        commitSingleFile("b.txt", "b-content");
        commitSingleFile("a.txt", "a-content");

        ManifestWalk walk = new ManifestWalk(repository, "1");
        Iterator<ManifestWalk.Entry> it = walk.lazyEntries();

        List<String> paths = new ArrayList<>();
        while (it.hasNext()) {
            paths.add(it.next().getPath());
        }
        assertEquals(List.of("a.txt", "b.txt"), paths);
        assertFalse(it.hasNext());
    }

    @Test
    public void testLazyEntries_EmptyManifestHasNextFalseImmediately() throws Exception {
        ManifestWalk walk = new ManifestWalk(repository, "-1");
        Iterator<ManifestWalk.Entry> it = walk.lazyEntries();
        assertFalse(it.hasNext());
    }

    // ------------------------------------------------------------------
    // Error propagation for an unresolvable revision
    // ------------------------------------------------------------------

    @Test
    public void testNext_UnknownRevisionThrowsRevisionNotFound() throws Exception {
        commitSingleFile("a.txt", "content");

        ManifestWalk walk = new ManifestWalk(repository, "zzzzzzzz");
        assertThrows(HgRevisionNotFoundException.class, walk::next);
    }

    @Test
    public void testLazyEntries_UnknownRevisionWrapsIOExceptionInRuntimeException() throws Exception {
        commitSingleFile("a.txt", "content");

        ManifestWalk walk = new ManifestWalk(repository, "zzzzzzzz");
        Iterator<ManifestWalk.Entry> it = walk.lazyEntries();

        RuntimeException ex = assertThrows(RuntimeException.class, it::hasNext);
        assertTrue(ex.getCause() instanceof HgRevisionNotFoundException);
    }

    @Test
    public void testGetEntries_UnknownRevisionThrowsWhenMaterialized() throws Exception {
        commitSingleFile("a.txt", "content");

        ManifestWalk walk = new ManifestWalk(repository, "zzzzzzzz");
        List<ManifestWalk.Entry> entries = walk.getEntries();

        assertThrows(RuntimeException.class, entries::size);
    }
}
