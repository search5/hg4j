package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import io.github.search5.hg4j.errors.HgCensoredContentException;
import java.nio.charset.StandardCharsets;

/**
 * Verifies {@link CensorCommand} against real Mercurial in both directions: hg4j censors a
 * revision and real hg (via its {@code censor} extension) confirms it refuses to read the
 * content and flags it during {@code hg verify}; and real hg censors a revision and hg4j confirms
 * it reads the same {@code REVIDX_ISCENSORED} state and tombstone real hg itself wrote.
 */
@Tag("interop")
public class CensorRealHgInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    @Test
    public void realHgRefusesToReadAFileRevisionCensoredByHg4j(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "secret1\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("v1").setAuthor("dev").call();
        Files.writeString(f.toPath(), "secret1\nsecret2\n");
        new CommitCommand(repo).setMessage("v2").setAuthor("dev").call();

        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "a.txt");
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        byte[] rev0Node = repo.getRevlog(flIdx, flDat).getIndexRecord(0).getNodeId();

        new CensorCommand(repo).setFile("a.txt").setRevision(NodeIdUtil.toHex(rev0Node)).call();

        Throwable e = assertThrows(Throwable.class, () ->
                HgTestUtils.hg(repoDir, "--config", "extensions.censor=", "cat", "-r", "0", "a.txt"));
        assertTrue(e.getMessage().contains("censored node"),
                "Real hg must refuse to read hg4j-censored content: " + e.getMessage());

        String verifyOut;
        try {
            verifyOut = HgTestUtils.hg(repoDir, "verify");
        } catch (Throwable verifyFailed) {
            verifyOut = verifyFailed.getMessage();
        }
        assertTrue(verifyOut.contains("censored file data"),
                "Real hg verify must recognize hg4j's REVIDX_ISCENSORED flag: " + verifyOut);

        String catV2 = HgTestUtils.hg(repoDir, "cat", "-r", "1", "a.txt");
        assertEquals("secret1\nsecret2", catV2, "The un-censored revision must remain fully readable by real hg");
    }

    @Test
    public void hg4jReadsARevisionCensoredByRealHg(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repository = HgTestUtils.nativeRepo(repoDir, dir -> {
            try {
                File f = new File(dir, "a.txt");
                Files.writeString(f.toPath(), "secret1\n");
                HgTestUtils.hg(dir, "add", "a.txt");
                HgTestUtils.hg(dir, "commit", "-m", "v1");
                Files.writeString(f.toPath(), "secret1\nsecret2\n");
                HgTestUtils.hg(dir, "commit", "-m", "v2");
                HgTestUtils.hg(dir, "--config", "extensions.censor=", "censor", "-r", "0", "a.txt");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), "a.txt");
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        Revlog filelog = repository.getRevlog(flIdx, flDat);

        assertTrue(filelog.isCensored(0), "hg4j must recognize a revision real hg censored");
        assertFalse(filelog.isCensored(1));
        assertThrows(HgCensoredContentException.class, () -> filelog.getRevisionContent(0));
        assertArrayEquals("secret1\nsecret2\n".getBytes(StandardCharsets.UTF_8), filelog.getRevisionContent(1));
    }
}
