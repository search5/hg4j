package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.PushCommand;
import io.github.search5.hg4j.errors.HgProtocolException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.HgTestUtils;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage-focused tests for {@link HgHttpWireServer}, exercising branches/lines not already
 * hit by {@link HgHttpWireServerTest}, {@link HgHttpTransportV2RoundtripTest}, and
 * {@link HgHttpWireServerRealHgInteropTest}: the 404 fallback, the outer catch-all error
 * response, wireprotocol v2's per-command error framing (mismatched command name, an unknown
 * wireprotov2 command, an exception thrown mid-command), the {@code lookup}/{@code branchmap}
 * v2 commands, an empty v2 request body, the plain (non-upgraded) {@code ?cmd=capabilities} path,
 * and the pre-changegroup hook.
 */
public class HgHttpWireServerCoverageTest {

    private Server server;
    private HgRepository serverRepo;
    private byte[] commitNode;

    @BeforeEach
    public void setUp(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("server_repo").toFile();
        serverRepo = Hg.init().setDirectory(repoDir).call();
        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "hello wire server");
        new AddCommand(serverRepo).call();
        commitNode = new CommitCommand(serverRepo).setMessage("v1").setAuthor("dev").call();

        server = HgTestUtils.startServlet(new HgHttpWireServer(serverRepo));
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            HgTestUtils.stop(server);
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + HgTestUtils.port(server);
    }

    private HttpURLConnection get(String pathAndQuery) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl() + pathAndQuery).toURL().openConnection();
        conn.setRequestMethod("GET");
        return conn;
    }

    private static String bodyOf(HttpURLConnection conn) throws IOException {
        InputStream in = conn.getResponseCode() < 400 ? conn.getInputStream() : conn.getErrorStream();
        if (in == null) {
            return "";
        }
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    // ==================== v1 routing edge cases ====================

    @Test
    public void rootPathWithNoCmdParamAtAllReturns404() throws Exception {
        HttpURLConnection conn = get("/");
        assertEquals(404, conn.getResponseCode());
    }

    @Test
    public void rootPathWithEmptyQueryStringReturns404() throws Exception {
        // A trailing "?" with nothing after it gives URI#getQuery() == "" (not null) -- a distinct
        // branch in parseQueryParams' (query == null || query.isEmpty()) short-circuit from the
        // plain no-"?"-at-all request above.
        HttpURLConnection conn = get("/?");
        assertEquals(404, conn.getResponseCode());
    }


    @Test
    public void malformedQueryPairsWithoutAnEqualsSignAreSkippedNotFatal() throws Exception {
        HttpURLConnection conn = get("/?cmd=heads&thisHasNoEqualsSign");
        assertEquals(200, conn.getResponseCode());
        String body = bodyOf(conn);
        assertEquals(NodeIdUtil.toHex(commitNode) + "\n", body);
    }


    @Test
    public void unsupportedV1CommandIsABadRequestLikeRealHg() throws Exception {
        // Verified against a real `hg serve` 7.2.4 (HttpServerParityInteropTest#unknownCommandIsBadRequest): an unknown
        // command is HTTP 400 "no such method", not an hg-error body with status 200.
        HttpURLConnection conn = get("/?cmd=nosuchcommand");
        assertEquals(400, conn.getResponseCode());
        assertTrue(bodyOf(conn).contains("no such method: nosuchcommand"));
    }

    @Test
    public void anUnhandledExceptionDuringV1DispatchIsTurnedIntoAnAbortResponseInsteadOfCrashingTheConnection() throws Exception {
        // "known" hex-decodes its "nodes" argument with no validation upstream of
        // NodeIdUtil.fromHex, which throws IllegalArgumentException on an odd-length hex string --
        // exercising HgHttpWireServer's own outer catch-all, which must still turn that into a
        // normal (if unhelpful) HTTP response rather than letting the connection die.
        HttpURLConnection conn = get("/?cmd=known&nodes=abc");
        assertEquals(200, conn.getResponseCode());
        assertEquals("application/mercurial-0.1", conn.getHeaderField("Content-Type"));
        String body = bodyOf(conn);
        assertTrue(body.startsWith("abort: "), "Expected the catch-all abort response, got: " + body);
    }

    // ==================== pre-changegroup hook ====================

    @Test
    public void preChangegroupHookCanAbortAPushBeforeAnythingIsApplied(@TempDir Path tempDir) throws Exception {
        File emptyServerRepoDir = tempDir.resolve("push_target_repo").toFile();
        HgRepository emptyServerRepo = Hg.init().setDirectory(emptyServerRepoDir).call();
        HgHttpWireServer pushTargetHandler = new HgHttpWireServer(emptyServerRepo);

        List<Map<String, Object>> observedContexts = new ArrayList<>();
        pushTargetHandler.registerPreChangegroupHook(ctx -> {
            observedContexts.add(ctx);
            return false; // reject
        });

        Server pushServer = HgTestUtils.startServlet(pushTargetHandler);
        try {
            File clientRepoDir = tempDir.resolve("push_client_repo").toFile();
            HgRepository clientRepo = Hg.init().setDirectory(clientRepoDir).call();
            Files.writeString(new File(clientRepoDir, "pushed.txt").toPath(), "pushed content");
            new AddCommand(clientRepo).call();
            byte[] pushedCommit = new CommitCommand(clientRepo).setMessage("pushed").setAuthor("dev").call();

            String pushUrl = "http://127.0.0.1:" + HgTestUtils.port(pushServer);
            String result = new PushCommand(clientRepo).setDestination(pushUrl).call();

            assertNotNull(result);
            assertTrue(result.startsWith("0"), "A pre-hook rejection must surface as the '0\\n...' failure line: " + result);
            assertEquals(1, observedContexts.size(), "The pre-changegroup hook must fire exactly once");
            @SuppressWarnings("unchecked")
            List<String> pendingNodes = (List<String>) observedContexts.get(0).get("nodes");
            assertEquals(List.of(NodeIdUtil.toHex(pushedCommit)), pendingNodes);

            File clIdx = new File(emptyServerRepo.getStoreDir(), "00changelog.i");
            File clDat = new File(emptyServerRepo.getStoreDir(), "00changelog.d");
            var cl = emptyServerRepo.getRevlog(clIdx, clDat);
            assertEquals(0, cl.getRevisionCount(), "Nothing must have been applied once the pre-hook rejected the push");
        } finally {
            HgTestUtils.stop(pushServer);
        }
    }

    // ==================== a v2 upgrade request ====================

    @Test
    public void aV2UpgradeRequestIsAnsweredWithPlainV1CapabilitiesLikeMercurial72() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl() + "/?cmd=capabilities").toURL().openConnection();
        conn.setRequestProperty("X-HgUpgrade-1", "exp-http-v2-0003");
        conn.setRequestProperty("X-HgProto-1", "cbor");
        assertEquals(200, conn.getResponseCode());
        String body = new String(conn.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("getbundle") && body.contains("batch"), "the ordinary v1 capabilities line: " + body);
        assertFalse(body.contains("apibase"), "no v2 discovery document");
    }

    @Test
    public void theApiPathOfWireProtocolV2IsNotServed() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(baseUrl() + "/api/exp-http-v2-0003/ro/heads").toURL().openConnection();
        conn.setRequestMethod("POST");
        assertEquals(404, conn.getResponseCode());
    }









}
