package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.lib.NodeId;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Targeted coverage tests for {@link ExportCommand}, complementing the roundtrip test in
 * {@link ExportImportCommandTest}. Covers the input-validation error paths, multi-line commit
 * descriptions, and the "# Parent" / "diff -r" header emitted for a non-root revision.
 */
public class ExportCommandCoverageTest {

    @Test
    public void testNullRevisionThrowsIllegalArgumentException(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        ExportCommand cmd = new ExportCommand(repo);
        assertThrows(IllegalArgumentException.class, cmd::call);
    }

    @Test
    public void testEmptyRevisionThrowsIllegalArgumentException(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        ExportCommand cmd = new ExportCommand(repo).setRevision("");
        assertThrows(IllegalArgumentException.class, cmd::call);
    }

    @Test
    public void testUnresolvableRevisionThrowsIOException(@TempDir Path tempDir) throws Exception {
        File dir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(dir).call();
        Files.writeString(dir.toPath().resolve("a.txt"), "content");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("first commit").setAuthor("dev").call();

        ExportCommand cmd = new ExportCommand(repo).setRevision("nonexistent-revision-id");
        IOException ex = assertThrows(IOException.class, cmd::call);
        assertTrue(ex.getMessage().contains("nonexistent-revision-id"));
    }

    @Test
    public void testMultilineDescriptionIsJoinedWithNewlines(@TempDir Path tempDir) throws Exception {
        File dir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(dir).call();
        Files.writeString(dir.toPath().resolve("a.txt"), "hello");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Summary line\n\nBody line one\nBody line two").setAuthor("dev").call();

        String patch = new ExportCommand(repo).setRevision("0").call();

        assertTrue(patch.contains("Summary line\n\nBody line one\nBody line two\n\n"),
                "Expected the multi-line description to be reproduced verbatim in the patch, but got:\n" + patch);
    }

    /**
     * Two ordinary sequential commits (rev 1's parent is rev 0, so rev - 1 happens to equal the
     * real parent too). Confirms the common, non-root "# Parent" / "diff -r" path is exercised.
     */
    @Test
    public void testExportSecondLinearRevisionIncludesParentHeaderAndDiff(@TempDir Path tempDir) throws Exception {
        File dir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(dir).call();
        File f1 = new File(dir, "a.txt");
        Files.writeString(f1.toPath(), "content0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("commit0").setAuthor("dev").call();

        Files.writeString(f1.toPath(), "content1");
        new CommitCommand(repo).setMessage("commit1").setAuthor("dev").call();

        Revlog changelog = getChangelog(repo);
        String rev0Hex = NodeIdUtil.toHex(changelog.getIndexRecord(0).getNodeId());
        String rev1Hex = NodeIdUtil.toHex(changelog.getIndexRecord(1).getNodeId());

        String patch = new ExportCommand(repo).setRevision("1").call();

        assertTrue(patch.contains("# Parent  " + rev0Hex), "Expected parent header with rev0's node id:\n" + patch);
        assertTrue(patch.contains("diff -r " + rev0Hex.substring(0, 12) + " -r " + rev1Hex.substring(0, 12) + " a.txt"),
                "Expected diff -r header referencing rev0 and rev1:\n" + patch);
        assertTrue(patch.contains("-content0"));
        assertTrue(patch.contains("+content1"));
    }

    /**
     * Regression test for a real bug found via real `hg`: a changeset's parent is not always
     * "revision number minus one" -- revision numbers reflect commit order, not DAG order. Here
     * rev 2 is committed on top of rev 0 (not rev 1), forming a second head. Verified against real
     * `hg export -r 2` on an equivalent repository: it reports "# Parent" as rev 0's node id and
     * diffs content0 -> content2-branch, not rev1 -> content2-branch.
     */
    @Test
    public void testExportBranchedRevisionUsesActualParentNotPrecedingRevisionNumber(@TempDir Path tempDir) throws Exception {
        File dir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(dir).call();
        File f1 = new File(dir, "a.txt");

        Files.writeString(f1.toPath(), "content0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("commit0").setAuthor("dev").call();

        Revlog changelog = getChangelog(repo);
        byte[] rev0Node = changelog.getIndexRecord(0).getNodeId();
        String rev0Hex = NodeIdUtil.toHex(rev0Node);

        Files.writeString(f1.toPath(), "content1");
        new CommitCommand(repo).setMessage("commit1").setAuthor("dev").call();

        // Rewind the working directory parent back to rev 0 so the next commit becomes a second
        // child of rev 0 (a new head), not a child of rev 1.
        var dirstate = repo.getDirstate();
        dirstate.setParents(new NodeId(rev0Node), NodeId.NULL);
        repo.writeDirstate(dirstate);
        repo.clearRevlogCache();

        Files.writeString(f1.toPath(), "content2-branch");
        new CommitCommand(repo).setMessage("commit2").setAuthor("dev").call();

        changelog = getChangelog(repo);
        assertEquals(3, changelog.getRevisionCount());
        assertEquals(0, changelog.getIndexRecord(2).getParent1(), "rev 2's real parent must be rev 0");

        String rev2Hex = NodeIdUtil.toHex(changelog.getIndexRecord(2).getNodeId());

        String patch = new ExportCommand(repo).setRevision("2").call();

        assertTrue(patch.contains("# Parent  " + rev0Hex),
                "Parent header must reference the real parent (rev 0), not rev 1:\n" + patch);
        assertTrue(patch.contains("diff -r " + rev0Hex.substring(0, 12) + " -r " + rev2Hex.substring(0, 12) + " a.txt"),
                "diff -r header must reference the real parent (rev 0), not rev 1:\n" + patch);
        assertTrue(patch.contains("-content0"), "Diff must be against rev0's content, not rev1's:\n" + patch);
        assertTrue(patch.contains("+content2-branch"));
        assertTrue(!patch.contains("-content1"), "Diff must not be computed against rev1's content:\n" + patch);
    }

    private static Revlog getChangelog(HgRepository repo) throws IOException {
        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        return repo.getRevlog(clIdx, clDat);
    }
}
