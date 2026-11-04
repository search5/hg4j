package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code hg branch --rev REV NAME} (cmdutil.changebranch), observed with real hg 7.2 on: r0 - r1 - r2 and r1 - r3 (r3 committed after
 * r2), the working directory on r2.
 */
class BranchRevTest {

    @TempDir
    Path tempDir;

    private File dir;
    private byte[] r0;
    private byte[] r1;
    private byte[] r2;
    private byte[] r3;

    private byte[] commit(HgRepository repo, String file, String message) throws Exception {
        Files.writeString(new File(dir, file).toPath(), file + "\n");
        new AddCommand(repo).call();
        return new CommitCommand(repo).setMessage(message).setAuthor("t").call();
    }

    private HgRepository build() throws Exception {
        dir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(dir).call();
        r0 = commit(repo, "a", "r0");
        r1 = commit(repo, "b", "r1");
        r2 = commit(repo, "c", "r2");
        new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(r1)).call();
        r3 = commit(repo, "d", "r3");
        new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(r2)).call();
        return repo;
    }

    private static String hex(byte[] node) {
        return NodeIdUtil.toHex(node);
    }

    private Revlog changelog(HgRepository repo) throws Exception {
        repo.clearRevlogCache();
        return repo.getRevlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"));
    }

    private String message(Revlog cl, int rev) throws Exception {
        return ChangesetRewrite.read(cl, rev).message;
    }

    private static HgValidationException refused(org.junit.jupiter.api.function.Executable action) {
        return assertThrows(HgValidationException.class, action);
    }

    @Test
    void theChangesetIsReplacedByACopyOnTheNewBranch() throws Exception {
        HgRepository repo = build();
        BranchCommand command = new BranchCommand(repo).setBranchName("feature").setRevisions("2");
        assertEquals("feature", command.call());
        assertEquals(1, command.getChangedChangesets());
        Revlog cl = changelog(repo);
        assertEquals(4, cl.getRevisionCount());
        // hg: r3 (which was kept) comes first, the replacement follows
        assertEquals("r3", message(cl, 2));
        assertEquals("r2", message(cl, 3));
        ChangesetRewrite.Entry fresh = ChangesetRewrite.read(cl, 3);
        assertEquals("feature", fresh.extra.get("branch"));
        assertEquals(hex(r2), fresh.extra.get("branch_change"));
        assertEquals(-1, cl.findRevision(r2), "the old changeset is gone");
        assertNotEquals(-1, cl.findRevision(r3), "the other head is kept with its hash");
        assertEquals(1, command.getBackupBundles().size());
        assertTrue(command.getBackupBundles().get(0).getName().endsWith("-branch-change.hg"));
        assertEquals(List.of(), new VerifyCommand(repo).call());
    }

    @Test
    void theWorkingDirectoryAndABookmarkFollowTheNewChangeset() throws Exception {
        HgRepository repo = build();
        new BookmarkCommand(repo).setBookmarkName("bm").setRevision(hex(r2)).call();
        new BranchCommand(repo).setBranchName("feature").setRevisions("2").call();
        Revlog cl = changelog(repo);
        byte[] fresh = cl.getIndexRecord(3).getNodeId();
        assertArrayEquals(fresh, repo.getDirstate().getParent1());
        assertEquals("feature", repo.getBranch());
        assertEquals(hex(fresh), new BookmarkCommand(repo).list().get("bm"));
    }

    @Test
    void refusalsWhereRealHgRefuses() throws Exception {
        HgRepository repo = build();
        assertEquals("cannot change branch of changeset, as that will orphan 2 descendants",
                refused(() -> new BranchCommand(repo).setBranchName("feature").setRevisions("1").call()).getMessage());
        assertEquals("cannot change branch of changeset, as that will orphan 1 descendants",
                refused(() -> new BranchCommand(repo).setBranchName("feature").setRevisions("1:2").call()).getMessage());
        assertEquals("no branch name specified for the revisions",
                refused(() -> new BranchCommand(repo).setRevisions("2").call()).getMessage());
        Files.writeString(new File(dir, "a").toPath(), "changed\n");
        assertEquals("uncommitted changes",
                refused(() -> new BranchCommand(repo).setBranchName("feature").setRevisions("2").call()).getMessage());
        assertEquals(4, changelog(repo).getRevisionCount(), "a refused change replaces nothing");
    }

    @Test
    void changingToTheBranchItAlreadyHasChangesNothing() throws Exception {
        HgRepository repo = build();
        BranchCommand command = new BranchCommand(repo).setBranchName("default").setRevisions("2");
        command.call();
        assertEquals(0, command.getChangedChangesets());
        assertNotEquals(-1, changelog(repo).findRevision(r2));
    }

    @Test
    void publicChangesetsAreRefused() throws Exception {
        HgRepository repo = build();
        new PhaseCommand(repo).setRevision(hex(r2)).setPhase(0).call();
        assertEquals("cannot change branch of public changesets: " + hex(r2).substring(0, 12),
                refused(() -> new BranchCommand(repo).setBranchName("feature").setRevisions("2").call()).getMessage());
    }

    // ------------------------------------------------------------------ what the locks are for

    @Test
    void itWaitsForTheLocksAndFailsWhenTheyStayHeld() throws Exception {
        HgRepository repo = build();
        Files.writeString(new File(dir, ".hg/hgrc").toPath(), "[ui]\ntimeout = 1\n");
        for (String lock : new String[] {"wlock", "lock"}) {
            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread holder = new Thread(() -> {
                try (HgRepository other = new HgRepository(dir); HgLock l = lock.equals("wlock") ? other.lockWorkingCopy() : other.lockStore()) {
                    held.countDown();
                    release.await();
                } catch (Exception ignored) {
                    held.countDown();
                }
            });
            holder.start();
            assertTrue(held.await(10, TimeUnit.SECONDS));
            try (HgRepository fresh = new HgRepository(dir)) {
                assertThrows(HgLockException.class, () -> new BranchCommand(fresh).setBranchName("feature").setRevisions("2").call());
            } finally {
                release.countDown();
                holder.join();
            }
        }
        assertNotEquals(-1, changelog(repo).findRevision(r2));
    }

    @Test
    void headsChangedAtTheSameMomentAreEachReplacedOnce() throws Exception {
        // three heads on r0; three threads move a different head to its own branch at the same moment
        dir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(dir).call();
        byte[] base = commit(repo, "a", "base");
        List<byte[]> heads = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            new UpdateCommand(repo).setRevision(hex(base)).call();
            heads.add(commit(repo, "f" + i, "head " + i));
        }
        Files.writeString(new File(dir, ".hg/hgrc").toPath(), "[ui]\ntimeout = 120\n");
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            int n = i;
            String head = hex(heads.get(i - 1));
            done.add(pool.submit(() -> {
                try (HgRepository own = new HgRepository(dir)) {
                    start.await();
                    // a revision number would move while the other heads are replaced; the hash of a head only changes when it is replaced
                    new BranchCommand(own).setBranchName("branch" + n).setRevisions(head).setForce(true).call();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : done) {
            f.get(180, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        try (HgRepository check = new HgRepository(dir)) {
            Revlog cl = changelog(check);
            assertEquals(4, cl.getRevisionCount(), "the base and one replacement per head: nothing lost, nothing twice");
            for (int i = 1; i <= 3; i++) {
                int found = 0;
                for (int r = 0; r < cl.getRevisionCount(); r++) {
                    ChangesetRewrite.Entry e = ChangesetRewrite.read(cl, r);
                    if (e.message.equals("head " + i)) {
                        found++;
                        assertEquals("branch" + i, e.extra.get("branch"));
                    }
                }
                assertEquals(1, found, "head " + i);
            }
            assertEquals(List.of(), new VerifyCommand(check).call());
            assertFalse(new File(dir, ".hg/wlock").exists());
            assertFalse(new File(dir, ".hg/store/lock").exists());
        }
    }
}
