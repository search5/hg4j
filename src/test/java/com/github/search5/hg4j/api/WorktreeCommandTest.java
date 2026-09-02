package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
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

    @Test
    public void testCallRejectsWhenNoWorktreeDirSpecified(@TempDir Path tempDir) throws Exception {
        HgRepository mainRepo = Hg.init().setDirectory(tempDir.resolve("main_repo").toFile()).call();

        WorktreeCommand cmd = new WorktreeCommand(mainRepo);

        assertThrows(IllegalStateException.class, cmd::call);
    }

    @Test
    public void testCallRejectsNonEmptyTargetDirectory(@TempDir Path tempDir) throws Exception {
        HgRepository mainRepo = Hg.init().setDirectory(tempDir.resolve("main_repo").toFile()).call();

        File worktreeDir = tempDir.resolve("worktree_repo").toFile();
        assertTrue(worktreeDir.mkdirs());
        Files.writeString(worktreeDir.toPath().resolve("preexisting.txt"), "already here");

        WorktreeCommand cmd = new WorktreeCommand(mainRepo).setNewWorktreeDir(worktreeDir);

        IOException ex = assertThrows(IOException.class, cmd::call);
        assertTrue(ex.getMessage().contains("must be empty or non-existent"));
    }

    @Test
    public void testCallAcceptsPreexistingEmptyTargetDirectory(@TempDir Path tempDir) throws Exception {
        HgRepository mainRepo = Hg.init().setDirectory(tempDir.resolve("main_repo").toFile()).call();

        File worktreeDir = tempDir.resolve("worktree_repo").toFile();
        assertTrue(worktreeDir.mkdirs());

        HgRepository worktreeRepo = new WorktreeCommand(mainRepo).setNewWorktreeDir(worktreeDir).call();

        assertNotNull(worktreeRepo);
        assertTrue(new File(worktreeDir, ".hg/sharedpath").exists());
    }

    @Test
    public void testCallSkipsRequiresCopyWhenMainHasNone(@TempDir Path tempDir) throws Exception {
        File mainRepoDir = tempDir.resolve("main_repo").toFile();
        assertTrue(new File(mainRepoDir, ".hg").mkdirs());
        HgRepository mainRepo = new HgRepository(mainRepoDir);

        File worktreeDir = tempDir.resolve("worktree_repo").toFile();
        HgRepository worktreeRepo = new WorktreeCommand(mainRepo).setNewWorktreeDir(worktreeDir).call();

        assertNotNull(worktreeRepo);
        assertFalse(new File(worktreeDir, ".hg/requires").exists(),
                "requires must not be copied when the main repository has none");
    }

    @Test
    public void testCallDefaultsDirstateWhenMainHasNone(@TempDir Path tempDir) throws Exception {
        File mainRepoDir = tempDir.resolve("main_repo").toFile();
        assertTrue(new File(mainRepoDir, ".hg").mkdirs());
        HgRepository mainRepo = new HgRepository(mainRepoDir);

        File worktreeDir = tempDir.resolve("worktree_repo").toFile();
        new WorktreeCommand(mainRepo).setNewWorktreeDir(worktreeDir).call();

        byte[] newDirstate = Files.readAllBytes(new File(worktreeDir, ".hg/dirstate").toPath());
        assertArrayEquals(new byte[40], newDirstate,
                "dirstate must default to 40 zero bytes when the main repository has none");
    }

    @Test
    public void testCallDefaultsDirstateWhenMainDirstateIsTooShort(@TempDir Path tempDir) throws Exception {
        File mainRepoDir = tempDir.resolve("main_repo").toFile();
        File mainHgDir = new File(mainRepoDir, ".hg");
        assertTrue(mainHgDir.mkdirs());
        Files.write(new File(mainHgDir, "dirstate").toPath(), new byte[]{1, 2, 3});
        HgRepository mainRepo = new HgRepository(mainRepoDir);

        File worktreeDir = tempDir.resolve("worktree_repo").toFile();
        new WorktreeCommand(mainRepo).setNewWorktreeDir(worktreeDir).call();

        byte[] newDirstate = Files.readAllBytes(new File(worktreeDir, ".hg/dirstate").toPath());
        assertArrayEquals(new byte[40], newDirstate,
                "dirstate must default to 40 zero bytes when the main repository's dirstate is shorter than 40 bytes");
    }
}
