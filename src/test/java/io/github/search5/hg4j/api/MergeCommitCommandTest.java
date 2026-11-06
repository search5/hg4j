package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The inputs {@link MergeCommitCommand} accepts and refuses, and what a refusal leaves behind, with hg4j alone. */
class MergeCommitCommandTest {

    @TempDir
    Path tempDir;

    private File dir;
    private HgRepository repo;
    private byte[] base;
    private byte[] left;
    private byte[] right;

    private byte[] commit(String file, String content, String message) throws Exception {
        Files.writeString(new File(dir, file).toPath(), content);
        new AddCommand(repo).call();
        return new CommitCommand(repo).setMessage(message).setAuthor("t").call();
    }

    /** base, then two heads: left changes a.txt, right adds b.txt. */
    private void twoHeads() throws Exception {
        dir = tempDir.resolve("repo").toFile();
        repo = Hg.init().setDirectory(dir).call();
        base = commit("a.txt", "a\n", "base");
        left = commit("a.txt", "a left\n", "left");
        new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(base)).call();
        right = commit("b.txt", "b\n", "right");
    }

    private MergeCommitCommand command() {
        return new MergeCommitCommand(repo).setP1(left).setP2(right).setUser("t").setMessage("merge");
    }

    private long changelogSize() {
        return new File(dir, ".hg/store/00changelog.i").length();
    }

    @Test
    void theMergeHasTheGivenParentsInOrderAndTheMergedTree() throws Exception {
        twoHeads();
        MergeCommitResult result = command().setP1(NodeIdUtil.toHex(left)).setP2(NodeIdUtil.toHex(right)).call();

        assertFalse(result.isConflicted());
        assertTrue(result.getConflicts().isEmpty());
        assertNotNull(result.getNode());
        assertEquals(NodeIdUtil.toHex(result.getNode()), result.getNodeHex());
        var changelog = repo.getRevlog(new File(dir, ".hg/store/00changelog.i"), new File(dir, ".hg/store/00changelog.d"));
        var tip = changelog.getIndexRecord(changelog.getRevisionCount() - 1);
        assertEquals(changelog.findRevision(left), tip.getParent1());
        assertEquals(changelog.findRevision(right), tip.getParent2());
        var files = new TreeCommand(repo).setNodeId(new io.github.search5.hg4j.lib.NodeId(result.getNode())).call();
        assertEquals(java.util.List.of("a.txt", "b.txt"), files.stream().map(TreeCommand.TreeEntry::getPath).toList());
    }

    @Test
    void theBaseCanBeGivenAsNodeOrAsRevision() throws Exception {
        twoHeads();
        assertFalse(command().setBase(base).call().isConflicted());
        assertFalse(command().setBase(NodeIdUtil.toHex(base)).setMessage("again").call().isConflicted());
    }

    @Test
    void aMissingUserOrMessageIsRefusedBeforeAnythingIsWritten() throws Exception {
        twoHeads();
        long before = changelogSize();
        assertThrows(HgValidationException.class, () -> command().setUser(" ").call());
        assertThrows(HgValidationException.class, () -> command().setUser("a\nb").call());
        assertThrows(HgValidationException.class, () -> command().setMessage(" \n ").call());
        assertThrows(HgValidationException.class, () -> command().setMessage(null).call());
        assertEquals(before, changelogSize());
    }

    @Test
    void theSameChangesetOnBothSidesAndAMissingParentAreRefused() throws Exception {
        twoHeads();
        assertThrows(HgValidationException.class, () -> command().setP2(left).call());
        assertThrows(HgValidationException.class, () -> new MergeCommitCommand(repo).setP1(left).setUser("t").setMessage("m").call());
        assertThrows(HgRevisionNotFoundException.class, () -> command().setP2(new byte[20]).call());
    }

    @Test
    void anAncestorIsRefusedAndNothingIsWritten() throws Exception {
        twoHeads();
        long before = changelogSize();
        assertThrows(HgValidationException.class, () -> command().setP1(base).setP2(left).call());
        assertThrows(HgValidationException.class, () -> command().setP1(left).setP2(base).call());
        assertEquals(before, changelogSize());
        // with the option, the ancestor on either side is merged in
        assertFalse(command().setP1(left).setP2(base).setAllowAncestorMerge(true).call().isConflicted());
    }

    @Test
    void aDateThatIsNotSecondsAndOffsetIsRefusedAndNothingIsWritten() throws Exception {
        twoHeads();
        long before = changelogSize();
        assertThrows(HgValidationException.class, () -> command().setDate("yesterday").call());
        assertThrows(HgValidationException.class, () -> command().setDate("1700000000 west").call());
        assertEquals(before, changelogSize());
        assertFalse(command().setDate("1700000000 -32400").call().isConflicted());
    }

    @Test
    void theBranchKeyIsNotAnExtra() throws Exception {
        twoHeads();
        assertThrows(IllegalArgumentException.class, () -> command().putExtra("branch", "x"));
    }

    @Test
    void aConflictIsAResultWithNoNodeAndNothingIsWritten() throws Exception {
        dir = tempDir.resolve("conflict").toFile();
        repo = Hg.init().setDirectory(dir).call();
        base = commit("f.txt", "line\n", "base");
        left = commit("f.txt", "left\n", "left");
        new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(base)).call();
        right = commit("f.txt", "right\n", "right");
        long before = changelogSize();

        MergeCommitResult result = command().call();

        assertTrue(result.isConflicted());
        assertNull(result.getNode());
        assertNull(result.getNodeHex());
        assertEquals(java.util.List.of("f.txt"), result.getConflicts());
        assertEquals(before, changelogSize());
    }

    @Test
    void theNodeIsACopy() throws Exception {
        twoHeads();
        MergeCommitResult result = command().call();
        byte[] node = result.getNode();
        node[0] ^= 1;
        assertArrayEquals(NodeIdUtil.fromHex(result.getNodeHex()), result.getNode());
    }
}
