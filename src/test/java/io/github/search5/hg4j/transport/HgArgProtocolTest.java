package io.github.search5.hg4j.transport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real hg's actual v1 argument-transport contract (from {@code mercurial/httppeer.py}'s
 * {@code makev1commandrequest()}/{@code encodevalueinheaders()}, confirmed by capturing a real
 * {@code hg --debug clone} session via a raw TCP logging proxy — see
 * {@code decisions/mercurial-spec-compliance-requirement.md}): argument-bearing v1 commands like
 * {@code getbundle} are sent as GET requests with the urlencoded arg string split across
 * {@code X-HgArg-1}, {@code X-HgArg-2}, ... headers (when the server advertises
 * {@code httpheader=<N>}), not as an HTTP POST body. Before this fix, {@link HgRemoteClient}
 * always POSTed instead, which a real server's arg parser never reads for these commands —
 * silently degrading changegroup-version negotiation to bundle1 (cg1) no matter what version list
 * hg4j advertised.
 */
public class HgArgProtocolTest {

    private static HttpServer startServer(String capabilities, RequestCapture capture) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String query = exchange.getRequestURI().getQuery();
                if (query != null && query.contains("cmd=capabilities")) {
                    byte[] resp = capabilities.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, resp.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(resp);
                    }
                    return;
                }
                capture.method = exchange.getRequestMethod();
                // Header names are case-insensitive on the wire (the JDK server itself only
                // preserves the very first character's case), so this map must be too.
                capture.headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                exchange.getRequestHeaders().forEach((k, v) -> capture.headers.put(k, v.get(0)));
                capture.body = exchange.getRequestBody().readAllBytes();

                byte[] resp = "0\n".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            }
        });
        server.start();
        return server;
    }

    private static class RequestCapture {
        String method;
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        byte[] body;
    }

    /** Reassembles a real-hg {@code encodevalueinheaders}-style header chain back into one string. */
    private static String reassemble(Map<String, String> headers, String prefix) {
        StringBuilder sb = new StringBuilder();
        int n = 1;
        while (headers.containsKey(prefix + "-" + n)) {
            sb.append(headers.get(prefix + "-" + n));
            n++;
        }
        return sb.toString();
    }

    private static Map<String, String> decodeArgs(String encoded) {
        Map<String, String> out = new LinkedHashMap<>();
        if (encoded.isEmpty()) {
            return out;
        }
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    @Test
    public void getBundleSendsArgsAsGetWithXHgArgHeadersNotPostBody() throws Exception {
        RequestCapture capture = new RequestCapture();
        HttpServer server = startServer("lookup getbundle batch httpheader=1024 unbundle=HG10UN", capture);
        try {
            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            client.getCapabilities();
            assertEquals(1024, client.getMaxHttpHeaderLimit());

            client.getBundle(List.of("aaaa000000000000000000000000000000000a"),
                    List.of("bbbb000000000000000000000000000000000b"), null);

            assertEquals("GET", capture.method, "getbundle must be sent as GET, not POST, when the server advertises httpheader=");
            assertEquals(0, capture.body.length, "GET-tier request must carry no body");
            assertTrue(capture.headers.containsKey("X-HgArg-1"), "args must travel in X-HgArg-1");

            String reassembled = reassemble(capture.headers, "X-HgArg");
            Map<String, String> args = decodeArgs(reassembled);
            assertEquals("aaaa000000000000000000000000000000000a", args.get("common"));
            assertEquals("bbbb000000000000000000000000000000000b", args.get("heads"));
            assertEquals("true", args.get("cg"));
            assertNotNull(args.get("bundlecaps"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void getBundleSplitsLargeArgsAcrossMultipleXHgArgHeadersWhenLimitIsSmall() throws Exception {
        RequestCapture capture = new RequestCapture();
        HttpServer server = startServer("lookup getbundle batch httpheader=64 unbundle=HG10UN", capture);
        try {
            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            client.getCapabilities();
            assertEquals(64, client.getMaxHttpHeaderLimit());

            client.getBundle(List.of("aaaa000000000000000000000000000000000a"),
                    List.of("bbbb000000000000000000000000000000000b"), null);

            assertEquals("GET", capture.method);
            assertTrue(capture.headers.containsKey("X-HgArg-1"));
            assertTrue(capture.headers.containsKey("X-HgArg-2"), "a 64-byte header budget must force the args across more than one header");
            for (Map.Entry<String, String> e : capture.headers.entrySet()) {
                if (e.getKey().startsWith("X-HgArg-")) {
                    int lineLen = e.getKey().length() + ": ".length() + e.getValue().length() + "\r\n".length();
                    assertTrue(lineLen <= 64, "each header line should respect the 64-byte budget, was " + lineLen);
                }
            }
            assertTrue(capture.headers.containsKey("Vary"));
            assertTrue(capture.headers.get("Vary").contains("X-HgArg-1"));
            assertTrue(capture.headers.get("Vary").contains("X-HgArg-2"));

            String reassembled = reassemble(capture.headers, "X-HgArg");
            Map<String, String> args = decodeArgs(reassembled);
            assertEquals("aaaa000000000000000000000000000000000a", args.get("common"));
            assertEquals("bbbb000000000000000000000000000000000b", args.get("heads"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void getBundleUsesPostWithXHgArgsPostHeaderWhenServerAdvertisesHttppostargs() throws Exception {
        RequestCapture capture = new RequestCapture();
        HttpServer server = startServer("lookup getbundle batch httppostargs httpheader=1024 unbundle=HG10UN", capture);
        try {
            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            client.getCapabilities();

            client.getBundle(List.of("aaaa000000000000000000000000000000000a"),
                    List.of("bbbb000000000000000000000000000000000b"), null);

            assertEquals("POST", capture.method, "httppostargs tier must POST");
            assertFalse(capture.headers.containsKey("X-HgArg-1"), "httppostargs tier must not also use X-HgArg headers");
            assertTrue(capture.headers.containsKey("X-HgArgs-Post"));
            assertEquals(String.valueOf(capture.body.length), capture.headers.get("X-HgArgs-Post"),
                    "X-HgArgs-Post must equal the actual POST body length");

            Map<String, String> args = decodeArgs(new String(capture.body, StandardCharsets.UTF_8));
            assertEquals("aaaa000000000000000000000000000000000a", args.get("common"));
            assertEquals("bbbb000000000000000000000000000000000b", args.get("heads"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void xHgProto1HeaderReflectsNegotiatedMediaTypesAndCompressionSortedAsRealHgDoes() throws Exception {
        RequestCapture capture = new RequestCapture();
        HttpServer server = startServer(
                "lookup getbundle batch httpheader=1024 httpmediatype=0.1,0.2,0.2tx compression=zstd,zlib unbundle=HG10UN",
                capture);
        try {
            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            client.getCapabilities();

            client.getBundle(List.of(), List.of("bbbb000000000000000000000000000000000b"), null);

            assertTrue(capture.headers.containsKey("X-HgProto-1"), "x-hgproto-1 must be sent once media types are known");
            String proto1 = reassemble(capture.headers, "X-HgProto");
            // Captured from a real hg client: "0.1 0.2 comp=zstd,zlib,none,bzip2 partial-pull" -- sorted.
            assertEquals("0.1 0.2 comp=zstd,zlib partial-pull", proto1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void pushkeyUsesXHgArgHeadersButAlwaysPostsRegardlessOfHttpMethod() throws Exception {
        // pushkey is a documented EXCEPTION to this file's general "GET + X-HgArg headers"
        // rule above: real hg's HTTP server enforces POST for every push-permission command
        // regardless of httppostargs (mercurial/hgweb/common.py checkauthz: "push requires
        // POST request"), and real hg's own client special-cases exactly this
        // (mercurial/httppeer.py makev1commandrequest: `if cmd == b'pushkey': args[b'data'] =
        // b''`, forcing urllib to POST even with an empty body). So pushkey's ARGUMENTS still
        // travel via X-HgArg headers when httppostargs isn't advertised (same as any other
        // argument-bearing v1 command), but the HTTP METHOD is always POST, never GET --
        // confirmed against real hg 7.2.2 over HTTP, 2026-09-04 (a push whose bookmark-move
        // silently no-op'd because the GET was rejected 405 by the real server, since
        // PushCommand only logs a bookmark-sync failure as a non-fatal warning).
        RequestCapture capture = new RequestCapture();
        HttpServer server = startServer("lookup pushkey batch httpheader=1024 unbundle=HG10UN", capture);
        try {
            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            client.getCapabilities();

            client.pushkey("bookmarks", "mybookmark", "", "cccc000000000000000000000000000000000c");

            assertEquals("POST", capture.method);
            String reassembled = reassemble(capture.headers, "X-HgArg");
            Map<String, String> args = decodeArgs(reassembled);
            assertEquals("bookmarks", args.get("namespace"));
            assertEquals("mybookmark", args.get("key"));
            assertEquals("cccc000000000000000000000000000000000c", args.get("new"));
        } finally {
            server.stop(0);
        }
    }
}
