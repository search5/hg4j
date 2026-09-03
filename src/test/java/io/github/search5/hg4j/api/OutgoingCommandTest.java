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
public class OutgoingCommandTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping OutgoingCommandTest.");
    }

    @Test
    public void callThrowsWhenDestinationNotSet() throws Exception {
        HgRepository repo = HgTestUtils.nativeRepo(Files.createTempDirectory("outgoing-nodest").toFile(), dir -> {});
        assertThrows(IllegalArgumentException.class, () -> new OutgoingCommand(repo).call());
    }

    @Test
    public void reportsNoOutgoingWhenRemoteIsUpToDate(@TempDir Path tempDir) throws Exception {
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

        List<String> result = new OutgoingCommand(localRepo).setDestination(remoteDir.getAbsolutePath()).call();
        assertEquals(1, result.size());
        assertEquals("no outgoing changes found", result.get(0));
    }

    @Test
    public void reportsLocalCommitNotYetPushed(@TempDir Path tempDir) throws Exception {
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
        new UpdateCommand(localRepo).call();

        // Create a new local commit that the remote does not have.
        Files.writeString(new File(localDir, "f2.txt").toPath(), "local only content");
        new AddCommand(localRepo).addFile("f2.txt").call();
        new CommitCommand(localRepo).setMessage("local only commit").setAuthor("Local <local@example.com>").call();

        List<String> result = new OutgoingCommand(localRepo).setDestination(remoteDir.getAbsolutePath()).call();
        assertTrue(result.stream().anyMatch(line -> line.startsWith("changeset:")),
                "Expected an outgoing changeset header, got: " + result);
        assertTrue(result.stream().anyMatch(line -> line.equals("summary:     local only commit")),
                "Expected the new commit's summary to be reported, got: " + result);
    }
}
