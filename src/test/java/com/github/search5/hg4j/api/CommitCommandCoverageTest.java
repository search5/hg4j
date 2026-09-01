package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.lib.HgLock;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.lib.NodeId;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.github.search5.hg4j.errors.HgLockException;
import com.github.search5.hg4j.errors.HgValidationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage for {@link CommitCommand}, focused on branches
 * {@link CommitCommandTest} does not exercise: the two-parent merge-commit manifest
 * reconciliation, the racy-hg content check, phase assignment, {@code skipLockAndJournal}
 * semantics, concurrent-lock rejection, and the dirstate-v2 rollback cleanup path.
 */
public class CommitCommandCoverageTest {

    @Test
    public void testMergeCommitReconcilesBothParentManifestsByContent(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {

            // --- Root commit: shared baseline for both branches ---
            File baseFile = new File(repoDir, "base.txt");
            File resolveP1File = new File(repoDir, "resolveP1.txt");
            File resolveP2File = new File(repoDir, "resolveP2.txt");
            File trueMergeFile = new File(repoDir, "trueMerge.txt");
            Files.writeString(baseFile.toPath(), "base content");
            Files.writeString(resolveP1File.toPath(), "orig r1");
            Files.writeString(resolveP2File.toPath(), "orig r2");
            Files.writeString(trueMergeFile.toPath(), "root value");
            new AddCommand(repo).call();
            byte[] rootNode = new CommitCommand(repo).setMessage("root").call();

            // --- P1 branch: edits resolveP1.txt, resolveMergeToP1.txt and trueMerge.txt, adds
            // onlyP1.txt ---
            File onlyP1File = new File(repoDir, "onlyP1.txt");
            File resolveMergeToP1File = new File(repoDir, "resolveMergeToP1.txt");
            Files.writeString(resolveP1File.toPath(), "p1 edit");
            Files.writeString(onlyP1File.toPath(), "only in p1");
            Files.writeString(resolveMergeToP1File.toPath(), "p1 wins content");
            Files.writeString(trueMergeFile.toPath(), "p1-edited-content");
            new AddCommand(repo).call();
            byte[] p1Node = new CommitCommand(repo).setMessage("p1 branch").call();

            // --- Branch back off the root to build P2 as a sibling of P1 ---
            Dirstate resetDirstate = repo.getDirstate();
            resetDirstate.setParents(new NodeId(rootNode), NodeId.NULL);
            resetDirstate.removeEntry("onlyP1.txt");
            repo.writeDirstate(resetDirstate);
            Files.deleteIfExists(onlyP1File.toPath());

            File onlyP2File = new File(repoDir, "onlyP2.txt");
            // resolveP1.txt reverts to the untouched root content on the P2 side.
            Files.writeString(resolveP1File.toPath(), "orig r1");
            Files.writeString(resolveP2File.toPath(), "p2 edit for r2");
            Files.writeString(onlyP2File.toPath(), "only in p2");
            Files.writeString(resolveMergeToP1File.toPath(), "p2 wins content");
            Files.writeString(trueMergeFile.toPath(), "p2-edited-content-longer");
            new AddCommand(repo).call();
            byte[] p2Node = new CommitCommand(repo).setMessage("p2 branch").call();

            // --- Set up the merge: dirstate parents = (P1, P2), working copy holds the
            // resolved content for the genuinely divergent files. ---
            Dirstate mergeDirstate = repo.getDirstate();
            mergeDirstate.setParents(new NodeId(p1Node), new NodeId(p2Node));

            // trueMerge.txt: resolved by hand to a value that matches *neither* parent exactly
            // (a real 3-way merge resolution), and its length differs from what the P2-branch
            // commit last recorded in dirstate, so the plain size check alone marks it "changed"
            // -- exercising the branch that appends a filelog revision with *both* P1's and P2's
            // filelog revisions as parents (a genuine per-file merge, not just a manifest pick).
            Files.writeString(trueMergeFile.toPath(), "final-merged-content");

            Files.writeString(onlyP1File.toPath(), "only in p1");
            mergeDirstate.addEntry("onlyP1.txt", new Dirstate.Entry('n', 0644,
                    (int) onlyP1File.length(), onlyP1File.lastModified() / 1000));

            // Resolve resolveMergeToP1.txt to the P1 side's content. Both branches' contents are
            // the same byte length, and the filelog's *last* revision happens to be P2's (it was
            // committed after P1's) -- so if this write's mtime looked "recent" to the M-2
            // racy-hg check, it would compare disk against that last (P2) revision, find a
            // mismatch, and misroute this into the "changed" path instead of the merge
            // disambiguation logic this test means to exercise. Backdating the mtime keeps it
            // out of the racy check's window entirely.
            Files.writeString(resolveMergeToP1File.toPath(), "p1 wins content");
            assertTrue(resolveMergeToP1File.setLastModified(System.currentTimeMillis() - 10_000));
            mergeDirstate.addEntry("resolveMergeToP1.txt", new Dirstate.Entry('n', 0644,
                    (int) resolveMergeToP1File.length(), resolveMergeToP1File.lastModified() / 1000));

            repo.writeDirstate(mergeDirstate);

            byte[] mergeNode = new CommitCommand(repo).setMessage("merge branches").call();
            assertNotNull(mergeNode);

            Map<String, String> p1Manifest = repo.getManifestAtCommit(p1Node);
            Map<String, String> p2Manifest = repo.getManifestAtCommit(p2Node);
            Map<String, String> mergedManifest = repo.getManifestAtCommit(mergeNode);

            // File only ever present in P1's manifest -> taken from P1 (hexP2 == null branch).
            assertEquals(p1Manifest.get("onlyP1.txt"), mergedManifest.get("onlyP1.txt"));
            // File only ever present in P2's manifest -> taken from P2 (hexP1 == null branch).
            assertEquals(p2Manifest.get("onlyP2.txt"), mergedManifest.get("onlyP2.txt"));
            // Untouched by either branch -> both sides equal, either is fine.
            assertEquals(p1Manifest.get("base.txt"), mergedManifest.get("base.txt"));
            assertEquals(p2Manifest.get("base.txt"), mergedManifest.get("base.txt"));

            // Genuinely divergent, unchanged-in-working-dir file: disk content matches P2's
            // side (the untouched-since-root content) so disambiguation must pick P2.
            assertNotEquals(p1Manifest.get("resolveP1.txt"), p2Manifest.get("resolveP1.txt"),
                    "Test setup sanity: the two sides must genuinely diverge on resolveP1.txt");
            assertEquals(p2Manifest.get("resolveP1.txt"), mergedManifest.get("resolveP1.txt"),
                    "Byte-level disambiguation must pick the side whose content matches disk");
            assertNotEquals(p1Manifest.get("resolveP1.txt"), mergedManifest.get("resolveP1.txt"));

            // Genuinely divergent file resolved on disk to match P1's side.
            assertNotEquals(p1Manifest.get("resolveMergeToP1.txt"), p2Manifest.get("resolveMergeToP1.txt"),
                    "Test setup sanity: the two sides must genuinely diverge on resolveMergeToP1.txt");
            assertEquals(p1Manifest.get("resolveMergeToP1.txt"), mergedManifest.get("resolveMergeToP1.txt"),
                    "Byte-level disambiguation must pick the side whose content matches disk");
            assertNotEquals(p2Manifest.get("resolveMergeToP1.txt"), mergedManifest.get("resolveMergeToP1.txt"));

            // The merge commit's changelog entry must record both parent revisions.
            File clIdx = new File(repo.getStoreDir(), "00changelog.i");
            File clDat = new File(repo.getStoreDir(), "00changelog.d");
            Revlog clRevlog = new Revlog(clIdx, clDat);
            assertEquals(4, clRevlog.getRevisionCount());
            Revlog.IndexRecord mergeRecord = clRevlog.getIndexRecord(3);
            assertEquals(1, mergeRecord.getParent1(), "Merge commit's first parent must be the P1 branch commit");
            assertEquals(2, mergeRecord.getParent2(), "Merge commit's second parent must be the P2 branch commit");

            // trueMerge.txt was genuinely changed relative to the dirstate record, and is present
            // (differently) in both parents' manifests -- the new filelog revision must record
            // *both* branches' own filelog revisions as its parents.
            assertNotEquals(p1Manifest.get("trueMerge.txt"), mergedManifest.get("trueMerge.txt"));
            assertNotEquals(p2Manifest.get("trueMerge.txt"), mergedManifest.get("trueMerge.txt"));
            // "trueMerge.txt" contains an uppercase letter, which real hg's store filename
            // encoding escapes (each uppercase ASCII letter becomes "_" + its lowercase form),
            // so the on-disk path isn't the literal "data/trueMerge.txt.i" -- resolve it the same
            // way CommitCommand itself does.
            File trueMergeFlIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "trueMerge.txt");
            File trueMergeFlDat = new File(trueMergeFlIdx.getPath().substring(0, trueMergeFlIdx.getPath().length() - 2) + ".d");
            Revlog trueMergeFilelog = new Revlog(trueMergeFlIdx, trueMergeFlDat);
            assertEquals(4, trueMergeFilelog.getRevisionCount());
            assertArrayEquals("final-merged-content".getBytes(StandardCharsets.UTF_8), trueMergeFilelog.getRevisionContent(3));
            Revlog.IndexRecord trueMergeRecord = trueMergeFilelog.getIndexRecord(3);
            assertEquals(1, trueMergeRecord.getParent1(), "Merged file revision's first parent must be the P1 branch's own edit");
            assertEquals(2, trueMergeRecord.getParent2(), "Merged file revision's second parent must be the P2 branch's own edit");

            // A finished commit always collapses dirstate back to a single parent.
            Dirstate finalDirstate = repo.getDirstate();
            assertArrayEquals(mergeNode, finalDirstate.getParent1());
            assertArrayEquals(new byte[20], finalDirstate.getParent2());
        }
    }

    @Test
    public void testRacyWriteWithinSameSecondIsDetectedViaFilelogComparison(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            File f = new File(repoDir, "racy.txt");
            String original = "0123456789";
            Files.writeString(f.toPath(), original);
            new AddCommand(repo).call();
            new CommitCommand(repo).setMessage("add racy.txt").call();

            // Overwrite with different content of the exact same byte length, then force the
            // dirstate entry to (falsely) agree with the new disk size/mtime, so only the
            // racy-hg filelog content comparison (M-2) can catch that content actually changed.
            String rewritten = "9876543210";
            Files.writeString(f.toPath(), rewritten);
            long diskTime = f.lastModified() / 1000;

            Dirstate dirstate = repo.getDirstate();
            Dirstate.Entry entry = dirstate.getEntries().get("racy.txt");
            dirstate.addEntry("racy.txt", new Dirstate.Entry(entry.getState(), entry.getMode(), rewritten.length(), diskTime));
            repo.writeDirstate(dirstate);

            new CommitCommand(repo).setMessage("racy commit").call();

            File flIdx = new File(repo.getStoreDir(), "data/racy.txt.i");
            File flDat = new File(repo.getStoreDir(), "data/racy.txt.d");
            Revlog filelog = new Revlog(flIdx, flDat);
            assertEquals(2, filelog.getRevisionCount(),
                    "The racy-hg check must catch the same-size/mtime content change and record a new revision");
            assertArrayEquals(rewritten.getBytes(StandardCharsets.UTF_8), filelog.getRevisionContent(1));
        }
    }

    @Test
    public void testCommitAssignsDraftPhaseAtCorrectStorePath(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            File f1 = new File(repoDir, "a.txt");
            Files.writeString(f1.toPath(), "content");
            new AddCommand(repo).call();
            byte[] commitNode = new CommitCommand(repo).setMessage("phase test").call();

            File phaserootsFile = new File(repo.getStoreDir(), "phaseroots");
            assertTrue(phaserootsFile.exists(), "phaseroots must be written under .hg/store");
            assertFalse(new File(repo.getHgDir(), "phaseroots").exists(),
                    "phaseroots must never be written directly under .hg (real hg writes .hg/store/phaseroots)");

            String content = Files.readString(phaserootsFile.toPath(), StandardCharsets.UTF_8);
            assertTrue(content.contains("1 " + NodeIdUtil.toHex(commitNode)),
                    "New commit must be recorded as DRAFT (phase value 1): " + content);
        }
    }

    @Test
    public void testEmptyOrNullAuthorFallsBackToDefaultAuthor(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            File f1 = new File(repoDir, "a.txt");
            Files.writeString(f1.toPath(), "content");
            new AddCommand(repo).call();

            new CommitCommand(repo)
                    .setAuthor(null)
                    .setAuthor("")
                    .setMessage("default author commit")
                    .call();

            File clIdx = new File(repo.getStoreDir(), "00changelog.i");
            File clDat = new File(repo.getStoreDir(), "00changelog.d");
            Revlog clRevlog = new Revlog(clIdx, clDat);
            String text = new String(clRevlog.getRevisionContent(0), StandardCharsets.UTF_8);
            assertTrue(text.contains("user <user@example.com>"), "Null/empty author must fall back to the default: " + text);
        }
    }

    @Test
    public void testSkipLockAndJournalCommitsWithoutJournalOrBackupFiles(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            File f1 = new File(repoDir, "a.txt");
            Files.writeString(f1.toPath(), "content");
            new AddCommand(repo).call();

            byte[] commitNode = new CommitCommand(repo)
                    .setMessage("skip lock and journal")
                    .setSkipLockAndJournal(true)
                    .call();

            assertNotNull(commitNode);
            assertFalse(new File(repo.getStoreDir(), "journal").exists(),
                    "skipLockAndJournal must never create a crash-recovery journal");
            assertFalse(new File(repoDir, ".hg/dirstate.backup").exists());
            assertFalse(new File(repo.getStoreDir(), "fncache.backup").exists());

            Dirstate dirstate = repo.getDirstate();
            assertArrayEquals(commitNode, dirstate.getParent1());
        }
    }

    @Test
    public void testSkipLockAndJournalFailureDoesNotRollbackPartialFilelogWrites(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            File aFile = new File(repoDir, "aaa.txt");
            Files.writeString(aFile.toPath(), "v1");
            new AddCommand(repo).call();
            new CommitCommand(repo).setMessage("commit 1").call();

            // "aaa.txt" sorts before "zzz_missing.txt", so the tree walk appends aaa.txt's new
            // filelog revision *before* it reaches the missing-file failure below.
            Files.writeString(aFile.toPath(), "v2");

            Dirstate d = repo.getDirstate();
            d.addEntry("zzz_missing.txt", new Dirstate.Entry('a', 0644, 100, System.currentTimeMillis() / 1000));
            repo.writeDirstate(d);

            CommitCommand cmd = new CommitCommand(repo).setMessage("fails, skip rollback").setSkipLockAndJournal(true);
            assertThrows(HgValidationException.class, cmd::call);

            File flIdx = new File(repo.getStoreDir(), "data/aaa.txt.i");
            File flDat = new File(repo.getStoreDir(), "data/aaa.txt.d");
            Revlog filelog = new Revlog(flIdx, flDat);
            assertEquals(2, filelog.getRevisionCount(),
                    "skipLockAndJournal must not roll back writes already made before the failure");
            assertArrayEquals("v2".getBytes(StandardCharsets.UTF_8), filelog.getRevisionContent(1));
        }
    }

    @Test
    public void testConcurrentStoreLockPreventsCommit(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            File f1 = new File(repoDir, "a.txt");
            Files.writeString(f1.toPath(), "content");
            new AddCommand(repo).call();

            CountDownLatch lockAcquired = new CountDownLatch(1);
            CountDownLatch releaseLock = new CountDownLatch(1);
            AtomicReference<Exception> holderError = new AtomicReference<>();
            Thread holder = new Thread(() -> {
                try (HgLock lock = repo.lockStore()) {
                    lockAcquired.countDown();
                    releaseLock.await();
                } catch (Exception e) {
                    holderError.set(e);
                    lockAcquired.countDown();
                }
            });
            holder.setDaemon(true);
            holder.start();
            try {
                assertTrue(lockAcquired.await(5, TimeUnit.SECONDS), "Background thread must acquire the store lock");
                assertNull(holderError.get(), "Background thread must successfully hold the store lock");

                CommitCommand cmd = new CommitCommand(repo).setMessage("should be blocked by concurrent store lock");
                assertThrows(HgLockException.class, cmd::call);
            } finally {
                releaseLock.countDown();
                holder.join(5000);
            }
        }
    }

    @Test
    public void testRollbackAfterDirstateWrittenCleansUpOrphanedDirstateV2DataFile(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).setDirstateV2(true).call()) {
            File f1 = new File(repoDir, "a.txt");
            Files.writeString(f1.toPath(), "v1");
            new AddCommand(repo).call();
            new CommitCommand(repo).setMessage("commit 1").call();

            // Activate a bookmark so the second commit's auto-advance step runs (and can fail).
            new BookmarkCommand(repo).setBookmarkName("feature").call();
            assertEquals("feature", new BookmarkCommand(repo).getActiveBookmark());

            File hgDir = repo.getHgDir();
            File[] dataFilesBefore = hgDir.listFiles((dir, name) -> name.startsWith("dirstate.") && !name.equals("dirstate.backup"));
            assertNotNull(dataFilesBefore);
            assertEquals(1, dataFilesBefore.length, "Exactly one dirstate-v2 data file should exist after the first commit");
            String originalDataFileName = dataFilesBefore[0].getName();

            // Corrupt bookmarks into a directory so BookmarkCommand.call() throws IOException
            // *after* the second commit has already rewritten the dirstate docket (and created a
            // brand-new dirstate-v2 data file). The active-bookmark advance step is the only
            // thing in call() after the dirstate write that isn't wrapped in its own try/catch,
            // so it's the one realistic way to trigger the N-1 rollback with an already-rewritten
            // dirstate-v2 docket that now points at an orphaned data file.
            File bkFile = new File(hgDir, "bookmarks");
            Files.delete(bkFile.toPath());
            Files.createDirectory(bkFile.toPath());

            Files.writeString(f1.toPath(), "v2");
            CommitCommand failing = new CommitCommand(repo).setMessage("commit 2 (will fail)");
            assertThrows(IOException.class, failing::call);

            Dirstate restored = repo.getDirstate();
            assertArrayEquals(new byte[20], restored.getParent2());

            File[] dataFilesAfter = hgDir.listFiles((dir, name) -> name.startsWith("dirstate.") && !name.equals("dirstate.backup"));
            assertNotNull(dataFilesAfter);
            assertEquals(1, dataFilesAfter.length, "Rollback must garbage-collect the orphaned dirstate-v2 data file");
            assertEquals(originalDataFileName, dataFilesAfter[0].getName(),
                    "The surviving data file must be the one the restored docket actually references");
        }
    }

    @Test
    public void testSetDateOverridesCommitTimestampAndTimezoneOffset(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            File f1 = new File(repoDir, "a.txt");
            Files.writeString(f1.toPath(), "content");
            new AddCommand(repo).call();

            long forcedSecs = 1_700_000_000L;
            int forcedOffset = 3600;
            new CommitCommand(repo)
                    .setMessage("dated commit")
                    .setDate(forcedSecs, forcedOffset)
                    .call();

            File clIdx = new File(repo.getStoreDir(), "00changelog.i");
            File clDat = new File(repo.getStoreDir(), "00changelog.d");
            Revlog clRevlog = new Revlog(clIdx, clDat);
            String text = new String(clRevlog.getRevisionContent(0), StandardCharsets.UTF_8);
            assertTrue(text.contains(forcedSecs + " " + forcedOffset),
                    "Explicit setDate() must override the wall-clock commit time and timezone offset: " + text);
        }
    }

    @Test
    public void testEncodeExtraKeyNullReturnsEmptyString() {
        assertEquals("", CommitCommand.encodeExtraKey(null));
    }
}
