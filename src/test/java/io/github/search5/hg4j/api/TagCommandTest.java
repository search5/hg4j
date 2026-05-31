package io.github.search5.hg4j.api;

import io.github.search5.hg4j.core.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class TagCommandTest {

    @Test
    public void testTagLifecycle(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Initial tag list should be empty
        Map<String, String> initialTags = new TagCommand(repo).call();
        assertTrue(initialTags.isEmpty());

        // 2. Commit a changeset
        File file = new File(repoDir, "a.txt");
        Files.writeString(file.toPath(), "Content");
        new AddCommand(repo).call();
        byte[] commitNode = new CommitCommand(repo).setMessage("First commit").call();

        // 3. Create a tag "v1.0" for this commit
        Map<String, String> created = new TagCommand(repo)
                .setTagName("v1.0")
                .setNodeId(commitNode)
                .setCommit(true)
                .call();
        assertEquals(1, created.size());
        
        // Verify .hgtags was created on disk
        File tagsFile = new File(repoDir, ".hgtags");
        assertTrue(tagsFile.exists());

        // 4. List tags and verify
        Map<String, String> tags = new TagCommand(repo).call();
        assertEquals(1, tags.size());
        
        String hexNode = toHex(commitNode).substring(0, 40);
        assertEquals(hexNode, tags.get("v1.0"));

        // 5. Verify the tag creation committed itself
        // History should now have 2 commits (Initial commit + Tag commit)
        assertEquals(2, new LogCommand(repo).call().size());
    }

    @Test
    public void testCreateTagWithoutCommitting(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File file = new File(repoDir, "a.txt");
        Files.writeString(file.toPath(), "Content");
        new AddCommand(repo).call();
        byte[] commitNode = new CommitCommand(repo).setMessage("First commit").call();

        // Create tag without committing
        new TagCommand(repo)
                .setTagName("v1.0-alpha")
                .setNodeId(commitNode)
                .setCommit(false)
                .call();

        // History remains at 1 commit
        assertEquals(1, new LogCommand(repo).call().size());

        // But tag is successfully registered
        Map<String, String> tags = new TagCommand(repo).call();
        assertEquals("v1.0-alpha", tags.keySet().iterator().next());
    }

    @Test
    public void testInvalidTagParameters(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        assertThrows(IllegalArgumentException.class, () -> 
                new TagCommand(repo).setTagName("v1.0").call()); // no nodeId

        assertThrows(IllegalArgumentException.class, () -> 
                new TagCommand(repo).setTagName("v1.0").setNodeId(new byte[10]).call()); // short nodeId
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(2 * bytes.length);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
