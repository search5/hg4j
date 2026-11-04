package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.phase.PhaseRoots;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code hg strip REV} removes REV and its descendants only (repair.strip): a revision above REV that is not a descendant is
 * saved, the store is cut and the saved revision is added again. The working copy goes to the parent of REV (strip.py).
 *
 * <pre>
 *   r0 --- r1 --- r3      strip r1: r1 and r3 go, r2 stays
 *     \
 *      --- r2
 * </pre>
 */
class StripKeepsUnrelatedRevisionsTest {

    @TempDir
    Path tempDir;

    private File dir;
    private byte[] r0;
    private byte[] r1;
    private byte[] r2;
    private byte[] r3;

    private byte[] commitFile(HgRepository repo, String name, String message) throws Exception {
        Files.writeString(new File(dir, name).toPath(), name + "\n");
        new AddCommand(repo).call();
        return new CommitCommand(repo).setMessage(message).setAuthor("t").call();
    }

    private HgRepository build() throws Exception {
        dir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(dir).call();
        r0 = commitFile(repo, "a.txt", "r0");
        r1 = commitFile(repo, "b.txt", "r1");
        new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(r0)).call();
        r2 = commitFile(repo, "c.txt", "r2");
        new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(r1)).call();
        r3 = commitFile(repo, "d.txt", "r3");
        return repo;
    }

    private Revlog changelog(HgRepository repo) throws Exception {
        repo.clearRevlogCache();
        return repo.getRevlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"));
    }

    @Test
    void onlyTheTargetAndItsDescendantsAreRemoved() throws Exception {
        HgRepository repo = build();
        new StripCommand(repo).setRevision(NodeIdUtil.toHex(r1)).call();
        Revlog changelog = changelog(repo);
        assertEquals(2, changelog.getRevisionCount(), "r0 and r2 are left");
        assertEquals(0, changelog.findRevision(r0));
        assertEquals(1, changelog.findRevision(r2), "the saved revision is added again with the same hash");
        assertEquals(-1, changelog.findRevision(r1));
        assertEquals(-1, changelog.findRevision(r3));
        assertEquals(List.of(), new VerifyCommand(repo).call());
        // the files that only the stripped revisions had are gone from the store
        assertFalse(new File(repo.getStoreDir(), "data/b.txt.i").exists());
        assertFalse(new File(repo.getStoreDir(), "data/d.txt.i").exists());
        assertTrue(new File(repo.getStoreDir(), "data/c.txt.i").exists());
    }

    @Test
    void theWorkingCopyGoesToTheParentOfTheStrippedRevision() throws Exception {
        HgRepository repo = build();
        new StripCommand(repo).setRevision(NodeIdUtil.toHex(r1)).call();
        assertArrayEquals(r0, repo.getDirstate().getParent1());
        assertTrue(new File(dir, "a.txt").exists());
        assertFalse(new File(dir, "b.txt").exists(), "files of the stripped revisions are removed");
        assertFalse(new File(dir, "d.txt").exists());
    }

    @Test
    void bookmarksOfStrippedNodesMoveAndTheOthersStay() throws Exception {
        HgRepository repo = build();
        new BookmarkCommand(repo).setBookmarkName("keepme").setRevision(NodeIdUtil.toHex(r2)).call();
        new BookmarkCommand(repo).setBookmarkName("gone").setRevision(NodeIdUtil.toHex(r3)).call();
        new StripCommand(repo).setRevision(NodeIdUtil.toHex(r1)).call();
        Map<String, String> bookmarks = new BookmarkCommand(repo).list();
        assertEquals(NodeIdUtil.toHex(r2), bookmarks.get("keepme"), "a node that survives keeps its bookmark");
        assertEquals(NodeIdUtil.toHex(r0), bookmarks.get("gone"), "a stripped node's bookmark moves to the parent");
    }

    @Test
    void theSavedRevisionKeepsItsPhase() throws Exception {
        HgRepository repo = build();
        // r0 and r1 become public, r2 and r3 stay draft
        new PhaseCommand(repo).setRevision(NodeIdUtil.toHex(r1)).setPhase(0).call();
        PhaseRoots before = repo.getPhaseRoots();
        PhaseRoots.Phase r2Before = before.getPhase(new NodeId(r2), changelog(repo));
        new StripCommand(repo).setRevision(NodeIdUtil.toHex(r3)).call();   // r3 is a leaf: nothing is saved
        assertEquals(r2Before, repo.getPhaseRoots().getPhase(new NodeId(r2), changelog(repo)));
        // now strip something that has to save r2
        HgRepository again = build2();
        PhaseRoots.Phase draft = again.getPhaseRoots().getPhase(new NodeId(r2), changelog(again));
        new StripCommand(again).setRevision(NodeIdUtil.toHex(r1)).call();
        assertEquals(draft, again.getPhaseRoots().getPhase(new NodeId(r2), changelog(again)), "r2 is saved and added again as it was");
    }

    private HgRepository build2() throws Exception {
        tempDir.toFile().toPath();
        File other = Files.createTempDirectory(tempDir, "second").toFile();
        dir = other;
        HgRepository repo = Hg.init().setDirectory(dir).call();
        r0 = commitFile(repo, "a.txt", "r0");
        r1 = commitFile(repo, "b.txt", "r1");
        new UpdateCommand(repo).setRevision(NodeIdUtil.toHex(r0)).call();
        r2 = commitFile(repo, "c.txt", "r2");
        return repo;
    }

    @Test
    void uncommittedChangesAreRefusedUnlessForcedOrKept() throws Exception {
        HgRepository repo = build();
        Files.writeString(new File(dir, "d.txt").toPath(), "edited\n");
        HgValidationException refused = assertThrows(HgValidationException.class,
                () -> new StripCommand(repo).setRevision(NodeIdUtil.toHex(r1)).call());
        assertEquals("uncommitted changes", refused.getMessage());
        assertEquals(4, changelog(repo).getRevisionCount(), "a refused strip changes nothing");

        // --keep: the files stay as they are, only the parent follows
        new StripCommand(repo).setRevision(NodeIdUtil.toHex(r1)).setKeep(true).call();
        assertEquals(2, changelog(repo).getRevisionCount());
        assertEquals("edited\n", Files.readString(new File(dir, "d.txt").toPath()));
        assertArrayEquals(r0, repo.getDirstate().getParent1());
    }

    @Test
    void forceDiscardsUncommittedChanges() throws Exception {
        HgRepository repo = build();
        Files.writeString(new File(dir, "d.txt").toPath(), "edited\n");
        new StripCommand(repo).setRevision(NodeIdUtil.toHex(r1)).setForce(true).call();
        assertEquals(2, changelog(repo).getRevisionCount());
        assertFalse(new File(dir, "d.txt").exists());
    }
}
