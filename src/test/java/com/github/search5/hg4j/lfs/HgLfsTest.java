package com.github.search5.hg4j.lfs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;
import com.github.search5.hg4j.errors.HgCorruptDataException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;

public class HgLfsTest {

    private static final String OID_64 = "1234567890abcdef".repeat(4);

    @TempDir
    File tempDir;

    private HttpServer startBatchServer(String responseJson) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/info/lfs/objects/batch", exchange -> {
            byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/vnd.git-lfs+json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    @Test
    public void testLfsPointerParsingSuccess() throws IOException {
        String pointerText = "version https://git-lfs.github.com/spec/v1\n" +
                "oid sha256:7b1a2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b\n" +
                "size 987654\n";

        HgLfsPointer pointer = HgLfsPointer.parse(pointerText.getBytes(StandardCharsets.UTF_8));
        assertNotNull(pointer);
        assertEquals("https://git-lfs.github.com/spec/v1", pointer.getVersion());
        assertEquals("7b1a2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b", pointer.getOid());
        assertEquals(987654L, pointer.getSize());
    }

    @Test
    public void testLfsPointerParsingMalformedThrows() {
        // Missing size
        String badPointer = "version https://git-lfs.github.com/spec/v1\n" +
                "oid sha256:7b1a2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b\n";

        assertThrows(HgCorruptDataException.class, () -> {
            HgLfsPointer.parse(badPointer.getBytes(StandardCharsets.UTF_8));
        });

        // Invalid OID length
        String shortOid = "version https://git-lfs.github.com/spec/v1\n" +
                "oid sha256:7b1a2c3d\n" +
                "size 100\n";

        assertThrows(HgCorruptDataException.class, () -> {
            HgLfsPointer.parse(shortOid.getBytes(StandardCharsets.UTF_8));
        });

        // Empty content
        assertThrows(HgCorruptDataException.class, () -> {
            HgLfsPointer.parse(new byte[0]);
        });

        // Null content
        assertThrows(HgCorruptDataException.class, () -> {
            HgLfsPointer.parse(null);
        });
    }

    @Test
    public void testLfsPointerConstructorNullVersionThrows() {
        assertThrows(IllegalArgumentException.class, () -> new HgLfsPointer(null, OID_64, 4));
    }

    @Test
    public void testLfsPointerConstructorNullOidThrows() {
        assertThrows(IllegalArgumentException.class, () -> new HgLfsPointer("v1", null, 4));
    }

    @Test
    public void testLfsPointerConstructorNegativeSizeThrows() {
        assertThrows(IllegalArgumentException.class, () -> new HgLfsPointer("v1", OID_64, -1));
    }

    @Test
    public void testLfsPointerParsingSkipsBlankAndUnrecognizedLines() throws IOException {
        String pointerText = "\n" +
                "  \n" +
                "version https://git-lfs.github.com/spec/v1\n" +
                "\n" +
                "some-unrecognized-line\n" +
                "oid sha256:7b1a2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b\n" +
                "size 42\n";

        HgLfsPointer pointer = HgLfsPointer.parse(pointerText.getBytes(StandardCharsets.UTF_8));
        assertEquals("https://git-lfs.github.com/spec/v1", pointer.getVersion());
        assertEquals("7b1a2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b", pointer.getOid());
        assertEquals(42L, pointer.getSize());
    }

    @Test
    public void testLfsPointerParsingInvalidSizeFormatThrows() {
        String pointerText = "version https://git-lfs.github.com/spec/v1\n" +
                "oid sha256:7b1a2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b\n" +
                "size not-a-number\n";

        HgCorruptDataException ex = assertThrows(HgCorruptDataException.class,
                () -> HgLfsPointer.parse(pointerText.getBytes(StandardCharsets.UTF_8)));
        assertTrue(ex.getMessage().contains("Invalid size format in LFS pointer"));
        assertNotNull(ex.getCause());
    }

    @Test
    public void testLfsPointerParsingMissingVersionThrows() {
        String pointerText = "oid sha256:7b1a2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b\n" +
                "size 42\n";

        assertThrows(HgCorruptDataException.class,
                () -> HgLfsPointer.parse(pointerText.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void testLfsPointerParsingMissingOidThrows() {
        String pointerText = "version https://git-lfs.github.com/spec/v1\n" +
                "size 42\n";

        assertThrows(HgCorruptDataException.class,
                () -> HgLfsPointer.parse(pointerText.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void testLfsManagerCachingScenario() throws Exception {
        HgLfsManager manager = new HgLfsManager(tempDir);
        assertNotNull(manager.getLfsObjectsDir());

        String oid = "11223344556677889900aabbccddeeff11223344556677889900aabbccddeeff";
        byte[] payload = "Large binary file mock content".getBytes(StandardCharsets.UTF_8);

        HgLfsPointer pointer = new HgLfsPointer("v1", oid, payload.length);
        assertFalse(manager.isCached(pointer));

        // Cache object
        manager.cacheObject(pointer, payload);
        assertTrue(manager.isCached(pointer));

        // Read cached object
        byte[] restored = manager.getCachedObject(pointer);
        assertArrayEquals(payload, restored);

        // Size mismatch on write should fail
        assertThrows(HgCorruptDataException.class, () -> {
            manager.cacheObject(pointer, new byte[10]); // wrong size
        });

        // Size mismatch on read (simulate modified file length)
        File expectedCacheFile = manager.getLocalPath(oid);
        assertTrue(expectedCacheFile.delete());
        assertTrue(expectedCacheFile.createNewFile()); // Size is now 0, pointer wants payload.length
        
        assertFalse(manager.isCached(pointer));
        assertThrows(HgCorruptDataException.class, () -> {
            manager.getCachedObject(pointer);
        });
    }

    @Test
    public void testLfsFetchObjectFlow() throws Exception {
        // 1. Start a simple native HTTP mock LFS server
        HttpServer mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        int port = mockServer.getAddress().getPort();
        String lfsServerUrl = "http://localhost:" + port + "/info/lfs";
        
        String oid = "223344556677889900aabbccddeeff11223344556677889900aabbccddeeffaa";
        byte[] originalPayload = "LFS Mock File Data".getBytes(StandardCharsets.UTF_8);
        
        // Batch endpoint mock response
        String downloadUrl = "http://localhost:" + port + "/download/object/" + oid;
        String batchResponseJson = "{\n"
                + "  \"transfer\": \"basic\",\n"
                + "  \"objects\": [\n"
                + "    {\n"
                + "      \"oid\": \"" + oid + "\",\n"
                + "      \"size\": " + originalPayload.length + ",\n"
                + "      \"actions\": {\n"
                + "        \"download\": {\n"
                + "          \"href\": \"" + downloadUrl + "\",\n"
                + "          \"header\": {\n"
                + "            \"Authorization\": \"Bearer mocktoken\"\n"
                + "          }\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "  ]\n"
                + "}";

        // Mock /info/lfs/objects/batch handler
        mockServer.createContext("/info/lfs/objects/batch", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/vnd.git-lfs+json");
            byte[] responseBytes = batchResponseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
            exchange.close();
        });

        // Mock /download/object/{oid} handler
        mockServer.createContext("/download/object/" + oid, exchange -> {
            assertEquals("Bearer mocktoken", exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, originalPayload.length);
            exchange.getResponseBody().write(originalPayload);
            exchange.close();
        });

        mockServer.start();

        try {
            HgLfsManager manager = new HgLfsManager(tempDir);
            HgLfsPointer pointer = new HgLfsPointer("v1", oid, originalPayload.length);

            assertFalse(manager.isCached(pointer));

            // Fetch object from mock server
            manager.fetchObject(pointer, lfsServerUrl);

            // Assert cached successfully
            assertTrue(manager.isCached(pointer));
            byte[] restored = manager.getCachedObject(pointer);
            assertArrayEquals(originalPayload, restored);
        } finally {
            mockServer.stop(0);
        }
    }

    @Test
    public void testConstructorNullHgDirThrows() {
        assertThrows(IllegalArgumentException.class, () -> new HgLfsManager(null));
    }

    @Test
    public void testGetLocalPathInvalidOidThrows() {
        HgLfsManager manager = new HgLfsManager(tempDir);
        assertThrows(IllegalArgumentException.class, () -> manager.getLocalPath(null));
        assertThrows(IllegalArgumentException.class, () -> manager.getLocalPath("tooShort"));
    }

    @Test
    public void testIsCachedNullPointerReturnsFalse() {
        HgLfsManager manager = new HgLfsManager(tempDir);
        assertFalse(manager.isCached(null));
    }

    @Test
    public void testCacheObjectNullArgsThrows() {
        HgLfsManager manager = new HgLfsManager(tempDir);
        HgLfsPointer pointer = new HgLfsPointer("v1", OID_64, 4);
        assertThrows(IllegalArgumentException.class, () -> manager.cacheObject(null, new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> manager.cacheObject(pointer, null));
    }

    @Test
    public void testCacheObjectDirectoryCreationFailure() throws Exception {
        File storeDir = new File(tempDir, "store");
        assertTrue(storeDir.mkdirs());
        File lfsBlocker = new File(storeDir, "lfs");
        Files.write(lfsBlocker.toPath(), "blocker file, not a directory".getBytes(StandardCharsets.UTF_8));

        HgLfsManager manager = new HgLfsManager(tempDir);
        byte[] payload = "data".getBytes(StandardCharsets.UTF_8);
        HgLfsPointer pointer = new HgLfsPointer("v1", OID_64, payload.length);

        IOException ex = assertThrows(IOException.class, () -> manager.cacheObject(pointer, payload));
        assertTrue(ex.getMessage().contains("Failed to create local LFS cache directories"));
    }

    @Test
    public void testFetchObjectNullArgsThrows() {
        HgLfsManager manager = new HgLfsManager(tempDir);
        HgLfsPointer pointer = new HgLfsPointer("v1", OID_64, 4);
        assertThrows(IllegalArgumentException.class, () -> manager.fetchObject(null, "http://x"));
        assertThrows(IllegalArgumentException.class, () -> manager.fetchObject(pointer, null));
    }

    @Test
    public void testFetchObjectAlreadyCachedSkipsNetwork() throws Exception {
        HgLfsManager manager = new HgLfsManager(tempDir);
        byte[] payload = "cached content".getBytes(StandardCharsets.UTF_8);
        HgLfsPointer pointer = new HgLfsPointer("v1", OID_64, payload.length);
        manager.cacheObject(pointer, payload);
        assertTrue(manager.isCached(pointer));

        // Unreachable URL: if fetchObject attempted network I/O, this call would throw.
        manager.fetchObject(pointer, "http://127.0.0.1:1/unreachable");

        assertArrayEquals(payload, manager.getCachedObject(pointer));
    }

    @Test
    public void testFetchObjectBatchNon200Throws() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/info/lfs/objects/batch", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            HgLfsManager manager = new HgLfsManager(tempDir);
            HgLfsPointer pointer = new HgLfsPointer("v1", OID_64, 4);
            String lfsServerUrl = "http://localhost:" + port + "/info/lfs";
            IOException ex = assertThrows(IOException.class, () -> manager.fetchObject(pointer, lfsServerUrl));
            assertTrue(ex.getMessage().contains("LFS batch API request failed with status: 500"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testFetchObjectEmptyObjectsArrayThrows() throws Exception {
        HttpServer server = startBatchServer("{\"objects\":[]}");
        try {
            HgLfsManager manager = new HgLfsManager(tempDir);
            HgLfsPointer pointer = new HgLfsPointer("v1", OID_64, 4);
            String lfsServerUrl = "http://localhost:" + server.getAddress().getPort() + "/info/lfs";
            IOException ex = assertThrows(IOException.class, () -> manager.fetchObject(pointer, lfsServerUrl));
            assertTrue(ex.getMessage().contains("Failed to parse LFS batch JSON response"));
            assertNotNull(ex.getCause());
            assertTrue(ex.getCause().getMessage().contains("No objects in LFS batch response"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testFetchObjectErrorFieldThrows() throws Exception {
        String json = "{\"objects\":[{\"oid\":\"" + OID_64 + "\",\"error\":{\"code\":404,\"message\":\"Object does not exist\"}}]}";
        HttpServer server = startBatchServer(json);
        try {
            HgLfsManager manager = new HgLfsManager(tempDir);
            HgLfsPointer pointer = new HgLfsPointer("v1", OID_64, 4);
            String lfsServerUrl = "http://localhost:" + server.getAddress().getPort() + "/info/lfs";
            IOException ex = assertThrows(IOException.class, () -> manager.fetchObject(pointer, lfsServerUrl));
            assertNotNull(ex.getCause());
            assertTrue(ex.getCause().getMessage().contains("LFS object download failed from batch API: 404 - Object does not exist"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testFetchObjectMissingActionsThrows() throws Exception {
        String json = "{\"objects\":[{\"oid\":\"" + OID_64 + "\"}]}";
        HttpServer server = startBatchServer(json);
        try {
            HgLfsManager manager = new HgLfsManager(tempDir);
            HgLfsPointer pointer = new HgLfsPointer("v1", OID_64, 4);
            String lfsServerUrl = "http://localhost:" + server.getAddress().getPort() + "/info/lfs";
            IOException ex = assertThrows(IOException.class, () -> manager.fetchObject(pointer, lfsServerUrl));
            assertNotNull(ex.getCause());
            assertTrue(ex.getCause().getMessage().contains("No actions for LFS object"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testFetchObjectMissingDownloadActionThrows() throws Exception {
        String json = "{\"objects\":[{\"oid\":\"" + OID_64 + "\",\"actions\":{}}]}";
        HttpServer server = startBatchServer(json);
        try {
            HgLfsManager manager = new HgLfsManager(tempDir);
            HgLfsPointer pointer = new HgLfsPointer("v1", OID_64, 4);
            String lfsServerUrl = "http://localhost:" + server.getAddress().getPort() + "/info/lfs";
            IOException ex = assertThrows(IOException.class, () -> manager.fetchObject(pointer, lfsServerUrl));
            assertNotNull(ex.getCause());
            assertTrue(ex.getCause().getMessage().contains("No download action for LFS object"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testFetchObjectMalformedJsonThrows() throws Exception {
        HttpServer server = startBatchServer("not-json-at-all");
        try {
            HgLfsManager manager = new HgLfsManager(tempDir);
            HgLfsPointer pointer = new HgLfsPointer("v1", OID_64, 4);
            String lfsServerUrl = "http://localhost:" + server.getAddress().getPort() + "/info/lfs";
            IOException ex = assertThrows(IOException.class, () -> manager.fetchObject(pointer, lfsServerUrl));
            assertTrue(ex.getMessage().contains("Failed to parse LFS batch JSON response"));
            assertNotNull(ex.getCause());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testFetchObjectMissingHrefThrows() throws Exception {
        String json = "{\"objects\":[{\"oid\":\"" + OID_64 + "\",\"actions\":{\"download\":{}}}]}";
        HttpServer server = startBatchServer(json);
        try {
            HgLfsManager manager = new HgLfsManager(tempDir);
            HgLfsPointer pointer = new HgLfsPointer("v1", OID_64, 4);
            String lfsServerUrl = "http://localhost:" + server.getAddress().getPort() + "/info/lfs";
            IOException ex = assertThrows(IOException.class, () -> manager.fetchObject(pointer, lfsServerUrl));
            assertTrue(ex.getMessage().contains("Failed to extract download URL"));
            assertNull(ex.getCause());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testFetchObjectDownloadNon200Throws() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        String downloadUrl = "http://localhost:" + port + "/download";
        String json = "{\"objects\":[{\"oid\":\"" + OID_64 + "\",\"actions\":{\"download\":{\"href\":\"" + downloadUrl + "\"}}}]}";
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        server.createContext("/info/lfs/objects/batch", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/vnd.git-lfs+json");
            exchange.sendResponseHeaders(200, jsonBytes.length);
            exchange.getResponseBody().write(jsonBytes);
            exchange.close();
        });
        server.createContext("/download", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            HgLfsManager manager = new HgLfsManager(tempDir);
            HgLfsPointer pointer = new HgLfsPointer("v1", OID_64, 4);
            String lfsServerUrl = "http://localhost:" + port + "/info/lfs";
            IOException ex = assertThrows(IOException.class, () -> manager.fetchObject(pointer, lfsServerUrl));
            assertTrue(ex.getMessage().contains("LFS object download failed with status: 404"));
        } finally {
            server.stop(0);
        }
    }
}
