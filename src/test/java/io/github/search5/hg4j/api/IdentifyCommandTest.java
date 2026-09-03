package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
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

    @Test
    public void identifiesEmptyBranchFileContentsAsDefaultBranch(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "f.txt").toPath(), "content");
        new AddCommand(repo).addFile("f.txt").call();
        byte[] node = new CommitCommand(repo).setAuthor("A <a@example.com>").setMessage("first").call();

        // Write an empty branch file directly - bypasses BranchCommand/setBranch validation,
        // which forbids an empty name, so this is the only way to make getBranch() return "".
        File branchFile = new File(tempDir.toFile(), ".hg/branch");
        Files.writeString(branchFile.toPath(), "", StandardCharsets.UTF_8);

        String expectedHex = NodeIdUtil.toHex(node).substring(0, 12);
        assertEquals(expectedHex + " tip default", new IdentifyCommand(repo).call());
    }

    @Test
    public void identifiesNonTipParentWithoutTipMarker(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "f.txt").toPath(), "content");
        new AddCommand(repo).addFile("f.txt").call();
        byte[] firstNode = new CommitCommand(repo).setAuthor("A <a@example.com>").setMessage("first").call();

        Files.writeString(new File(tempDir.toFile(), "g.txt").toPath(), "more content");
        new AddCommand(repo).addFile("g.txt").call();
        new CommitCommand(repo).setAuthor("A <a@example.com>").setMessage("second").call();

        // Move the working copy parent back to the first (non-tip) revision.
        new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(firstNode)).call();

        String expectedHex = NodeIdUtil.toHex(firstNode).substring(0, 12);
        assertEquals(expectedHex + " default", new IdentifyCommand(repo).call());
    }

    @Test
    public void identifiesWithEmptyHgtagsFile(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "f.txt").toPath(), "content");
        new AddCommand(repo).addFile("f.txt").call();
        byte[] node = new CommitCommand(repo).setAuthor("A <a@example.com>").setMessage("first").call();

        Files.writeString(new File(tempDir.toFile(), ".hgtags").toPath(), "", StandardCharsets.UTF_8);

        String expectedHex = NodeIdUtil.toHex(node).substring(0, 12);
        assertEquals(expectedHex + " tip default", new IdentifyCommand(repo).call());
    }

    @Test
    public void identifiesSkippingBlankAndCommentLinesInHgtags(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "f.txt").toPath(), "content");
        new AddCommand(repo).addFile("f.txt").call();
        byte[] node = new CommitCommand(repo).setAuthor("A <a@example.com>").setMessage("first").call();

        String fullHex = NodeIdUtil.toHex(node);
        Files.writeString(new File(tempDir.toFile(), ".hgtags").toPath(),
                "\n   \n# a comment line\n" + fullHex + " v2.0\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        String expectedHex = fullHex.substring(0, 12);
        assertEquals(expectedHex + " v2.0 default", new IdentifyCommand(repo).call());
    }

    @Test
    public void identifiesSkippingMalformedHgtagsLine(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "f.txt").toPath(), "content");
        new AddCommand(repo).addFile("f.txt").call();
        byte[] node = new CommitCommand(repo).setAuthor("A <a@example.com>").setMessage("first").call();

        String fullHex = NodeIdUtil.toHex(node);
        // First line has no whitespace separator, so split() yields a single token and is skipped.
        Files.writeString(new File(tempDir.toFile(), ".hgtags").toPath(),
                "malformedlinewithoutatag\n" + fullHex + " v3.0\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        String expectedHex = fullHex.substring(0, 12);
        assertEquals(expectedHex + " v3.0 default", new IdentifyCommand(repo).call());
    }

    @Test
    public void identifiesReversePrefixTagAndSkipsUnrelatedTag(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "f.txt").toPath(), "content");
        new AddCommand(repo).addFile("f.txt").call();
        byte[] node = new CommitCommand(repo).setAuthor("A <a@example.com>").setMessage("first").call();

        String fullHex = NodeIdUtil.toHex(node);
        // "unrelatedTag" hex shares no prefix relation with fullHex at all (both startsWith checks false).
        // "longTag" hex is fullHex with an extra suffix, so fullHex does NOT start with it, but it
        // starts with fullHex - exercising the reverse-prefix branch of the OR condition.
        String unrelatedHex = "0000000000000000000000000000000000dead";
        String longerHex = fullHex + "extra";
        Files.writeString(new File(tempDir.toFile(), ".hgtags").toPath(),
                unrelatedHex + " unrelatedTag\n" + longerHex + " longTag\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        String expectedHex = fullHex.substring(0, 12);
        assertEquals(expectedHex + " longTag default", new IdentifyCommand(repo).call());
    }
}
