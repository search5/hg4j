package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code hg tags}-equivalent listing, verified directly against real hg 7.2.2 on scratch repos
 * (2026-09-02). See {@link TagsCommand}'s class Javadoc for the full list of behaviors verified.
 */
public class TagsCommandTest {

    @Test
    public void emptyRepositoryHasOnlyTheImplicitTipPseudoTag(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();

        List<TagsCommand.Tag> tags = new TagsCommand(repo).call();
        assertEquals(1, tags.size());
        TagsCommand.Tag tip = tags.get(0);
        assertEquals("tip", tip.getName());
        assertEquals(-1, tip.getRev());
        assertTrue(NodeIdUtil.isAllZero(tip.getNode()));
        assertFalse(tip.isLocal());
    }

    @Test
    public void singleTagIsListedAlongsideTip(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "content");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage("c0").call();

        new TagCommand(repo).setTagName("v1.0").setNodeId(c0).call(); // setCommit(true) by default, advances tip

        List<TagsCommand.Tag> tags = new TagsCommand(repo).call();
        Map<String, TagsCommand.Tag> byName = tags.stream()
                .collect(Collectors.toMap(TagsCommand.Tag::getName, t -> t));

        assertEquals(2, tags.size());
        assertEquals(NodeIdUtil.toHex(c0), NodeIdUtil.toHex(byName.get("v1.0").getNode()));
        assertEquals(0, byName.get("v1.0").getRev());
        assertFalse(byName.get("v1.0").isLocal());
        assertEquals(1, byName.get("tip").getRev(), "the tag commit itself becomes the new tip");
        // Real hg orders `hg tags` output with the highest revision first.
        assertEquals("tip", tags.get(0).getName());
        assertEquals("v1.0", tags.get(1).getName());
    }

    @Test
    public void multipleTagsOnSameRevisionAreOrderedReverseAlphabetically(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "content");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage("c0").call();

        String hex = NodeIdUtil.toHex(c0);
        Files.writeString(new File(repoDir, ".hgtags").toPath(),
                hex + " zeta\n" + hex + " alpha\n", StandardCharsets.UTF_8);

        // A second commit advances tip past rev 0, isolating the same-revision tie-break between
        // "zeta" and "alpha" -- exactly the scenario verified against real hg 7.2.2: two tags on
        // one revision (rev 0) are listed "zeta" before "alpha" (reverse alphabetical), tip listed
        // separately as the highest revision.
        Files.writeString(new File(repoDir, "b.txt").toPath(), "b");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage("c1").call();

        List<TagsCommand.Tag> tags = new TagsCommand(repo).call();
        assertEquals(List.of("tip", "zeta", "alpha"),
                tags.stream().map(TagsCommand.Tag::getName).collect(Collectors.toList()));
    }

    @Test
    public void reassigningATagToALaterRevisionMakesTheLatestHgtagsLineWin(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "a");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage("c0").call();

        Files.writeString(new File(repoDir, "b.txt").toPath(), "b");
        new AddCommand(repo).call();
        byte[] c1 = new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage("c1").call();

        Files.writeString(new File(repoDir, ".hgtags").toPath(),
                NodeIdUtil.toHex(c0) + " alpha\n" + NodeIdUtil.toHex(c1) + " alpha\n", StandardCharsets.UTF_8);

        List<TagsCommand.Tag> tags = new TagsCommand(repo).call();
        Map<String, TagsCommand.Tag> byName = tags.stream()
                .collect(Collectors.toMap(TagsCommand.Tag::getName, t -> t));

        assertEquals(2, tags.size(), "only one 'alpha' entry must survive -- the last line wins");
        assertEquals(1, byName.get("alpha").getRev());
        assertEquals(NodeIdUtil.toHex(c1), NodeIdUtil.toHex(byName.get("alpha").getNode()));
    }

    @Test
    public void localTagsAppearAlongsideGlobalTagsAndAreFlaggedLocal(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "a");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage("c0").call();

        Files.writeString(new File(repoDir, ".hgtags").toPath(), NodeIdUtil.toHex(c0) + " global1\n", StandardCharsets.UTF_8);
        Files.writeString(new File(repo.getHgDir(), "localtags").toPath(), NodeIdUtil.toHex(c0) + " local1\n", StandardCharsets.UTF_8);

        List<TagsCommand.Tag> tags = new TagsCommand(repo).call();
        Map<String, TagsCommand.Tag> byName = tags.stream()
                .collect(Collectors.toMap(TagsCommand.Tag::getName, t -> t));

        assertEquals(3, tags.size()); // tip, global1, local1
        assertFalse(byName.get("global1").isLocal());
        assertTrue(byName.get("local1").isLocal());
        assertFalse(byName.get("tip").isLocal());
    }

    @Test
    public void localTagOverridesGlobalTagOfTheSameName(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "a");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage("c0").call();

        Files.writeString(new File(repoDir, "b.txt").toPath(), "b");
        new AddCommand(repo).call();
        byte[] c1 = new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage("c1").call();

        Files.writeString(new File(repoDir, ".hgtags").toPath(), NodeIdUtil.toHex(c0) + " shared\n", StandardCharsets.UTF_8);
        Files.writeString(new File(repo.getHgDir(), "localtags").toPath(), NodeIdUtil.toHex(c1) + " shared\n", StandardCharsets.UTF_8);

        List<TagsCommand.Tag> tags = new TagsCommand(repo).call();
        Map<String, TagsCommand.Tag> byName = tags.stream()
                .collect(Collectors.toMap(TagsCommand.Tag::getName, t -> t));

        assertEquals(2, tags.size(), "the local definition must win, not add a second 'shared' entry");
        assertTrue(byName.get("shared").isLocal());
        assertEquals(1, byName.get("shared").getRev());
        assertEquals(NodeIdUtil.toHex(c1), NodeIdUtil.toHex(byName.get("shared").getNode()));
    }

    @Test
    public void tagPointingAtNullNodeIsExcluded(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "a");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage("c0").call();

        Files.writeString(new File(repoDir, ".hgtags").toPath(), "0".repeat(40) + " deleted\n", StandardCharsets.UTF_8);

        List<TagsCommand.Tag> tags = new TagsCommand(repo).call();
        assertTrue(tags.stream().noneMatch(t -> t.getName().equals("deleted")));
    }

    @Test
    public void tagPointingAtUnknownNodeIsExcluded(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "a");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage("c0").call();

        Files.writeString(new File(repoDir, ".hgtags").toPath(), "d".repeat(40) + " ghost\n", StandardCharsets.UTF_8);

        List<TagsCommand.Tag> tags = new TagsCommand(repo).call();
        assertTrue(tags.stream().noneMatch(t -> t.getName().equals("ghost")));
    }

    @Test
    public void handWrittenTagNamedTipIsIgnoredInFavorOfTheRealTip(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "a");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage("c0").call();

        Files.writeString(new File(repoDir, "b.txt").toPath(), "b");
        new AddCommand(repo).call();
        byte[] c1 = new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage("c1").call();

        // real `hg tag tip` itself aborts ("the name 'tip' is reserved"); a hand-edited .hgtags
        // line naming "tip" is the only way to produce this, and real hg still ignores it.
        Files.writeString(new File(repoDir, ".hgtags").toPath(), NodeIdUtil.toHex(c0) + " tip\n", StandardCharsets.UTF_8);

        List<TagsCommand.Tag> tags = new TagsCommand(repo).call();
        long tipCount = tags.stream().filter(t -> t.getName().equals("tip")).count();
        assertEquals(1, tipCount, "there must be exactly one 'tip' entry");
        TagsCommand.Tag tip = tags.stream().filter(t -> t.getName().equals("tip")).findFirst().orElseThrow();
        assertEquals(1, tip.getRev());
        assertEquals(NodeIdUtil.toHex(c1), NodeIdUtil.toHex(tip.getNode()));
    }

    @Test
    public void blankAndCommentLinesInHgtagsAreSkipped(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "a");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage("c0").call();

        Files.writeString(new File(repoDir, ".hgtags").toPath(),
                "\n# a comment\n" + NodeIdUtil.toHex(c0) + " v1.0\n\n", StandardCharsets.UTF_8);

        List<TagsCommand.Tag> tags = new TagsCommand(repo).call();
        Map<String, TagsCommand.Tag> byName = tags.stream()
                .collect(Collectors.toMap(TagsCommand.Tag::getName, t -> t));
        assertEquals(2, tags.size());
        assertEquals(0, byName.get("v1.0").getRev());
    }

    @Test
    public void hgtagsEntryInARepositoryWithNoCommitsIsExcludedSinceChangelogIsNull(@TempDir Path tempDir) throws Exception {
        // A repository with zero commits has no 00changelog.i at all, so `changelog` in call() is
        // null -- a stray .hgtags file (hand-written, never actually committed) referencing a node
        // must still be safely excluded rather than NPE-ing on a null changelog lookup.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        byte[] fakeNode = new byte[20];
        java.util.Arrays.fill(fakeNode, (byte) 0xCD);
        Files.writeString(new File(repoDir, ".hgtags").toPath(),
                NodeIdUtil.toHex(fakeNode) + " v0.1\n", StandardCharsets.UTF_8);

        List<TagsCommand.Tag> tags = new TagsCommand(repo).call();
        assertEquals(1, tags.size(), "only the implicit tip pseudo-tag should remain: " + tags);
        assertEquals("tip", tags.get(0).getName());
    }

    @Test
    public void malformedHgtagsLinesAreSkippedWithoutThrowing(@TempDir Path tempDir) throws Exception {
        // readTagFile()'s tolerant parser (mirroring real hg's own leniency) must skip: a line
        // with no space separator at all (spaceIdx == -1), a line whose name part is blank after
        // the space, and a line whose hex part isn't exactly 40 characters -- none of these are
        // exercised by the other .hgtags-driven tests, which only ever feed well-formed lines.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "a");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage("c0").call();

        String hex = NodeIdUtil.toHex(c0);
        Files.writeString(new File(repoDir, ".hgtags").toPath(),
                "no-space-at-all-on-this-line\n"
                        + hex + " \n"
                        + "abcd v1.0\n"
                        + hex + " valid\n",
                StandardCharsets.UTF_8);

        List<TagsCommand.Tag> tags = new TagsCommand(repo).call();
        Map<String, TagsCommand.Tag> byName = tags.stream()
                .collect(Collectors.toMap(TagsCommand.Tag::getName, t -> t));
        assertEquals(2, tags.size(), "only \"tip\" and \"valid\" should have been parsed: " + byName.keySet());
        assertTrue(byName.containsKey("valid"));
    }
}
