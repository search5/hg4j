package com.github.search5.hg4j.api;

import com.github.search5.hg4j.HgTestUtils;
import com.github.search5.hg4j.core.HgRepository;
import com.github.search5.hg4j.lib.NodeId;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("interop")
public class CHgCommitWriteTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping CHgCommitWriteTest.");
    }

    @Test
    public void testHg4jCommitVsNativeRead(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        
        // 1. Initialize repo using hg4j
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repository = new HgRepository(repoDir);

        // 2. Add files and commit using hg4j
        File f1 = new File(repoDir, "hello.txt");
        Files.writeString(f1.toPath(), "Hello native hg, this was committed by hg4j!");
        
        new AddCommand(repository).call();
        
        byte[] commitNodeId = new CommitCommand(repository)
                .setAuthor("Antigravity <antigravity@google.com>")
                .setMessage("Initial commit from hg4j")
                .call();
        
        String commitHex = new com.github.search5.hg4j.lib.NodeId(commitNodeId).toHex();

        // 3. Cross-verify using native hg commands
        // Compare NodeID
        String nativeNode = HgTestUtils.hg(repoDir, "log", "-r", "tip", "--template", "{node}");
        assertEquals(commitHex, nativeNode, "NodeID must be identical in native hg");

        // Compare Author
        String nativeAuthor = HgTestUtils.hg(repoDir, "log", "-r", "tip", "--template", "{author}");
        assertEquals("Antigravity <antigravity@google.com>", nativeAuthor, "Author must match");

        // Compare Message
        String nativeDesc = HgTestUtils.hg(repoDir, "log", "-r", "tip", "--template", "{desc}");
        assertEquals("Initial commit from hg4j", nativeDesc, "Message must match");

        // Compare Content
        String nativeCat = HgTestUtils.hg(repoDir, "cat", "-r", "tip", "hello.txt");
        assertEquals("Hello native hg, this was committed by hg4j!", nativeCat, "File contents must match");

        // Verify repository integrity
        String nativeVerify = HgTestUtils.hg(repoDir, "verify");
        org.junit.jupiter.api.Assertions.assertFalse(nativeVerify.contains("integrity error"), "Saved repository contains integrity errors!\n" + nativeVerify);
    }
}
