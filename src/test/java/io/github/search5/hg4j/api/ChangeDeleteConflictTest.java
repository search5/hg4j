package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A file that one side deleted and the other changed is a conflict, as in the original ("file 'f.txt' was deleted in local but was
 * modified in other"; {@code graft --to} aborts with {@code cannot graft in memory: merge conflicts}; {@code rebase --dry-run} ends
 * with {@code hit a merge conflict}). The in-memory merge used to let the deletion win and drop the change without a word.
 */
class ChangeDeleteConflictTest {

    @TempDir
    Path tempDir;

    private HgRepository repo;
    private File dir;
    private byte[] base;
    private byte[] changed;
    private byte[] deleted;
    private byte[] untouched;

    private byte[] commit(String message) throws Exception {
        new AddCommand(repo).call();
        return new CommitCommand(repo).setMessage(message).setAuthor("t").call();
    }

    /** base has f.txt and k.txt; one branch changes f.txt, the other deletes it (and touches k.txt, so it is not empty). */
    @BeforeEach
    void history() throws Exception {
        dir = tempDir.resolve("repo").toFile();
        repo = Hg.init().setDirectory(dir).call();
        Files.writeString(new File(dir, "f.txt").toPath(), "x\n");
        Files.writeString(new File(dir, "k.txt").toPath(), "k\n");
        base = commit("base");
        Files.writeString(new File(dir, "f.txt").toPath(), "x2\n");
        changed = commit("change f");
        new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(base)).call();
        Files.writeString(new File(dir, "other.txt").toPath(), "o\n");
        untouched = commit("other");
        new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(base)).call();
        new RemoveCommand(repo).setFile("f.txt").call();
        Files.writeString(new File(dir, "k.txt").toPath(), "k2\n");
        deleted = commit("delete f");
    }

    private long changelogSize() {
        return new File(dir, ".hg/store/00changelog.i").length();
    }

    @Test
    void treeMergeReportsOursDeletedTheirsChangedAsAConflict() throws Exception {
        TreeMergeCommand.TreeMergeResult r = new TreeMergeCommand(repo).setOurs(deleted).setTheirs(changed).call();
        assertTrue(r.isConflicted());
        assertEquals(List.of("f.txt"), r.getConflicts());
    }

    @Test
    void treeMergeReportsOursChangedTheirsDeletedAsAConflict() throws Exception {
        TreeMergeCommand.TreeMergeResult r = new TreeMergeCommand(repo).setOurs(changed).setTheirs(deleted).call();
        assertTrue(r.isConflicted());
        assertEquals(List.of("f.txt"), r.getConflicts());
    }

    @Test
    void aDeletionOfAFileTheOtherSideLeftAloneStillWins() throws Exception {
        // base -> deleted removes f.txt; merging it into a head that did not touch f.txt removes the file
        TreeMergeCommand.TreeMergeResult r = new TreeMergeCommand(repo).setOurs(untouched).setTheirs(deleted).call();
        assertEquals(List.of(), r.getConflicts());
        assertTrue(r.getRemovedFiles().contains("f.txt"));
        // and the other way round: ours deleted it, theirs did not touch it: nothing to do
        TreeMergeCommand.TreeMergeResult back = new TreeMergeCommand(repo).setOurs(deleted).setTheirs(untouched).call();
        assertEquals(List.of(), back.getConflicts());
        assertTrue(back.getRemovedFiles().isEmpty());
    }

    @Test
    void graftToRefusesAChangeOntoADeletion() throws Exception {
        long before = changelogSize();
        HgValidationException e = assertThrows(HgValidationException.class, () -> new GraftInMemory(repo)
                .setTo(NodeIdUtil.toHex(deleted)).addRevision(NodeIdUtil.toHex(changed)).call());
        // the original: abort: cannot graft in memory: merge conflicts / (in-memory merge does not support file conflicts)
        assertEquals("cannot graft in memory: merge conflicts\n(in-memory merge does not support file conflicts)", e.getMessage());
        assertEquals(before, changelogSize());
    }

    @Test
    void graftToOfTwoEditsOfTheSameLineStillNeedsAMergeTool() throws Exception {
        new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(base)).call();
        Files.writeString(new File(dir, "f.txt").toPath(), "x3\n");
        byte[] other = commit("change f differently");
        HgValidationException e = assertThrows(HgValidationException.class, () -> new GraftInMemory(repo)
                .setTo(NodeIdUtil.toHex(other)).addRevision(NodeIdUtil.toHex(changed)).call());
        assertEquals("cannot graft in memory: merge conflicts\n(in-memory merge does not support external merge tools)", e.getMessage());
    }

    @Test
    void aDryRunRebaseOfAChangeOntoADeletionHitsAMergeConflictAndChangesNothing() throws Exception {
        new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(deleted)).call();
        long before = changelogSize();
        RebaseCommand rebase = new RebaseCommand(repo).addSourceRevset(NodeIdUtil.toHex(changed))
                .setDestination(NodeIdUtil.toHex(deleted)).setDryRun(true);
        rebase.call();
        assertTrue(rebase.getMessages().contains("hit a merge conflict\n"), rebase.getMessages().toString());
        assertEquals(before, changelogSize());
    }
}
