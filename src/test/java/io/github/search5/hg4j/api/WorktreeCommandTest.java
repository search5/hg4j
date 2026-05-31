package io.github.search5.hg4j.api;

import io.github.search5.hg4j.core.HgRepository;
import io.github.search5.hg4j.core.Revlog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class WorktreeCommandTest {

    @Test
    public void testWorktreeStoreSharingAndIndependentCommits(@TempDir Path tempDir) throws Exception {
        File mainRepoDir = tempDir.resolve("main_repo").toFile();
        File worktreeDir = tempDir.resolve("worktree_repo").toFile();

        // 1. Create main repository and add an initial commit
        HgRepository mainRepo = Hg.init().setDirectory(mainRepoDir).call();
        File f1 = new File(mainRepoDir, "a.txt");
        Files.writeString(f1.toPath(), "Main repository initial file");
        new AddCommand(mainRepo).call();
        byte[] commitNode1 = new CommitCommand(mainRepo).setMessage("Initial commit in main").call();

        // 2. Create worktree linked back to main
        HgRepository worktreeRepo = Hg.wrap(mainRepo).worktree()
                .setNewWorktreeDir(worktreeDir)
                .call();
        assertNotNull(worktreeRepo, "Worktree creation must return a valid repository handle");

        // 3. Verify history is shared in the worktree
        Revlog wtChangelog = worktreeRepo.getRevlog(
                new File(worktreeRepo.getStoreDir(), "00changelog.i"),
                new File(worktreeRepo.getStoreDir(), "00changelog.d")
        );
        assertEquals(1, wtChangelog.getRevisionCount(), "Worktree must share main's store and see 1 revision.");
        assertArrayEquals(commitNode1, wtChangelog.getIndexRecord(0).getNodeId());

        // 4. Perform an independent commit in the worktree
        File f2 = new File(worktreeDir, "b.txt");
        Files.writeString(f2.toPath(), "Worktree independent file");
        new AddCommand(worktreeRepo).call();
        byte[] commitNode2 = new CommitCommand(worktreeRepo).setMessage("Independent commit in worktree").call();

        // 5. Verify the main repository instantly sees the new commit through the shared store!
        mainRepo.clearRevlogCache();
        Revlog mainChangelog = mainRepo.getRevlog(
                new File(mainRepo.getStoreDir(), "00changelog.i"),
                new File(mainRepo.getStoreDir(), "00changelog.d")
        );
        assertEquals(2, mainChangelog.getRevisionCount(), "Main repository must instantly see the worktree's commit via shared store.");
        assertArrayEquals(commitNode2, mainChangelog.getIndexRecord(1).getNodeId());
    }
}
