package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.errors.HgRevisionNotFoundException;
import com.github.search5.hg4j.errors.HgValidationException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targeted coverage for {@link RebaseCommand}: input validation, PRE_REBASE/POST_REBASE hook
 * behavior, executable/symlink flag preservation, non-default branch preservation, merge-commit
 * independent-branch restoration, and crash-recovery rollback of a rebase that fails mid-flight.
 */
public class RebaseCommandTest {

    @Test
    public void nullSourceThrowsIllegalState(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        RebaseCommand rebaseCmd = new RebaseCommand(repo).setTarget(new byte[20]);
        assertThrows(IllegalStateException.class, rebaseCmd::call);
    }

    @Test
    public void nullTargetThrowsIllegalState(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        RebaseCommand rebaseCmd = new RebaseCommand(repo).setSource(new byte[20]);
        assertThrows(IllegalStateException.class, rebaseCmd::call);
    }

    @Test
    public void sourceRevisionNotFoundThrows(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "content");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("t").setMessage("c0").call();

        byte[] bogus = new byte[20];
        bogus[0] = (byte) 0xAB;
        RebaseCommand rebaseCmd = new RebaseCommand(repo).setSource(bogus).setTarget(c0);
        assertThrows(HgRevisionNotFoundException.class, rebaseCmd::call);
    }

    @Test
    public void targetRevisionNotFoundThrows(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "content");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("t").setMessage("c0").call();

        byte[] bogus = new byte[20];
        bogus[0] = (byte) 0xCD;
        RebaseCommand rebaseCmd = new RebaseCommand(repo).setSource(c0).setTarget(bogus);
        assertThrows(HgRevisionNotFoundException.class, rebaseCmd::call);
    }

    @Test
    public void preRebaseHookRejectionAbortsAndPreservesHistory(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        Files.writeString(new File(repoDir, "base.txt").toPath(), "base");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("t").setMessage("c0").call();

        Files.writeString(new File(repoDir, "main.txt").toPath(), "main");
        new AddCommand(repo).call();
        byte[] c1 = new CommitCommand(repo).setAuthor("t").setMessage("c1").call();

        Dirstate ds = repo.getDirstate();
        ds.setParents(c0, new byte[20]);
        repo.writeDirstate(ds);
        Files.writeString(new File(repoDir, "feature.txt").toPath(), "feature");
        new AddCommand(repo).call();
        byte[] c2 = new CommitCommand(repo).setAuthor("t").setMessage("c2").call();

        AtomicInteger firstHookCalls = new AtomicInteger();
        List<Map<String, Object>> secondHookContexts = new ArrayList<>();

        RebaseCommand rebaseCmd = new RebaseCommand(repo)
                .setSource(c2)
                .setTarget(c1)
                .registerPreRebaseHook(ctx -> {
                    firstHookCalls.incrementAndGet();
                    return true;
                })
                .registerPreRebaseHook(ctx -> {
                    secondHookContexts.add(ctx);
                    return false;
                });

        assertThrows(HgValidationException.class, rebaseCmd::call);
        assertEquals(1, firstHookCalls.get(), "first hook must run before the rejecting hook");
        assertEquals(1, secondHookContexts.size());
        Map<String, Object> ctx = secondHookContexts.get(0);
        assertArrayEquals(c2, (byte[]) ctx.get("sourceNode"));
        assertArrayEquals(c1, (byte[]) ctx.get("targetNode"));
        assertSame(repo, ctx.get("repository"));

        Revlog cl = new Revlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"));
        assertEquals(3, cl.getRevisionCount(), "rejected rebase must not touch history");
        assertFalse(new File(repo.getStoreDir(), "rebase-backup").exists());
        assertFalse(new File(repo.getStoreDir(), "journal").exists());
    }

    @Test
    public void postRebaseHookReceivesRebasedTipNodeContext(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        Files.writeString(new File(repoDir, "base.txt").toPath(), "base");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("t").setMessage("c0").call();

        Files.writeString(new File(repoDir, "main.txt").toPath(), "main");
        new AddCommand(repo).call();
        byte[] c1 = new CommitCommand(repo).setAuthor("t").setMessage("c1").call();

        Dirstate ds = repo.getDirstate();
        ds.setParents(c0, new byte[20]);
        repo.writeDirstate(ds);
        Files.writeString(new File(repoDir, "feature.txt").toPath(), "feature");
        new AddCommand(repo).call();
        byte[] c2 = new CommitCommand(repo).setAuthor("t").setMessage("c2").call();

        List<Map<String, Object>> postContexts = new ArrayList<>();
        RebaseCommand rebaseCmd = new RebaseCommand(repo)
                .setSource(c2)
                .setTarget(c1)
                .registerPostRebaseHook(ctx -> {
                    postContexts.add(ctx);
                    return true;
                });

        byte[] rebasedTip = rebaseCmd.call();
        assertNotNull(rebasedTip);
        assertEquals(1, postContexts.size());
        Map<String, Object> ctx = postContexts.get(0);
        assertArrayEquals(c2, (byte[]) ctx.get("sourceNode"));
        assertArrayEquals(c1, (byte[]) ctx.get("targetNode"));
        assertEquals(NodeIdUtil.toHex(rebasedTip), ctx.get("rebasedTipNode"));
        assertSame(repo, ctx.get("repository"));
    }

    @Test
    public void postRebaseHookExceptionDoesNotAbortRebase(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        Files.writeString(new File(repoDir, "base.txt").toPath(), "base");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("t").setMessage("c0").call();

        Files.writeString(new File(repoDir, "main.txt").toPath(), "main");
        new AddCommand(repo).call();
        byte[] c1 = new CommitCommand(repo).setAuthor("t").setMessage("c1").call();

        Dirstate ds = repo.getDirstate();
        ds.setParents(c0, new byte[20]);
        repo.writeDirstate(ds);
        Files.writeString(new File(repoDir, "feature.txt").toPath(), "feature");
        new AddCommand(repo).call();
        byte[] c2 = new CommitCommand(repo).setAuthor("t").setMessage("c2").call();

        RebaseCommand rebaseCmd = new RebaseCommand(repo)
                .setSource(c2)
                .setTarget(c1)
                .registerPostRebaseHook(ctx -> {
                    throw new IOException("post-rebase hook boom");
                });

        byte[] rebasedTip = rebaseCmd.call();
        assertNotNull(rebasedTip, "a failing post-rebase hook must not fail the overall rebase");

        assertFalse(new File(repo.getStoreDir(), "rebase-backup").exists());
        assertFalse(new File(repo.getStoreDir(), "journal").exists());
    }

    @Test
    public void rebaseThreeCommitChainPreservesLinearOrder(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        Files.writeString(new File(repoDir, "base.txt").toPath(), "base");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("t").setMessage("c0").call();

        Files.writeString(new File(repoDir, "main.txt").toPath(), "main");
        new AddCommand(repo).call();
        byte[] c1 = new CommitCommand(repo).setAuthor("t").setMessage("c1").call();

        Dirstate ds = repo.getDirstate();
        ds.setParents(c0, new byte[20]);
        repo.writeDirstate(ds);
        Files.writeString(new File(repoDir, "f1.txt").toPath(), "f1");
        new AddCommand(repo).call();
        byte[] c2 = new CommitCommand(repo).setAuthor("t").setMessage("c2 (chain start)").call();

        Files.writeString(new File(repoDir, "f2.txt").toPath(), "f2");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("t").setMessage("c3 (chain mid)").call();

        Files.writeString(new File(repoDir, "f3.txt").toPath(), "f3");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("t").setMessage("c4 (chain tip)").call();

        RebaseCommand rebaseCmd = new RebaseCommand(repo).setSource(c2).setTarget(c1);
        byte[] rebasedTip = rebaseCmd.call();
        assertNotNull(rebasedTip);

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog cl = new Revlog(clIdx, clDat);
        assertEquals(5, cl.getRevisionCount(), "c0,c1,and 3 rebased chain commits");

        int tipRev = NodeIdUtil.findRevisionByNodeId(cl, rebasedTip);
        assertTrue(new String(cl.getRevisionContent(tipRev), StandardCharsets.UTF_8).contains("chain tip"));

        int midRev = cl.getIndexRecord(tipRev).getParent1();
        assertTrue(new String(cl.getRevisionContent(midRev), StandardCharsets.UTF_8).contains("chain mid"));

        int startRev = cl.getIndexRecord(midRev).getParent1();
        assertTrue(new String(cl.getRevisionContent(startRev), StandardCharsets.UTF_8).contains("chain start"));

        int c1Rev = NodeIdUtil.findRevisionByNodeId(cl, c1);
        assertEquals(c1Rev, cl.getIndexRecord(startRev).getParent1(), "rebased chain root must attach to target");
    }

    @Test
    public void rebaseExecutableAndSymlinkFilesPreservedOnCheckout(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File exec = new File(repoDir, "run.sh");
        Files.writeString(exec.toPath(), "#!/bin/sh\necho hi\n");
        assertTrue(exec.setExecutable(true, false));

        File link = new File(repoDir, "link.txt");
        Files.createSymbolicLink(link.toPath(), Path.of("run.sh"));

        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("t").setMessage("c0 with exec+symlink").call();

        Files.writeString(new File(repoDir, "main.txt").toPath(), "main");
        new AddCommand(repo).call();
        byte[] c1 = new CommitCommand(repo).setAuthor("t").setMessage("c1").call();

        Dirstate ds = repo.getDirstate();
        ds.setParents(c0, new byte[20]);
        repo.writeDirstate(ds);
        Files.writeString(new File(repoDir, "feature.txt").toPath(), "feature");
        new AddCommand(repo).call();
        byte[] c2 = new CommitCommand(repo).setAuthor("t").setMessage("c2").call();

        RebaseCommand rebaseCmd = new RebaseCommand(repo).setSource(c2).setTarget(c1);
        byte[] rebasedTip = rebaseCmd.call();
        assertNotNull(rebasedTip);

        assertTrue(exec.exists());
        assertTrue(exec.canExecute(), "executable bit must survive rebase checkout");
        assertTrue(Files.isSymbolicLink(link.toPath()), "symlink flag must survive rebase checkout");
        assertEquals("run.sh", Files.readSymbolicLink(link.toPath()).toString());
    }

    @Test
    public void rebaseNonDefaultBranchNamePreserved(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        Files.writeString(new File(repoDir, "base.txt").toPath(), "base");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("t").setMessage("c0").call();

        Files.writeString(new File(repoDir, "main.txt").toPath(), "main");
        new AddCommand(repo).call();
        byte[] c1 = new CommitCommand(repo).setAuthor("t").setMessage("c1").call();

        Dirstate ds = repo.getDirstate();
        ds.setParents(c0, new byte[20]);
        repo.writeDirstate(ds);
        repo.setBranch("feature-branch");
        Files.writeString(new File(repoDir, "feature.txt").toPath(), "feature");
        new AddCommand(repo).call();
        byte[] c2 = new CommitCommand(repo).setAuthor("t").setMessage("c2 on feature-branch").call();

        RebaseCommand rebaseCmd = new RebaseCommand(repo).setSource(c2).setTarget(c1);
        byte[] rebasedTip = rebaseCmd.call();
        assertNotNull(rebasedTip);

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog cl = new Revlog(clIdx, clDat);
        int tipRev = NodeIdUtil.findRevisionByNodeId(cl, rebasedTip);
        String clText = new String(cl.getRevisionContent(tipRev), StandardCharsets.UTF_8);
        assertTrue(clText.contains("branch:feature-branch"), "rebased commit must retain its original branch name: " + clText);
    }

    @Test
    public void rebaseIndependentBranchWithMergeCommitRestoredWithBothParents(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);

        Files.writeString(new File(repoDir, "base.txt").toPath(), "base");
        hg.add().call();
        byte[] c0 = hg.commit().setAuthor("t").setMessage("c0").call();

        Files.writeString(new File(repoDir, "main.txt").toPath(), "main");
        hg.add().call();
        byte[] c1 = hg.commit().setAuthor("t").setMessage("c1 (target)").call();

        hg.update().setRevision("0").call();
        Files.writeString(new File(repoDir, "feature.txt").toPath(), "feature");
        hg.add().call();
        byte[] c2 = hg.commit().setAuthor("t").setMessage("c2 (source)").call();

        hg.update().setRevision("0").call();
        Files.writeString(new File(repoDir, "m1.txt").toPath(), "m1");
        hg.add().call();
        byte[] c3 = hg.commit().setAuthor("t").setMessage("c3 (indep A)").call();

        hg.update().setRevision("0").call();
        Files.writeString(new File(repoDir, "m2.txt").toPath(), "m2");
        hg.add().call();
        byte[] c4 = hg.commit().setAuthor("t").setMessage("c4 (indep B)").call();

        hg.update().setRevision(NodeIdUtil.toHex(c3)).call();
        MergeCommand.MergeResult mergeResult = new MergeCommand(repo).setNodeId(c4).call();
        assertFalse(mergeResult.isConflicted(), "disjoint files must merge cleanly");
        byte[] c5 = hg.commit().setAuthor("t").setMessage("c5 (indep merge)").call();

        RebaseCommand rebaseCmd = new RebaseCommand(repo).setSource(c2).setTarget(c1);
        byte[] rebasedTip = rebaseCmd.call();
        assertNotNull(rebasedTip);

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog cl = new Revlog(clIdx, clDat);
        assertEquals(6, cl.getRevisionCount(), "c0,c1,rebased c2',and restored c3/c4/c5 merge");

        int restoredMergeRev = NodeIdUtil.findRevisionByNodeId(cl, c5);
        assertTrue(restoredMergeRev != -1, "merge commit must be restored preserving its original node id");

        Revlog.IndexRecord mergeRec = cl.getIndexRecord(restoredMergeRev);
        assertTrue(mergeRec.getParent2() != -1, "restored independent merge commit must keep both parents");

        byte[] restoredP1 = cl.getIndexRecord(mergeRec.getParent1()).getNodeId();
        byte[] restoredP2 = cl.getIndexRecord(mergeRec.getParent2()).getNodeId();
        assertArrayEquals(c3, restoredP1);
        assertArrayEquals(c4, restoredP2);

        int rebasedRev = NodeIdUtil.findRevisionByNodeId(cl, rebasedTip);
        int c1Rev = NodeIdUtil.findRevisionByNodeId(cl, c1);
        assertEquals(c1Rev, cl.getIndexRecord(rebasedRev).getParent1());
    }

    @Test
    public void rebaseFailureMidCherryPickTriggersPhysicalRollback(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File keep = new File(repoDir, "keep.txt");
        Files.writeString(keep.toPath(), "keep-base-content");
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

        // Sabotage the working copy so that checking out the target's manifest (which includes
        // keep.txt) during cherry-pick fails with a genuine IOException, forcing a real
        // physical rollback of the already-stripped store files.
        Files.delete(keep.toPath());
        assertTrue(keep.mkdir());
        Files.writeString(new File(keep, "blocker.txt").toPath(), "blocks Files.delete on checkout");

        RebaseCommand rebaseCmd = new RebaseCommand(repo).setSource(c2).setTarget(c1);
        assertThrows(IOException.class, rebaseCmd::call);

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog cl = new Revlog(clIdx, clDat);
        assertEquals(3, cl.getRevisionCount(), "physical rollback must restore the pre-rebase changelog");
        assertTrue(NodeIdUtil.findRevisionByNodeId(cl, c0) != -1);
        assertTrue(NodeIdUtil.findRevisionByNodeId(cl, c1) != -1);
        assertTrue(NodeIdUtil.findRevisionByNodeId(cl, c2) != -1, "original source commit must be restored on rollback");

        assertFalse(new File(repo.getStoreDir(), "rebase-backup").exists(), "backup dir must be cleaned after rollback");
        assertFalse(new File(repo.getStoreDir(), "journal").exists(), "journal must be cleaned after rollback");
    }
}
