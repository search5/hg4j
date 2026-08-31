package com.github.search5.hg4j.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SafeFileIOTest {

    @Test
    public void testPrivateConstructor() throws Exception {
        Constructor<SafeFileIO> constructor = SafeFileIO.class.getDeclaredConstructor();
        assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()));
        constructor.setAccessible(true);
        SafeFileIO instance = constructor.newInstance();
        assertNotNull(instance);
    }

    @Test
    public void testWriteAtomicAndValidation(@TempDir Path tempDir) throws Exception {
        File targetFile = tempDir.resolve("atomic.txt").toFile();
        
        // Target file null validation
        assertThrows(IllegalArgumentException.class, () -> {
            SafeFileIO.writeAtomic(null, new byte[0]);
        });

        // Safe write raw bytes
        byte[] data = "Hello Atomic World".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        SafeFileIO.writeAtomic(targetFile, data);
        
        assertTrue(targetFile.exists());
        assertArrayEquals(data, Files.readAllBytes(targetFile.toPath()));
    }

    @Test
    public void testWriteLinesAtomic(@TempDir Path tempDir) throws Exception {
        File targetFile = tempDir.resolve("lines.txt").toFile();
        List<String> lines = List.of("Line 1", "Line 2", "Line 3");
        
        SafeFileIO.writeLinesAtomic(targetFile, lines);
        
        assertTrue(targetFile.exists());
        List<String> readLines = Files.readAllLines(targetFile.toPath());
        assertEquals(3, readLines.size());
        assertEquals("Line 1", readLines.get(0));
        assertEquals("Line 2", readLines.get(1));
        assertEquals("Line 3", readLines.get(2));
    }

    @Test
    public void testWriteAtomicParentCreationAndException(@TempDir Path tempDir) throws Exception {
        // Create a deep nested directory structure that does not exist
        File deepFile = tempDir.resolve("nested/dir/structure/file.txt").toFile();
        SafeFileIO.writeStringAtomic(deepFile, "Nested Content");
        
        assertTrue(deepFile.exists());
        assertEquals("Nested Content", Files.readString(deepFile.toPath()));
        
        // Write atomic on read-only directory or file that is a directory to trigger IOException
        File directoryAsFile = tempDir.resolve("a_directory").toFile();
        directoryAsFile.mkdirs();
        
        assertThrows(IOException.class, () -> {
            SafeFileIO.writeStringAtomic(directoryAsFile, "Failure expected");
        });
    }
}
