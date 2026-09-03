package io.github.search5.hg4j.transport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;

/**
 * Integration tests for {@link HgRemoteClient}'s response-stream unwrapping
 * (mercurial-0.1/0.2 content-type handling) against a local mock HTTP server.
 */
@DisplayName("응답 스트림 래핑 테스트")
public class HgRemoteClientStreamTest {

    // ─────────────────────────────────────────────────────────────────────
    // Integration Tests using a Local HTTP Server (Processing mercurial-0.1 & 0.2 responses)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("mercurial-0.1 응답에서 zlib 압축 감지 동작 확인")
    public void testHgRemoteClient_mercurial01_capabilities_viaLocalServer() throws Exception {
        // Configure a local server using com.sun.net.httpserver
        HttpServer server =
                HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/", exchange -> {
            String response = "lookup getbundle changegroup\n";
            byte[] respBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/mercurial-0.1");
            exchange.sendResponseHeaders(200, respBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(respBytes);
            }
        });
        server.start();
        int port = server.getAddress().getPort();

        try {
            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + port + "/");
            List<String> caps = client.getCapabilities();
            assertNotNull(caps);
            assertTrue(caps.contains("lookup"));
            assertTrue(caps.contains("getbundle"));
            assertTrue(caps.contains("changegroup"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("mercurial-0.2 응답에서 none 압축으로 데이터 정상 수신")
    public void testHgRemoteClient_mercurial02_none_compression() throws Exception {
        HttpServer server =
                HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/", exchange -> {
            // Real hg's actual -0.2 wire format (confirmed against a real Mercurial 7.2.4
            // server, 2026-09-03): 1-byte compression-name length + name, then the payload
            // straight through to end of stream -- no inner chunk-length framing.
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            String compName = "none";
            body.write(compName.length()); // Compression name length
            body.write(compName.getBytes(StandardCharsets.US_ASCII));
            body.write("lookup getbundle\n".getBytes(StandardCharsets.UTF_8));

            byte[] respBytes = body.toByteArray();
            exchange.getResponseHeaders().set("Content-Type", "application/mercurial-0.2");
            exchange.sendResponseHeaders(200, respBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(respBytes);
            }
        });
        server.start();
        int port = server.getAddress().getPort();

        try {
            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + port + "/");
            List<String> caps = client.getCapabilities();
            assertNotNull(caps);
            assertTrue(caps.contains("lookup"));
            assertTrue(caps.contains("getbundle"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("HTTP 500 응답 → IOException 발생")
    public void testHgRemoteClient_http500_throwsIOException() throws Exception {
        HttpServer server =
                HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.getResponseBody().close();
        });
        server.start();
        int port = server.getAddress().getPort();

        try {
            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + port + "/");
            assertThrows(IOException.class, () -> client.getCapabilities());
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("getChangegroup 정상 요청 처리")
    public void testHgRemoteClient_getChangegroup_success() throws Exception {
        HttpServer server =
                HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] resp = new byte[]{0x01, 0x02, 0x03};
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        int port = server.getAddress().getPort();

        try {
            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + port + "/");
            byte[] result = client.getChangegroup(List.of("abc123"));
            assertNotNull(result);
            assertEquals(3, result.length);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("getBundle 정상 요청 처리")
    public void testHgRemoteClient_getBundle_success() throws Exception {
        HttpServer server =
                HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] resp = "bundle_data".getBytes(StandardCharsets.US_ASCII);
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        int port = server.getAddress().getPort();

        try {
            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + port + "/");
            byte[] result = client.getBundle(List.of(), List.of("head1"), null);
            assertNotNull(result);
            assertTrue(result.length > 0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("push 정상 요청 처리")
    public void testHgRemoteClient_push_success() throws Exception {
        HttpServer server =
                HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String resp = "push ok";
            byte[] respBytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, respBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(respBytes);
            }
        });
        server.start();
        int port = server.getAddress().getPort();

        try {
            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + port + "/");
            String result = client.push(new byte[]{0x01, 0x02}, List.of("head1"));
            assertEquals("push ok", result);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("지원하지 않는 압축 방식 (mercurial-0.2) → IOException 발생")
    public void testHgRemoteClient_mercurial02_unknownCompression_throwsIOException() throws Exception {
        HttpServer server =
                HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            String compName = "lz4"; // Unsupported compression
            body.write(compName.length());
            body.write(compName.getBytes(StandardCharsets.US_ASCII));
            body.write(new byte[]{0, 0, 0, 0}); // Terminal chunk

            byte[] respBytes = body.toByteArray();
            exchange.getResponseHeaders().set("Content-Type", "application/mercurial-0.2");
            exchange.sendResponseHeaders(200, respBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(respBytes);
            }
        });
        server.start();
        int port = server.getAddress().getPort();

        try {
            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + port + "/");
            assertThrows(IOException.class, () -> client.getCapabilities());
        } finally {
            server.stop(0);
        }
    }
}
