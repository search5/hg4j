package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code hg pull} and {@code hg clone -U} never touch the working directory parent: an empty repository that is filled by them keeps
 * the null parent (no {@code .hg/dirstate} file), which is what a repository without working files needs.
 */
class PullLeavesDirstateTest {

    @TempDir
    Path tempDir;

    private File sourceDir;

    @BeforeEach
    void source() throws Exception {
        sourceDir = tempDir.resolve("source").toFile();
        HgRepository source = Hg.init().setDirectory(sourceDir).call();
        Files.writeString(new File(sourceDir, "a.txt").toPath(), "a\n");
        new AddCommand(source).call();
        new CommitCommand(source).setMessage("a").setAuthor("t").call();
    }

    private static byte[] parent(HgRepository repo) throws Exception {
        return repo.getDirstate().getParent1();
    }

    @Test
    void pullIntoAnEmptyRepositoryKeepsTheNullParent() throws Exception {
        File dir = tempDir.resolve("pulled").toFile();
        HgRepository repo = Hg.init().setDirectory(dir).call();
        assertEquals(1, new PullCommand(repo).setSource(sourceDir.getAbsolutePath()).call().size());
        assertTrue(NodeIdUtil.isAllZero(parent(new HgRepository(dir))));
        assertFalse(new File(dir, ".hg/dirstate").exists());
    }

    @Test
    void cloneWithoutUpdateLeavesNoWorkingFilesAndTheNullParent() throws Exception {
        File dir = tempDir.resolve("cloned").toFile();
        HgRepository repo = new CloneCommand().setSource(sourceDir.getAbsolutePath()).setDirectory(dir).setUpdate(false).call();
        assertFalse(new File(dir, "a.txt").exists());
        assertTrue(NodeIdUtil.isAllZero(parent(repo)));
        assertFalse(new File(dir, ".hg/dirstate").exists());
        assertEquals(1, new LogCommand(repo).call().size());
    }

    @Test
    void cloneChecksOutTheTipByDefault() throws Exception {
        File dir = tempDir.resolve("cloned").toFile();
        HgRepository repo = new CloneCommand().setSource(sourceDir.getAbsolutePath()).setDirectory(dir).call();
        assertTrue(new File(dir, "a.txt").exists());
        assertFalse(NodeIdUtil.isAllZero(parent(repo)));
    }
}
