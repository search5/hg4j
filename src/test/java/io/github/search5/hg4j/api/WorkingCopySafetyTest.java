package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import io.github.search5.hg4j.errors.HgRepositoryNotFoundException;

public class WorkingCopySafetyTest {

    @Test
    public void testUpdateCommandDirtyGuard(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Baseline Commit (Revision 0)
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Baseline Content\n");
        new AddCommand(repo).call();
        byte[] baseNode = new CommitCommand(repo).setMessage("Base").call();

        // 2. Second Commit (Revision 1)
        Files.writeString(f1.toPath(), "Revision 1 Content\n");
        new CommitCommand(repo).setMessage("Rev 1").call();

        // 3. Make working copy dirty by modifying f1
        Files.writeString(f1.toPath(), "Dirty Content\n");

        // 4. Update back to base revision 0 without force should fail
        UpdateCommand updateCmd = new UpdateCommand(repo).setRevision("0");
        IOException ex = assertThrows(IOException.class, updateCmd::call);
        assertTrue(ex.getMessage().contains("uncommitted changes"));

        // 5. Update back to base revision 0 with force should succeed
        byte[] resNode = new UpdateCommand(repo).setRevision("0").setForce(true).call();
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
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Base").call();

        // 2. Modify f1 (dirty)
        Files.writeString(f1.toPath(), "Dirty Content\n");

        // 3. Remove without force should fail
        RemoveCommand rmCmd = new RemoveCommand(repo).setFile("a.txt");
        IOException ex = assertThrows(IOException.class, rmCmd::call);
        assertTrue(ex.getMessage().contains("uncommitted changes (modified)"));

        // 4. Remove with force should succeed
        assertTrue(new RemoveCommand(repo).setFile("a.txt").setForce(true).call());
        assertFalse(f1.exists());

        // 5. Test with newly added file
        File f2 = new File(repoDir, "b.txt");
        Files.writeString(f2.toPath(), "Newly added\n");
        new AddCommand(repo).call(); // state is 'a'

        // 6. Remove without force should fail
        RemoveCommand rmCmd2 = new RemoveCommand(repo).setFile("b.txt");
        IOException ex2 = assertThrows(IOException.class, rmCmd2::call);
        assertTrue(ex2.getMessage().contains("uncommitted changes (added)"));

        // 7. Remove with force should succeed
        assertTrue(new RemoveCommand(repo).setFile("b.txt").setForce(true).call());
        assertFalse(f2.exists());
    }

    @Test
    public void testRevertCommandExceptionSafety(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Baseline Commit
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Baseline Content\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Base").call();

        // 2. Modify f1 (dirty)
        Files.writeString(f1.toPath(), "Dirty Content\n");

        // 3. Try reverting to a non-existent invalid revision
        // This will trigger an IOException in CatCommand/resolveTargetNodeId, not "File not tracked at target revision".
        // The file should NOT be deleted, and the IOException should propagate to the caller.
        RevertCommand revertCmd = new RevertCommand(repo).setFile("a.txt").setRevision("invalid_rev_123");
        assertThrows(IOException.class, revertCmd::call);
        
        // Assert file still exists and has not been deleted due to the error
        assertTrue(f1.exists());
        assertEquals("Dirty Content\n", Files.readString(f1.toPath()));
        
        // 4. Reverting to base should succeed
        assertTrue(new RevertCommand(repo).setFile("a.txt").setRevision("0").call());
        assertEquals("Baseline Content\n", Files.readString(f1.toPath()));
    }

    @Test
    public void testUpdateAndRevertEdgeCases(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Revert with empty parent commit (zero node) and added file
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Added file content\n");
        new AddCommand(repo).call(); // added, but parent commit is zero
        
        // Reverting this should succeed and delete/untrack the file because parent node is zero and file is added
        assertTrue(new RevertCommand(repo).setFile("a.txt").call());
        assertFalse(f1.exists());
        assertNull(repo.getDirstate().getEntries().get("a.txt"));

        // 2. Commit a file to revision 0
        Files.writeString(f1.toPath(), "Baseline content\n");
        new AddCommand(repo).call();
        byte[] baseNode = new CommitCommand(repo).setMessage("Base").call();
        String baseHex = NodeIdUtil.toHex(baseNode);

        // 3. Test UpdateCommand with various revision specifiers
        // 3a. Update using branch name "default" (should succeed because it resolves default branch head)
        byte[] res1 = new UpdateCommand(repo).setRevision("default").setForce(true).call();
        assertArrayEquals(baseNode, res1);

        // 3b. Update using partial hex nodeId prefix
        byte[] res2 = new UpdateCommand(repo).setRevision(baseHex.substring(0, 8)).setForce(true).call();
        assertArrayEquals(baseNode, res2);

        // 3c. Update with identical content on disk (testing needsWrite = false optimization)
        // file content is already same. Update should not throw and complete cleanly.
        byte[] res3 = new UpdateCommand(repo).setRevision("0").setForce(true).call();
        assertArrayEquals(baseNode, res3);

        // 3d. Update with ambiguous prefix should fail
        assertThrows(Exception.class, () -> {
            new UpdateCommand(repo).setRevision("non_existent_branch_123").call(); // invalid/empty spec
        });

        // 4. Test Revert target not tracked at target commit
        // Create an untracked file, commit another revision, then try reverting that file to base revision where it wasn't tracked
        File f2 = new File(repoDir, "b.txt");
        Files.writeString(f2.toPath(), "Tracked in rev 1\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Rev 1").call();

        // Reverting b.txt to revision "0" (baseNode) where it wasn't tracked
        // It should delete it from disk and untrack it completely because it's not tracked in target commit
        assertTrue(new RevertCommand(repo).setFile("b.txt").setRevision("0").call());
        assertFalse(f2.exists());
        assertNull(repo.getDirstate().getEntries().get("b.txt"));
    }

    @Test
    public void testUpdateCommandEdgeCases(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Empty repository validation exception
        UpdateCommand updateEmpty = new UpdateCommand(repo).setRevision(null);
        Exception ex1 = assertThrows(Exception.class, updateEmpty::call);
        assertTrue(ex1.getMessage().contains("Repository is empty"));

        // 2. Non-existent branch / revision exception
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Content\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Base").call();

        UpdateCommand updateInvalid = new UpdateCommand(repo).setRevision("non_existent_branch_999");
        assertThrows(Exception.class, updateInvalid::call);

        // 3. File version missing from filelog index (invalid node ID in manifest map simulation)
        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "a.txt");
        assertTrue(flIdx.exists());
        
        // Let's delete the filelog index to force "Filelog index not found" exception during update
        assertTrue(flIdx.delete());
        UpdateCommand updateCorrupt = new UpdateCommand(repo).setRevision("0").setForce(true);
        assertThrows(HgRepositoryNotFoundException.class, updateCorrupt::call);

        // 4. Named branch update test (restore repo state by initializing a new one)
        File repoDirBranch = new File(repoDir, "branch_test_dir");
        repoDirBranch.mkdirs();
        HgRepository repoB = Hg.init().setDirectory(repoDirBranch).call();

        File fBranch = new File(repoDirBranch, "branch.txt");
        Files.writeString(fBranch.toPath(), "Branch content\n");
        new AddCommand(repoB).call();
        new BranchCommand(repoB).setBranchName("my-feature").call();
        new CommitCommand(repoB).setMessage("Branch commit").call();

        // Update using branch name "my-feature" should succeed
        byte[] resBranch = new UpdateCommand(repoB).setRevision("my-feature").setForce(true).call();
        assertNotNull(resBranch);
    }
}
