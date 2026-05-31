package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.core.HgRepository;
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
public class CHgPullRoundtripTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping CHgPullRoundtripTest.");
    }

    @Test
    public void testNativeHgPullRoundtrip(@TempDir Path tempDir) throws Exception {
        File remoteDir = tempDir.resolve("remote_repo").toFile();
        File localDir = tempDir.resolve("local_repo").toFile();

        // 1. Setup native hg remote repository with rich history
        HgTestUtils.nativeRepo(remoteDir, dir -> {
            try {
                // Commit 0 (default branch)
                File f1 = new File(dir, "f1.txt");
                Files.writeString(f1.toPath(), "Log test file 1");
                HgTestUtils.hg(dir, "add", "f1.txt");
                HgTestUtils.hg(dir, "commit", "-u", "Author One <one@example.com>", "-m", "First commit");

                // Commit 1 (branch feature)
                HgTestUtils.hg(dir, "branch", "feature-x");
                File f2 = new File(dir, "f2.txt");
                Files.writeString(f2.toPath(), "Log test file 2");
                HgTestUtils.hg(dir, "add", "f2.txt");
                HgTestUtils.hg(dir, "commit", "-u", "Author Two <two@example.com>", "-m", "Second commit");

                // Commit 2 (branch feature continued)
                Files.writeString(f2.toPath(), "Modified Log test file 2");
                HgTestUtils.hg(dir, "commit", "-u", "Author Three <three@example.com>", "-m", "Third commit");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // 2. Initialize local repository via hg4j
        new InitCommand().setDirectory(localDir).call();
        HgRepository localRepository = new HgRepository(localDir);

        // 3. Pull from remote repository to local repository
        new PullCommand(localRepository).setSource(remoteDir.getAbsolutePath()).call();

        // 4. Compare local hg4j log outputs with native hg remote outputs
        String nativeLog = HgTestUtils.hg(remoteDir, "log", "--template", "{node}|{desc}|{author}|{branch}\n");
        String[] nativeLines = nativeLog.split("\n");

        List<io.github.search5.hg4j.api.HgCommit> localLog = new LogCommand(localRepository).call();

        assertEquals(3, localLog.size(), "Commit count must match pulled commits");

        for (int i = 0; i < 3; i++) {
            // Both local log and native log are in reverse chronological order (newest first)
            String[] fields = nativeLines[i].split("\\|");
            io.github.search5.hg4j.api.HgCommit hgCommit = localLog.get(i);

            assertEquals(fields[0], hgCommit.getNodeId().toHex(), "Node ID must match");
            assertEquals(fields[1], hgCommit.getMessage(), "Commit message must match");
            assertEquals(fields[2], hgCommit.getAuthor(), "Author must match");
            
            String expectedBranch = fields[3].equals("default") ? "default" : fields[3];
            assertEquals(expectedBranch, hgCommit.getBranch(), "Branch name must match");
        }
    }
}
