package io.github.search5.hg4j.transport;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.InflaterInputStream;

/**
 * Test-only transparent HTTP reverse proxy in front of a real {@code hg serve} backend that
 * rewrites <b>only</b> the {@code ?cmd=capabilities} response body, stripping named capability
 * tokens before forwarding it on to hg4j -- used to force hg4j's own client-side negotiation
 * fallback logic down paths a real hg server has no config knob to actually produce.
 * {@code httpheader=<N>} and {@code unbundlehash} are both <em>unconditional</em> entries in real
 * hg's own {@code addcapabilities()}/{@code wireprotocaps} (confirmed by reading
 * {@code mercurial/wireprotoserver.py} and {@code mercurial/wireprotov1server.py} directly,
 * 2026-09-03) -- there is no hgrc setting that turns either off. Every other request/response
 * (including every OTHER command's response, and even the capabilities response's own framing/
 * compression semantics once decoded) passes through byte-for-byte unmodified; real hg itself is
 * never touched or modified.
 */
final class CapabilityStrippingHttpProxy implements AutoCloseable {

    private final HttpServer server;
    final String url;

    CapabilityStrippingHttpProxy(String backendBaseUrl, Set<String> tokenPrefixesToStrip) throws IOException {
        String normalizedBackend = backendBaseUrl.endsWith("/")
                ? backendBaseUrl.substring(0, backendBaseUrl.length() - 1) : backendBaseUrl;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                forward(exchange, normalizedBackend, tokenPrefixesToStrip);
            } catch (Exception e) {
                byte[] msg = String.valueOf(e).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(502, msg.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(msg);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private static void forward(HttpExchange exchange, String normalizedBackend, Set<String> tokenPrefixesToStrip)
            throws IOException {
        URL backend = URI.create(normalizedBackend + exchange.getRequestURI()).toURL();
        HttpURLConnection conn = (HttpURLConnection) backend.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod(exchange.getRequestMethod());
        for (Map.Entry<String, List<String>> h : exchange.getRequestHeaders().entrySet()) {
            String key = h.getKey();
            if (key.equalsIgnoreCase("Host") || key.equalsIgnoreCase("Content-Length")
                    || key.equalsIgnoreCase("Connection") || key.equalsIgnoreCase("Transfer-Encoding")) {
                continue;
            }
            for (String v : h.getValue()) {
                conn.addRequestProperty(key, v);
            }
        }
        byte[] reqBody = exchange.getRequestBody().readAllBytes();
        if ("POST".equalsIgnoreCase(exchange.getRequestMethod()) || reqBody.length > 0) {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(reqBody);
            }
        }

        int status = conn.getResponseCode();
        InputStream respStream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        byte[] body = respStream == null ? new byte[0] : respStream.readAllBytes();
        String contentType = conn.getContentType();

        String query = exchange.getRequestURI().getRawQuery();
        boolean isCapabilities = query != null
                && (query.equals("cmd=capabilities") || query.startsWith("cmd=capabilities&"));
        if (isCapabilities && body.length > 0) {
            body = stripTokens(body, tokenPrefixesToStrip);
        }

        Headers respHeaders = exchange.getResponseHeaders();
        if (contentType != null) {
            respHeaders.set("Content-Type", contentType);
        }
        if (body.length == 0) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }
        conn.disconnect();
    }

    /**
     * Real hg's {@code ?cmd=capabilities} response is unconditionally zlib-compressed (RFC1950
     * framing, {@code mercurial/wireprotoserver.py}'s {@code _httpresponsetype} falls through to
     * its {@code HGTYPE}/zlib branch whenever the request's {@code X-HgProto-1} doesn't advertise
     * {@code "0.2"} -- exactly hg4j's own first-negotiation request, which sends only
     * {@code X-HgProto-1: cbor} for the v2-upgrade probe). Decompress, filter the space-separated
     * token line, and re-emit as plain uncompressed text: {@link HgRemoteClient}'s own
     * {@code unwrapResponseStream} auto-detects the zlib magic bytes ({@code 0x78 0x9C}-family)
     * and only inflates when they're present, so handing back an already-plain response (which a
     * filtered capabilities line will never coincidentally start with) is safe either way.
     */
    private static byte[] stripTokens(byte[] body, Set<String> tokenPrefixesToStrip) {
        byte[] plain = body;
        if (body.length >= 2 && (body[0] & 0xFF) == 0x78) {
            try (InflaterInputStream iis = new InflaterInputStream(new ByteArrayInputStream(body))) {
                plain = iis.readAllBytes();
            } catch (IOException notActuallyZlib) {
                plain = body;
            }
        }
        String text = new String(plain, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            return plain;
        }
        StringBuilder rewritten = new StringBuilder();
        for (String tok : text.split("\\s+")) {
            boolean strip = false;
            for (String prefix : tokenPrefixesToStrip) {
                if (tok.equals(prefix) || tok.startsWith(prefix)) {
                    strip = true;
                    break;
                }
            }
            if (!strip) {
                if (rewritten.length() > 0) {
                    rewritten.append(' ');
                }
                rewritten.append(tok);
            }
        }
        return rewritten.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
