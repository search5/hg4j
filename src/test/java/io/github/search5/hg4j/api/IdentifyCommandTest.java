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

/**
 * All expected strings below were verified directly against real hg 7.2.2 (2026-09-05, see
 * {@link IdentifyCommand}'s own javadoc for the full format writeup) -- the default branch is
 * never shown (only non-default branches get a parenthesized suffix), and the pseudo-tag
 * {@code "tip"} plus any real {@code .hgtags}/{@code localtags} tags are joined with {@code "/"}
 * in plain alphabetical order rather than the old ad hoc "first match wins" scan this class used
 * to do (which also, unlike real hg, tolerated hex prefixes instead of requiring an exact 40-hex
 * node reference).
 */
public class IdentifyCommandTest {

    @Test
    public void identifiesEmptyRepositoryAsNullParentTaggedTip(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        assertEquals("000000000000 tip", new IdentifyCommand(repo).call());
    }

    @Test
    public void identifiesTipOnDefaultBranchWithNoTags(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "f.txt").toPath(), "content");
        new AddCommand(repo).addFile("f.txt").call();
        byte[] node = new CommitCommand(repo).setAuthor("A <a@example.com>").setMessage("first").call();

        String expectedHex = NodeIdUtil.toHex(node).substring(0, 12);
        assertEquals(expectedHex + " tip", new IdentifyCommand(repo).call());
    }

    @Test
    public void identifiesCustomBranchNameInParentheses(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        new BranchCommand(repo).setBranchName("feature").call();
        Files.writeString(new File(tempDir.toFile(), "f.txt").toPath(), "content");
        new AddCommand(repo).addFile("f.txt").call();
        byte[] node = new CommitCommand(repo).setAuthor("A <a@example.com>").setMessage("first").call();

        String expectedHex = NodeIdUtil.toHex(node).substring(0, 12);
        assertEquals(expectedHex + " (feature) tip", new IdentifyCommand(repo).call());
    }

    @Test
    public void identifiesMatchingTagFromHgtagsFileJoinedWithTip(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "f.txt").toPath(), "content");
        new AddCommand(repo).addFile("f.txt").call();
        byte[] node = new CommitCommand(repo).setAuthor("A <a@example.com>").setMessage("first").call();

        String fullHex = NodeIdUtil.toHex(node);
        Files.writeString(new File(tempDir.toFile(), ".hgtags").toPath(), fullHex + " v1.0\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        // Alphabetical: "tip" sorts before "v1.0".
        String expectedHex = fullHex.substring(0, 12);
        assertEquals(expectedHex + " tip/v1.0", new IdentifyCommand(repo).call());
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
        assertEquals(expectedHex + " tip", new IdentifyCommand(repo).call());
    }

    @Test
    public void identifiesNonTipParentWithoutTipMarkerOrBranchSuffix(@TempDir Path tempDir) throws Exception {
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
        assertEquals(expectedHex, new IdentifyCommand(repo).call());
    }

    @Test
    public void identifiesWithEmptyHgtagsFile(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "f.txt").toPath(), "content");
        new AddCommand(repo).addFile("f.txt").call();
        byte[] node = new CommitCommand(repo).setAuthor("A <a@example.com>").setMessage("first").call();

        Files.writeString(new File(tempDir.toFile(), ".hgtags").toPath(), "", StandardCharsets.UTF_8);

        String expectedHex = NodeIdUtil.toHex(node).substring(0, 12);
        assertEquals(expectedHex + " tip", new IdentifyCommand(repo).call());
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
        assertEquals(expectedHex + " tip/v2.0", new IdentifyCommand(repo).call());
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
        assertEquals(expectedHex + " tip/v3.0", new IdentifyCommand(repo).call());
    }

    @Test
    public void identifiesIgnoringHexPrefixMismatchedHgtagsLines(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "f.txt").toPath(), "content");
        new AddCommand(repo).addFile("f.txt").call();
        byte[] node = new CommitCommand(repo).setAuthor("A <a@example.com>").setMessage("first").call();

        String fullHex = NodeIdUtil.toHex(node);
        // Real hg's .hgtags requires an exact 40-hex node reference -- unlike the old
        // implementation this class used to have, neither an unrelated node nor a >40-char line
        // (even one that is a superstring of the real node) matches by prefix.
        String unrelatedHex = "0000000000000000000000000000000000dead";
        String longerHex = fullHex + "extra";
        Files.writeString(new File(tempDir.toFile(), ".hgtags").toPath(),
                unrelatedHex + " unrelatedTag\n" + longerHex + " longTag\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        String expectedHex = fullHex.substring(0, 12);
        assertEquals(expectedHex + " tip", new IdentifyCommand(repo).call());
    }

    @Test
    public void identifiesDirtyWorkingCopyWithTrailingPlus(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "f.txt").toPath(), "content");
        new AddCommand(repo).addFile("f.txt").call();
        byte[] node = new CommitCommand(repo).setAuthor("A <a@example.com>").setMessage("first").call();

        Files.writeString(new File(tempDir.toFile(), "f.txt").toPath(), "modified");

        String expectedHex = NodeIdUtil.toHex(node).substring(0, 12);
        assertEquals(expectedHex + "+ tip", new IdentifyCommand(repo).call());
    }

    @Test
    public void identifiesUntrackedFileWithoutDirtyMarker(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "f.txt").toPath(), "content");
        new AddCommand(repo).addFile("f.txt").call();
        byte[] node = new CommitCommand(repo).setAuthor("A <a@example.com>").setMessage("first").call();

        Files.writeString(new File(tempDir.toFile(), "untracked.txt").toPath(), "not tracked");

        String expectedHex = NodeIdUtil.toHex(node).substring(0, 12);
        assertEquals(expectedHex + " tip", new IdentifyCommand(repo).call());
    }

    @Test
    public void identifiesActiveBookmark(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "f.txt").toPath(), "content");
        new AddCommand(repo).addFile("f.txt").call();
        byte[] node = new CommitCommand(repo).setAuthor("A <a@example.com>").setMessage("first").call();
        new BookmarkCommand(repo).setBookmarkName("mark1").call();

        String expectedHex = NodeIdUtil.toHex(node).substring(0, 12);
        assertEquals(expectedHex + " tip mark1", new IdentifyCommand(repo).call());
    }

    @Test
    public void setRevisionIdentifiesAFixedRevisionWithNoDirtyMarker(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "f.txt").toPath(), "content");
        new AddCommand(repo).addFile("f.txt").call();
        byte[] firstNode = new CommitCommand(repo).setAuthor("A <a@example.com>").setMessage("first").call();

        Files.writeString(new File(tempDir.toFile(), "g.txt").toPath(), "more content");
        new AddCommand(repo).addFile("g.txt").call();
        new CommitCommand(repo).setAuthor("A <a@example.com>").setMessage("second").call();
        // A dirty working copy must not affect a -r-fixed query.
        Files.writeString(new File(tempDir.toFile(), "g.txt").toPath(), "dirty edit");

        String expectedHex = NodeIdUtil.toHex(firstNode).substring(0, 12);
        assertEquals(expectedHex, new IdentifyCommand(repo).setRevision("0").call());
        assertEquals(expectedHex, new IdentifyCommand(repo).setRevision(NodeIdUtil.toHex(firstNode)).call());
    }
}
