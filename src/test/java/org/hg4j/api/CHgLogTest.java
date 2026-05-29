package org.hg4j.api;

import org.hg4j.HgTestUtils;
import org.hg4j.core.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("interop")
public class CHgLogTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping CHgLogTest.");
    }

    @Test
    public void testNativeLogVsHg4jLog(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repository = HgTestUtils.nativeRepo(repoDir, dir -> {
            try {
                // Write some files
                File f1 = new File(dir, "f1.txt");
                Files.writeString(f1.toPath(), "Log test file 1");
                HgTestUtils.hg(dir, "add", "f1.txt");
                HgTestUtils.hg(dir, "commit", "-u", "Author One <one@example.com>", "-m", "First commit");

                File f2 = new File(dir, "f2.txt");
                Files.writeString(f2.toPath(), "Log test file 2");
                HgTestUtils.hg(dir, "add", "f2.txt");
                HgTestUtils.hg(dir, "branch", "feature-interop");
                HgTestUtils.hg(dir, "commit", "-u", "Author Two <two@example.com>", "-m", "Second commit - 안녕");

                Files.writeString(f2.toPath(), "Modified Log test file 2");
                HgTestUtils.hg(dir, "commit", "-u", "Author Three <three@example.com>", "-m", "Third commit");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // 1. Get native hg log outputs
        // hg log --template 는 newest first(descending rev order)로 출력
        String nativeLog = HgTestUtils.hg(repoDir, "log", "--template", "{node}|{desc}|{author}|{branch}\n");
        String[] nativeLines = nativeLog.split("\n");

        // 2. Get hg4j log outputs (newest first, i.e. descending rev)
        List<org.hg4j.api.HgCommit> hg4jLog = new LogCommand(repository).call();

        // 3. 1:1 Field Level Comparisons
        // 두 출력 모두 newest first이므로 인덱스가 1:1 대응
        assertEquals(3, hg4jLog.size(), "Commit count must be identical");
        assertEquals(3, nativeLines.length, "Native log lines must be 3");

        for (int i = 0; i < 3; i++) {
            // nativeLines[i] ↔ hg4jLog.get(i) — 둘 다 newest first
            String[] fields = nativeLines[i].split("\\|");
            org.hg4j.api.HgCommit hgCommit = hg4jLog.get(i);

            assertEquals(fields[0], hgCommit.getNodeId().toHex(), "Node ID must match (i=" + i + ")");
            assertEquals(fields[1], hgCommit.getMessage(), "Commit message must match (i=" + i + ")");
            assertEquals(fields[2], hgCommit.getAuthor(), "Author must match (i=" + i + ")");

            String expectedBranch = fields[3];
            assertEquals(expectedBranch, hgCommit.getBranch(), "Branch must match (i=" + i + ")");
        }
    }
}
