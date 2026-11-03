package io.github.search5.hg4j.transport;

import com.sun.net.httpserver.HttpServer;
import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.bundle.Bundle2;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.testsupport.LocalHg;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The HTTP counterpart of the SSH session tests: a REAL hg client talks to a real {@code hg serve} through a recording proxy; the
 * requests it made are then replayed to a pristine copy of the server under the real {@code hg serve} and under hg4j's
 * {@link HgHttpWireServer}, and every answer must have the same status, the same {@code Content-Type} and the same body. Bodies
 * are compared after decompression (zlib or the engine of media type 0.2), and a bundle2 answer part by part without the
 * changegroup, whose deltas may legitimately be encoded differently.
 */
@Tag("interop")
class HttpSessionParityInteropTest {

    @TempDir
    Path tmp;
    private ParitySupport.Fixture fx;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(LocalHg.isAvailable(), LocalHg::unavailableReason);
        fx = ParitySupport.createRepo(tmp);
        Files.writeString(fx.repo().resolve(".hg/hgrc"), "[web]\nallow-push = *\npush_ssl = false\n");
    }

    // ------------------------------------------------------------------ a real hg serve

    private static final class RealServe implements AutoCloseable {
        final Process process;
        final int port;

        RealServe(Path repo) throws Exception {
            ProcessBuilder pb = LocalHg.processBuilder(repo, "serve", "-p", "0", "--address", "127.0.0.1");
            pb.redirectErrorStream(true);
            pb.environment().put("PYTHONUNBUFFERED", "1");
            process = pb.start();
            CompletableFuture<Integer> found = new CompletableFuture<>();
            List<String> seen = new CopyOnWriteArrayList<>();
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        seen.add(line);
                        Matcher m = Pattern.compile("listening at http://[^:/]+:(\\d+)/").matcher(line);
                        if (m.find()) {
                            found.complete(Integer.parseInt(m.group(1)));
                        }
                    }
                } catch (IOException ignored) {
                    // the process was stopped
                }
            });
            reader.setDaemon(true);
            reader.start();
            try {
                port = found.get(30, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                process.destroy();
                throw new AssertionError("hg serve printed no listening banner; output=" + seen);
            }
        }

        @Override
        public void close() {
            process.destroy();
        }
    }

    // ------------------------------------------------------------------ the recording proxy

    private record Recorded(String method, String uri, Map<String, String> headers, byte[] body) {
    }

    private static final Set<String> NOT_FORWARDED = Set.of("host", "content-length", "connection", "transfer-encoding", "expect", "upgrade");

    private List<Recorded> record(int targetPort, Consumer<String> client) throws Exception {
        List<Recorded> recorded = new CopyOnWriteArrayList<>();
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            Map<String, String> headers = new TreeMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> {
                if (!NOT_FORWARDED.contains(k.toLowerCase(Locale.ROOT))) {
                    headers.put(k, String.join(",", v));
                }
            });
            Recorded request = new Recorded(exchange.getRequestMethod(), exchange.getRequestURI().toString(), headers, body);
            recorded.add(request);
            try {
                HttpResponse<byte[]> answer = send(request, targetPort);
                answer.headers().firstValue("Content-Type").ifPresent(t -> exchange.getResponseHeaders().add("Content-Type", t));
                exchange.sendResponseHeaders(answer.statusCode(), answer.body().length == 0 ? -1 : answer.body().length);
                if (answer.body().length > 0) {
                    exchange.getResponseBody().write(answer.body());
                }
            } catch (Exception e) {
                exchange.sendResponseHeaders(502, -1);
            } finally {
                exchange.close();
            }
        });
        proxy.start();
        try {
            client.accept("http://127.0.0.1:" + proxy.getAddress().getPort() + "/");
        } finally {
            proxy.stop(0);
        }
        assertFalse(recorded.isEmpty(), "the client made no request");
        return recorded;
    }

    private HttpResponse<byte[]> send(Recorded request, int port) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + request.uri()));
        request.headers().forEach(b::header);
        b.method(request.method(), request.body().length == 0 && "GET".equals(request.method())
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(request.body()));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    // ------------------------------------------------------------------ normalising an answer

    private static byte[] decompress(String engine, InputStream in) throws IOException {
        return switch (engine) {
            case "zstd" -> new com.github.luben.zstd.ZstdInputStream(in).readAllBytes();
            case "zlib" -> new InflaterInputStream(in).readAllBytes();
            case "none" -> in.readAllBytes();
            default -> throw new IOException("unknown compression engine " + engine);
        };
    }

    /** {@code status|content type|engine|body}, the body decompressed and, for bundle2, as its parts without the changegroup. */
    private static String normalise(HttpResponse<byte[]> answer) throws IOException {
        String type = answer.headers().firstValue("Content-Type").orElse("");
        byte[] body = answer.body();
        String engine = "-";
        if (type.startsWith("application/mercurial-0.2") && body.length > 0) {
            int length = body[0] & 0xFF;
            engine = new String(body, 1, length, StandardCharsets.US_ASCII);
            body = decompress(engine, new ByteArrayInputStream(body, 1 + length, body.length - 1 - length));
        } else if (type.startsWith("application/mercurial-0.1") && body.length > 0) {
            try {
                body = new InflaterInputStream(new ByteArrayInputStream(body)).readAllBytes();
            } catch (IOException notCompressed) {
                // plain bytes: heads, listkeys, ... are not compressed in media type 0.1
            }
        }
        StringBuilder text = new StringBuilder();
        text.append(answer.statusCode()).append('|').append(type).append('|').append(engine).append('|');
        if (Bundle2.isBundle2(body)) {
            for (Bundle2.Part part : Bundle2.read(body)) {
                if (!part.type.equals("changegroup")) {
                    text.append(part.type).append(part.mandatory ? "!" : "?").append(part.params).append('=')
                            .append(part.type.equals("hgtagsfnodes") ? unorderedRecords(part.payload, 40)
                                    : binary(part.type) ? ParitySupport.hex(part.payload, 0, part.payload.length)
                                    : new String(part.payload, StandardCharsets.ISO_8859_1).replace("\n", "\\n")).append(";");
                }
            }
        } else {
            text.append(new String(body, StandardCharsets.ISO_8859_1).replace("\n", "\\n").replace("\0", "\\0"));
        }
        return text.toString();
    }

    /**
     * The fixed-size records of a payload as a sorted list. The original does not send the {@code hgtagsfnodes} pairs in a fixed
     * order (the same request gives a different order in different runs), so only the set of pairs can be compared.
     */
    private static String unorderedRecords(byte[] payload, int size) {
        List<String> records = new ArrayList<>();
        for (int at = 0; at + size <= payload.length; at += size) {
            records.add(ParitySupport.hex(payload, at, at + size));
        }
        java.util.Collections.sort(records);
        return String.join("|", records);
    }

    private static boolean binary(String type) {
        return type.equals("bookmarks") || type.equals("phase-heads") || type.equals("hgtagsfnodes") || type.equals("stream2");
    }

    // ------------------------------------------------------------------ one scenario

    private void assertSameHttpSession(String name, Consumer<String> client, Consumer<Path> beforeRecord,
                                       Consumer<Path> beforeReplay) throws Exception {
        Path recordDir = tmp.resolve(name + "-record");
        Path realDir = tmp.resolve(name + "-real");
        Path mineDir = tmp.resolve(name + "-mine");
        for (Path dir : List.of(recordDir, realDir, mineDir)) {
            ParitySupport.copyTree(fx.repo(), dir);
            beforeRecord.accept(dir);
        }

        List<Recorded> requests;
        try (RealServe recorder = new RealServe(recordDir)) {
            requests = record(recorder.port, client);
        }

        beforeReplay.accept(realDir);
        beforeReplay.accept(mineDir);
        List<String> expected = new ArrayList<>();
        List<String> actual = new ArrayList<>();
        try (RealServe real = new RealServe(realDir)) {
            for (Recorded request : requests) {
                expected.add(normalise(send(request, real.port)));
            }
        }
        Server mine = HgTestUtils.startServlet(new HgHttpWireServer(new HgRepository(mineDir.toFile())));
        try {
            for (Recorded request : requests) {
                actual.add(normalise(send(request, HgTestUtils.port(mine))));
            }
        } finally {
            HgTestUtils.stop(mine);
        }
        for (int i = 0; i < requests.size(); i++) {
            assertEquals(expected.get(i), actual.get(i), name + ": answer to request " + (i + 1) + " " + requests.get(i).method() + " "
                    + requests.get(i).uri() + "\n request headers: " + requests.get(i).headers());
        }
        String view = "{rev} {node|short} {phase} {bookmarks}\n";
        assertEquals(LocalHg.hgIn(tmp, realDir.getFileName().toString(), "log", "-T", view),
                LocalHg.hgIn(tmp, mineDir.getFileName().toString(), "log", "-T", view), name + ": state after the session");
    }

    private void clientCommand(Path dir, String... args) {
        try {
            LocalHg.hgTolerantIn(tmp, dir.getFileName().toString(), args);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Path newClient(String name) throws Exception {
        LocalHg.hgIn(tmp, ".", "clone", fx.repo().toString(), name);
        return tmp.resolve(name);
    }

    private void commit(Path client, String text) throws Exception {
        Files.writeString(client.resolve("a.txt"), text);
        LocalHg.hgIn(tmp, client.getFileName().toString(), "commit", "-u", "dev", "-m", "client " + text.trim());
    }

    private static final Consumer<Path> NOTHING = dir -> { };

    @Test
    @DisplayName("HTTP: clone 세션의 응답이 원본과 같다")
    void cloneSession() throws Exception {
        assertSameHttpSession("clone", url -> {
            try {
                LocalHg.hgIn(tmp, ".", "clone", url, "http-clone");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, NOTHING, NOTHING);
    }

    @Test
    @DisplayName("HTTP: 새 커밋, 이동한 북마크, 바뀐 phase 를 받는 pull 세션의 응답이 원본과 같다")
    void pullSession() throws Exception {
        Path client = newClient("pull-client");
        assertSameHttpSession("pull", url -> clientCommand(client, "pull", url), dir -> {
            try {
                Files.writeString(dir.resolve("a.txt"), "server change\n");
                LocalHg.hgIn(tmp, dir.getFileName().toString(), "commit", "-u", "dev", "-d", "2021-01-01 00:00 +0000", "-m", "server side");
                LocalHg.hgIn(tmp, dir.getFileName().toString(), "bookmark", "-f", "bm1", "-r", "tip");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, NOTHING);
    }

    @Test
    @DisplayName("HTTP: 일반 push 세션의 응답이 원본과 같다")
    void plainPushSession() throws Exception {
        Path client = newClient("push-client");
        commit(client, "pushed\n");
        assertSameHttpSession("push", url -> clientCommand(client, "push", url), NOTHING, NOTHING);
    }

    @Test
    @DisplayName("HTTP: 북마크를 함께 push 하는 세션의 응답이 원본과 같다")
    void bookmarkPushSession() throws Exception {
        Path client = newClient("bookmark-client");
        commit(client, "pushed\n");
        clientCommand(client, "bookmark", "pushedbm");
        assertSameHttpSession("bookmark", url -> clientCommand(client, "push", "-B", "pushedbm", url), NOTHING, NOTHING);
    }

    @Test
    @DisplayName("HTTP: 번들1 push 세션의 응답이 원본과 같다")
    void bundle1PushSession() throws Exception {
        Path client = newClient("bundle1-client");
        commit(client, "pushed\n");
        assertSameHttpSession("bundle1", url -> clientCommand(client, "push", "--config", "devel.legacy.exchange=bundle1", url),
                NOTHING, NOTHING);
    }

    @Test
    @DisplayName("HTTP: 경합으로 거절되는 push 세션의 응답이 원본과 같다")
    void racedPushSession() throws Exception {
        Path client = newClient("race-client");
        commit(client, "pushed\n");
        assertSameHttpSession("race", url -> clientCommand(client, "push", url), NOTHING, dir -> {
            try {
                Files.writeString(dir.resolve("a.txt"), "someone else\n");
                LocalHg.hgIn(tmp, dir.getFileName().toString(), "commit", "-u", "other", "-d", "2020-01-01 00:00 +0000", "-m", "raced");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
