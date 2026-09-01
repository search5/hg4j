package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code hg branches}-equivalent branch/head grouping, verified against real hg 7.2.2's own
 * behavior directly (2026-09-01): {@code hg branches} lists one entry per branch -- the
 * highest-revision <em>open</em> head if the branch has one, otherwise (every head closed) the
 * highest-revision closed head, hidden by default and shown only with {@code --closed}. A branch
 * with several open heads still shows only its highest-revision open head as "the" entry (real hg
 * doesn't enumerate every head here -- that's what {@code hg heads <branch>} is for).
 */
public class BranchesCommandTest {

    @Test
    public void singleDefaultBranchCommitIsItsOwnOpenHead(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "a.txt").toPath(), "content");
        new AddCommand(repo).call();
        byte[] commit = new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage("c0").call();

        List<BranchesCommand.BranchHead> branches = new BranchesCommand(repo).call();
        assertEquals(1, branches.size());
        BranchesCommand.BranchHead head = branches.get(0);
        assertEquals("default", head.getBranch());
        assertEquals(NodeIdUtil.toHex(commit), NodeIdUtil.toHex(head.getNode()));
        assertFalse(head.isClosed());
    }

    @Test
    public void aNamedBranchAndDefaultEachReportTheirOwnOpenHead(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "a.txt").toPath(), "a");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage("default c0").call();

        repo.setBranch("feature");
        Files.writeString(new File(tempDir.toFile(), "b.txt").toPath(), "b");
        new AddCommand(repo).call();
        byte[] featureCommit = new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage("feature c1").call();

        List<BranchesCommand.BranchHead> branches = new BranchesCommand(repo).call();
        Map<String, BranchesCommand.BranchHead> byBranch = branches.stream()
                .collect(Collectors.toMap(BranchesCommand.BranchHead::getBranch, h -> h));

        assertEquals(2, branches.size());
        assertFalse(byBranch.get("default").isClosed());
        assertEquals(NodeIdUtil.toHex(featureCommit), NodeIdUtil.toHex(byBranch.get("feature").getNode()));
        assertFalse(byBranch.get("feature").isClosed());
    }

    @Test
    public void closingOneOfTwoHeadsOnABranchLeavesTheRemainingOpenHeadAsItsEntry(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "a.txt").toPath(), "a");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage("default c0").call();

        repo.setBranch("feature");
        Files.writeString(new File(tempDir.toFile(), "b.txt").toPath(), "b");
        new AddCommand(repo).call();
        byte[] c1 = new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage("feature c1").call();

        // Fork a second head on the same "feature" branch from c0 (real hg's `hg update -r 0 && hg
        // branch feature --force && hg commit`, reproduced directly via dirstate + CommitCommand).
        com.github.search5.hg4j.dirstate.Dirstate dirstate = repo.getDirstate();
        dirstate.setParents(new com.github.search5.hg4j.lib.NodeId(c0), com.github.search5.hg4j.lib.NodeId.NULL);
        repo.writeDirstate(dirstate);
        repo.setBranch("feature");
        Files.writeString(new File(tempDir.toFile(), "c.txt").toPath(), "c");
        new AddCommand(repo).call();
        byte[] c2 = new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage("feature c2 (second head)").call();

        List<BranchesCommand.BranchHead> beforeClose = new BranchesCommand(repo).call();
        Map<String, BranchesCommand.BranchHead> beforeByBranch = beforeClose.stream()
                .collect(Collectors.toMap(BranchesCommand.BranchHead::getBranch, h -> h));
        assertEquals(NodeIdUtil.toHex(c2), NodeIdUtil.toHex(beforeByBranch.get("feature").getNode()),
                "With two open heads, the higher-revision one must be reported");

        // Close c2 (the branch tip we're on) -- c1 remains the only open head afterward.
        new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage("close c2").setCloseBranch(true).call();

        List<BranchesCommand.BranchHead> afterClose = new BranchesCommand(repo).call();
        Map<String, BranchesCommand.BranchHead> afterByBranch = afterClose.stream()
                .collect(Collectors.toMap(BranchesCommand.BranchHead::getBranch, h -> h));

        assertEquals(2, afterClose.size(), "Both branches must still be listed (feature still has an open head)");
        assertEquals(NodeIdUtil.toHex(c1), NodeIdUtil.toHex(afterByBranch.get("feature").getNode()),
                "Once c2 is closed, the remaining open head c1 must be reported even though c2 has a higher revision");
        assertFalse(afterByBranch.get("feature").isClosed());
    }

    @Test
    public void aBranchWhoseOnlyHeadIsClosedIsHiddenByDefaultAndShownWithIncludeClosed(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "a.txt").toPath(), "a");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage("default c0").call();

        repo.setBranch("feature");
        Files.writeString(new File(tempDir.toFile(), "b.txt").toPath(), "b");
        new AddCommand(repo).call();
        byte[] featureClose = new CommitCommand(repo).setAuthor("u <u@example.com>")
                .setMessage("feature c1, closed immediately").setCloseBranch(true).call();

        List<BranchesCommand.BranchHead> defaultListing = new BranchesCommand(repo).call();
        assertEquals(1, defaultListing.size(), "A branch with only closed heads must be hidden by default");
        assertEquals("default", defaultListing.get(0).getBranch());

        List<BranchesCommand.BranchHead> withClosed = new BranchesCommand(repo).setIncludeClosed(true).call();
        Map<String, BranchesCommand.BranchHead> byBranch = withClosed.stream()
                .collect(Collectors.toMap(BranchesCommand.BranchHead::getBranch, h -> h));
        assertEquals(2, withClosed.size());
        assertTrue(byBranch.get("feature").isClosed());
        assertEquals(NodeIdUtil.toHex(featureClose), NodeIdUtil.toHex(byBranch.get("feature").getNode()));
    }
}
