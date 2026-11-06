package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lfs.HgLfsFactory;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In a repository that tracks files with the lfs extension ({@code lfs.track}), every new file revision of a tracked file is a pointer
 * ({@code lfs}'s {@code filelogaddrevision} wrapper sets the external-storage flag for them all), also the ones a merge, a squash or an
 * in-memory graft writes. The content the merge works on is the real content, not the pointer text.
 */
class LfsMergeGraftTest {

    @TempDir
    Path tempDir;

    private File dir;
    private HgRepository repo;
    private byte[] base;
    private byte[] left;
    private byte[] right;

    private static String big(String first, String last) {
        StringBuilder text = new StringBuilder(first).append('\n');
        for (int i = 2; i < 40; i++) {
            text.append("line ").append(i).append(" of the large file\n");
        }
        return text.append(last).append('\n').toString();
    }

    private byte[] commit(String message, String... fileAndContent) throws Exception {
        for (int i = 0; i < fileAndContent.length; i += 2) {
            Files.writeString(new File(dir, fileAndContent[i]).toPath(), fileAndContent[i + 1]);
        }
        new AddCommand(repo).call();
        return new CommitCommand(repo).setMessage(message).setAuthor("t").call();
    }

    /** base has big.txt (tracked: over 100 bytes) and small.txt; left edits the first line of big.txt, right the last line. */
    @BeforeEach
    void history() throws Exception {
        dir = tempDir.resolve("repo").toFile();
        Hg.init().setDirectory(dir).call();
        Files.writeString(new File(dir, ".hg/hgrc").toPath(), "[extensions]\nlfs =\n[lfs]\ntrack = size(\">100\")\n");
        repo = new HgRepository(dir);
        base = commit("base", "big.txt", big("first", "last"), "small.txt", "s\n", "other.txt", "o\n");
        left = commit("left", "big.txt", big("first ours", "last"), "small.txt", "s ours\n");
        new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(base)).call();
        right = commit("right", "big.txt", big("first", "last theirs"), "small.txt", "s\n", "added.txt", "a\n");
    }

    private Revlog filelog(String path) throws Exception {
        File index = CommitCommand.getFilelogIndex(repo.getStoreDir(), path);
        return repo.getRevlog(index, new File(index.getPath().substring(0, index.getPath().length() - 2) + ".d"));
    }

    private String manifestEntry(byte[] changeset, String path) throws Exception {
        return repo.getManifestAtCommit(changeset).get(path);
    }

    private void assertPointer(byte[] changeset, String path, String expectedContent) throws Exception {
        Revlog filelog = filelog(path);
        int rev = filelog.findRevision(NodeIdUtil.fromHex(manifestEntry(changeset, path).substring(0, 40)));
        assertTrue(filelog.isExtStored(rev), path + " has to be stored as an LFS pointer");
        assertArrayEquals(expectedContent.getBytes(StandardCharsets.UTF_8),
                HgLfsFactory.getInstance().readContent(repo, path, filelog, rev));
    }

    @Test
    void aMergeOfBothSidesEditingAnLfsFileWritesAPointer() throws Exception {
        MergeCommitResult result = new MergeCommitCommand(repo).setP1(left).setP2(right).setUser("t").setMessage("merge").call();
        assertFalse(result.isConflicted(), result.getConflicts().toString());
        assertPointer(result.getNode(), "big.txt", big("first ours", "last theirs"));
        Revlog small = filelog("small.txt");
        assertFalse(small.isExtStored(small.findRevision(NodeIdUtil.fromHex(manifestEntry(result.getNode(), "small.txt").substring(0, 40)))));
    }

    @Test
    void anLfsFileTakenFromTheOtherSideKeepsItsPointerRevision() throws Exception {
        // the clean side changed another file only, so the merge takes big.txt from right as it is (the same file revision)
        byte[] clean = baseWithSmallEdit();
        MergeCommitResult result = new MergeCommitCommand(repo).setP1(clean).setP2(right).setUser("t").setMessage("merge clean").call();
        assertFalse(result.isConflicted(), result.getConflicts().toString());
        assertEquals(manifestEntry(right, "big.txt"), manifestEntry(result.getNode(), "big.txt"));
        assertPointer(result.getNode(), "big.txt", big("first", "last theirs"));
    }

    private byte[] baseWithSmallEdit() throws Exception {
        new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(base)).call();
        return commit("only small", "other.txt", "o changed\n");
    }

    @Test
    void aSquashOfBothSidesEditingAnLfsFileWritesAPointer() throws Exception {
        MergeCommitResult result = new MergeCommitCommand(repo).setP1(left).setP2(right).setSquash(true).setUser("t")
                .setMessage("squash").call();
        assertFalse(result.isConflicted(), result.getConflicts().toString());
        assertPointer(result.getNode(), "big.txt", big("first ours", "last theirs"));
    }

    @Test
    void anInMemoryGraftOfAnLfsFileEditWritesAPointer() throws Exception {
        new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(base)).call();
        byte[] target = commit("target", "big.txt", big("first ours", "last"));
        GraftResult result = new GraftInMemory(repo).setTo(NodeIdUtil.toHex(target)).addRevision(NodeIdUtil.toHex(right)).setUser("t")
                .setDate("1700000000 0").execute();
        assertFalse(result.isConflicted(), result.getConflicts().toString());
        assertPointer(result.getNodes().get(0), "big.txt", big("first ours", "last theirs"));
    }

    @Test
    void theNodeOfAPointerRevisionIsTheHashOfTheRealContent() throws Exception {
        MergeCommitResult result = new MergeCommitCommand(repo).setP1(left).setP2(right).setUser("t").setMessage("merge").call();
        Revlog filelog = filelog("big.txt");
        String entry = manifestEntry(result.getNode(), "big.txt");
        int rev = filelog.findRevision(NodeIdUtil.fromHex(entry.substring(0, 40)));
        Revlog.IndexRecord record = filelog.getIndexRecord(rev);
        byte[] p1 = record.getParent1() < 0 ? new byte[20] : java.util.Arrays.copyOf(filelog.getIndexRecord(record.getParent1()).getNodeId(), 20);
        byte[] p2 = record.getParent2() < 0 ? new byte[20] : java.util.Arrays.copyOf(filelog.getIndexRecord(record.getParent2()).getNodeId(), 20);
        byte[] expected = NodeIdUtil.computeNodeId(
                Revlog.wrapMetadata(big("first ours", "last theirs").getBytes(StandardCharsets.UTF_8), (Map<String, String>) null), p1, p2);
        assertEquals(NodeIdUtil.toHex(java.util.Arrays.copyOf(expected, 20)), entry.substring(0, 40));
    }
}
