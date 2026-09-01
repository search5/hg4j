package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.github.search5.hg4j.util.NodeIdUtil;

public class SubrepoCommandTest {

    @Test
    public void callThrowsWhenActionNotSet(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        assertThrows(IllegalArgumentException.class, () -> new SubrepoCommand(repo).call());
    }

    @Test
    public void addThrowsWhenPathOrUrlMissing(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        assertThrows(IllegalArgumentException.class,
                () -> new SubrepoCommand(repo).setAction("add").call());
        assertThrows(IllegalArgumentException.class,
                () -> new SubrepoCommand(repo).setAction("add").setSubrepoPath("sub").call());
    }

    @Test
    public void addAppendsToHgsubAndHgsubstateWithExplicitRevision(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        String rev40 = "a".repeat(40);

        new SubrepoCommand(repo)
                .setAction("add")
                .setSubrepoPath("libs/sub1")
                .setSubrepoUrl("https://example.invalid/sub1")
                .setRevision(rev40)
                .call();

        List<String> hgsub = Files.readAllLines(new File(tempDir.toFile(), ".hgsub").toPath(), StandardCharsets.UTF_8);
        assertEquals(List.of("libs/sub1 = https://example.invalid/sub1"), hgsub);

        List<String> hgsubstate = Files.readAllLines(new File(tempDir.toFile(), ".hgsubstate").toPath(), StandardCharsets.UTF_8);
        assertEquals(List.of(rev40 + " libs/sub1"), hgsubstate);
    }

    @Test
    public void addWithoutRevisionDefaultsToNullNode(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();

        new SubrepoCommand(repo)
                .setAction("add")
                .setSubrepoPath("sub2")
                .setSubrepoUrl("https://example.invalid/sub2")
                .call();

        List<String> hgsubstate = Files.readAllLines(new File(tempDir.toFile(), ".hgsubstate").toPath(), StandardCharsets.UTF_8);
        assertEquals(List.of("0".repeat(40) + " sub2"), hgsubstate);
    }

    @Test
    public void initIsNoOpWhenNoHgsubFileExists(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        // Must not throw even though there is nothing to initialize.
        new SubrepoCommand(repo).setAction("init").call();
        new SubrepoCommand(repo).setAction("update").call();
    }

    @Test
    public void initClonesSubrepoAndPinsRevisionFromHgsubstate(@TempDir Path tempDir) throws Exception {
        // Prepare a real hg4j repository to act as the subrepo source.
        File subSourceDir = tempDir.resolve("sub-source").toFile();
        HgRepository subSourceRepo = Hg.init().setDirectory(subSourceDir).call();
        File subFile = new File(subSourceDir, "hello.txt");
        Files.writeString(subFile.toPath(), "hello from subrepo");
        new AddCommand(subSourceRepo).addFile("hello.txt").call();
        new CommitCommand(subSourceRepo).setMessage("sub first commit").setAuthor("Sub <sub@example.com>").call();
        String subTipNode = new LogCommand(subSourceRepo).call().get(0).getNodeId().toHex();

        // Parent repo declares the subrepo pinned to that exact revision.
        File parentDir = tempDir.resolve("parent").toFile();
        HgRepository parentRepo = Hg.init().setDirectory(parentDir).call();
        Files.writeString(new File(parentDir, ".hgsub").toPath(), "vendor/sub = " + subSourceDir.getAbsolutePath() + "\n");
        Files.writeString(new File(parentDir, ".hgsubstate").toPath(), subTipNode + " vendor/sub\n");

        new SubrepoCommand(parentRepo).setAction("init").call();

        File clonedSubDir = new File(parentDir, "vendor/sub");
        assertTrue(clonedSubDir.exists(), "Subrepo should have been cloned into the parent working directory");
        assertEquals("hello from subrepo", Files.readString(new File(clonedSubDir, "hello.txt").toPath()));

        HgRepository clonedSubRepo = new HgRepository(clonedSubDir);
        assertEquals(subTipNode, NodeIdUtil.toHex(clonedSubRepo.getDirstate().getParent1()));
    }

    @Test
    public void initSkipsCloneWhenSubrepoDirectoryAlreadyExists(@TempDir Path tempDir) throws Exception {
        File subSourceDir = tempDir.resolve("sub-source").toFile();
        Hg.init().setDirectory(subSourceDir).call();

        File parentDir = tempDir.resolve("parent").toFile();
        HgRepository parentRepo = Hg.init().setDirectory(parentDir).call();
        File alreadyThere = new File(parentDir, "vendor/sub");
        assertTrue(alreadyThere.mkdirs());
        File marker = new File(alreadyThere, "already-here.txt");
        Files.writeString(marker.toPath(), "untouched");

        Files.writeString(new File(parentDir, ".hgsub").toPath(), "vendor/sub = " + subSourceDir.getAbsolutePath() + "\n");

        new SubrepoCommand(parentRepo).setAction("update").call();

        assertTrue(marker.exists(), "Existing subrepo directory content must be left untouched, not re-cloned over");
        assertFalse(new File(alreadyThere, ".hg").exists(), "No clone should have happened since the directory already existed");
    }
}
