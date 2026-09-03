package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.errors.HgAuthException;
import io.github.search5.hg4j.errors.HgProtocolException;
import io.github.search5.hg4j.errors.HgTransportException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.transport.wireprotov2.Cbor;
import io.github.search5.hg4j.transport.wireprotov2.Wire2Commands;
import io.github.search5.hg4j.util.NodeIdUtil;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage for {@link HgRemoteClient} beyond HgRemoteClientTest /
 * HgRemoteMockAndServeExtensionTest: real gaps identified from a per-class jacoco line report --
 * setCredentialsProvider, between()/known()/pushkey() v1 direct paths, the v1-&gt;v2 delegate
 * wiring branches ("if (delegate != null) return delegate.xxx(...)") across every public method,
 * the private tryEstablishV2FromDiscoveryResponse decision tree, roots/param-joining branches in
 * getChangegroup/getBundle, the push() 10MB response guard, and the mercurial-0.1 raw-zlib
 * auto-detect branch in unwrapResponseStream. All server-side mocks follow the exact byte layouts
 * already established in the sibling test files in this package (v1 plain-text capabilities,
 * real X-HgUpgrade-1/X-HgProto-1 CBOR discovery handshake, and -- since 2026-09-03 -- real hg's
 * actual GET+X-HgArg-N argument transport, see {@link HgArgProtocolTest}).
 */
public class HgRemoteClientCoverageTest {

    /** Reassembles a real-hg {@code encodevalueinheaders}-style X-HgArg-N header chain and decodes it. */
    private static Map<String, String> reassembleXHgArgs(HttpExchange exchange) {
        StringBuilder sb = new StringBuilder();
        int n = 1;
        while (true) {
            String chunk = exchange.getRequestHeaders().getFirst("X-HgArg-" + n);
            if (chunk == null) {
                break;
            }
            sb.append(chunk);
            n++;
        }
        Map<String, String> args = new LinkedHashMap<>();
        String encoded = sb.toString();
        if (!encoded.isEmpty()) {
            for (String pair : encoded.split("&")) {
                int eq = pair.indexOf('=');
                args.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return args;
    }

    private static void respondCapabilities(HttpExchange exchange, String capabilities) throws IOException {
        byte[] resp = capabilities.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, resp.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(resp);
        }
    }

    // ==========================================================
    // setCredentialsProvider
    // ==========================================================

    @Test
    public void setCredentialsProviderWiresBasicAuthHeader() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String[] authHeader = {null};
        server.createContext("/", exchange -> {
            authHeader[0] = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body = "head1\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            client.setCredentialsProvider(new UsernamePasswordCredentialsProvider("bob", "s3cr3t"));

            client.getHeads();

            assertNotNull(authHeader[0], "Authorization header must be set from the provider's credentials");
            String expected = "Basic " + java.util.Base64.getEncoder().encodeToString("bob:s3cr3t".getBytes(StandardCharsets.UTF_8));
            assertEquals(expected, authHeader[0]);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void setCredentialsProviderWithNullProviderIsNoOp() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String[] authHeader = {"unset"};
        server.createContext("/", exchange -> {
            authHeader[0] = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body = "head1\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            client.setCredentialsProvider(null);

            client.getHeads();

            assertNull(authHeader[0], "No Authorization header should be sent when no provider was ever configured");
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void setCredentialsProviderThatFailsToFillLeavesCredentialsUnset() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String[] authHeader = {"unset"};
        server.createContext("/", exchange -> {
            authHeader[0] = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body = "head1\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            // A provider that never fills any CredentialItem (returns false) -- setCredentials()
            // must not be invoked in this case.
            client.setCredentialsProvider((uri, items) -> false);

            client.getHeads();

            assertNull(authHeader[0], "A provider that fails to supply credentials must not set an Authorization header");
            }
        } finally {
            server.stop(0);
        }
    }

    // ==========================================================
    // between() / known() -- entirely untested v1 direct paths
    // ==========================================================

    @Test
    public void betweenSendsPairsAndParsesWhitespaceSeparatedNodes() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String[] capturedQuery = {null};
        server.createContext("/", exchange -> {
            capturedQuery[0] = exchange.getRequestURI().getQuery();
            byte[] body = "aaaa1111 bbbb2222 cccc3333\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            List<String> result = client.between(List.of("startnode-endnode"));

            assertNotNull(capturedQuery[0]);
            assertTrue(capturedQuery[0].contains("cmd=between"));
            assertTrue(capturedQuery[0].contains("pairs="));
            assertEquals(List.of("aaaa1111", "bbbb2222", "cccc3333"), result);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void betweenWithEmptyResponseReturnsEmptyList() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            List<String> result = client.between(List.of("a-b"));
            assertTrue(result.isEmpty());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void knownSendsEncodedNodesAndReturnsRawResponseVerbatim() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String[] capturedQuery = {null};
        server.createContext("/", exchange -> {
            capturedQuery[0] = exchange.getRequestURI().getQuery();
            byte[] body = "1 0 1".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            String result = client.known(List.of("a".repeat(40), "b".repeat(40), "c".repeat(40)));

            assertNotNull(capturedQuery[0]);
            assertTrue(capturedQuery[0].contains("cmd=known"));
            assertTrue(capturedQuery[0].contains("nodes="));
            // known() returns the raw server body untouched -- no whitespace splitting like heads()/between().
            assertEquals("1 0 1", result);
            }
        } finally {
            server.stop(0);
        }
    }

    // ==========================================================
    // pushkey() -- v1 direct path (namespace/key/old/new params + all 3 truthy-response branches)
    // ==========================================================

    @Test
    public void pushkeySendsAllParamsWithOldAndNewDefaultedFromNull() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final Map<String, String>[] capturedArgs = new Map[]{null};
        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query != null && query.contains("cmd=capabilities")) {
                respondCapabilities(exchange, "lookup pushkey batch httpheader=1024");
                return;
            }
            capturedArgs[0] = reassembleXHgArgs(exchange);
            byte[] resp = "1".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            client.getCapabilities(); // negotiates httpheader= so pushkey uses X-HgArg-N, not the legacy query-string tier
            boolean ok = client.pushkey("bookmarks", "mybook", null, null);

            assertTrue(ok, "\"1\" response body must be treated as success");
            assertNotNull(capturedArgs[0]);
            assertEquals("bookmarks", capturedArgs[0].get("namespace"));
            assertEquals("mybook", capturedArgs[0].get("key"));
            assertEquals("", capturedArgs[0].get("old"));
            assertEquals("", capturedArgs[0].get("new"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void pushkeyTreatsTrueCaseInsensitiveResponseAsSuccess() throws Exception {
        assertTrue(runPushkeyWithResponse("True"));
    }

    @Test
    public void pushkeyTreatsEmptyResponseAsSuccess() throws Exception {
        assertTrue(runPushkeyWithResponse(""));
    }

    @Test
    public void pushkeyTreatsOtherResponseAsFailure() throws Exception {
        assertFalse(runPushkeyWithResponse("0"));
    }

    private boolean runPushkeyWithResponse(String responseBody) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] resp = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length == 0 ? -1 : resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            return client.pushkey("phases", "somekey", "old", "new");
            }
        } finally {
            server.stop(0);
        }
    }

    // ==========================================================
    // getChangegroup(roots) -- null/empty/multi-root joining branches
    // ==========================================================

    @Test
    public void getChangegroupWithMultipleRootsJoinsThemWithSpace() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final Map<String, String>[] capturedArgs = new Map[]{null};
        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query != null && query.contains("cmd=capabilities")) {
                respondCapabilities(exchange, "lookup getbundle changegroupsubset batch httpheader=1024");
                return;
            }
            capturedArgs[0] = reassembleXHgArgs(exchange);
            byte[] resp = new byte[]{0, 0, 0, 0};
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            client.getCapabilities(); // negotiates httpheader= so getChangegroup uses X-HgArg-N, not the legacy query-string tier
            byte[] result = client.getChangegroup(List.of("root1", "root2", "root3"));

            assertNotNull(result);
            assertNotNull(capturedArgs[0]);
            assertEquals("root1 root2 root3", capturedArgs[0].get("roots"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void getChangegroupWithNullRootsOmitsRootsParam() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String[] capturedBody = {null};
        server.createContext("/", exchange -> {
            capturedBody[0] = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] resp = new byte[]{0, 0, 0, 0};
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            byte[] result = client.getChangegroup(null);

            assertNotNull(result);
            assertEquals("", capturedBody[0], "No 'roots' param should be sent when roots is null");
            }
        } finally {
            server.stop(0);
        }
    }

    // ==========================================================
    // getBundle() -- full parameter branch matrix
    // ==========================================================

    @Test
    public void getBundleDefaultsCommonAndBundleCapsWhenNotSpecified() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final Map<String, String>[] capturedArgs = new Map[]{null};
        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query != null && query.contains("cmd=capabilities")) {
                respondCapabilities(exchange, "lookup getbundle batch httpheader=1024");
                return;
            }
            capturedArgs[0] = reassembleXHgArgs(exchange);
            byte[] resp = "bundledata".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            client.getCapabilities(); // negotiates httpheader= so getBundle uses X-HgArg-N, not the legacy query-string tier
            byte[] result = client.getBundle(null, null, null);

            assertNotNull(result);
            assertNotNull(capturedArgs[0]);
            assertEquals("", capturedArgs[0].get("common"));
            assertFalse(capturedArgs[0].containsKey("heads"), "heads param must be omitted when heads is null/empty");
            assertEquals("true", capturedArgs[0].get("cg"));
            // 실제 스펙(wireprototypes.GETBUNDLE_ARGUMENTS의 bundlecaps="scsv" 타입) 실측
            // 정정(2026-09-03): changegroup 버전 목록은 평평한 "changegroup=..." 토큰이
            // 아니라 "bundle2=<blob>" 토큰 안에 콤마로 중첩돼야만 실제 hg가 인식한다 —
            // Bundle2Parser#buildChangegroupBundleCaps 주석 참고. 예전 어서션의 스페이스
            // 구분 평평한 형태는 실제 hg 서버에 보내면 협상 자체가 항상 구식 bundle1로
            // 폴백되던, 검증 안 된(그리고 이번에 실제로 틀렸다고 확인된) 형태였다.
            assertEquals("HG20,bundle2=HG20%0Achangegroup%3D01%2C02%2C03%2C04%2C05,compression=GZ,BZ,ZS",
                    capturedArgs[0].get("bundlecaps"),
                    "Default bundlecaps must be sent when none specified. Args were: " + capturedArgs[0]);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void getBundleWithExplicitCommonHeadsAndBundleCaps() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final Map<String, String>[] capturedArgs = new Map[]{null};
        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query != null && query.contains("cmd=capabilities")) {
                respondCapabilities(exchange, "lookup getbundle batch httpheader=1024");
                return;
            }
            capturedArgs[0] = reassembleXHgArgs(exchange);
            byte[] resp = "bundledata".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            client.getCapabilities(); // negotiates httpheader= so getBundle uses X-HgArg-N, not the legacy query-string tier
            byte[] result = client.getBundle(List.of("c1", "c2"), List.of("h1", "h2"), List.of("bundle2"));

            assertNotNull(result);
            assertNotNull(capturedArgs[0]);
            assertEquals("c1 c2", capturedArgs[0].get("common"));
            assertEquals("h1 h2", capturedArgs[0].get("heads"));
            assertEquals("bundle2", capturedArgs[0].get("bundlecaps"));
            }
        } finally {
            server.stop(0);
        }
    }

    // ==========================================================
    // Malformed URL branches not yet exercised for the POST-based command paths
    // ==========================================================

    @Test
    public void executePostBinaryMalformedUrlThrowsHgTransportException() {
        try (HgRemoteClient client = new HgRemoteClient("http://[invalid-url")) {
        HgTransportException ex = assertThrows(HgTransportException.class,
                () -> client.getChangegroup(List.of("abc")));
        assertTrue(ex.getMessage().contains("Malformed URL"));
        }
    }

    @Test
    public void pushMalformedUrlThrowsHgTransportException() {
        try (HgRemoteClient client = new HgRemoteClient("http://[invalid-url")) {
        HgTransportException ex = assertThrows(HgTransportException.class,
                () -> client.push(new byte[]{1, 2, 3}, List.of("head1")));
        assertTrue(ex.getMessage().contains("Malformed URL"));
        }
    }

    // ==========================================================
    // push() -- 10MB response size guard
    // ==========================================================

    @Test
    public void pushThrowsWhenResponseExceeds10MbGuard() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket socket = serverSocket.accept()) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    // Consume the request line/headers, then the small pushed bundle body.
                    String line;
                    int contentLength = 0;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase().startsWith("content-length:")) {
                            contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                        }
                    }
                    for (int i = 0; i < contentLength; i++) {
                        reader.read();
                    }
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    byte[] chunk = new byte[65536];
                    Arrays.fill(chunk, (byte) 'x');
                    // Write well past the 10MB guard threshold.
                    for (int i = 0; i < 200; i++) {
                        out.write(chunk);
                    }
                    out.flush();
                } catch (IOException ignored) {
                    // Client is expected to abort/disconnect once the guard trips.
                }
            });
            serverThread.start();

            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + port)) {
            HgProtocolException ex = assertThrows(HgProtocolException.class,
                    () -> client.push(new byte[]{1, 2, 3}, List.of("head1")));
            assertTrue(ex.getMessage().contains("10MB"), "Message was: " + ex.getMessage());
            serverThread.join();
            }
        }
    }

    // ==========================================================
    // negotiateV2(null)
    // ==========================================================

    @Test
    public void negotiateV2WithNullCapabilitiesReturnsCurrentV2State() {
        try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1/")) {
        assertFalse(client.negotiateV2(null), "No capabilities negotiated yet -- v2 must not be reported as supported");
        }
    }

    // ==========================================================
    // Private tryEstablishV2FromDiscoveryResponse(byte[]) decision tree, invoked via reflection
    // exactly like the existing MercurialChunkedInputStream/unwrapResponseStream reflection tests
    // in HgRemoteClientTest.
    // ==========================================================

    private boolean invokeTryEstablishV2(HgRemoteClient client, byte[] discoveryBytes) throws Exception {
        Method m = HgRemoteClient.class.getDeclaredMethod("tryEstablishV2FromDiscoveryResponse", byte[].class);
        m.setAccessible(true);
        try {
            return (boolean) m.invoke(client, (Object) discoveryBytes);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception) {
                throw (Exception) e.getCause();
            }
            throw e;
        }
    }

    @Test
    public void tryEstablishV2ReturnsFalseWhenDecodedListIsEmpty() throws Exception {
        try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1/")) {
        assertFalse(invokeTryEstablishV2(client, new byte[0]));
        }
    }

    @Test
    public void tryEstablishV2ReturnsFalseWhenApibaseIsMissing() throws Exception {
        try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1/")) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("apis", Map.of(Wire2Commands.NAMESPACE, Map.of()));
        byte[] bytes = Cbor.encode(descriptor);
        assertFalse(invokeTryEstablishV2(client, bytes));
        }
    }

    @Test
    public void tryEstablishV2ReturnsFalseWhenApisDoesNotContainTheRealNamespace() throws Exception {
        try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1/")) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("apibase", "api/");
        descriptor.put("apis", Map.of("some-other-namespace", Map.of()));
        byte[] bytes = Cbor.encode(descriptor);
        assertFalse(invokeTryEstablishV2(client, bytes));
        }
    }

    @Test
    public void tryEstablishV2SucceedsAndParsesEmbeddedV1CapabilitiesAndCredentials() throws Exception {
        try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1/")) {
        client.setCredentials("alice", "hunter2");

        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("apibase", "api/");
        descriptor.put("apis", Map.of(Wire2Commands.NAMESPACE, Map.of()));
        descriptor.put("v1capabilities", "lookup httpheader=4096 clonebundles");
        byte[] bytes = Cbor.encode(descriptor);

        boolean upgraded = invokeTryEstablishV2(client, bytes);

        assertTrue(upgraded);
        // The embedded v1capabilities line must still be parsed for tokens with no v2 equivalent.
        assertEquals(4096, client.getMaxHttpHeaderLimit());
        assertTrue(client.supportsClonebundles());
        }
    }

    @Test
    public void tryEstablishV2SucceedsWithEmptyV1CapabilitiesLine() throws Exception {
        try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1/")) {

        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("apibase", "api/");
        descriptor.put("apis", Map.of(Wire2Commands.NAMESPACE, Map.of()));
        descriptor.put("v1capabilities", "");
        byte[] bytes = Cbor.encode(descriptor);

        assertTrue(invokeTryEstablishV2(client, bytes));
        }
    }

    @Test
    public void tryEstablishV2SucceedsWithoutV1CapabilitiesFieldAtAll() throws Exception {
        try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1/")) {

        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("apibase", "api/");
        descriptor.put("apis", Map.of(Wire2Commands.NAMESPACE, Map.of()));
        byte[] bytes = Cbor.encode(descriptor);

        assertTrue(invokeTryEstablishV2(client, bytes));
        }
    }

    // ==========================================================
    // Full v1 -> v2 auto-upgrade delegate wiring: covers "if (delegate != null) return
    // delegate.xxx(...)" in getHeads/getChangegroup/getBundle/listKeys/pushkey/push against a
    // real HgHttpWireServer (same real-handshake pattern as
    // getCapabilitiesAutoUpgradesToV2WhenServerAdvertisesTheRealHandshake in HgRemoteClientTest).
    // ==========================================================

    @Test
    public void allPublicMethodsDelegateToV2AfterAutoUpgrade(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("v2_delegate_repo").toFile()).call();
        java.io.File testFile = new java.io.File(repo.getDirectory(), "a.txt");
        java.nio.file.Files.writeString(testFile.toPath(), "content", StandardCharsets.UTF_8);
        Hg hg = Hg.wrap(repo);
        hg.add().addFile("a.txt").call();
        byte[] commitNode = hg.commit().setMessage("coverage commit").call();
        String hex = NodeIdUtil.toHex(commitNode).substring(0, 40);

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/", new HgHttpWireServer(repo));
            server.start();

            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {

            // Triggers the real X-HgUpgrade-1/X-HgProto-1 handshake and wires up the delegate.
            List<String> caps = client.getCapabilities();
            assertTrue(caps.contains("changesetdata"), "Client must have auto-upgraded to v2");

            List<String> heads = client.getHeads();
            assertEquals(1, heads.size());
            assertEquals(hex, heads.get(0));

            // getChangegroup/getBundle delegate branches.
            byte[] changegroupBytes = client.getChangegroup(List.of());
            assertNotNull(changegroupBytes);

            byte[] bundleBytes = client.getBundle(List.of(), heads, List.of());
            assertNotNull(bundleBytes);
            assertTrue(bundleBytes.length > 0);

            // listKeys/pushkey delegate branches, against a real bookmark.
            assertTrue(client.listKeys("bookmarks").isEmpty());
            assertTrue(client.pushkey("bookmarks", "covbook", "", hex),
                    "pushkey on a not-yet-existing key with empty oldVal must succeed");
            assertEquals(hex, client.listKeys("bookmarks").get("covbook"));

            // push() delegate branch -- real wireprotocol v2 has no push/unbundle command at all.
            Exception e = assertThrows(Exception.class, () -> client.push(new byte[]{1, 2, 3}, null));
            assertTrue(e.getMessage().contains("no push/unbundle command"), "Message was: " + e.getMessage());
            }
        } finally {
            server.stop(0);
        }
    }

    // ==========================================================
    // unwrapResponseStream -- mercurial-0.1 raw-zlib auto-detect branches (private method,
    // invoked via reflection exactly like HgRemoteClientTest's existing unwrapResponseStream
    // tests).
    // ==========================================================

    private InputStream invokeUnwrap(HgRemoteClient client, InputStream in, String contentType) throws Exception {
        Method method = HgRemoteClient.class.getDeclaredMethod("unwrapResponseStream", InputStream.class, String.class);
        method.setAccessible(true);
        try {
            return (InputStream) method.invoke(client, in, contentType);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception) {
                throw (Exception) e.getCause();
            }
            throw e;
        }
    }

    @Test
    public void unwrapMercurial01AutoDetectsAndInflatesRawZlibStream() throws Exception {
        String plaintext = "lookup getbundle changegroup\n";
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream dos = new DeflaterOutputStream(compressed)) {
            dos.write(plaintext.getBytes(StandardCharsets.UTF_8));
        }
        byte[] zlibBytes = compressed.toByteArray();
        // Sanity: a real zlib stream always starts 0x78 followed by one of the 4 known FLEVEL bytes.
        assertEquals(0x78, zlibBytes[0] & 0xFF);

        try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1/")) {
        InputStream unwrapped = invokeUnwrap(client, new ByteArrayInputStream(zlibBytes), "application/mercurial-0.1");
        byte[] decoded = unwrapped.readAllBytes();

        assertEquals(plaintext, new String(decoded, StandardCharsets.UTF_8));
        }
    }

    @Test
    public void unwrapMercurial01PassesThroughNonZlibBytesUnchangedWhenSecondByteDoesNotMatch() throws Exception {
        // Starts with the zlib CMF byte (0x78) but a second byte that is not one of the 4 real
        // FLEVEL values real zlib ever produces -- must be treated as plain uncompressed data.
        byte[] raw = new byte[]{0x78, 0x02, 'h', 'e', 'a', 'd'};

        try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1/")) {
        InputStream unwrapped = invokeUnwrap(client, new ByteArrayInputStream(raw), "application/mercurial-0.1");
        byte[] result = unwrapped.readAllBytes();

        assertArrayEquals(raw, result);
        }
    }

    @Test
    public void unwrapMercurial01HandlesSingleByteStreamWithoutError() throws Exception {
        byte[] raw = new byte[]{0x78};

        try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1/")) {
        InputStream unwrapped = invokeUnwrap(client, new ByteArrayInputStream(raw), "application/mercurial-0.1");
        byte[] result = unwrapped.readAllBytes();

        assertArrayEquals(raw, result);
        }
    }

    @Test
    public void unwrapMercurial01HandlesEmptyStreamWithoutError() throws Exception {
        try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1/")) {
        InputStream unwrapped = invokeUnwrap(client, new ByteArrayInputStream(new byte[0]), "application/mercurial-0.1");
        assertEquals(-1, unwrapped.read());
        }
    }

    @Test
    public void unwrapMercurial02WithEmptyCompressionNameSkipsInflate() throws Exception {
        // Real hg's actual -0.2 wire format (see HgRemoteClient#unwrapResponseStream): 1-byte
        // namelen + name, then the payload straight through to end of stream -- no inner
        // chunk-length framing.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0); // compression name length = 0 -> empty name, no compression
        String payload = "listkeys";
        out.write(payload.getBytes(StandardCharsets.UTF_8));

        try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1/")) {
        InputStream unwrapped = invokeUnwrap(client, new ByteArrayInputStream(out.toByteArray()), "application/mercurial-0.2");
        byte[] result = unwrapped.readAllBytes();

        assertEquals(payload, new String(result, StandardCharsets.UTF_8));
        }
    }

    @Test
    public void unwrapPassesThroughUnrelatedContentTypeUnchanged() throws Exception {
        byte[] raw = "plain text body".getBytes(StandardCharsets.UTF_8);
        try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1/")) {
        InputStream unwrapped = invokeUnwrap(client, new ByteArrayInputStream(raw), "text/plain");
        assertArrayEquals(raw, unwrapped.readAllBytes());
        }
    }

    @Test
    public void unwrapPassesThroughNullContentTypeUnchanged() throws Exception {
        byte[] raw = "plain text body".getBytes(StandardCharsets.UTF_8);
        try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1/")) {
        InputStream unwrapped = invokeUnwrap(client, new ByteArrayInputStream(raw), null);
        assertArrayEquals(raw, unwrapped.readAllBytes());
        }
    }

    /**
     * Real zlib headers only ever use 4 possible second bytes (one per FLEVEL value 0..3): level
     * 0-1 -> 0x01, level 2-5 -> 0x5E, level 6 (default, already covered above) -> 0x9C, level 7-9
     * -> 0xDA. Covers the remaining OR-branches of the CMF/FLEVEL sniff in unwrapResponseStream.
     */
    @Test
    public void unwrapMercurial01AutoDetectsEveryRealZlibFlevelByte() throws Exception {
        String plaintext = "changegroupsubset\n";
        for (int level : new int[]{1, 3, 9}) {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            Deflater deflater = new Deflater(level);
            try (DeflaterOutputStream dos = new DeflaterOutputStream(compressed, deflater)) {
                dos.write(plaintext.getBytes(StandardCharsets.UTF_8));
            } finally {
                deflater.end();
            }
            byte[] zlibBytes = compressed.toByteArray();
            assertEquals(0x78, zlibBytes[0] & 0xFF, "level " + level);

            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1/")) {
            InputStream unwrapped = invokeUnwrap(client, new ByteArrayInputStream(zlibBytes), "application/mercurial-0.1");
            byte[] decoded = unwrapped.readAllBytes();

            assertEquals(plaintext, new String(decoded, StandardCharsets.UTF_8), "level " + level);
            }
        }
    }

    // ==========================================================
    // getCapabilities() -- the two "second call" branches: v1-only fallback repeated (line
    // executeGetBinary without upgrade headers) and already-upgraded delegate short-circuit.
    // ==========================================================

    @Test
    public void secondCapabilitiesCallOnV1OnlyServerSkipsUpgradeHeaders() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/", exchange -> {
                byte[] body = "lookup changegroupsubset".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();

            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            List<String> first = client.getCapabilities();
            List<String> second = client.getCapabilities();

            assertEquals(first, second);
            assertTrue(second.contains("lookup"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void secondCapabilitiesCallAfterV2UpgradeShortCircuitsToDelegate(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("v2_second_call_repo").toFile()).call();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/", new HgHttpWireServer(repo));
            server.start();

            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            List<String> first = client.getCapabilities();
            assertTrue(first.contains("changesetdata"));

            // Second call must hit the top-of-method "if (delegate != null)" short-circuit rather
            // than re-running the hasNegotiated/upgrade-header logic.
            List<String> second = client.getCapabilities();
            assertEquals(first, second);
            }
        } finally {
            server.stop(0);
        }
    }

    // ==========================================================
    // executePostBinary() -- forceTls and its own 401/5xx status handling (a separate code path
    // from executeGetBinary's, duplicated rather than shared in the production class).
    // ==========================================================

    @Test
    public void executePostBinaryForceTlsRejectsPlainHttp() {
        try (HgRemoteClient client = new HgRemoteClient("http://example.com/repo")) {
        client.setForceTls(true);
        assertThrows(SecurityException.class, () -> client.getChangegroup(List.of("abc")));
        }
    }

    @Test
    public void executePostBinaryUnauthorizedThrowsHgAuthException() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/", exchange -> exchange.sendResponseHeaders(401, -1));
            server.start();

            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            assertThrows(HgAuthException.class, () -> client.pushkey("bookmarks", "k", "", "v"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void executePostBinaryServerErrorThrowsHgProtocolException() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/", exchange -> exchange.sendResponseHeaders(503, -1));
            server.start();

            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            assertThrows(HgProtocolException.class, () -> client.getBundle(List.of(), List.of("h1"), List.of()));
            }
        } finally {
            server.stop(0);
        }
    }

    // ==========================================================
    // push()'s own 401/5xx status handling (yet another separate code path from
    // executeGetBinary/executePostBinary).
    // ==========================================================

    @Test
    public void pushUnauthorizedThrowsHgAuthException() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/", exchange -> exchange.sendResponseHeaders(403, -1));
            server.start();

            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            assertThrows(HgAuthException.class, () -> client.push(new byte[]{1, 2, 3}, List.of("head1")));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void pushServerErrorThrowsHgProtocolException() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/", exchange -> exchange.sendResponseHeaders(500, -1));
            server.start();

            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            assertThrows(HgProtocolException.class, () -> client.push(new byte[]{1, 2, 3}, List.of("head1")));
            }
        } finally {
            server.stop(0);
        }
    }

    // ==========================================================
    // executeGetBinary()'s own 100MB response size guard (getHeads/getCapabilities/listKeys/... 's
    // shared GET path) -- structurally identical to, but a distinct code path from, push()'s 10MB
    // guard already covered above.
    // ==========================================================

    @Test
    public void getHeadsThrowsWhenResponseExceeds100MbGuard() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket socket = serverSocket.accept()) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    }
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    byte[] chunk = new byte[1024 * 1024];
                    Arrays.fill(chunk, (byte) 'x');
                    // 105 x 1MB = 105MB, safely past the 100MB guard threshold.
                    for (int i = 0; i < 105; i++) {
                        out.write(chunk);
                    }
                    out.flush();
                } catch (IOException ignored) {
                    // Client aborts the connection once the guard trips.
                }
            });
            serverThread.start();

            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + port)) {
            HgProtocolException ex = assertThrows(HgProtocolException.class, client::getHeads);
            assertTrue(ex.getMessage().contains("100MB"), "Message was: " + ex.getMessage());
            serverThread.join();
            }
        }
    }

    /**
     * executePostBinary() has its own copy of the 100MB response-size guard (a separate code path
     * from executeGetBinary's), including its own try/catch(Exception)/finally cleanup block --
     * neither of which is exercised by the GET-side guard test above.
     */
    @Test
    public void getChangegroupThrowsWhenResponseExceeds100MbGuard() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            Thread serverThread = new Thread(() -> {
                try (Socket socket = serverSocket.accept()) {
                    java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    int contentLength = 0;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase().startsWith("content-length:")) {
                            contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                        }
                    }
                    for (int i = 0; i < contentLength; i++) {
                        reader.read();
                    }
                    OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    byte[] chunk = new byte[1024 * 1024];
                    Arrays.fill(chunk, (byte) 'x');
                    for (int i = 0; i < 105; i++) {
                        out.write(chunk);
                    }
                    out.flush();
                } catch (IOException ignored) {
                    // Client aborts the connection once the guard trips.
                }
            });
            serverThread.start();

            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + port)) {
            HgProtocolException ex = assertThrows(HgProtocolException.class,
                    () -> client.getChangegroup(List.of("root1")));
            assertTrue(ex.getMessage().contains("100MB"), "Message was: " + ex.getMessage());
            serverThread.join();
            }
        }
    }

    // ==========================================================
    // executePostBinary() Authorization header wiring (a separate code path from
    // executeGetBinary's, which is already covered by setCredentialsProviderWiresBasicAuthHeader).
    // ==========================================================

    @Test
    public void executePostBinarySendsBasicAuthHeaderWhenCredentialsSet() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        final String[] authHeader = {null};
        try {
            server.createContext("/", exchange -> {
                authHeader[0] = exchange.getRequestHeaders().getFirst("Authorization");
                exchange.getRequestBody().readAllBytes();
                byte[] resp = new byte[]{0, 0, 0, 0};
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            });
            server.start();

            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            client.setCredentials("carol", "pw123");
            client.getChangegroup(List.of("root1"));

            assertNotNull(authHeader[0]);
            String expected = "Basic " + java.util.Base64.getEncoder().encodeToString("carol:pw123".getBytes(StandardCharsets.UTF_8));
            assertEquals(expected, authHeader[0]);
            }
        } finally {
            server.stop(0);
        }
    }

    // ==========================================================
    // Remaining small branch gaps: malformed httpheader value, provider supplying only a
    // username, and the null-proxy no-op.
    // ==========================================================

    @Test
    public void negotiateV2WithMalformedHttpHeaderValueIgnoresParseError() {
        try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1/")) {
        int before = client.getMaxHttpHeaderLimit();
        boolean supportsV2 = client.negotiateV2(List.of("lookup", "httpheader=not-a-number"));
        assertFalse(supportsV2);
        assertEquals(before, client.getMaxHttpHeaderLimit(), "Malformed httpheader value must be ignored, not applied");
        }
    }

    @Test
    public void setCredentialsProviderWithOnlyUsernameLeavesPasswordNull() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        final String[] authHeader = {"unset"};
        try {
            server.createContext("/", exchange -> {
                authHeader[0] = exchange.getRequestHeaders().getFirst("Authorization");
                byte[] body = "head1\n".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();

            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            // A provider that only fills the Username item -- Password.getValue() stays null,
            // exercising the "passChars != null ? ... : null" false branch in setCredentialsProvider.
            client.setCredentialsProvider((uri, items) -> {
                boolean filled = false;
                for (CredentialItem item : items) {
                    if (item instanceof CredentialItem.Username) {
                        ((CredentialItem.Username) item).setValue("onlyuser");
                        filled = true;
                    }
                }
                return filled;
            });

            client.getHeads();

            // password stays null, so the Authorization header must never be built (executeGetBinary
            // requires both username AND password to be non-null).
            assertNull(authHeader[0]);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void setProxyWithNullIsIgnored() {
        try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1/")) {
        assertDoesNotThrow(() -> client.setProxy(null));
        }
    }

    // ==========================================================
    // unwrapResponseStream -- application/mercurial-0.2 framing edge cases not yet covered:
    // a completely empty stream, and the real "zlib"/"deflate" named-compression branch (distinct
    // from the raw-zlib auto-detect tested for application/mercurial-0.1).
    // ==========================================================

    @Test
    public void unwrapMercurial02WithCompletelyEmptyStreamReturnsInputUnchanged() throws Exception {
        try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1/")) {
        InputStream unwrapped = invokeUnwrap(client, new ByteArrayInputStream(new byte[0]), "application/mercurial-0.2");
        assertEquals(-1, unwrapped.read());
        }
    }

    @Test
    public void unwrapMercurial02WithZlibCompressionNameInflatesChunkedPayload() throws Exception {
        assertMercurial02CompressedPayloadRoundTrips("zlib");
    }

    @Test
    public void unwrapMercurial02WithDeflateCompressionNameInflatesChunkedPayload() throws Exception {
        assertMercurial02CompressedPayloadRoundTrips("deflate");
    }

    private void assertMercurial02CompressedPayloadRoundTrips(String compressionName) throws Exception {
        String plaintext = "lookup getbundle changegroupsubset\n";
        ByteArrayOutputStream deflated = new ByteArrayOutputStream();
        try (DeflaterOutputStream dos = new DeflaterOutputStream(deflated)) {
            dos.write(plaintext.getBytes(StandardCharsets.UTF_8));
        }
        byte[] deflatedBytes = deflated.toByteArray();

        // Real hg's actual -0.2 wire format (confirmed against a real Mercurial 7.2.4 server,
        // 2026-09-03): 1-byte namelen + name, then the compressed payload straight through to end
        // of stream -- no inner chunk-length framing on top.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] nameBytes = compressionName.getBytes(StandardCharsets.US_ASCII);
        out.write(nameBytes.length);
        out.write(nameBytes);
        out.write(deflatedBytes);

        try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1/")) {
        InputStream unwrapped = invokeUnwrap(client, new ByteArrayInputStream(out.toByteArray()), "application/mercurial-0.2");
        byte[] decoded = unwrapped.readAllBytes();

        assertEquals(plaintext, new String(decoded, StandardCharsets.UTF_8), "compression=" + compressionName);
        }
    }

    // ==========================================================
    // push() -- the v1->v2 delegate branch normally always throws (real wireprotocol v2 has no
    // push/unbundle command, see allPublicMethodsDelegateToV2AfterAutoUpgrade above), so the
    // "return delegate.push(...)" statement itself can only ever be observed as *entered*, never
    // as *completed*, against a real delegate. Substituting a stub delegate via reflection on the
    // private `delegate` field exercises the same production branch through to a normal return.
    // ==========================================================

    private static class SucceedingHgRemoteClientV2Stub extends HgRemoteClientV2 {
        SucceedingHgRemoteClientV2Stub(String url) {
            super(url);
        }

        @Override
        public String push(byte[] bundleBytes, List<String> heads) {
            return "stubbed-ok:" + bundleBytes.length + ":" + heads;
        }
    }

    @Test
    public void pushDelegatesToV2AndReturnsSuccessfully() throws Exception {
        try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1/")) {
        java.lang.reflect.Field delegateField = HgRemoteClient.class.getDeclaredField("delegate");
        delegateField.setAccessible(true);
        delegateField.set(client, new SucceedingHgRemoteClientV2Stub("http://127.0.0.1/"));

        String result = client.push(new byte[]{1, 2, 3}, List.of("head1"));

        assertEquals("stubbed-ok:3:[head1]", result);
        }
    }

    // ==========================================================
    // push() -- remaining v1 direct-path branches: null heads, Basic auth header wiring, and the
    // forceTls+https "check passed" branch (a separate code path from executeGetBinary's and
    // executePostBinary's own copies of the same check).
    // ==========================================================

    @Test
    public void pushWithNullHeadsOmitsHeadsParam() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        final String[] capturedQuery = {null};
        try {
            server.createContext("/", exchange -> {
                capturedQuery[0] = exchange.getRequestURI().getQuery();
                exchange.getRequestBody().readAllBytes();
                byte[] resp = "ok\n".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            });
            server.start();

            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            String result = client.push(new byte[]{1, 2, 3}, null);

            assertEquals("ok\n", result);
            assertNotNull(capturedQuery[0]);
            assertFalse(capturedQuery[0].contains("heads="), "heads param must be omitted when heads is null. Query was: " + capturedQuery[0]);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void pushSendsBasicAuthHeaderWhenCredentialsSet() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        final String[] authHeader = {null};
        try {
            server.createContext("/", exchange -> {
                authHeader[0] = exchange.getRequestHeaders().getFirst("Authorization");
                exchange.getRequestBody().readAllBytes();
                byte[] resp = "ok\n".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            });
            server.start();

            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            client.setCredentials("dave", "pw456");
            client.push(new byte[]{1, 2, 3}, List.of("head1"));

            assertNotNull(authHeader[0]);
            String expected = "Basic " + java.util.Base64.getEncoder().encodeToString("dave:pw456".getBytes(StandardCharsets.UTF_8));
            assertEquals(expected, authHeader[0]);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void pushWithHttpsForceTlsSkipsSecurityCheckAndAttemptsRealConnection() {
        try (HgRemoteClient client = new HgRemoteClient("https://127.0.0.1:1/repo")) {
        client.setForceTls(true);
        // Port 1 is unused (nothing listens there): the forceTls+https check must pass silently
        // (no SecurityException), and the subsequent real connection attempt must fail with a
        // plain IOException instead.
        Exception e = assertThrows(Exception.class, () -> client.push(new byte[]{1, 2, 3}, List.of("head1")));
        assertFalse(e instanceof SecurityException, "https:// URL with forceTls=true must not be rejected by the security check");
        }
    }

    @Test
    public void pushUnauthorizedWithCredentialsThrowsHgAuthExceptionMentioningTheUsername() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/", exchange -> exchange.sendResponseHeaders(401, -1));
            server.start();

            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            client.setCredentials("erin", "pw789");
            HgAuthException ex = assertThrows(HgAuthException.class,
                    () -> client.push(new byte[]{1, 2, 3}, List.of("head1")));
            assertTrue(ex.getMessage().contains("erin"), "Message was: " + ex.getMessage());
            }
        } finally {
            server.stop(0);
        }
    }

    // ==========================================================
    // executePostBinary() -- remaining branch gaps: forceTls+https "check passed" branch, partial
    // credentials (username set, password null), and the status==403 (rather than 401) auth path
    // with credentials set (covers the "username != null" side of the exception-message ternary).
    // ==========================================================

    @Test
    public void getChangegroupWithHttpsForceTlsSkipsSecurityCheckAndAttemptsRealConnection() {
        try (HgRemoteClient client = new HgRemoteClient("https://127.0.0.1:1/repo")) {
        client.setForceTls(true);
        Exception e = assertThrows(Exception.class, () -> client.getChangegroup(List.of("abc")));
        assertFalse(e instanceof SecurityException, "https:// URL with forceTls=true must not be rejected by the security check");
        }
    }

    @Test
    public void executePostBinaryWithPartialCredentialsOmitsAuthHeader() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        final String[] authHeader = {"unset"};
        try {
            server.createContext("/", exchange -> {
                authHeader[0] = exchange.getRequestHeaders().getFirst("Authorization");
                exchange.getRequestBody().readAllBytes();
                byte[] resp = new byte[]{0, 0, 0, 0};
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            });
            server.start();

            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            client.setCredentials("onlyuser", null);
            client.getChangegroup(List.of("root1"));

            assertNull(authHeader[0], "Authorization header must not be sent when password is null");
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void executePostBinaryForbiddenWithCredentialsThrowsHgAuthExceptionMentioningTheUsername() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/", exchange -> exchange.sendResponseHeaders(403, -1));
            server.start();

            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            client.setCredentials("frank", "pw000");
            HgAuthException ex = assertThrows(HgAuthException.class,
                    () -> client.pushkey("bookmarks", "k", "", "v"));
            assertTrue(ex.getMessage().contains("frank"), "Message was: " + ex.getMessage());
            }
        } finally {
            server.stop(0);
        }
    }

    // ==========================================================
    // executeGetBinary() -- the status==403 (rather than 401) auth path with credentials set
    // (covers the "username != null" side of the exception-message ternary for the GET path;
    // status==401 with no credentials is already covered by
    // HgRemoteMockAndServeExtensionTest#testHttp401ThrowsAuthException).
    // ==========================================================

    @Test
    public void getHeadsForbiddenWithCredentialsThrowsHgAuthExceptionMentioningTheUsername() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/", exchange -> exchange.sendResponseHeaders(403, -1));
            server.start();

            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            client.setCredentials("grace", "pw111");
            HgAuthException ex = assertThrows(HgAuthException.class, client::getHeads);
            assertTrue(ex.getMessage().contains("grace"), "Message was: " + ex.getMessage());
            }
        } finally {
            server.stop(0);
        }
    }

    // ==========================================================
    // getCapabilities()/getHeads() with an empty server response -- the "!resp.trim().isEmpty()"
    // false branch (the redundant "resp != null" half of this check was dead code -- new
    // String(bytes, ...) never returns null -- and was removed from the production method).
    // ==========================================================

    @Test
    public void getCapabilitiesWithEmptyResponseReturnsEmptyList() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/", exchange -> exchange.sendResponseHeaders(200, -1));
            server.start();

            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            List<String> caps = client.getCapabilities();
            assertTrue(caps.isEmpty());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void getHeadsWithEmptyResponseReturnsEmptyList() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/", exchange -> exchange.sendResponseHeaders(200, -1));
            server.start();

            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            List<String> heads = client.getHeads();
            assertTrue(heads.isEmpty());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void getHeadsWithLeadingWhitespaceSkipsTheResultingEmptyToken() throws Exception {
        // resp.split("\\s+") on a string with LEADING whitespace produces a leading empty-string
        // token (Java's split() only strips trailing empty strings) -- exercises the
        // "!clean.isEmpty()" false branch that a plain trailing-whitespace response never hits.
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/", exchange -> {
                byte[] body = "  realhead1234567890\n".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();

            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            List<String> heads = client.getHeads();
            assertEquals(List.of("realhead1234567890"), heads);
            }
        } finally {
            server.stop(0);
        }
    }

    // ==========================================================
    // listKeys() -- entirely untested v1 direct path: real tab-separated parsing plus the
    // "line without a tab" skip branch.
    // ==========================================================

    @Test
    public void listKeysParsesTabSeparatedEntriesAndSkipsLinesWithoutTab() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        final String[] capturedQuery = {null};
        try {
            server.createContext("/", exchange -> {
                capturedQuery[0] = exchange.getRequestURI().getQuery();
                byte[] body = "bookmark1\tabc123\nmalformed-line-with-no-tab\nbookmark2\tdef456\n"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();

            try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort())) {
            Map<String, String> keys = client.listKeys("bookmarks");

            assertNotNull(capturedQuery[0]);
            assertTrue(capturedQuery[0].contains("cmd=listkeys"));
            assertTrue(capturedQuery[0].contains("namespace=bookmarks"));
            assertEquals(2, keys.size(), "The malformed line without a tab must be skipped");
            assertEquals("abc123", keys.get("bookmark1"));
            assertEquals("def456", keys.get("bookmark2"));
            }
        } finally {
            server.stop(0);
        }
    }

    // ==========================================================
    // tryEstablishV2FromDiscoveryResponse() -- the missing quarter of the credentials-copy
    // branch: username set but password null (the other 3 combinations -- both null, both set --
    // are already covered above).
    // ==========================================================

    @Test
    public void tryEstablishV2WithUsernameButNoPasswordDoesNotCopyCredentialsToDelegate() throws Exception {
        try (HgRemoteClient client = new HgRemoteClient("http://127.0.0.1/")) {
        client.setCredentials("onlyuser", null);

        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("apibase", "api/");
        descriptor.put("apis", Map.of(Wire2Commands.NAMESPACE, Map.of()));
        byte[] bytes = Cbor.encode(descriptor);

        assertTrue(invokeTryEstablishV2(client, bytes));
        }
    }
}
