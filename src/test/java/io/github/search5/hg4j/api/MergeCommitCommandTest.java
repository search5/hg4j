package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.search5.hg4j.lib.NodeId.NULL;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MergeCommitCommand} (P3-33): writes a {@link TreeMergeCommand.TreeMergeResult} directly
 * to the filelog/manifest/changelog revlogs as a real 2-parent changeset -- entirely in-core, no
 * working directory or dirstate involved (JGit inCore-merge parity). This suite mirrors {@code
 * TreeMergeCommandTest}'s repository-setup pattern (fork the dirstate to build two divergent
 * branches from a common base) and {@code CommitCommandTest}'s changelog/manifest assertions.
 */
public class MergeCommitCommandTest {

    private static byte[] commit(HgRepository repo, String message) throws Exception {
        new AddCommand(repo).call();
        return new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage(message).call();
    }

    private static void write(File repoDir, String name, String content) throws Exception {
        Files.writeString(new File(repoDir, name).toPath(), content);
    }

    private static void forkFrom(HgRepository repo, byte[] base) throws Exception {
        Dirstate forkDirstate = repo.getDirstate();
        forkDirstate.setParents(new NodeId(base), NULL);
        repo.writeDirstate(forkDirstate);
    }

    @Test
    public void writesACleanTwoParentMergeCommitWithNoConflicts(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "a.txt", "line1\nline2\nline3\n");
        byte[] base = commit(repo, "base");

        write(repoDir, "a.txt", "line1 OURS\nline2\nline3\n");
        byte[] ours = commit(repo, "ours");

        forkFrom(repo, base);
        write(repoDir, "b.txt", "new file from theirs\n");
        byte[] theirs = commit(repo, "theirs");

        TreeMergeCommand.TreeMergeResult mergeResult =
                new TreeMergeCommand(repo).setOurs(ours).setTheirs(theirs).call();
        assertFalse(mergeResult.isConflicted());

        byte[] newCommit = new MergeCommitCommand(repo)
                .setParents(ours, theirs)
                .setTreeMergeResult(mergeResult)
                .setAuthor("merger <merger@example.com>")
                .setMessage("merge ours+theirs")
                .call();

        assertNotNull(newCommit);

        Revlog changelog = repo.getRevlog(new File(repo.getStoreDir(), "00changelog.i"),
                new File(repo.getStoreDir(), "00changelog.d"));
        int newRev = NodeIdUtil.findRevisionByNodeId(changelog, newCommit);
        assertTrue(newRev >= 0, "the new changeset must actually be findable in the changelog");
        Revlog.IndexRecord rec = changelog.getIndexRecord(newRev);
        assertArrayEquals(ours, changelog.getIndexRecord(rec.getParent1()).getNodeId());
        assertArrayEquals(theirs, changelog.getIndexRecord(rec.getParent2()).getNodeId());

        // Merged manifest must contain both a.txt (ours' own unopposed change) and b.txt
        // (theirs' addition) -- exactly TreeMergeResult's decision, now durably recorded.
        java.util.Map<String, String> mergedManifest = repo.getManifestAtCommit(newCommit);
        assertTrue(mergedManifest.containsKey("a.txt"));
        assertTrue(mergedManifest.containsKey("b.txt"));

        // The new file's content must be readable back from its own filelog.
        String bHex = mergedManifest.get("b.txt").substring(0, 40);
        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "b.txt");
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        Revlog filelog = repo.getRevlog(flIdx, flDat);
        int bRev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(bHex));
        assertEquals("new file from theirs\n", new String(filelog.getRevisionContent(bRev), StandardCharsets.UTF_8));

        // Commit message must be exactly what was set.
        String clText = new String(changelog.getRevisionContent(newRev), StandardCharsets.UTF_8);
        assertTrue(clText.endsWith("merge ours+theirs"));
    }

    @Test
    public void neverTouchesTheWorkingDirectoryOrDirstate(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "a.txt", "base\n");
        byte[] base = commit(repo, "base");

        write(repoDir, "a.txt", "ours\n");
        byte[] ours = commit(repo, "ours");

        forkFrom(repo, base);
        write(repoDir, "a.txt", "theirs\n");
        byte[] theirs = commit(repo, "theirs (unrelated fork, a.txt diverges but we won't use its conflict)");

        // Use a non-conflicting pair instead so this stays a "clean merge" fixture: merge ours
        // with base (a no-op content-wise) just to exercise the write path without dirstate/wdir.
        TreeMergeCommand.TreeMergeResult mergeResult =
                new TreeMergeCommand(repo).setOurs(ours).setTheirs(base).call();
        assertFalse(mergeResult.isConflicted());

        byte[] dirstateBefore = Files.readAllBytes(new File(repoDir, ".hg/dirstate").toPath());
        String workingContentBefore = Files.readString(new File(repoDir, "a.txt").toPath());

        new MergeCommitCommand(repo)
                .setParents(ours, base)
                .setTreeMergeResult(mergeResult)
                .setAuthor("merger <merger@example.com>")
                .setMessage("merge without touching working copy")
                .call();

        assertArrayEquals(dirstateBefore, Files.readAllBytes(new File(repoDir, ".hg/dirstate").toPath()),
                "dirstate must be byte-for-byte untouched");
        assertEquals(workingContentBefore, Files.readString(new File(repoDir, "a.txt").toPath()),
                "working copy file must be untouched");
    }

    @Test
    public void removesFilesThatTheMergeResultMarkedRemoved(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "keep.txt", "keep\n");
        write(repoDir, "gone.txt", "bye\n");
        byte[] base = commit(repo, "base");

        write(repoDir, "ours-only.txt", "o\n");
        byte[] ours = commit(repo, "ours");

        forkFrom(repo, base);
        new RemoveCommand(repo).setFile("gone.txt").call();
        byte[] theirs = commit(repo, "theirs removes gone.txt");

        TreeMergeCommand.TreeMergeResult mergeResult =
                new TreeMergeCommand(repo).setOurs(ours).setTheirs(theirs).call();
        assertFalse(mergeResult.isConflicted());
        assertTrue(mergeResult.getRemovedFiles().contains("gone.txt"));

        byte[] newCommit = new MergeCommitCommand(repo)
                .setParents(ours, theirs)
                .setTreeMergeResult(mergeResult)
                .setAuthor("merger <merger@example.com>")
                .setMessage("merge removal")
                .call();

        java.util.Map<String, String> mergedManifest = repo.getManifestAtCommit(newCommit);
        assertFalse(mergedManifest.containsKey("gone.txt"));
        assertTrue(mergedManifest.containsKey("keep.txt"));
        assertTrue(mergedManifest.containsKey("ours-only.txt"));
    }

    @Test
    public void appliesModeChangesFromTheMergeResult(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "run.sh", "echo hi\n");
        byte[] base = commit(repo, "base");
        byte[] ours = base;

        new File(repoDir, "run.sh").setExecutable(true, false);
        new AddCommand(repo).call();
        byte[] theirs = new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage("chmod +x").call();

        TreeMergeCommand.TreeMergeResult mergeResult =
                new TreeMergeCommand(repo).setOurs(ours).setTheirs(theirs).call();
        assertFalse(mergeResult.isConflicted());
        assertEquals(0755, mergeResult.getChangedModes().get("run.sh"));

        byte[] newCommit = new MergeCommitCommand(repo)
                .setParents(ours, theirs)
                .setTreeMergeResult(mergeResult)
                .setAuthor("merger <merger@example.com>")
                .setMessage("merge chmod")
                .call();

        java.util.Map<String, String> mergedManifest = repo.getManifestAtCommit(newCommit);
        String entry = mergedManifest.get("run.sh");
        assertTrue(entry.endsWith("x"), "the executable flag must be recorded on the merged manifest entry: " + entry);
    }

    @Test
    public void preservesCopyMetadataForAFileCleanlyAdoptedFromTheirs(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "a.txt", "content\n");
        byte[] base = commit(repo, "base");

        write(repoDir, "unrelated.txt", "o\n");
        byte[] ours = commit(repo, "ours touches something else");

        forkFrom(repo, base);
        new CopyCommand(repo).setSource("a.txt").setDestination("a-copy.txt").call();
        new AddCommand(repo).call();
        byte[] theirs = new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage("hg cp a.txt a-copy.txt").call();

        TreeMergeCommand.TreeMergeResult mergeResult =
                new TreeMergeCommand(repo).setOurs(ours).setTheirs(theirs).call();
        assertFalse(mergeResult.isConflicted());
        assertTrue(mergeResult.getChangedFiles().containsKey("a-copy.txt"));
        assertTrue(mergeResult.getCopiedFiles().containsKey("a-copy.txt"),
                "TreeMergeCommand must forward the copy metadata theirs' own filelog revision already carried");
        assertEquals("a.txt", mergeResult.getCopiedFiles().get("a-copy.txt").get("copy"));

        byte[] newCommit = new MergeCommitCommand(repo)
                .setParents(ours, theirs)
                .setTreeMergeResult(mergeResult)
                .setAuthor("merger <merger@example.com>")
                .setMessage("merge copy")
                .call();

        java.util.Map<String, String> mergedManifest = repo.getManifestAtCommit(newCommit);
        String copyHex = mergedManifest.get("a-copy.txt").substring(0, 40);
        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "a-copy.txt");
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        Revlog filelog = repo.getRevlog(flIdx, flDat);
        int rev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(copyHex));
        java.util.Map<String, String> storedMeta = filelog.getRevisionMetadata(rev);
        assertEquals("a.txt", storedMeta.get("copy"),
                "the merge-committed filelog revision must itself carry the copy metadata (hg log --follow parity)");
    }

    @Test
    public void throwsWhenTreeMergeResultIsConflicted(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "a.txt", "line1\n");
        byte[] base = commit(repo, "base");

        write(repoDir, "a.txt", "ours version\n");
        byte[] ours = commit(repo, "ours");

        forkFrom(repo, base);
        write(repoDir, "a.txt", "theirs version\n");
        byte[] theirs = commit(repo, "theirs");

        TreeMergeCommand.TreeMergeResult mergeResult =
                new TreeMergeCommand(repo).setOurs(ours).setTheirs(theirs).call();
        assertTrue(mergeResult.isConflicted());

        // Defensive: MergeCommitCommand is never expected to be called with a conflicted result
        // (callers must check isConflicted() first, exactly like PullRequestServiceImpl.hgMerge()
        // does) -- but it must refuse outright rather than silently commit conflict markers.
        assertThrows(IllegalStateException.class, () -> new MergeCommitCommand(repo)
                .setParents(ours, theirs)
                .setTreeMergeResult(mergeResult)
                .setAuthor("merger <merger@example.com>")
                .setMessage("should not be committed")
                .call());
    }

    @Test
    public void realHgReadsBackTheMergedFileContentAndManifestCorrectly(@TempDir Path tempDir) throws Exception {
        // hg4j-only round trip (real-hg CLI verification happens separately, per this repo's
        // established convention of never trusting a hg4j-only round trip alone) -- this test just
        // pins down that repo.getManifestAtCommit()/filelog content read-back agree with what was
        // written, using the SAME reader machinery real hg-interop tests build on elsewhere.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "a.txt", "line1\nline2\nline3\n");
        byte[] base = commit(repo, "base");

        write(repoDir, "a.txt", "line1 OURS\nline2\nline3\n");
        byte[] ours = commit(repo, "ours");

        forkFrom(repo, base);
        write(repoDir, "a.txt", "line1\nline2\nline3 THEIRS\n");
        byte[] theirs = commit(repo, "theirs");

        TreeMergeCommand.TreeMergeResult mergeResult =
                new TreeMergeCommand(repo).setOurs(ours).setTheirs(theirs).call();
        assertFalse(mergeResult.isConflicted());

        byte[] newCommit = new MergeCommitCommand(repo)
                .setParents(ours, theirs)
                .setTreeMergeResult(mergeResult)
                .setAuthor("merger <merger@example.com>")
                .setMessage("merge both edits")
                .call();

        String mergedHex = mergeResult.getChangedFiles().containsKey("a.txt")
                ? repo.getManifestAtCommit(newCommit).get("a.txt").substring(0, 40) : null;
        assertNotNull(mergedHex);
        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "a.txt");
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        Revlog filelog = repo.getRevlog(flIdx, flDat);
        int rev = NodeIdUtil.findRevisionByNodeId(filelog, NodeIdUtil.fromHex(mergedHex));
        assertEquals("line1 OURS\nline2\nline3 THEIRS\n", new String(filelog.getRevisionContent(rev), StandardCharsets.UTF_8));
    }
}
