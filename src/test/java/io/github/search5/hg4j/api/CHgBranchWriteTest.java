package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.core.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("interop")
public class CHgBranchWriteTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping CHgBranchWriteTest.");
    }

    @Test
    public void testHg4jBranchVsNativeRead(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        
        // 1. Initialize repo using hg4j
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repository = new HgRepository(repoDir);

        // 2. Set active branch name in hg4j
        File f1 = new File(repoDir, "hello.txt");
        Files.writeString(f1.toPath(), "Content");
        new AddCommand(repository).call();

        // Write custom branch
        new BranchCommand(repository).setBranchName("feature-custom-branch").call();
        
        new CommitCommand(repository)
                .setAuthor("Antigravity <antigravity@google.com>")
                .setMessage("Commit in custom branch")
                .call();

        // 3. Verify in native hg
        String nativeBranch = HgTestUtils.hg(repoDir, "log", "-r", "tip", "--template", "{branch}");
        assertEquals("feature-custom-branch", nativeBranch, "Branch metadata must match native hg");

        // Verify repository integrity
        String nativeVerify = HgTestUtils.hg(repoDir, "verify");
        org.junit.jupiter.api.Assertions.assertFalse(nativeVerify.contains("integrity error"), "Saved repository contains integrity errors!\n" + nativeVerify);
    }
}
