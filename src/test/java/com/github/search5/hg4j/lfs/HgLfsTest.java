package com.github.search5.hg4j.lfs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class HgLfsTest {

    @TempDir
    File tempDir;

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

        assertThrows(com.github.search5.hg4j.errors.HgCorruptDataException.class, () -> {
            HgLfsPointer.parse(badPointer.getBytes(StandardCharsets.UTF_8));
        });

        // Invalid OID length
        String shortOid = "version https://git-lfs.github.com/spec/v1\n" +
                "oid sha256:7b1a2c3d\n" +
                "size 100\n";

        assertThrows(com.github.search5.hg4j.errors.HgCorruptDataException.class, () -> {
            HgLfsPointer.parse(shortOid.getBytes(StandardCharsets.UTF_8));
        });

        // Empty content
        assertThrows(com.github.search5.hg4j.errors.HgCorruptDataException.class, () -> {
            HgLfsPointer.parse(new byte[0]);
        });
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
        assertThrows(com.github.search5.hg4j.errors.HgCorruptDataException.class, () -> {
            manager.cacheObject(pointer, new byte[10]); // wrong size
        });

        // Size mismatch on read (simulate modified file length)
        File expectedCacheFile = manager.getLocalPath(oid);
        assertTrue(expectedCacheFile.delete());
        assertTrue(expectedCacheFile.createNewFile()); // Size is now 0, pointer wants payload.length
        
        assertFalse(manager.isCached(pointer));
        assertThrows(com.github.search5.hg4j.errors.HgCorruptDataException.class, () -> {
            manager.getCachedObject(pointer);
        });
    }

    @Test
    public void testLfsFetchObjectFlow() throws Exception {
        // 1. Start a simple native HTTP mock LFS server
        com.sun.net.httpserver.HttpServer mockServer = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
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
}
