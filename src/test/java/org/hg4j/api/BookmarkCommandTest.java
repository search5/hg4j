package org.hg4j.api;

import org.hg4j.core.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class BookmarkCommandTest {

    @Test
    public void testBookmarkLifecycle(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Initial bookmarks should be empty
        Map<String, String> initial = new BookmarkCommand(repo).call();
        assertTrue(initial.isEmpty());
        assertNull(new BookmarkCommand(repo).getActiveBookmark());

        // 2. Commit a changeset
        File file = new File(repoDir, "a.txt");
        Files.writeString(file.toPath(), "Content");
        new AddCommand(repo).call();
        byte[] commitNode = new CommitCommand(repo).setMessage("First commit").call();

        // 3. Create bookmark "feature-x" (should default to commitNode since it is parent 1)
        Map<String, String> created = new BookmarkCommand(repo).setBookmarkName("feature-x").call();
        assertEquals(1, created.size());
        
        String hexNode = toHex(commitNode).substring(0, 40);
        assertEquals(hexNode, created.get("feature-x"));

        // 4. Activate bookmark "feature-x"
        new BookmarkCommand(repo).setBookmarkName("feature-x").setActive(true).call();
        assertEquals("feature-x", new BookmarkCommand(repo).getActiveBookmark());

        // 5. Create another bookmark "feature-y"
        new BookmarkCommand(repo).setBookmarkName("feature-y").call();
        Map<String, String> list = new BookmarkCommand(repo).call();
        assertEquals(2, list.size());
        assertEquals(hexNode, list.get("feature-y"));

        // 6. Delete bookmark "feature-x"
        new BookmarkCommand(repo).setBookmarkName("feature-x").setDelete(true).call();
        Map<String, String> postDelete = new BookmarkCommand(repo).call();
        assertEquals(1, postDelete.size());
        assertFalse(postDelete.containsKey("feature-x"));
        assertTrue(postDelete.containsKey("feature-y"));

        // Verify active bookmark was deleted since active bookmark "feature-x" was deleted
        assertNull(new BookmarkCommand(repo).getActiveBookmark());
    }

    @Test
    public void testBookmarkCommandExceptions(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        assertThrows(IllegalArgumentException.class, () -> 
                new BookmarkCommand(repo).setDelete(true).call()); // no name for delete

        assertThrows(IllegalArgumentException.class, () -> 
                new BookmarkCommand(repo).setBookmarkName("nonexistent").setActive(true).call()); // activate nonexistent
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(2 * bytes.length);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
