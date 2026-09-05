package io.github.search5.hg4j.api;

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
import io.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.errors.HgValidationException;

public class CensorCommandTest {

    private File filelogIndex(HgRepository repo, String path) {
        return CommitCommand.getFilelogIndex(repo.getStoreDir(), path);
    }

    private File filelogData(File flIdx) {
        return new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
    }

    @Test
    public void censorReplacesTargetRevisionContentWithATombstoneAndMarksItCensored(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "secret1\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("v1").setAuthor("dev").call();

        File flIdx = filelogIndex(repo, "a.txt");
        File flDat = filelogData(flIdx);
        Revlog filelog = repo.getRevlog(flIdx, flDat);
        byte[] originalNode = filelog.getIndexRecord(0).getNodeId().clone();
        assertFalse(filelog.isCensored(0));
        assertArrayEquals("secret1\n".getBytes(StandardCharsets.UTF_8), filelog.getRevisionContent(0));

        // rev 0 is the repo's only head here, and real hg's own censor refuses to censor a
        // revision that is still live at a head (see the dedicated checkHeads* tests below) --
        // this test is about tombstone mechanics only, so it opts out exactly like real hg's own
        // `--no-check-heads`.
        new CensorCommand(repo).setFile("a.txt").setRevision(NodeIdUtil.toHex(originalNode)).setCheckHeads(false).call();

        Revlog reread = repo.getRevlog(flIdx, flDat);
        assertTrue(reread.isCensored(0), "Revision must be marked censored after CensorCommand runs");
        assertArrayEquals(originalNode, reread.getIndexRecord(0).getNodeId(),
                "Node identity must be preserved so history/DAG references stay valid");
        assertThrows(HgCensoredContentException.class, () -> reread.getRevisionContent(0));
    }

    @Test
    public void censorLeavesOtherRevisionsReadableAndUnaffected(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "secret1\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("v1").setAuthor("dev").call();
        Files.writeString(f.toPath(), "secret1\nsecret2\n");
        new CommitCommand(repo).setMessage("v2").setAuthor("dev").call();

        File flIdx = filelogIndex(repo, "a.txt");
        File flDat = filelogData(flIdx);
        Revlog filelog = repo.getRevlog(flIdx, flDat);
        byte[] rev0Node = filelog.getIndexRecord(0).getNodeId().clone();
        byte[] rev1Node = filelog.getIndexRecord(1).getNodeId().clone();

        new CensorCommand(repo).setFile("a.txt").setRevision(NodeIdUtil.toHex(rev0Node)).call();

        Revlog reread = repo.getRevlog(flIdx, flDat);
        assertEquals(2, reread.getRevisionCount(), "Censoring must not remove any revision from history");
        assertTrue(reread.isCensored(0));
        assertFalse(reread.isCensored(1), "Only the targeted revision may become censored");
        assertArrayEquals("secret1\nsecret2\n".getBytes(StandardCharsets.UTF_8), reread.getRevisionContent(1),
                "The untouched revision's content must read back exactly as before");
        assertArrayEquals(rev1Node, reread.getIndexRecord(1).getNodeId());
        assertEquals(0, reread.getIndexRecord(1).getParent1(), "DAG structure (parent linkage) must be unaffected by censoring an ancestor");
    }

    @Test
    public void censorThrowsWhenTheRevisionDoesNotExist(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "content\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("v1").setAuthor("dev").call();

        assertThrows(HgRevisionNotFoundException.class, () ->
                new CensorCommand(repo).setFile("a.txt").setRevision("f".repeat(40)).call());
    }

    @Test
    public void censorThrowsForAMissingFile(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        assertThrows(HgRepositoryNotFoundException.class, () ->
                new CensorCommand(repo).setFile("nope.txt").setRevision("f".repeat(40)).call());
    }

    @Test
    public void constructorRejectsANullRepository() {
        assertThrows(IllegalArgumentException.class, () -> new CensorCommand(null));
    }

    @Test
    public void censorThrowsWhenFilePathIsNull(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        assertThrows(HgValidationException.class, () ->
                new CensorCommand(repo).setRevision("f".repeat(40)).call());
    }

    @Test
    public void censorThrowsWhenFilePathIsEmpty(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        assertThrows(HgValidationException.class, () ->
                new CensorCommand(repo).setFile("").setRevision("f".repeat(40)).call());
    }

    @Test
    public void censorThrowsWhenRevisionIsNull(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        assertThrows(HgValidationException.class, () ->
                new CensorCommand(repo).setFile("a.txt").call());
    }

    @Test
    public void censorThrowsWhenRevisionIsEmpty(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        assertThrows(HgValidationException.class, () ->
                new CensorCommand(repo).setFile("a.txt").setRevision("").call());
    }

    @Test
    public void censorRecordsACustomTombstoneMessageInTheRawRevisionContent(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "secret1\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("v1").setAuthor("dev").call();

        File flIdx = filelogIndex(repo, "a.txt");
        File flDat = filelogData(flIdx);
        Revlog filelog = repo.getRevlog(flIdx, flDat);
        byte[] originalNode = filelog.getIndexRecord(0).getNodeId().clone();

        // rev 0 is the repo's only head -- opt out of the check-heads guard, see the comment on
        // censorReplacesTargetRevisionContentWithATombstoneAndMarksItCensored above.
        new CensorCommand(repo).setFile("a.txt").setRevision(NodeIdUtil.toHex(originalNode))
                .setTombstone("court order 1234").setCheckHeads(false).call();

        Revlog reread = repo.getRevlog(flIdx, flDat);
        assertTrue(reread.isCensored(0));
        assertArrayEquals(CensorCommand.buildTombstone("court order 1234"), reread.getRawRevisionContent(0),
                "Tombstone must carry the caller-supplied message using real hg's packmeta format");
    }

    @Test
    public void setTombstoneWithNullResetsToTheEmptyDefault(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "secret1\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("v1").setAuthor("dev").call();

        File flIdx = filelogIndex(repo, "a.txt");
        File flDat = filelogData(flIdx);
        Revlog filelog = repo.getRevlog(flIdx, flDat);
        byte[] originalNode = filelog.getIndexRecord(0).getNodeId().clone();

        // rev 0 is the repo's only head -- opt out of the check-heads guard, see the comment on
        // censorReplacesTargetRevisionContentWithATombstoneAndMarksItCensored above.
        new CensorCommand(repo).setFile("a.txt").setRevision(NodeIdUtil.toHex(originalNode))
                .setTombstone("some reason").setTombstone(null).setCheckHeads(false).call();

        Revlog reread = repo.getRevlog(flIdx, flDat);
        assertArrayEquals(CensorCommand.buildTombstone(""), reread.getRawRevisionContent(0),
                "A null tombstone must reset to the empty-string default, matching real hg");
    }

    @Test
    public void censorRefusesARevisionStillLiveAtARepositoryHead(@TempDir Path tempDir) throws Exception {
        // Matches real hg 7.2's hgext.censor: "abort: cannot censor file in heads (<hex>)"
        // (confirmed live 2026-09-04) -- a single-commit repo's only revision is always a head.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "secret1\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("v1").setAuthor("dev").call();

        File flIdx = filelogIndex(repo, "a.txt");
        File flDat = filelogData(flIdx);
        Revlog filelog = repo.getRevlog(flIdx, flDat);
        byte[] originalNode = filelog.getIndexRecord(0).getNodeId().clone();

        HgValidationException ex = assertThrows(HgValidationException.class, () ->
                new CensorCommand(repo).setFile("a.txt").setRevision(NodeIdUtil.toHex(originalNode)).call());
        assertTrue(ex.getMessage().contains("cannot censor file in heads"), ex.getMessage());

        Revlog reread = repo.getRevlog(flIdx, flDat);
        assertFalse(reread.isCensored(0), "A refused censor attempt must leave the revision untouched");
    }

    @Test
    public void censorRefusesTheWorkingDirectoryParentRevisionEvenWhenNotAHead(@TempDir Path tempDir) throws Exception {
        // real hg's censor also separately guards the working-directory parent ("abort: cannot
        // censor working directory", confirmed live 2026-09-04): here rev0's content is no longer
        // a repository head's content (c1 changed the file), but the working copy has been moved
        // back to c0 -- rev0's content -- so only the working-directory guard, not the heads
        // guard, can be what trips.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "secret1\n");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setMessage("v1").setAuthor("dev").call();
        Files.writeString(f.toPath(), "secret1\nsecret2\n");
        new CommitCommand(repo).setMessage("v2").setAuthor("dev").call();

        new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(c0)).call();

        File flIdx = filelogIndex(repo, "a.txt");
        File flDat = filelogData(flIdx);
        Revlog filelog = repo.getRevlog(flIdx, flDat);
        byte[] rev0Node = filelog.getIndexRecord(0).getNodeId().clone();

        HgValidationException ex = assertThrows(HgValidationException.class, () ->
                new CensorCommand(repo).setFile("a.txt").setRevision(NodeIdUtil.toHex(rev0Node)).call());
        assertTrue(ex.getMessage().contains("cannot censor working directory"), ex.getMessage());

        Revlog reread = repo.getRevlog(flIdx, flDat);
        assertFalse(reread.isCensored(0), "A refused censor attempt must leave the revision untouched");
    }

    @Test
    public void censorNoCheckHeadsBypassesTheHeadGuardExactlyLikeRealHg(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "secret1\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("v1").setAuthor("dev").call();

        File flIdx = filelogIndex(repo, "a.txt");
        File flDat = filelogData(flIdx);
        Revlog filelog = repo.getRevlog(flIdx, flDat);
        byte[] originalNode = filelog.getIndexRecord(0).getNodeId().clone();

        new CensorCommand(repo).setFile("a.txt").setRevision(NodeIdUtil.toHex(originalNode))
                .setCheckHeads(false).call();

        Revlog reread = repo.getRevlog(flIdx, flDat);
        assertTrue(reread.isCensored(0), "setCheckHeads(false) must bypass the guard exactly like real hg's --no-check-heads");
    }
}
