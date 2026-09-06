package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.function.UnaryOperator;

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
        // Evolution-only rebase (2026-09-04): rootA is never physically stripped (only hidden via
        // an obsolescence marker), so both original roots remain plus the freshly rebased rootA'.
        assertEquals(3, cl.getRevisionCount(), "rootA (kept, now hidden) + rootB + rebased rootA'");

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
        // Evolution-only rebase (2026-09-04): c1 and c3 are never physically stripped (only hidden
        // via obsolescence markers), so all 5 original revisions (c0,c1,c2,c3,rootD) remain, plus
        // the 2 freshly cherry-picked ones (c1',c3') = 7, not the pre-2026-09-04 5.
        assertEquals(7, cl.getRevisionCount());

        int restoredC2Rev = NodeIdUtil.findRevisionByNodeId(cl, c2);
        assertTrue(restoredC2Rev != -1, "independent branch commit must be restored preserving its node id");

        int rebasedTipRev = NodeIdUtil.findRevisionByNodeId(cl, rebasedTip);
        assertTrue(new String(cl.getRevisionContent(rebasedTipRev), StandardCharsets.UTF_8).contains("merge, descendant"),
                "rebased tip must be the flattened merge commit");
        assertEquals("Line 1 [MINE]\nLine 2\nLine 3 [THEIRS]\n", Files.readString(shared.toPath()),
                "merged content from both sides must survive the rebase's flattening cherry-pick");
    }

    // ------------------------------------------------------------------
    // A stale/corrupt filelog referenced by fncache must not abort the whole rebase. Since the
    // 2026-09-04 evolution-only rewrite, RebaseCommand.backupStoreFiles only ever plain-byte-copies
    // (never loads/parses) each fncache-listed filelog for its own crash-safety backup, so a
    // corrupt entry can no longer even throw here in the first place -- this test now mostly
    // documents that fact (the corrupt entry is simply inert).
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
        // Evolution-only rebase (2026-09-04): c2 is never physically stripped, so it remains
        // (hidden) alongside c0/c1, plus the freshly cherry-picked c2'.
        assertEquals(4, cl.getRevisionCount());
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
        // Evolution-only rebase (2026-09-04): c2 is never physically stripped, so it remains
        // (hidden) alongside c0/c1, plus the freshly cherry-picked c2'.
        assertEquals(4, cl.getRevisionCount());
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

    // ------------------------------------------------------------------
    // A symlink target that legitimately exceeds every real OS's symlink-target length
    // limit (PATH_MAX, ~4096 bytes on both Linux and macOS -- verified empirically:
    // creating a real symlink with a 5000-byte target fails with ENAMETOOLONG, while
    // writing the same 5000 bytes as ordinary file content succeeds) makes
    // Files.createSymbolicLink fail for a reason that has nothing to do with
    // permissions, so RebaseCommand.cherryPickBackup's/applyManifestToWorkingCopy's
    // createSymbolicLink-fails fallback must actually complete (write the target text
    // as a plain file) rather than being an intentionally-untaken defensive branch.
    // The filelog content is forged directly (same technique RebaseCommand.restoreBackup
    // itself uses) because no real symlink of that length can be created on this or any
    // POSIX filesystem to seed it through the normal add/commit path.
    // ------------------------------------------------------------------
    @Test
    public void rebaseSymlinkCreationFallsBackToPlainFileWhenTargetExceedsOsLimit(@TempDir Path tempDir) throws Exception {
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
        File bigLink = new File(repoDir, "biglink");
        Files.createSymbolicLink(bigLink.toPath(), Path.of("base.txt"));
        new AddCommand(repo).call();
        byte[] c2 = new CommitCommand(repo).setAuthor("t").setMessage("c2 (source, symlink)").call();

        byte[] tooLongTarget = forgeOverLengthSymlinkContent(repo, "biglink");

        RebaseCommand rebaseCmd = new RebaseCommand(repo).setSource(c2).setTarget(c1);
        byte[] rebasedTip = rebaseCmd.call();
        assertNotNull(rebasedTip);

        assertFalse(Files.isSymbolicLink(bigLink.toPath()),
                "an over-length symlink target must fall back to a plain file, not a real symlink");
        assertEquals(new String(tooLongTarget, StandardCharsets.UTF_8), Files.readString(bigLink.toPath()),
                "fallback plain file must contain the untruncated target bytes");
    }

    // ------------------------------------------------------------------
    // Same over-length-symlink-target fallback as above, but reached through
    // RebaseCommand.checkoutNode/applyManifestToWorkingCopy instead of
    // cherryPickBackup: here the REBASE TARGET itself carries the over-length symlink,
    // so cherryPickBackup's very first step -- checking out the new parent before
    // writing any of the source's own files -- is what hits the fallback.
    // ------------------------------------------------------------------
    @Test
    public void rebaseTargetCheckoutSymlinkFallsBackToPlainFileWhenTargetExceedsOsLimit(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        Files.writeString(new File(repoDir, "base.txt").toPath(), "base");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("t").setMessage("c0").call();

        File bigLink = new File(repoDir, "biglink");
        Files.createSymbolicLink(bigLink.toPath(), Path.of("base.txt"));
        new AddCommand(repo).call();
        byte[] c1 = new CommitCommand(repo).setAuthor("t").setMessage("c1 (target, symlink)").call();
        byte[] tooLongTarget = forgeOverLengthSymlinkContent(repo, "biglink");

        // The real, valid, short-target symlink physically written for c1 above must be
        // removed from both the working copy and the dirstate before switching to c0's
        // independent lineage -- left on disk it would linger as an untracked file that
        // AddCommand below would pick up for c2, re-introducing a real (valid) symlink
        // that masks the fallback this test means to exercise; left only in the dirstate
        // (without a parent reset clearing it) CommitCommand would refuse the commit as
        // a missing tracked file.
        Files.delete(bigLink.toPath());
        Dirstate ds = repo.getDirstate();
        ds.removeEntry("biglink");
        ds.setParents(c0, new byte[20]);
        repo.writeDirstate(ds);
        Files.writeString(new File(repoDir, "feature.txt").toPath(), "feature");
        new AddCommand(repo).call();
        byte[] c2 = new CommitCommand(repo).setAuthor("t").setMessage("c2 (source)").call();

        RebaseCommand rebaseCmd = new RebaseCommand(repo).setSource(c2).setTarget(c1);
        byte[] rebasedTip = rebaseCmd.call();
        assertNotNull(rebasedTip);

        assertFalse(Files.isSymbolicLink(bigLink.toPath()),
                "an over-length symlink target must fall back to a plain file when checking out the target, not a real symlink");
        assertEquals(new String(tooLongTarget, StandardCharsets.UTF_8), Files.readString(bigLink.toPath()),
                "fallback plain file must contain the untruncated target bytes");
    }

    private static byte[] forgeOverLengthSymlinkContent(HgRepository repo, String path) throws IOException {
        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), path);
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        Revlog originalFilelog = repo.getRevlog(flIdx, flDat);
        Revlog.IndexRecord origRec = originalFilelog.getIndexRecord(0);
        byte[] origNode = origRec.getNodeId();
        int origLinkRev = origRec.getLinkRev();

        Files.deleteIfExists(flIdx.toPath());
        Files.deleteIfExists(flDat.toPath());
        repo.clearRevlogCache();

        byte[] tooLongTarget = "a".repeat(5000).getBytes(StandardCharsets.UTF_8);
        Revlog forgedFilelog = repo.getRevlog(flIdx, flDat);
        forgedFilelog.appendRawRevision(tooLongTarget, origNode, -1, -1, new byte[20], new byte[20], origLinkRev);
        repo.clearRevlogCache();
        return tooLongTarget;
    }

    // ------------------------------------------------------------------
    // A target commit whose changelog entry references a manifest node id that the
    // manifest revlog does not have must fail closed with a clear exception (rather than
    // NPE-ing or silently checking out an empty tree) -- exercises
    // RebaseCommand.applyManifestToWorkingCopy's manifest-not-found guard, reached via
    // checkoutNode when cherry-picking onto that corrupted target. The corruption is
    // forged directly onto an otherwise-real commit's changelog entry (only its
    // manifest-hex field is replaced), mirroring the truncate+reappend technique
    // RebaseCommand.restoreBackup itself relies on.
    // ------------------------------------------------------------------
    @Test
    public void rebaseRollsBackWhenTargetManifestNodeIsMissing(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        Files.writeString(new File(repoDir, "a.txt").toPath(), "a");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("t").setMessage("c0 (doomed target)").call();

        byte[] bogusManifestHex = new byte[20];
        Arrays.fill(bogusManifestHex, (byte) 0xAB);
        patchLastChangelogRevision(repo, text -> {
            int firstNl = text.indexOf('\n');
            return NodeIdUtil.toHex(bogusManifestHex) + text.substring(firstNl);
        });

        Dirstate ds = repo.getDirstate();
        ds.setParents(new byte[20], new byte[20]);
        repo.writeDirstate(ds);
        Files.writeString(new File(repoDir, "b.txt").toPath(), "b");
        new AddCommand(repo).call();
        byte[] c1 = new CommitCommand(repo).setAuthor("t").setMessage("c1 (source, independent root)").call();

        RebaseCommand rebaseCmd = new RebaseCommand(repo).setSource(c1).setTarget(c0);
        assertThrows(HgRevisionNotFoundException.class, rebaseCmd::call,
                "checking out a target whose manifest node id doesn't exist must fail, not silently misbehave");

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog cl = new Revlog(clIdx, clDat);
        assertEquals(2, cl.getRevisionCount(), "physical rollback must restore both revisions");
        assertFalse(new File(repo.getStoreDir(), "rebase-backup").exists());
        assertFalse(new File(repo.getStoreDir(), "journal").exists());
    }

    // ------------------------------------------------------------------
    // Real hg commits always write a "<time> <offset> <extra>" (or "<time> <offset>")
    // date line with at least one space, and any offset field they write always parses
    // as a valid integer -- so RebaseCommand.readRevisionMeta's defensive
    // NumberFormatException handling and its whole "no space at all" fallback branch are
    // never exercised by any normally-produced changelog entry. Forging the date line of
    // the revision actually being rebased -- the only revision this class ever reads
    // author/message/date metadata from, since the 2026-09-04 evolution-only rewrite no
    // longer "restores" untouched revisions at all (they are simply never stripped in the
    // first place, so there is nothing to restore and nothing to read metadata from) --
    // exercises both a non-numeric, space-less date line (this test: the "else" branch's
    // throwing-and-swallowed path) and a purely-numeric space-less one (the companion test
    // below: that branch's normal, non-throwing completion). Only the date line is
    // corrupted -- manifest/file content stays real and valid, so the rebased revision
    // still cherry-picks correctly.
    // ------------------------------------------------------------------
    @Test
    public void readRevisionMetaToleratesNonNumericSpacelessDateLine(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        Files.writeString(new File(repoDir, "target.txt").toPath(), "target");
        new AddCommand(repo).call();
        byte[] target = new CommitCommand(repo).setAuthor("t").setMessage("rebase target").call();

        Dirstate ds = repo.getDirstate();
        ds.setParents(new byte[20], new byte[20]);
        repo.writeDirstate(ds);
        Files.writeString(new File(repoDir, "src.txt").toPath(), "src");
        new AddCommand(repo).call();
        byte[] source = new CommitCommand(repo).setAuthor("t").setMessage("source (rebased)").call();
        patchLastChangelogRevision(repo, text -> replaceDateLine(text, "not-a-number-no-space"));

        RebaseCommand rebaseCmd = new RebaseCommand(repo).setSource(source).setTarget(target);
        byte[] rebasedTip = rebaseCmd.call();
        assertNotNull(rebasedTip, "a malformed date line on the rebased revision must not abort the rebase");

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog cl = new Revlog(clIdx, clDat);
        // Evolution-only rebase (2026-09-04): source is never physically stripped, so it remains
        // (hidden) alongside target, plus the freshly cherry-picked source'.
        assertEquals(3, cl.getRevisionCount(), "target, kept-but-hidden source, and rebased source'");
        assertTrue(new String(cl.getRevisionContent(NodeIdUtil.findRevisionByNodeId(cl, rebasedTip)), StandardCharsets.UTF_8)
                .contains("source (rebased)"));
    }

    @Test
    public void readRevisionMetaAcceptsPurelyNumericSpacelessDateLine(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        Files.writeString(new File(repoDir, "target.txt").toPath(), "target");
        new AddCommand(repo).call();
        byte[] target = new CommitCommand(repo).setAuthor("t").setMessage("rebase target").call();

        Dirstate ds = repo.getDirstate();
        ds.setParents(new byte[20], new byte[20]);
        repo.writeDirstate(ds);
        Files.writeString(new File(repoDir, "src.txt").toPath(), "src");
        new AddCommand(repo).call();
        byte[] source = new CommitCommand(repo).setAuthor("t").setMessage("source (rebased)").call();
        patchLastChangelogRevision(repo, text -> replaceDateLine(text, "9876543210"));

        RebaseCommand rebaseCmd = new RebaseCommand(repo).setSource(source).setTarget(target);
        byte[] rebasedTip = rebaseCmd.call();
        assertNotNull(rebasedTip, "a purely-numeric space-less date line must parse without error");

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog cl = new Revlog(clIdx, clDat);
        assertEquals(3, cl.getRevisionCount(), "target, kept-but-hidden source, and rebased source'");
    }

    // ------------------------------------------------------------------
    // backupRevision's message-line reconstruction only exercises its "join with \n"
    // branch when the commit message spans more than one line -- every other test in
    // this suite uses single-line messages. A multi-line message rebased through
    // cherry-pick (which re-commits "[rebase] " + backup.message) must preserve every
    // line and the newlines between them, not just the first line.
    // ------------------------------------------------------------------
    @Test
    public void rebasePreservesMultiLineCommitMessage(@TempDir Path tempDir) throws Exception {
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
        String multiLineMessage = "summary line\n\nsecond paragraph\nthird line";
        byte[] c2 = new CommitCommand(repo).setAuthor("t").setMessage(multiLineMessage).call();

        RebaseCommand rebaseCmd = new RebaseCommand(repo).setSource(c2).setTarget(c1);
        byte[] rebasedTip = rebaseCmd.call();
        assertNotNull(rebasedTip);

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog cl = new Revlog(clIdx, clDat);
        int tipRev = NodeIdUtil.findRevisionByNodeId(cl, rebasedTip);
        String clText = new String(cl.getRevisionContent(tipRev), StandardCharsets.UTF_8);
        assertTrue(clText.endsWith("[rebase] " + multiLineMessage),
                "every line of a multi-line commit message must survive rebase, not just the first: " + clText);
    }

    // ------------------------------------------------------------------
    // performPhysicalRollback's own journal cleanup is best-effort: if the rebase fails
    // for an unrelated reason (here, writeRebaseJournal itself failing because the
    // journal path is unexpectedly a non-empty directory) and deleteRebaseJournal() is
    // then retried during rollback, it hits the exact same obstacle and must not let
    // that second failure mask the original exception or abort the rollback partway
    // through.
    // ------------------------------------------------------------------
    @Test
    public void rebaseRollbackToleratesJournalDeletionFailure(@TempDir Path tempDir) throws Exception {
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

        // writeRebaseJournal() unconditionally deletes then rewrites the "journal" file;
        // pre-creating it as a non-empty directory makes that first Files.deleteIfExists
        // throw DirectoryNotEmptyException (triggering rollback), and since nothing ever
        // clears the obstacle, deleteRebaseJournal()'s own retry during rollback hits the
        // exact same failure.
        File journalAsDir = new File(repo.getStoreDir(), "journal");
        assertTrue(journalAsDir.mkdir());
        Files.writeString(new File(journalAsDir, "blocker.txt").toPath(), "blocks Files.deleteIfExists");

        RebaseCommand rebaseCmd = new RebaseCommand(repo).setSource(c2).setTarget(c1);
        assertThrows(IOException.class, rebaseCmd::call);

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog cl = new Revlog(clIdx, clDat);
        assertEquals(3, cl.getRevisionCount(), "physical rollback of the store must still complete "
                + "even though the journal itself could not be cleaned up");
        assertFalse(new File(repo.getStoreDir(), "rebase-backup").exists(), "backup dir must still be cleaned after rollback");
    }

    private static String replaceDateLine(String changelogText, String newDateLine) {
        int firstNl = changelogText.indexOf('\n');
        int secondNl = changelogText.indexOf('\n', firstNl + 1);
        int thirdNl = changelogText.indexOf('\n', secondNl + 1);
        return changelogText.substring(0, secondNl + 1) + newDateLine + "\n" + changelogText.substring(thirdNl + 1);
    }

    private static void patchLastChangelogRevision(HgRepository repo, UnaryOperator<String> transform) throws IOException {
        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog changelog = repo.getRevlog(clIdx, clDat);

        int rev = changelog.getRevisionCount() - 1;
        Revlog.IndexRecord rec = changelog.getIndexRecord(rev);
        byte[] node = rec.getNodeId();
        int linkRev = rec.getLinkRev();
        int parent1 = rec.getParent1();
        int parent2 = rec.getParent2();
        byte[] p1Node = parent1 != -1 ? changelog.getIndexRecord(parent1).getNodeId() : new byte[20];
        byte[] p2Node = parent2 != -1 ? changelog.getIndexRecord(parent2).getNodeId() : new byte[20];

        byte[] rawContent = changelog.getRawRevisionContent(rev);
        String text = new String(rawContent, StandardCharsets.UTF_8);
        byte[] newRaw = transform.apply(text).getBytes(StandardCharsets.UTF_8);

        long idxSize = (long) rev * 64;
        long datSize = rec.getOffset();
        truncateForTest(clIdx, idxSize);
        truncateForTest(clDat, datSize);
        repo.clearRevlogCache();

        Revlog freshChangelog = repo.getRevlog(clIdx, clDat);
        freshChangelog.appendRawRevision(newRaw, node, parent1, parent2, p1Node, p2Node, linkRev);
        repo.clearRevlogCache();
    }

    private static void truncateForTest(File file, long size) throws IOException {
        if (!file.exists()) return;
        if (size == 0) {
            Files.deleteIfExists(file.toPath());
        } else {
            try (FileChannel outChan = FileChannel.open(file.toPath(), StandardOpenOption.WRITE)) {
                outChan.truncate(size);
                outChan.force(true);
            }
        }
    }
}
