package com.github.search5.hg4j.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.github.search5.hg4j.lib.HgRepository;
import java.nio.charset.StandardCharsets;

public class HgNarrowCloneTest {

    @TempDir
    File tempDir;

    @Test
    public void testNarrowCloneRequirementsAndSpecGeneration() throws Exception {
        File srcRepoDir = new File(tempDir, "src_repo");
        File destRepoDir = new File(tempDir, "narrow_repo");
        
        // 1. Initialize source repository and write files in different dirs
        HgRepository srcRepo = Hg.init().setDirectory(srcRepoDir).call();
        try (Hg hgSrc = Hg.wrap(srcRepo)) {
            File f1 = new File(srcRepoDir, "src/main/A.java");
            f1.getParentFile().mkdirs();
            Files.writeString(f1.toPath(), "Class A");

            File f2 = new File(srcRepoDir, "docs/readme.txt");
            f2.getParentFile().mkdirs();
            Files.writeString(f2.toPath(), "Doc readme");

            hgSrc.add().addFile("src/main/A.java").addFile("docs/readme.txt").call();
            hgSrc.commit().setAuthor("Tester").setMessage("init commit").call();
        }

        // 2. Perform narrow clone including only "src/"
        Hg hgNarrow = Hg.narrowClone()
                .setSource(srcRepoDir.getAbsolutePath())
                .setDirectory(destRepoDir)
                .addIncludePath("src/")
                .call();

        assertNotNull(hgNarrow);
        
        // 3. Verify narrowspec file and requires specifications
        File narrowSpecFile = new File(destRepoDir, ".hg/narrowspec");
        assertTrue(narrowSpecFile.exists());

        String specText = Files.readString(narrowSpecFile.toPath(), StandardCharsets.UTF_8);
        assertTrue(specText.contains("[includes]"));
        assertTrue(specText.contains("src/"));

        File requiresFile = new File(destRepoDir, ".hg/requires");
        String requiresText = Files.readString(requiresFile.toPath(), StandardCharsets.UTF_8);
        assertTrue(requiresText.contains("narrowspec"));
    }

    @Test
    public void testNarrowCloneWithExcludePathsAndIgnoredNulls() throws Exception {
        File srcRepoDir = new File(tempDir, "src_repo2");
        File destRepoDir = new File(tempDir, "narrow_repo2");

        HgRepository srcRepo = Hg.init().setDirectory(srcRepoDir).call();
        try (Hg hgSrc = Hg.wrap(srcRepo)) {
            File f1 = new File(srcRepoDir, "src/main/A.java");
            f1.getParentFile().mkdirs();
            Files.writeString(f1.toPath(), "Class A");

            File f2 = new File(srcRepoDir, "docs/readme.txt");
            f2.getParentFile().mkdirs();
            Files.writeString(f2.toPath(), "Doc readme");

            hgSrc.add().addFile("src/main/A.java").addFile("docs/readme.txt").call();
            hgSrc.commit().setAuthor("Tester").setMessage("init commit").call();
        }

        Hg hgNarrow = Hg.narrowClone()
                .setSource(srcRepoDir.getAbsolutePath())
                .setDirectory(destRepoDir)
                .addIncludePath("src/")
                .addIncludePath(null)
                .addExcludePath("docs/")
                .addExcludePath(null)
                .call();

        assertNotNull(hgNarrow);

        File narrowSpecFile = new File(destRepoDir, ".hg/narrowspec");
        String specText = Files.readString(narrowSpecFile.toPath(), StandardCharsets.UTF_8);
        assertTrue(specText.contains("[excludes]"));
        assertTrue(specText.contains("docs/"));
    }

    @Test
    public void testCallThrowsWhenSourceIsNull() {
        File destRepoDir = new File(tempDir, "narrow_repo3");
        NarrowCloneCommand cmd = Hg.narrowClone().setDirectory(destRepoDir);
        assertThrows(IllegalStateException.class, cmd::call);
    }

    @Test
    public void testCallThrowsWhenDirectoryIsNull() {
        NarrowCloneCommand cmd = Hg.narrowClone().setSource("http://example.invalid/repo");
        assertThrows(IllegalStateException.class, cmd::call);
    }
}
