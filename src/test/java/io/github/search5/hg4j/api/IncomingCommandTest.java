package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("interop")
public class IncomingCommandTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping IncomingCommandTest.");
    }

    @Test
    public void callThrowsWhenSourceNotSet() throws Exception {
        HgRepository repo = HgTestUtils.nativeRepo(Files.createTempDirectory("incoming-nosrc").toFile(), dir -> {});
        assertThrows(IllegalArgumentException.class, () -> new IncomingCommand(repo).call());
    }

    @Test
    public void reportsNoIncomingWhenLocalIsUpToDate(@TempDir Path tempDir) throws Exception {
        File remoteDir = tempDir.resolve("remote").toFile();
        HgTestUtils.nativeRepo(remoteDir, dir -> {
            try {
                Files.writeString(new File(dir, "f1.txt").toPath(), "content");
                HgTestUtils.hg(dir, "add", "f1.txt");
                HgTestUtils.hg(dir, "commit", "-u", "A <a@example.com>", "-m", "first");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        File localDir = tempDir.resolve("local").toFile();
        new InitCommand().setDirectory(localDir).call();
        HgRepository localRepo = new HgRepository(localDir);
        new PullCommand(localRepo).setSource(remoteDir.getAbsolutePath()).call();

        List<String> result = new IncomingCommand(localRepo).setSource(remoteDir.getAbsolutePath()).call();
        assertEquals(1, result.size());
        assertEquals("no incoming changes found", result.get(0));
    }

    @Test
    public void reportsIncomingHeadNotYetPulled(@TempDir Path tempDir) throws Exception {
        File remoteDir = tempDir.resolve("remote").toFile();
        HgTestUtils.nativeRepo(remoteDir, dir -> {
            try {
                Files.writeString(new File(dir, "f1.txt").toPath(), "content");
                HgTestUtils.hg(dir, "add", "f1.txt");
                HgTestUtils.hg(dir, "commit", "-u", "A <a@example.com>", "-m", "first");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        File localDir = tempDir.resolve("local").toFile();
        new InitCommand().setDirectory(localDir).call();
        HgRepository localRepo = new HgRepository(localDir);
        new PullCommand(localRepo).setSource(remoteDir.getAbsolutePath()).call();

        // Advance the remote by one more commit that local has not pulled yet.
        Files.writeString(new File(remoteDir, "f2.txt").toPath(), "content2");
        HgTestUtils.hg(remoteDir, "add", "f2.txt");
        HgTestUtils.hg(remoteDir, "commit", "-u", "B <b@example.com>", "-m", "second");

        List<String> result = new IncomingCommand(localRepo).setSource(remoteDir.getAbsolutePath()).call();
        assertTrue(result.stream().anyMatch(line -> line.startsWith("changeset:")),
                "Expected an incoming changeset header, got: " + result);
    }
}
