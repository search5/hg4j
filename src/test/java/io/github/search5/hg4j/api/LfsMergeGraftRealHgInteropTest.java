package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Merge, squash and in-memory graft in a repository whose files the lfs extension tracks: the changesets are the ones the real hg makes
 * (pointer file revisions whose nodes are hashes of the real content), and the repository verifies.
 */
@Tag("interop")
class LfsMergeGraftRealHgInteropTest {

    private static final String DATE = "1700000000 0";

    @TempDir
    Path tempDir;

    private File dir;
    private String base;
    private String p1;
    private String p2;

    @BeforeEach
    void history() throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "real hg required");
        dir = tempDir.resolve("real").toFile();
        dir.mkdirs();
        HgTestUtils.hg(dir, "init");
        Files.writeString(new File(dir, ".hg/hgrc").toPath(), "[extensions]\nlfs =\n[lfs]\ntrack = size(\">100\")\n");
        base = commit("base", "big.txt", big("first", "last"), "small.txt", "s\n");
        p1 = commit("ours", "big.txt", big("first ours", "last"), "small.txt", "s ours\n");
        HgTestUtils.hg(dir, "update", "-r", base);
        p2 = commit("theirs", "big.txt", big("first", "last theirs"), "added.txt", "a\n");
    }

    private static String big(String first, String last) {
        StringBuilder text = new StringBuilder(first).append('\n');
        for (int i = 2; i < 40; i++) {
            text.append("line ").append(i).append(" of the large file\n");
        }
        return text.append(last).append('\n').toString();
    }

    private String commit(String message, String... fileAndContent) throws Exception {
        for (int i = 0; i < fileAndContent.length; i += 2) {
            Files.writeString(new File(dir, fileAndContent[i]).toPath(), fileAndContent[i + 1]);
        }
        HgTestUtils.hg(dir, "commit", "-A", "-m", message, "-u", "t", "-d", DATE);
        return HgTestUtils.hg(dir, "log", "-r", ".", "--template", "{node}").trim();
    }

    /** A copy of the repository (the LFS blobs are in it) for hg4j to work on. */
    private File copyOfTheRepository() throws IOException {
        File copy = tempDir.resolve("hg4j").toFile();
        try (Stream<Path> walk = Files.walk(dir.toPath())) {
            for (Path source : (Iterable<Path>) walk::iterator) {
                Path target = copy.toPath().resolve(dir.toPath().relativize(source));
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target);
                }
            }
        }
        return copy;
    }

    private static String node(File repo, String revset) throws Exception {
        return HgTestUtils.hg(repo, "log", "-r", revset, "--template", "{node}").trim();
    }

    @Test
    void aMergeWritesTheSamePointerRevisionAsTheRealHg() throws Exception {
        File ours = copyOfTheRepository();
        HgTestUtils.hg(dir, "update", "-r", p1);
        HgTestUtils.hg(dir, "merge", "-r", p2, "--tool", "internal:merge");
        HgTestUtils.hg(dir, "commit", "-m", "merge", "-u", "t", "-d", DATE);
        String expected = node(dir, ".");

        MergeCommitResult result = new MergeCommitCommand(new HgRepository(ours))
                .setP1(p1).setP2(p2).setUser("t").setDate(DATE).setMessage("merge").call();
        assertFalse(result.isConflicted(), result.getConflicts().toString());
        assertEquals(expected, result.getNodeHex());
        HgTestUtils.hg(ours, "verify");
    }

    @Test
    void aSquashWritesTheSamePointerRevisionAsTheRealHg() throws Exception {
        File ours = copyOfTheRepository();
        HgTestUtils.hg(dir, "update", "-r", p1);
        HgTestUtils.hg(dir, "merge", "-r", p2, "--tool", "internal:merge");
        HgTestUtils.hg(dir, "debugsetparents", p1);
        HgTestUtils.hg(dir, "commit", "-m", "squash", "-u", "t", "-d", DATE);
        String expected = node(dir, ".");

        MergeCommitResult result = new MergeCommitCommand(new HgRepository(ours))
                .setP1(p1).setP2(p2).setSquash(true).setUser("t").setDate(DATE).setMessage("squash").call();
        assertFalse(result.isConflicted(), result.getConflicts().toString());
        assertEquals(expected, result.getNodeHex());
        HgTestUtils.hg(ours, "verify");
    }

    @Test
    void anInMemoryGraftWritesTheSamePointerRevisionAsTheRealHg() throws Exception {
        HgTestUtils.hg(dir, "update", "-r", base);
        String target = commit("target", "big.txt", big("first ours", "last"));
        File ours = copyOfTheRepository();
        HgTestUtils.hg(dir, "graft", "--to", target, "-r", p2, "-u", "t", "-d", DATE);
        String expected = node(dir, "children(" + target + ")");

        GraftResult result = new GraftInMemory(new HgRepository(ours)).setTo(target).addRevision(p2).setUser("t").setDate(DATE).execute();
        assertFalse(result.isConflicted(), result.getConflicts().toString());
        assertEquals(List.of(expected), result.getNodeHexes());
        HgTestUtils.hg(ours, "verify");
    }
}
