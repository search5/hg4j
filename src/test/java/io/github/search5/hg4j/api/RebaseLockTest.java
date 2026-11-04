package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgMergeConflictException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The locks of {@code hg rebase} as in Mercurial ({@code _origrebase}): the working copy lock and then the store lock, taken before
 * anything is read, held until the command ends (also when it stops for a conflict), and taken again by {@code --continue} and
 * {@code --abort}. Another process (here another thread) holding either lock makes the command fail without touching the repository.
 */
class RebaseLockTest {

    @TempDir
    Path tmp;

    private HgRepository repo;
    private byte[] c1;
    private byte[] c2;

    @BeforeEach
    void setUp() throws Exception {
        File dir = tmp.toFile();
        repo = Hg.init().setDirectory(dir).call();
        Files.writeString(new File(dir, "f.txt").toPath(), "a\nb\nc\n");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("t").setMessage("base").call();
        Files.writeString(new File(dir, "f.txt").toPath(), "a\nX\nc\n");
        c1 = new CommitCommand(repo).setAuthor("t").setMessage("src").call();
        new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(c0)).setForce(true).call();
        Files.writeString(new File(dir, "f.txt").toPath(), "a\nY\nc\n");
        c2 = new CommitCommand(repo).setAuthor("t").setMessage("dest").call();
        new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(c1)).setForce(true).call();
    }

    /** Runs {@code action} on another thread that holds the lock until {@code release} is counted down. */
    private interface Held {
        void whileHeld(Runnable body) throws Exception;
    }

    private Held holding(boolean workingCopy) {
        return body -> {
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
            assertTrue(taken.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertNull(failure.get());
            try {
                body.run();
            } finally {
                release.countDown();
                holder.join();
            }
        };
    }

    private RebaseCommand rebase() {
        return new RebaseCommand(repo).setSource(c1).setTarget(c2);
    }

    @Test
    void theWorkingCopyLockHeldElsewhereStopsTheRebaseBeforeAnythingIsDone() throws Exception {
        holding(true).whileHeld(() -> assertThrows(HgLockException.class, () -> rebase().call()));
        assertFalse(new File(repo.getHgDir(), "rebasestate").exists());
        assertEquals(3, new Revlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d")).getRevisionCount());
    }

    @Test
    void theStoreLockHeldElsewhereStopsTheRebaseToo() throws Exception {
        holding(false).whileHeld(() -> assertThrows(HgLockException.class, () -> rebase().call()));
        assertFalse(new File(repo.getHgDir(), "rebasestate").exists());
        assertFalse(new File(repo.getStoreDir(), "journal").exists());
    }

    @Test
    void aRebaseThatStopsOnAConflictReleasesBothLocksAndKeepsTheState() throws Exception {
        assertThrows(HgMergeConflictException.class, () -> rebase().call());
        assertTrue(new File(repo.getHgDir(), "rebasestate").isFile());
        // both locks are free again: another thread can take them
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try (HgLock w = repo.lockWorkingCopy(); HgLock s = repo.lockStore()) {
                assertNotNull(w);
                assertNotNull(s);
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        other.start();
        other.join();
        assertNull(failure.get());
    }

    @Test
    void continueAndAbortTakeTheLocksAndLeaveTheStateWhenTheyCannot() throws Exception {
        assertThrows(HgMergeConflictException.class, () -> rebase().call());
        byte[] state = Files.readAllBytes(new File(repo.getHgDir(), "rebasestate").toPath());
        holding(true).whileHeld(() -> {
            assertThrows(HgLockException.class, () -> new RebaseCommand(repo).continueRebase());
            assertThrows(HgLockException.class, () -> new RebaseCommand(repo).abort());
        });
        holding(false).whileHeld(() -> assertThrows(HgLockException.class, () -> new RebaseCommand(repo).abort()));
        assertArrayEquals(state, Files.readAllBytes(new File(repo.getHgDir(), "rebasestate").toPath()));
        new RebaseCommand(repo).abort();
        assertFalse(new File(repo.getHgDir(), "rebasestate").exists());
    }

    @Test
    void anAbandonedTransactionIsNotRecoveredByTheRebase() throws Exception {
        Files.writeString(new File(repo.getStoreDir(), "journal").toPath(), "");
        assertThrows(io.github.search5.hg4j.errors.HgAbandonedTransactionException.class, () -> rebase().call());
        assertTrue(new File(repo.getStoreDir(), "journal").exists());
        assertFalse(new File(repo.getHgDir(), "rebasestate").exists());
    }
}
