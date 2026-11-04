package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** {@code hg unshare} (cmd_impls/clone.py unshare + copy_store). */
class UnshareCommandTest {

    @TempDir
    Path tempDir;

    private File main;
    private File shared;

    private void commit(File dir, String file, String content, String message) throws Exception {
        try (HgRepository repo = new HgRepository(dir)) {
            Files.writeString(new File(dir, file).toPath(), content);
            new AddCommand(repo).call();
            new CommitCommand(repo).setMessage(message).setAuthor("t").call();
        }
    }

    private int revisions(File dir) throws Exception {
        try (HgRepository repo = new HgRepository(dir)) {
            return repo.getRevlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"))
                    .getRevisionCount();
        }
    }

    private void createShare() throws Exception {
        main = tempDir.resolve("main").toFile();
        Hg.init().setDirectory(main).call().close();
        commit(main, "a.txt", "one\n", "r0");
        commit(main, "b.txt", "two\n", "r1");
        shared = tempDir.resolve("shared").toFile();
        try (HgRepository repo = new HgRepository(main)) {
            new WorktreeCommand(repo).setNewWorktreeDir(shared).call().close();
        }
    }

    @Test
    void theRepositoryGetsItsOwnStoreAndLosesTheSharedPath() throws Exception {
        createShare();
        assertTrue(new File(shared, ".hg/sharedpath").exists());
        try (HgRepository repo = new HgRepository(shared)) {
            HgRepository unshared = new UnshareCommand(repo).call();
            assertEquals(new File(shared, ".hg/store").getCanonicalFile(), unshared.getStoreDir().getCanonicalFile());
        }
        assertFalse(new File(shared, ".hg/sharedpath").exists());
        assertTrue(new File(shared, ".hg/sharedpath.old").exists());
        assertFalse(Files.readAllLines(new File(shared, ".hg/requires").toPath()).contains("shared"));
        assertEquals(2, revisions(shared));
        assertEquals("one\n", Files.readString(new File(shared, "a.txt").toPath()));
        assertFalse(new File(shared, ".hg/store/lock").exists(), "the lock on the new store is released");
        assertFalse(new File(main, ".hg/store/lock").exists(), "and so is the one on the shared store");
    }

    @Test
    void afterwardsTheTwoRepositoriesAreIndependent() throws Exception {
        createShare();
        try (HgRepository repo = new HgRepository(shared)) {
            new UnshareCommand(repo).call();
        }
        // the copy may be made of hard links: writing to one repository must not change the other (hg's vfs breaks the link first)
        commit(shared, "c.txt", "three\n", "only in the unshared repository");
        assertEquals(3, revisions(shared));
        assertEquals(2, revisions(main), "the original repository did not change");
        commit(main, "d.txt", "four\n", "only in the original");
        assertEquals(3, revisions(main));
        assertEquals(3, revisions(shared));
        try (HgRepository m = new HgRepository(main); HgRepository s = new HgRepository(shared)) {
            assertEquals(List.of(), new VerifyCommand(m).call());
            assertEquals(List.of(), new VerifyCommand(s).call());
        }
    }

    @Test
    void aRepositoryThatIsNotSharedIsRefused() throws Exception {
        File plain = tempDir.resolve("plain").toFile();
        Hg.init().setDirectory(plain).call().close();
        try (HgRepository repo = new HgRepository(plain)) {
            assertEquals("this is not a shared repo",
                    assertThrows(HgValidationException.class, () -> new UnshareCommand(repo).call()).getMessage());
        }
    }

    @Test
    void theSharedStoreLockIsNeededAndWaitedFor() throws Exception {
        createShare();
        Files.writeString(new File(shared, ".hg/hgrc").toPath(), "[ui]\ntimeout = 1\n");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (HgRepository other = new HgRepository(main); HgLock l = other.lockStore()) {
                held.countDown();
                release.await();
            } catch (Exception ignored) {
                held.countDown();
            }
        });
        holder.start();
        assertTrue(held.await(10, TimeUnit.SECONDS));
        try (HgRepository repo = new HgRepository(shared)) {
            assertThrows(HgLockException.class, () -> new UnshareCommand(repo).call());
        } finally {
            release.countDown();
            holder.join();
        }
        assertTrue(new File(shared, ".hg/sharedpath").exists(), "nothing changes without the lock");
    }

    @Test
    void writingToAHardLinkedCopyLeavesTheOriginalAlone() throws Exception {
        // what a local `hg clone` produces: another directory whose store files are the same inodes as the original's
        org.junit.jupiter.api.Assumptions.assumeTrue(new File("/bin/cp").exists() || new File("/usr/bin/cp").exists());
        File original = tempDir.resolve("original").toFile();
        Hg.init().setDirectory(original).call().close();
        commit(original, "a.txt", "one\n", "r0");
        File copy = tempDir.resolve("hardlinked").toFile();
        Process cp = new ProcessBuilder("cp", "-al", original.getPath(), copy.getPath()).redirectErrorStream(true).start();
        cp.getInputStream().readAllBytes();
        org.junit.jupiter.api.Assumptions.assumeTrue(cp.waitFor() == 0);
        File changelog = new File(original, ".hg/store/00changelog.i");
        assertTrue(Files.isSameFile(changelog.toPath(), new File(copy, ".hg/store/00changelog.i").toPath()), "precondition: hard linked");
        byte[] before = Files.readAllBytes(changelog.toPath());
        byte[] fncacheBefore = Files.readAllBytes(new File(original, ".hg/store/fncache").toPath());

        commit(copy, "new-file.txt", "added in the copy\n", "only in the copy");

        assertArrayEquals(before, Files.readAllBytes(changelog.toPath()), "the original changelog must not change");
        assertArrayEquals(fncacheBefore, Files.readAllBytes(new File(original, ".hg/store/fncache").toPath()), "nor its fncache");
        assertEquals(1, revisions(original));
        assertEquals(2, revisions(copy));
        try (HgRepository o = new HgRepository(original); HgRepository c = new HgRepository(copy)) {
            assertEquals(List.of(), new VerifyCommand(o).call());
            assertEquals(List.of(), new VerifyCommand(c).call());
        }
    }
}
