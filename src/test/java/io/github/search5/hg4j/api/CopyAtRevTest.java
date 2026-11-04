package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.phase.PhaseRoots;
import io.github.search5.hg4j.storage.Revlog;
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
 * {@code hg copy --after --at-rev REV} and {@code --forget --at-rev}: observed with real hg 7.2 on a repository where r1 added b.txt as
 * a copy of a.txt, r2 and r3 are two heads on top of r1 (r2 adds c.txt, r3 adds d.txt, a plain copy of a.txt on disk).
 */
class CopyAtRevTest {

    @TempDir
    Path tempDir;

    private File dir;
    private byte[] r0;
    private byte[] r1;
    private byte[] r2;
    private byte[] r3;

    private byte[] commit(HgRepository repo, String file, String content, String message) throws Exception {
        Files.writeString(new File(dir, file).toPath(), content);
        new AddCommand(repo).call();
        return new CommitCommand(repo).setMessage(message).setAuthor("t").call();
    }

    private HgRepository build() throws Exception {
        dir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(dir).call();
        r0 = commit(repo, "a.txt", "A\n", "r0");
        r1 = commit(repo, "b.txt", "A\n", "r1");
        r2 = commit(repo, "c.txt", "C\n", "r2");
        new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(r1)).call();
        r3 = commit(repo, "d.txt", "A\n", "r3");
        return repo;
    }

    private static String hex(byte[] n) {
        return NodeIdUtil.toHex(n);
    }

    private Revlog changelog(HgRepository repo) throws Exception {
        repo.clearRevlogCache();
        return repo.getRevlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"));
    }

    private CopyCommand.PatternResult atRev(HgRepository repo, String rev, boolean after, boolean forget, String... patterns) throws Exception {
        return new CopyCommand(repo).setPatterns(patterns).setAtRev(rev).setAfter(after).setForget(forget).callPatterns();
    }

    private static String copyOf(HgRepository repo, String file, byte[] node) throws Exception {
        Map<String, String> manifest = repo.getManifestAtCommit(node);
        File idx = CommitCommand.getFilelogIndex(repo.getStoreDir(), file);
        Revlog filelog = repo.getRevlog(idx, new File(idx.getPath().substring(0, idx.getPath().length() - 2) + ".d"));
        return filelog.getRevisionMetadata(filelog.findRevision(NodeIdUtil.fromHex(manifest.get(file).substring(0, 40)))).get("copy");
    }

    private byte[] headWithMessage(HgRepository repo, String message) throws Exception {
        Revlog cl = changelog(repo);
        for (int r = cl.getRevisionCount() - 1; r >= 0; r--) {
            if (new String(cl.getRevisionContent(r)).endsWith("\n" + message)) {
                return cl.getIndexRecord(r).getNodeId();
            }
        }
        return null;
    }

    @Test
    void theCopyIsRecordedInTheChangesetWhichIsReplacedByANewOne() throws Exception {
        HgRepository repo = build();
        CopyCommand.PatternResult r = atRev(repo, hex(r3), true, false, "a.txt", "d.txt");
        assertFalse(r.failed());
        Revlog cl = changelog(repo);
        assertEquals(-1, cl.findRevision(r3), "the old changeset is gone");
        byte[] fresh = headWithMessage(repo, "r3");
        assertNotNull(fresh);
        assertFalse(java.util.Arrays.equals(r3, fresh));
        assertEquals("a.txt", copyOf(repo, "d.txt", fresh));
        assertEquals(4, cl.getRevisionCount(), "r2 stays (it was saved and added again), r3 was replaced");
        assertTrue(ChangesetRewrite.read(cl, cl.findRevision(fresh)).extra.containsKey("amend_source"));
        assertEquals(hex(r3), ChangesetRewrite.read(cl, cl.findRevision(fresh)).extra.get("amend_source"));
        assertEquals(1, r.status().size());
        assertTrue(r.status().get(0).startsWith("saved backup bundle to "));
        assertTrue(r.status().get(0).contains("-copy.hg"), r.status().get(0));
        assertEquals(List.of(), new VerifyCommand(repo).call());
        assertNotEquals(-1, cl.findRevision(r2));
    }

    @Test
    void doingItAgainReplacesItAgain() throws Exception {
        HgRepository repo = build();
        atRev(repo, hex(r3), true, false, "a.txt", "d.txt");
        byte[] first = headWithMessage(repo, "r3");
        atRev(repo, hex(first), true, false, "a.txt", "d.txt");
        byte[] second = headWithMessage(repo, "r3");
        assertFalse(java.util.Arrays.equals(first, second), "amend_source makes the new changeset differ even when nothing else does");
        assertEquals(List.of(), new VerifyCommand(repo).call());
    }

    @Test
    void forgetRemovesTheCopyInformation() throws Exception {
        HgRepository repo = build();
        atRev(repo, hex(r3), true, false, "a.txt", "d.txt");
        byte[] marked = headWithMessage(repo, "r3");
        CopyCommand.PatternResult r = atRev(repo, hex(marked), false, true, "d.txt");
        byte[] fresh = headWithMessage(repo, "r3");
        assertNull(copyOf(repo, "d.txt", fresh));
        assertTrue(r.status().get(0).contains("-uncopy.hg"), r.status().get(0));
        // nothing marked: real hg warns and replaces the changeset all the same
        CopyCommand.PatternResult nothing = atRev(repo, hex(fresh), false, true, "d.txt");
        assertEquals(List.of("d.txt: not unmarking as copy - file is not marked as copied"), nothing.warnings());
        assertEquals(List.of(), new VerifyCommand(repo).call());
    }

    @Test
    void aBookmarkFollowsTheNewChangeset() throws Exception {
        HgRepository repo = build();
        new BookmarkCommand(repo).setBookmarkName("bm").setRevision(hex(r3)).call();
        atRev(repo, hex(r3), true, false, "a.txt", "d.txt");
        byte[] fresh = headWithMessage(repo, "r3");
        assertEquals(hex(fresh), new BookmarkCommand(repo).list().get("bm"));
    }

    @Test
    void theWorkingCopyParentFollowsAndThePhaseIsKept() throws Exception {
        HgRepository repo = build();
        // the working copy is on r3 (the last commit)
        PhaseRoots.Phase before = repo.getPhaseRoots().getPhase(new NodeId(r3), changelog(repo));
        atRev(repo, hex(r3), true, false, "a.txt", "d.txt");
        byte[] fresh = headWithMessage(repo, "r3");
        assertArrayEquals(fresh, repo.getDirstate().getParent1());
        assertEquals(before, repo.getPhaseRoots().getPhase(new NodeId(fresh), changelog(repo)));
    }

    @Test
    void refusalsWhereRealHgRefuses() throws Exception {
        HgRepository repo = build();
        assertEquals("--at-rev requires --after", assertThrows(HgValidationException.class,
                () -> atRev(repo, hex(r3), false, false, "a.txt", "d.txt")).getMessage());
        assertEquals("zzz: copy destination does not exist in " + hex(r3).substring(0, 12), assertThrows(HgValidationException.class,
                () -> atRev(repo, hex(r3), true, false, "a.txt", "zzz")).getMessage());
        assertEquals("cannot uncopy changeset, as that will orphan 2 descendants", assertThrows(HgValidationException.class,
                () -> atRev(repo, hex(r1), true, false, "a.txt", "b.txt")).getMessage());
        assertEquals("--at-rev requires a single source", assertThrows(HgValidationException.class,
                () -> atRev(repo, hex(r3), true, false, "a.txt", "b.txt", "d.txt")).getMessage());
        new PhaseCommand(repo).setRevision(hex(r3)).setPhase(0).call();
        assertEquals("cannot uncopy public changesets: " + hex(r3).substring(0, 12), assertThrows(HgValidationException.class,
                () -> atRev(repo, hex(r3), true, false, "a.txt", "d.txt")).getMessage());
        assertEquals(List.of(), new VerifyCommand(repo).call());
    }

    @Test
    void aSourceThatIsNotInTheParentIsReported() throws Exception {
        HgRepository repo = build();
        HgValidationException refused = assertThrows(HgValidationException.class,
                () -> atRev(repo, hex(r3), true, false, "a.txt", "d.txt", "a.txt", "b.txt"));
        assertEquals("--at-rev requires a single source", refused.getMessage());
    }

    // ------------------------------------------------------------------ what the locks are for

    @Test
    void itWaitsForBothLocksAndFailsWhenTheyStayHeld() throws Exception {
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
                assertThrows(HgLockException.class, () -> atRev(fresh, hex(r3), true, false, "a.txt", "d.txt"));
            } finally {
                release.countDown();
                holder.join();
            }
        }
        assertNotEquals(-1, changelog(repo).findRevision(r3), "a rewrite that never got the locks changed nothing");
    }

    @Test
    void severalRewritesAtTheSameMomentAreSerializedAndLoseNothing() throws Exception {
        // three heads on r0, each with a plain copy of a.txt: three threads record the copy in their own head at the same moment
        dir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(dir).call();
        byte[] base = commit(repo, "a.txt", "A\n", "base");
        List<byte[]> heads = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            new UpdateCommand(repo).setRevision(hex(base)).call();
            heads.add(commit(repo, "d" + i + ".txt", "A\n", "head " + i));
        }
        Files.writeString(new File(dir, ".hg/hgrc").toPath(), "[ui]\ntimeout = 120\n");
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String head = hex(heads.get(i - 1));
            String file = "d" + i + ".txt";
            done.add(pool.submit(() -> {
                try (HgRepository own = new HgRepository(dir)) {
                    start.await();
                    atRev(own, head, true, false, "a.txt", file);
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
            assertEquals(4, cl.getRevisionCount(), "base and the three rewritten heads, none lost and none duplicated");
            for (int i = 1; i <= 3; i++) {
                byte[] head = headWithMessage(check, "head " + i);
                assertNotNull(head, "head " + i);
                assertEquals("a.txt", copyOf(check, "d" + i + ".txt", head), "head " + i + " has its copy recorded");
            }
            assertEquals(List.of(), new VerifyCommand(check).call());
            assertFalse(new File(dir, ".hg/wlock").exists());
            assertFalse(new File(dir, ".hg/store/lock").exists());
        }
    }
}
