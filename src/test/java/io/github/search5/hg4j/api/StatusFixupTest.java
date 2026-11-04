package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code status} as the original does it: a file whose mtime changed but whose content did not is clean (it is looked up, not reported
 * as modified), and the refreshed stat is written to the dirstate when the working copy lock is free ({@code wlock(wait=False)}); when it
 * is not, status still answers and leaves the dirstate alone.
 */
class StatusFixupTest {

    @TempDir
    Path tmp;

    private HgRepository setUp() throws Exception {
        File dir = tmp.toFile();
        HgRepository repo = Hg.init().setDirectory(dir).call();
        Files.writeString(new File(dir, "f.txt").toPath(), "content\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("t").setMessage("one").call();
        // the stat the commit recorded is old enough to be reliable
        File f = new File(dir, "f.txt");
        assertTrue(f.setLastModified(f.lastModified() - 60_000));
        return repo;
    }

    @Test
    void aTouchedFileWithTheSameContentIsCleanAndItsStatIsRecorded() throws Exception {
        HgRepository repo = setUp();
        new StatusCommand(repo).call(); // settles the stat of the old file
        File f = new File(tmp.toFile(), "f.txt");
        assertTrue(f.setLastModified(f.lastModified() - 30_000)); // touched: another mtime, same size and content

        Status status = new StatusCommand(repo).call();
        assertTrue(status.getClean().contains("f.txt"), "clean: " + status.getModified());
        assertTrue(status.getModified().isEmpty());
        long recorded = repo.getDirstate().getEntries().get("f.txt").getTime();
        assertEquals(f.lastModified() / 1000, recorded, "the new stat was written to the dirstate");
    }

    @Test
    void aChangedFileIsStillModified() throws Exception {
        HgRepository repo = setUp();
        File f = new File(tmp.toFile(), "f.txt");
        Files.writeString(f.toPath(), "other\n");
        assertTrue(f.setLastModified(f.lastModified() - 20_000));
        assertTrue(new StatusCommand(repo).call().getModified().contains("f.txt"));
    }

    @Test
    void withTheWorkingCopyLockHeldElsewhereStatusAnswersAndLeavesTheDirstate() throws Exception {
        HgRepository repo = setUp();
        new StatusCommand(repo).call();
        File f = new File(tmp.toFile(), "f.txt");
        assertTrue(f.setLastModified(f.lastModified() - 30_000));
        long before = repo.getDirstate().getEntries().get("f.txt").getTime();

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            try (HgLock ignored = repo.lockWorkingCopy()) {
                held.countDown();
                release.await();
            } catch (Throwable t) {
                failure.set(t);
                held.countDown();
            }
        });
        holder.start();
        assertTrue(held.await(10, TimeUnit.SECONDS));
        assertNull(failure.get());
        try {
            Status status = new StatusCommand(repo).call();
            assertTrue(status.getClean().contains("f.txt"));
        } finally {
            release.countDown();
            holder.join();
        }
        assertEquals(before, repo.getDirstate().getEntries().get("f.txt").getTime(), "the lock was not available: nothing was written");
    }

    @Test
    void realMercurialReadsTheDirstateWrittenByStatus() throws Exception {
        assumeTrue(hgAvailable());
        HgRepository repo = setUp();
        new StatusCommand(repo).call();
        File f = new File(tmp.toFile(), "f.txt");
        assertTrue(f.setLastModified(f.lastModified() - 30_000));
        new StatusCommand(repo).call();
        Process p = new ProcessBuilder("hg", "status", "-A").directory(tmp.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        assertEquals("C f.txt\n", out);
    }

    private static boolean hgAvailable() {
        try {
            Process p = new ProcessBuilder("hg", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
