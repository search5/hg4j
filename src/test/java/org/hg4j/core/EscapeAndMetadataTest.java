package org.hg4j.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class EscapeAndMetadataTest {

    @Test
    public void testFileStartingWithMetaMarker(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("meta.i").toFile();
        File datFile = tempDir.resolve("meta.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);

        // Content starting with \x01\n
        byte[] originalContent = "\u0001\nHello World starting with meta marker\n".getBytes(StandardCharsets.UTF_8);
        byte[] pNode = new byte[20];

        byte[] node = revlog.appendRevision(originalContent, -1, -1, pNode, pNode, 0);
        assertNotNull(node);

        // Read it back
        byte[] readBack = revlog.getRevisionContent(0);
        assertArrayEquals(originalContent, readBack, "Content starting with \\x01\\n must be correctly unescaped to match the original bytes.");

        // Verify raw content on disk indeed has escaping: prefixed with \x01\n\x01\n
        byte[] rawContent = revlog.getRawRevisionContent(0);
        assertTrue(rawContent.length >= 4);
        assertEquals('\u0001', (char) rawContent[0]);
        assertEquals('\n', (char) rawContent[1]);
        assertEquals('\u0001', (char) rawContent[2]);
        assertEquals('\n', (char) rawContent[3]);
        
        // Check that the rest is the original content
        byte[] restOfRaw = Arrays.copyOfRange(rawContent, 4, rawContent.length);
        assertArrayEquals(originalContent, restOfRaw);
    }

    @Test
    public void testMetadataParsing(@TempDir Path tempDir) throws Exception {
        File idxFile = tempDir.resolve("meta.i").toFile();
        File datFile = tempDir.resolve("meta.d").toFile();
        Revlog revlog = new Revlog(idxFile, datFile);

        byte[] content = "File content".getBytes(StandardCharsets.UTF_8);
        Map<String, String> metadata = Map.of(
            "copy", "src/original.txt",
            "copyrev", "1a2b3c4d5e6f1a2b3c4d5e6f1a2b3c4d5e6f1a2b"
        );
        byte[] pNode = new byte[20];

        byte[] node = revlog.appendRevision(content, metadata, -1, -1, pNode, pNode, 0);
        assertNotNull(node);

        // Verify normal content is returned
        byte[] readBack = revlog.getRevisionContent(0);
        assertArrayEquals(content, readBack);

        // Verify metadata map is returned correctly
        Map<String, String> readMeta = revlog.getRevisionMetadata(0);
        assertEquals("src/original.txt", readMeta.get("copy"));
        assertEquals("1a2b3c4d5e6f1a2b3c4d5e6f1a2b3c4d5e6f1a2b", readMeta.get("copyrev"));
    }
}
