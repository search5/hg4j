package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What Mercurial reads before it locks and what it locks at all: {@code hg phase} without a phase to set shows the phase without any
 * lock; setting it takes the store lock; {@code merge --abort} and {@code revert} read and check first and lock when they change the
 * working copy.
 */
class ReadBeforeLockTest {

    @TempDir
    Path tmp;

    private HgRepository repo;
    private byte[] c0;
    private byte[] c1;

    @BeforeEach
    void setUp() throws Exception {
        File dir = tmp.toFile();
        repo = Hg.init().setDirectory(dir).call();
        Files.writeString(new File(dir, "f.txt").toPath(), "a\n");
        new AddCommand(repo).call();
        c0 = new CommitCommand(repo).setAuthor("t").setMessage("base").call();
        Files.writeString(new File(dir, "f.txt").toPath(), "b\n");
        c1 = new CommitCommand(repo).setAuthor("t").setMessage("two").call();
    }

    private void holding(boolean workingCopy, ThrowingRunnable body) throws Exception {
        CountDownLatch taken = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            try (HgLock ignored = workingCopy ? repo.lockWorkingCopy() : repo.lockStore()) {
                taken.countDown();
                release.await();
            } catch (Throwable t) {
                failure.set(t);
                taken.countDown();
            }
        });
        holder.start();
        assertTrue(taken.await(10, TimeUnit.SECONDS));
        assertNull(failure.get());
        try {
            body.run();
        } finally {
            release.countDown();
            holder.join();
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void showingAPhaseTakesNoLockButChangingItTakesTheStoreLock() throws Exception {
        holding(false, () -> {
            assertEquals(1, new PhaseCommand(repo).setRevision(NodeIdUtil.toHex(c1)).call(), "shown while the store is locked elsewhere");
            assertThrows(Exception.class, () -> new PhaseCommand(repo).setRevision(NodeIdUtil.toHex(c1)).setPhase(0).call());
        });
        PhaseCommand change = new PhaseCommand(repo).setRevision(NodeIdUtil.toHex(c1)).setPhase(0);
        assertEquals(0, change.call());
        assertEquals(java.util.List.of("phase changed for 2 changesets\n"), change.getNotes());
    }

    @Test
    void phaseResolvesTheRevisionBeforeItLocks() throws Exception {
        holding(false, () -> {
            Exception e = assertThrows(Exception.class, () -> new PhaseCommand(repo).setRevision("ffffffffffffffffffffffffffffffffffffffff").setPhase(0).call());
            assertTrue(e.getMessage().contains("Revision not found"), e.getMessage());
        });
    }

    @Test
    void revertChecksItsArgumentsBeforeItLocks() throws Exception {
        holding(true, () -> {
            assertThrows(Exception.class, () -> new RevertCommand(repo).setAll(true).setRevision("ffffffffffffffffffffffffffffffffffffffff").callSelected());
            // a valid request meets the lock
            assertThrows(HgLockException.class, () -> new RevertCommand(repo).setAll(true).callSelected());
        });
    }

    @Test
    void mergeAbortReadsFirstAndLocksToUpdate() throws Exception {
        // no merge in progress: refused before any lock is needed
        holding(true, () -> {
            HgValidationException e = assertThrows(HgValidationException.class, () -> new MergeCommand(repo).abort());
            assertEquals("no merge in progress", e.getMessage());
        });
    }
}
