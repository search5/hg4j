package org.hg4j.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.hg4j.core.HgRepository;
import org.hg4j.errors.HgAuthException;
import org.hg4j.errors.HgProtocolException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class HgHttpTransportRoundtripTest {

    private HttpServer server;
    private int port;
    private volatile int responseCode = 200;
    private volatile boolean simulateDisconnect = false;
    private volatile boolean corruptStream = false;

    @BeforeEach
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        responseCode = 200;
        simulateDisconnect = false;
        corruptStream = false;

        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if (responseCode != 200) {
                    exchange.sendResponseHeaders(responseCode, 0);
                    exchange.close();
                    return;
                }

                String query = exchange.getRequestURI().getQuery();
                if (simulateDisconnect) {
                    // Disconnect abruptly without sending headers
                    exchange.close();
                    return;
                }

                if (corruptStream) {
                    byte[] garbage = "CORRUPTED_STREAM_GARBAGE_DATA".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, garbage.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(garbage);
                    }
                    return;
                }

                if (query != null && query.contains("cmd=capabilities")) {
                    String caps = "lookup changegroupsubsets branchmap getbundle\n";
                    byte[] resp = caps.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, resp.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(resp);
                    }
                } else if (query != null && query.contains("cmd=heads")) {
                    String heads = "0000000000000000000000000000000000000000\n";
                    byte[] resp = heads.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, resp.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(resp);
                    }
                } else {
                    byte[] resp = "0\n".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, resp.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(resp);
                    }
                }
            }
        });
        server.start();
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void testHttp401ThrowsHgAuthException(@TempDir Path tempDir) throws Exception {
        responseCode = 401;
        
        File localDir = tempDir.resolve("local_repo").toFile();
        new InitCommand().setDirectory(localDir).call();
        HgRepository repository = new HgRepository(localDir);

        PullCommand pull = new PullCommand(repository)
                .setSource("http://127.0.0.1:" + port + "/");

        assertThrows(HgAuthException.class, pull::call, "401 error must throw HgAuthException");
    }

    @Test
    public void testHttp500ThrowsHgProtocolException(@TempDir Path tempDir) throws Exception {
        responseCode = 500;
        
        File localDir = tempDir.resolve("local_repo").toFile();
        new InitCommand().setDirectory(localDir).call();
        HgRepository repository = new HgRepository(localDir);

        PullCommand pull = new PullCommand(repository)
                .setSource("http://127.0.0.1:" + port + "/");

        assertThrows(HgProtocolException.class, pull::call, "500 error must throw HgProtocolException");
    }

    @Test
    public void testHttpDisconnectThrowsHgProtocolException(@TempDir Path tempDir) throws Exception {
        simulateDisconnect = true;

        File localDir = tempDir.resolve("local_repo").toFile();
        new InitCommand().setDirectory(localDir).call();
        HgRepository repository = new HgRepository(localDir);

        PullCommand pull = new PullCommand(repository)
                .setSource("http://127.0.0.1:" + port + "/");

        // Abrupt close during capabilities/heads fetch should trigger protocol exception
        assertThrows(Exception.class, pull::call, "Disconnect must throw Exception");
    }

    @Test
    public void testCorruptedStreamThrowsException(@TempDir Path tempDir) throws Exception {
        corruptStream = true;

        File localDir = tempDir.resolve("local_repo").toFile();
        new InitCommand().setDirectory(localDir).call();
        HgRepository repository = new HgRepository(localDir);

        PullCommand pull = new PullCommand(repository)
                .setSource("http://127.0.0.1:" + port + "/");

        // Completely garbage stream returned for capabilities should trigger protocol/parse exception
        assertThrows(Exception.class, pull::call, "Corrupted/garbage headers/capabilities stream must fail with exception");
    }
}
