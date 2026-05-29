package org.hg4j.core;

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

        assertThrows(org.hg4j.errors.HgCorruptDataException.class, () -> {
            HgLfsPointer.parse(badPointer.getBytes(StandardCharsets.UTF_8));
        });

        // Invalid OID length
        String shortOid = "version https://git-lfs.github.com/spec/v1\n" +
                "oid sha256:7b1a2c3d\n" +
                "size 100\n";

        assertThrows(org.hg4j.errors.HgCorruptDataException.class, () -> {
            HgLfsPointer.parse(shortOid.getBytes(StandardCharsets.UTF_8));
        });

        // Empty content
        assertThrows(org.hg4j.errors.HgCorruptDataException.class, () -> {
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
        assertThrows(org.hg4j.errors.HgCorruptDataException.class, () -> {
            manager.cacheObject(pointer, new byte[10]); // wrong size
        });

        // Size mismatch on read (simulate modified file length)
        File expectedCacheFile = manager.getLocalPath(oid);
        assertTrue(expectedCacheFile.delete());
        assertTrue(expectedCacheFile.createNewFile()); // Size is now 0, pointer wants payload.length
        
        assertFalse(manager.isCached(pointer));
        assertThrows(org.hg4j.errors.HgCorruptDataException.class, () -> {
            manager.getCachedObject(pointer);
        });
    }
}
