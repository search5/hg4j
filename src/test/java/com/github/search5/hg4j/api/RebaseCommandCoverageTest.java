package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage for {@link RebaseCommand} targeting branches not exercised by
 * {@link RebaseCommandTest}: rebasing from a true root revision (no parent1), restoring
 * a merge commit whose file was itself merged from two filelog parents, tolerating a
 * corrupt/unreadable filelog referenced by fncache during the physical strip, and
 * newly-introduced executable/symlink files surviving a rebase cherry-pick.
 */
public class RebaseCommandCoverageTest {

    // ------------------------------------------------------------------
    // Rebase whose source is the repository's very first (parentless) commit:
    // exercises BackupCommit.parent1Node == new byte[20] (RebaseCommand.backupRevision,
    // revRec.getParent1() == -1) and the matching stripRevisionsFrom() manifest-truncate
    // branch where the earliest in-range manifest revision is manifest rev 0 itself.
    // ------------------------------------------------------------------
    @Test
    public void rebaseFromRootRevisionOntoUnrelatedRootSucceeds(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // Root A (rev 0) -- this will be the rebase *source*, i.e. minOrigRev == 0.
        File fa = new File(repoDir, "a.txt");
        Files.writeString(fa.toPath(), "root A content");
        new AddCommand(repo).call();
        byte[] rootA = new CommitCommand(repo).setAuthor("t").setMessage("root A").call();

        // A second, unrelated root (rev 1) used as the rebase target -- same
        // null-parent-reset mechanism already used by BisectCommandCoverageTest /
        // MergeCommandCoverageTest to build a second independent DAG root.
        Dirstate ds = repo.getDirstate();
        ds.setParents(new byte[20], new byte[20]);
        repo.writeDirstate(ds);
        File fb = new File(repoDir, "b.txt");
        Files.writeString(fb.toPath(), "root B content");
        new AddCommand(repo).call();
        byte[] rootB = new CommitCommand(repo).setAuthor("t").setMessage("root B (unrelated)").call();

        RebaseCommand rebaseCmd = new RebaseCommand(repo).setSource(rootA).setTarget(rootB);
        byte[] rebasedTip = rebaseCmd.call();
        assertNotNull(rebasedTip);

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog cl = new Revlog(clIdx, clDat);
        assertEquals(2, cl.getRevisionCount(), "rootB restored + rebased rootA'");

        int rebasedRev = NodeIdUtil.findRevisionByNodeId(cl, rebasedTip);
        int rootBRev = NodeIdUtil.findRevisionByNodeId(cl, rootB);
        assertTrue(rootBRev != -1, "unrelated root must be physically restored with its original node id");
        assertEquals(rootBRev, cl.getIndexRecord(rebasedRev).getParent1(),
                "rebased root commit must attach onto the target root");
        assertTrue(new String(cl.getRevisionContent(rebasedRev), StandardCharsets.UTF_8).contains("root A"));
    }

    // ------------------------------------------------------------------
    // A revision within the backed-up range references a file whose *own* filelog
    // revision was produced by a real two-parent merge (both branches modified the
    // same file on different lines, auto-merged cleanly) -- exercises
    // RebaseCommand.backupRevision's FileBackupInfo.p2Node capture.
    // ------------------------------------------------------------------
    @Test
    public void rebaseRangeIncludingMergeCommitWithDualParentFilelogEntry(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File shared = new File(repoDir, "shared.txt");
        Files.writeString(shared.toPath(), "Line 1\nLine 2\nLine 3\n");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("t").setMessage("c0 base").call();

        // "Yours" branch (rebase source): touches line 1 only.
        Files.writeString(shared.toPath(), "Line 1 [MINE]\nLine 2\nLine 3\n");
        byte[] c1 = new CommitCommand(repo).setAuthor("t").setMessage("c1 (rebase source)").call();

        // "Theirs" branch: back to base, touches line 3 only.
        Dirstate ds = repo.getDirstate();
        ds.setParents(c0, new byte[20]);
        repo.writeDirstate(ds);
        Files.writeString(shared.toPath(), "Line 1\nLine 2\nLine 3 [THEIRS]\n");
        byte[] c2 = new CommitCommand(repo).setAuthor("t").setMessage("c2 (independent)").call();

        // Merge c1 into c2 (dirstate currently on c2) -- non-conflicting 3-way merge
        // producing a shared.txt filelog revision with both c1's and c2's file
        // revisions as parents.
        MergeCommand.MergeResult mergeResult = new MergeCommand(repo).setNodeId(c1).call();
        assertFalse(mergeResult.isConflicted(), "disjoint line edits must merge cleanly");
        byte[] c3 = new CommitCommand(repo).setAuthor("t").setMessage("c3 (merge, descendant of source)").call();

        // A second, unrelated root used as the rebase target so that c2 (independent
        // branch) and c3 (merge) end up split across to-rebase/to-restore.
        ds = repo.getDirstate();
        ds.setParents(new byte[20], new byte[20]);
        repo.writeDirstate(ds);
        Files.writeString(new File(repoDir, "d.txt").toPath(), "root D content");
        new AddCommand(repo).call();
        byte[] rootD = new CommitCommand(repo).setAuthor("t").setMessage("root D (rebase target)").call();

        RebaseCommand rebaseCmd = new RebaseCommand(repo).setSource(c1).setTarget(rootD);
        byte[] rebasedTip = rebaseCmd.call();
        assertNotNull(rebasedTip);

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog cl = new Revlog(clIdx, clDat);
        // c0, rootD, rebased c1', rebased c3', restored c2 == 5 revisions.
        assertEquals(5, cl.getRevisionCount());

        int restoredC2Rev = NodeIdUtil.findRevisionByNodeId(cl, c2);
        assertTrue(restoredC2Rev != -1, "independent branch commit must be restored preserving its node id");

        int rebasedTipRev = NodeIdUtil.findRevisionByNodeId(cl, rebasedTip);
        assertTrue(new String(cl.getRevisionContent(rebasedTipRev), StandardCharsets.UTF_8).contains("merge, descendant"),
                "rebased tip must be the flattened merge commit");
        assertEquals("Line 1 [MINE]\nLine 2\nLine 3 [THEIRS]\n", Files.readString(shared.toPath()),
                "merged content from both sides must survive the rebase's flattening cherry-pick");
    }

    // ------------------------------------------------------------------
    // A stale/corrupt filelog referenced by fncache must not abort the whole rebase --
    // exercises the per-entry try/catch in RebaseCommand.stripRevisionsFrom.
    // ------------------------------------------------------------------
    @Test
    public void rebaseToleratesCorruptFilelogListedInFncache(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        Files.writeString(new File(repoDir, "base.txt").toPath(), "base");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("t").setMessage("c0").call();

        Files.writeString(new File(repoDir, "main.txt").toPath(), "main");
        new AddCommand(repo).call();
        byte[] c1 = new CommitCommand(repo).setAuthor("t").setMessage("c1 (target)").call();

        Dirstate ds = repo.getDirstate();
        ds.setParents(c0, new byte[20]);
        repo.writeDirstate(ds);
        Files.writeString(new File(repoDir, "feature.txt").toPath(), "feature");
        new AddCommand(repo).call();
        byte[] c2 = new CommitCommand(repo).setAuthor("t").setMessage("c2 (source)").call();

        // Register a bogus fncache entry pointing at a corrupt (too-short) revlog index
        // file, simulating a stale/damaged fncache entry left over from an unrelated
        // failure. RevlogIndex rejects any index file under 64 bytes as corrupt.
        File fncacheFile = new File(repo.getStoreDir(), "fncache");
        assertTrue(fncacheFile.exists(), "commits must have populated fncache");
        File corruptIdx = new File(repo.getStoreDir(), "data/_corrupt.i");
        corruptIdx.getParentFile().mkdirs();
        Files.write(corruptIdx.toPath(), new byte[]{1, 2, 3, 4});
        Files.write(fncacheFile.toPath(), ("\n" + "data/_corrupt.i" + "\n").getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);

        RebaseCommand rebaseCmd = new RebaseCommand(repo).setSource(c2).setTarget(c1);
        byte[] rebasedTip = rebaseCmd.call();
        assertNotNull(rebasedTip, "a corrupt, unrelated fncache entry must not abort the rebase");

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog cl = new Revlog(clIdx, clDat);
        assertEquals(3, cl.getRevisionCount());
    }

    // ------------------------------------------------------------------
    // A brand-new executable file and a brand-new symlink introduced by the very
    // revision being cherry-picked (not inherited from a common ancestor already
    // checked out) must retain their mode/symlink-ness after rebase -- verified
    // against real hg 7.2 (`hg rebase -s -d`, scratch repo): both the exec bit and
    // the symlink survive rebasing a commit that newly adds them.
    // ------------------------------------------------------------------
    @Test
    public void rebaseNewlyAddedExecutableAndSymlinkFilesPreserveFlags(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        Files.writeString(new File(repoDir, "base.txt").toPath(), "base");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("t").setMessage("c0").call();

        Files.writeString(new File(repoDir, "main.txt").toPath(), "main");
        new AddCommand(repo).call();
        byte[] c1 = new CommitCommand(repo).setAuthor("t").setMessage("c1 (target)").call();

        Dirstate ds = repo.getDirstate();
        ds.setParents(c0, new byte[20]);
        repo.writeDirstate(ds);

        File runSh = new File(repoDir, "run.sh");
        Files.writeString(runSh.toPath(), "#!/bin/sh\necho hi\n");
        assertTrue(runSh.setExecutable(true, false));

        File myLink = new File(repoDir, "mylink");
        Files.createSymbolicLink(myLink.toPath(), Path.of("run.sh"));

        new AddCommand(repo).call();
        byte[] c2 = new CommitCommand(repo).setAuthor("t").setMessage("c2 (source, adds exec+symlink)").call();

        RebaseCommand rebaseCmd = new RebaseCommand(repo).setSource(c2).setTarget(c1);
        byte[] rebasedTip = rebaseCmd.call();
        assertNotNull(rebasedTip);

        assertTrue(runSh.exists(), "run.sh must exist after rebase");
        assertEquals("#!/bin/sh\necho hi\n", Files.readString(runSh.toPath()),
                "run.sh content must survive rebase unmangled");
        assertTrue(runSh.canExecute(), "newly-added executable bit must survive rebase cherry-pick");

        assertTrue(Files.isSymbolicLink(myLink.toPath()),
                "newly-added symlink must survive rebase cherry-pick as an actual symlink, "
                        + "not a regular file containing the link target text");
        assertEquals("run.sh", Files.readSymbolicLink(myLink.toPath()).toString());
    }

    // ------------------------------------------------------------------
    // Registering the obsolescence marker linking the original commit to its rebased
    // replacement is explicitly documented as "non-blocking" (RebaseCommand.call()); a
    // corrupt/unwritable obsstore must not abort an otherwise-successful rebase.
    // ------------------------------------------------------------------
    @Test
    public void rebaseSucceedsWhenObsstoreCannotBeWritten(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        Files.writeString(new File(repoDir, "base.txt").toPath(), "base");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("t").setMessage("c0").call();

        Files.writeString(new File(repoDir, "main.txt").toPath(), "main");
        new AddCommand(repo).call();
        byte[] c1 = new CommitCommand(repo).setAuthor("t").setMessage("c1 (target)").call();

        Dirstate ds = repo.getDirstate();
        ds.setParents(c0, new byte[20]);
        repo.writeDirstate(ds);
        Files.writeString(new File(repoDir, "feature.txt").toPath(), "feature");
        new AddCommand(repo).call();
        byte[] c2 = new CommitCommand(repo).setAuthor("t").setMessage("c2 (source)").call();

        // Force HgObsMarker.writeMarker to fail: it opens "<store>/obsstore" as a
        // FileOutputStream, which throws when that path is already a directory.
        File obsstoreAsDir = new File(repo.getStoreDir(), "obsstore");
        assertTrue(obsstoreAsDir.mkdir());

        RebaseCommand rebaseCmd = new RebaseCommand(repo).setSource(c2).setTarget(c1);
        byte[] rebasedTip = rebaseCmd.call();
        assertNotNull(rebasedTip, "a failing obsolescence-marker write must not abort the rebase");

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog cl = new Revlog(clIdx, clDat);
        assertEquals(3, cl.getRevisionCount());
    }

    // ------------------------------------------------------------------
    // A newly-introduced symlink whose containing directory has no write permission at
    // cherry-pick time: Files.createSymbolicLink fails (POSIX symlink creation needs
    // write access on the parent directory), exercising RebaseCommand.cherryPickBackup's
    // createSymbolicLink-fails fallback (which also fails here, for the same reason,
    // and correctly propagates as a genuine IOException that triggers physical rollback
    // rather than being silently swallowed).
    // ------------------------------------------------------------------
    @Test
    public void rebaseRollsBackWhenNewSymlinkParentDirIsUnwritable(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        Files.writeString(new File(repoDir, "base.txt").toPath(), "base");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("t").setMessage("c0").call();

        Files.writeString(new File(repoDir, "main.txt").toPath(), "main");
        new AddCommand(repo).call();
        byte[] c1 = new CommitCommand(repo).setAuthor("t").setMessage("c1 (target)").call();

        Dirstate ds = repo.getDirstate();
        ds.setParents(c0, new byte[20]);
        repo.writeDirstate(ds);
        File restrictedDir = new File(repoDir, "restricted");
        Files.createDirectories(restrictedDir.toPath());
        File newLink = new File(restrictedDir, "newlink");
        Files.createSymbolicLink(newLink.toPath(), Path.of("../base.txt"));
        new AddCommand(repo).call();
        byte[] c2 = new CommitCommand(repo).setAuthor("t").setMessage("c2 (source, adds restricted/newlink)").call();

        // The symlink's content is already durably stored in the filelog; remove the
        // physical entry and lock the directory down so cherry-picking must recreate it
        // from scratch under a directory it cannot write into.
        Files.delete(newLink.toPath());
        Files.setPosixFilePermissions(restrictedDir.toPath(), PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            RebaseCommand rebaseCmd = new RebaseCommand(repo).setSource(c2).setTarget(c1);
            assertThrows(IOException.class, rebaseCmd::call);

            File clIdx = new File(repo.getStoreDir(), "00changelog.i");
            File clDat = new File(repo.getStoreDir(), "00changelog.d");
            Revlog cl = new Revlog(clIdx, clDat);
            assertEquals(3, cl.getRevisionCount(), "physical rollback must restore the pre-rebase changelog");
            assertTrue(NodeIdUtil.findRevisionByNodeId(cl, c2) != -1, "original source commit must be restored on rollback");
            assertFalse(new File(repo.getStoreDir(), "rebase-backup").exists());
            assertFalse(new File(repo.getStoreDir(), "journal").exists());
        } finally {
            // Restore write permission so @TempDir cleanup can remove the directory.
            Files.setPosixFilePermissions(restrictedDir.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    // ------------------------------------------------------------------
    // Same fallback, but reached via RebaseCommand.checkoutNode/applyManifestToWorkingCopy:
    // here it is the rebase *target*'s own manifest that carries the symlink under an
    // unwritable directory, so the failure happens while checking out the new parent
    // before any of the source's own files are even written.
    // ------------------------------------------------------------------
    @Test
    public void rebaseRollsBackWhenTargetCheckoutSymlinkParentDirIsUnwritable(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        Files.writeString(new File(repoDir, "base.txt").toPath(), "base");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("t").setMessage("c0").call();

        // Target: introduces a symlink under "restricted/".
        File restrictedDir = new File(repoDir, "restricted");
        Files.createDirectories(restrictedDir.toPath());
        File newLink = new File(restrictedDir, "newlink");
        Files.createSymbolicLink(newLink.toPath(), Path.of("../base.txt"));
        new AddCommand(repo).call();
        byte[] c1 = new CommitCommand(repo).setAuthor("t").setMessage("c1 (target, adds restricted/newlink)").call();

        // Source: independent branch from c0, untouched by the restricted directory.
        Dirstate ds = repo.getDirstate();
        ds.setParents(c0, new byte[20]);
        repo.writeDirstate(ds);
        Files.writeString(new File(repoDir, "feature.txt").toPath(), "feature");
        new AddCommand(repo).call();
        byte[] c2 = new CommitCommand(repo).setAuthor("t").setMessage("c2 (source)").call();

        Files.delete(newLink.toPath());
        Files.setPosixFilePermissions(restrictedDir.toPath(), PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            RebaseCommand rebaseCmd = new RebaseCommand(repo).setSource(c2).setTarget(c1);
            assertThrows(IOException.class, rebaseCmd::call);

            File clIdx = new File(repo.getStoreDir(), "00changelog.i");
            File clDat = new File(repo.getStoreDir(), "00changelog.d");
            Revlog cl = new Revlog(clIdx, clDat);
            assertEquals(3, cl.getRevisionCount(), "physical rollback must restore the pre-rebase changelog");
            assertTrue(NodeIdUtil.findRevisionByNodeId(cl, c1) != -1, "target commit must be restored on rollback");
            assertTrue(NodeIdUtil.findRevisionByNodeId(cl, c2) != -1, "original source commit must be restored on rollback");
        } finally {
            Files.setPosixFilePermissions(restrictedDir.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    // ------------------------------------------------------------------
    // registerPreRebaseHook(null)/registerPostRebaseHook(null) must be silently ignored
    // (fluent no-op) rather than adding a null hook that would NPE when invoked.
    // ------------------------------------------------------------------
    @Test
    public void registeringNullHooksIsIgnoredAndRebaseStillRuns(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        Files.writeString(new File(repoDir, "base.txt").toPath(), "base");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("t").setMessage("c0").call();

        Files.writeString(new File(repoDir, "main.txt").toPath(), "main");
        new AddCommand(repo).call();
        byte[] c1 = new CommitCommand(repo).setAuthor("t").setMessage("c1 (target)").call();

        Dirstate ds = repo.getDirstate();
        ds.setParents(c0, new byte[20]);
        repo.writeDirstate(ds);
        Files.writeString(new File(repoDir, "feature.txt").toPath(), "feature");
        new AddCommand(repo).call();
        byte[] c2 = new CommitCommand(repo).setAuthor("t").setMessage("c2 (source)").call();

        RebaseCommand rebaseCmd = new RebaseCommand(repo)
                .registerPreRebaseHook(null)
                .registerPostRebaseHook(null)
                .setSource(c2)
                .setTarget(c1);
        byte[] rebasedTip = rebaseCmd.call();
        assertNotNull(rebasedTip, "registering a null hook must be a no-op, not break the rebase");
    }
}
