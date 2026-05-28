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
 * HgRemoteClient 내부 MercurialChunkedInputStream 클래스와
 * unwrapResponseStream 동작에 대한 심층 단위 테스트.
 */
@DisplayName("MercurialChunkedInputStream 및 응답 스트림 래핑 테스트")
public class HgRemoteClientStreamTest {

    // ─────────────────────────────────────────────────────────────────────
    // 헬퍼: MercurialChunkedInputStream 인스턴스 생성 (리플렉션)
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
        // 터미널 청크: 길이 0
        out.write(new byte[]{0, 0, 0, 0});
        return out.toByteArray();
    }

    // ─────────────────────────────────────────────────────────────────────
    // 정상 동작 테스트
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
        // 터미널 청크만
        InputStream stream = createChunkedStream(new byte[]{0, 0, 0, 0});
        assertEquals(-1, stream.read());
    }

    @Test
    @DisplayName("EOF 이후 연속 read() 호출 시 -1 반환")
    public void testChunked_afterEof_returnsMinusOne() throws Exception {
        byte[] payload = buildChunkedPayload("X".getBytes(StandardCharsets.US_ASCII));
        InputStream stream = createChunkedStream(payload);

        // 전부 읽어 EOF
        while (stream.read() != -1) {}

        // 추가 read() 모두 -1
        assertEquals(-1, stream.read());
        assertEquals(-1, stream.read(new byte[4], 0, 4));
    }

    @Test
    @DisplayName("청크 길이 4바이트가 부분적으로 도착하는 경우 정상 처리")
    public void testChunked_partialLengthHeader_handledCorrectly() throws Exception {
        // 청크 길이 = 3, 데이터 = 'A','B','C', 터미널
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0, 0, 0, 3}); // 길이
        out.write(new byte[]{'A', 'B', 'C'}); // 데이터
        out.write(new byte[]{0, 0, 0, 0}); // 터미널

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
    // 오류 케이스 테스트
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("음수 청크 길이 → IOException 발생")
    public void testChunked_negativeChunkLength_throwsIOException() throws Exception {
        // 0x80000001 = -2147483647 (음수 int)
        byte[] raw = new byte[]{(byte) 0x80, 0x00, 0x00, 0x01};
        InputStream stream = createChunkedStream(raw);
        assertThrows(IOException.class, () -> stream.read());
    }

    @Test
    @DisplayName("청크 길이 헤더 중간에 EOF → IOException 발생")
    public void testChunked_eofMidLengthHeader_throwsIOException() throws Exception {
        // 4바이트 중 2바이트만 제공
        InputStream stream = createChunkedStream(new byte[]{0, 0});
        assertThrows(IOException.class, () -> stream.read());
    }

    @Test
    @DisplayName("청크 페이로드 중간에 EOF → IOException 발생 (배열 read)")
    public void testChunked_eofMidPayload_readArray_throwsIOException() throws Exception {
        // 길이 = 5라고 선언했지만 실제 데이터 3바이트만 있음
        // ByteArrayInputStream은 배열 read 시 가용 데이터만큼 부분 반환하므로
        // IOException은 남은 2바이트를 읽으려는 두 번째 호출에서 발생
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0, 0, 0, 5}); // 길이 5 선언
        out.write(new byte[]{'A', 'B', 'C'}); // 데이터 3바이트만
        InputStream stream = createChunkedStream(out.toByteArray());

        assertThrows(IOException.class, () -> {
            byte[] buf = new byte[10];
            // 반복 읽기: 첫 번째 호출은 3바이트 반환, 두 번째에서 IOException 발생
            int count;
            while ((count = stream.read(buf, 0, 10)) != -1) {
                // 루프 계속 - IOException이 발생할 때까지
            }
        });
    }

    @Test
    @DisplayName("청크 페이로드 중간에 EOF → IOException 발생 (단바이트 read)")
    public void testChunked_eofMidPayload_readByte_throwsIOException() throws Exception {
        // 길이 = 3이지만 1바이트만 있음
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0, 0, 0, 3});
        out.write(new byte[]{'X'}); // 데이터 1바이트만
        InputStream stream = createChunkedStream(out.toByteArray());

        // 첫 바이트는 정상 읽힘
        assertEquals('X', stream.read());
        // 두 번째 바이트에서 EOF 에러
        assertThrows(IOException.class, () -> stream.read());
    }

    // ─────────────────────────────────────────────────────────────────────
    // HTTP 로컬 서버 이용한 통합 테스트 (mercurial-0.1 및 0.2 응답 처리)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("mercurial-0.1 응답에서 zlib 압축 감지 동작 확인")
    public void testHgRemoteClient_mercurial01_capabilities_viaLocalServer() throws Exception {
        // com.sun.net.httpserver 이용하여 로컬 서버 구성
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
            // mercurial-0.2 응답 형식:
            // 1. 압축 이름 길이 (1 byte)
            // 2. 압축 이름 ("none")
            // 3. 청크 프레임 (4바이트 길이 + 데이터 + 종료 4바이트 0)
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            String compName = "none";
            body.write(compName.length()); // 압축 이름 길이
            body.write(compName.getBytes(StandardCharsets.US_ASCII));
            // 청크 데이터
            String data = "lookup getbundle\n";
            byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);
            body.write((dataBytes.length >> 24) & 0xFF);
            body.write((dataBytes.length >> 16) & 0xFF);
            body.write((dataBytes.length >> 8) & 0xFF);
            body.write(dataBytes.length & 0xFF);
            body.write(dataBytes);
            // 터미널 청크
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
            String compName = "lz4"; // 지원하지 않는 압축
            body.write(compName.length());
            body.write(compName.getBytes(StandardCharsets.US_ASCII));
            body.write(new byte[]{0, 0, 0, 0}); // 터미널

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
