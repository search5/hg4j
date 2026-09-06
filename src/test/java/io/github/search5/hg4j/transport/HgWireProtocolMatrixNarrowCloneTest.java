package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.NarrowCloneCommand;
import io.github.search5.hg4j.api.PullCommand;
import io.github.search5.hg4j.api.UpdateCommand;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.transport.WireMatrixCombos.HttpCombo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.StandardOpenOption;

/**
 * Backlog item 39, wave 5 (wire matrix track): {@link NarrowCloneCommand} across the same 21-combo
 * matrix (HTTP 18 + SSH 3) {@link HgWireProtocolMatrixTest} established for {@code Clone}/{@code
 * Pull}/{@code Push}, hg4j client against a real {@code hg} server. Backlog 28's
 * {@code NarrowCloneRealHgInteropTest} already verified narrow clone semantics against real hg's
 * own {@code narrow} extension at the default transport configuration; this fills in the remaining
 * "does the narrow-clone-specific machinery (requires/narrowspec writing, tree-filtered checkout,
 * then a subsequent narrow-scoped incremental pull) survive every other tier/compression/bundle2
 * combo the wire protocol allows" gap that was explicitly left open in that class's javadoc.
 */
@Tag("interop")
public class HgWireProtocolMatrixNarrowCloneTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    private File seedRepo(Path tempDir, String name) throws Exception {
        File repoDir = tempDir.resolve(name).toFile();
        repoDir.mkdirs();
        HgTestUtils.hg(repoDir, "init");
        writeFile(repoDir, "srcdir/A.java", "class A");
        writeFile(repoDir, "srcdir/sub/B.java", "class B");
        writeFile(repoDir, "docs/readme.txt", "doc readme");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-m", "first commit", "-u", "dev");
        return repoDir;
    }

    private void runNarrowCloneScenario(File repoDir, String url, Path tempDir, String label) throws Exception {
        File destDir = tempDir.resolve("narrow-dest-" + label).toFile();
        Hg.narrowClone()
                .setSource(url)
                .setDirectory(destDir)
                .addIncludePath("srcdir")
                .addExcludePath("srcdir/sub")
                .call();

        assertTrue(new File(destDir, "srcdir/A.java").exists(), "in-scope file must be checked out, combo=" + label);
        assertFalse(new File(destDir, "srcdir/sub").exists(), "excluded subdir must not be checked out, combo=" + label);
        assertFalse(new File(destDir, "docs").exists(), "out-of-scope dir must not be checked out, combo=" + label);

        File requiresFile = new File(destDir, ".hg/requires");
        assertTrue(Files.readString(requiresFile.toPath()).contains("narrowhg-experimental"),
                "requires file must record the narrow requirement, combo=" + label);

        // Incremental narrow-scoped pull: an in-scope and an out-of-scope file both land on the
        // server; the plain pull (no explicit treeFilter) must keep respecting the stored
        // narrowspec across every wire combo, not just the default one.
        writeFile(repoDir, "srcdir/C.java", "class C");
        writeFile(repoDir, "docs/more.txt", "more docs");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-m", "second commit " + label, "-u", "dev");

        HgRepository destRepo = new HgRepository(destDir);
        new PullCommand(destRepo).setSource(url).call();
        new UpdateCommand(destRepo).call();

        assertTrue(new File(destDir, "srcdir/C.java").exists(),
                "new in-scope file must be checked out after a plain narrow-scoped pull, combo=" + label);
        assertFalse(new File(destDir, "docs/more.txt").exists(),
                "new out-of-scope file must NOT be checked out, combo=" + label);
    }

    private static void writeFile(File repoDir, String relPath, String content) throws Exception {
        File f = new File(repoDir, relPath);
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), content);
    }

    // ------------------------------------------------------------------
    // HTTP matrix: 3 tiers x 3 compression x 2 bundle2 = 18
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.github.search5.hg4j.transport.WireMatrixCombos#httpCombos")
    public void httpMatrixNarrowClone(HttpCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = seedRepo(tempDir, "narrow-matrix-" + combo.label());
        try (HttpMatrixServer server = HttpMatrixServer.start(repoDir, combo)) {
            server.verifySanity(combo);
            runNarrowCloneScenario(repoDir, server.url, tempDir, combo.label());
        }
    }

    // ------------------------------------------------------------------
    // SSH matrix: compression only (3 combinations)
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "ssh-{0}")
    @ValueSource(strings = {"zlib", "zstd", "none"})
    public void sshMatrixNarrowClone(String compression, @TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("ssh-narrow-matrix-" + compression).toFile();
        HgTestUtils.hg(tempDir.toFile(), "init", repoDir.getAbsolutePath());
        Files.writeString(new File(repoDir, ".hg/hgrc").toPath(),
                "[server]\ncompressionengines = " + compression + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        writeFile(repoDir, "srcdir/A.java", "class A");
        writeFile(repoDir, "srcdir/sub/B.java", "class B");
        writeFile(repoDir, "docs/readme.txt", "doc readme");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-m", "first commit", "-u", "dev");

        try (SshMatrixServer ssh = SshMatrixServer.start(tempDir)) {
            runNarrowCloneScenario(repoDir, ssh.url(repoDir), tempDir, "ssh-" + compression);
        }
    }
}
