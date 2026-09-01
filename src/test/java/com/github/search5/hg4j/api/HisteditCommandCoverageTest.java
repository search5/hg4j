package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage-focused tests for {@link HisteditCommand}, targeting the FOLD/ROLL/DROP action
 * branches, the empty-rules no-op, crash-safety rollback edge cases and the non-blocking
 * undo-info failure path that the base {@code HisteditCommandTest} does not exercise.
 *
 * Several of these tests also pin down real behavioral bugs uncovered while writing them
 * (see per-test comments), each verified against real `hg histedit` (mercurial 7.2, via the
 * histedit extension) on a scratch repository before the corresponding production fix.
 */
public class HisteditCommandCoverageTest {

    private static HgCommit findCommitByMessage(List<HgCommit> log, String message) {
        return log.stream()
                .filter(c -> message.equals(c.getMessage()))
                .findFirst()
                .orElse(null);
    }

    @Test
    public void callWithNoRulesIsANoOp(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Content A");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit A").setAuthor("dev").call();

        byte[] parentBefore = repo.getDirstate().getParent1();
        File journalFile = new File(repo.getStoreDir(), "journal");

        // No rules added at all -- call() must return immediately without touching the
        // repository (locks, journal, dirstate) in any way.
        new HisteditCommand(repo).call();

        assertArrayEquals(parentBefore, repo.getDirstate().getParent1(),
                "call() with zero rules must not change the working copy parent");
        assertFalse(journalFile.exists(), "call() with zero rules must never create a journal file");
    }

    @Test
    public void addRuleIgnoresNullOrEmptyHexNode(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Content A");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit A").setAuthor("dev").call();

        byte[] parentBefore = repo.getDirstate().getParent1();

        // Malformed rules (null action, null hex, empty hex) must be silently dropped,
        // leaving the rule list empty -- so call() takes the same no-op path as above.
        new HisteditCommand(repo)
                .addRule(null, "deadbeef")
                .addRule(HisteditCommand.Action.PICK, null)
                .addRule(HisteditCommand.Action.PICK, "")
                .call();

        assertArrayEquals(parentBefore, repo.getDirstate().getParent1(),
                "Only well-formed rules should ever be queued; malformed ones must be no-ops");
    }

    @Test
    public void histeditOnRepoWithNoDirstateAndBogusRuleRollsBackCleanly(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // A brand-new repository has never had anything committed, so `.hg/dirstate` does
        // not exist on disk yet, and neither do the top-level changelog/manifest revlog
        // files -- exercising the "no prior dirstate to back up" and "roll back a file that
        // did not exist before this operation (size 0)" branches together.
        File dirstateFile = new File(repoDir, ".hg/dirstate");
        assertFalse(dirstateFile.exists(), "Precondition: fresh repo must not have a dirstate file yet");

        String bogusHex = "a".repeat(40);
        assertThrows(IOException.class, () ->
                new HisteditCommand(repo).addRule(HisteditCommand.Action.PICK, bogusHex).call());

        assertFalse(new File(repo.getStoreDir(), "journal").exists(),
                "Journal must be cleaned up even when the repo had nothing to roll back to");
        assertFalse(new File(repoDir, ".hg/dirstate.backup").exists(),
                "Dirstate backup must be cleaned up even when there was no dirstate to back up");
        assertFalse(dirstateFile.exists(), "No dirstate should have been created by the failed histedit");
    }

    // Verified against real `hg histedit`: folding a commit that touches file B into one
    // that touched file A must produce a single commit containing BOTH files -- not just
    // the last-folded commit's files. (Confirmed 2026-09-01 by running
    // `hg --config extensions.histedit= histedit` with "pick A / fold B" on a scratch repo:
    // `hg manifest -r tip` listed both a.txt and b.txt.) The original HisteditCommand only
    // replayed the LAST rule's file list for an entire fold/roll group, silently dropping
    // every earlier member's changes.
    @Test
    public void foldCombinesFileChangesFromEveryFoldedCommit(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File a = new File(repoDir, "a.txt");
        Files.writeString(a.toPath(), "Content A");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit A").setAuthor("dev").call();
        String hexA = NodeIdUtil.toHex(repo.getDirstate().getParent1());

        File b = new File(repoDir, "b.txt");
        Files.writeString(b.toPath(), "Content B");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit B").setAuthor("dev").call();
        String hexB = NodeIdUtil.toHex(repo.getDirstate().getParent1());

        new HisteditCommand(repo)
                .addRule(HisteditCommand.Action.PICK, hexA)
                .addRule(HisteditCommand.Action.FOLD, hexB)
                .call();

        // Both files must survive the fold, with their original content.
        assertTrue(new File(repoDir, "a.txt").exists(), "Folding must not drop a.txt from the pick");
        assertTrue(new File(repoDir, "b.txt").exists(), "Folding must not drop b.txt from the fold");
        assertEquals("Content A", Files.readString(a.toPath()));
        assertEquals("Content B", Files.readString(b.toPath()));

        List<HgCommit> log = new LogCommand(repo).call();
        String newTipHex = NodeIdUtil.toHex(repo.getDirstate().getParent1());
        HgCommit folded = log.stream()
                .filter(c -> NodeIdUtil.toHex(c.getNodeId().getBytes()).equals(newTipHex))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Folded commit not found in log"));
        assertTrue(folded.getFiles().contains("a.txt"), "Folded commit's file list must include a.txt");
        assertTrue(folded.getFiles().contains("b.txt"), "Folded commit's file list must include b.txt");
    }

    // Verified against real `hg histedit`: rolling a commit into a pick behaves like fold
    // for file content (both files' changes are combined) but discards the rolled commit's
    // message, keeping only the pick's.
    @Test
    public void rollCombinesFileChangesButKeepsOnlyThePickMessage(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File a = new File(repoDir, "a.txt");
        Files.writeString(a.toPath(), "Content A");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit A").setAuthor("dev").call();
        String hexA = NodeIdUtil.toHex(repo.getDirstate().getParent1());

        File b = new File(repoDir, "b.txt");
        Files.writeString(b.toPath(), "Content B");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit B").setAuthor("dev2").call();
        String hexB = NodeIdUtil.toHex(repo.getDirstate().getParent1());

        new HisteditCommand(repo)
                .addRule(HisteditCommand.Action.PICK, hexA)
                .addRule(HisteditCommand.Action.ROLL, hexB)
                .call();

        assertEquals("Content A", Files.readString(a.toPath()));
        assertEquals("Content B", Files.readString(b.toPath()),
                "Rolling must still bring in the rolled commit's file changes");

        List<HgCommit> log = new LogCommand(repo).call();
        String newTipHex = NodeIdUtil.toHex(repo.getDirstate().getParent1());
        HgCommit rolled = log.stream()
                .filter(c -> NodeIdUtil.toHex(c.getNodeId().getBytes()).equals(newTipHex))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Rolled commit not found in log"));
        assertEquals("Commit A", rolled.getMessage(), "Roll must discard the rolled commit's message");
        // Verified against real hg: roll (like fold) keeps the pick's author, not the
        // rolled-in commit's.
        assertEquals("dev", rolled.getAuthor(), "Roll must keep the pick's author, not the rolled commit's");
    }

    // Verified against real `hg histedit`: folding/rolling a later commit into an earlier
    // pick keeps the PICK's author and branch for the resulting commit -- confirmed
    // 2026-09-01 with two scratch-repo runs using `hg --config extensions.histedit=
    // histedit`: (1) folding a commit by user "bob" into a pick by user "alice" produced a
    // commit authored by "alice"; (2) folding a commit made after `hg branch feature` into a
    // pick made on the default branch kept the resulting commit on "default". The original
    // HisteditCommand instead overwrote the author (and branch, via lastCommitHex) with the
    // LAST folded-in commit's values.
    @Test
    public void foldKeepsTheAuthorAndBranchOfTheAnchorPick(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File a = new File(repoDir, "a.txt");
        Files.writeString(a.toPath(), "Content A");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit A").setAuthor("alice").call();
        String hexA = NodeIdUtil.toHex(repo.getDirstate().getParent1());

        new BranchCommand(repo).setBranchName("feature").call();
        File b = new File(repoDir, "b.txt");
        Files.writeString(b.toPath(), "Content B");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit B").setAuthor("bob").call();
        String hexB = NodeIdUtil.toHex(repo.getDirstate().getParent1());

        new HisteditCommand(repo)
                .addRule(HisteditCommand.Action.PICK, hexA)
                .addRule(HisteditCommand.Action.FOLD, hexB)
                .call();

        List<HgCommit> log = new LogCommand(repo).call();
        String newTipHex = NodeIdUtil.toHex(repo.getDirstate().getParent1());
        HgCommit folded = log.stream()
                .filter(c -> NodeIdUtil.toHex(c.getNodeId().getBytes()).equals(newTipHex))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Folded commit not found in log"));

        assertEquals("alice", folded.getAuthor(), "Fold must keep the anchor pick's author");
        assertEquals("default", folded.getBranch(), "Fold must keep the anchor pick's branch");
        assertEquals("default", repo.getBranch(), "Working directory must end up on the anchor's branch");
    }

    // Verified against real `hg histedit`: a file removed by a commit that gets folded into
    // an earlier pick must stay removed in the resulting combined commit -- the fold does
    // not resurrect it.
    @Test
    public void foldAppliesAFileDeletionFromAFoldedCommit(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File a = new File(repoDir, "a.txt");
        Files.writeString(a.toPath(), "Content A");
        File b = new File(repoDir, "b.txt");
        Files.writeString(b.toPath(), "Content B");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit A+B").setAuthor("dev").call();
        String hexA = NodeIdUtil.toHex(repo.getDirstate().getParent1());

        new RemoveCommand(repo).setFile("b.txt").call();
        new CommitCommand(repo).setMessage("Remove B").setAuthor("dev").call();
        String hexRemove = NodeIdUtil.toHex(repo.getDirstate().getParent1());

        new HisteditCommand(repo)
                .addRule(HisteditCommand.Action.PICK, hexA)
                .addRule(HisteditCommand.Action.FOLD, hexRemove)
                .call();

        assertTrue(new File(repoDir, "a.txt").exists(), "a.txt must survive the fold");
        assertFalse(new File(repoDir, "b.txt").exists(), "b.txt's removal must be preserved by the fold");

        List<HgCommit> log = new LogCommand(repo).call();
        String newTipHex = NodeIdUtil.toHex(repo.getDirstate().getParent1());
        HgCommit folded = log.stream()
                .filter(c -> NodeIdUtil.toHex(c.getNodeId().getBytes()).equals(newTipHex))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Folded commit not found in log"));
        assertTrue(folded.getFiles().contains("b.txt"),
                "The removal of b.txt must be recorded as a touched file on the folded commit");
    }

    @Test
    public void dropDiscardsTheDroppedCommitsChangesAndMessageEntirely(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File a = new File(repoDir, "a.txt");
        Files.writeString(a.toPath(), "Content A");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit A").setAuthor("dev").call();
        String hexA = NodeIdUtil.toHex(repo.getDirstate().getParent1());

        File b = new File(repoDir, "b.txt");
        Files.writeString(b.toPath(), "Content B");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit B to be dropped").setAuthor("dev").call();
        String hexB = NodeIdUtil.toHex(repo.getDirstate().getParent1());

        File c = new File(repoDir, "c.txt");
        Files.writeString(c.toPath(), "Content C");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit C").setAuthor("dev").call();
        String hexC = NodeIdUtil.toHex(repo.getDirstate().getParent1());

        new HisteditCommand(repo)
                .addRule(HisteditCommand.Action.PICK, hexA)
                .addRule(HisteditCommand.Action.DROP, hexB)
                .addRule(HisteditCommand.Action.PICK, hexC)
                .call();

        assertFalse(new File(repoDir, "b.txt").exists(), "Dropped commit's file must not resurface");
        assertTrue(new File(repoDir, "a.txt").exists());
        assertTrue(new File(repoDir, "c.txt").exists());

        // This implementation appends rewritten commits rather than stripping the originals
        // from the revlog (documented in HisteditCommandTest), so the raw, unfiltered log
        // still contains the original "Commit B" entry -- exactly like real hg keeps an
        // obsoleted revision's raw changelog data on disk. What must NOT happen is for the
        // dropped commit to be part of the new tip's ancestry: it must not be reachable by
        // walking parents from the rewritten history's new head.
        String newTipHex = NodeIdUtil.toHex(repo.getDirstate().getParent1());
        List<HgCommit> reachable = new LogCommand(repo).setFollowAncestors(true).setStartRev(newTipHex).call();
        assertNotNull(findCommitByMessage(reachable, "Commit A"));
        assertNotNull(findCommitByMessage(reachable, "Commit C"));
        assertNull(findCommitByMessage(reachable, "Commit B to be dropped"),
                "A dropped commit must not be part of the rewritten history's ancestry");
    }

    // Two independent PICK groups touching the same path force the SAME filelog to be
    // journaled twice across the whole histedit run (fileSizes/journal state persists across
    // pick-group boundaries) -- exercising recordAndJournal's "already recorded, skip"
    // dedup branch on the second occurrence.
    @Test
    public void twoSeparatePicksTouchingTheSameFileDoNotDoubleJournalItsFilelog(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File a = new File(repoDir, "a.txt");
        Files.writeString(a.toPath(), "v1");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("v1").setAuthor("dev").call();
        String hexV1 = NodeIdUtil.toHex(repo.getDirstate().getParent1());

        Files.writeString(a.toPath(), "v2");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("v2").setAuthor("dev").call();
        String hexV2 = NodeIdUtil.toHex(repo.getDirstate().getParent1());

        new HisteditCommand(repo)
                .addRule(HisteditCommand.Action.PICK, hexV1)
                .addRule(HisteditCommand.Action.PICK, hexV2)
                .call();

        assertEquals("v2", Files.readString(a.toPath()),
                "Final content must reflect the later pick's version of the shared file");
        assertFalse(new File(repo.getStoreDir(), "journal").exists());
    }

    // A directory pre-occupying the "undo" file's path makes CommitCommand.writeUndoInfo
    // fail (Files.deleteIfExists on a non-empty directory throws), letting us exercise the
    // surrounding try/catch that HisteditCommand documents as "non-blocking, same as
    // CommitCommand/StripCommand" -- the histedit itself must still succeed.
    @Test
    public void undoInfoWriteFailureDoesNotFailTheHistedit(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File a = new File(repoDir, "a.txt");
        Files.writeString(a.toPath(), "Content A");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit A").setAuthor("dev").call();
        String hexA = NodeIdUtil.toHex(repo.getDirstate().getParent1());

        File undoPath = new File(repo.getStoreDir(), "undo");
        Files.deleteIfExists(undoPath.toPath());
        Files.createDirectories(undoPath.toPath());
        Files.writeString(new File(undoPath, "blocker").toPath(), "x");

        new HisteditCommand(repo)
                .addRule(HisteditCommand.Action.PICK, hexA)
                .call();

        String newTipHex = NodeIdUtil.toHex(repo.getDirstate().getParent1());
        assertNotNull(newTipHex);
        List<HgCommit> log = new LogCommand(repo).call();
        assertNotNull(findCommitByMessage(log, "Commit A"),
                "Histedit must still succeed even when writing undo info fails");
        assertFalse(new File(repo.getStoreDir(), "journal").exists(),
                "Journal must still be cleaned up despite the undo-info failure");
    }

    // A directory pre-occupying the obsstore's path makes HgObsMarker.writeMarker fail
    // (opening a FileOutputStream on a directory throws), exercising the per-marker
    // try/catch that HisteditCommand documents as "non-blocking" -- obsolescence bookkeeping
    // is best-effort and must never fail the histedit itself.
    @Test
    public void obsMarkerWriteFailureDoesNotFailTheHistedit(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File a = new File(repoDir, "a.txt");
        Files.writeString(a.toPath(), "Content A");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit A").setAuthor("dev").call();
        String hexA = NodeIdUtil.toHex(repo.getDirstate().getParent1());

        File obsstorePath = new File(repo.getStoreDir(), "obsstore");
        Files.deleteIfExists(obsstorePath.toPath());
        Files.createDirectories(obsstorePath.toPath());

        new HisteditCommand(repo)
                .addRule(HisteditCommand.Action.PICK, hexA)
                .call();

        List<HgCommit> log = new LogCommand(repo).call();
        assertNotNull(findCommitByMessage(log, "Commit A"),
                "Histedit must still succeed even when writing an obsolescence marker fails");
    }

    // Verified against the fixed base-parent computation: histedit must build the rewritten
    // range on top of the FIRST rule's true original parent, not the current dirstate tip.
    // Picking only the middle commit of a.txt/b.txt/c.txt must produce a tree with a.txt
    // (inherited from the real, untouched parent) and b.txt (the picked commit's own
    // change), but WITHOUT c.txt -- a commit the edited range never mentions at all. Using
    // the (buggy) current tip as the base would instead start from a manifest that already
    // contained all three files, silently keeping c.txt around.
    @Test
    public void pickingOnlyAMiddleCommitUsesItsRealParentNotTheCurrentTip(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File a = new File(repoDir, "a.txt");
        Files.writeString(a.toPath(), "Content A");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit A").setAuthor("dev").call();

        File b = new File(repoDir, "b.txt");
        Files.writeString(b.toPath(), "Content B");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit B").setAuthor("dev").call();
        String hexB = NodeIdUtil.toHex(repo.getDirstate().getParent1());

        File c = new File(repoDir, "c.txt");
        Files.writeString(c.toPath(), "Content C");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit C").setAuthor("dev").call();

        new HisteditCommand(repo)
                .addRule(HisteditCommand.Action.PICK, hexB)
                .call();

        assertTrue(new File(repoDir, "a.txt").exists(), "The real, untouched parent's file must survive");
        assertEquals("Content A", Files.readString(a.toPath()));
        assertTrue(new File(repoDir, "b.txt").exists(), "The picked commit's own file must be present");
        assertEquals("Content B", Files.readString(b.toPath()));
        assertFalse(new File(repoDir, "c.txt").exists(),
                "A commit the edited range never mentions must not resurface just because it used to be the tip");
    }

    // FOLD/ROLL as the very first rule of a histedit run (no preceding PICK) has no earlier
    // group to fold into, so it must open its own group exactly like a PICK would --
    // exercising the "pendingCommitMsg == null" branch inside the FOLD/ROLL handling that
    // every other test (which always leads with a PICK) never reaches.
    @Test
    public void foldAsTheOnlyRuleOpensItsOwnGroupLikeAPick(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File a = new File(repoDir, "a.txt");
        Files.writeString(a.toPath(), "Content A");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit A").setAuthor("dev").call();
        String hexA = NodeIdUtil.toHex(repo.getDirstate().getParent1());

        new HisteditCommand(repo)
                .addRule(HisteditCommand.Action.FOLD, hexA)
                .call();

        assertEquals("Content A", Files.readString(a.toPath()));
        List<HgCommit> log = new LogCommand(repo).call();
        String newTipHex = NodeIdUtil.toHex(repo.getDirstate().getParent1());
        HgCommit folded = log.stream()
                .filter(c -> NodeIdUtil.toHex(c.getNodeId().getBytes()).equals(newTipHex))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Commit not found"));
        assertEquals("Commit A", folded.getMessage());
        assertEquals("dev", folded.getAuthor());
    }

    @Test
    public void rollAsTheOnlyRuleOpensItsOwnGroupLikeAPick(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File a = new File(repoDir, "a.txt");
        Files.writeString(a.toPath(), "Content A");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit A").setAuthor("dev").call();
        String hexA = NodeIdUtil.toHex(repo.getDirstate().getParent1());

        new HisteditCommand(repo)
                .addRule(HisteditCommand.Action.ROLL, hexA)
                .call();

        assertEquals("Content A", Files.readString(a.toPath()));
        List<HgCommit> log = new LogCommand(repo).call();
        String newTipHex = NodeIdUtil.toHex(repo.getDirstate().getParent1());
        HgCommit rolled = log.stream()
                .filter(c -> NodeIdUtil.toHex(c.getNodeId().getBytes()).equals(newTipHex))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Commit not found"));
        assertEquals("Commit A", rolled.getMessage());
        assertEquals("dev", rolled.getAuthor());
    }

    // Dropping every single rule (nothing ever gets picked/folded/rolled) leaves
    // pendingCommitMsg null for the whole run, so the final flush is skipped and the working
    // copy parent falls back to the edited range's original base -- here, the repository
    // root (no commits at all). Exercises both the "no pending group to flush at the end"
    // branch and the "new tip not found in the changelog" branch, and also exercises
    // close() via try-with-resources.
    @Test
    public void droppingTheEntireHistoryLeavesTheRepositoryAtItsRoot(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File a = new File(repoDir, "a.txt");
        Files.writeString(a.toPath(), "Content A");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit A").setAuthor("dev").call();
        String hexA = NodeIdUtil.toHex(repo.getDirstate().getParent1());

        try (HisteditCommand cmd = new HisteditCommand(repo)) {
            cmd.addRule(HisteditCommand.Action.DROP, hexA).call();
        }

        assertFalse(new File(repoDir, "a.txt").exists(),
                "The only file, whose only commit was dropped, must be gone from the working copy");
        assertTrue(NodeIdUtil.isAllZero(repo.getDirstate().getParent1()),
                "Dropping the entire history must leave the working copy parented on the null revision");
    }
}
