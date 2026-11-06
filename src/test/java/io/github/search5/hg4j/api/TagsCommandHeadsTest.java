package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code hg tags} reads the {@code .hgtags} of every head of the repository, not the working copy. A repository that was filled by
 * push or pull has no working files, so only this reading shows its tags.
 */
class TagsCommandHeadsTest {

    @TempDir
    Path tempDir;

    private HgRepository source;
    private File sourceDir;
    private byte[] first;
    private byte[] second;

    private byte[] commit(HgRepository repo, File dir, String file, String content, String message) throws Exception {
        Files.writeString(new File(dir, file).toPath(), content);
        new AddCommand(repo).call();
        return new CommitCommand(repo).setMessage(message).setAuthor("t").call();
    }

    /** first, second, then {@code hg tag v1} on first (the tag commit is the tip). */
    private void taggedSource() throws Exception {
        sourceDir = tempDir.resolve("source").toFile();
        source = Hg.init().setDirectory(sourceDir).call();
        first = commit(source, sourceDir, "a.txt", "a\n", "first");
        second = commit(source, sourceDir, "a.txt", "a2\n", "second");
        new TagCommand(source).setTagName("v1").setNodeId(first).call();
    }

    private HgRepository pulled() throws Exception {
        File dir = tempDir.resolve("server").toFile();
        HgRepository server = Hg.init().setDirectory(dir).call();
        new PullCommand(server).setSource(sourceDir.getAbsolutePath()).call();
        return server;
    }

    private static List<String> names(List<TagsCommand.Tag> tags) {
        return tags.stream().map(TagsCommand.Tag::getName).collect(Collectors.toList());
    }

    @Test
    void aRepositoryWithoutWorkingFilesShowsOnlyTipByDefault() throws Exception {
        taggedSource();
        assertEquals(List.of("tip"), names(new TagsCommand(pulled()).call()));
    }

    @Test
    void readingFromTheHeadsFindsTheTagsOfARepositoryWithoutWorkingFiles() throws Exception {
        taggedSource();
        List<TagsCommand.Tag> tags = new TagsCommand(pulled()).setReadFromHeads(true).call();
        assertEquals(List.of("tip", "v1"), names(tags));
        TagsCommand.Tag v1 = tags.get(1);
        assertEquals(NodeIdUtil.toHex(first), NodeIdUtil.toHex(v1.getNode()));
        assertEquals(0, v1.getRev());
        assertFalse(v1.isLocal());
    }

    @Test
    void theRevisionGivenIsTheOnlyOneRead() throws Exception {
        taggedSource();
        HgRepository server = pulled();
        // the second changeset has no .hgtags yet, the tag commit (tip) has
        assertEquals(List.of("tip"), names(new TagsCommand(server).setRevision(NodeIdUtil.toHex(second)).call()));
        assertEquals(List.of("tip", "v1"), names(new TagsCommand(server).setRevision("tip").call()));
    }

    @Test
    void theHeadsAreReadOldestFirstAndTheLaterLineWins() throws Exception {
        taggedSource();
        // a second head from `second`, which moves v1 to itself and adds w
        new UpdateCommand(source).setRevision(NodeIdUtil.toHex(second)).call();
        byte[] other = commit(source, sourceDir, "b.txt", "b\n", "other head");
        Files.writeString(new File(sourceDir, ".hgtags").toPath(),
                NodeIdUtil.toHex(other) + " v1\n" + NodeIdUtil.toHex(second) + " w\n");
        new AddCommand(source).call();
        byte[] head2 = new CommitCommand(source).setMessage("tags on head 2").setAuthor("t").call();

        List<TagsCommand.Tag> tags = new TagsCommand(pulledFrom(source)).setReadFromHeads(true).call();
        assertTrue(names(tags).containsAll(List.of("tip", "v1", "w")), names(tags).toString());
        // the older head (the first tag commit) is read first, the newer one (head2) after it: its v1 line is later, so it wins
        TagsCommand.Tag v1 = tags.stream().filter(t -> t.getName().equals("v1")).findFirst().orElseThrow();
        assertEquals(NodeIdUtil.toHex(other), NodeIdUtil.toHex(v1.getNode()));
        assertEquals(NodeIdUtil.toHex(head2), NodeIdUtil.toHex(tags.get(0).getNode()));
    }

    @Test
    void aRemovedTagAndAnUnknownNodeAreLeftOut() throws Exception {
        taggedSource();
        new TagCommand(source).setTagName("v1").setRemove(true).call();
        assertEquals(List.of("tip"), names(new TagsCommand(pulledFrom(source)).setReadFromHeads(true).call()));
    }

    private HgRepository pulledFrom(HgRepository src) throws Exception {
        File dir = tempDir.resolve("server-" + System.nanoTime()).toFile();
        HgRepository server = Hg.init().setDirectory(dir).call();
        new PullCommand(server).setSource(src.getDirectory().getAbsolutePath()).call();
        return server;
    }

    @Test
    void localTagsStillComeFromTheLocalFileWhenReadingFromTheHeads() throws Exception {
        taggedSource();
        HgRepository server = pulled();
        Files.writeString(new File(server.getHgDir(), "localtags").toPath(), NodeIdUtil.toHex(second) + " mine\n");
        List<TagsCommand.Tag> tags = new TagsCommand(server).setReadFromHeads(true).call();
        assertEquals(List.of("tip", "mine", "v1").size(), tags.size());
        assertTrue(tags.stream().filter(t -> t.getName().equals("mine")).findFirst().orElseThrow().isLocal());
    }

    @Test
    void theWorkingCopyReadingIsUnchangedWhenTheOptionIsNotSet() throws Exception {
        taggedSource();
        assertEquals(List.of("tip", "v1"), names(new TagsCommand(source).call()));
    }
}
