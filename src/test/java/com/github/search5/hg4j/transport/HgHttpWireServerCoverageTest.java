package com.github.search5.hg4j.transport;

import com.github.search5.hg4j.api.AddCommand;
import com.github.search5.hg4j.api.CommitCommand;
import com.github.search5.hg4j.api.Hg;
import com.github.search5.hg4j.api.PushCommand;
import com.github.search5.hg4j.errors.HgProtocolException;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.transport.wireprotov2.Cbor;
import com.github.search5.hg4j.transport.wireprotov2.Wire2Commands;
import com.github.search5.hg4j.transport.wireprotov2.Wire2Frame;
import com.github.search5.hg4j.transport.wireprotov2.Wire2Transport;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private HttpServer server;
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

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", new HgHttpWireServer(serverRepo));
        server.start();
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
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
    public void apiPathWithWrongSegmentCountFallsThroughTo404() throws Exception {
        // "/api/<namespace>/<ro|rw>/<command>" needs exactly 3 segments after "/api/"; anything
        // else (here: just one) must fall through to the ordinary v1 "cmd" lookup, which finds
        // none and 404s, rather than being routed into wireprotocol v2 handling.
        HttpURLConnection conn = get("/api/onlyonesegment");
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
    public void capabilitiesCommandWithoutTheUpgradeHeaderServesPlainV1Capabilities() throws Exception {
        // The capabilities-discovery-handshake short-circuit ("/".equals(path) && ... &&
        // X-HgUpgrade-1 present) requires ALL four conditions; a plain GET (as a real hg client --
        // wireprotocol v2 was removed entirely after Mercurial 6.1 -- always sends) with no
        // X-HgUpgrade-1 header at all must fall through to ordinary v1 dispatch instead.
        HttpURLConnection conn = get("/?cmd=capabilities");
        assertEquals(200, conn.getResponseCode());
        assertEquals("application/mercurial-0.1", conn.getHeaderField("Content-Type"));
        String body = bodyOf(conn);
        assertTrue(body.contains("lookup"), "Expected the plain v1 capabilities string: " + body);
        assertTrue(body.contains("unbundle=HG10UN"), "Expected the plain v1 capabilities string: " + body);
    }

    @Test
    public void unsupportedV1CommandReturnsAnOobErrorNotACrash() throws Exception {
        HttpURLConnection conn = get("/?cmd=nosuchcommand");
        // ooberror is always sent as HTTP 200 with a dedicated content type (real hg's
        // wireprotoserver.py._callhttp: HTTP_OK + HGERRTYPE for ooberror, regardless of the
        // underlying condition).
        assertEquals(200, conn.getResponseCode());
        assertEquals("application/hg-error", conn.getHeaderField("Content-Type"));
        assertEquals("unsupported command: nosuchcommand", bodyOf(conn));
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

        HttpServer pushServer = HttpServer.create(new InetSocketAddress(0), 0);
        pushServer.createContext("/", pushTargetHandler);
        pushServer.start();
        try {
            File clientRepoDir = tempDir.resolve("push_client_repo").toFile();
            HgRepository clientRepo = Hg.init().setDirectory(clientRepoDir).call();
            Files.writeString(new File(clientRepoDir, "pushed.txt").toPath(), "pushed content");
            new AddCommand(clientRepo).call();
            byte[] pushedCommit = new CommitCommand(clientRepo).setMessage("pushed").setAuthor("dev").call();

            String pushUrl = "http://127.0.0.1:" + pushServer.getAddress().getPort();
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
            pushServer.stop(0);
        }
    }

    // ==================== wireprotocol v2 error/edge framing ====================

    private static final String NS = Wire2Commands.NAMESPACE;

    private byte[] postWire2(String urlSuffix, byte[] requestBody) throws IOException {
        String url = baseUrl() + "/api/" + NS + "/" + urlSuffix;
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", Wire2Transport.FRAMINGTYPE);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestBody);
        }
        assertEquals(200, conn.getResponseCode());
        try (InputStream in = conn.getInputStream()) {
            return in.readAllBytes();
        }
    }

    @Test
    public void emptyWire2RequestBodyProducesAnEmptyResponseInsteadOfAStreamSettingsFrame() throws Exception {
        byte[] response = postWire2("ro/heads", new byte[0]);
        assertEquals(0, response.length, "No commands in the request means nothing (not even stream-settings) should be sent back");
    }

    @Test
    public void wire2CommandNameMismatchBetweenFrameAndUrlProducesAFramedProtocolError() throws Exception {
        // Frame says "known", URL says "heads" -- not a multirequest, so this must be rejected per
        // command instead of being silently dispatched under the wrong name.
        byte[] requestBody = Wire2Transport.buildCommandRequest(7, "known", Map.of("nodes", List.of()));
        byte[] response = postWire2("ro/heads", requestBody);

        HgProtocolException e = assertThrows(HgProtocolException.class,
                () -> Wire2Transport.readCommandResponse(Wire2Transport.toStream(response)));
        assertTrue(e.getMessage().contains("command in frame must match command in URL"), e.getMessage());
    }

    @Test
    public void wire2LookupOfAKnownRevisionReturnsItsRawTwentyByteNode() throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("key", "tip");
        byte[] requestBody = Wire2Transport.buildCommandRequest(1, "lookup", args);
        byte[] response = postWire2("ro/lookup", requestBody);

        List<Object> objs = Wire2Transport.readCommandResponse(Wire2Transport.toStream(response));
        assertEquals(1, objs.size());
        assertArrayEquals(java.util.Arrays.copyOf(commitNode, 20), Cbor.asBytes(objs.get(0)));
    }

    @Test
    public void wire2LookupOfAnUnknownRevisionSurfacesAsAFramedProtocolErrorNotAConnectionFailure() throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("key", "nosuchrevision");
        byte[] requestBody = Wire2Transport.buildCommandRequest(1, "lookup", args);
        byte[] response = postWire2("ro/lookup", requestBody);

        HgProtocolException e = assertThrows(HgProtocolException.class,
                () -> Wire2Transport.readCommandResponse(Wire2Transport.toStream(response)));
        assertTrue(e.getMessage().contains("unknown revision"), e.getMessage());
    }

    @Test
    public void wire2UnsupportedCommandNameSurfacesAsAFramedProtocolError() throws Exception {
        byte[] requestBody = Wire2Transport.buildCommandRequest(1, "bogus", Map.of());
        byte[] response = postWire2("ro/bogus", requestBody);

        HgProtocolException e = assertThrows(HgProtocolException.class,
                () -> Wire2Transport.readCommandResponse(Wire2Transport.toStream(response)));
        assertTrue(e.getMessage().contains("unsupported wire protocol v2 command"), e.getMessage());
    }

    @Test
    public void wire2BranchmapReturnsTheCurrentBranchAndItsHeads() throws Exception {
        byte[] requestBody = Wire2Transport.buildCommandRequest(1, "branchmap", Map.of());
        byte[] response = postWire2("ro/branchmap", requestBody);

        List<Object> objs = Wire2Transport.readCommandResponse(Wire2Transport.toStream(response));
        assertEquals(1, objs.size());
        Map<String, Object> branchToHeads = Cbor.asMap(objs.get(0));
        assertEquals(1, branchToHeads.size());
        List<Object> heads = Cbor.asList(branchToHeads.get("default"));
        assertEquals(1, heads.size());
        assertArrayEquals(java.util.Arrays.copyOf(commitNode, 20), Cbor.asBytes(heads.get(0)));
    }

    @Test
    public void wire2MultirequestToleratesACommandFrameWithNoNameInsteadOfCrashingTheConnection() throws Exception {
        // A command name of null makes dispatchWire2Command's classic switch(String) throw a
        // NullPointerException -- not an HgProtocolException -- which must still be turned into a
        // per-command framed error (the generic catch(Exception), not left to blow up the whole
        // request). Only reachable via a "multirequest" URL: any other URL would instead trip the
        // (also-NPE-prone) name-vs-URL comparison one line earlier, which isn't what this test is
        // targeting.
        Map<String, Object> data = new LinkedHashMap<>(); // deliberately no "name" key
        byte[] payload = Cbor.encode(data);
        Wire2Frame frame = new Wire2Frame(3, 1, Wire2Frame.STREAM_FLAG_BEGIN,
                Wire2Frame.TYPE_COMMAND_REQUEST, Wire2Frame.FLAG_COMMAND_REQUEST_NEW, payload);

        byte[] response = postWire2("ro/multirequest", frame.encode());

        HgProtocolException e = assertThrows(HgProtocolException.class,
                () -> Wire2Transport.readCommandResponse(Wire2Transport.toStream(response)));
        assertTrue(e.getMessage().contains("Remote command error:"), e.getMessage());
    }

    @Test
    public void aTruncatedWire2RequestBodyStillProducesAWellFormedHttpResponse() throws Exception {
        // A COMMAND_REQUEST frame that declares FLAG_COMMAND_REQUEST_MORE_FRAMES but is never
        // followed by the continuation makes Wire2Transport#readAllCommandRequests throw while
        // parsing the request -- entirely outside handleWire2Request's own per-command try/catch --
        // which must still surface as a clean response rather than an aborted/reset connection.
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "heads");
        byte[] payload = Cbor.encode(data);
        Wire2Frame frame = new Wire2Frame(1, 1, Wire2Frame.STREAM_FLAG_BEGIN,
                Wire2Frame.TYPE_COMMAND_REQUEST,
                Wire2Frame.FLAG_COMMAND_REQUEST_NEW | Wire2Frame.FLAG_COMMAND_REQUEST_MORE_FRAMES, payload);

        String url = baseUrl() + "/api/" + NS + "/ro/heads";
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", Wire2Transport.FRAMINGTYPE);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(frame.encode());
        }

        // Whatever HgHttpWireServer decides to answer with, it must be able to answer at all --
        // the server-side exception here must not corrupt the HTTP response framing so badly that
        // the client can't even read a response back.
        int status = conn.getResponseCode();
        byte[] body;
        try (InputStream in = status < 400 ? conn.getInputStream() : conn.getErrorStream()) {
            body = in != null ? in.readAllBytes() : new byte[0];
        }
        assertEquals(200, status);
        String bodyText = new String(body, StandardCharsets.UTF_8);
        assertTrue(bodyText.startsWith("abort: "), bodyText);
        assertTrue(bodyText.contains("Unexpected EOF while reading a continued command-request"), bodyText);
    }
}
