package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.HgCommit;
import io.github.search5.hg4j.api.LogCommand;
import io.github.search5.hg4j.api.PullCommand;
import io.github.search5.hg4j.api.PushCommand;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live HTTP wire-protocol-v1 interop test against a real, running Mercurial server
 * (Mercurial 6.0, run via `docker run -p 18099:8099 hg4j-test-mercurial-6.0`, serving a
 * 2-commit repository over `hg serve`).
 * <p>
 * Unlike the rest of the wire-protocol-v1 test suite (which round-trips against static
 * bundle/changegroup files produced by the real `hg` CLI), this specifically exercises a
 * live, real-time HTTP conversation: hg4j's own {@link HttpURLConnection}-based transport
 * stack talking to an actual `hg serve` process, for both pull and push. This closes the
 * "최신 실제 Mercurial 서버와의 라이브 통신 검증 미착수" gap tracked in
 * llm-wiki/decisions/mercurial-spec-compliance-requirement.md.
 * <p>
 * Skipped automatically if the container is not reachable (this is intentionally not
 * wired into a Testcontainers-managed lifecycle; the container is expected to already be
 * running for this one-off verification).
 */
@Tag("interop")
public class HgHttpV1LiveServerInteropTest {

    private static final String SERVER_URL = "http://localhost:18099/";

    private static boolean isServerReachable() {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(SERVER_URL + "?cmd=capabilities").toURL().openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    public void pullsRealCommitsFromALiveMercurialHttpServer(@TempDir Path tempDir) throws Exception {
        assumeTrue(isServerReachable(), "Live Mercurial 6.0 test server not reachable at " + SERVER_URL);

        HgRepository local = Hg.init().setDirectory(tempDir.toFile()).call();
        new PullCommand(local).setSource(SERVER_URL).call();

        List<HgCommit> log = new LogCommand(local).call();
        assertTrue(log.size() >= 2, "Expected at least the 2 seeded commits, got: " + log.size());
        assertTrue(log.stream().anyMatch(c -> "second commit".equals(c.getMessage())));
        assertTrue(log.stream().anyMatch(c -> "first commit".equals(c.getMessage())));
    }

    @Test
    public void pushesANewCommitToALiveMercurialHttpServerAndItIsVisibleToAFreshPull(@TempDir Path tempDir) throws Exception {
        assumeTrue(isServerReachable(), "Live Mercurial 6.0 test server not reachable at " + SERVER_URL);

        File pushSideDir = tempDir.resolve("push-side").toFile();
        HgRepository pushSide = Hg.init().setDirectory(pushSideDir).call();
        new PullCommand(pushSide).setSource(SERVER_URL).call();

        String marker = "live-push-" + System.nanoTime();
        Files.writeString(new File(pushSideDir, marker + ".txt").toPath(), "pushed live from hg4j");
        new AddCommand(pushSide).addFile(marker + ".txt").call();
        new CommitCommand(pushSide).setAuthor("hg4j <hg4j@example.com>").setMessage(marker).call();

        new PushCommand(pushSide).setDestination(SERVER_URL).call();

        // Verify from an independent, freshly-pulled repo that the server actually has it.
        File verifyDir = tempDir.resolve("verify-side").toFile();
        HgRepository verifySide = Hg.init().setDirectory(verifyDir).call();
        new PullCommand(verifySide).setSource(SERVER_URL).call();

        List<HgCommit> log = new LogCommand(verifySide).call();
        assertTrue(log.stream().anyMatch(c -> marker.equals(c.getMessage())),
                "Pushed commit must be visible to a fresh pull from the live server, got: " + log);
    }
}
