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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link GraftInMemory#execute()}: the outcome of the graft as a value (conflicts, the new changesets in order). */
class GraftInMemoryResultTest {

    @TempDir
    Path tempDir;

    private HgRepository repo;
    private File dir;
    private byte[] base;
    private byte[] c1;
    private byte[] c2;
    private byte[] target;
    private byte[] clash;

    private byte[] commit(String file, String content, String message) throws Exception {
        Files.writeString(new File(dir, file).toPath(), content);
        new AddCommand(repo).call();
        return new CommitCommand(repo).setMessage(message).setAuthor("t").call();
    }

    /** base; a branch with c1 (adds x.txt) and c2 (adds y.txt); target (adds t.txt) and clash (changes a.txt differently from c3). */
    @BeforeEach
    void history() throws Exception {
        dir = tempDir.resolve("repo").toFile();
        repo = Hg.init().setDirectory(dir).call();
        base = commit("a.txt", "a\n", "base");
        c1 = commit("x.txt", "x\n", "c1");
        c2 = commit("y.txt", "y\n", "c2");
        new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(base)).call();
        target = commit("t.txt", "t\n", "target");
        new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(base)).call();
        clash = commit("a.txt", "clash\n", "clash");
    }

    private long changelogSize() {
        return new File(dir, ".hg/store/00changelog.i").length();
    }

    private GraftInMemory graft(byte[] to, byte[]... sources) {
        GraftInMemory g = new GraftInMemory(repo).setTo(NodeIdUtil.toHex(to)).setUser("u").setDate("1700000000 0");
        for (byte[] s : sources) {
            g.addRevision(NodeIdUtil.toHex(s));
        }
        return g;
    }

    @Test
    void theNewChangesetsAreReturnedInOrder() throws Exception {
        GraftResult result = graft(target, c1, c2).execute();
        assertFalse(result.isConflicted());
        assertTrue(result.getConflicts().isEmpty());
        assertEquals(2, result.getNodes().size());
        var changelog = repo.getRevlog(new File(dir, ".hg/store/00changelog.i"), new File(dir, ".hg/store/00changelog.d"));
        assertEquals(changelog.findRevision(target), changelog.getIndexRecord(changelog.findRevision(result.getNodes().get(0))).getParent1());
        assertEquals(changelog.findRevision(result.getNodes().get(0)),
                changelog.getIndexRecord(changelog.findRevision(result.getNodes().get(1))).getParent1());
        assertEquals(NodeIdUtil.toHex(result.getNodes().get(1)), result.getNodeHexes().get(1));
    }

    @Test
    void theNodesAreTheOnesCallReturns() throws Exception {
        // the same graft twice gives the same changesets (same user and date): the second finds them already there
        GraftResult result = graft(target, c1, c2).execute();
        List<String> again = graft(target, c1, c2).call();
        assertEquals(result.getNodeHexes(), again);
    }

    @Test
    void aConflictIsAResultAndNothingIsWritten() throws Exception {
        new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(base)).call();
        byte[] edit = commit("a.txt", "edit\n", "edit a");
        long before = changelogSize();
        GraftResult result = graft(clash, edit).execute();
        assertTrue(result.isConflicted());
        assertEquals(List.of("a.txt"), result.getConflicts());
        assertTrue(result.getNodes().isEmpty());
        assertEquals(before, changelogSize());
    }

    @Test
    void aConflictAfterAGoodGraftRollsTheGoodOneBackToo() throws Exception {
        new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(base)).call();
        byte[] edit = commit("a.txt", "edit\n", "edit a");
        long before = changelogSize();
        GraftResult result = graft(clash, c1, edit).execute();
        assertTrue(result.isConflicted());
        assertTrue(result.getNodes().isEmpty());
        assertEquals(before, changelogSize());
    }

    @Test
    void aDryRunSaysWhetherTheGraftWorksAndWritesNothing() throws Exception {
        long before = changelogSize();
        GraftResult ok = graft(target, c1, c2).setDryRun(true).execute();
        assertFalse(ok.isConflicted());
        assertEquals(2, ok.getNodes().size());
        assertEquals(before, changelogSize());

        new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(base)).call();
        byte[] edit = commit("a.txt", "edit\n", "edit a");
        long before2 = changelogSize();
        GraftResult bad = graft(clash, edit).setDryRun(true).execute();
        assertTrue(bad.isConflicted());
        assertEquals(List.of("a.txt"), bad.getConflicts());
        assertEquals(before2, changelogSize());
    }

    @Test
    void callStillThrowsForAConflictAndStillOnlyPrintsOnADryRun() throws Exception {
        new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(base)).call();
        byte[] edit = commit("a.txt", "edit\n", "edit a");
        assertThrows(HgValidationException.class, () -> graft(clash, edit).call());
        GraftInMemory dry = graft(clash, edit).setDryRun(true);
        assertEquals(List.of(), dry.call());
    }

    @Test
    void usageErrorsStayExceptions() {
        assertThrows(IllegalStateException.class, () -> new GraftInMemory(repo).addRevision("0").execute());
        assertThrows(HgValidationException.class, () -> new GraftInMemory(repo).setTo("0").execute());
    }
}
