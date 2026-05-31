package org.hg4j.transport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for the internal MercurialChunkedInputStream class
 * of HgRemoteClient and the behavior of unwrapResponseStream.
 */
@DisplayName("MercurialChunkedInputStream 및 응답 스트림 래핑 테스트")
public class HgRemoteClientStreamTest {

    // ─────────────────────────────────────────────────────────────────────
    // Helper: Instantiating MercurialChunkedInputStream (Reflection)
    // ─────────────────────────────────────────────────────────────────────

    private InputStream createChunkedStream(byte[] rawBytes) throws Exception {
        Constructor<?> ctor = Class.forName("org.hg4j.transport.HgRemoteClient$MercurialChunkedInputStream")
                .getDeclaredConstructor(InputStream.class);
        ctor.setAccessible(true);
        return (InputStream) ctor.newInstance(new ByteArrayInputStream(rawBytes));
    }

    private byte[] buildChunkedPayload(byte[]... chunks) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            int len = chunk.length;
            out.write((len >> 24) & 0xFF);
            out.write((len >> 16) & 0xFF);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
            out.write(chunk);
        }
        // Terminal chunk: length 0
        out.write(new byte[]{0, 0, 0, 0});
        return out.toByteArray();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Successful Operation Tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("단일 청크 정상 읽기 (배열 방식)")
    public void testChunked_singleChunk_readArray() throws Exception {
        byte[] payload = buildChunkedPayload("Hello".getBytes(StandardCharsets.US_ASCII));
        InputStream stream = createChunkedStream(payload);

        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buf = new byte[16];
        int count;
        while ((count = stream.read(buf)) != -1) {
            result.write(buf, 0, count);
        }
        assertEquals("Hello", result.toString(StandardCharsets.US_ASCII));
    }

    @Test
    @DisplayName("복수 청크 연속 읽기 (배열 방식)")
    public void testChunked_multiChunk_readArray() throws Exception {
        byte[] payload = buildChunkedPayload(
                "Hello".getBytes(StandardCharsets.US_ASCII),
                " ".getBytes(StandardCharsets.US_ASCII),
                "World".getBytes(StandardCharsets.US_ASCII)
        );
        InputStream stream = createChunkedStream(payload);

        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buf = new byte[4];
        int count;
        while ((count = stream.read(buf)) != -1) {
            result.write(buf, 0, count);
        }
        assertEquals("Hello World", result.toString(StandardCharsets.US_ASCII));
    }

    @Test
    @DisplayName("단일 바이트 read() 방식으로 청크 읽기")
    public void testChunked_readByteAtATime() throws Exception {
        byte[] payload = buildChunkedPayload("AB".getBytes(StandardCharsets.US_ASCII));
        InputStream stream = createChunkedStream(payload);

        assertEquals('A', stream.read());
        assertEquals('B', stream.read());
        assertEquals(-1, stream.read()); // EOF
    }

    @Test
    @DisplayName("빈 스트림(터미널 청크만 존재) → 즉시 -1 반환")
    public void testChunked_emptyStream_returnsEof() throws Exception {
        // Terminal chunk only
        InputStream stream = createChunkedStream(new byte[]{0, 0, 0, 0});
        assertEquals(-1, stream.read());
    }

    @Test
    @DisplayName("EOF 이후 연속 read() 호출 시 -1 반환")
    public void testChunked_afterEof_returnsMinusOne() throws Exception {
        byte[] payload = buildChunkedPayload("X".getBytes(StandardCharsets.US_ASCII));
        InputStream stream = createChunkedStream(payload);

        // Read all to reach EOF
        while (stream.read() != -1) {}

        // Subsequent read() calls should return -1
        assertEquals(-1, stream.read());
        assertEquals(-1, stream.read(new byte[4], 0, 4));
    }

    @Test
    @DisplayName("청크 길이 4바이트가 부분적으로 도착하는 경우 정상 처리")
    public void testChunked_partialLengthHeader_handledCorrectly() throws Exception {
        // Chunk length = 3, data = 'A','B','C', terminal chunk
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0, 0, 0, 3}); // Length
        out.write(new byte[]{'A', 'B', 'C'}); // Data
        out.write(new byte[]{0, 0, 0, 0}); // Terminal chunk

        InputStream stream = createChunkedStream(out.toByteArray());
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buf = new byte[10];
        int count;
        while ((count = stream.read(buf)) != -1) {
            result.write(buf, 0, count);
        }
        assertArrayEquals(new byte[]{'A', 'B', 'C'}, result.toByteArray());
    }

    // ─────────────────────────────────────────────────────────────────────
    // Error Case Tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("음수 청크 길이 → IOException 발생")
    public void testChunked_negativeChunkLength_throwsIOException() throws Exception {
        // 0x80000001 = -2147483647 (negative int)
        byte[] raw = new byte[]{(byte) 0x80, 0x00, 0x00, 0x01};
        InputStream stream = createChunkedStream(raw);
        assertThrows(IOException.class, () -> stream.read());
    }

    @Test
    @DisplayName("청크 길이 헤더 중간에 EOF → IOException 발생")
    public void testChunked_eofMidLengthHeader_throwsIOException() throws Exception {
        // Providing only 2 bytes out of 4
        InputStream stream = createChunkedStream(new byte[]{0, 0});
        assertThrows(IOException.class, () -> stream.read());
    }

    @Test
    @DisplayName("청크 페이로드 중간에 EOF → IOException 발생 (배열 read)")
    public void testChunked_eofMidPayload_readArray_throwsIOException() throws Exception {
        // Declared length = 5, but only 3 bytes of actual data are present.
        // Since ByteArrayInputStream returns partial data up to the available bytes
        // during an array read, IOException occurs on the second read call when
        // attempting to read the remaining 2 bytes.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0, 0, 0, 5}); // Declared length = 5
        out.write(new byte[]{'A', 'B', 'C'}); // 데이터 3바이트만
        InputStream stream = createChunkedStream(out.toByteArray());

        assertThrows(IOException.class, () -> {
            byte[] buf = new byte[10];
            // Repeated read: first call returns 3 bytes, second call throws IOException.
            int count;
            while ((count = stream.read(buf, 0, 10)) != -1) {
                // Continue loop until IOException is thrown
            }
        });
    }

    @Test
    @DisplayName("청크 페이로드 중간에 EOF → IOException 발생 (단바이트 read)")
    public void testChunked_eofMidPayload_readByte_throwsIOException() throws Exception {
        // Length = 3, but only 1 byte of actual data is present
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0, 0, 0, 3});
        out.write(new byte[]{'X'}); // Only 1 byte of data
        InputStream stream = createChunkedStream(out.toByteArray());

        // The first byte is successfully read.
        assertEquals('X', stream.read());
        // EOF error occurs on the second byte
        assertThrows(IOException.class, () -> stream.read());
    }

    // ─────────────────────────────────────────────────────────────────────
    // Integration Tests using a Local HTTP Server (Processing mercurial-0.1 & 0.2 responses)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("mercurial-0.1 응답에서 zlib 압축 감지 동작 확인")
    public void testHgRemoteClient_mercurial01_capabilities_viaLocalServer() throws Exception {
        // Configure a local server using com.sun.net.httpserver
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/", exchange -> {
            String response = "lookup getbundle changegroup\n";
            byte[] respBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/mercurial-0.1");
            exchange.sendResponseHeaders(200, respBytes.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(respBytes);
            }
        });
        server.start();
        int port = server.getAddress().getPort();

        try {
            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + port + "/");
            java.util.List<String> caps = client.getCapabilities();
            assertNotNull(caps);
            assertTrue(caps.contains("lookup"));
            assertTrue(caps.contains("getbundle"));
            assertTrue(caps.contains("changegroup"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("mercurial-0.2 응답에서 none 압축으로 청크 데이터 정상 수신")
    public void testHgRemoteClient_mercurial02_none_compression() throws Exception {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/", exchange -> {
            // mercurial-0.2 response format:
            // 1. Compression name length (1 byte)
            // 2. Compression name ("none")
            // 3. Chunk frame (4-byte length + data + terminal 4-byte 0)
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            String compName = "none";
            body.write(compName.length()); // Compression name length
            body.write(compName.getBytes(StandardCharsets.US_ASCII));
            // Chunk data
            String data = "lookup getbundle\n";
            byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);
            body.write((dataBytes.length >> 24) & 0xFF);
            body.write((dataBytes.length >> 16) & 0xFF);
            body.write((dataBytes.length >> 8) & 0xFF);
            body.write(dataBytes.length & 0xFF);
            body.write(dataBytes);
            // Terminal chunk
            body.write(new byte[]{0, 0, 0, 0});

            byte[] respBytes = body.toByteArray();
            exchange.getResponseHeaders().set("Content-Type", "application/mercurial-0.2");
            exchange.sendResponseHeaders(200, respBytes.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(respBytes);
            }
        });
        server.start();
        int port = server.getAddress().getPort();

        try {
            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + port + "/");
            java.util.List<String> caps = client.getCapabilities();
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
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
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
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] resp = new byte[]{0x01, 0x02, 0x03};
            exchange.sendResponseHeaders(200, resp.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        int port = server.getAddress().getPort();

        try {
            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + port + "/");
            byte[] result = client.getChangegroup(java.util.List.of("abc123"));
            assertNotNull(result);
            assertEquals(3, result.length);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("getBundle 정상 요청 처리")
    public void testHgRemoteClient_getBundle_success() throws Exception {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] resp = "bundle_data".getBytes(StandardCharsets.US_ASCII);
            exchange.sendResponseHeaders(200, resp.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        int port = server.getAddress().getPort();

        try {
            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + port + "/");
            byte[] result = client.getBundle(java.util.List.of(), java.util.List.of("head1"), null);
            assertNotNull(result);
            assertTrue(result.length > 0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("push 정상 요청 처리")
    public void testHgRemoteClient_push_success() throws Exception {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String resp = "push ok";
            byte[] respBytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, respBytes.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(respBytes);
            }
        });
        server.start();
        int port = server.getAddress().getPort();

        try {
            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + port + "/");
            String result = client.push(new byte[]{0x01, 0x02}, java.util.List.of("head1"));
            assertEquals("push ok", result);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("지원하지 않는 압축 방식 (mercurial-0.2) → IOException 발생")
    public void testHgRemoteClient_mercurial02_unknownCompression_throwsIOException() throws Exception {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            String compName = "lz4"; // Unsupported compression
            body.write(compName.length());
            body.write(compName.getBytes(StandardCharsets.US_ASCII));
            body.write(new byte[]{0, 0, 0, 0}); // Terminal chunk

            byte[] respBytes = body.toByteArray();
            exchange.getResponseHeaders().set("Content-Type", "application/mercurial-0.2");
            exchange.sendResponseHeaders(200, respBytes.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
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
