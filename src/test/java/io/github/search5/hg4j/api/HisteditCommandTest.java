package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.util.List;

public class HisteditCommandTest {

    @Test
    public void testHisteditFlow(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // Create base commit
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Content A");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit A").setAuthor("dev").call();

        String hexA = NodeIdUtil.toHex(repo.getDirstate().getParent1());

        // Create second commit to rewrite
        File f2 = new File(repoDir, "b.txt");
        Files.writeString(f2.toPath(), "Content B");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit B").setAuthor("dev").call();

        String hexB = NodeIdUtil.toHex(repo.getDirstate().getParent1());

        // Execute histedit PICK on A and B
        new HisteditCommand(repo)
            .addRule(HisteditCommand.Action.PICK, hexA)
            .addRule(HisteditCommand.Action.PICK, hexB)
            .call();

        String hexAfter = NodeIdUtil.toHex(repo.getDirstate().getParent1());
        assertNotNull(hexAfter);
        assertNotEquals(hexB, hexAfter); // rewritten history yields different node hash
    }

    @Test
    public void histeditPreservesTheOriginalBranchOfRewrittenCommits(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Content A");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit A").setAuthor("dev").call();
        String hexA = NodeIdUtil.toHex(repo.getDirstate().getParent1());

        new BranchCommand(repo).setBranchName("feature").call();
        File f2 = new File(repoDir, "b.txt");
        Files.writeString(f2.toPath(), "Content B");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit B on feature").setAuthor("dev").call();
        String hexB = NodeIdUtil.toHex(repo.getDirstate().getParent1());

        new HisteditCommand(repo)
            .addRule(HisteditCommand.Action.PICK, hexA)
            .addRule(HisteditCommand.Action.PICK, hexB)
            .call();

        // This histedit implementation appends the rewritten commits rather than
        // stripping the originals, so the new tip is whatever the dirstate parent now
        // points to, not necessarily the last (or only) entry in the log.
        String newTipHex = NodeIdUtil.toHex(repo.getDirstate().getParent1());
        List<HgCommit> log = new LogCommand(repo).call();
        HgCommit rewrittenB = log.stream()
                .filter(c -> NodeIdUtil.toHex(c.getNodeId().getBytes()).equals(newTipHex))
                .findFirst()
                .orElseThrow(() -> new AssertionError("New tip commit not found in log: " + newTipHex));
        assertEquals("Commit B on feature", rewrittenB.getMessage());
        assertEquals("feature", rewrittenB.getBranch(),
                "Histedit must not silently drop the original branch of a rewritten commit");

        assertEquals("feature", repo.getBranch(),
                "Histedit must leave the working directory on the branch of its new tip");
    }

    @Test
    public void histeditRollsBackAllProgressWhenALaterRuleFails(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Content A");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit A").setAuthor("dev").call();
        String hexA = NodeIdUtil.toHex(repo.getDirstate().getParent1());

        File f2 = new File(repoDir, "b.txt");
        Files.writeString(f2.toPath(), "Content B");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit B").setAuthor("dev").call();

        int countBefore = new LogCommand(repo).call().size();
        byte[] parentBefore = repo.getDirstate().getParent1();
        long clSizeBefore = new File(repo.getStoreDir(), "00changelog.i").length();

        // PICK(A) forces a real commitNewRev() to actually happen (flushed when the next
        // rule is processed) before the third rule's nonexistent node aborts the whole
        // operation — a genuine "partial progress, then failure" scenario.
        String nonexistentHex = "f".repeat(40);
        assertThrows(IOException.class, () ->
                new HisteditCommand(repo)
                        .addRule(HisteditCommand.Action.PICK, hexA)
                        .addRule(HisteditCommand.Action.PICK, hexA) // any second rule to flush the first
                        .addRule(HisteditCommand.Action.PICK, nonexistentHex)
                        .call());

        assertEquals(countBefore, new LogCommand(repo).call().size(),
                "A failed histedit must not leave the partially-rewritten commit behind");
        assertArrayEquals(parentBefore, repo.getDirstate().getParent1(),
                "Working copy parent must be restored to its pre-histedit value");
        assertEquals(clSizeBefore, new File(repo.getStoreDir(), "00changelog.i").length(),
                "changelog index must be truncated back to its pre-histedit size");
        assertFalse(new File(repo.getStoreDir(), "journal").exists(),
                "Journal must be cleaned up after rollback completes");
        assertFalse(new File(repoDir, ".hg/dirstate.backup").exists(),
                "Dirstate backup must be cleaned up after rollback completes");
    }
}
