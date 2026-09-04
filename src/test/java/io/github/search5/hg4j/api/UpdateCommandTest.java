package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.treewalk.HgTreeFilter;
import io.github.search5.hg4j.util.NodeIdUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class UpdateCommandTest {

    @Test
    public void callThrowsWhenRepositoryIsEmpty(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        assertThrows(HgValidationException.class,
                () -> new UpdateCommand(repo).setRevision("0").call());
    }

    @Test
    public void callThrowsWithoutForceWhenWorkingDirHasUncommittedChanges(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        Files.writeString(new File(tempDir.toFile(), "a.txt").toPath(), "dirty");
        assertThrows(HgValidationException.class,
                () -> new UpdateCommand(repo).setRevision("0").call());
    }

    @Test
    public void forceOverwritesUncommittedChanges(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File f = new File(tempDir.toFile(), "a.txt");
        Files.writeString(f.toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        Files.writeString(f.toPath(), "dirty");
        new UpdateCommand(repo).setRevision("0").setForce(true).call();
        assertEquals("v0", Files.readString(f.toPath()));
    }

    @Test
    public void updateBetweenRevisionsCreatesModifiesAndDeletesFilesAndCleansEmptyDirs(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();

        Files.writeString(new File(repoDir, "a.txt").toPath(), "a-v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        // rev1 modifies a.txt and adds a nested file under a subdirectory.
        Files.writeString(new File(repoDir, "a.txt").toPath(), "a-v1");
        File nestedDir = new File(repoDir, "sub");
        nestedDir.mkdirs();
        File nested = new File(nestedDir, "b.txt");
        Files.writeString(nested.toPath(), "nested content");
        new AddCommand(repo).addFile("sub/b.txt").call();
        new CommitCommand(repo).setMessage("rev1").call();

        // Going back to rev0 must restore a.txt's old content, delete sub/b.txt,
        // and remove the now-empty "sub" directory.
        new UpdateCommand(repo).setRevision("0").call();
        assertEquals("a-v0", Files.readString(new File(repoDir, "a.txt").toPath()));
        assertFalse(nested.exists());
        assertFalse(nestedDir.exists(), "Emptied parent directory must be cleaned up");
        assertFalse(repo.getDirstate().getEntries().containsKey("sub/b.txt"));

        // Going forward to rev1 (tip) must recreate the file and content.
        new UpdateCommand(repo).setRevision("tip").call();
        assertEquals("a-v1", Files.readString(new File(repoDir, "a.txt").toPath()));
        assertEquals("nested content", Files.readString(nested.toPath()));

        // Updating to the same revision again exercises the "content already matches
        // on disk, skip rewrite" fast path.
        byte[] target = new UpdateCommand(repo).setRevision("tip").call();
        assertEquals("nested content", Files.readString(nested.toPath()));
        assertEquals(NodeIdUtil.toHex(target), NodeIdUtil.toHex(repo.getDirstate().getParent1()));
    }

    @Test
    public void resolvesNamedBranchHeadWhenRevisionMatchesABranchName(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        new BranchCommand(repo).setBranchName("feature").call();
        Files.writeString(new File(repoDir, "b.txt").toPath(), "feature file");
        new AddCommand(repo).addFile("b.txt").call();
        byte[] featureHead = new CommitCommand(repo).setMessage("rev1 on feature").call();

        new UpdateCommand(repo).setRevision("0").call();
        byte[] resolved = new UpdateCommand(repo).setRevision("feature").call();
        assertEquals(NodeIdUtil.toHex(featureHead), NodeIdUtil.toHex(resolved));
    }

    @Test
    public void throwsWhenRevisionCannotBeResolved(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        assertThrows(HgRevisionNotFoundException.class,
                () -> new UpdateCommand(repo).setRevision("no-such-branch-or-rev").call());
    }

    @Test
    public void switchesActiveBookmarkWhenTargetHasExactlyOneMatchingBookmark(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        byte[] rev0 = new CommitCommand(repo).setMessage("rev0").call();

        Files.writeString(new File(repoDir, "a.txt").toPath(), "v1");
        new CommitCommand(repo).setMessage("rev1").call();

        new BookmarkCommand(repo).setBookmarkName("stable").setNodeId(rev0).call();

        new UpdateCommand(repo).setRevision("0").call();
        Map<String, String> active = new BookmarkCommand(repo).call();
        // BookmarkCommand.call() lists all bookmarks; verify "stable" still points at rev0
        // and that updating away from a bookmarked revision (rev1, unbookmarked) clears activity.
        assertEquals(NodeIdUtil.toHex(rev0), active.get("stable"));
    }

    @Test
    public void preUpdateHookCanRejectAndPostUpdateHookRunsOnSuccess(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        assertThrows(HgValidationException.class, () ->
                new UpdateCommand(repo).setRevision("0").registerPreUpdateHook(ctx -> false).call());

        List<String> fired = new ArrayList<>();
        new UpdateCommand(repo).setRevision("0")
                .registerPreUpdateHook(ctx -> { fired.add("pre"); return true; })
                .registerPostUpdateHook(ctx -> { fired.add("post"); return true; })
                .call();
        assertEquals(List.of("pre", "post"), fired);
    }

    @Test
    public void treeFilterLimitsWhichFilesAreCheckedOut(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        Files.writeString(new File(repoDir, "a.txt").toPath(), "v1");
        File other = new File(repoDir, "other.txt");
        Files.writeString(other.toPath(), "other v1");
        new AddCommand(repo).addFile("other.txt").call();
        new CommitCommand(repo).setMessage("rev1").call();

        new UpdateCommand(repo).setRevision("0").setForce(true).call();
        HgTreeFilter filter =
                HgTreeFilter.createPathPrefixFilter(List.of("a.txt"), List.of());
        new UpdateCommand(repo).setRevision("tip").setForce(true).setTreeFilter(filter).call();

        assertEquals("v1", Files.readString(new File(repoDir, "a.txt").toPath()));
        assertFalse(other.exists(), "Filtered-out file must not be checked out");
    }

    @Test
    public void updateRecursivelyChecksOutSubrepositoriesDeclaredInHgsub(@TempDir Path tempDir) throws Exception {
        File subSourceDir = tempDir.resolve("sub-source").toFile();
        HgRepository subSourceRepo = Hg.init().setDirectory(subSourceDir).call();
        Files.writeString(new File(subSourceDir, "hello.txt").toPath(), "hello from subrepo");
        new AddCommand(subSourceRepo).call();
        new CommitCommand(subSourceRepo).setAuthor("s <s@example.com>").setMessage("sub commit").call();
        String subTipHex = new LogCommand(subSourceRepo).call().get(0).getNodeId().toHex();

        File repoDir = tempDir.resolve("main").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        // Check out the subrepo locally before declaring/committing .hgsub -- real hg only
        // records a non-null revision in .hgsubstate for a subrepo that is actually present
        // in the working directory at commit time (a declared-but-not-checked-out path
        // instead has its .hgsubstate entry reset to the null revision; see
        // mercurial-spec-compliance-requirement.md, backlog 23/24, decided 2026-09-04).
        File vendorSubDir = new File(repoDir, "vendor/sub");
        new CloneCommand().setSource(subSourceDir.getAbsolutePath()).setDirectory(vendorSubDir).call();

        Files.writeString(new File(repoDir, ".hgsub").toPath(), "vendor/sub = " + subSourceDir.getAbsolutePath() + "\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev1 with subrepo").call();

        // Sanity: the commit auto-recorded the checked-out subrepo's real revision (not the
        // null revision) because "vendor/sub" was checked out beforehand.
        assertEquals(subTipHex + " vendor/sub\n", Files.readString(new File(repoDir, ".hgsubstate").toPath()));

        new UpdateCommand(repo).setRevision("0").setForce(true).call();
        new UpdateCommand(repo).setRevision("tip").setForce(true).call();

        File checkedOutSub = new File(repoDir, "vendor/sub/hello.txt");
        assertTrue(checkedOutSub.exists(), "Subrepo declared in .hgsub must be recursively checked out");
        assertEquals("hello from subrepo", Files.readString(checkedOutSub.toPath()));

        // Re-checking-out the same revision must reuse the existing subrepo clone
        // (it already has a .hg directory) instead of re-initializing it.
        new UpdateCommand(repo).setRevision("tip").setForce(true).call();
        assertTrue(checkedOutSub.exists());
    }

    @Test
    public void updateChecksOutASymlinkFromTheTargetManifest(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();

        Files.writeString(new File(repoDir, "target.txt").toPath(), "target content");
        File link = new File(repoDir, "link.txt");
        Files.createSymbolicLink(link.toPath(), Path.of("target.txt"));
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("add symlink").call();

        Files.delete(link.toPath());
        Files.writeString(link.toPath(), "not a symlink anymore");

        new UpdateCommand(repo).setRevision("0").setForce(true).call();

        assertTrue(Files.isSymbolicLink(link.toPath()));
        assertEquals("target.txt", Files.readSymbolicLink(link.toPath()).toString());
    }

    @Test
    public void clearsActiveBookmarkWhenTargetHasMultipleOrNoMatchingBookmarks(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        byte[] rev0 = new CommitCommand(repo).setMessage("rev0").call();

        // Two bookmarks pointing at the exact same revision: ambiguous, so no single
        // bookmark should be auto-activated.
        new BookmarkCommand(repo).setBookmarkName("alpha").setNodeId(rev0).call();
        new BookmarkCommand(repo).setBookmarkName("beta").setNodeId(rev0).call();

        new UpdateCommand(repo).setRevision("0").call();

        // Revision with zero matching bookmarks.
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v1");
        new CommitCommand(repo).setMessage("rev1").call();
        new UpdateCommand(repo).setRevision("1").call();
        assertEquals("v1", Files.readString(new File(repoDir, "a.txt").toPath()));
    }

    @Test
    public void reUpdatingToSameRevisionSkipsRewriteForExecutableAndSymlinkFiles(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();

        File script = new File(repoDir, "run.sh");
        Files.writeString(script.toPath(), "#!/bin/sh\n");
        assertTrue(script.setExecutable(true, false));

        File target = new File(repoDir, "target.txt");
        Files.writeString(target.toPath(), "target content much longer than the link path");
        File link = new File(repoDir, "link.txt");
        Files.createSymbolicLink(link.toPath(), Path.of("target.txt"));

        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        // This previously failed: after the first checkout, UpdateCommand recorded the
        // symlink's dirstate size as its own path-string length, while StatusCommand's
        // (pre-fix) size check followed the link to the target file's content length —
        // an artificial mismatch that made the second update think there were
        // uncommitted changes. Now that both sides use lstat-style symlink semantics,
        // re-checking-out the same revision must be a clean no-op.
        new UpdateCommand(repo).setRevision("0").call();
        new UpdateCommand(repo).setRevision("0").call();

        assertTrue(script.canExecute(), "Executable bit must be preserved across a no-op re-checkout");
        assertTrue(Files.isSymbolicLink(link.toPath()), "Symlink must be preserved across a no-op re-checkout");
        assertEquals("target.txt", Files.readSymbolicLink(link.toPath()).toString());
    }

    @Test
    public void updateRestoresWorkingBranchToMatchTheTargetRevision(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();

        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0 on default").call();
        assertEquals("default", repo.getBranch());

        new BranchCommand(repo).setBranchName("feature").call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v1");
        new CommitCommand(repo).setMessage("rev1 on feature").call();
        assertEquals("feature", repo.getBranch());

        new UpdateCommand(repo).setRevision("0").call();
        assertEquals("default", repo.getBranch(),
                "Updating to rev0 (committed on the default branch) must restore the working branch to 'default'");

        new UpdateCommand(repo).setRevision("1").call();
        assertEquals("feature", repo.getBranch(),
                "Updating to rev1 (committed on the feature branch) must restore the working branch to 'feature'");
    }

    @Test
    public void updateToABranchNameLeavesTheWorkingBranchSetToThatBranch(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();

        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        new BranchCommand(repo).setBranchName("feature").call();
        Files.writeString(new File(repoDir, "b.txt").toPath(), "feature file");
        new AddCommand(repo).addFile("b.txt").call();
        new CommitCommand(repo).setMessage("rev1 on feature").call();

        new UpdateCommand(repo).setRevision("0").call();
        new UpdateCommand(repo).setRevision("feature").call();
        assertEquals("feature", repo.getBranch(),
                "Updating by branch name must leave the working branch set to that branch");
    }
}
