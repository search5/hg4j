package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PathsCommand}, the porcelain equivalent of {@code hg paths}: lists all
 * registered {@code [paths]} aliases from the repository's {@code .hg/hgrc}.
 *
 * <p>Real-hg cross-check (hg 7.2, scratch repo, 2026-09-02):
 * <pre>
 * $ hg paths
 * alpha = /another/path
 * default = https://example.com/repo
 * default-push = ssh://example.com/repo
 * zeta = /some/local/path
 * </pre>
 * i.e. {@code name = url} per line, sorted alphabetically by name (see
 * {@code mercurial/utils/urlutil.py}'s {@code list_paths()}, which does
 * {@code sorted(ui.paths.items())}). A repo with no {@code [paths]} section at all prints
 * nothing and exits 0 (verified with a freshly-{@code hg init}'d scratch repo).</p>
 */
public class PathsCommandTest {

    private static void writeHgrc(File repoDir, String content) throws Exception {
        File hgrc = new File(repoDir, ".hg/hgrc");
        Files.writeString(hgrc.toPath(), content, StandardCharsets.UTF_8);
    }

    @Test
    public void testNoPathsSectionReturnsEmptyMap(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        Map<String, String> paths = new PathsCommand(repo).call();

        assertNotNull(paths);
        assertTrue(paths.isEmpty());
    }

    @Test
    public void testEmptyPathsSectionReturnsEmptyMap(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        Hg.init().setDirectory(repoDir).call();
        writeHgrc(repoDir, "[paths]\n");
        HgRepository repo = new HgRepository(repoDir);

        Map<String, String> paths = new PathsCommand(repo).call();

        assertNotNull(paths);
        assertTrue(paths.isEmpty());
    }

    @Test
    public void testSinglePath(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        Hg.init().setDirectory(repoDir).call();
        writeHgrc(repoDir, "[paths]\ndefault = https://example.com/repo\n");
        HgRepository repo = new HgRepository(repoDir);

        Map<String, String> paths = new PathsCommand(repo).call();

        assertEquals(1, paths.size());
        assertEquals("https://example.com/repo", paths.get("default"));
    }

    @Test
    public void testMultiplePathsAndHyphenatedNames(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        Hg.init().setDirectory(repoDir).call();
        writeHgrc(repoDir,
                "[paths]\n"
                        + "default = https://example.com/repo\n"
                        + "default-push = ssh://example.com/repo\n"
                        + "zeta = /some/local/path\n"
                        + "alpha = /another/path\n");
        HgRepository repo = new HgRepository(repoDir);

        Map<String, String> paths = new PathsCommand(repo).call();

        assertEquals(4, paths.size());
        assertEquals("https://example.com/repo", paths.get("default"));
        assertEquals("ssh://example.com/repo", paths.get("default-push"));
        assertEquals("/some/local/path", paths.get("zeta"));
        assertEquals("/another/path", paths.get("alpha"));
    }

    /**
     * Cross-checks against real hg's alphabetical-by-name ordering: {@code hg paths} on the
     * same four aliases prints {@code alpha, default, default-push, zeta} in that order
     * (verified against hg 7.2 on a scratch repo, see the class javadoc).
     */
    @Test
    public void testIterationOrderMatchesRealHgAlphabeticalOrder(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        Hg.init().setDirectory(repoDir).call();
        writeHgrc(repoDir,
                "[paths]\n"
                        + "zeta = /some/local/path\n"
                        + "default = https://example.com/repo\n"
                        + "alpha = /another/path\n"
                        + "default-push = ssh://example.com/repo\n");
        HgRepository repo = new HgRepository(repoDir);

        Map<String, String> paths = new PathsCommand(repo).call();

        List<String> expectedOrder = List.of("alpha", "default", "default-push", "zeta");
        assertEquals(expectedOrder, new ArrayList<>(paths.keySet()));
    }

    @Test
    public void testUnrelatedSectionsAreIgnored(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        Hg.init().setDirectory(repoDir).call();
        writeHgrc(repoDir,
                "[ui]\n"
                        + "username = Tester <tester@example.com>\n"
                        + "\n"
                        + "[paths]\n"
                        + "default = https://example.com/repo\n");
        HgRepository repo = new HgRepository(repoDir);

        Map<String, String> paths = new PathsCommand(repo).call();

        assertEquals(1, paths.size());
        assertEquals("https://example.com/repo", paths.get("default"));
    }
}
