package io.github.search5.hg4j.api;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cloning a local repository copies its store (cmd_impls/clone.py, streamclone.local_copy) instead of pulling, with hard links where
 * possible; the source store lock is only tried, a source with a secret changeset is pulled from, {@code --pull} always pulls.
 */
class LocalCloneCopyTest {

    @TempDir
    Path tempDir;

    private File source;

    private byte[] commit(HgRepository repo, String file, String content, String message) throws Exception {
        Files.writeString(new File(repo.getDirectory(), file).toPath(), content);
        new AddCommand(repo).call();
        return new CommitCommand(repo).setMessage(message).setAuthor("t").call();
    }

    private HgRepository sourceWithHistory() throws Exception {
        source = tempDir.resolve("source").toFile();
        HgRepository repo = Hg.init().setDirectory(source).call();
        commit(repo, "a.txt", "one\n", "r0");
        byte[] r1 = commit(repo, "b.txt", "two\n", "r1");
        commit(repo, "a.txt", "three\n", "r2");
        new BookmarkCommand(repo).setBookmarkName("mark").setRevision(NodeIdUtil.toHex(r1)).call();
        return repo;
    }

    private File clone(String name, boolean pull) throws Exception {
        File dest = tempDir.resolve(name).toFile();
        new CloneCommand().setSource(source.getAbsolutePath()).setDirectory(dest).setPull(pull).call().close();
        return dest;
    }

    private static int revisions(File dir) throws Exception {
        try (HgRepository repo = new HgRepository(dir)) {
            return repo.getRevlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d")).getRevisionCount();
        }
    }

    private static boolean hardLinked(File a, File b) throws Exception {
        return Files.isSameFile(new File(a, ".hg/store/00changelog.i").toPath(), new File(b, ".hg/store/00changelog.i").toPath());
    }

    @Test
    void aLocalSourceIsCopiedWithHardLinks() throws Exception {
        sourceWithHistory().close();
        File dest = clone("dest", false);
        assertTrue(hardLinked(source, dest), "the store files are links to the source's");
        assertEquals(3, revisions(dest));
        assertEquals("three\n", Files.readString(new File(dest, "a.txt").toPath()));
        assertEquals("two\n", Files.readString(new File(dest, "b.txt").toPath()));
        try (HgRepository repo = new HgRepository(dest)) {
            assertEquals(List.of(), new VerifyCommand(repo).call());
            assertEquals(1, new BookmarkCommand(repo).list().size());
            assertTrue(repo.getConfig().getPath("default").endsWith("source"));
            assertFalse(Files.readAllLines(new File(dest, ".hg/requires").toPath()).contains("shared"));
        }
        assertFalse(new File(dest, ".hg/wlock").exists());
        assertFalse(new File(dest, ".hg/store/lock").exists());
        assertFalse(new File(source, ".hg/store/lock").exists());
    }

    @Test
    void theCopyAndTheSourceAreIndependentAfterwards() throws Exception {
        sourceWithHistory().close();
        File dest = clone("dest", false);
        try (HgRepository repo = new HgRepository(dest)) {
            commit(repo, "c.txt", "only in the clone\n", "r3 in clone");
        }
        assertEquals(4, revisions(dest));
        assertEquals(3, revisions(source), "writing to the clone did not reach the source");
        try (HgRepository src = new HgRepository(source); HgRepository dst = new HgRepository(dest)) {
            assertEquals(List.of(), new VerifyCommand(src).call());
            assertEquals(List.of(), new VerifyCommand(dst).call());
        }
    }

    @Test
    void pullAlwaysPulls() throws Exception {
        sourceWithHistory().close();
        File dest = clone("dest", true);
        assertFalse(hardLinked(source, dest));
        assertEquals(3, revisions(dest));
    }

    @Test
    void aSourceWithASecretChangesetIsNotCopied() throws Exception {
        try (HgRepository repo = sourceWithHistory()) {
            new PhaseCommand(repo).setRevision(NodeIdUtil.toHex(repo.getRevlog(new File(repo.getStoreDir(), "00changelog.i"),
                    new File(repo.getStoreDir(), "00changelog.d")).getIndexRecord(2).getNodeId())).setPhase(2).setForce(true).call();
        }
        File dest = clone("dest", false);
        assertFalse(hardLinked(source, dest), "a secret changeset must not be copied along");
    }

    @Test
    void aBusySourceStoreLockMakesItPullInsteadOfWaiting() throws Exception {
        sourceWithHistory().close();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (HgRepository other = new HgRepository(source); HgLock l = other.lockStore()) {
                held.countDown();
                release.await();
            } catch (Exception ignored) {
                held.countDown();
            }
        });
        holder.start();
        assertTrue(held.await(10, TimeUnit.SECONDS));
        long begin = System.currentTimeMillis();
        File dest;
        try {
            dest = clone("dest", false);
        } finally {
            release.countDown();
            holder.join();
        }
        assertTrue(System.currentTimeMillis() - begin < 20_000, "srcrepo.lock(wait=False) does not wait");
        assertFalse(hardLinked(source, dest));
        assertEquals(3, revisions(dest), "the changesets were pulled all the same");
    }

    @Test
    void cloningWhileTheSourceKeepsCommittingAlwaysGivesAConsistentRepository() throws Exception {
        sourceWithHistory().close();
        AtomicBoolean stop = new AtomicBoolean();
        List<Throwable> committerErrors = new ArrayList<>();
        Thread committer = new Thread(() -> {
            try (HgRepository repo = new HgRepository(source)) {
                for (int i = 0; i < 400 && !stop.get(); i++) {
                    try {
                        commit(repo, "busy.txt", "round " + i + "\n", "busy " + i);
                    } catch (io.github.search5.hg4j.errors.HgLockException busy) {
                        Thread.sleep(5); // a clone holds the store lock for a moment
                    }
                }
            } catch (Throwable t) {
                committerErrors.add(t);
            }
        });
        committer.start();
        for (int i = 0; i < 25; i++) {
            File dest = clone("dest" + i, false);
            try (HgRepository repo = new HgRepository(dest)) {
                assertEquals(List.of(), new VerifyCommand(repo).call(), "clone " + i + " must be a complete repository");
            }
            assertTrue(revisions(dest) >= 3);
        }
        stop.set(true);
        committer.join();
        assertEquals(List.of(), committerErrors);
        try (HgRepository repo = new HgRepository(source)) {
            assertEquals(List.of(), new VerifyCommand(repo).call(), "the source is intact");
        }
    }
}
