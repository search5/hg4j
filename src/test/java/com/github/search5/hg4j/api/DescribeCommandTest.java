package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.lib.NodeId;
import com.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class DescribeCommandTest {

    @Test
    public void describesEmptyRepositoryWithoutAnyCommits(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();

        assertEquals("empty-repository", new DescribeCommand(repo).call());
    }

    @Test
    public void describesCurrentRevisionExactlyMatchingATag(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "f.txt").toPath(), "content");
        new AddCommand(repo).addFile("f.txt").call();
        byte[] node = new CommitCommand(repo).setAuthor("A <a@example.com>").setMessage("first").call();

        String fullHex = NodeIdUtil.toHex(node);
        Files.writeString(new File(repoDir, ".hgtags").toPath(), fullHex + " v1.0\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        assertEquals("v1.0", new DescribeCommand(repo).call());
    }

    @Test
    public void describesRevisionWithoutTagsAsV0FallbackWithDistance(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "f.txt").toPath(), "content");
        new AddCommand(repo).addFile("f.txt").call();
        byte[] node = new CommitCommand(repo).setAuthor("A <a@example.com>").setMessage("first").call();

        String expectedHex = NodeIdUtil.toHex(node).substring(0, 12);
        assertEquals("v0.0-1-g" + expectedHex, new DescribeCommand(repo).call());
    }

    @Test
    public void describesAncestorTagWithDistanceAndCurrentNodeSuffix(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        Files.writeString(new File(repoDir, "f.txt").toPath(), "v1");
        new AddCommand(repo).addFile("f.txt").call();
        byte[] firstNode = new CommitCommand(repo).setAuthor("A <a@example.com>").setMessage("first").call();

        String firstFullHex = NodeIdUtil.toHex(firstNode);
        Files.writeString(new File(repoDir, ".hgtags").toPath(), firstFullHex + " v1.0\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        Files.writeString(new File(repoDir, "f.txt").toPath(), "v2");
        new AddCommand(repo).addFile("f.txt").call();
        byte[] secondNode = new CommitCommand(repo).setAuthor("A <a@example.com>").setMessage("second").call();

        String secondHexShort = NodeIdUtil.toHex(secondNode).substring(0, 12);
        assertEquals("v1.0-1-g" + secondHexShort, new DescribeCommand(repo).call());
    }

    @Test
    public void skipsBlankCommentAndMalformedHgtagsLines(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "f.txt").toPath(), "content");
        new AddCommand(repo).addFile("f.txt").call();
        byte[] node = new CommitCommand(repo).setAuthor("A <a@example.com>").setMessage("first").call();

        String fullHex = NodeIdUtil.toHex(node);
        Files.writeString(new File(repoDir, ".hgtags").toPath(),
                "\n# a comment\n" + "onlyonetoken\n" + fullHex + " v2.0\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        assertEquals("v2.0", new DescribeCommand(repo).call());
    }

    @Test
    public void fallsBackToLatestRevisionWhenDirstateParentIsUnknown(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        Files.writeString(new File(repoDir, "f.txt").toPath(), "v1");
        new AddCommand(repo).addFile("f.txt").call();
        byte[] firstNode = new CommitCommand(repo).setAuthor("A <a@example.com>").setMessage("first").call();

        String firstFullHex = NodeIdUtil.toHex(firstNode);
        Files.writeString(new File(repoDir, ".hgtags").toPath(), firstFullHex + " v1.0\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        Files.writeString(new File(repoDir, "f.txt").toPath(), "v2");
        new AddCommand(repo).addFile("f.txt").call();
        byte[] secondNode = new CommitCommand(repo).setAuthor("A <a@example.com>").setMessage("second").call();

        byte[] bogusParent = new byte[20];
        for (int i = 0; i < bogusParent.length; i++) {
            bogusParent[i] = (byte) 0xEE;
        }
        Dirstate dirstate = repo.getDirstate();
        dirstate.setParents(new NodeId(bogusParent), NodeId.NULL);
        repo.writeDirstate(dirstate);

        String secondHexShort = NodeIdUtil.toHex(secondNode).substring(0, 12);
        assertEquals("v1.0-1-g" + secondHexShort, new DescribeCommand(repo).call());
    }
}
