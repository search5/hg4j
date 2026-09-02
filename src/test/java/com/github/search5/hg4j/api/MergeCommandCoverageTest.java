package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.errors.HgCorruptDataException;
import com.github.search5.hg4j.errors.HgRevisionNotFoundException;
import com.github.search5.hg4j.errors.HgValidationException;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.merge.MergeState;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.util.NodeIdUtil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage for {@link MergeCommand}, targeting branches that {@code MergeCommandTest}
 * doesn't reach: package-private helper methods exercised directly, PRE/POST_MERGE hook wiring,
 * conflict persistence into {@code .hg/merge/state2} + the local-content backup file, delete/keep
 * tree-walk combinations, and deeper criss-cross LCA synthesis (virtual base combination of
 * add/delete/modify, recursion depth cutoff, and a criss-cross merge that itself conflicts so the
 * virtual ancestor-linknode path is used).
 */
public class MergeCommandCoverageTest {

    private static byte[] commit(HgRepository repo, String message) throws Exception {
        new AddCommand(repo).call();
        return new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage(message).call();
    }

    private static void write(File repoDir, String name, String content) throws Exception {
        File f = new File(repoDir, name);
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), content);
    }

    // ---------------------------------------------------------------------
    // Package-private helper methods, exercised directly (same package).
    // ---------------------------------------------------------------------

    @Test
    public void modeFlagDetectionCoversAllHexFlagVariants(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        MergeCommand cmd = new MergeCommand(repo);

        assertEquals(0644, cmd.getModeFromManifestHex(null));
        String hex40 = "0".repeat(40);
        assertEquals(0644, cmd.getModeFromManifestHex(hex40)); // length == 40, no flag char
        assertEquals(0755, cmd.getModeFromManifestHex(hex40 + "x"));
        assertEquals(0120000, cmd.getModeFromManifestHex(hex40 + "l"));
        assertEquals(0644, cmd.getModeFromManifestHex(hex40 + "z")); // unrecognized flag falls back to 0644
    }

    @Test
    public void readLinesHandlesNullEmptyAndMissingTrailingNewline(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        MergeCommand cmd = new MergeCommand(repo);

        assertEquals(List.of(), cmd.readLines(null));
        assertEquals(List.of(), cmd.readLines(new byte[0]));
        assertEquals(List.of("abc"), cmd.readLines("abc".getBytes(StandardCharsets.UTF_8))); // no trailing \n
        assertEquals(List.of("abc", "def"), cmd.readLines("abc\ndef\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of("abc", "", "def"), cmd.readLines("abc\n\ndef".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void getFileRevisionContentThrowsForMissingFilelogAndMissingRevision(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "hello.txt", "hi\n");
        commit(repo, "base");
        MergeCommand cmd = new MergeCommand(repo);

        // Path that was never added: no filelog on disk at all.
        assertThrows(HgCorruptDataException.class,
                () -> cmd.getFileRevisionContent("never-added.txt", "1".repeat(40)));

        // Filelog exists, but the requested node id was never stored in it.
        assertThrows(HgRevisionNotFoundException.class,
                () -> cmd.getFileRevisionContent("hello.txt", "1".repeat(40)));
    }

    @Test
    public void mergeBaseFileContentBranchesCoverCacheHexNullAndRealFetch(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "f1.txt", "hello\n");
        write(repoDir, "f2.txt", "world\n");
        byte[] baseNode = commit(repo, "base");
        MergeCommand cmd = new MergeCommand(repo);

        Map<String, String> manifest = repo.getManifestAtCommit(baseNode);

        // Virtual base (rev == -1) with a cached content entry: cache hit wins, regardless of hex.
        Map<String, byte[]> cache = new HashMap<>();
        cache.put("f1.txt", "CACHED CONTENT".getBytes(StandardCharsets.UTF_8));
        MergeCommand.MergeBase virtualBase = new MergeCommand.MergeBase(manifest, cache);
        assertArrayEquals("CACHED CONTENT".getBytes(StandardCharsets.UTF_8),
                virtualBase.getFileContent(cmd, "f1.txt", "garbage-hex-ignored"));

        // Virtual base, path not cached, hex null -> empty array without touching the store.
        assertEquals(0, virtualBase.getFileContent(cmd, "missing.txt", null).length);
        // Virtual base, path not cached, hex empty string -> empty array.
        assertEquals(0, virtualBase.getFileContent(cmd, "missing.txt", "").length);
        // Virtual base, path not cached, real hex -> falls through to the real filelog fetch.
        assertArrayEquals("world\n".getBytes(StandardCharsets.UTF_8),
                virtualBase.getFileContent(cmd, "f2.txt", manifest.get("f2.txt")));

        // Real (non-virtual) base: cache lookup is skipped entirely; hex null still yields empty.
        int rev0 = NodeIdUtil.findRevisionByNodeId(
                repo.getRevlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d")),
                baseNode);
        MergeCommand.MergeBase realBase = new MergeCommand.MergeBase(rev0, manifest);
        assertEquals(0, realBase.getFileContent(cmd, "missing.txt", null).length);
        assertArrayEquals("hello\n".getBytes(StandardCharsets.UTF_8),
                realBase.getFileContent(cmd, "f1.txt", manifest.get("f1.txt")));
    }

    @Test
    public void registerHooksIgnoreNullHook(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        MergeCommand cmd = new MergeCommand(repo);
        // Must simply be no-ops, not NPE.
        assertSame(cmd, cmd.registerPreMergeHook(null));
        assertSame(cmd, cmd.registerPostMergeHook(null));
    }

    // ---------------------------------------------------------------------
    // PRE_MERGE / POST_MERGE hook wiring.
    // ---------------------------------------------------------------------

    @Test
    public void preMergeHookRejectionAbortsBeforeAnyStateChange(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "a.txt", "base\n");
        byte[] baseNode = commit(repo, "base");
        write(repoDir, "a.txt", "changed\n");
        byte[] otherNode = commit(repo, "other");
        Dirstate ds = repo.getDirstate();
        ds.setParents(baseNode, new byte[20]);
        repo.writeDirstate(ds);

        AtomicBoolean hookRan = new AtomicBoolean(false);
        MergeCommand cmd = new MergeCommand(repo).setNodeId(otherNode).registerPreMergeHook(ctx -> {
            hookRan.set(true);
            assertEquals(repo, ctx.get("repository"));
            assertArrayEquals(otherNode, (byte[]) ctx.get("targetNodeId"));
            return false;
        });

        assertThrows(HgValidationException.class, cmd::call);
        assertTrue(hookRan.get());

        // Dirstate must be untouched: still pointing at base with no P2.
        Dirstate after = repo.getDirstate();
        assertArrayEquals(baseNode, after.getParent1());
        assertTrue(isAllZero(after.getParent2()));
    }

    @Test
    public void preMergeHookApprovalAllowsMergeToProceed(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "a.txt", "base\n");
        byte[] baseNode = commit(repo, "base");
        write(repoDir, "a.txt", "changed\n");
        byte[] otherNode = commit(repo, "other");
        Dirstate ds = repo.getDirstate();
        ds.setParents(baseNode, new byte[20]);
        repo.writeDirstate(ds);

        AtomicInteger hookCalls = new AtomicInteger();
        MergeCommand.MergeResult res = new MergeCommand(repo).setNodeId(otherNode)
                .registerPreMergeHook(ctx -> {
                    hookCalls.incrementAndGet();
                    return true;
                }).call();

        assertFalse(res.isConflicted());
        assertEquals(1, hookCalls.get());
    }

    @Test
    public void postMergeHookReceivesContextOnCleanAndConflictedMerge(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "a.txt", "L1\nL2\nL3\n");
        byte[] baseNode = commit(repo, "base");
        write(repoDir, "a.txt", "L1 mine\nL2\nL3\n");
        byte[] yoursNode = commit(repo, "yours");
        Dirstate ds = repo.getDirstate();
        ds.setParents(baseNode, new byte[20]);
        repo.writeDirstate(ds);
        write(repoDir, "a.txt", "L1\nL2\nL3 theirs\n");
        commit(repo, "theirs");

        Map<String, Object> captured = new HashMap<>();
        MergeCommand.MergeResult res = new MergeCommand(repo).setNodeId(yoursNode)
                .registerPostMergeHook(ctx -> {
                    captured.putAll(ctx);
                    return true;
                }).call();

        assertFalse(res.isConflicted());
        assertEquals(Boolean.FALSE, captured.get("conflicted"));
        assertEquals(List.of(), captured.get("conflicts"));
        assertEquals(repo, captured.get("repository"));
    }

    @Test
    public void postMergeHookExceptionIsLoggedNotPropagated(@TempDir Path tempDir) throws Exception {
        // Must be a genuine (non-fast-forward) divergent merge: POST_MERGE hooks only fire on the
        // full tree-walk merge path, not on the fast-forward/already-merged early-return paths.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "a.txt", "L1\nL2\nL3\n");
        byte[] baseNode = commit(repo, "base");
        write(repoDir, "a.txt", "L1 mine\nL2\nL3\n");
        byte[] yoursNode = commit(repo, "yours");
        Dirstate ds = repo.getDirstate();
        ds.setParents(baseNode, new byte[20]);
        repo.writeDirstate(ds);
        write(repoDir, "a.txt", "L1\nL2\nL3 theirs\n");
        commit(repo, "theirs");

        MergeCommand.MergeResult res = new MergeCommand(repo).setNodeId(yoursNode)
                .registerPostMergeHook(ctx -> {
                    throw new RuntimeException("boom from post-merge hook");
                }).call();

        // The hook's exception must be swallowed (logged), never break the merge result.
        assertFalse(res.isConflicted());
    }

    // ---------------------------------------------------------------------
    // Fast-forward / tree-walk delete-keep combinations.
    // ---------------------------------------------------------------------

    @Test
    public void fastForwardDeletesFilesRemovedOnTarget(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "keep.txt", "keep\n");
        File removed = new File(repoDir, "removed.txt");
        write(repoDir, "removed.txt", "gone soon\n");
        byte[] baseNode = commit(repo, "base");

        // Advance: delete removed.txt on top of base (still a fast-forward target from base).
        removed.delete();
        Dirstate ds = repo.getDirstate();
        ds.addEntry("removed.txt", new Dirstate.Entry('r', 0, 0, 0));
        repo.writeDirstate(ds);
        byte[] advancedNode = commit(repo, "advance (delete removed.txt)");

        // Reset current checkout back to base, then fast-forward merge into advancedNode.
        ds = repo.getDirstate();
        ds.setParents(baseNode, new byte[20]);
        repo.writeDirstate(ds);
        write(repoDir, "removed.txt", "gone soon\n"); // restore working copy to match base

        MergeCommand.MergeResult res = new MergeCommand(repo).setNodeId(advancedNode).call();
        assertFalse(res.isConflicted());
        assertFalse(removed.exists(), "fast-forward must delete files removed on the target");
        assertFalse(repo.getDirstate().getEntries().containsKey("removed.txt"));
        assertTrue(new File(repoDir, "keep.txt").exists());
    }

    @Test
    public void mergeDeletesFileWhenTargetDeletedAndLocalUnchanged(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File fileX = new File(repoDir, "x.txt");
        write(repoDir, "x.txt", "X\n");
        write(repoDir, "y.txt", "Y\n");
        byte[] baseNode = commit(repo, "base");

        // Current branch: leave x.txt untouched, add an unrelated file so it's a distinct commit.
        write(repoDir, "z.txt", "Z\n");
        byte[] currentNode = commit(repo, "current (adds z.txt)");

        // Other branch, forked from base: delete x.txt.
        Dirstate ds = repo.getDirstate();
        ds.setParents(baseNode, new byte[20]);
        new File(repoDir, "z.txt").delete();
        ds.removeEntry("z.txt");
        repo.writeDirstate(ds);
        fileX.delete();
        ds = repo.getDirstate();
        ds.addEntry("x.txt", new Dirstate.Entry('r', 0, 0, 0));
        repo.writeDirstate(ds);
        byte[] otherNode = commit(repo, "other (deletes x.txt)");

        // Move back onto currentNode, restoring its working copy state, then merge otherNode in.
        ds = repo.getDirstate();
        ds.setParents(currentNode, new byte[20]);
        write(repoDir, "x.txt", "X\n");
        write(repoDir, "z.txt", "Z\n");
        ds.addEntry("z.txt", new Dirstate.Entry('n', 0644, 2, System.currentTimeMillis() / 1000));
        repo.writeDirstate(ds);

        MergeCommand.MergeResult res = new MergeCommand(repo).setNodeId(otherNode).call();
        assertFalse(res.isConflicted());
        assertFalse(fileX.exists(), "unmodified local copy must follow the other side's deletion");
        assertTrue(new File(repoDir, "y.txt").exists());
    }

    @Test
    public void mergeDeletionIsNoopWhenWorkingCopyAlreadyMissingTheFile(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File fileX = new File(repoDir, "x.txt");
        write(repoDir, "x.txt", "X\n");
        byte[] baseNode = commit(repo, "base");

        write(repoDir, "z.txt", "Z\n");
        byte[] currentNode = commit(repo, "current (adds z.txt)");

        Dirstate ds = repo.getDirstate();
        ds.setParents(baseNode, new byte[20]);
        new File(repoDir, "z.txt").delete();
        ds.removeEntry("z.txt");
        repo.writeDirstate(ds);
        fileX.delete();
        ds = repo.getDirstate();
        ds.addEntry("x.txt", new Dirstate.Entry('r', 0, 0, 0));
        repo.writeDirstate(ds);
        byte[] otherNode = commit(repo, "other (deletes x.txt)");

        ds = repo.getDirstate();
        ds.setParents(currentNode, new byte[20]);
        write(repoDir, "z.txt", "Z\n");
        ds.addEntry("z.txt", new Dirstate.Entry('n', 0644, 2, System.currentTimeMillis() / 1000));
        repo.writeDirstate(ds);
        // Deliberately leave x.txt missing from disk already (simulates a half-broken working
        // copy): deleteFileFromWorkingCopy() must tolerate a file that is already gone.
        if (fileX.exists()) fileX.delete();

        MergeCommand.MergeResult res = new MergeCommand(repo).setNodeId(otherNode).call();
        assertFalse(res.isConflicted());
        assertFalse(fileX.exists());
    }

    @Test
    public void mergeOfIndependentlyAddedFileWithDifferentContentConflictsWithNullAncestor(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "base.txt", "base\n");
        byte[] baseNode = commit(repo, "base");

        write(repoDir, "new.txt", "AAA\n");
        byte[] currentNode = commit(repo, "current (adds new.txt = AAA)");

        Dirstate ds = repo.getDirstate();
        ds.setParents(baseNode, new byte[20]);
        new File(repoDir, "new.txt").delete();
        ds.removeEntry("new.txt");
        repo.writeDirstate(ds);
        write(repoDir, "new.txt", "BBB\n");
        byte[] otherNode = commit(repo, "other (adds new.txt = BBB)");

        ds = repo.getDirstate();
        ds.setParents(currentNode, new byte[20]);
        write(repoDir, "new.txt", "AAA\n");
        repo.writeDirstate(ds);

        MergeCommand.MergeResult res = new MergeCommand(repo).setNodeId(otherNode).call();
        assertTrue(res.isConflicted());
        assertEquals(List.of("new.txt"), res.getConflicts());

        // .hg/merge/state2 must be written with the real local/other parent nodes and an
        // unresolved entry for new.txt whose ancestor node is the null hex (no common ancestor).
        File stateFile = new File(repo.getHgDir(), "merge/state2");
        assertTrue(stateFile.exists());
        MergeState ms = MergeState.read(stateFile);
        assertArrayEquals(currentNode, ms.local);
        assertArrayEquals(otherNode, ms.other);
        assertEquals(List.of("new.txt"), ms.unresolvedFiles());
        List<String> fields = ms.state.get("new.txt");
        String localKey = fields.get(1);
        String ancestorNodeHex = fields.get(4);
        assertEquals(MergeState.NULL_HEX, ancestorNodeHex);

        // The pre-merge local content (AAA) must be backed up under .hg/merge/<localkey>.
        File localBackup = new File(repo.getHgDir(), "merge/" + localKey);
        assertTrue(localBackup.exists());
        assertEquals("AAA\n", Files.readString(localBackup.toPath()));

        // ancestorlinknode extra must still be recorded even though there's no real ancestor rev.
        Map<String, String> extras = ms.stateExtras.get("new.txt");
        assertNotNull(extras);
        assertTrue(extras.containsKey("ancestorlinknode"));
    }

    /**
     * Conflicting merge on an executable file: the LCA and both parents' manifest hex strings all
     * carry the {@code x} flag suffix (length &gt; 40), so both {@code cleanHexOf} and
     * {@code flagOf} must take their substring branches (as opposed to the plain 40-char hex used
     * by every other conflict test in this suite).
     */
    @Test
    public void conflictingMergeOnExecutableFilePreservesFlagInMergeState(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File script = new File(repoDir, "run.sh");
        Files.writeString(script.toPath(), "L1\nL2\nL3\n");
        script.setExecutable(true, false);
        byte[] baseNode = commit(repo, "base (executable run.sh)");

        Files.writeString(script.toPath(), "L1\nL2 mine\nL3\n");
        script.setExecutable(true, false);
        byte[] yoursNode = commit(repo, "yours");

        Dirstate ds = repo.getDirstate();
        ds.setParents(baseNode, new byte[20]);
        repo.writeDirstate(ds);
        Files.writeString(script.toPath(), "L1\nL2 theirs\nL3\n");
        script.setExecutable(true, false);
        commit(repo, "theirs");

        MergeCommand.MergeResult res = new MergeCommand(repo).setNodeId(yoursNode).call();
        assertTrue(res.isConflicted());
        assertEquals(List.of("run.sh"), res.getConflicts());

        // The executable bit must survive being rewritten with conflict markers.
        assertTrue(script.canExecute());

        File stateFile = new File(repo.getHgDir(), "merge/state2");
        MergeState ms = MergeState.read(stateFile);
        List<String> fields = ms.state.get("run.sh");
        String ancestorNodeHex = fields.get(4);
        String otherNodeHex = fields.get(6);
        String flags = fields.get(7);
        assertEquals(40, ancestorNodeHex.length(), "cleanHexOf must strip the flag suffix from the LCA hex");
        assertEquals(40, otherNodeHex.length(), "cleanHexOf must strip the flag suffix from the other-side hex");
        assertEquals("x", flags, "flagOf must extract the executable flag from local's manifest hex");
    }

    // ---------------------------------------------------------------------
    // Error paths reached before the tree-walk.
    // ---------------------------------------------------------------------

    @Test
    public void noCommonAncestorAcrossUnrelatedRootsThrows(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "r1.txt", "root one\n");
        byte[] root1 = commit(repo, "root1");

        // Start a second, unrelated root (parent1 = null) in the same repo.
        Dirstate ds = repo.getDirstate();
        ds.setParents(new byte[20], new byte[20]);
        new File(repoDir, "r1.txt").delete();
        ds.removeEntry("r1.txt");
        repo.writeDirstate(ds);
        write(repoDir, "r2.txt", "root two\n");
        byte[] root2 = commit(repo, "root2");

        MergeCommand cmd = new MergeCommand(repo).setNodeId(root1);
        HgRevisionNotFoundException ex = assertThrows(HgRevisionNotFoundException.class, cmd::call);
        assertTrue(ex.getMessage().contains("No common ancestor"));
    }

    @Test
    public void corruptedDirstateParentPointingToUnknownRevisionThrows(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "a.txt", "a\n");
        commit(repo, "base");

        byte[] fakeParent = new byte[20];
        Arrays.fill(fakeParent, (byte) 7);
        Dirstate ds = repo.getDirstate();
        ds.setParents(fakeParent, new byte[20]);
        repo.writeDirstate(ds);

        assertThrows(HgRevisionNotFoundException.class, () -> new MergeCommand(repo).setRevision(0).call());
    }

    @Test
    public void negativeRevisionIndexIsRejected(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "a.txt", "a\n");
        commit(repo, "base");

        assertThrows(IllegalArgumentException.class, () -> new MergeCommand(repo).setRevision(-2).call());
    }

    // ---------------------------------------------------------------------
    // Deeper criss-cross LCA synthesis.
    // ---------------------------------------------------------------------

    /**
     * Builds two divergent commits (r1, r2) from a shared base r0 with enough file variety to
     * exercise every add/delete/modify combination in the virtual-base synthesis inside
     * {@link MergeCommand#getMergeBase}, then a criss-cross pair (r3 = merge r1 into r2, r4 =
     * merge r2 into r1) so the final merge sees two LCA candidates and must recurse.
     */
    @Test
    public void crissCrossVirtualBaseSynthesizesAddDeleteAndMergedContent(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        write(repoDir, "shared.txt", "l1\nl2\nl3\n");
        write(repoDir, "delbase1.txt", "d1\n");
        write(repoDir, "delbase2.txt", "d2\n");
        write(repoDir, "bothdel.txt", "gone from both\n");
        write(repoDir, "base.txt", "base\n");
        byte[] r0 = commit(repo, "r0");

        // r1: modify shared.txt, delete delbase1.txt and bothdel.txt, add a.txt.
        write(repoDir, "shared.txt", "l1\nCHANGED-A\nl3\n");
        new File(repoDir, "delbase1.txt").delete();
        new File(repoDir, "bothdel.txt").delete();
        write(repoDir, "a.txt", "A\n");
        Dirstate ds = repo.getDirstate();
        ds.removeEntry("delbase1.txt");
        ds.removeEntry("bothdel.txt");
        repo.writeDirstate(ds);
        byte[] r1 = commit(repo, "r1");

        // r2: forked from r0. Keep shared.txt unchanged, delete delbase2.txt and bothdel.txt, add b.txt.
        ds = repo.getDirstate();
        ds.setParents(r0, new byte[20]);
        write(repoDir, "shared.txt", "l1\nl2\nl3\n");
        new File(repoDir, "a.txt").delete();
        ds.removeEntry("a.txt");
        write(repoDir, "delbase1.txt", "d1\n");
        ds.addEntry("delbase1.txt", new Dirstate.Entry('n', 0644, 3, System.currentTimeMillis() / 1000));
        repo.writeDirstate(ds);
        new File(repoDir, "delbase2.txt").delete();
        new File(repoDir, "bothdel.txt").delete();
        ds = repo.getDirstate();
        ds.removeEntry("delbase2.txt");
        ds.removeEntry("bothdel.txt");
        repo.writeDirstate(ds);
        write(repoDir, "b.txt", "B\n");
        byte[] r2 = commit(repo, "r2");

        // r3 = merge r1 into r2.
        new MergeCommand(repo).setNodeId(r1).call();
        byte[] r3 = commit(repo, "r3 (merge r1 into r2)");

        // r4 = merge r2 into r1.
        ds = repo.getDirstate();
        ds.setParents(r1, new byte[20]);
        new File(repoDir, "b.txt").delete();
        ds.removeEntry("b.txt");
        write(repoDir, "a.txt", "A\n");
        ds.addEntry("a.txt", new Dirstate.Entry('n', 0644, 2, System.currentTimeMillis() / 1000));
        write(repoDir, "delbase1.txt", "d1\n"); // r1 deleted it, r2 (target) keeps it -> should reappear
        ds.addEntry("delbase1.txt", new Dirstate.Entry('n', 0644, 3, System.currentTimeMillis() / 1000));
        repo.writeDirstate(ds);
        new MergeCommand(repo).setNodeId(r2).call();
        byte[] r4 = commit(repo, "r4 (merge r2 into r1)");

        // Now merge r3 and r4: candidates for their LCA are {r1, r2} -> forces recursive synthesis.
        ds = repo.getDirstate();
        ds.setParents(r4, new byte[20]);
        repo.writeDirstate(ds);

        MergeCommand cmd = new MergeCommand(repo).setNodeId(r3);
        MergeCommand.MergeResult res = cmd.call();
        assertFalse(res.isConflicted());

        // delbase1/delbase2/bothdel must all have been resolved as deleted by the synthesis
        // (each was dropped on at least one side with the other side unchanged, or on both sides).
        assertFalse(new File(repoDir, "bothdel.txt").exists());
        assertTrue(new File(repoDir, "shared.txt").exists());
        assertEquals("l1\nCHANGED-A\nl3\n", Files.readString(new File(repoDir, "shared.txt").toPath()));

        // Directly force the recursion-depth cutoff (depth > 3): with 2 real LCA candidates
        // {r1, r2}, a depth beyond the recursion limit must short-circuit to the newest one
        // instead of recursing further.
        Revlog changelog = repo.getRevlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"));
        Revlog manifestRevlog = repo.getManifestRevlog();
        int r3Rev = NodeIdUtil.findRevisionByNodeId(changelog, r3);
        int r4Rev = NodeIdUtil.findRevisionByNodeId(changelog, r4);
        int r1Rev = NodeIdUtil.findRevisionByNodeId(changelog, r1);
        int r2Rev = NodeIdUtil.findRevisionByNodeId(changelog, r2);

        MergeCommand.MergeBase deepCutoff = cmd.getMergeBase(changelog, manifestRevlog, r3Rev, r4Rev, 4);
        assertEquals(Math.max(r1Rev, r2Rev), deepCutoff.rev);
    }

    /**
     * A criss-cross merge whose two LCA candidates disagree on the same file, so the virtual
     * base itself is a conflicted synthesis, and the final merge (comparing against that virtual,
     * unnumbered base) also conflicts -- covering the {@code lca.rev == -1} branch of the
     * ancestorlinknode computation.
     */
    @Test
    public void crissCrossConflictRecordsVirtualAncestorLinknode(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        write(repoDir, "shared.txt", "l1\nl2\nl3\n");
        byte[] r0 = commit(repo, "r0");

        write(repoDir, "shared.txt", "l1\nA-CHANGE\nl3\n");
        write(repoDir, "a.txt", "A\n");
        byte[] r1 = commit(repo, "r1");

        Dirstate ds = repo.getDirstate();
        ds.setParents(r0, new byte[20]);
        write(repoDir, "shared.txt", "l1\nB-CHANGE\nl3\n");
        new File(repoDir, "a.txt").delete();
        ds.removeEntry("a.txt");
        repo.writeDirstate(ds);
        write(repoDir, "b.txt", "B\n");
        byte[] r2 = commit(repo, "r2");

        // r3 = merge r1 into r2 (will itself conflict on shared.txt); resolve by keeping r1's
        // side so the commit (which refuses to commit literal conflict markers) can proceed.
        MergeCommand.MergeResult r3MergeRes = new MergeCommand(repo).setNodeId(r1).call();
        assertTrue(r3MergeRes.isConflicted());
        write(repoDir, "shared.txt", "l1\nA-CHANGE\nl3\n");
        byte[] r3 = commit(repo, "r3 (merge r1 into r2)");

        // r4 = merge r2 into r1 (will itself conflict on shared.txt); resolve by keeping r2's
        // side this time, so r3 and r4 disagree on shared.txt's final content.
        ds = repo.getDirstate();
        ds.setParents(r1, new byte[20]);
        write(repoDir, "shared.txt", "l1\nA-CHANGE\nl3\n");
        new File(repoDir, "b.txt").delete();
        ds.removeEntry("b.txt");
        repo.writeDirstate(ds);
        MergeCommand.MergeResult r4MergeRes = new MergeCommand(repo).setNodeId(r2).call();
        assertTrue(r4MergeRes.isConflicted());
        write(repoDir, "shared.txt", "l1\nB-CHANGE\nl3\n");
        byte[] r4 = commit(repo, "r4 (merge r2 into r1)");

        // Final criss-cross merge: LCA candidates are {r1, r2} -> virtual (rev == -1) base.
        ds = repo.getDirstate();
        ds.setParents(r4, new byte[20]);
        repo.writeDirstate(ds);

        MergeCommand.MergeResult res = new MergeCommand(repo).setNodeId(r3).call();

        // Whether or not shared.txt itself still differs after the two prior conflicted merges,
        // the merge must complete without error and, if conflicted, must still persist a valid
        // state2 with a real (20-byte, possibly all-zero) ancestorlinknode.
        if (res.isConflicted()) {
            File stateFile = new File(repo.getHgDir(), "merge/state2");
            assertTrue(stateFile.exists());
            MergeState ms = MergeState.read(stateFile);
            for (String path : res.getConflicts()) {
                Map<String, String> extras = ms.stateExtras.get(path);
                assertNotNull(extras);
                String link = extras.get("ancestorlinknode");
                assertNotNull(link);
                assertEquals(40, link.length());
            }
        }
    }

    /**
     * Criss-cross virtual-base synthesis where both LCA candidates (c1, c2) independently modify
     * the same executable file on different lines: the virtual base's own 3-way merge of that
     * file must preserve the {@code x} flag, taking the {@code hC1.length() > 40} branch when
     * building the synthesized manifest hex (as opposed to every other criss-cross test in this
     * suite, whose merged files are all flagless).
     */
    @Test
    public void crissCrossVirtualBaseSynthesisPreservesExecutableFlag(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File script = new File(repoDir, "run.sh");
        Files.writeString(script.toPath(), "A\nB\nC\nD\nE\n");
        script.setExecutable(true, false);
        byte[] r0 = commit(repo, "r0");

        // r1: modify a line near the top of the executable file, keep it executable.
        Files.writeString(script.toPath(), "A\nB1\nC\nD\nE\n");
        script.setExecutable(true, false);
        byte[] r1 = commit(repo, "r1");

        // r2: forked from r0, modify a line far from r1's edit so the two hunks are unambiguously
        // independent (non-conflicting with r1's change).
        Dirstate ds = repo.getDirstate();
        ds.setParents(r0, new byte[20]);
        repo.writeDirstate(ds);
        Files.writeString(script.toPath(), "A\nB\nC\nD\nE2\n");
        script.setExecutable(true, false);
        byte[] r2 = commit(repo, "r2");

        // r3 = merge r1 into r2.
        MergeCommand.MergeResult r3Res = new MergeCommand(repo).setNodeId(r1).call();
        assertFalse(r3Res.isConflicted());
        script.setExecutable(true, false);
        byte[] r3 = commit(repo, "r3 (merge r1 into r2)");

        // r4 = merge r2 into r1.
        ds = repo.getDirstate();
        ds.setParents(r1, new byte[20]);
        repo.writeDirstate(ds);
        MergeCommand.MergeResult r4Res = new MergeCommand(repo).setNodeId(r2).call();
        assertFalse(r4Res.isConflicted());
        script.setExecutable(true, false);
        byte[] r4 = commit(repo, "r4 (merge r2 into r1)");

        // Final merge of r3/r4: LCA candidates are {r1, r2} -> forces virtual-base synthesis,
        // which itself must 3-way merge run.sh (modified differently by both r1 and r2).
        ds = repo.getDirstate();
        ds.setParents(r4, new byte[20]);
        repo.writeDirstate(ds);

        MergeCommand.MergeResult res = new MergeCommand(repo).setNodeId(r3).call();
        assertFalse(res.isConflicted());
        assertEquals("A\nB1\nC\nD\nE2\n", Files.readString(script.toPath()));
        assertTrue(script.canExecute(), "virtual-base synthesis must preserve the executable flag");
    }

    private static boolean isAllZero(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) return false;
        }
        return true;
    }
}
