package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage-focused TDD tests for {@link GraftCommand}.
 *
 * <p>Behavioral expectations below (message left untouched by default, date/user copied
 * from the source changeset) were verified against real {@code hg graft} (Mercurial 7.2,
 * {@code /usr/bin/hg}) on 2026-09-01: a plain {@code hg graft REV} (no {@code --log} flag)
 * leaves {@code hg log -r tip}'s description byte-for-byte equal to the source's message and
 * copies the source's exact user/date onto the new commit (see {@code hg help graft}: "By
 * default, graft will copy user, date, and description from the source changesets" and
 * "If --log is specified, log messages will have a comment appended of the form: (grafted
 * from CHANGESETHASH)").</p>
 */
public class GraftCommandCoverageTest {

    @TempDir
    File tempDir;

    private HgRepository initRepo(String name) throws IOException {
        File repoDir = new File(tempDir, name);
        return Hg.init().setDirectory(repoDir).call();
    }

    private HgCommit findCommit(Hg hg, String hex) throws IOException {
        for (HgCommit c : hg.log().call()) {
            if (NodeIdUtil.toHex(c.getNodeId().getBytes()).equals(hex)) {
                return c;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Input validation
    // ------------------------------------------------------------------

    @Test
    public void testCallWithoutSourceRevisionThrowsIllegalArgumentException() throws Exception {
        HgRepository repo = initRepo("no_source_repo");
        GraftCommand graft = new GraftCommand(repo);
        assertThrows(IllegalArgumentException.class, graft::call);
    }

    @Test
    public void testCallWithEmptySourceRevisionThrowsIllegalArgumentException() throws Exception {
        HgRepository repo = initRepo("empty_source_repo");
        GraftCommand graft = new GraftCommand(repo).setSource("");
        assertThrows(IllegalArgumentException.class, graft::call);
    }

    @Test
    public void testUnresolvableSourceRevisionThrowsIOException() throws Exception {
        HgRepository repo = initRepo("unresolvable_repo");
        Hg hg = Hg.wrap(repo);
        Files.writeString(new File(repo.getDirectory(), "a.txt").toPath(), "content");
        hg.add().addFile("a.txt").call();
        hg.commit().setAuthor("tester").setMessage("initial").call();

        GraftCommand graft = new GraftCommand(repo).setSource("deadbeefdeadbeefdeadbeefdeadbeefdeadbeef");
        IOException ex = assertThrows(IOException.class, graft::call);
        assertTrue(ex.getMessage().contains("Graft source revision not found"), ex.getMessage());
        hg.close();
    }

    // ------------------------------------------------------------------
    // Happy path: user/date/message preservation, hooks
    // ------------------------------------------------------------------

    @Test
    public void testGraftPreservesAuthorDateAndMessage() throws Exception {
        HgRepository repo = initRepo("graft_happy_path_repo");
        Hg hg = Hg.wrap(repo);

        Files.writeString(new File(repo.getDirectory(), "a.txt").toPath(), "hello\n");
        hg.add().addFile("a.txt").call();
        byte[] commitA = hg.commit().setAuthor("Alice <alice@example.com>")
                .setDate(1577836800L, 0)
                .setMessage("add a").call();

        Files.writeString(new File(repo.getDirectory(), "b.txt").toPath(), "world\n");
        hg.add().addFile("b.txt").call();
        long bobSecs = 1623758400L;
        int bobOffset = -3600;
        byte[] commitB = hg.commit().setAuthor("Bob <bob@example.com>")
                .setDate(bobSecs, bobOffset)
                .setMessage("add b").call();

        // Branch off commit A so grafting B onto it is a real cherry-pick, not a no-op.
        hg.update().setRevision(NodeIdUtil.toHex(commitA)).setForce(true).call();
        Files.writeString(new File(repo.getDirectory(), "c.txt").toPath(), "other\n");
        hg.add().addFile("c.txt").call();
        hg.commit().setAuthor("Carl <carl@example.com>")
                .setDate(1640995200L, 0)
                .setMessage("add c").call();

        AtomicInteger hookCalls = new AtomicInteger(0);
        AtomicReference<String> hookSourceRevision = new AtomicReference<>();
        AtomicReference<String> hookGraftedNode = new AtomicReference<>();

        GraftCommand graft = new GraftCommand(repo).setSource(NodeIdUtil.toHex(commitB));
        graft.registerPostGraftHook(ctx -> {
            hookCalls.incrementAndGet();
            hookSourceRevision.set((String) ctx.get("sourceRevision"));
            hookGraftedNode.set((String) ctx.get("graftedNode"));
            assertNotNull(ctx.get("repository"));
            return true;
        });

        String graftedHex = graft.call();
        assertNotNull(graftedHex);

        // Working copy now has b.txt's content grafted in.
        assertEquals("world\n", Files.readString(new File(repo.getDirectory(), "b.txt").toPath()));

        HgCommit grafted = findCommit(hg, graftedHex);
        assertNotNull(grafted, "grafted commit must be visible in log");
        assertEquals("Bob <bob@example.com>", grafted.getAuthor());
        assertEquals(bobSecs, grafted.getTimestamp());
        assertEquals(bobOffset, grafted.getTimezoneOffset());
        // Real hg (no --log) leaves the description untouched -- no "(grafted from ...)" suffix.
        assertEquals("add b", grafted.getMessage());

        // Since 2026-09-05: a plain graft must NOT write any obsolescence marker at all (verified
        // live against real `hg graft` 7.2 -- see GraftCommand's own class javadoc) -- the source
        // commit must stay fully visible, never spuriously hidden by a marker real hg itself
        // would never have written for a plain, non---log graft.
        File obsstore = new File(repo.getStoreDir(), "obsstore");
        assertFalse(obsstore.exists() && obsstore.length() > 0,
                "a plain graft must not write an obsolescence marker (real `hg graft` never does)");

        // POST_GRAFT hook fired with the expected context.
        assertEquals(1, hookCalls.get());
        assertEquals(NodeIdUtil.toHex(commitB), hookSourceRevision.get());
        assertEquals(graftedHex, hookGraftedNode.get());

        hg.close();
    }

    @Test
    public void testGraftOfMultiFileRevisionCopiesAllFiles() throws Exception {
        HgRepository repo = initRepo("graft_multi_file_repo");
        Hg hg = Hg.wrap(repo);

        Files.writeString(new File(repo.getDirectory(), "base.txt").toPath(), "base\n");
        hg.add().addFile("base.txt").call();
        byte[] commitBase = hg.commit().setAuthor("tester").setMessage("base").call();

        Files.writeString(new File(repo.getDirectory(), "one.txt").toPath(), "one\n");
        Files.writeString(new File(repo.getDirectory(), "two.txt").toPath(), "two\n");
        hg.add().addFile("one.txt").addFile("two.txt").call();
        byte[] commitMulti = hg.commit().setAuthor("tester").setMessage("add one and two").call();

        hg.update().setRevision(NodeIdUtil.toHex(commitBase)).setForce(true).call();
        Files.writeString(new File(repo.getDirectory(), "unrelated.txt").toPath(), "unrelated\n");
        hg.add().addFile("unrelated.txt").call();
        hg.commit().setAuthor("tester").setMessage("unrelated branch commit").call();

        String graftedHex = new GraftCommand(repo).setSource(NodeIdUtil.toHex(commitMulti)).call();
        assertNotNull(graftedHex);

        assertEquals("one\n", Files.readString(new File(repo.getDirectory(), "one.txt").toPath()));
        assertEquals("two\n", Files.readString(new File(repo.getDirectory(), "two.txt").toPath()));

        HgCommit grafted = findCommit(hg, graftedHex);
        assertNotNull(grafted);
        assertTrue(grafted.getFiles().contains("one.txt"));
        assertTrue(grafted.getFiles().contains("two.txt"));

        hg.close();
    }

    // ------------------------------------------------------------------
    // File deletion handling
    // ------------------------------------------------------------------

    @Test
    public void testGraftOfFileRemovalDeletesFileInWorkingCopy() throws Exception {
        HgRepository repo = initRepo("graft_delete_repo");
        Hg hg = Hg.wrap(repo);

        Files.writeString(new File(repo.getDirectory(), "keep.txt").toPath(), "keep\n");
        Files.writeString(new File(repo.getDirectory(), "del.txt").toPath(), "delete-me\n");
        hg.add().addFile("keep.txt").addFile("del.txt").call();
        byte[] commitAdd = hg.commit().setAuthor("tester").setMessage("add keep and del").call();

        hg.remove().setFile("del.txt").call();
        byte[] commitRemove = hg.commit().setAuthor("tester").setMessage("remove del").call();

        // Go back to the commit where del.txt still exists, then graft the removal onto it.
        hg.update().setRevision(NodeIdUtil.toHex(commitAdd)).setForce(true).call();
        assertTrue(new File(repo.getDirectory(), "del.txt").exists());

        String graftedHex = new GraftCommand(repo).setSource(NodeIdUtil.toHex(commitRemove)).call();
        assertNotNull(graftedHex);

        assertFalse(new File(repo.getDirectory(), "del.txt").exists(), "del.txt must be removed by the grafted deletion");
        assertTrue(new File(repo.getDirectory(), "keep.txt").exists());

        hg.close();
    }

    // ------------------------------------------------------------------
    // Non-blocking failure paths (post-graft hook)
    // ------------------------------------------------------------------

    @Test
    public void testPostGraftHookExceptionDoesNotAbortGraft() throws Exception {
        HgRepository repo = initRepo("graft_hook_exception_repo");
        Hg hg = Hg.wrap(repo);

        Files.writeString(new File(repo.getDirectory(), "a.txt").toPath(), "hello\n");
        hg.add().addFile("a.txt").call();
        byte[] commitA = hg.commit().setAuthor("tester").setMessage("add a").call();

        Files.writeString(new File(repo.getDirectory(), "b.txt").toPath(), "world\n");
        hg.add().addFile("b.txt").call();
        byte[] commitB = hg.commit().setAuthor("tester").setMessage("add b").call();

        hg.update().setRevision(NodeIdUtil.toHex(commitA)).setForce(true).call();

        GraftCommand graft = new GraftCommand(repo).setSource(NodeIdUtil.toHex(commitB));
        graft.registerPostGraftHook(ctx -> {
            throw new RuntimeException("boom - simulated hook failure");
        });

        String graftedHex = graft.call();
        assertNotNull(graftedHex, "a failing post-graft hook must not abort the already-committed graft");

        hg.close();
    }

    // ------------------------------------------------------------------
    // Corrupted / missing filelog defensive checks
    // ------------------------------------------------------------------

    @Test
    public void testGraftOfFileWithMissingFilelogThrowsHgRepositoryNotFoundException() throws Exception {
        File repoDir = new File(tempDir, "graft_missing_filelog_repo");
        HgRepository setupRepo = Hg.init().setDirectory(repoDir).call();
        Hg setupHg = Hg.wrap(setupRepo);

        Files.writeString(new File(repoDir, "a.txt").toPath(), "hello\n");
        setupHg.add().addFile("a.txt").call();
        byte[] commitA = setupHg.commit().setAuthor("tester").setMessage("add a").call();

        Files.writeString(new File(repoDir, "x.txt").toPath(), "x-content\n");
        setupHg.add().addFile("x.txt").call();
        byte[] commitX = setupHg.commit().setAuthor("tester").setMessage("add x").call();

        setupHg.update().setRevision(NodeIdUtil.toHex(commitA)).setForce(true).call();

        File flIdx = CommitCommand.getFilelogIndex(setupRepo.getStoreDir(), "x.txt");
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        setupHg.close();

        assertTrue(flIdx.exists());
        Files.deleteIfExists(flIdx.toPath());
        Files.deleteIfExists(flDat.toPath());

        // Reopen so no stale in-memory revlog cache masks the on-disk corruption.
        try (Hg hg = Hg.open(repoDir)) {
            HgRepository repo = hg.getRepository();
            GraftCommand graft = new GraftCommand(repo).setSource(NodeIdUtil.toHex(commitX));
            assertThrows(HgRepositoryNotFoundException.class, graft::call);
        }
    }

    @Test
    public void testGraftOfFileWithEmptyFilelogThrowsHgRevisionNotFoundException() throws Exception {
        File repoDir = new File(tempDir, "graft_empty_filelog_repo");
        HgRepository setupRepo = Hg.init().setDirectory(repoDir).call();
        Hg setupHg = Hg.wrap(setupRepo);

        Files.writeString(new File(repoDir, "a.txt").toPath(), "hello\n");
        setupHg.add().addFile("a.txt").call();
        byte[] commitA = setupHg.commit().setAuthor("tester").setMessage("add a").call();

        Files.writeString(new File(repoDir, "x.txt").toPath(), "x-content\n");
        setupHg.add().addFile("x.txt").call();
        byte[] commitX = setupHg.commit().setAuthor("tester").setMessage("add x").call();

        setupHg.update().setRevision(NodeIdUtil.toHex(commitA)).setForce(true).call();

        File flIdx = CommitCommand.getFilelogIndex(setupRepo.getStoreDir(), "x.txt");
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        setupHg.close();

        assertTrue(flIdx.exists());
        // Truncate to zero bytes: the file still exists (so the "missing filelog" check
        // passes) but now reports zero revisions, so the specific node id can never be found.
        Files.write(flIdx.toPath(), new byte[0]);
        Files.write(flDat.toPath(), new byte[0]);

        try (Hg hg = Hg.open(repoDir)) {
            HgRepository repo = hg.getRepository();
            GraftCommand graft = new GraftCommand(repo).setSource(NodeIdUtil.toHex(commitX));
            assertThrows(HgRevisionNotFoundException.class, graft::call);
        }
    }

    // ------------------------------------------------------------------
    // Hook registration edge case
    // ------------------------------------------------------------------

    @Test
    public void testRegisterPostGraftHookIgnoresNull() throws Exception {
        HgRepository repo = initRepo("graft_null_hook_repo");
        GraftCommand graft = new GraftCommand(repo);
        // Must be a no-op (no exception) and still support fluent chaining.
        assertNotNull(graft.registerPostGraftHook(null));
    }

    // ------------------------------------------------------------------
    // Multi-line commit message reconstruction
    // ------------------------------------------------------------------

    @Test
    public void testGraftPreservesMultiLineCommitMessage() throws Exception {
        HgRepository repo = initRepo("graft_multiline_message_repo");
        Hg hg = Hg.wrap(repo);

        Files.writeString(new File(repo.getDirectory(), "a.txt").toPath(), "hello\n");
        hg.add().addFile("a.txt").call();
        byte[] commitA = hg.commit().setAuthor("tester").setMessage("add a").call();

        Files.writeString(new File(repo.getDirectory(), "b.txt").toPath(), "world\n");
        hg.add().addFile("b.txt").call();
        String multiLineMessage = "summary line\n\ndetailed body line 1\ndetailed body line 2";
        byte[] commitB = hg.commit().setAuthor("tester").setMessage(multiLineMessage).call();

        hg.update().setRevision(NodeIdUtil.toHex(commitA)).setForce(true).call();

        String graftedHex = new GraftCommand(repo).setSource(NodeIdUtil.toHex(commitB)).call();
        HgCommit grafted = findCommit(hg, graftedHex);
        assertNotNull(grafted);
        assertEquals(multiLineMessage, grafted.getMessage());

        hg.close();
    }

    // ------------------------------------------------------------------
    // File removal: branch-never-had-the-file, and untracking a locally-added file
    // ------------------------------------------------------------------

    @Test
    public void testGraftOfFileRemovalIsNoOpWhenTargetBranchNeverHadTheFile() throws Exception {
        HgRepository repo = initRepo("graft_remove_never_existed_repo");
        Hg hg = Hg.wrap(repo);

        Files.writeString(new File(repo.getDirectory(), "base.txt").toPath(), "base\n");
        hg.add().addFile("base.txt").call();
        byte[] commitBase = hg.commit().setAuthor("tester").setMessage("base").call();

        Files.writeString(new File(repo.getDirectory(), "x.txt").toPath(), "x\n");
        hg.add().addFile("x.txt").call();
        hg.commit().setAuthor("tester").setMessage("add x").call();

        hg.remove().setFile("x.txt").call();
        byte[] commitRemoveX = hg.commit().setAuthor("tester").setMessage("remove x").call();

        // Branch off the base commit: this branch never tracked x.txt at all.
        hg.update().setRevision(NodeIdUtil.toHex(commitBase)).setForce(true).call();
        assertFalse(new File(repo.getDirectory(), "x.txt").exists());

        String graftedHex = new GraftCommand(repo).setSource(NodeIdUtil.toHex(commitRemoveX)).call();
        assertNotNull(graftedHex);

        assertFalse(new File(repo.getDirectory(), "x.txt").exists());
        assertNull(repo.getDirstate().getEntries().get("x.txt"));

        hg.close();
    }

    @Test
    public void testGraftOfFileRemovalUntracksLocallyAddedUncommittedFile() throws Exception {
        HgRepository repo = initRepo("graft_remove_untracks_added_repo");
        Hg hg = Hg.wrap(repo);

        Files.writeString(new File(repo.getDirectory(), "base.txt").toPath(), "base\n");
        hg.add().addFile("base.txt").call();
        byte[] commitBase = hg.commit().setAuthor("tester").setMessage("base").call();

        Files.writeString(new File(repo.getDirectory(), "del.txt").toPath(), "committed-del\n");
        hg.add().addFile("del.txt").call();
        hg.commit().setAuthor("tester").setMessage("add del").call();

        hg.remove().setFile("del.txt").call();
        byte[] commitRemoveDel = hg.commit().setAuthor("tester").setMessage("remove del").call();

        // On the target branch, locally `hg add` a same-named file that was never committed
        // (dirstate state 'a'). Grafting the removal must untrack it entirely rather than
        // recording a "removed" entry for something the target never actually committed.
        hg.update().setRevision(NodeIdUtil.toHex(commitBase)).setForce(true).call();
        Files.writeString(new File(repo.getDirectory(), "del.txt").toPath(), "locally-added-uncommitted\n");
        hg.add().addFile("del.txt").call();
        assertEquals('a', repo.getDirstate().getEntries().get("del.txt").getState());

        String graftedHex = new GraftCommand(repo).setSource(NodeIdUtil.toHex(commitRemoveDel)).call();
        assertNotNull(graftedHex);

        assertFalse(new File(repo.getDirectory(), "del.txt").exists());
        assertNull(repo.getDirstate().getEntries().get("del.txt"));

        hg.close();
    }

    // ------------------------------------------------------------------
    // Executable bit / symlink flag preservation
    // ------------------------------------------------------------------

    @Test
    public void testGraftPreservesExecutableBitOfNewlyIntroducedFile() throws Exception {
        HgRepository repo = initRepo("graft_executable_repo");
        Hg hg = Hg.wrap(repo);

        Files.writeString(new File(repo.getDirectory(), "base.txt").toPath(), "base\n");
        hg.add().addFile("base.txt").call();
        byte[] commitBase = hg.commit().setAuthor("tester").setMessage("base").call();

        File script = new File(repo.getDirectory(), "script.sh");
        Files.writeString(script.toPath(), "#!/bin/sh\necho hi\n");
        assertTrue(script.setExecutable(true, false));
        hg.add().addFile("script.sh").call();
        byte[] commitScript = hg.commit().setAuthor("tester").setMessage("add executable script").call();

        // Branch off base: this branch never had script.sh, so it must be freshly tracked ('a').
        hg.update().setRevision(NodeIdUtil.toHex(commitBase)).setForce(true).call();
        assertFalse(new File(repo.getDirectory(), "script.sh").exists());

        String graftedHex = new GraftCommand(repo).setSource(NodeIdUtil.toHex(commitScript)).call();
        assertNotNull(graftedHex);

        File graftedScript = new File(repo.getDirectory(), "script.sh");
        assertTrue(graftedScript.exists());
        assertTrue(graftedScript.canExecute(), "grafting an executable file must preserve its executable bit (verified against real `hg graft`)");

        hg.close();
    }

    @Test
    public void testGraftPreservesSymlinkOfNewlyIntroducedFile() throws Exception {
        HgRepository repo = initRepo("graft_symlink_repo");
        Hg hg = Hg.wrap(repo);

        Files.writeString(new File(repo.getDirectory(), "base.txt").toPath(), "base\n");
        hg.add().addFile("base.txt").call();
        byte[] commitBase = hg.commit().setAuthor("tester").setMessage("base").call();

        File link = new File(repo.getDirectory(), "link.txt");
        Files.createSymbolicLink(link.toPath(), java.nio.file.Path.of("base.txt"));
        hg.add().addFile("link.txt").call();
        byte[] commitLink = hg.commit().setAuthor("tester").setMessage("add symlink").call();

        hg.update().setRevision(NodeIdUtil.toHex(commitBase)).setForce(true).call();
        assertFalse(new File(repo.getDirectory(), "link.txt").exists());

        String graftedHex = new GraftCommand(repo).setSource(NodeIdUtil.toHex(commitLink)).call();
        assertNotNull(graftedHex);

        File graftedLink = new File(repo.getDirectory(), "link.txt");
        assertTrue(Files.isSymbolicLink(graftedLink.toPath()), "grafting a symlink must recreate it as a symlink (verified against real `hg graft`)");
        assertEquals("base.txt", Files.readSymbolicLink(graftedLink.toPath()).toString());

        hg.close();
    }

    /**
     * <b>Behavior corrected 2026-09-05.</b> Before this fix, {@link GraftCommand} had no 3-way
     * merge/conflict detection at all: it always blindly overwrote the destination's file with
     * the graft source's content, silently discarding whatever the destination had -- a real
     * data-loss bug (this exact scenario used to assert the symlink replacement happened
     * silently). Verified live against real {@code hg graft} 7.2: grafting a revision that adds
     * {@code link.txt} as a symlink onto a destination that independently added {@code link.txt}
     * as a different (regular-file) path since their common ancestor is a genuine "both sides
     * added it differently" conflict -- real hg leaves it unresolved ("no tool found to merge
     * link.txt" / "file 'link.txt' needs to be resolved") rather than silently picking either
     * side. {@link GraftCommand} now shares {@link RebaseCommand}'s hardened 3-way-merge cherry-
     * pick logic ({@link RebaseCommand#attemptThreeWayMerge}) and must pause the same way {@link
     * RebaseCommand} does on a genuine same-path conflict.
     */
    @Test
    public void testGraftOfDivergedFileAddedDifferentlyOnBothSidesPausesOnConflict() throws Exception {
        HgRepository repo = initRepo("graft_symlink_overwrite_repo");
        Hg hg = Hg.wrap(repo);

        Files.writeString(new File(repo.getDirectory(), "base.txt").toPath(), "base\n");
        hg.add().addFile("base.txt").call();
        byte[] commitBase = hg.commit().setAuthor("tester").setMessage("base").call();

        // Target branch: link.txt already exists as a tracked *regular* file.
        Files.writeString(new File(repo.getDirectory(), "link.txt").toPath(), "was-a-regular-file\n");
        hg.add().addFile("link.txt").call();
        hg.commit().setAuthor("tester").setMessage("add link.txt as regular file").call();

        // Source branch (off base): link.txt is a symlink instead.
        hg.update().setRevision(NodeIdUtil.toHex(commitBase)).setForce(true).call();
        File link = new File(repo.getDirectory(), "link.txt");
        Files.createSymbolicLink(link.toPath(), java.nio.file.Path.of("base.txt"));
        hg.add().addFile("link.txt").call();
        byte[] commitSymlink = hg.commit().setAuthor("tester").setMessage("replace with symlink").call();

        // Go back to the branch where link.txt is still a regular file, then graft the symlink onto it.
        hg.update().setRevision(NodeIdUtil.toHex(commitBase)).setForce(true).call();
        Files.writeString(new File(repo.getDirectory(), "link.txt").toPath(), "was-a-regular-file\n");
        hg.add().addFile("link.txt").call();
        hg.commit().setAuthor("tester").setMessage("add link.txt as regular file again").call();
        assertTrue(link.exists() && !Files.isSymbolicLink(link.toPath()));

        GraftCommand graft = new GraftCommand(repo).setSource(NodeIdUtil.toHex(commitSymlink));
        io.github.search5.hg4j.errors.HgMergeConflictException ex =
                assertThrows(io.github.search5.hg4j.errors.HgMergeConflictException.class, graft::call,
                        "diverged content on both sides must pause the graft instead of silently overwriting it");
        assertEquals(java.util.List.of("link.txt"), ex.getConflictPaths());

        // Aborting must cleanly restore the pre-graft state (mirrors RebaseCommand#abort()).
        new GraftCommand(repo).abort();
        assertTrue(link.exists() && !Files.isSymbolicLink(link.toPath()),
                "abort() must restore link.txt to its pre-graft (regular file) content");
        assertEquals("was-a-regular-file\n", Files.readString(link.toPath()));

        hg.close();
    }

    // ------------------------------------------------------------------
    // Real 3-way merge: clean (non-conflicting) divergence, and continueGraft() resumption
    // ------------------------------------------------------------------

    /**
     * When the destination and the graft source changed *different lines* of the same file since
     * their common ancestor, the new 3-way merge must combine both edits cleanly (no conflict),
     * rather than either the pre-2026-09-05 "always take source" bug or a spurious conflict.
     */
    @Test
    public void testGraftMergesDisjointLineEditsCleanlyWithoutConflict() throws Exception {
        HgRepository repo = initRepo("graft_clean_merge_repo");
        Hg hg = Hg.wrap(repo);

        Files.writeString(new File(repo.getDirectory(), "f.txt").toPath(), "line1\nline2\nline3\n");
        hg.add().addFile("f.txt").call();
        byte[] commitBase = hg.commit().setAuthor("tester").setMessage("base").call();

        // Source branch: changes line1 only.
        Files.writeString(new File(repo.getDirectory(), "f.txt").toPath(), "line1-source\nline2\nline3\n");
        byte[] commitSource = hg.commit().setAuthor("tester").setMessage("source changes line1").call();

        // Destination branch (off base): changes line3 only -- disjoint from the source's edit.
        hg.update().setRevision(NodeIdUtil.toHex(commitBase)).setForce(true).call();
        Files.writeString(new File(repo.getDirectory(), "f.txt").toPath(), "line1\nline2\nline3-dest\n");
        hg.commit().setAuthor("tester").setMessage("dest changes line3").call();

        String graftedHex = new GraftCommand(repo).setSource(NodeIdUtil.toHex(commitSource)).call();
        assertNotNull(graftedHex);

        assertEquals("line1-source\nline2\nline3-dest\n",
                Files.readString(new File(repo.getDirectory(), "f.txt").toPath()),
                "a real 3-way merge must combine both sides' disjoint edits");

        HgCommit grafted = findCommit(hg, graftedHex);
        assertNotNull(grafted);
        assertTrue(grafted.getFiles().contains("f.txt"));

        hg.close();
    }

    /**
     * Companion to {@link #testGraftOfDivergedFileAddedDifferentlyOnBothSidesPausesOnConflict}:
     * after resolving the conflict on disk exactly like a real {@code hg resolve} session would,
     * {@link GraftCommand#continueGraft()} must finish the paused commit.
     */
    @Test
    public void testContinueGraftAfterManualResolutionCompletesTheGraft() throws Exception {
        HgRepository repo = initRepo("graft_continue_repo");
        Hg hg = Hg.wrap(repo);

        Files.writeString(new File(repo.getDirectory(), "f.txt").toPath(), "line1\n");
        hg.add().addFile("f.txt").call();
        byte[] commitBase = hg.commit().setAuthor("tester").setMessage("base").call();

        Files.writeString(new File(repo.getDirectory(), "f.txt").toPath(), "line1-source\n");
        byte[] commitSource = hg.commit().setAuthor("tester").setMessage("source modifies f").call();

        hg.update().setRevision(NodeIdUtil.toHex(commitBase)).setForce(true).call();
        Files.writeString(new File(repo.getDirectory(), "f.txt").toPath(), "line1-dest\n");
        hg.commit().setAuthor("tester").setMessage("dest modifies f (conflicts with source)").call();

        GraftCommand graft = new GraftCommand(repo).setSource(NodeIdUtil.toHex(commitSource));
        io.github.search5.hg4j.errors.HgMergeConflictException ex =
                assertThrows(io.github.search5.hg4j.errors.HgMergeConflictException.class, graft::call);
        assertEquals(java.util.List.of("f.txt"), ex.getConflictPaths());
        assertEquals("<<<<<<< dest\nline1-dest\n=======\nline1-source\n>>>>>>> source\n",
                Files.readString(new File(repo.getDirectory(), "f.txt").toPath()));

        // Manually resolve, exactly like a user driving `hg resolve` would: overwrite the
        // conflict-marked file with the merged content, then mark it resolved.
        Files.writeString(new File(repo.getDirectory(), "f.txt").toPath(), "line1-dest\nline1-source\n");
        new ResolveCommand(repo).setFile("f.txt").markResolved(true).call();

        String graftedHex = new GraftCommand(repo).continueGraft();
        assertNotNull(graftedHex);

        HgCommit grafted = findCommit(hg, graftedHex);
        assertNotNull(grafted, "grafted commit must be visible in log");
        assertEquals("line1-dest\nline1-source\n", Files.readString(new File(repo.getDirectory(), "f.txt").toPath()));

        // Nothing left in progress.
        assertThrows(io.github.search5.hg4j.errors.HgValidationException.class,
                () -> new GraftCommand(repo).continueGraft());
        assertThrows(io.github.search5.hg4j.errors.HgValidationException.class,
                () -> new GraftCommand(repo).abort());

        hg.close();
    }

    // ------------------------------------------------------------------
    // Empty (zero-file) source revision
    // ------------------------------------------------------------------

    @Test
    public void testGraftOfEmptyRootCommitWithNoFiles() throws Exception {
        HgRepository repo = initRepo("graft_empty_root_repo");
        Hg hg = Hg.wrap(repo);

        // A commit with no tracked files at all: its manifest revision content is empty,
        // exercising the manifest-text-parsing loop's empty-line branch.
        byte[] emptyRootCommit = hg.commit().setAuthor("tester").setMessage("empty root").call();

        Files.writeString(new File(repo.getDirectory(), "other.txt").toPath(), "other\n");
        hg.add().addFile("other.txt").call();
        hg.commit().setAuthor("tester").setMessage("add other").call();

        String graftedHex = new GraftCommand(repo).setSource(NodeIdUtil.toHex(emptyRootCommit)).call();
        assertNotNull(graftedHex);

        hg.close();
    }
}
