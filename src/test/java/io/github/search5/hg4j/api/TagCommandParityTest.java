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
 * {@code hg tag} as the original does it ({@code commands.tag} and {@code tags._tag}); the expectations were recorded from real hg 7.2:
 * a moved or removed tag writes the node it had before as a line of its own first, the names and targets are checked, a global tag
 * is committed from a branch head only, and a changed {@code .hgtags} stops the command.
 */
class TagCommandParityTest {

    private static final String ZERO = "0".repeat(40);

    @TempDir
    Path tempDir;

    private HgRepository repo;
    private File dir;
    private byte[] c0;
    private byte[] c1;

    private byte[] commit(String content, String message) throws Exception {
        Files.writeString(new File(dir, "a.txt").toPath(), content);
        new AddCommand(repo).call();
        return new CommitCommand(repo).setMessage(message).setAuthor("t").call();
    }

    @BeforeEach
    void history() throws Exception {
        dir = tempDir.resolve("repo").toFile();
        repo = Hg.init().setDirectory(dir).call();
        c0 = commit("a\n", "c0");
        c1 = commit("b\n", "c1");
    }

    private static String hex(byte[] node) {
        return NodeIdUtil.toHex(node).substring(0, 40);
    }

    private List<String> hgtags() throws Exception {
        return Files.readAllLines(new File(dir, ".hgtags").toPath());
    }

    private HgValidationException refused(TagCommand command) {
        return assertThrows(HgValidationException.class, command::call);
    }

    private TagCommand tag(String name, byte[] node) {
        return new TagCommand(repo).setTagName(name).setNodeId(node);
    }

    @Test
    void aMovedTagWritesItsOldNodeFirst() throws Exception {
        tag("v1", c0).call();
        tag("v1", c1).setForce(true).call();
        assertEquals(List.of(hex(c0) + " v1", hex(c0) + " v1", hex(c1) + " v1"), hgtags());
    }

    @Test
    void aRemovedTagWritesItsOldNodeThenTheNullNode() throws Exception {
        tag("v1", c1).call();
        new TagCommand(repo).setTagName("v1").setRemove(true).call();
        assertEquals(List.of(hex(c1) + " v1", hex(c1) + " v1", ZERO + " v1"), hgtags());
        assertTrue(new LogCommand(repo).call().stream().anyMatch(c -> c.getMessage().strip().equals("Removed tag v1")));
    }

    @Test
    void aTagAddedAfterItsRemovalWritesTheNullNodeFirst() throws Exception {
        tag("v1", c1).call();
        new TagCommand(repo).setTagName("v1").setRemove(true).call();
        tag("v1", c1).call();
        assertEquals(List.of(hex(c1) + " v1", hex(c1) + " v1", ZERO + " v1", ZERO + " v1", hex(c1) + " v1"), hgtags());
    }

    @Test
    void aTagLineIsStartedOnANewLineWhenTheFileDidNotEndWithOne() throws Exception {
        Files.writeString(new File(dir, ".hgtags").toPath(), hex(c0) + " old");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("hgtags").setAuthor("t").call();
        tag("v1", c1).call();
        assertEquals(List.of(hex(c0) + " old", hex(c1) + " v1"), hgtags());
    }

    @Test
    void removingATagThatIsNotThereOrIsAlreadyRemovedIsRefused() throws Exception {
        assertEquals("tag 'zzz' does not exist", refused(new TagCommand(repo).setTagName("zzz").setRemove(true)).getMessage());
        tag("v1", c1).call();
        new TagCommand(repo).setTagName("v1").setRemove(true).call();
        assertEquals("tag 'v1' is already removed", refused(new TagCommand(repo).setTagName("v1").setRemove(true)).getMessage());
    }

    @Test
    void aLocalTagCannotBeRemovedAsAGlobalOneAndTheOtherWayRound() throws Exception {
        tag("loc", c0).setLocal(true).call();
        assertEquals("tag 'loc' is not a global tag", refused(new TagCommand(repo).setTagName("loc").setRemove(true)).getMessage());
        tag("glob", c0).call();
        assertEquals("tag 'glob' is not a local tag",
                refused(new TagCommand(repo).setTagName("glob").setRemove(true).setLocal(true)).getMessage());
    }

    @Test
    void aMovedLocalTagWritesItsOldNodeFirstToo() throws Exception {
        tag("loc", c0).setLocal(true).call();
        tag("loc", c1).setLocal(true).setForce(true).call();
        assertEquals(List.of(hex(c0) + " loc", hex(c0) + " loc", hex(c1) + " loc"),
                Files.readAllLines(new File(dir, ".hg/localtags").toPath()));
    }

    @Test
    void reservedAndMalformedNamesAreRefused() {
        assertEquals("the name 'tip' is reserved", refused(tag("tip", c1)).getMessage());
        assertEquals("the name 'null' is reserved", refused(tag("null", c1)).getMessage());
        assertEquals("the name '.' is reserved", refused(tag(".", c1)).getMessage());
        assertEquals("cannot use an integer as a name", refused(tag("12", c1)).getMessage());
        assertEquals("':' cannot be used in a name", refused(tag("a:b", c1)).getMessage());
        assertEquals("'\\n' cannot be used in a name", refused(tag("a\nb", c1)).getMessage());
        assertEquals("tag names cannot consist entirely of whitespace", refused(tag("  ", c1)).getMessage());
    }

    @Test
    void theNameIsStripped() throws Exception {
        tag("  v1 ", c1).call();
        assertEquals(List.of(hex(c1) + " v1"), hgtags());
    }

    @Test
    void theNullRevisionCannotBeTagged() {
        assertEquals("cannot tag null revision", refused(tag("v1", new byte[20])).getMessage());
    }

    @Test
    void aGlobalTagIsCommittedFromABranchHeadOnlyUnlessForced() throws Exception {
        new UpdateCommand(repo).setRevision(hex(c0)).call();
        assertEquals("working directory is not at a branch head (use -f to force)", refused(tag("v1", c0)).getMessage());
        tag("v1", c0).setForce(true).call();
        assertEquals(List.of(hex(c0) + " v1"), hgtags());
    }

    @Test
    void aLocalTagIsNotBoundToABranchHead() throws Exception {
        new UpdateCommand(repo).setRevision(hex(c0)).call();
        tag("loc", c0).setLocal(true).call();
        assertEquals(List.of(hex(c0) + " loc"), Files.readAllLines(new File(dir, ".hg/localtags").toPath()));
    }

    @Test
    void aRepositoryWithoutAWorkingCopyCannotTagLikeTheOriginal() throws Exception {
        File serverDir = tempDir.resolve("server").toFile();
        HgRepository server = Hg.init().setDirectory(serverDir).call();
        new PullCommand(server).setSource(dir.getAbsolutePath()).call();
        HgValidationException e = assertThrows(HgValidationException.class,
                () -> new TagCommand(server).setTagName("v1").setNodeId(c0).call());
        assertEquals("working directory is not at a branch head (use -f to force)", e.getMessage());
    }

    @Test
    void aChangedHgtagsStopsTheCommandBeforeAnythingIsWritten() throws Exception {
        tag("v1", c0).call();
        Files.writeString(new File(dir, ".hgtags").toPath(), "edited\n");
        String before = Files.readString(new File(dir, ".hgtags").toPath());
        assertEquals("working copy of .hgtags is changed", refused(tag("v2", c1)).getMessage());
        assertEquals(before, Files.readString(new File(dir, ".hgtags").toPath()));
        assertTrue(new LogCommand(repo).call().size() == 3);
    }
}
