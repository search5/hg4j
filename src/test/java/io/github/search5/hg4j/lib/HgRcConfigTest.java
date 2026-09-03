package io.github.search5.hg4j.lib;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

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

    /**
     * {@code %include}/{@code %unset}를 실제 hg CLI(`hg config`)의 결과와 대조 검증한다.
     * 실제 스펙(mercurial/config.py의 unsetre/includere 처리)에 따르면 %unset은
     * 현재 시점까지 설정된 값을 제거하고, %include는 포함 파일의 디렉터리를 기준으로
     * 상대 경로를 해석하며 없는 파일은 조용히 무시한다.
     */
    @Test
    public void testIncludeAndUnsetMatchRealHg() throws Exception {
        Assumptions.assumeTrue(isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");

        File includedFile = new File(tempDir, "included.rc");
        Files.writeString(includedFile.toPath(), "[extra]\nfromInclude = yes\n");

        File mainFile = new File(tempDir, "main.rc");
        Files.writeString(mainFile.toPath(),
                "[ui]\n" +
                "username = Base User\n" +
                "verbose = True\n" +
                "%unset verbose\n" +
                "%include included.rc\n" +
                "[paths]\n" +
                "default = https://example.com/repo\n");

        HgRcConfig config = new HgRcConfig();
        config.load(mainFile);

        assertEquals("Base User", config.get("ui", "username"));
        assertNull(config.get("ui", "verbose"), "%unset 이후에는 값이 완전히 제거되어야 함");
        assertEquals("yes", config.get("extra", "fromInclude"));
        assertEquals("https://example.com/repo", config.getPath("default"));

        Map<String, String> nativeConfig = hgConfig(mainFile);
        assertEquals("Base User", nativeConfig.get("ui.username"));
        assertFalse(nativeConfig.containsKey("ui.verbose"), "실제 hg도 %unset 이후 키를 노출하지 않아야 함");
        assertEquals("yes", nativeConfig.get("extra.fromInclude"));
        assertEquals("https://example.com/repo", nativeConfig.get("paths.default"));
    }

    private static boolean isHgInstalled() {
        try {
            Process process = new ProcessBuilder("hg", "--version").start();
            process.waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Map<String, String> hgConfig(File hgrcFile) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("hg", "config");
        pb.environment().put("HGRCPATH", hgrcFile.getAbsolutePath());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out;
        try (InputStream is = p.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new AssertionError("hg config failed with exit code " + code + ": " + out);
        }
        Map<String, String> result = new HashMap<>();
        for (String line : out.split("\n")) {
            int eq = line.indexOf('=');
            if (eq != -1) {
                result.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return result;
    }
}
