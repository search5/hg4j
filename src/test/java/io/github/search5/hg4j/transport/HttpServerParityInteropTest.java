package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.testsupport.LocalHg;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Differential test of the HTTP wire server against a real {@code hg serve} (the 7.2.4 oracle) serving the very same
 * repository: the same raw GET request must give the same status, content type and body. Only requests whose answer does not
 * depend on server-side choices are compared byte for byte; the known differences (capability tokens, store order of
 * {@code stream_out}) are covered by the real hg client tests instead and listed in
 * {@code llm-wiki/backlog/wire-server-command-gaps.md}.
 */
@Tag("interop")
class HttpServerParityInteropTest {

    @TempDir
    Path tmp;

    private Process real;
    private int realPort;
    private Server hg4j;
    private int hg4jPort;
    private String node0;
    private String node2;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(LocalHg.isAvailable(), "no local hg (see scripts/build-local-hg.sh)");
        Path repo = Files.createDirectories(tmp.resolve("server"));
        LocalHg.hgIn(tmp, "server", "init", ".");
        for (int i = 0; i < 3; i++) {
            Files.writeString(repo.resolve("a.txt"), "a" + i + "\n");
            if (i == 0) {
                LocalHg.hgIn(tmp, "server", "add", "a.txt");
            }
            LocalHg.hgIn(tmp, "server", "commit", "-u", "dev", "-m", "c" + i);
        }
        LocalHg.hgIn(tmp, "server", "bookmark", "bm1", "-r", "1");
        node0 = LocalHg.hgIn(tmp, "server", "log", "-r", "0", "--template", "{node}");
        node2 = LocalHg.hgIn(tmp, "server", "log", "-r", "2", "--template", "{node}");

        ProcessBuilder pb = LocalHg.processBuilder(repo, "serve", "-p", "0", "--address", "127.0.0.1",
                "--config", "web.allow-push=*", "--config", "web.push_ssl=false");
        pb.redirectErrorStream(true);
        pb.environment().put("PYTHONUNBUFFERED", "1");
        real = pb.start();
        CompletableFuture<Integer> port = new CompletableFuture<>();
        List<String> seen = new ArrayList<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(real.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    synchronized (seen) {
                        seen.add(line);
                    }
                    // the banner names the host "localhost", not the address it is bound to
                    Matcher m = Pattern.compile("listening at http://[^:/]+:(\\d+)/").matcher(line);
                    if (m.find()) {
                        port.complete(Integer.parseInt(m.group(1)));
                    }
                }
            } catch (IOException ignored) {
                // the server process was stopped; nothing more to read
            }
        });
        reader.setDaemon(true);
        reader.start();
        try {
            realPort = port.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            real.destroy();
            synchronized (seen) {
                throw new AssertionError("hg serve printed no listening banner; output=" + seen);
            }
        }
        hg4j = HgTestUtils.startServlet(new HgHttpWireServer(new HgRepository(repo.toFile())));
        hg4jPort = HgTestUtils.port(hg4j);
    }

    @AfterEach
    void tearDown() {
        if (hg4j != null) {
            HgTestUtils.stop(hg4j);
        }
        if (real != null) {
            real.destroy();
        }
    }

    private record Answer(int status, String contentType, String body) {
    }

    private Answer get(int port, String query) throws Exception {
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/?" + query)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        return new Answer(r.statusCode(), r.headers().firstValue("Content-Type").orElse(""),
                new String(r.body(), StandardCharsets.ISO_8859_1));
    }

    @Test
    @DisplayName("같은 GET 요청에 상태, Content-Type, 본문이 실제 hg serve 와 같다")
    void sameAnswerAsRealServer() throws Exception {
        String[] queries = {
                "cmd=heads", "cmd=branchmap", "cmd=lookup&key=tip", "cmd=lookup&key=bm1", "cmd=lookup&key=nosuch",
                "cmd=known&nodes=" + node0 + "+" + "ab".repeat(20),
                "cmd=listkeys&namespace=bookmarks", "cmd=listkeys&namespace=namespaces", "cmd=listkeys&namespace=phases",
                "cmd=between&pairs=" + node2 + "-" + node0, "cmd=branches&nodes=" + node2,
                "cmd=debugwireargs&one=a&two=b", "cmd=clonebundles",
        };
        for (String q : queries) {
            assertEquals(get(realPort, q), get(hg4jPort, q), q);
        }
    }

    @Test
    @DisplayName("알 수 없는 명령은 실제 hg 처럼 HTTP 400 이다")
    void unknownCommandIsBadRequest() throws Exception {
        Answer expected = get(realPort, "cmd=thisdoesnotexist");
        Answer actual = get(hg4jPort, "cmd=thisdoesnotexist");
        assertEquals(400, expected.status());
        assertEquals(expected.status(), actual.status());
        assertTrue(actual.body().contains("no such method"), actual.body());
    }

    @Test
    @DisplayName("쓰기 명령(pushkey, unbundle)을 GET 으로 보내면 실제 hg 처럼 405 와 같은 본문을 준다")
    void pushCommandsRequirePost() throws Exception {
        for (String q : new String[]{"cmd=pushkey&namespace=bookmarks&key=x&old=&new=" + node0, "cmd=unbundle&heads=" + node2}) {
            Answer expected = get(realPort, q);
            assertEquals(405, expected.status(), q);
            assertEquals(expected, get(hg4jPort, q), q);
        }
    }

    @Test
    @DisplayName("실제 hg 클라이언트가 HTTP 로 --stream 복제를 받고 이력이 같다")
    void realClientStreamClone() throws Exception {
        Path dest = tmp.resolve("streamed");
        LocalHg.hgIn(tmp, ".", "clone", "--stream", "http://127.0.0.1:" + hg4jPort + "/", dest.toString());
        assertEquals(LocalHg.hgIn(tmp, "server", "log", "-T", "{node}\n"),
                LocalHg.hgIn(tmp, "streamed", "log", "-T", "{node}\n"));
        assertEquals("a2\n", Files.readString(dest.resolve("a.txt")));
    }
}
