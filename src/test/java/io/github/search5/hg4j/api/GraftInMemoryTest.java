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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code graft --to}: the scenario and the expected hashes are those of Mercurial 7.2 for the same commits (user and date of the source are
 * kept, so equal input gives the very same node).
 */
class GraftInMemoryTest {

    private static final String BASE = "5a39ea715f3ce97599abae6de166d4118b1986bc";
    private static final String SOURCE = "b305ff84eb5e795ed4f6a179c27be0738806ce85";
    private static final String TARGET = "e84cdff67e690455272ef7f70752826263da20cd";
    private static final String GRAFTED = "df2513f3da7897c2e5e72a0d2a52774e2754f6ae";

    @TempDir
    Path tempDir;

    File dir;
    HgRepository repo;

    private String commit(String message, String user, long time) throws Exception {
        new AddCommand(repo).call();
        return NodeIdUtil.toHex(new CommitCommand(repo).setMessage(message).setAuthor(user).setDate(time, 0).call());
    }

    @BeforeEach
    void history() throws Exception {
        dir = tempDir.resolve("repo").toFile();
        repo = Hg.init().setDirectory(dir).call();
        Files.writeString(new File(dir, "a.txt").toPath(), "1\n2\n3\n4\n5\n");
        Files.writeString(new File(dir, "k.txt").toPath(), "keep\n");
        assertEquals(BASE, commit("base", "t", 1000));
        Files.writeString(new File(dir, "a.txt").toPath(), "ONE\n2\n3\n4\n5\n");
        Files.writeString(new File(dir, "n.txt").toPath(), "new\n");
        new AddCommand(repo).call();
        new RemoveCommand(repo).setFile("k.txt").call();
        assertEquals(SOURCE, commit("source change", "s", 2000));
        new UpdateCommand(repo).setRevision(BASE).call();
        Files.writeString(new File(dir, "a.txt").toPath(), "1\n2\n3\n4\nFIVE\n");
        assertEquals(TARGET, commit("target change", "t", 3000));
    }

    private int revisions() throws Exception {
        return repo.getRevlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d")).getRevisionCount();
    }

    @Test
    void theGraftedChangesetHasTheNodeMercurialMakes() throws Exception {
        GraftInMemory graft = new GraftInMemory(repo).setTo(TARGET).addRevision(SOURCE);
        List<String> created = graft.call();
        io.github.search5.hg4j.storage.Revlog cl = repo.getRevlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"));
        int newRev = cl.findRevision(NodeIdUtil.fromHex(created.get(0)));
        String entryText = new String(cl.getRevisionContent(newRev), java.nio.charset.StandardCharsets.UTF_8).replace("\0", "<NUL>");
        io.github.search5.hg4j.storage.Revlog mf = repo.getManifestRevlog();
        String manifestText = new String(mf.getRevisionContent(mf.getRevisionCount() - 1), java.nio.charset.StandardCharsets.UTF_8).replace("\0", "<NUL>");
        assertEquals(List.of(GRAFTED), created, "\nCHANGELOG:\n" + entryText + "\nMANIFEST:\n" + manifestText);
        assertEquals(List.of("grafting 1:b305ff84eb5e \"source change\"\n", "merging a.txt\n"), graft.getMessages());
        assertEquals(4, revisions());
    }

    @Test
    void theWorkingCopyAndTheDirstateAreNotTouched() throws Exception {
        byte[] parentBefore = repo.getDirstate().getParent1();
        String contentBefore = Files.readString(new File(dir, "a.txt").toPath());
        new GraftInMemory(repo).setTo(TARGET).addRevision(SOURCE).call();
        assertArrayEquals(parentBefore, repo.getDirstate().getParent1());
        assertEquals(contentBefore, Files.readString(new File(dir, "a.txt").toPath()));
        assertFalse(new File(dir, "n.txt").exists(), "the working copy never gets the grafted files");
    }

    @Test
    void aDryRunOnlyPrints() throws Exception {
        GraftInMemory graft = new GraftInMemory(repo).setTo(TARGET).addRevision(SOURCE).setDryRun(true);
        assertEquals(List.of(), graft.call());
        assertEquals(List.of("grafting 1:b305ff84eb5e \"source change\"\n"), graft.getMessages());
        assertEquals(3, revisions());
    }

    @Test
    void aConflictAbortsAndLeavesNothingBehind() throws Exception {
        new UpdateCommand(repo).setRevision(BASE).call();
        Files.writeString(new File(dir, "a.txt").toPath(), "Y\n2\n3\n4\n5\n");
        String conflicting = commit("conflicting target", "t", 4000);
        GraftInMemory graft = new GraftInMemory(repo).setTo(conflicting).addRevision(SOURCE);
        HgValidationException e = assertThrows(HgValidationException.class, graft::call);
        assertEquals("cannot graft in memory: merge conflicts\n(hint: in-memory merge does not support external merge tools)", e.getMessage());
        assertEquals(List.of("grafting 1:b305ff84eb5e \"source change\"\n", "merging a.txt\n"), graft.getMessages());
        assertEquals(4, revisions());
        assertFalse(new File(repo.getStoreDir(), "journal").exists());
        assertFalse(new File(repo.getStoreDir(), "lock").exists());
    }

    @Test
    void aLaterConflictRollsBackTheEarlierGraftOfTheSameCall() throws Exception {
        // graft SOURCE (clean) and then a second change that conflicts with it: the first new changeset must disappear too
        new UpdateCommand(repo).setRevision(SOURCE).call();
        Files.writeString(new File(dir, "a.txt").toPath(), "ONE\n2\n3\n4\ncinco\n"); // line 5 differs from what TARGET made of it
        String second = commit("conflicts with the first", "s", 5000);
        int before = revisions();
        GraftInMemory graft = new GraftInMemory(repo).setTo(TARGET).addRevision(SOURCE).addRevision(second);
        assertThrows(HgValidationException.class, graft::call);
        assertEquals(before, revisions(), "the transaction is all or nothing");
    }

    @Test
    void theGraftedChangesetIsDraftAndKeepsTheSourceInItsExtra() throws Exception {
        new GraftInMemory(repo).setTo(TARGET).addRevision(SOURCE).call();
        io.github.search5.hg4j.storage.Revlog changelog = repo.getRevlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"));
        int rev = changelog.findRevision(NodeIdUtil.fromHex(GRAFTED));
        ChangesetRewrite.Entry entry = ChangesetRewrite.read(changelog, rev);
        assertEquals(SOURCE, entry.extra.get("source"));
        assertEquals(List.of("a.txt", "k.txt", "n.txt"), entry.files);
        assertEquals("source change", entry.message);
        assertEquals("s", entry.user);
    }

    @Test
    void graftingTheSameChangesetTwiceDoesNotAddItAgain() throws Exception {
        new GraftInMemory(repo).setTo(TARGET).addRevision(SOURCE).call();
        List<String> again = new GraftInMemory(repo).setTo(TARGET).addRevision(SOURCE).call();
        assertEquals(List.of(GRAFTED), again);
        assertEquals(4, revisions());
    }

    @Test
    void noWorkingCopyLockIsNeededButTheStoreLockIs() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread wlockHolder = new Thread(() -> {
            try (HgRepository other = new HgRepository(dir); HgLock l = other.lockWorkingCopy()) {
                held.countDown();
                release.await();
            } catch (Exception ignored) {
                held.countDown();
            }
        });
        wlockHolder.start();
        assertTrue(held.await(10, TimeUnit.SECONDS));
        try {
            assertEquals(List.of(GRAFTED), new GraftInMemory(repo).setTo(TARGET).addRevision(SOURCE).call(), "wlock held elsewhere: no matter");
        } finally {
            release.countDown();
            wlockHolder.join();
        }
        // and now the store lock
        CountDownLatch held2 = new CountDownLatch(1);
        CountDownLatch release2 = new CountDownLatch(1);
        Thread lockHolder = new Thread(() -> {
            try (HgRepository other = new HgRepository(dir); HgLock l = other.lockStore()) {
                held2.countDown();
                release2.await();
            } catch (Exception ignored) {
                held2.countDown();
            }
        });
        lockHolder.start();
        assertTrue(held2.await(10, TimeUnit.SECONDS));
        try {
            assertThrows(HgLockException.class, () -> new GraftInMemory(repo).setTo(TARGET).addRevision("0").setDryRun(true).call());
        } finally {
            release2.countDown();
            lockHolder.join();
        }
    }

    @Test
    void manyGraftsAtTheSameMomentCreateTheChangesetOnce() throws Exception {
        Files.writeString(new File(dir, ".hg/hgrc").toPath(), "[ui]\ntimeout = 120\n");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<String>>> done = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            done.add(pool.submit(() -> {
                try (HgRepository own = new HgRepository(dir)) {
                    start.await();
                    return new GraftInMemory(own).setTo(TARGET).addRevision(SOURCE).call();
                }
            }));
        }
        start.countDown();
        for (Future<List<String>> f : done) {
            assertEquals(List.of(GRAFTED), f.get(120, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        try (HgRepository fresh = new HgRepository(dir)) {
            assertEquals(4, fresh.getRevlog(new File(fresh.getStoreDir(), "00changelog.i"), new File(fresh.getStoreDir(), "00changelog.d"))
                    .getRevisionCount(), "exactly one new changeset");
            assertEquals(List.of(), new VerifyCommand(fresh).call());
        }
    }

    @Test
    void refusalsUseHgsMessages() {
        assertEquals("no revisions specified", assertThrows(HgValidationException.class, () -> new GraftInMemory(repo).setTo(TARGET).call()).getMessage());
    }
}
