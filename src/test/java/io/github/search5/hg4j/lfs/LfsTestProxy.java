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
    /** The {@code User-Agent} of every request. */
    final java.util.List<String> userAgents = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    /** When set, a request without credentials gets a 401 with this {@code WWW-Authenticate} challenge. */
    volatile String authChallenge;
    /** When set, every request whose {@code Authorization} header is not exactly this value gets a 401 Basic challenge. */
    volatile String requiredAuthorization;
    /** How many requests were refused with a 401 because of {@link #requiredAuthorization}. */
    final AtomicInteger refusals = new AtomicInteger();
    /** The number of LFS object requests (GET or PUT of a blob) to drop without any answer, closing the connection. */
    final AtomicInteger dropObjectRequests = new AtomicInteger();
    /** Every blob request that arrived (dropped ones too). */
    final AtomicInteger objectRequests = new AtomicInteger();
    /** Milliseconds every blob request is held before it is relayed, to make concurrent transfers overlap. */
    volatile int objectDelayMillis;
    private final AtomicInteger activeObjects = new AtomicInteger();
    /** The highest number of blob requests that were being handled at the same time. */
    final AtomicInteger maxConcurrentObjects = new AtomicInteger();
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
                String agent = exchange.getRequestHeaders().getFirst("User-Agent");
                if (agent != null) {
                    userAgents.add(agent);
                }
                String required = requiredAuthorization;
                if (required != null && !required.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    refusals.incrementAndGet();
                    exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"hg\"");
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }
                String challenge = authChallenge;
                if (challenge != null && exchange.getRequestHeaders().getFirst("Authorization") == null) {
                    exchange.getResponseHeaders().add("WWW-Authenticate", challenge);
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }
                boolean blobRequest = exchange.getRequestURI().getPath().contains("/.hg/lfs/objects/");
                if (blobRequest) {
                    objectRequests.incrementAndGet();
                    int active = activeObjects.incrementAndGet();
                    maxConcurrentObjects.accumulateAndGet(active, Math::max);
                    if (dropObjectRequests.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
                        activeObjects.decrementAndGet();
                        exchange.close(); // no answer at all: the client sees the connection die
                        return;
                    }
                    if (objectDelayMillis > 0) {
                        Thread.sleep(objectDelayMillis);
                    }
                }
                try {
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
                } finally {
                    if (blobRequest) {
                        activeObjects.decrementAndGet();
                    }
                }
            } catch (Exception e) {
                exchange.sendResponseHeaders(502, -1);
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool()); // the default is one thread: no concurrency
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
