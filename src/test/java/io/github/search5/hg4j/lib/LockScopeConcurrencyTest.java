package io.github.search5.hg4j.lib;

import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.AddremoveCommand;
import io.github.search5.hg4j.api.AmendCommand;
import io.github.search5.hg4j.api.BisectCommand;
import io.github.search5.hg4j.api.BranchCommand;
import io.github.search5.hg4j.api.PhaseCommand;
import io.github.search5.hg4j.api.RemoveCommand;
import io.github.search5.hg4j.api.RenameCommand;
import io.github.search5.hg4j.api.FetchCommand;
import io.github.search5.hg4j.api.CensorCommand;
import io.github.search5.hg4j.api.GcCommand;
import io.github.search5.hg4j.api.RecoverCommand;
import io.github.search5.hg4j.api.UnbundleCommand;
import io.github.search5.hg4j.api.VerifyCommand;
import io.github.search5.hg4j.api.GraftCommand;
import io.github.search5.hg4j.api.RollbackCommand;
import io.github.search5.hg4j.api.ShelveCommand;
import io.github.search5.hg4j.api.UpdateCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.HgHook;
import io.github.search5.hg4j.api.TagCommand;
import io.github.search5.hg4j.api.TagsCommand;
import io.github.search5.hg4j.bundle.ChangegroupParser;
import io.github.search5.hg4j.errors.HgHookRejectedException;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.transport.HgLocalClient;
import io.github.search5.hg4j.util.NodeIdUtil;
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
 * The locks of the commands that change a repository cover what real hg's cover: the whole of a command, not only its writes. Each
 * test starts two actors at once and checks that neither damages what the other did.
 */
class LockScopeConcurrencyTest {

    @TempDir
    Path tempDir;

    private byte[] commitFile(File dir, String name, String content, String message) throws Exception {
        try (HgRepository repo = new HgRepository(dir)) {
            Files.writeString(new File(dir, name).toPath(), content);
            new AddCommand(repo).call();
            return new CommitCommand(repo).setMessage(message).setAuthor("t").call();
        }
    }

    /** Like `[ui] timeout` in a user's hgrc: commands wait that many seconds for a lock another process holds (hg's default is 600). */
    private static void waitForLocksUpTo(File dir, int seconds) throws Exception {
        Files.writeString(new File(dir, ".hg/hgrc").toPath(), "[ui]\ntimeout = " + seconds + "\n");
    }

    private File newRepository(String name) throws Exception {
        File dir = tempDir.resolve(name).toFile();
        Hg.init().setDirectory(dir).call().close();
        return dir;
    }

    private byte[] bundleOf(File dir) throws Exception {
        try (HgRepository repo = new HgRepository(dir); HgLocalClient client = new HgLocalClient(repo)) {
            return client.getBundle(null, null, null);
        }
    }

    @Test
    void aPushRejectedByAHookDoesNotUndoAPushThatArrivesMeanwhile() throws Exception {
        File sourceA = newRepository("a");
        File sourceB = newRepository("b");
        commitFile(sourceA, "a.txt", "a\n", "from a");
        byte[] nodeB = commitFile(sourceB, "b.txt", "b\n", "from b");
        byte[] bundleA = bundleOf(sourceA);
        byte[] bundleB = bundleOf(sourceB);
        File target = newRepository("target");
        waitForLocksUpTo(target, 60);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        // the hook keeps its push open for a while, then rejects it
        HgHook slowRejection = ctx -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return false;
        };
        Future<?> rejected = pool.submit(() -> {
            try (HgRepository repo = new HgRepository(target); HgLocalClient client = new HgLocalClient(repo)) {
                client.pushWithHooks(bundleA, List.of(), List.of(), List.of(slowRejection), List.of(), null);
            }
            return null;
        });
        Thread.sleep(500);
        Future<HgLocalClient.PushResult> accepted = pool.submit(() -> {
            try (HgRepository repo = new HgRepository(target); HgLocalClient client = new HgLocalClient(repo)) {
                return client.pushWithHooks(bundleB, List.of(), List.of(), List.of());
            }
        });
        Exception failure = assertThrows(Exception.class, () -> rejected.get(60, TimeUnit.SECONDS));
        assertInstanceOf(HgHookRejectedException.class, failure.getCause());
        assertEquals(1, accepted.get(60, TimeUnit.SECONDS).importedNodeHexes.size());
        pool.shutdownNow();

        try (HgRepository repo = new HgRepository(target)) {
            io.github.search5.hg4j.storage.Revlog changelog = repo.getRevlog(
                    new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"));
            assertEquals(1, changelog.getRevisionCount(), "only the accepted push may remain");
            assertNotEquals(-1, changelog.findRevision(nodeB), "the accepted push was undone by the other one's rollback");
        }
    }

    @Test
    void takingTheLockAgainDoesNotRecoverTheJournalOfTheRunningTransaction() throws Exception {
        File dir = newRepository("journal");
        try (HgRepository repo = new HgRepository(dir)) {
            File fncache = new File(repo.getStoreDir(), "fncache");
            Files.writeString(fncache.toPath(), "live");
            Files.writeString(new File(repo.getStoreDir(), "fncache.backup").toPath(), "stale");
            File journal = new File(repo.getStoreDir(), "journal");

            try (HgLock held = repo.lockStore()) {
                // the transaction that holds the lock starts and journals its work
                Files.writeString(journal.toPath(), "fncache\n");
                // the journal found here was written by this very transaction
                try (HgLock again = repo.lockStore()) {
                    assertEquals("live", Files.readString(fncache.toPath()));
                    assertTrue(journal.exists());
                }
            }
            // a lock taken anew does not recover it, as hg's repo.lock() does not: the transaction would be refused, and `recover` cleans up
            assertThrows(io.github.search5.hg4j.errors.HgAbandonedTransactionException.class, repo::lockStore);
            assertEquals("live", Files.readString(fncache.toPath()));
            assertTrue(journal.exists());
            new io.github.search5.hg4j.api.RecoverCommand(repo).call();
            assertEquals("stale", Files.readString(fncache.toPath()));
            assertFalse(journal.exists());
        }
    }

    @Test
    void tagsMadeAtTheSameMomentAreAllKept() throws Exception {
        File dir = newRepository("tags");
        byte[] node = commitFile(dir, "a.txt", "a\n", "one");
        waitForLocksUpTo(dir, 120);
        int writers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            String name = "tag" + i;
            done.add(pool.submit(() -> {
                try (HgRepository repo = new HgRepository(dir)) {
                    start.await();
                    new TagCommand(repo).setTagName(name).setNodeId(node).call();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : done) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        String hgtags = Files.readString(new File(dir, ".hgtags").toPath());
        for (int i = 0; i < writers; i++) {
            assertTrue(hgtags.contains(" tag" + i + "\n"), "tag" + i + " lost from:\n" + hgtags);
        }
    }

    @Test
    void bisectWaitsForTheLocksAndFailsWhenTheyStayHeld() throws Exception {
        File dir = newRepository("bisect");
        byte[] good = commitFile(dir, "a.txt", "1\n", "r0");
        commitFile(dir, "a.txt", "2\n", "r1");
        byte[] bad = commitFile(dir, "a.txt", "3\n", "r2");
        Files.writeString(new File(dir, ".hg/hgrc").toPath(), "[ui]\ntimeout = 1\n");
        // another thread (a lock is re-entrant only for the thread that holds it) keeps the working copy lock for a while
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (HgRepository other = new HgRepository(dir); HgLock lock = other.lockWorkingCopy()) {
                held.countDown();
                release.await();
            } catch (Exception ignored) {
                held.countDown();
            }
        });
        holder.start();
        assertTrue(held.await(10, TimeUnit.SECONDS));
        try (HgRepository repo = new HgRepository(dir)) {
            assertThrows(HgLockException.class, () -> new BisectCommand(repo).setGood(good).setBad(bad).next());
        }
        release.countDown();
        holder.join();
        try (HgRepository repo = new HgRepository(dir)) {
            assertNotNull(new BisectCommand(repo).setGood(good).setBad(bad).next());
        }
    }

    private interface Action {
        void run(HgRepository repo, String node) throws Exception;
    }

    /** Runs {@code action} while another thread keeps {@code lock} ("wlock" or "lock"); it has to give up after {@code ui.timeout}. */
    private void assertWaitsForLock(String lock, Action action) throws Exception {
        File dir = newRepository("wait-" + lock + "-" + System.nanoTime());
        byte[] node = commitFile(dir, "a.txt", "1\n", "r0");
        Files.writeString(new File(dir, ".hg/hgrc").toPath(), "[ui]\ntimeout = 1\n");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (HgRepository other = new HgRepository(dir);
                 HgLock l = lock.equals("wlock") ? other.lockWorkingCopy() : other.lockStore()) {
                held.countDown();
                release.await();
            } catch (Exception ignored) {
                held.countDown();
            }
        });
        holder.start();
        assertTrue(held.await(10, TimeUnit.SECONDS));
        long begin = System.currentTimeMillis();
        try (HgRepository repo = new HgRepository(dir)) {
            Exception failure = assertThrows(Exception.class, () -> action.run(repo, NodeIdUtil.toHex(node)));
            assertTrue(failure instanceof HgLockException || failure.getCause() instanceof HgLockException, "not a lock failure: " + failure);
        }
        assertTrue(System.currentTimeMillis() - begin >= 900, "it has to wait for ui.timeout first");
        release.countDown();
        holder.join();
    }

    @Test
    void branchHoldsTheWorkingCopyLock() throws Exception {
        assertWaitsForLock("wlock", (repo, node) -> new BranchCommand(repo).setBranchName("other").call());
    }

    @Test
    void phaseHoldsTheStoreLock() throws Exception {
        assertWaitsForLock("lock", (repo, node) -> new PhaseCommand(repo).setRevision(node).setPhase(2).setForce(true).call());
    }

    @Test
    void rollbackHoldsBothLocks() throws Exception {
        assertWaitsForLock("wlock", (repo, node) -> new RollbackCommand(repo).call());
        assertWaitsForLock("lock", (repo, node) -> new RollbackCommand(repo).call());
    }

    @Test
    void addremoveHoldsTheWorkingCopyLock() throws Exception {
        assertWaitsForLock("wlock", (repo, node) -> new AddremoveCommand(repo).call());
    }

    @Test
    void amendHoldsBothLocks() throws Exception {
        assertWaitsForLock("wlock", (repo, node) -> new AmendCommand(repo).setMessage("m").setAuthor("t").call());
        assertWaitsForLock("lock", (repo, node) -> new AmendCommand(repo).setMessage("m").setAuthor("t").call());
    }

    /** Holds the store lock in another thread (a lock is re-entrant only for the thread that holds it) while {@code action} runs. */
    private void whileAnotherThreadHoldsTheStoreLock(File dir, Action action, String node) throws Exception {
        Files.writeString(new File(dir, ".hg/hgrc").toPath(), "[ui]\ntimeout = 3\n"); // a command that waits for it fails soon
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (HgRepository other = new HgRepository(dir); HgLock l = other.lockStore()) {
                held.countDown();
                release.await();
            } catch (Exception ignored) {
                held.countDown();
            }
        });
        holder.start();
        assertTrue(held.await(10, TimeUnit.SECONDS));
        try (HgRepository repo = new HgRepository(dir)) {
            action.run(repo, node);
        } finally {
            release.countDown();
            holder.join();
        }
    }

    @Test
    void commandsThatOnlyChangeTheWorkingCopyDoNotNeedTheStoreLock() throws Exception {
        // real hg takes only wlock for these, so a commit or push that holds the store lock does not stop them
        for (String command : new String[] {"update", "remove", "rename"}) {
            File dir = newRepository("wconly-" + command);
            byte[] first = commitFile(dir, "a.txt", "1\n", "r0");
            commitFile(dir, "b.txt", "2\n", "r1");
            Action action = switch (command) {
                case "update" -> (repo, n) -> new UpdateCommand(repo).setRevision(n).call();
                case "remove" -> (repo, n) -> new RemoveCommand(repo).setFile("a.txt").setForce(true).call();
                default -> (repo, n) -> new RenameCommand(repo).setSource("b.txt").setTarget("c.txt").call();
            };
            whileAnotherThreadHoldsTheStoreLock(dir, action, NodeIdUtil.toHex(first));
        }
    }

    @Test
    void workingCopyOnlyCommandsLeaveTheStoreJournalAlone() throws Exception {
        File dir = newRepository("nojournal");
        commitFile(dir, "a.txt", "1\n", "r0");
        File storeJournal = new File(dir, ".hg/store/journal");
        Files.writeString(storeJournal.toPath(), "kept\n");
        try (HgRepository repo = new HgRepository(dir)) {
            new RenameCommand(repo).setSource("a.txt").setTarget("b.txt").call();
            new RemoveCommand(repo).setFile("b.txt").setForce(true).call();
        }
        assertEquals("kept\n", Files.readString(storeJournal.toPath()), "the store journal belongs to the store lock");
        assertFalse(new File(dir, ".hg/wc-journal").exists(), "a finished command leaves no working copy journal");
    }

    @Test
    void theWorkingCopyLockRecoversNothingAndCommandsLeaveNoJournalBehind() throws Exception {
        File dir = newRepository("wcnorecover");
        commitFile(dir, "a.txt", "1\n", "r0");
        File dirstate = new File(dir, ".hg/dirstate");
        byte[] before = Files.readAllBytes(dirstate.toPath());
        // like hg's repo.wlock(): taking it does not look at any leftover; there is no journal for the working copy to find
        Files.writeString(new File(dir, ".hg/wc-journal").toPath(), "dirstate\n");
        try (HgRepository repo = new HgRepository(dir)) {
            try (HgLock held = repo.lockWorkingCopy()) {
                assertArrayEquals(before, Files.readAllBytes(dirstate.toPath()));
                assertTrue(new File(dir, ".hg/wc-journal").exists(), "not touched by the lock");
            }
            Files.delete(new File(dir, ".hg/wc-journal").toPath());
            new RenameCommand(repo).setSource("a.txt").setTarget("b.txt").call();
        }
        assertFalse(new File(dir, ".hg/wc-journal").exists());
        assertFalse(new File(dir, ".hg/dirstate.backup").exists());
    }

    /** Runs {@code action} while another thread holds {@code lock} ("wlock" or "lock"); the action is expected to throw. */
    private Exception failsWhileAnotherThreadHolds(String lock, File dir, Action action) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (HgRepository other = new HgRepository(dir);
                 HgLock l = lock.equals("wlock") ? other.lockWorkingCopy() : other.lockStore()) {
                held.countDown();
                release.await();
            } catch (Exception ignored) {
                held.countDown();
            }
        });
        holder.start();
        assertTrue(held.await(10, TimeUnit.SECONDS));
        try (HgRepository repo = new HgRepository(dir)) {
            return assertThrows(Exception.class, () -> action.run(repo, null));
        } finally {
            release.countDown();
            holder.join();
        }
    }

    /** r0 adds a.txt, r1 adds b.txt, r2 (a second head) adds c.txt; the working copy is at r2. Returns the hex of r1. */
    private String repositoryWithATwoHeadsToGraft(File dir) throws Exception {
        byte[] r0 = commitFile(dir, "a.txt", "a\n", "r0");
        byte[] r1 = commitFile(dir, "b.txt", "b\n", "r1");
        try (HgRepository repo = new HgRepository(dir)) {
            new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(r0)).call();
        }
        commitFile(dir, "c.txt", "c\n", "r2");
        return NodeIdUtil.toHex(r1);
    }

    @Test
    void graftHoldsTheWorkingCopyLockFromTheStartAndTheStoreLockForTheCommit() throws Exception {
        // without any lock held elsewhere it grafts r1 onto r2
        File ok = newRepository("graft-ok");
        String r1 = repositoryWithATwoHeadsToGraft(ok);
        try (HgRepository repo = new HgRepository(ok)) {
            assertNotNull(new GraftCommand(repo).setSource(r1).call());
        }
        assertTrue(new File(ok, "b.txt").exists());

        // the working copy lock is needed before anything is read or changed: the working copy stays as it was
        File blockedW = newRepository("graft-wlock");
        String r1w = repositoryWithATwoHeadsToGraft(blockedW);
        Exception atStart = failsWhileAnotherThreadHolds("wlock", blockedW, (repo, n) -> new GraftCommand(repo).setSource(r1w).call());
        assertInstanceOf(HgLockException.class, atStart);
        assertFalse(new File(blockedW, "b.txt").exists(), "nothing may be applied without the working copy lock");

        // the store lock is only needed for the commit step, so it is that step that stops
        File blockedS = newRepository("graft-lock");
        String r1s = repositoryWithATwoHeadsToGraft(blockedS);
        Exception atCommit = failsWhileAnotherThreadHolds("lock", blockedS, (repo, n) -> new GraftCommand(repo).setSource(r1s).call());
        assertInstanceOf(HgLockException.class, atCommit);
    }

    @Test
    void shelveScansUnderTheWorkingCopyLockAndWritesUnderTheStoreLock() throws Exception {
        for (String lock : new String[] {"wlock", "lock"}) {
            File dir = newRepository("shelve-" + lock);
            commitFile(dir, "a.txt", "one\n", "r0");
            Files.writeString(new File(dir, "a.txt").toPath(), "two\n");
            Exception failure = failsWhileAnotherThreadHolds(lock, dir, (repo, n) -> new ShelveCommand(repo).setName("s1").call());
            assertInstanceOf(HgLockException.class, failure, lock);
            assertEquals("two\n", Files.readString(new File(dir, "a.txt").toPath()), "a failed shelve must leave the change in place");
            File shelved = new File(dir, ".hg/shelved");
            assertTrue(!shelved.exists() || java.util.Objects.requireNonNull(shelved.list()).length == 0, "nothing may be left behind for " + lock);
        }
        File dir = newRepository("shelve-ok");
        commitFile(dir, "a.txt", "one\n", "r0");
        Files.writeString(new File(dir, "a.txt").toPath(), "two\n");
        try (HgRepository repo = new HgRepository(dir)) {
            new ShelveCommand(repo).setName("s1").call();
        }
        assertEquals("one\n", Files.readString(new File(dir, "a.txt").toPath()));
    }

    @Test
    void applyingABundleThatCouldNotGetTheLocksTouchesNothing() throws Exception {
        File source = newRepository("fetch-source");
        commitFile(source, "a.txt", "a\n", "from source");
        byte[] bundleBytes = bundleOf(source);
        File target = newRepository("fetch-target");
        commitFile(target, "t.txt", "t\n", "in target");
        File dirstate = new File(target, ".hg/dirstate");
        File fncache = new File(target, ".hg/store/fncache");
        byte[] dirstateBefore = Files.readAllBytes(dirstate.toPath());
        byte[] fncacheBefore = Files.readAllBytes(fncache.toPath());
        for (String lock : new String[] {"wlock", "lock"}) {
            Exception failure = failsWhileAnotherThreadHolds(lock, target, (repo, n) -> {
                ChangegroupParser.ChangegroupBundle bundle = ChangegroupParser.parseBundle(
                        new java.io.ByteArrayInputStream(bundleBytes, 6, bundleBytes.length - 6), "01");
                new FetchCommand(repo).applyBundle(bundle);
            });
            assertInstanceOf(HgLockException.class, failure, lock);
            // a rollback that runs because the locks were not obtained must not delete or rewrite files it never saved
            assertArrayEquals(dirstateBefore, Files.readAllBytes(dirstate.toPath()), "dirstate after " + lock + " was held");
            assertTrue(fncache.exists(), "fncache after " + lock + " was held");
            assertArrayEquals(fncacheBefore, Files.readAllBytes(fncache.toPath()), "fncache after " + lock + " was held");
        }
    }

    private static boolean lockFilePresent(File dir, String relative) {
        return Files.exists(new File(dir, relative).toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    @Test
    void theCommitHookRunsAfterTheLocksAreReleased() throws Exception {
        File dir = newRepository("hook-commit");
        java.util.List<String> seen = new ArrayList<>();
        HgHook pre = ctx -> {
            seen.add("pre wlock=" + lockFilePresent(dir, ".hg/wlock") + " lock=" + lockFilePresent(dir, ".hg/store/lock"));
            return true;
        };
        HgHook post = ctx -> {
            seen.add("post wlock=" + lockFilePresent(dir, ".hg/wlock") + " lock=" + lockFilePresent(dir, ".hg/store/lock"));
            return true;
        };
        try (HgRepository repo = new HgRepository(dir)) {
            Files.writeString(new File(dir, "a.txt").toPath(), "a\n");
            new AddCommand(repo).call();
            new CommitCommand(repo).setMessage("m").setAuthor("t").registerPreCommitHook(pre).registerPostCommitHook(post).call();
        }
        // real hg: precommit runs with wlock and lock held, the commit hook (repo._afterlock) once both are released
        assertEquals(List.of("pre wlock=true lock=true", "post wlock=false lock=false"), seen);
    }

    @Test
    void thePostHooksOfALocalPushRunAfterTheLocksAreReleased() throws Exception {
        File source = newRepository("hook-push-source");
        commitFile(source, "a.txt", "a\n", "from source");
        byte[] bundleBytes = bundleOf(source);
        File target = newRepository("hook-push-target");
        java.util.List<String> seen = new ArrayList<>();
        HgHook preTxn = ctx -> {
            seen.add("pretxn wlock=" + lockFilePresent(target, ".hg/wlock") + " lock=" + lockFilePresent(target, ".hg/store/lock"));
            return true;
        };
        HgHook post = ctx -> {
            seen.add("post wlock=" + lockFilePresent(target, ".hg/wlock") + " lock=" + lockFilePresent(target, ".hg/store/lock"));
            return true;
        };
        try (HgRepository repo = new HgRepository(target); HgLocalClient client = new HgLocalClient(repo)) {
            client.pushWithHooks(bundleBytes, List.of(), List.of(), List.of(preTxn), List.of(post), null);
        }
        assertEquals(List.of("pretxn wlock=true lock=true", "post wlock=false lock=false"), seen);
    }

    @Test
    void afterLockWorkWaitsForTheOutermostLock() throws Exception {
        File dir = newRepository("afterlock");
        List<String> seen = new ArrayList<>();
        try (HgRepository repo = new HgRepository(dir)) {
            // no lock held: runs at once
            repo.afterLock(() -> seen.add("now"));
            assertEquals(List.of("now"), seen);
            try (HgLock w = repo.lockWorkingCopy()) {
                try (HgLock l = repo.lockStore()) {
                    repo.afterLock(() -> seen.add("after both: wlock=" + lockFilePresent(dir, ".hg/wlock")
                            + " lock=" + lockFilePresent(dir, ".hg/store/lock")));
                }
                assertEquals(List.of("now"), seen, "the store lock is released but the working copy lock is still held");
                repo.afterLock(() -> {
                    throw new IllegalStateException("a failing post hook cannot fail the operation");
                });
            }
            assertEquals(List.of("now", "after both: wlock=false lock=false"), seen);
        }
    }

    @Test
    void thePostHooksOfAPushRunInsideAnOuterLockWaitForIt() throws Exception {
        // the server side of a push calls pushWithHooks while it already holds the locks for the whole request
        File source = newRepository("outer-source");
        commitFile(source, "a.txt", "a\n", "from source");
        byte[] bundleBytes = bundleOf(source);
        File target = newRepository("outer-target");
        List<String> seen = new ArrayList<>();
        HgHook post = ctx -> {
            seen.add("post wlock=" + lockFilePresent(target, ".hg/wlock") + " lock=" + lockFilePresent(target, ".hg/store/lock"));
            return true;
        };
        try (HgRepository repo = new HgRepository(target); HgLocalClient client = new HgLocalClient(repo)) {
            try (HgLock w = repo.lockWorkingCopy(); HgLock l = repo.lockStore()) {
                client.pushWithHooks(bundleBytes, List.of(), List.of(), List.of(), List.of(post), null);
                assertTrue(seen.isEmpty(), "the outer locks are still held, so the hook must wait: " + seen);
            }
        }
        assertEquals(List.of("post wlock=false lock=false"), seen);
    }

    @Test
    void everyCommandWaitsForALockLikeHgDoes() throws Exception {
        // commit and update used to fail at once when another process held a lock; real hg waits ui.timeout seconds
        for (String lock : new String[] {"wlock", "lock"}) {
            File dir = newRepository("waits-" + lock);
            byte[] first = commitFile(dir, "a.txt", "1\n", "r0");
            waitForLocksUpTo(dir, 30);
            CountDownLatch held = new CountDownLatch(1);
            Thread holder = new Thread(() -> {
                try (HgRepository other = new HgRepository(dir);
                     HgLock l = lock.equals("wlock") ? other.lockWorkingCopy() : other.lockStore()) {
                    held.countDown();
                    Thread.sleep(1500);
                } catch (Exception ignored) {
                    held.countDown();
                }
            });
            holder.start();
            assertTrue(held.await(10, TimeUnit.SECONDS));
            long begin = System.currentTimeMillis();
            try (HgRepository repo = new HgRepository(dir)) {
                Files.writeString(new File(dir, "b.txt").toPath(), "2\n");
                new AddCommand(repo).call();
                assertNotNull(new CommitCommand(repo).setMessage("waited").setAuthor("t").call());
            }
            assertTrue(System.currentTimeMillis() - begin >= 1000, "the commit has to wait for the " + lock + " first");
            holder.join();
            try (HgRepository repo = new HgRepository(dir)) {
                assertNotNull(new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(first)).call());
            }
        }
    }

    /** Runs {@code action} while another thread holds {@code lock}; the action has to succeed (the command does not need that lock). */
    private void succeedsWhileAnotherThreadHolds(String lock, File dir, Action action) throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (HgRepository other = new HgRepository(dir);
                 HgLock l = lock.equals("wlock") ? other.lockWorkingCopy() : other.lockStore()) {
                held.countDown();
                release.await();
            } catch (Exception ignored) {
                held.countDown();
            }
        });
        holder.start();
        assertTrue(held.await(10, TimeUnit.SECONDS));
        try (HgRepository repo = new HgRepository(dir)) {
            action.run(repo, null);
        } finally {
            release.countDown();
            holder.join();
        }
    }

    @Test
    void censorGcVerifyAndRecoverTakeTheLocksRealHgTakes() throws Exception {
        File dir = newRepository("misc-locks");
        commitFile(dir, "a.txt", "one\n", "r0");
        waitForLocksUpTo(dir, 1);
        // censor: wlock and lock (hgext/censor.py), gc: wlock and lock (upgrade/rebuildfncache), verify and recover: lock only
        Action censor = (repo, n) -> {
            File idx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "a.txt");
            File dat = new File(idx.getPath().substring(0, idx.getPath().length() - 2) + ".d");
            String node = NodeIdUtil.toHex(repo.getRevlog(idx, dat).getIndexRecord(0).getNodeId());
            new CensorCommand(repo).setFile("a.txt").setRevision(node).setCheckHeads(false).call();
        };
        assertInstanceOf(HgLockException.class, failsWhileAnotherThreadHolds("wlock", dir, censor));
        assertInstanceOf(HgLockException.class, failsWhileAnotherThreadHolds("lock", dir, censor));
        Action gc = (repo, n) -> new GcCommand(repo).call();
        assertInstanceOf(HgLockException.class, failsWhileAnotherThreadHolds("wlock", dir, gc));
        assertInstanceOf(HgLockException.class, failsWhileAnotherThreadHolds("lock", dir, gc));
        Action verify = (repo, n) -> new VerifyCommand(repo).call();
        Action recover = (repo, n) -> new RecoverCommand(repo).call();
        assertInstanceOf(HgLockException.class, failsWhileAnotherThreadHolds("lock", dir, verify));
        assertInstanceOf(HgLockException.class, failsWhileAnotherThreadHolds("lock", dir, recover));
        succeedsWhileAnotherThreadHolds("wlock", dir, verify);
        succeedsWhileAnotherThreadHolds("wlock", dir, recover);
    }

    @Test
    void unbundleHoldsOnlyTheStoreLockLikeHg() throws Exception {
        File source = newRepository("unbundle-source");
        commitFile(source, "a.txt", "a\n", "from source");
        File bundleFile = tempDir.resolve("unbundle.hg").toFile();
        Files.write(bundleFile.toPath(), bundleOf(source));
        File target = newRepository("unbundle-target");
        waitForLocksUpTo(target, 1);
        Action unbundle = (repo, n) -> new UnbundleCommand(repo).setBundleFile(bundleFile).call();
        assertInstanceOf(HgLockException.class, failsWhileAnotherThreadHolds("lock", target, unbundle));
        // real hg's unbundle (cmdutil.unbundle_files) does not take the working copy lock
        succeedsWhileAnotherThreadHolds("wlock", target, unbundle);
    }

    @Test
    void recordingTheLfsRequirementTakesTheStoreLock() throws Exception {
        File dir = newRepository("lfs-requirement");
        waitForLocksUpTo(dir, 1);
        Exception failure = failsWhileAnotherThreadHolds("lock", dir, (repo, n) -> io.github.search5.hg4j.lfs.HgLfsConfig.addRequirement(repo));
        assertInstanceOf(java.io.IOException.class, failure);
        assertInstanceOf(HgLockException.class, failure.getCause());
    }
}
