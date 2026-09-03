package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.util.NodeIdUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HTTP counterpart of {@link HgSshUnbundleHashTest} — the same real hg {@code unbundlehash}
 * wire-encoding optimization ({@code mercurial/wireprotov1peer.py}'s {@code unbundle()}) applies
 * transport-agnostically; both {@link HgSshClient} and {@link HgRemoteClient} share the actual
 * computation via {@link NodeIdUtil#computeUnbundleHeadsWireValue}, so this test exists mainly to
 * confirm the HTTP transport actually wires the negotiated capability through to it.
 */
public class HgHttpUnbundleHashTest {

    private static String sha1HexOfSortedConcat(List<String> hexHeads) throws Exception {
        List<byte[]> raw = new ArrayList<>();
        for (String h : hexHeads) {
            raw.add(NodeIdUtil.fromHex(h));
        }
        raw.sort((a, b) -> {
            int len = Math.min(a.length, b.length);
            for (int i = 0; i < len; i++) {
                int ai = a[i] & 0xFF, bi = b[i] & 0xFF;
                if (ai != bi) return ai - bi;
            }
            return a.length - b.length;
        });
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        for (byte[] n : raw) {
            sha1.update(n);
        }
        return NodeIdUtil.toHex(sha1.digest());
    }

    @Test
    public void pushSendsHashedSentinelWhenServerAdvertisesUnbundlehash() throws Exception {
        List<String> heads = List.of(
                "aaaa000000000000000000000000000000000a",
                "bbbb000000000000000000000000000000000b");
        String expectedHashHex = sha1HexOfSortedConcat(heads);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String[] capturedQuery = {null};
        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query != null && query.contains("cmd=capabilities")) {
                byte[] resp = "lookup pushkey unbundlehash unbundle=HG10UN".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
                return;
            }
            capturedQuery[0] = query;
            exchange.getRequestBody().readAllBytes();
            byte[] resp = "push ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            client.getCapabilities(); // negotiates unbundlehash
            client.push(new byte[]{0x01, 0x02}, heads);

            assertNotNull(capturedQuery[0]);
            Map<String, String> args = decodeQuery(capturedQuery[0]);
            String heads0 = args.get("heads");
            assertNotNull(heads0);
            String[] tokens = heads0.split(" ");
            assertEquals(2, tokens.length, "expected [hashed, <digest>], got: " + heads0);
            assertEquals("hashed", new String(NodeIdUtil.fromHex(tokens[0]), StandardCharsets.US_ASCII));
            assertEquals(expectedHashHex, tokens[1]);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void pushSendsLiteralHeadsWhenServerDoesNotAdvertiseUnbundlehash() throws Exception {
        List<String> heads = List.of(
                "aaaa000000000000000000000000000000000a",
                "bbbb000000000000000000000000000000000b");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String[] capturedQuery = {null};
        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query != null && query.contains("cmd=capabilities")) {
                byte[] resp = "lookup pushkey unbundle=HG10UN".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
                return;
            }
            capturedQuery[0] = query;
            exchange.getRequestBody().readAllBytes();
            byte[] resp = "push ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            client.getCapabilities();
            client.push(new byte[]{0x01, 0x02}, heads);

            assertNotNull(capturedQuery[0]);
            Map<String, String> args = decodeQuery(capturedQuery[0]);
            assertEquals(String.join(" ", heads), args.get("heads").replace('+', ' '));
        } finally {
            server.stop(0);
        }
    }

    private static Map<String, String> decodeQuery(String query) {
        Map<String, String> args = new java.util.LinkedHashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq == -1) continue;
            args.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return args;
    }
}
