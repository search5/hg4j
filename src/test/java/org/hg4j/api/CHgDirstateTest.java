package org.hg4j.api;

import org.hg4j.HgTestUtils;
import org.hg4j.core.Dirstate;
import org.hg4j.core.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("interop")
public class CHgDirstateTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping CHgDirstateTest.");
    }

    @Test
    public void testNativeDirstateVsHg4jDirstate(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repository = HgTestUtils.nativeRepo(repoDir, dir -> {
            try {
                File f1 = new File(dir, "f1.txt");
                Files.writeString(f1.toPath(), "Dirstate check 1");
                File f2 = new File(dir, "f2.txt");
                Files.writeString(f2.toPath(), "Dirstate check 2");

                HgTestUtils.hg(dir, "add", "f1.txt", "f2.txt");
                HgTestUtils.hg(dir, "commit", "-m", "Commit for dirstate analysis");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Add an untracked file to verify it's not in the dirstate
        File untracked = new File(repoDir, "untracked.txt");
        Files.writeString(untracked.toPath(), "Not tracked yet");

        // Parse using hg4j
        Dirstate dirstate = repository.getDirstate();
        Map<String, Dirstate.Entry> entries = dirstate.getEntries();

        // Parse using native hg debugstate (it outputs state, mode, size, mtime, path)
        String debugstateOut = HgTestUtils.hg(repoDir, "debugstate");

        // Let's ensure the tracked files exist in our parsed entries
        assertNotNull(entries.get("f1.txt"), "f1.txt must be tracked in hg4j dirstate");
        assertNotNull(entries.get("f2.txt"), "f2.txt must be tracked in hg4j dirstate");
        assertTrue(!entries.containsKey("untracked.txt"), "untracked.txt must NOT be tracked in dirstate");

        // We can parse the debugstate output and compare entries directly
        if (!debugstateOut.isEmpty()) {
            for (String line : debugstateOut.split("\n")) {
                if (line.trim().isEmpty()) continue;
                // debugstate output format varies slightly, but typically contains the filename at the end
                // example: "n 644         17 2026-05-29 04:02:26 f1.txt"
                String[] tokens = line.split("\\s+");
                if (tokens.length >= 5) {
                    char nativeState = tokens[0].charAt(0);
                    String path = tokens[tokens.length - 1].trim();

                    Dirstate.Entry entry = entries.get(path);
                    assertNotNull(entry, "Dirstate entry for " + path + " must be found in hg4j");
                    
                    // Match state (e.g. 'n' for normal)
                    assertEquals(nativeState, entry.getState(), "Dirstate entry state for " + path + " must match native");
                }
            }
        }
    }
}
