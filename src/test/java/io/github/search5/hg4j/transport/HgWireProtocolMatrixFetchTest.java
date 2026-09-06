package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.FetchCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.HgCommit;
import io.github.search5.hg4j.api.LogCommand;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.transport.WireMatrixCombos.HttpCombo;
import io.github.search5.hg4j.util.NodeIdUtil;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.StandardOpenOption;

/**
 * Backlog item 39, wave 5 (wire matrix track): {@link FetchCommand} is the network-sync primitive
 * {@link io.github.search5.hg4j.api.PullCommand}/{@link io.github.search5.hg4j.api.UnbundleCommand}
 * already exercise heavily as an implementation detail via their own matrix tests, but it had never
 * been given its own first-class wire-matrix coverage as a standalone command. Same 21-combo
 * matrix as {@link HgWireProtocolMatrixTest} (HTTP 18 + SSH 3), hg4j client against a real {@code
 * hg} server, exercising both the initial full fetch (empty local repo, no discovery needed) and an
 * incremental fetch (some history already local, real between/known discovery negotiation
 * exercised) in the same combo -- so both of {@link FetchCommand#call()}'s two code paths run
 * across every combo.
 */
@Tag("interop")
public class HgWireProtocolMatrixFetchTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    private File seedRepo(Path tempDir, String name) throws Exception {
        File repoDir = tempDir.resolve(name).toFile();
        repoDir.mkdirs();
        HgTestUtils.hg(repoDir, "init");
        Files.writeString(repoDir.toPath().resolve("a.txt"), "first content");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-m", "first commit", "-u", "dev");
        return repoDir;
    }

    private void runFetchScenario(File repoDir, String url, Path tempDir, String label) throws Exception {
        // Phase 1: initial fetch into an empty local repo -- no discovery needed (count == 0).
        HgRepository local = Hg.init().setDirectory(tempDir.resolve("fetch-side-" + label).toFile()).call();
        List<byte[]> imported = new FetchCommand(local).setSource(url).call();
        assertEquals(1, imported.size(), "initial fetch (label=" + label + ") must import exactly the 1 seeded commit");

        List<HgCommit> log = new LogCommand(local).call();
        assertEquals(1, log.size());
        assertEquals("first commit", log.get(0).getMessage());

        // Phase 2: incremental fetch -- a second real-hg commit lands on the server, and the
        // local repo (which already has the first commit) must discover exactly the delta via the
        // real between/known negotiation path (FetchCommand.call()'s "!upToDate" branch).
        String marker = "fetch-matrix-" + label + "-" + System.nanoTime();
        Files.writeString(repoDir.toPath().resolve(marker + ".txt"), "second commit for " + label);
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-m", marker, "-u", "dev");

        List<byte[]> incrementalImported = new FetchCommand(local).setSource(url).call();
        assertEquals(1, incrementalImported.size(), "incremental fetch (label=" + label + ") must import exactly the 1 new commit");

        List<HgCommit> logAfter = new LogCommand(local).call();
        assertEquals(2, logAfter.size());
        assertTrue(logAfter.stream().anyMatch(c -> marker.equals(c.getMessage())));

        String serverTip = HgTestUtils.hg(repoDir, "log", "-T", "{node}\n", "-r", "tip").trim();
        assertEquals(serverTip, NodeIdUtil.toHex(incrementalImported.get(0)),
                "the node FetchCommand reports as imported must match real hg's own tip node hash");
    }

    // ------------------------------------------------------------------
    // HTTP matrix: 3 tiers x 3 compression x 2 bundle2 = 18
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.github.search5.hg4j.transport.WireMatrixCombos#httpCombos")
    public void httpMatrixFetch(HttpCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = seedRepo(tempDir, "fetch-matrix-" + combo.label());
        try (HttpMatrixServer server = HttpMatrixServer.start(repoDir, combo)) {
            server.verifySanity(combo);
            runFetchScenario(repoDir, server.url, tempDir, combo.label());
        }
    }

    // ------------------------------------------------------------------
    // SSH matrix: compression only (3 combinations)
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "ssh-{0}")
    @ValueSource(strings = {"zlib", "zstd", "none"})
    public void sshMatrixFetch(String compression, @TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("ssh-fetch-matrix-" + compression).toFile();
        HgTestUtils.hg(tempDir.toFile(), "init", repoDir.getAbsolutePath());
        Files.writeString(new File(repoDir, ".hg/hgrc").toPath(),
                "[server]\ncompressionengines = " + compression + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        Files.writeString(repoDir.toPath().resolve("seed.txt"), "seed for ssh fetch " + compression);
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-m", "first commit", "-u", "dev");

        try (SshMatrixServer ssh = SshMatrixServer.start(tempDir)) {
            runFetchScenario(repoDir, ssh.url(repoDir), tempDir, "ssh-" + compression);
        }
    }
}
