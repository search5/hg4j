package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import io.github.search5.hg4j.dirstate.Dirstate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tag ({@code hg tag}/{@code hg tags}) real hg CLI interop verification (backlog 23's "tag"
 * category). {@link TagCommand}/{@link TagsCommand} already had unit-test coverage before this,
 * but per backlog item 23 that coverage was hg4j-internal round trips only -- nothing had actually
 * been checked against a real {@code hg} 7.2.2 process. Every scenario here writes with one side
 * (hg4j or real hg) and reads back with the other.
 */
@Tag("interop")
public class TagRealHgInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    @Test
    public void hg4jGlobalTagIsRecognizedByRealHgTags(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);

        Files.writeString(new File(repoDir, "a.txt").toPath(), "one");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("T").setMessage("c0").call();

        new TagCommand(repo).setTagName("v1.0").setNodeId(c0).call();

        String nativeTags = HgTestUtils.hg(repoDir, "tags");
        assertTrue(nativeTags.contains("v1.0") && nativeTags.contains(NodeIdUtil.toHex(c0).substring(0, 12)),
                "real hg tags: " + nativeTags);

        // Real hg's own commit message convention for a tag commit.
        String nativeLogMsg = HgTestUtils.hg(repoDir, "log", "-r", "tip", "--template", "{desc}");
        assertEquals("Added tag v1.0 for changeset " + NodeIdUtil.toHex(c0).substring(0, 12), nativeLogMsg);
    }

    @Test
    public void realHgGlobalTagIsRecognizedByHg4jTagsCommand(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgTestUtils.nativeRepo(repoDir, dir -> {
            try {
                Files.writeString(new File(dir, "a.txt").toPath(), "one");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "T", "-m", "c0");
        HgTestUtils.hg(repoDir, "tag", "-u", "T", "v2.0");

        HgRepository repo = new HgRepository(repoDir);
        List<TagsCommand.Tag> tags = new TagsCommand(repo).call();
        Map<String, String> byName = tags.stream()
                .collect(Collectors.toMap(TagsCommand.Tag::getName,
                        t -> NodeIdUtil.toHex(t.getNode())));

        String v10RealHex = HgTestUtils.hg(repoDir, "log", "-r", "v2.0", "--template", "{node}");
        assertEquals(v10RealHex, byName.get("v2.0"));
        assertTrue(byName.containsKey("tip"));
    }

    @Test
    public void hg4jLocalTagIsRecognizedByRealHgAndStaysUncommitted(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);

        Files.writeString(new File(repoDir, "a.txt").toPath(), "one");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("T").setMessage("c0").call();

        int commitsBefore = new LogCommand(repo).call().size();
        new TagCommand(repo).setTagName("mylocal").setNodeId(c0).setLocal(true).call();
        int commitsAfter = new LogCommand(repo).call().size();
        assertEquals(commitsBefore, commitsAfter, "a local tag must not create a commit");

        File localTagsFile = new File(repo.getHgDir(), "localtags");
        assertTrue(localTagsFile.exists());
        assertFalse(new File(repoDir, ".hgtags").exists(), "a local-only tag must not touch .hgtags");

        String nativeTags = HgTestUtils.hg(repoDir, "tags");
        assertTrue(nativeTags.contains("mylocal"), "real hg tags: " + nativeTags);

        // Real hg's own status must not consider .hg/localtags part of the tracked working copy.
        String nativeStatus = HgTestUtils.hg(repoDir, "status");
        assertFalse(nativeStatus.contains("localtags"), "real hg status: " + nativeStatus);
    }

    @Test
    public void realHgLocalTagIsRecognizedByHg4jTagsCommandAndWinsOverSameNamedGlobalTag(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgTestUtils.nativeRepo(repoDir, dir -> {
            try {
                Files.writeString(new File(dir, "a.txt").toPath(), "one");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "T", "-m", "c0");
        String rev0Hex = HgTestUtils.hg(repoDir, "log", "-r", "0", "--template", "{node}");
        HgTestUtils.hg(repoDir, "tag", "-u", "T", "shared"); // global, tags rev0

        Files.writeString(new File(repoDir, "b.txt").toPath(), "two");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "T", "-m", "c-after-tag");
        String rev2Hex = HgTestUtils.hg(repoDir, "log", "-r", "tip", "--template", "{node}");
        HgTestUtils.hg(repoDir, "tag", "--local", "-u", "T", "-f", "-r", rev2Hex, "shared");

        HgRepository repo = new HgRepository(repoDir);
        List<TagsCommand.Tag> tags = new TagsCommand(repo).call();
        TagsCommand.Tag shared = tags.stream().filter(t -> t.getName().equals("shared")).findFirst().orElseThrow();
        assertTrue(shared.isLocal(), "local definition must win and be reported as local");
        assertEquals(rev2Hex, NodeIdUtil.toHex(shared.getNode()));

        // Sanity: real hg agrees the local tag shadows the global one.
        String nativeTags = HgTestUtils.hg(repoDir, "tags");
        assertTrue(nativeTags.contains("shared") && nativeTags.contains(rev2Hex.substring(0, 12)));
        assertFalse(rev0Hex.equals(rev2Hex));
    }

    @Test
    public void hg4jRetaggingMovesTheTagAndRealHgSeesTheNewTargetWithOldLineStale(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);

        Files.writeString(new File(repoDir, "a.txt").toPath(), "one");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("T").setMessage("c0").call();
        new TagCommand(repo).setTagName("v1.0").setNodeId(c0).call();

        Files.writeString(new File(repoDir, "b.txt").toPath(), "two");
        new AddCommand(repo).call();
        byte[] c1 = new CommitCommand(repo).setAuthor("T").setMessage("c1").call();
        // Moving an existing tag now requires -f (backlog #36, matches real hg's own gate).
        new TagCommand(repo).setTagName("v1.0").setNodeId(c1).setForce(true).call();

        String hgtagsContent = Files.readString(new File(repoDir, ".hgtags").toPath(), StandardCharsets.UTF_8);
        long v10Lines = hgtagsContent.lines().filter(l -> l.endsWith(" v1.0")).count();
        assertEquals(2, v10Lines, "moving a tag must append a new line, keeping the old one as dead history");

        String nativeTags = HgTestUtils.hg(repoDir, "tags");
        assertTrue(nativeTags.contains(NodeIdUtil.toHex(c1).substring(0, 12)),
                "real hg must resolve v1.0 to the *new* (moved-to) revision: " + nativeTags);
        assertFalse(nativeTags.lines().filter(l -> l.contains("v1.0")).anyMatch(l -> l.contains(NodeIdUtil.toHex(c0).substring(0, 12))),
                "real hg's tags listing must not show the stale c0 line as a live tag: " + nativeTags);

        List<TagsCommand.Tag> hg4jTags = new TagsCommand(repo).call();
        TagsCommand.Tag v10 = hg4jTags.stream().filter(t -> t.getName().equals("v1.0")).findFirst().orElseThrow();
        assertEquals(NodeIdUtil.toHex(c1), NodeIdUtil.toHex(v10.getNode()));
    }

    @Test
    public void hg4jTagRemovalIsRecognizedByRealHgAsGone(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);

        Files.writeString(new File(repoDir, "a.txt").toPath(), "one");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("T").setMessage("c0").call();
        new TagCommand(repo).setTagName("gone").setNodeId(c0).call();

        String beforeRemoveTags = HgTestUtils.hg(repoDir, "tags");
        assertTrue(beforeRemoveTags.contains("gone"));

        new TagCommand(repo).setTagName("gone").setRemove(true).call();

        List<TagsCommand.Tag> hg4jTags = new TagsCommand(repo).call();
        assertTrue(hg4jTags.stream().noneMatch(t -> t.getName().equals("gone")));

        String afterRemoveTags = HgTestUtils.hg(repoDir, "tags");
        assertFalse(afterRemoveTags.contains("gone"), "real hg must also no longer list the removed tag: " + afterRemoveTags);

        String nativeLogMsg = HgTestUtils.hg(repoDir, "log", "-r", "tip", "--template", "{desc}");
        assertEquals("Removed tag gone", nativeLogMsg);
    }

    @Test
    public void realHgTagRemovalIsRecognizedByHg4jAsGone(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgTestUtils.nativeRepo(repoDir, dir -> {
            try {
                Files.writeString(new File(dir, "a.txt").toPath(), "one");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "T", "-m", "c0");
        HgTestUtils.hg(repoDir, "tag", "-u", "T", "gone");
        HgTestUtils.hg(repoDir, "tag", "--remove", "-u", "T", "gone");

        HgRepository repo = new HgRepository(repoDir);
        List<TagsCommand.Tag> hg4jTags = new TagsCommand(repo).call();
        assertTrue(hg4jTags.stream().noneMatch(t -> t.getName().equals("gone")),
                "hg4j must recognize a real-hg-removed tag as gone");
    }

    /** Backlog #36: hg4j must reject a retag without {@code -f}, matching real hg's own gate
     * message byte-for-byte (verified against the CLI, 2026-09-04: {@code abort: tag '<name>'
     * already exists (use -f to force)}, exit 255). */
    @Test
    public void hg4jRejectsRetaggingWithoutForceMatchingRealHgsAbortMessage(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);

        Files.writeString(new File(repoDir, "a.txt").toPath(), "one");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("T").setMessage("c0").call();
        new TagCommand(repo).setTagName("v1.0").setNodeId(c0).call();

        Files.writeString(new File(repoDir, "b.txt").toPath(), "two");
        new AddCommand(repo).call();
        byte[] c1 = new CommitCommand(repo).setAuthor("T").setMessage("c1").call();

        HgValidationException ex = assertThrows(HgValidationException.class,
                () -> new TagCommand(repo).setTagName("v1.0").setNodeId(c1).call());
        assertEquals("tag 'v1.0' already exists (use -f to force)", ex.getMessage());

        // Real hg CLI, same scenario, must abort with the identical message.
        Files.writeString(repoDir.toPath().resolve("real-abort-check.txt"), "x");
        String nativeAbort = "";
        try {
            HgTestUtils.hg(repoDir, "tag", "-u", "T", "v1.0");
            fail("real hg must also abort retagging an existing tag without -f");
        } catch (AssertionError expected) {
            nativeAbort = expected.getMessage();
        }
        assertTrue(nativeAbort.contains("tag 'v1.0' already exists (use -f to force)"),
                "real hg abort message: " + nativeAbort);

        // The tag must genuinely be untouched -- still resolves to c0, not moved or duplicated.
        String hgtagsContent = Files.readString(new File(repoDir, ".hgtags").toPath(), StandardCharsets.UTF_8);
        assertEquals(1, hgtagsContent.lines().filter(l -> l.endsWith(" v1.0")).count(),
                "a rejected retag must not append a line");
        List<TagsCommand.Tag> hg4jTags = new TagsCommand(repo).call();
        TagsCommand.Tag v10 = hg4jTags.stream().filter(t -> t.getName().equals("v1.0")).findFirst().orElseThrow();
        assertEquals(NodeIdUtil.toHex(c0), NodeIdUtil.toHex(v10.getNode()));
    }

    /** Backlog #36, other half: with {@code -f}/{@code setForce(true)}, the move must still
     * succeed exactly as before this gate was added. */
    @Test
    public void hg4jRetagWithForceStillSucceedsAndRealHgSeesTheNewTarget(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);

        Files.writeString(new File(repoDir, "a.txt").toPath(), "one");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("T").setMessage("c0").call();
        new TagCommand(repo).setTagName("v1.0").setNodeId(c0).call();

        Files.writeString(new File(repoDir, "b.txt").toPath(), "two");
        new AddCommand(repo).call();
        byte[] c1 = new CommitCommand(repo).setAuthor("T").setMessage("c1").call();
        new TagCommand(repo).setTagName("v1.0").setNodeId(c1).setForce(true).call();

        String nativeTags = HgTestUtils.hg(repoDir, "tags");
        assertTrue(nativeTags.contains(NodeIdUtil.toHex(c1).substring(0, 12)),
                "real hg must resolve v1.0 to the forced new target: " + nativeTags);
    }

    /** Backlog #36: real hg's own gate also spans local vs. global -- a local tag colliding with
     * an existing global name (or vice versa) is rejected without {@code -f} too (verified
     * against the CLI, 2026-09-04). */
    @Test
    public void hg4jRejectsLocalTagCollidingWithExistingGlobalTagWithoutForce(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);

        Files.writeString(new File(repoDir, "a.txt").toPath(), "one");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("T").setMessage("c0").call();
        new TagCommand(repo).setTagName("shared").setNodeId(c0).call(); // global

        HgValidationException ex = assertThrows(HgValidationException.class,
                () -> new TagCommand(repo).setTagName("shared").setNodeId(c0).setLocal(true).call());
        assertEquals("tag 'shared' already exists (use -f to force)", ex.getMessage());
    }

    /** Backlog #36: removal is exempt from the force gate -- an existing tag can always be
     * removed (verified against the CLI, 2026-09-04: {@code hg tag --remove} on a live tag exits
     * 0 without {@code -f}). */
    @Test
    public void hg4jTagRemovalDoesNotRequireForceEvenThoughTagCurrentlyExists(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);

        Files.writeString(new File(repoDir, "a.txt").toPath(), "one");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("T").setMessage("c0").call();
        new TagCommand(repo).setTagName("gone").setNodeId(c0).call();

        // No setForce(true) here -- must not throw.
        new TagCommand(repo).setTagName("gone").setRemove(true).call();

        List<TagsCommand.Tag> hg4jTags = new TagsCommand(repo).call();
        assertTrue(hg4jTags.stream().noneMatch(t -> t.getName().equals("gone")));
    }

    @Test
    public void hg4jTagOnAMergeCommitIsRecognizedByRealHg(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);

        Files.writeString(new File(repoDir, "a.txt").toPath(), "0");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("T").setMessage("c0").call();

        Files.writeString(new File(repoDir, "b.txt").toPath(), "1");
        new AddCommand(repo).call();
        byte[] c1 = new CommitCommand(repo).setAuthor("T").setMessage("c1").call();

        Dirstate forkDirstate = repo.getDirstate();
        forkDirstate.setParents(new NodeId(c0), NodeId.NULL);
        repo.writeDirstate(forkDirstate);
        Files.writeString(new File(repoDir, "c.txt").toPath(), "2");
        new AddCommand(repo).call();
        byte[] c2 = new CommitCommand(repo).setAuthor("T").setMessage("c2 (second head)").call();

        Dirstate mergeDirstate = repo.getDirstate();
        mergeDirstate.setParents(new NodeId(c2), new NodeId(c1));
        repo.writeDirstate(mergeDirstate);
        byte[] merge = new CommitCommand(repo).setAuthor("T").setMessage("merge").call();

        new TagCommand(repo).setTagName("mergepoint").setNodeId(merge).call();

        String nativeTags = HgTestUtils.hg(repoDir, "tags");
        assertTrue(nativeTags.contains("mergepoint") && nativeTags.contains(NodeIdUtil.toHex(merge).substring(0, 12)),
                "real hg tags: " + nativeTags);
        // Sanity: real hg must also agree the tagged revision is genuinely a merge (two parents).
        String parents = HgTestUtils.hg(repoDir, "log", "-r", "mergepoint", "--template", "{p1rev} {p2rev}");
        assertFalse(parents.contains("-1"), "tagged revision must be a real merge commit (both parents present): " + parents);
    }
}
