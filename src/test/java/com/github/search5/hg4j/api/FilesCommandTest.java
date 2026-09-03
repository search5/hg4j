package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.lib.NodeId;
import com.github.search5.hg4j.treewalk.HgTreeFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FilesCommand}, verified against real {@code hg files} behavior
 * on scratch repos (see task notes): default listing reflects the working copy's
 * tracked set (dirstate), not literally the tip commit; {@code -r <rev>} lists a
 * historical manifest; pattern/glob matching reuses the already-verified
 * {@link com.github.search5.hg4j.treewalk.SparsePathFilter} semantics; paths are
 * relative to the repo root with forward slashes, sorted lexicographically
 * (byte-wise UTF-8 order, matching TreeCommand/StatusCommand's own sort).
 */
public class FilesCommandTest {

    @Test
    public void testEmptyRepositoryReturnsEmptyList(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        List<String> files = new FilesCommand(repo).call();
        assertTrue(files.isEmpty());
    }

    /**
     * Distinct from {@link #testEmptyRepositoryReturnsEmptyList} above: that test never sets a
     * revision at all, so it goes through the default working-copy (dirstate) listing path and
     * never even reaches {@code listRevisionFiles()}'s own "00changelog.i doesn't exist yet"
     * short-circuit. Real hg resolves any revision reference on a commit-less repo to the null
     * revision rather than aborting (matches CloneCommand/PullCommand's own empty-source handling).
     */
    @Test
    public void testEmptyRepositoryWithExplicitRevisionReturnsEmptyList(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        List<String> files = new FilesCommand(repo).setRevision("tip").call();
        assertTrue(files.isEmpty());
    }

    @Test
    public void testChangelogIndexExistsButHasZeroRevisionsWithExplicitRevisionReturnsEmptyList(@TempDir Path tempDir) throws Exception {
        // Distinct from testEmptyRepositoryWithExplicitRevisionReturnsEmptyList: here
        // 00changelog.i exists on disk (e.g. a store initialized but never committed to) but has
        // zero revisions, a separate branch outcome from "the index file doesn't exist at all".
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File storeDir = new File(repoDir, ".hg/store");
        assertTrue(storeDir.exists() || storeDir.mkdirs());
        assertTrue(new File(storeDir, "00changelog.i").createNewFile());

        List<String> files = new FilesCommand(repo).setRevision("tip").call();
        assertTrue(files.isEmpty());
    }

    @Test
    public void testSetRevisionWithNullNodeIdResetsToWorkingCopyDefault(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "a");
        new AddCommand(repo).call();

        List<String> files = new FilesCommand(repo).setRevision((NodeId) null).call();
        assertEquals(List.of("a.txt"), files, "null NodeId must fall back to the working-copy default, not throw or resolve a bogus hex");
    }

    @Test
    public void testSetTreeFilterWithNullResetsToMatchAll(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "a");
        new AddCommand(repo).call();

        List<String> files = new FilesCommand(repo).setTreeFilter(null).call();
        assertEquals(List.of("a.txt"), files);
    }

    @Test
    public void testDefaultListsAllTrackedFilesSortedWithSubdirectories(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File srcDir = new File(repoDir, "src");
        File subDir = new File(srcDir, "sub");
        subDir.mkdirs();

        Files.writeString(new File(repoDir, "b.txt").toPath(), "b\n");
        Files.writeString(new File(repoDir, "a.txt").toPath(), "a\n");
        Files.writeString(new File(srcDir, "c.txt").toPath(), "c\n");
        Files.writeString(new File(subDir, "d.txt").toPath(), "d\n");

        new AddCommand(repo)
                .addFile("b.txt").addFile("a.txt")
                .addFile("src/c.txt").addFile("src/sub/d.txt")
                .call();
        new CommitCommand(repo).setAuthor("tester <test@example.com>").setMessage("rev0").call();

        List<String> files = new FilesCommand(repo).call();
        assertEquals(List.of("a.txt", "b.txt", "src/c.txt", "src/sub/d.txt"), files);
    }

    @Test
    public void testDefaultReflectsWorkingCopyNotJustLastCommit(@TempDir Path tempDir) throws Exception {
        // Verified against real hg: `hg files` (no -r) with an uncommitted `hg rm`
        // and an uncommitted `hg add` shows the working copy's tracked set (removed
        // file excluded, newly-added-but-uncommitted file included) rather than the
        // last commit's manifest.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        Files.writeString(new File(repoDir, "a.txt").toPath(), "a\n");
        Files.writeString(new File(repoDir, "b.txt").toPath(), "b\n");
        new AddCommand(repo).addFile("a.txt").addFile("b.txt").call();
        new CommitCommand(repo).setAuthor("tester <test@example.com>").setMessage("rev0").call();

        new RemoveCommand(repo).setFile("b.txt").setForce(true).call();
        Files.writeString(new File(repoDir, "e.txt").toPath(), "e\n");
        new AddCommand(repo).addFile("e.txt").call();

        List<String> files = new FilesCommand(repo).call();
        assertEquals(List.of("a.txt", "e.txt"), files);
    }

    @Test
    public void testRevisionScopedListingShowsFilesRemovedLater(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File fa = new File(repoDir, "a.txt");
        File fb = new File(repoDir, "b.txt");
        Files.writeString(fa.toPath(), "a\n");
        Files.writeString(fb.toPath(), "b\n");
        new AddCommand(repo).addFile("a.txt").addFile("b.txt").call();
        byte[] rev0Node = new CommitCommand(repo).setAuthor("tester <test@example.com>").setMessage("rev0").call();

        // rev1: remove b.txt, add c.txt
        new RemoveCommand(repo).setFile("b.txt").setForce(true).call();
        Files.writeString(new File(repoDir, "c.txt").toPath(), "c\n");
        new AddCommand(repo).addFile("c.txt").call();
        new CommitCommand(repo).setAuthor("tester <test@example.com>").setMessage("rev1").call();

        // Current tip (rev1): b.txt gone, c.txt present
        List<String> tipFiles = new FilesCommand(repo).call();
        assertEquals(List.of("a.txt", "c.txt"), tipFiles);

        // Historical rev0 by revision number: b.txt still there, c.txt didn't exist yet
        List<String> rev0Files = new FilesCommand(repo).setRevision("0").call();
        assertEquals(List.of("a.txt", "b.txt"), rev0Files);

        // Historical rev0 by node id
        List<String> rev0ByNode = new FilesCommand(repo).setRevision(new NodeId(rev0Node)).call();
        assertEquals(List.of("a.txt", "b.txt"), rev0ByNode);

        // "tip" keyword resolves to the latest revision
        List<String> tipByKeyword = new FilesCommand(repo).setRevision("tip").call();
        assertEquals(List.of("a.txt", "c.txt"), tipByKeyword);
    }

    @Test
    public void testUnknownRevisionThrows(@TempDir Path tempDir) throws Exception {
        // Verified against real hg: `hg files -r 999` on a non-empty repo aborts
        // with "unknown revision" rather than silently returning nothing.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "a\n");
        new AddCommand(repo).addFile("a.txt").call();
        new CommitCommand(repo).setAuthor("tester <test@example.com>").setMessage("rev0").call();

        assertThrows(Exception.class, () -> new FilesCommand(repo).setRevision("999").call());
    }

    @Test
    public void testGlobPatternFiltersToMatchingSubtree(@TempDir Path tempDir) throws Exception {
        // Verified against real hg: `hg files 'glob:src/*.txt'` matches only direct
        // children of src/, while a bare directory pattern like `hg files src` matches
        // everything below that directory (SparsePathFilter's own directory-prefix
        // semantics, reused as-is here).
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File srcDir = new File(repoDir, "src");
        File subDir = new File(srcDir, "sub");
        subDir.mkdirs();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "a\n");
        Files.writeString(new File(srcDir, "c.txt").toPath(), "c\n");
        Files.writeString(new File(subDir, "d.txt").toPath(), "d\n");
        new AddCommand(repo).addFile("a.txt").addFile("src/c.txt").addFile("src/sub/d.txt").call();
        new CommitCommand(repo).setAuthor("tester <test@example.com>").setMessage("rev0").call();

        List<String> globDirect = new FilesCommand(repo).setPattern("src/*.txt").call();
        assertEquals(List.of("src/c.txt"), globDirect);

        List<String> dirPrefix = new FilesCommand(repo).setPattern("src").call();
        assertEquals(List.of("src/c.txt", "src/sub/d.txt"), dirPrefix);

        List<String> noMatch = new FilesCommand(repo).setPattern("nomatch.txt").call();
        assertTrue(noMatch.isEmpty());

        List<String> exact = new FilesCommand(repo).setPattern("a.txt").call();
        assertEquals(List.of("a.txt"), exact);
    }

    @Test
    public void testSetTreeFilterWithPathPrefixFilter(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File srcDir = new File(repoDir, "src");
        File docDir = new File(repoDir, "doc");
        srcDir.mkdirs();
        docDir.mkdirs();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "a\n");
        Files.writeString(new File(docDir, "b.txt").toPath(), "b\n");
        new AddCommand(repo).addFile("src/a.txt").addFile("doc/b.txt").call();
        new CommitCommand(repo).setAuthor("tester <test@example.com>").setMessage("rev0").call();

        HgTreeFilter filter = HgTreeFilter.createPathPrefixFilter(List.of("src/"), List.of());
        List<String> files = new FilesCommand(repo).setTreeFilter(filter).call();
        assertEquals(List.of("src/a.txt"), files);
    }

    @Test
    public void testRevisionWithPatternCombined(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        Files.writeString(new File(repoDir, "a.txt").toPath(), "a\n");
        Files.writeString(new File(repoDir, "b.log").toPath(), "b\n");
        new AddCommand(repo).addFile("a.txt").addFile("b.log").call();
        new CommitCommand(repo).setAuthor("tester <test@example.com>").setMessage("rev0").call();

        List<String> files = new FilesCommand(repo).setRevision("0").setPattern("*.txt").call();
        assertEquals(List.of("a.txt"), files);
    }
}
