package org.hg4j.api;

import org.hg4j.core.Dirstate;
import org.hg4j.core.HgRepository;
import org.hg4j.core.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class WorkingCopySafetyTest {

    @Test
    public void testUpdateCommandDirtyGuard(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Baseline Commit (Revision 0)
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Baseline Content\n");
        Hg.add(repo).call();
        byte[] baseNode = Hg.commit(repo).setMessage("Base").call();

        // 2. Second Commit (Revision 1)
        Files.writeString(f1.toPath(), "Revision 1 Content\n");
        byte[] rev1Node = Hg.commit(repo).setMessage("Rev 1").call();

        // 3. Make working copy dirty by modifying f1
        Files.writeString(f1.toPath(), "Dirty Content\n");

        // 4. Update back to base revision 0 without force should fail
        UpdateCommand updateCmd = Hg.update(repo).setRevision("0");
        IOException ex = assertThrows(IOException.class, updateCmd::call);
        assertTrue(ex.getMessage().contains("uncommitted changes"));

        // 5. Update back to base revision 0 with force should succeed
        byte[] resNode = Hg.update(repo).setRevision("0").setForce(true).call();
        assertArrayEquals(baseNode, resNode);
        assertEquals("Baseline Content\n", Files.readString(f1.toPath()));
    }

    @Test
    public void testRemoveCommandDirtyGuard(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Baseline Commit
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Baseline Content\n");
        Hg.add(repo).call();
        Hg.commit(repo).setMessage("Base").call();

        // 2. Modify f1 (dirty)
        Files.writeString(f1.toPath(), "Dirty Content\n");

        // 3. Remove without force should fail
        RemoveCommand rmCmd = Hg.remove(repo).setFile("a.txt");
        IOException ex = assertThrows(IOException.class, rmCmd::call);
        assertTrue(ex.getMessage().contains("uncommitted changes (modified)"));

        // 4. Remove with force should succeed
        assertTrue(Hg.remove(repo).setFile("a.txt").setForce(true).call());
        assertFalse(f1.exists());

        // 5. Test with newly added file
        File f2 = new File(repoDir, "b.txt");
        Files.writeString(f2.toPath(), "Newly added\n");
        Hg.add(repo).call(); // state is 'a'

        // 6. Remove without force should fail
        RemoveCommand rmCmd2 = Hg.remove(repo).setFile("b.txt");
        IOException ex2 = assertThrows(IOException.class, rmCmd2::call);
        assertTrue(ex2.getMessage().contains("uncommitted changes (added)"));

        // 7. Remove with force should succeed
        assertTrue(Hg.remove(repo).setFile("b.txt").setForce(true).call());
        assertFalse(f2.exists());
    }

    @Test
    public void testRevertCommandExceptionSafety(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Baseline Commit
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Baseline Content\n");
        Hg.add(repo).call();
        Hg.commit(repo).setMessage("Base").call();

        // 2. Modify f1 (dirty)
        Files.writeString(f1.toPath(), "Dirty Content\n");

        // 3. Try reverting to a non-existent invalid revision
        // This will trigger an IOException in CatCommand/resolveTargetNodeId, not "File not tracked at target revision".
        // The file should NOT be deleted, and the IOException should propagate to the caller.
        RevertCommand revertCmd = Hg.revert(repo).setFile("a.txt").setRevision("invalid_rev_123");
        IOException ex = assertThrows(IOException.class, revertCmd::call);
        
        // Assert file still exists and has not been deleted due to the error
        assertTrue(f1.exists());
        assertEquals("Dirty Content\n", Files.readString(f1.toPath()));
        
        // 4. Reverting to base should succeed
        assertTrue(Hg.revert(repo).setFile("a.txt").setRevision("0").call());
        assertEquals("Baseline Content\n", Files.readString(f1.toPath()));
    }

    @Test
    public void testUpdateAndRevertEdgeCases(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Revert with empty parent commit (zero node) and added file
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Added file content\n");
        Hg.add(repo).call(); // added, but parent commit is zero
        
        // Reverting this should succeed and delete/untrack the file because parent node is zero and file is added
        assertTrue(Hg.revert(repo).setFile("a.txt").call());
        assertFalse(f1.exists());
        assertNull(repo.getDirstate().getEntries().get("a.txt"));

        // 2. Commit a file to revision 0
        Files.writeString(f1.toPath(), "Baseline content\n");
        Hg.add(repo).call();
        byte[] baseNode = Hg.commit(repo).setMessage("Base").call();
        String baseHex = NodeIdUtil.toHex(baseNode);

        // 3. Test UpdateCommand with various revision specifiers
        // 3a. Update using branch name "default" (should succeed because it resolves default branch head)
        byte[] res1 = Hg.update(repo).setRevision("default").setForce(true).call();
        assertArrayEquals(baseNode, res1);

        // 3b. Update using partial hex nodeId prefix
        byte[] res2 = Hg.update(repo).setRevision(baseHex.substring(0, 8)).setForce(true).call();
        assertArrayEquals(baseNode, res2);

        // 3c. Update with identical content on disk (testing needsWrite = false optimization)
        // file content is already same. Update should not throw and complete cleanly.
        byte[] res3 = Hg.update(repo).setRevision("0").setForce(true).call();
        assertArrayEquals(baseNode, res3);

        // 3d. Update with ambiguous prefix should fail
        assertThrows(Exception.class, () -> {
            Hg.update(repo).setRevision("non_existent_branch_123").call(); // invalid/empty spec
        });

        // 4. Test Revert target not tracked at target commit
        // Create an untracked file, commit another revision, then try reverting that file to base revision where it wasn't tracked
        File f2 = new File(repoDir, "b.txt");
        Files.writeString(f2.toPath(), "Tracked in rev 1\n");
        Hg.add(repo).call();
        byte[] rev1Node = Hg.commit(repo).setMessage("Rev 1").call();

        // Reverting b.txt to revision "0" (baseNode) where it wasn't tracked
        // It should delete it from disk and untrack it completely because it's not tracked in target commit
        assertTrue(Hg.revert(repo).setFile("b.txt").setRevision("0").call());
        assertFalse(f2.exists());
        assertNull(repo.getDirstate().getEntries().get("b.txt"));
    }
}
