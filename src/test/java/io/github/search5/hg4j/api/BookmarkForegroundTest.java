package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Moving a bookmark without -f: forward in the history, or onto the successor of an obsolete changeset. */
class BookmarkForegroundTest {

    @TempDir
    Path tempDir;

    private byte[] commit(HgRepository repo, String content, String message) throws Exception {
        Files.writeString(new File(tempDir.toFile(), "a.txt").toPath(), content);
        return new CommitCommand(repo).setMessage(message).setAuthor("t").call();
    }

    @Test
    void aBookmarkMovesOntoTheAmendedSuccessorButNotOntoAnUnrelatedSibling() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "a.txt").toPath(), "base\n");
        new AddCommand(repo).call();
        commit(repo, "base\n", "base");
        byte[] c1 = commit(repo, "one\n", "one");
        new BookmarkCommand(repo).setBookmarkName("work").setNodeId(c1).call();

        byte[] amended = new AmendCommand(repo).setAuthor("t").setMessage("one, amended").call();
        // the amended changeset is a sibling of c1, reachable only through the obsolescence marker
        Map<String, String> moved = new BookmarkCommand(repo).setBookmarkName("work").setNodeId(amended).call();
        assertEquals(NodeIdUtil.toHex(amended), moved.get("work"));

        // a changeset that neither descends from it nor succeeds it is refused
        byte[] other = new byte[20];
        other[0] = 1;
        assertThrows(Exception.class, () -> new BookmarkCommand(repo).setBookmarkName("work").setNodeId(other).call());
        // a plain descendant needs no marker at all
        byte[] next = commit(repo, "two\n", "two");
        assertEquals(NodeIdUtil.toHex(next), new BookmarkCommand(repo).setBookmarkName("work").setNodeId(next).call().get("work"));
    }

    @Test
    void movingBackToTheAncestorIsRefusedWhenNothingObsoletesIt() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "a.txt").toPath(), "base\n");
        new AddCommand(repo).call();
        byte[] c0 = commit(repo, "base\n", "base");
        byte[] c1 = commit(repo, "one\n", "one");
        new BookmarkCommand(repo).setBookmarkName("work").setNodeId(c1).call();
        HgValidationException refused = assertThrows(HgValidationException.class,
                () -> new BookmarkCommand(repo).setBookmarkName("work").setNodeId(c0).call());
        assertEquals("bookmark 'work' already exists (use -f to force)", refused.getMessage());
    }
}
