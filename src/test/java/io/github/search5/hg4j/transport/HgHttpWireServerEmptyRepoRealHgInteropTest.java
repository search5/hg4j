package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.lib.HgRepository;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for a bug found by actually cloning a real, freshly-created (zero-commit,
 * {@code hg init}-equivalent) Mercurial repository served by hg4j: {@link
 * HgLocalClient#getBundle(java.util.List, java.util.List, java.util.List,
 * HgRemoteConnection.NarrowScope)} used to short-circuit with a bare {@code new byte[0]} whenever
 * there was nothing to send (missing/zero-revision changelog, or an incremental request where the
 * client is already fully up to date) -- neither a valid empty bundle2 (HG20) envelope nor a valid
 * legacy {@code HG10UN}-prefixed empty changegroup, so a real hg client either aborted with
 * {@code "stream ended unexpectedly (got 0 bytes, expected 4)"} (HTTP) or hung forever waiting for
 * bytes that would never arrive (a raw SSH-style stream). See
 * {@code llm-wiki/known-bugs-registry.md}'s {@code HgLocalClient.getBundle()} entry for the full
 * root-cause writeup.
 *
 * <p>Since every brand-new Mercurial project starts life as exactly this kind of empty repository,
 * this bug meant nobody could ever run a first {@code hg clone} against one.
 */
@Tag("interop")
public class HgHttpWireServerEmptyRepoRealHgInteropTest {

    private Server server;
    private HgRepository serverRepo;
    private File serverRepoDir;

    @BeforeEach
    public void setUp(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");

        serverRepoDir = tempDir.resolve("server_repo").toFile();
        // Deliberately zero commits -- this is the exact state a brand-new hg4j/yona-created
        // Mercurial project is in before anyone has pushed anything to it.
        serverRepo = Hg.init().setDirectory(serverRepoDir).call();

        server = HgTestUtils.startServlet(new HgHttpWireServer(serverRepo));
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            HgTestUtils.stop(server);
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + HgTestUtils.port(server) + "/";
    }

    /**
     * The core regression: a real {@code hg clone} against a zero-commit repository must
     * succeed (with an empty working copy), not hang or abort. Before the fix, this reproduced
     * the exact real-world symptom 100% of the time: either an immediate
     * {@code "stream ended unexpectedly (got 0 bytes, expected 4)"} abort, or (over the
     * SSH-relay-shaped raw-stream path this HTTP test doesn't exercise directly) an indefinite
     * hang -- wrapped in {@code assertTimeoutPreemptively} here purely as a safety net in case a
     * regression reintroduces the hang rather than the clean-ish HTTP abort.
     */
    @Test
    public void realHgClonesAFreshZeroCommitRepositoryServedByHg4jOverHttp(@TempDir Path tempDir) throws Exception {
        File destDir = tempDir.resolve("client_repo").toFile();

        assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
                HgTestUtils.hg(tempDir.toFile(), "clone", baseUrl(), destDir.getAbsolutePath()));

        assertTrue(destDir.isDirectory(), "clone must have created the destination directory");
        assertTrue(new File(destDir, ".hg").isDirectory(), "clone must have created a .hg repository");

        String log = HgTestUtils.hg(destDir, "log", "-T", "{node}\n");
        assertEquals("", log.trim(), "a fresh empty repository must clone to an empty working copy, not error out");

        // Real hg's own `hg heads` deliberately exits 1 (with no output) on a repository with
        // zero revisions -- not an error condition here, just real hg's documented convention
        // for "no heads to report" on an empty repo, so HgTestUtils.hg()'s exit-code-0 assertion
        // is deliberately bypassed for this one call.
        Process headsProcess = new ProcessBuilder("hg", "heads").directory(destDir).redirectErrorStream(true).start();
        String headsOutput = new String(headsProcess.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        headsProcess.waitFor();
        assertEquals(1, headsProcess.exitValue(), "real hg's own documented behavior for `hg heads` on an empty repo");
        assertEquals("", headsOutput, "an empty repository has no heads to list");
    }

    /**
     * Same regression, but for the OTHER early-return this bug had (the incremental
     * "startRev >= count" branch): a real {@code hg pull} that already has everything the server
     * has (nothing new to fetch) must complete cleanly rather than hang/abort. This exercises the
     * exact same {@code new byte[0]} short-circuit, just reached via "client already up to date"
     * instead of "server has zero commits" -- both branches were fixed the same way, so both need
     * their own regression coverage.
     */
    @Test
    public void realHgPullsWithNothingNewFromHg4jServedOverHttpWithoutHangingOrAborting(@TempDir Path tempDir) throws Exception {
        Files.writeString(new File(serverRepoDir, "a.txt").toPath(), "hello");
        new AddCommand(serverRepo).call();
        new CommitCommand(serverRepo).setMessage("v1").setAuthor("dev").call();

        File destDir = tempDir.resolve("client_repo").toFile();
        HgTestUtils.hg(tempDir.toFile(), "clone", baseUrl(), destDir.getAbsolutePath());

        // Second pull immediately after clone: the client is already fully up to date, so the
        // server's getbundle response has zero new changesets to report.
        String result = assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
                HgTestUtils.hg(destDir, "pull"));
        assertTrue(result.contains("no changes found"),
                "expected real hg's own no-op-pull message, got: " + result);
    }

    /**
     * Adjacent question the getBundle bug raised (per this investigation's own scope note): does
     * the OTHER direction -- a real hg client pushing its first commit INTO an hg4j-served
     * zero-commit repository -- already work, or does it share a similar latent bug? Answer:
     * already fine. {@code HgLocalClient.pushWithHooks}/{@code PullCommand#applyBundle} never
     * went through {@code getBundle} at all (the incoming bundle bytes come from the pushING
     * client, not from a server-side changegroup-generation call), and {@link
     * HgLocalClient#getHeads()} already handled a missing/zero-revision changelog by returning an
     * empty list rather than any byte-stream short-circuit -- so this direction was never exposed
     * to the bug fixed above. Kept as permanent regression coverage for the adjacent scenario.
     */
    @Test
    public void realHgPushesItsFirstCommitIntoAFreshZeroCommitRepositoryServedByHg4jOverHttp(@TempDir Path tempDir) throws Exception {
        File clientDir = tempDir.resolve("client_repo").toFile();
        assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
                HgTestUtils.hg(tempDir.toFile(), "clone", baseUrl(), clientDir.getAbsolutePath()));

        Files.writeString(new File(clientDir, "a.txt").toPath(), "first ever commit");
        HgTestUtils.hg(clientDir, "add", "a.txt");
        HgTestUtils.hg(clientDir, "commit", "-m", "v1");

        // real hg's client refuses a first push into a branch-less empty remote unless told
        // explicitly that this is expected (`abort: push creates new remote branches: default`) --
        // real hg's own well-documented safety net, unrelated to the getBundle bug, so
        // `--new-branch` is passed here exactly as a human operator would.
        assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
                HgTestUtils.hg(clientDir, "push", "--new-branch", baseUrl()));

        serverRepo.clearRevlogCache();
        File clIdx = new File(serverRepo.getStoreDir(), "00changelog.i");
        File clDat = new File(serverRepo.getStoreDir(), "00changelog.d");
        var cl = serverRepo.getRevlog(clIdx, clDat);
        assertEquals(1, cl.getRevisionCount(), "the pushed first-ever commit must be applied server-side");
    }
}
