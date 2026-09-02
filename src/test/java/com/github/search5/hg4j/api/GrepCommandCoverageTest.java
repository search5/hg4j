package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional tests targeting branch/line coverage gaps in {@link GrepCommand}
 * not exercised by {@link GrepCommandTest}.
 */
public class GrepCommandCoverageTest {

    @Test
    public void testNullQueryReturnsEmpty() throws Exception {
        HgRepository repo = createRepoWithCommit(Files.createTempDirectory("grep-null").toFile());
        List<GrepCommand.GrepResult> results = new GrepCommand(repo).call();
        assertTrue(results.isEmpty());
    }

    @Test
    public void testEmptyQueryReturnsEmpty() throws Exception {
        HgRepository repo = createRepoWithCommit(Files.createTempDirectory("grep-empty").toFile());
        List<GrepCommand.GrepResult> results = new GrepCommand(repo).setQuery("").call();
        assertTrue(results.isEmpty());
    }

    @Test
    public void testNoFncacheReturnsEmpty(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        // No commit has been made, so .hg/store/fncache does not exist yet.
        List<GrepCommand.GrepResult> results = new GrepCommand(repo)
            .setQuery("anything")
            .call();
        assertTrue(results.isEmpty());
    }

    @Test
    public void testCaseInsensitiveSearch(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f1 = new File(repoDir, "b.txt");
        Files.writeString(f1.toPath(), "Hello World\nFindMeSpecialPattern\nGoodBye");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("First Commit").call();

        List<GrepCommand.GrepResult> results = new GrepCommand(repo)
            .setQuery("specialpattern")
            .setCaseInsensitive(true)
            .call();

        assertEquals(1, results.size());
        assertEquals("b.txt", results.get(0).path);
        assertEquals("FindMeSpecialPattern", results.get(0).lineContent);
    }

    /**
     * Appends extra, hand-crafted fncache entries that exercise:
     *  - a line that does not end with ".i" (skipped entirely)
     *  - a line ending with ".i" that does not start with "data/" after
     *    stripping the extension (so the "data/" prefix is left untouched)
     *  - a ".i" entry whose actual index file does not exist on disk
     * alongside the real, already-committed entry so the genuine match is
     * still found.
     */
    @Test
    public void testFncacheWithNonDataAndMissingEntries(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f1 = new File(repoDir, "c.txt");
        Files.writeString(f1.toPath(), "Hello World\nFindMeSpecialPattern\nGoodBye");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("First Commit").call();

        File fncacheFile = new File(repo.getStoreDir(), "fncache");
        assertTrue(fncacheFile.exists());

        // Append: a non-".i" line, and a ".i" line not prefixed with "data/"
        // whose backing index file does not exist.
        Files.writeString(fncacheFile.toPath(),
            "\ndata/notreal.d\ndh/hashedpath.i\n",
            StandardOpenOption.APPEND);

        List<GrepCommand.GrepResult> results = new GrepCommand(repo)
            .setQuery("SpecialPattern")
            .call();

        assertEquals(1, results.size());
        assertEquals("c.txt", results.get(0).path);
    }

    private HgRepository createRepoWithCommit(File repoDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Hello World\nFindMeSpecialPattern\nGoodBye");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("First Commit").call();
        return repo;
    }
}
