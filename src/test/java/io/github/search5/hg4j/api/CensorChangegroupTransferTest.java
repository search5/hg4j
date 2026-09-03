package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.bundle.ChangegroupParser;
import io.github.search5.hg4j.errors.HgCensoredContentException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that pulling/cloning a repository containing a censored file revision neither crashes
 * nor silently un-censors it — the "cg3/censor" gap this covers: bundling code previously read
 * filelog content via {@code getRevisionContent()} (which now throws {@link
 * HgCensoredContentException} for a censored revision, per item 5), and the receiving side never
 * reconstructed {@link Revlog#REVIDX_ISCENSORED} from transferred content at all. Real hg carries
 * the flag explicitly only in cg3's per-entry flags field; for changegroup formats without one
 * (cg1/cg2, which is what hg4j's own bundling always produces) real hg's own revlog layer
 * recovers it by sniffing the tombstone marker in the transferred text
 * ({@code revlog.py}'s {@code _peek_iscensored}) — mirrored here by
 * {@code Revlog.isCensoredText}.
 */
public class CensorChangegroupTransferTest {

    @Test
    public void pullingACensoredFileRevisionPreservesTheCensoredFlagOnTheReceivingSide(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();

        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        File f = new File(srcDir, "a.txt");
        Files.writeString(f.toPath(), "secret1\n");
        new AddCommand(srcRepo).call();
        new CommitCommand(srcRepo).setMessage("v1").setAuthor("dev").call();
        Files.writeString(f.toPath(), "secret1\nsecret2\n");
        new CommitCommand(srcRepo).setMessage("v2").setAuthor("dev").call();

        File srcFlIdx = CommitCommand.getFilelogIndex(srcRepo.getStoreDir(), "a.txt");
        File srcFlDat = new File(srcFlIdx.getPath().substring(0, srcFlIdx.getPath().length() - 2) + ".d");
        byte[] rev0Node = srcRepo.getRevlog(srcFlIdx, srcFlDat).getIndexRecord(0).getNodeId().clone();
        new CensorCommand(srcRepo).setFile("a.txt").setRevision(NodeIdUtil.toHex(rev0Node)).call();

        // Building the outgoing bundle from the source repo must not crash on the censored
        // revision (this is exactly the crash risk item 5 introduced into any bundling code path
        // still using getRevisionContent() instead of getRawRevisionContent()).
        ChangegroupParser.ChangegroupBundle bundle = HgTestUtils.createMockBundleFromRepo(srcRepo);

        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        FetchCommand fetchCmd = new FetchCommand(destRepo);
        fetchCmd.applyBundle(bundle);

        File destFlIdx = CommitCommand.getFilelogIndex(destRepo.getStoreDir(), "a.txt");
        File destFlDat = new File(destFlIdx.getPath().substring(0, destFlIdx.getPath().length() - 2) + ".d");
        Revlog destFl = destRepo.getRevlog(destFlIdx, destFlDat);

        assertEquals(2, destFl.getRevisionCount());
        assertTrue(destFl.isCensored(0),
                "The receiving side must recover REVIDX_ISCENSORED from the transferred tombstone content, "
                        + "not silently treat it as an ordinary (un-censored) revision");
        assertFalse(destFl.isCensored(1));
        assertThrows(HgCensoredContentException.class, () -> destFl.getRevisionContent(0));
        assertArrayEquals("secret1\nsecret2\n".getBytes(StandardCharsets.UTF_8), destFl.getRevisionContent(1));
        assertArrayEquals(rev0Node, destFl.getIndexRecord(0).getNodeId());
    }
}
