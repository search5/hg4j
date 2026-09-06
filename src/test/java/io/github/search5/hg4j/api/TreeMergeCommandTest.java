package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.NodeId;
import static io.github.search5.hg4j.lib.NodeId.NULL;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TreeMergeCommand}: a working-copy-free 3-way merge computation (JGit {@code
 * ThreeWayMerger} parity) -- takes two commit nodes, computes the merge purely from the
 * changelog/manifest/filelog store, and returns the result as data (changed/removed paths) instead
 * of writing to disk or touching dirstate. Reuses {@link MergeCommand}'s already-verified
 * LCA/criss-cross base and per-file {@link io.github.search5.hg4j.merge.Merge3} logic -- this
 * suite exercises the tree-level decision table (add/remove/keep-ours/take-theirs/3-way-merge),
 * not that underlying line-merge algorithm itself (covered by {@code Merge3Test}).
 */
public class TreeMergeCommandTest {

    private static byte[] commit(HgRepository repo, String message) throws Exception {
        new AddCommand(repo).call();
        return new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage(message).call();
    }

    private static void write(File repoDir, String name, String content) throws Exception {
        Files.writeString(new File(repoDir, name).toPath(), content);
    }

    @Test
    public void keepsOursWhenOnlyOursModifiedAFile(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "a.txt", "base\n");
        byte[] base = commit(repo, "base");

        write(repoDir, "a.txt", "ours changed\n");
        byte[] ours = commit(repo, "ours");

        // "theirs" branches from base, unrelated file, a.txt left untouched.
        Dirstate forkDirstate = repo.getDirstate();
        forkDirstate.setParents(new NodeId(base), NULL);
        repo.writeDirstate(forkDirstate);
        write(repoDir, "unrelated.txt", "x\n");
        byte[] theirs = commit(repo, "theirs");

        TreeMergeCommand.TreeMergeResult result = new TreeMergeCommand(repo).setOurs(ours).setTheirs(theirs).call();

        assertFalse(result.isConflicted());
        assertFalse(result.getChangedFiles().containsKey("a.txt"), "Ours' own unopposed change needs no delta");
        assertTrue(result.getChangedFiles().containsKey("unrelated.txt"), "Theirs' addition must appear as a change");
        assertEquals("x\n", new String(result.getChangedFiles().get("unrelated.txt"), StandardCharsets.UTF_8));
    }

    @Test
    public void takesTheirsWhenOnlyTheirsModifiedAFile(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "a.txt", "base\n");
        byte[] base = commit(repo, "base");
        byte[] ours = base;

        write(repoDir, "a.txt", "theirs changed\n");
        byte[] theirs = commit(repo, "theirs");

        TreeMergeCommand.TreeMergeResult result = new TreeMergeCommand(repo).setOurs(ours).setTheirs(theirs).call();

        assertFalse(result.isConflicted());
        assertEquals("theirs changed\n", new String(result.getChangedFiles().get("a.txt"), StandardCharsets.UTF_8));
    }

    @Test
    public void mergesNonOverlappingChangesWithoutConflict(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "a.txt", "line1\nline2\nline3\n");
        byte[] base = commit(repo, "base");

        write(repoDir, "a.txt", "line1 OURS\nline2\nline3\n");
        byte[] ours = commit(repo, "ours");

        Dirstate forkDirstate = repo.getDirstate();
        forkDirstate.setParents(new NodeId(base), NULL);
        repo.writeDirstate(forkDirstate);
        write(repoDir, "a.txt", "line1\nline2\nline3 THEIRS\n");
        byte[] theirs = commit(repo, "theirs");

        TreeMergeCommand.TreeMergeResult result = new TreeMergeCommand(repo).setOurs(ours).setTheirs(theirs).call();

        assertFalse(result.isConflicted());
        assertEquals("line1 OURS\nline2\nline3 THEIRS\n",
                new String(result.getChangedFiles().get("a.txt"), StandardCharsets.UTF_8));
    }

    @Test
    public void flagsAConflictWhenBothSidesEditTheSameLine(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "a.txt", "line1\n");
        byte[] base = commit(repo, "base");

        write(repoDir, "a.txt", "ours version\n");
        byte[] ours = commit(repo, "ours");

        Dirstate forkDirstate = repo.getDirstate();
        forkDirstate.setParents(new NodeId(base), NULL);
        repo.writeDirstate(forkDirstate);
        write(repoDir, "a.txt", "theirs version\n");
        byte[] theirs = commit(repo, "theirs");

        TreeMergeCommand.TreeMergeResult result = new TreeMergeCommand(repo).setOurs(ours).setTheirs(theirs).call();

        assertTrue(result.isConflicted());
        assertTrue(result.getConflicts().contains("a.txt"));
        assertTrue(result.getChangedFiles().containsKey("a.txt"), "A conflicted file's markers must still be returned");
    }

    @Test
    public void reportsAFileRemovedOnlyByTheirsAsRemoved(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "a.txt", "keep\n");
        write(repoDir, "gone.txt", "bye\n");
        byte[] base = commit(repo, "base");

        // "ours" leaves both files untouched but needs a distinct commit; touch an unrelated file.
        write(repoDir, "ours-only.txt", "o\n");
        byte[] ours = commit(repo, "ours");

        Dirstate forkDirstate = repo.getDirstate();
        forkDirstate.setParents(new NodeId(base), NULL);
        repo.writeDirstate(forkDirstate);
        new RemoveCommand(repo).setFile("gone.txt").call();
        byte[] theirs = commit(repo, "theirs removes gone.txt");

        TreeMergeCommand.TreeMergeResult result = new TreeMergeCommand(repo).setOurs(ours).setTheirs(theirs).call();

        assertFalse(result.isConflicted());
        assertTrue(result.getRemovedFiles().contains("gone.txt"));
        assertFalse(result.getChangedFiles().containsKey("gone.txt"));
    }

    @Test
    public void callNeverTouchesTheWorkingDirectoryOrDirstate(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "a.txt", "base\n");
        byte[] base = commit(repo, "base");

        write(repoDir, "a.txt", "ours\n");
        byte[] ours = commit(repo, "ours");

        Dirstate forkDirstate = repo.getDirstate();
        forkDirstate.setParents(new NodeId(base), NULL);
        repo.writeDirstate(forkDirstate);
        write(repoDir, "a.txt", "theirs\n");
        byte[] theirs = commit(repo, "theirs");

        byte[] parent1Before = repo.getDirstate().getParent1();
        String workingContentBefore = Files.readString(new File(repoDir, "a.txt").toPath());

        new TreeMergeCommand(repo).setOurs(ours).setTheirs(theirs).call();

        assertArrayEquals(parent1Before, repo.getDirstate().getParent1(), "Dirstate parent must be unchanged");
        assertEquals(workingContentBefore, Files.readString(new File(repoDir, "a.txt").toPath()),
                "The working copy file must be untouched by a tree-level merge computation");
    }

    @Test
    public void takesTheirsExecutableBitEvenWhenContentIsUnchanged(@TempDir Path tempDir) throws Exception {
        // Backlog #39: a flag-only change (chmod +x, identical bytes) used to be silently dropped
        // by getChangedFiles() alone -- TreeMergeResult had no way to report the new mode at all.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "run.sh", "echo hi\n");
        byte[] base = commit(repo, "base");
        byte[] ours = base;

        new File(repoDir, "run.sh").setExecutable(true, false);
        new AddCommand(repo).call();
        byte[] theirs = new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage("chmod +x").call();

        TreeMergeCommand.TreeMergeResult result = new TreeMergeCommand(repo).setOurs(ours).setTheirs(theirs).call();

        assertFalse(result.isConflicted());
        assertTrue(result.getChangedFiles().containsKey("run.sh"), "a flag-only change must still be reported as changed");
        assertEquals("echo hi\n", new String(result.getChangedFiles().get("run.sh"), StandardCharsets.UTF_8));
        assertEquals(0755, result.getChangedModes().get("run.sh"), "the new executable mode must be reported");
    }

    @Test
    public void reportsSymlinkModeForAddedSymlink(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "a.txt", "base\n");
        byte[] base = commit(repo, "base");

        write(repoDir, "ours-only.txt", "o\n");
        byte[] ours = commit(repo, "ours");

        Dirstate forkDirstate = repo.getDirstate();
        forkDirstate.setParents(new NodeId(base), NULL);
        repo.writeDirstate(forkDirstate);
        try {
            Files.createSymbolicLink(new File(repoDir, "link.txt").toPath(), new File(repoDir, "a.txt").toPath());
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }
        byte[] theirs = commit(repo, "theirs adds a symlink");

        TreeMergeCommand.TreeMergeResult result = new TreeMergeCommand(repo).setOurs(ours).setTheirs(theirs).call();

        assertFalse(result.isConflicted());
        assertTrue(result.getChangedFiles().containsKey("link.txt"));
        assertEquals(0120000, result.getChangedModes().get("link.txt"), "the symlink mode must be reported");
    }

    @Test
    public void throwsWhenOursNodeIdIsNotFound(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "a.txt", "base\n");
        byte[] theirs = commit(repo, "base");

        byte[] fakeNode = new byte[20];
        Arrays.fill(fakeNode, (byte) 1);

        HgRevisionNotFoundException ex = assertThrows(HgRevisionNotFoundException.class,
                () -> new TreeMergeCommand(repo).setOurs(fakeNode).setTheirs(theirs).call());
        assertTrue(ex.getMessage().contains("0101010101010101010101010101010101010101"));
    }

    @Test
    public void throwsWhenTheirsNodeIdIsNotFound(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "a.txt", "base\n");
        byte[] ours = commit(repo, "base");

        byte[] fakeNode = new byte[20];
        Arrays.fill(fakeNode, (byte) 2);

        HgRevisionNotFoundException ex = assertThrows(HgRevisionNotFoundException.class,
                () -> new TreeMergeCommand(repo).setOurs(ours).setTheirs(fakeNode).call());
        assertTrue(ex.getMessage().contains("0202020202020202020202020202020202020202"));
    }
}
