package com.github.search5.hg4j.api;

import com.github.search5.hg4j.core.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BranchCommandTest {

    @Test
    public void testBranchLifecycle(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Initial active branch should be "default"
        String initialBranch = new BranchCommand(repo).call();
        assertEquals("default", initialBranch);

        // 2. Set new branch name "feature-x"
        String setBranch = new BranchCommand(repo).setBranchName("feature-x").call();
        assertEquals("feature-x", setBranch);

        // Check active branch via getter
        String activeBranch = new BranchCommand(repo).call();
        assertEquals("feature-x", activeBranch);

        // 3. Create a file and commit on "feature-x"
        File file = new File(repoDir, "hello.txt");
        Files.writeString(file.toPath(), "Hello on branch!");
        new AddCommand(repo).call();

        byte[] node = new CommitCommand(repo)
                .setAuthor("Tester <tester@example.com>")
                .setMessage("Commit on feature branch")
                .call();
        assertNotNull(node);

        // 4. Verify commit log includes branch name
        List<HgCommit> history = new LogCommand(repo).call();
        assertEquals(1, history.size());
        assertEquals("feature-x", history.get(0).getBranch());

        // 5. Switch back to "default" and commit
        new BranchCommand(repo).setBranchName("default").call();
        assertEquals("default", new BranchCommand(repo).call());

        File file2 = new File(repoDir, "world.txt");
        Files.writeString(file2.toPath(), "Hello on default!");
        new AddCommand(repo).call();

        byte[] node2 = new CommitCommand(repo)
                .setAuthor("Tester <tester@example.com>")
                .setMessage("Commit on default branch")
                .call();
        assertNotNull(node2);

        List<HgCommit> history2 = new LogCommand(repo).call();
        assertEquals(2, history2.size());
        // Newest commit at index 0 (should be "default")
        assertEquals("default", history2.get(0).getBranch());
        // Older commit at index 1 (should be "feature-x")
        assertEquals("feature-x", history2.get(1).getBranch());
    }

    @Test
    public void testInvalidBranchName(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        assertThrows(IllegalArgumentException.class, () -> new BranchCommand(repo).setBranchName("").call());
    }
}
