package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.storage.Revlog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class HgAdvancedHistoryTest {

    @Test
    public void testShelveAndUnshelve(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Commit baseline file
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Baseline content");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Base commit").call();

        // 2. Modify f1 and create added f2
        Files.writeString(f1.toPath(), "Modified content is completely different and much longer!");
        File f2 = new File(repoDir, "b.txt");
        Files.writeString(f2.toPath(), "Added file content");
        new AddCommand(repo).call();

        // 3. Perform shelve
        new ShelveCommand(repo).setName("feature_stash").call();

        // Verify working copy reverted to Baseline and f2 is deleted
        assertEquals("Baseline content", Files.readString(f1.toPath()));
        assertFalse(f2.exists());

        Dirstate dsPostShelve = repo.getDirstate();
        assertEquals('n', dsPostShelve.getEntries().get("a.txt").getState());
        assertNull(dsPostShelve.getEntries().get("b.txt"));

        // 4. Perform unshelve
        new ShelveCommand(repo).setName("feature_stash").setUnshelve(true).call();

        // Verify working copy modifications restored
        assertEquals("Modified content is completely different and much longer!", Files.readString(f1.toPath()));
        assertTrue(f2.exists());
        assertEquals("Added file content", Files.readString(f2.toPath()));

        Dirstate dsPostUnshelve = repo.getDirstate();
        // Since unshelving restored state:
        assertEquals('a', dsPostUnshelve.getEntries().get("b.txt").getState());
    }

    @Test
    public void testRebaseLinearCherryPick(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // Commit 0 (Common Base)
        File f1 = new File(repoDir, "base.txt");
        Files.writeString(f1.toPath(), "Common Base Content");
        new AddCommand(repo).call();
        byte[] commonBase = new CommitCommand(repo).setMessage("Commit 0").call();

        // Branch main: Commit 1
        File fMain = new File(repoDir, "main.txt");
        Files.writeString(fMain.toPath(), "Main branch edit");
        new AddCommand(repo).call();
        byte[] mainHead = new CommitCommand(repo).setMessage("Commit 1 (Main)").call();

        // Backtrack to Common Base to spawn branch feature
        Dirstate ds = repo.getDirstate();
        ds.setParents(commonBase, new byte[20]);
        repo.writeDirstate(ds);

        // Branch feature: Commit 2
        File fFeature = new File(repoDir, "feature.txt");
        Files.writeString(fFeature.toPath(), "Feature branch edit");
        new AddCommand(repo).call();
        byte[] featureHead = new CommitCommand(repo).setMessage("Commit 2 (Feature)").call();

        // Now rebase featureHead on top of mainHead
        RebaseCommand rebaseCmd = new RebaseCommand(repo)
                .setSource(featureHead)
                .setTarget(mainHead);
        
        byte[] newHead = rebaseCmd.call();
        assertNotNull(newHead);

        // Verify linear history: newHead parent must be mainHead
        Revlog cl = new Revlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"));
        int newHeadRev = NodeIdUtil.findRevisionByNodeId(cl, newHead);
        assertTrue(newHeadRev != -1);

        Revlog.IndexRecord rec = cl.getIndexRecord(newHeadRev);
        int parent1Rev = rec.getParent1();
        byte[] parent1Node = cl.getIndexRecord(parent1Rev).getNodeId();
        assertArrayEquals(mainHead, parent1Node);

        // Verify all files exist in working directory after rebase checkout
        assertTrue(f1.exists());
        assertTrue(fMain.exists());
        assertTrue(fFeature.exists());
    }

    @Test
    public void testRebaseNonLinearHistoryPreservesIndependentBranch(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Common Base (Commit 0)
        File fBase = new File(repoDir, "base.txt");
        Files.writeString(fBase.toPath(), "C0 Content");
        new AddCommand(repo).call();
        byte[] c0Node = new CommitCommand(repo).setMessage("Commit 0").call();

        // 2. Main branch (Commit 1)
        File fMain = new File(repoDir, "main.txt");
        Files.writeString(fMain.toPath(), "C1 Main Edit");
        new AddCommand(repo).call();
        byte[] c1Node = new CommitCommand(repo).setMessage("Commit 1").call();

        // 3. Backtrack to C0 for Feature branch (Commit 2)
        Dirstate ds = repo.getDirstate();
        ds.setParents(c0Node, new byte[20]);
        repo.writeDirstate(ds);

        File fFeature = new File(repoDir, "feature.txt");
        Files.writeString(fFeature.toPath(), "C2 Feature Edit");
        new AddCommand(repo).call();
        byte[] c2Node = new CommitCommand(repo).setMessage("Commit 2").call();

        // 4. Backtrack to C0 again for an Independent branch (Commit 3)
        // This independent branch should be preserved during the rebase of C2 onto C1!
        ds = repo.getDirstate();
        ds.setParents(c0Node, new byte[20]);
        repo.writeDirstate(ds);

        File fIndependent = new File(repoDir, "independent.txt");
        Files.writeString(fIndependent.toPath(), "C3 Independent Edit");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit 3").call();

        // 5. Rebase C2(Feature branch) onto C1(Main branch)
        RebaseCommand rebaseCmd = new RebaseCommand(repo)
                .setSource(c2Node)
                .setTarget(c1Node);
        
        byte[] rebasedFeatureHead = rebaseCmd.call();
        assertNotNull(rebasedFeatureHead);

        // 6. Verify History Structures
        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog cl = new Revlog(clIdx, clDat);

        // Evolution-only rebase (2026-09-04): C2 is never physically stripped/restored -- it
        // remains exactly where it always was (merely hidden via an obsolescence marker), and the
        // independent branch C3 was never touched at all. So the post-rebase changelog holds all
        // 4 original revisions (C0, C1, C2, C3) PLUS the freshly cherry-picked C2' = 5, not the
        // pre-2026-09-04 4.
        assertEquals(5, cl.getRevisionCount(), "History should contain 5 revisions after non-linear rebase "
                + "(C0, C1, C2 kept-but-hidden, C3 untouched, C2').");

        // Find the revisions
        int c0Rev = NodeIdUtil.findRevisionByNodeId(cl, c0Node);
        int c1Rev = NodeIdUtil.findRevisionByNodeId(cl, c1Node);
        int c2Rev = NodeIdUtil.findRevisionByNodeId(cl, c2Node);

        // Find C2' (its parent1 must be C1)
        int rebasedFeatureRev = NodeIdUtil.findRevisionByNodeId(cl, rebasedFeatureHead);
        assertTrue(rebasedFeatureRev != -1);
        assertEquals(c1Rev, cl.getIndexRecord(rebasedFeatureRev).getParent1(), "Rebased Feature's parent must be C1.");

        // C2 itself is still present, unmodified, still parented on C0 (now hidden, not gone).
        assertTrue(c2Rev != -1, "Original C2 must remain fully readable (evolution-only: no physical strip).");
        assertEquals(c0Rev, cl.getIndexRecord(c2Rev).getParent1());

        // Find the untouched independent branch C3 (its parent1 must be C0, distinct from C2).
        int independentRev = -1;
        for (int i = 0; i < cl.getRevisionCount(); i++) {
            if (cl.getIndexRecord(i).getParent1() == c0Rev && i != c1Rev && i != rebasedFeatureRev && i != c2Rev) {
                independentRev = i;
                break;
            }
        }
        assertTrue(independentRev != -1, "Independent branch C3 must remain untouched with C0 as parent1.");
        assertTrue(new String(cl.getRevisionContent(independentRev), StandardCharsets.UTF_8).contains("Commit 3"));
    }
}
