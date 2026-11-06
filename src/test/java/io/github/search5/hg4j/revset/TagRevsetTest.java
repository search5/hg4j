package io.github.search5.hg4j.revset;

import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.PullCommand;
import io.github.search5.hg4j.api.TagCommand;
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

/** {@code tag()} follows the tags {@code hg tags} lists: the heads' {@code .hgtags}, removals, local tags; names are case sensitive. */
class TagRevsetTest {

    @TempDir
    Path tempDir;

    private File srcDir;
    private HgRepository src;
    private byte[] first;

    @BeforeEach
    void history() throws Exception {
        srcDir = tempDir.resolve("src").toFile();
        src = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a").toPath(), "a\n");
        new AddCommand(src).call();
        first = new CommitCommand(src).setMessage("a").setAuthor("t").call();
        Files.writeString(new File(srcDir, "a").toPath(), "b\n");
        new CommitCommand(src).setMessage("b").setAuthor("t").call();
        new TagCommand(src).setTagName("v1").setNodeId(first).call();
    }

    private HgRepository server() throws Exception {
        HgRepository server = Hg.init().setDirectory(tempDir.resolve("server").toFile()).call();
        new PullCommand(server).setSource(srcDir.getAbsolutePath()).call();
        return server;
    }

    private static List<Integer> q(HgRepository repo, String revset) throws Exception {
        return new HgRevsetEngine(repo).query(revset);
    }

    @Test
    void aRepositoryWithoutWorkingFilesFindsTheTag() throws Exception {
        assertEquals(List.of(0), q(server(), "tag(\"v1\")"));
    }

    @Test
    void aRemovedTagIsNotFound() throws Exception {
        new TagCommand(src).setTagName("v1").setRemove(true).call();
        assertEquals(List.of(), q(src, "tag(\"v1\")"));
        assertEquals(List.of(), q(server(), "tag(\"v1\")"));
    }

    @Test
    void aMovedTagIsFoundOnlyAtItsLastNode() throws Exception {
        new TagCommand(src).setTagName("v1").setNodeId(NodeIdUtil.fromHex(NodeIdUtil.toHex(first))).setForce(true).call();
        byte[] second = src.getRevlog(new File(srcDir, ".hg/store/00changelog.i"), new File(srcDir, ".hg/store/00changelog.d"))
                .getIndexRecord(1).getNodeId();
        new TagCommand(src).setTagName("v1").setNodeId(second).setForce(true).call();
        assertEquals(List.of(1), q(src, "tag(\"v1\")"));
        assertEquals(List.of(1), q(server(), "tag(\"v1\")"));
    }

    @Test
    void aLocalTagIsFound() throws Exception {
        HgRepository server = server();
        Files.writeString(new File(server.getHgDir(), "localtags").toPath(), NodeIdUtil.toHex(first) + " mine\n");
        assertEquals(List.of(0), q(server, "tag(\"mine\")"));
    }

    @Test
    void theNameIsCaseSensitive() throws Exception {
        assertEquals(List.of(), q(src, "tag(\"V1\")"));
    }

    @Test
    void reAndNoArgumentSelectTheTagsOfTheListing() throws Exception {
        assertEquals(List.of(0), q(server(), "tag(\"re:^v\")"));
        assertEquals(List.of(), q(server(), "tag(\"re:^x\")"));
        assertEquals(List.of(0), q(server(), "tag()"));
    }
}
