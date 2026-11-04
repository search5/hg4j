package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bookmark changes hold the working copy lock and the store lock like real hg's {@code bookmark} does, so writers that run at the
 * same moment neither lose each other's change nor write while somebody else holds a lock.
 */
class BookmarkConcurrencyTest {

    @TempDir
    Path tempDir;

    private HgRepository repositoryWithOneCommit() throws Exception {
        File dir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(dir).call();
        Files.writeString(new File(dir, "a.txt").toPath(), "a\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("one").setAuthor("t").call();
        return repo;
    }

    @Test
    void writersAtTheSameMomentLoseNoBookmark() throws Exception {
        HgRepository repo = repositoryWithOneCommit();
        Files.writeString(new File(tempDir.toFile(), ".hg/hgrc").toPath(), "[ui]\ntimeout = 60\n"); // wait for the lock like hg does
        int writers = 12;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            String name = "mark" + i;
            done.add(pool.submit(() -> {
                // each writer has its own repository object, as separate processes or requests would have
                try (HgRepository own = new HgRepository(tempDir.toFile())) {
                    start.await();
                    new BookmarkCommand(own).setBookmarkName(name).call();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : done) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        Map<String, String> bookmarks = new BookmarkCommand(repo).list();
        for (int i = 0; i < writers; i++) {
            assertTrue(bookmarks.containsKey("mark" + i), "lost bookmark mark" + i + " in " + bookmarks.keySet());
        }
    }

    @Test
    void aChangeWaitsForTheStoreLockAndFailsWhenItStaysHeld() throws Exception {
        HgRepository repo = repositoryWithOneCommit();
        Files.writeString(new File(tempDir.toFile(), ".hg/hgrc").toPath(), "[ui]\ntimeout = 1\n");
        try (HgRepository own = new HgRepository(tempDir.toFile())) {
            long begin = System.currentTimeMillis();
            Thread holder = new Thread(() -> {
                try (HgLock held = own.lockStore()) {
                    Thread.sleep(3000);
                } catch (Exception ignored) {
                }
            });
            holder.start();
            Thread.sleep(300);
            try (HgRepository other = new HgRepository(tempDir.toFile())) {
                assertThrows(HgLockException.class, () -> new BookmarkCommand(other).setBookmarkName("late").call());
            }
            long waited = System.currentTimeMillis() - begin;
            assertTrue(waited >= 1000, "it must wait for ui.timeout before giving up, waited " + waited + " ms");
            holder.join();
        }
        assertFalse(new BookmarkCommand(repo).list().containsKey("late"));
    }

    @Test
    void aChangeProceedsOnceTheLockIsReleased() throws Exception {
        HgRepository repo = repositoryWithOneCommit();
        Files.writeString(new File(tempDir.toFile(), ".hg/hgrc").toPath(), "[ui]\ntimeout = 10\n");
        Thread holder;
        try (HgRepository own = new HgRepository(tempDir.toFile())) {
            holder = new Thread(() -> {
                try (HgLock held = own.lockWorkingCopy()) {
                    Thread.sleep(1200);
                } catch (Exception ignored) {
                }
            });
            holder.start();
            Thread.sleep(300);
            try (HgRepository other = new HgRepository(tempDir.toFile())) {
                new BookmarkCommand(other).setBookmarkName("after").call();
            }
            holder.join();
        }
        assertTrue(new BookmarkCommand(repo).list().containsKey("after"));
    }

    @Test
    void listingNeedsNoLock() throws Exception {
        HgRepository repo = repositoryWithOneCommit();
        try (HgLock w = repo.lockWorkingCopy(); HgLock s = repo.lockStore()) {
            Thread other = new Thread(() -> {
            });
            other.start();
            other.join();
            try (HgRepository own = new HgRepository(tempDir.toFile())) {
                assertNotNull(new BookmarkCommand(own).list());
            }
        }
    }
}
