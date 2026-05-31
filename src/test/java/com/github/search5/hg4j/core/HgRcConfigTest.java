package com.github.search5.hg4j.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

public class HgRcConfigTest {

    @TempDir
    File tempDir;

    @Test
    public void testHgRcConfigParsing() throws Exception {
        String iniContent = "# Global Mercurial Config\n" +
                "[ui]\n" +
                "username = Tester Name <tester@example.com>\n" +
                "verbose = True\n" +
                "\n" +
                "[paths]\n" +
                "default = https://hg.example.com/project\n" +
                "default-push = https://hg.example.com/project-push\n";

        HgRcConfig config = new HgRcConfig();
        config.parse(iniContent);

        assertEquals("Tester Name <tester@example.com>", config.getUsername());
        assertEquals("Tester Name <tester@example.com>", config.get("ui", "username"));
        assertEquals("https://hg.example.com/project", config.getPath("default"));
        assertEquals("https://hg.example.com/project-push", config.get("paths", "default-push"));
        assertEquals("True", config.get("ui", "verbose"));
        
        // Fallback default value test
        assertEquals("default_val", config.get("ui", "non-existent", "default_val"));
        assertNull(config.get("ui", null));
        assertNull(config.get(null, "key"));
    }

    @Test
    public void testHgRcConfigFileLoading() throws Exception {
        File configFile = new File(tempDir, "hgrc");
        String iniContent = "[ui]\nusername = File Tester\n";
        Files.writeString(configFile.toPath(), iniContent);

        HgRcConfig config = new HgRcConfig();
        config.load(configFile);

        assertEquals("File Tester", config.getUsername());
        
        // Non-existent file should load gracefully without throwing exceptions
        HgRcConfig config2 = new HgRcConfig();
        config2.load(new File(tempDir, "non_existent_hgrc"));
        assertNull(config2.getUsername());
    }

    @Test
    public void testHgRcConfigWritingAndSaving() throws Exception {
        HgRcConfig config = new HgRcConfig();
        config.set("ui", "username", "Writer Tester <writer@example.com>");
        config.set("paths", "default", "https://hg.example.com/project-write");
        config.set("ui", "verbose", "False");

        File saveFile = new File(tempDir, "saved_hgrc");
        config.save(saveFile);

        assertTrue(saveFile.exists());

        HgRcConfig config2 = new HgRcConfig();
        config2.load(saveFile);

        assertEquals("Writer Tester <writer@example.com>", config2.getUsername());
        assertEquals("https://hg.example.com/project-write", config2.getPath("default"));
        assertEquals("False", config2.get("ui", "verbose"));
    }
}
