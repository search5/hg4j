package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class IdentifyCommandTest {

    @Test
    public void identifiesEmptyRepositoryAsNullParentOnDefaultBranch(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        assertEquals("000000000000 default", new IdentifyCommand(repo).call());
    }

    @Test
    public void identifiesTipOnDefaultBranchWithNoTags(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "f.txt").toPath(), "content");
        new AddCommand(repo).addFile("f.txt").call();
        byte[] node = new CommitCommand(repo).setAuthor("A <a@example.com>").setMessage("first").call();

        String expectedHex = NodeIdUtil.toHex(node).substring(0, 12);
        assertEquals(expectedHex + " tip default", new IdentifyCommand(repo).call());
    }

    @Test
    public void identifiesCustomBranchName(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        new BranchCommand(repo).setBranchName("feature").call();
        Files.writeString(new File(tempDir.toFile(), "f.txt").toPath(), "content");
        new AddCommand(repo).addFile("f.txt").call();
        byte[] node = new CommitCommand(repo).setAuthor("A <a@example.com>").setMessage("first").call();

        String expectedHex = NodeIdUtil.toHex(node).substring(0, 12);
        assertEquals(expectedHex + " tip feature", new IdentifyCommand(repo).call());
    }

    @Test
    public void identifiesMatchingTagFromHgtagsFile(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "f.txt").toPath(), "content");
        new AddCommand(repo).addFile("f.txt").call();
        byte[] node = new CommitCommand(repo).setAuthor("A <a@example.com>").setMessage("first").call();

        String fullHex = NodeIdUtil.toHex(node);
        Files.writeString(new File(tempDir.toFile(), ".hgtags").toPath(), fullHex + " v1.0\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        String expectedHex = fullHex.substring(0, 12);
        assertEquals(expectedHex + " v1.0 default", new IdentifyCommand(repo).call());
    }
}
