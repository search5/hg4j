package io.github.search5.hg4j.lfs;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A forwarding proxy placed in front of a REAL {@code hg serve}: it relays every request unchanged and only counts the LFS
 * traffic ({@code batchRequests}, {@code uploads}, {@code downloads}). The download links of a batch answer are pointed at the
 * proxy as well, so the transfers pass through it too. {@code corruptDownloads} flips a byte of every non-batch GET answer.
 */
final class LfsTestProxy implements AutoCloseable {

    final AtomicInteger batchRequests = new AtomicInteger();
    final AtomicInteger uploads = new AtomicInteger();
    final AtomicBoolean corruptDownloads = new AtomicBoolean();
    /** Every request the proxy relayed, as {@code METHOD path}; shown in assertion messages. */
    final java.util.List<String> seen = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private final HttpServer server;
    private final int port;

    LfsTestProxy(int targetPort) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        Set<String> restricted = Set.of("host", "content-length", "connection", "expect", "upgrade");
        Set<String> skipped = Set.of("transfer-encoding", "connection", "content-length");
        server.createContext("/", exchange -> {
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                HttpRequest.Builder request = HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + targetPort + exchange.getRequestURI()))
                        .method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(body));
                exchange.getRequestHeaders().forEach((name, values) -> {
                    if (!restricted.contains(name.toLowerCase(Locale.ROOT))) {
                        values.forEach(value -> request.header(name, value));
                    }
                });
                HttpResponse<byte[]> answer = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                byte[] out = answer.body();
                String path = exchange.getRequestURI().getPath();
                if (path.contains("lfs")) {
                    seen.add(exchange.getRequestMethod() + " " + path);
                }
                if (path.endsWith("/objects/batch")) {
                    batchRequests.incrementAndGet();
                    out = new String(out, StandardCharsets.UTF_8)
                            .replace("127.0.0.1:" + targetPort, "127.0.0.1:" + port).getBytes(StandardCharsets.UTF_8);
                } else if (path.contains("/lfs/objects/") && exchange.getRequestMethod().equals("PUT")) {
                    uploads.incrementAndGet();
                } else if (corruptDownloads.get() && path.contains("/lfs/objects/") && out.length > 0) {
                    out = out.clone();
                    out[0] ^= 1;
                }
                answer.headers().map().forEach((name, values) -> {
                    if (!skipped.contains(name.toLowerCase(Locale.ROOT))) {
                        values.forEach(value -> exchange.getResponseHeaders().add(name, value));
                    }
                });
                exchange.sendResponseHeaders(answer.statusCode(), out.length == 0 ? -1 : out.length);
                if (out.length > 0) {
                    exchange.getResponseBody().write(out);
                }
            } catch (Exception e) {
                exchange.sendResponseHeaders(502, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    int port() {
        return port;
    }

    String url() {
        return "http://127.0.0.1:" + port;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
