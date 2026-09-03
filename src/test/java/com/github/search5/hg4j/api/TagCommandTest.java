package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import com.github.search5.hg4j.errors.HgValidationException;
import java.util.ArrayList;
import java.util.List;

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
    public void explicitEmptyTagNameFallsBackToListingTags(@TempDir Path tempDir) throws Exception {
        // tagName != null && !tagName.isEmpty() must take its false branch for an *explicit*
        // empty string too, not just the never-called-setTagName default (null) case that
        // testTagLifecycle/listTagsSkipsBlankAndCommentLines already exercise.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        Map<String, String> tags = new TagCommand(repo).setTagName("").call();
        assertTrue(tags.isEmpty());
    }

    @Test
    public void registeringNullHooksIsANoOp(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "a");
        new AddCommand(repo).call();
        byte[] node = new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage("c0").call();

        // Neither registration should throw or add anything that later breaks tag creation.
        Map<String, String> result = new TagCommand(repo)
                .registerPreTagHook(null)
                .registerPostTagHook(null)
                .setTagName("v1.0")
                .setNodeId(node)
                .call();
        assertEquals("v1.0", result.keySet().iterator().next());
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

    @Test
    public void preTagHookCanRejectTagCreation(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File file = new File(repoDir, "a.txt");
        Files.writeString(file.toPath(), "Content");
        new AddCommand(repo).call();
        byte[] commitNode = new CommitCommand(repo).setMessage("First commit").call();

        assertThrows(HgValidationException.class, () ->
                new TagCommand(repo)
                        .setTagName("rejected")
                        .setNodeId(commitNode)
                        .setCommit(false)
                        .registerPreTagHook(ctx -> false)
                        .call());

        // Rejected tag must not have been written.
        assertTrue(new TagCommand(repo).call().isEmpty());
    }

    @Test
    public void preAndPostTagHooksRunWithExpectedContextOnSuccessfulTag(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File file = new File(repoDir, "a.txt");
        Files.writeString(file.toPath(), "Content");
        new AddCommand(repo).call();
        byte[] commitNode = new CommitCommand(repo).setMessage("First commit").call();

        List<String> firedHooks = new ArrayList<>();
        new TagCommand(repo)
                .setTagName("v2.0")
                .setNodeId(commitNode)
                .setCommit(false)
                .registerPreTagHook(ctx -> {
                    firedHooks.add("pre:" + ctx.get("tag"));
                    return true;
                })
                .registerPostTagHook(ctx -> {
                    firedHooks.add("post:" + ctx.get("tag"));
                    return true;
                })
                .call();

        assertEquals(List.of("pre:v2.0", "post:v2.0"), firedHooks);
        assertEquals("v2.0", new TagCommand(repo).call().keySet().iterator().next());
    }

    @Test
    public void listTagsSkipsBlankAndCommentLines(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, ".hgtags").toPath(),
                "\n# a comment\n" + "a".repeat(40) + " v1.0\n\n");

        Map<String, String> tags = new TagCommand(repo).call();
        assertEquals(1, tags.size());
        assertEquals("a".repeat(40), tags.get("v1.0"));
    }

    @Test
    public void listTagsSkipsALineWithNoSpaceSeparator(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, ".hgtags").toPath(),
                "no-space-token\n" + "a".repeat(40) + " valid\n");

        Map<String, String> tags = new TagCommand(repo).call();
        assertEquals(1, tags.size());
        assertEquals("a".repeat(40), tags.get("valid"));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(2 * bytes.length);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
