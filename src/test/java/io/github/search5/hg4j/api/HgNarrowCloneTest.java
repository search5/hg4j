package io.github.search5.hg4j.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import io.github.search5.hg4j.lib.HgRepository;
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

        // 3. Verify narrowspec file and requires specifications match real hg 7.2 (verified
        // against the host's native "hg --config extensions.narrow= clone --narrow ..."):
        // the authoritative narrowspec lives at .hg/store/narrowspec (not .hg/narrowspec), uses
        // singular "[include]"/"[exclude]" sections, and patterns are normalized to the
        // "path:" kind with the trailing "/" stripped. The requirement recorded in
        // .hg/requires is "narrowhg-experimental", not "narrowspec".
        File narrowSpecFile = new File(destRepoDir, ".hg/store/narrowspec");
        assertTrue(narrowSpecFile.exists());

        String specText = Files.readString(narrowSpecFile.toPath(), StandardCharsets.UTF_8);
        assertEquals("[include]\npath:src\n", specText);

        File dirstateSpecFile = new File(destRepoDir, ".hg/narrowspec.dirstate");
        assertTrue(dirstateSpecFile.exists());
        assertEquals(specText, Files.readString(dirstateSpecFile.toPath(), StandardCharsets.UTF_8));

        File requiresFile = new File(destRepoDir, ".hg/requires");
        String requiresText = Files.readString(requiresFile.toPath(), StandardCharsets.UTF_8);
        assertTrue(requiresText.contains("narrowhg-experimental"));
        assertFalse(requiresText.contains("narrowspec\n"), "real hg's requirement key is narrowhg-experimental, not narrowspec");
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

        File narrowSpecFile = new File(destRepoDir, ".hg/store/narrowspec");
        String specText = Files.readString(narrowSpecFile.toPath(), StandardCharsets.UTF_8);
        assertEquals("[include]\npath:src\n[exclude]\npath:docs\n", specText);

        assertTrue(new File(destRepoDir, "src/main/A.java").exists());
        assertFalse(new File(destRepoDir, "docs/readme.txt").exists(),
                "docs/ is excluded so it must not be materialized in the narrow working copy");
    }

    /**
     * Regression test for a real bug found while auditing against real hg 7.2 (backlog 28):
     * hg4j's include matching used to be a naive {@code String#startsWith}, so an include of
     * "srcdir" would incorrectly also match a sibling directory like "srcdirextra" (no
     * component-boundary check). Real hg's narrowspec "path:" matching never does this --
     * confirmed by narrow-cloning a repo containing both "srcdir/" and "srcdirextra/" against
     * real hg with {@code --include srcdir}, where "srcdirextra/x.txt" was correctly excluded.
     */
    @Test
    public void testNarrowCloneIncludeRespectsPathComponentBoundary() throws Exception {
        File srcRepoDir = new File(tempDir, "src_repo_boundary");
        File destRepoDir = new File(tempDir, "narrow_repo_boundary");

        HgRepository srcRepo = Hg.init().setDirectory(srcRepoDir).call();
        try (Hg hgSrc = Hg.wrap(srcRepo)) {
            File f1 = new File(srcRepoDir, "srcdir/A.java");
            f1.getParentFile().mkdirs();
            Files.writeString(f1.toPath(), "Class A");

            File f2 = new File(srcRepoDir, "srcdirextra/x.txt");
            f2.getParentFile().mkdirs();
            Files.writeString(f2.toPath(), "extra");

            hgSrc.add().addFile("srcdir/A.java").addFile("srcdirextra/x.txt").call();
            hgSrc.commit().setAuthor("Tester").setMessage("init commit").call();
        }

        Hg.narrowClone()
                .setSource(srcRepoDir.getAbsolutePath())
                .setDirectory(destRepoDir)
                .addIncludePath("srcdir")
                .call();

        assertTrue(new File(destRepoDir, "srcdir/A.java").exists());
        assertFalse(new File(destRepoDir, "srcdirextra/x.txt").exists(),
                "include=srcdir must not match the sibling directory srcdirextra (component boundary)");
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
