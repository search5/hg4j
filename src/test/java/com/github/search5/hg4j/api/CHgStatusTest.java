package com.github.search5.hg4j.api;

import com.github.search5.hg4j.HgTestUtils;
import com.github.search5.hg4j.core.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("interop")
public class CHgStatusTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping CHgStatusTest.");
    }

    @Test
    public void testNativeStatusVsHg4jStatus(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repository = HgTestUtils.nativeRepo(repoDir, dir -> {
            try {
                File f1 = new File(dir, "f1.txt");
                Files.writeString(f1.toPath(), "Content 1");
                File f2 = new File(dir, "f2.txt");
                Files.writeString(f2.toPath(), "Content 2");

                HgTestUtils.hg(dir, "add", "f1.txt", "f2.txt");
                HgTestUtils.hg(dir, "commit", "-m", "Initial status commit");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Manipulate working copy
        // 1. Modify f1.txt
        File f1 = new File(repoDir, "f1.txt");
        Files.writeString(f1.toPath(), "Content 1 Modified");

        // 2. Delete f2.txt (physically)
        File f2 = new File(repoDir, "f2.txt");
        f2.delete();

        // 3. Add f3.txt (added)
        File f3 = new File(repoDir, "f3.txt");
        Files.writeString(f3.toPath(), "Content 3");
        HgTestUtils.hg(repoDir, "add", "f3.txt");

        // 4. Untracked file
        File untracked = new File(repoDir, "untracked.txt");
        Files.writeString(untracked.toPath(), "Untracked Content");

        // Execute hg4j StatusCommand
        Status status = new StatusCommand(repository).call();

        // Execute native hg status
        String nativeStatusOut = HgTestUtils.hg(repoDir, "status");
        
        Set<String> nativeModified = new HashSet<>();
        Set<String> nativeAdded = new HashSet<>();
        Set<String> nativeRemoved = new HashSet<>();
        Set<String> nativeClean = new HashSet<>();
        Set<String> nativeUntracked = new HashSet<>();

        if (!nativeStatusOut.isEmpty()) {
            for (String line : nativeStatusOut.split("\n")) {
                if (line.trim().isEmpty()) continue;
                char prefix = line.charAt(0);
                String path = line.substring(2).trim();
                if (prefix == 'M') nativeModified.add(path);
                else if (prefix == 'A') nativeAdded.add(path);
                else if (prefix == 'R') nativeRemoved.add(path);
                else if (prefix == '!') nativeRemoved.add(path); // Missing physically is '! ' in hg status, which we class as removed in hg4j Status
                else if (prefix == '?') nativeUntracked.add(path);
                else if (prefix == 'C') nativeClean.add(path);
            }
        }

        // Compare Modified
        assertEquals(nativeModified.size(), status.getModified().size(), "Modified files count must match");
        for (String p : nativeModified) {
            assertTrue(status.getModified().contains(p), "Modified file " + p + " must be detected by hg4j");
        }

        // Compare Added
        assertEquals(nativeAdded.size(), status.getAdded().size(), "Added files count must match");
        for (String p : nativeAdded) {
            assertTrue(status.getAdded().contains(p), "Added file " + p + " must be detected by hg4j");
        }

        // Compare Removed (which includes missing files '! ' in hg status)
        // Since we physically deleted f2.txt, native hg status will show '! f2.txt' (missing).
        // hg4j StatusCommand treats missing tracked files as removed.
        assertTrue(status.getRemoved().contains("f2.txt"), "Physically deleted tracked file f2.txt must be classified as removed");

        // Compare Untracked
        assertEquals(nativeUntracked.size(), status.getUntracked().size(), "Untracked files count must match");
        for (String p : nativeUntracked) {
            assertTrue(status.getUntracked().contains(p), "Untracked file " + p + " must be detected by hg4j");
        }
    }
}
