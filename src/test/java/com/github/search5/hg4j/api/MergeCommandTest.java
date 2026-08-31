package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.core.HgRepository;
import com.github.search5.hg4j.core.Revlog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MergeCommandTest {

    @Test
    public void testCleanMergeAndCommit(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Base Commit (Revision 0)
        File f1 = new File(repoDir, "hello.txt");
        Files.writeString(f1.toPath(), "Line 1\nLine 2\nLine 3\n");
        new AddCommand(repo).call();
        byte[] baseNode = new CommitCommand(repo).setMessage("Base commit").call();

        // 2. Commit Yours (Revision 1)
        Files.writeString(f1.toPath(), "Line 1 [MINE]\nLine 2\nLine 3\n");
        byte[] yoursNode = new CommitCommand(repo).setMessage("Yours change").call();

        // 3. Switch back to Base and commit Theirs (Revision 2)
        // Set dirstate parent back to base to simulate branching
        Dirstate dirstate = repo.getDirstate();
        dirstate.setParents(baseNode, new byte[20]);
        repo.writeDirstate(dirstate);
        
        // Overwrite hello.txt with base + Theirs change
        Files.writeString(f1.toPath(), "Line 1\nLine 2\nLine 3 [THEIRS]\n");
        byte[] theirsNode = new CommitCommand(repo).setMessage("Theirs change").call();

        // 4. Merge Yours (Revision 1) into Theirs (Revision 2)
        // Currently, dirstate parent is Theirs (Revision 2)
        MergeCommand.MergeResult res = new MergeCommand(repo).setNodeId(yoursNode).call();
        assertFalse(res.isConflicted());
        assertTrue(res.getConflicts().isEmpty());

        // Verify merged content on disk
        String diskContent = Files.readString(f1.toPath());
        assertEquals("Line 1 [MINE]\nLine 2\nLine 3 [THEIRS]\n", diskContent);

        // Verify dirstate parent headers (P1 = theirsNode, P2 = yoursNode)
        Dirstate postMergeDs = repo.getDirstate();
        assertArrayEquals(theirsNode, postMergeDs.getParent1());
        assertArrayEquals(yoursNode, postMergeDs.getParent2());

        // 5. Commit the merge (Revision 3)
        byte[] mergeCommitNode = new CommitCommand(repo)
                .setAuthor("Merger <merger@example.com>")
                .setMessage("Merged branch Yours")
                .call();
        assertNotNull(mergeCommitNode);

        // Verify changelog index record of Revision 3 has Parent 1 = 2 (theirs) and Parent 2 = 1 (yours)
        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog changelog = new Revlog(clIdx, clDat);
        Revlog.IndexRecord mergeRec = changelog.getIndexRecord(3);
        assertEquals(2, mergeRec.getParent1());
        assertEquals(1, mergeRec.getParent2());

        // Verify dirstate parent resets to [mergeCommitNode, zeros]
        Dirstate finalDs = repo.getDirstate();
        assertArrayEquals(mergeCommitNode, finalDs.getParent1());
        assertTrue(isAllZero(finalDs.getParent2()));
    }

    @Test
    public void testConflictingMerge(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Base Commit
        File f1 = new File(repoDir, "hello.txt");
        Files.writeString(f1.toPath(), "Line 1\nLine 2\nLine 3\n");
        new AddCommand(repo).call();
        byte[] baseNode = new CommitCommand(repo).setMessage("Base commit").call();

        // 2. Commit Yours
        Files.writeString(f1.toPath(), "Line 1\nLine 2 [MINE]\nLine 3\n");
        byte[] yoursNode = new CommitCommand(repo).setMessage("Yours change").call();

        // 3. Switch back to Base and commit Theirs
        Dirstate dirstate = repo.getDirstate();
        dirstate.setParents(baseNode, new byte[20]);
        repo.writeDirstate(dirstate);
        Files.writeString(f1.toPath(), "Line 1\nLine 2 [THEIRS]\nLine 3\n");
        new CommitCommand(repo).setMessage("Theirs change").call();

        // 4. Merge Yours into Theirs (Conflict expected on hello.txt)
        MergeCommand.MergeResult res = new MergeCommand(repo).setNodeId(yoursNode).call();
        assertTrue(res.isConflicted());
        assertEquals(List.of("hello.txt"), res.getConflicts());

        // Verify conflict markers written on disk
        String diskContent = Files.readString(f1.toPath());
        assertTrue(diskContent.contains("<<<<<<< Yours"));
        assertTrue(diskContent.contains("Line 2 [THEIRS]")); // since theirs is the current checkout (Parent 1)
        assertTrue(diskContent.contains("======="));
        assertTrue(diskContent.contains("Line 2 [MINE]")); // since yours is the target to merge (Parent 2)
        assertTrue(diskContent.contains(">>>>>>> Theirs"));
    }

    @Test
    public void testMergeWithAddedFile(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Base Commit (hello.txt)
        File f1 = new File(repoDir, "hello.txt");
        Files.writeString(f1.toPath(), "Hello Base\n");
        new AddCommand(repo).call();
        byte[] baseNode = new CommitCommand(repo).setMessage("Base commit").call();

        // 2. Commit Yours (No change to f1, but keep as P1 branch)
        byte[] yoursNode = new CommitCommand(repo).setMessage("Yours branch commit").call();

        // 3. Switch back to Base and commit Theirs with an ADDED file (added.txt)
        Dirstate dirstate = repo.getDirstate();
        dirstate.setParents(baseNode, new byte[20]);
        repo.writeDirstate(dirstate);

        File f2 = new File(repoDir, "added.txt");
        Files.writeString(f2.toPath(), "This file is added in Theirs\n");
        new AddCommand(repo).call();
        byte[] theirsNode = new CommitCommand(repo).setMessage("Theirs branch commit with added file").call();

        // 4. Merge Theirs (theirsNode) into Yours (yoursNode)
        // Currently, dirstate parent is Theirs (theirsNode)
        // Let's reset parents to Yours (yoursNode) and read dirstate to simulate being on Yours branch
        dirstate = repo.getDirstate();
        dirstate.setParents(yoursNode, new byte[20]);
        // Also remove added.txt from working copy to simulate Yours branch state
        if (f2.exists()) f2.delete();
        dirstate.removeEntry("added.txt");
        repo.writeDirstate(dirstate);

        MergeCommand.MergeResult res = new MergeCommand(repo).setNodeId(theirsNode).call();
        assertFalse(res.isConflicted());

        // Verify added.txt is successfully merged (copied to Yours branch)
        assertTrue(f2.exists());
        assertEquals("This file is added in Theirs\n", Files.readString(f2.toPath()));

        Dirstate postMergeDs = repo.getDirstate();
        assertArrayEquals(yoursNode, postMergeDs.getParent1());
        assertArrayEquals(theirsNode, postMergeDs.getParent2());
        assertEquals('n', postMergeDs.getEntries().get("added.txt").getState());
    }

    @Test
    public void testMergeWithDeletedFile(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Base Commit (hello.txt and goodbye.txt)
        File f1 = new File(repoDir, "hello.txt");
        Files.writeString(f1.toPath(), "Hello Base\n");
        File f2 = new File(repoDir, "goodbye.txt");
        Files.writeString(f2.toPath(), "Goodbye Base\n");
        new AddCommand(repo).call();
        byte[] baseNode = new CommitCommand(repo).setMessage("Base commit").call();

        // 2. Commit Yours (Keep hello.txt, but delete goodbye.txt)
        if (f2.exists()) f2.delete();
        Dirstate ds = repo.getDirstate();
        ds.addEntry("goodbye.txt", new Dirstate.Entry('r', 0, 0, 0));
        repo.writeDirstate(ds);
        byte[] yoursNode = new CommitCommand(repo).setMessage("Yours commit (deleted goodbye.txt)").call();

        // 3. Switch back to Base and commit Theirs (Modify hello.txt, keep goodbye.txt)
        Dirstate dirstate = repo.getDirstate();
        dirstate.setParents(baseNode, new byte[20]);
        // Restore goodbye.txt to simulate base checkout
        Files.writeString(f2.toPath(), "Goodbye Base\n");
        dirstate.addEntry("goodbye.txt", new Dirstate.Entry('n', 0644, (int) f2.length(), (int) (f2.lastModified() / 1000)));
        repo.writeDirstate(dirstate);

        Files.writeString(f1.toPath(), "Hello Base [THEIRS]\n");
        byte[] theirsNode = new CommitCommand(repo).setMessage("Theirs commit (modified hello.txt)").call();

        // 4. Merge Theirs (theirsNode) into Yours (yoursNode)
        // Reset state to Yours branch
        dirstate = repo.getDirstate();
        dirstate.setParents(yoursNode, new byte[20]);
        Files.writeString(f1.toPath(), "Hello Base\n");
        if (f2.exists()) f2.delete();
        dirstate.removeEntry("goodbye.txt");
        repo.writeDirstate(dirstate);

        MergeCommand.MergeResult res = new MergeCommand(repo).setNodeId(theirsNode).call();
        assertFalse(res.isConflicted());

        // Verify goodbye.txt remains deleted, and hello.txt is updated to Theirs version
        assertFalse(f2.exists());
        assertEquals("Hello Base [THEIRS]\n", Files.readString(f1.toPath()));
    }

    @Test
    public void testMergeAlreadyMerged(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f1 = new File(repoDir, "hello.txt");
        Files.writeString(f1.toPath(), "Line 1\n");
        new AddCommand(repo).call();
        byte[] baseNode = new CommitCommand(repo).setMessage("Base").call();

        Files.writeString(f1.toPath(), "Line 1\nLine 2\n");
        byte[] p2Node = new CommitCommand(repo).setMessage("Feature").call();

        // Currently, p2Node is the descendant. If we attempt to merge baseNode (which is an ancestor)
        // it should cleanly report that it's already merged.
        MergeCommand.MergeResult res = new MergeCommand(repo).setNodeId(baseNode).call();
        assertFalse(res.isConflicted());
        assertTrue(res.getConflicts().isEmpty());

        // Parents should remain unchanged (P1 = p2Node, P2 = zeros)
        Dirstate postMergeDs = repo.getDirstate();
        assertArrayEquals(p2Node, postMergeDs.getParent1());
        assertTrue(isAllZero(postMergeDs.getParent2()));
    }

    @Test
    public void testMergeExceptionsAndEdgeCases(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Merge in empty repository (IllegalStateException)
        assertThrows(IllegalStateException.class, () -> new MergeCommand(repo).setRevision(0).call());

        // Create base commit
        File f1 = new File(repoDir, "hello.txt");
        Files.writeString(f1.toPath(), "Hello\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Base").call();

        // 2. Target commit not found by NodeID (IOException)
        byte[] fakeNode = new byte[20];
        Arrays.fill(fakeNode, (byte) 1);
        assertThrows(IOException.class, () -> new MergeCommand(repo).setNodeId(fakeNode).call());

        // 3. Target revision index out of bounds (IllegalArgumentException)
        assertThrows(IllegalArgumentException.class, () -> new MergeCommand(repo).setRevision(999).call());
        
        // 4. No target specified (IllegalArgumentException)
        assertThrows(IllegalArgumentException.class, () -> new MergeCommand(repo).call());

        // 5. Merge identical parents (p1Rev == p2Rev)
        // Find last commit node
        Revlog changelog = new Revlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"));
        byte[] lastNode = changelog.getIndexRecord(0).getNodeId();
        MergeCommand.MergeResult sameParentRes = new MergeCommand(repo).setNodeId(lastNode).call();
        assertFalse(sameParentRes.isConflicted());

        // 6. Delete in P1, keep in P2 reconciliation branch test
        // Let's create a file in Base, delete in branch A, keep in branch B, and merge.
        // Already we have 'hello.txt' in Base commit.
        // Create branch A (delete hello.txt)
        f1 = new File(repoDir, "hello.txt");
        if (f1.exists()) f1.delete();
        Dirstate ds = repo.getDirstate();
        ds.addEntry("hello.txt", new Dirstate.Entry('r', 0, 0, 0));
        repo.writeDirstate(ds);
        byte[] branchANode = new CommitCommand(repo).setMessage("Branch A (deleted hello.txt)").call();

        // Create branch B (keep hello.txt but do another change)
        ds = repo.getDirstate();
        ds.setParents(lastNode, new byte[20]);
        Files.writeString(f1.toPath(), "Hello\n");
        ds.addEntry("hello.txt", new Dirstate.Entry('n', 0644, 6, System.currentTimeMillis() / 1000));
        repo.writeDirstate(ds);
        
        File f2 = new File(repoDir, "other.txt");
        Files.writeString(f2.toPath(), "Other file\n");
        new AddCommand(repo).call();
        byte[] branchBNode = new CommitCommand(repo).setMessage("Branch B (keep hello, add other)").call();

        // Merge branch B into branch A (so parent1 = branch A (deleted), parent2 = branch B (kept))
        ds = repo.getDirstate();
        ds.setParents(branchANode, new byte[20]);
        if (f1.exists()) f1.delete();
        ds.removeEntry("hello.txt");
        if (f2.exists()) f2.delete();
        ds.removeEntry("other.txt");
        repo.writeDirstate(ds);

        MergeCommand.MergeResult mergeDeletedRes = new MergeCommand(repo).setNodeId(branchBNode).call();
        assertFalse(mergeDeletedRes.isConflicted());
        // Verify hello.txt is deleted (deleted in P1, kept in P2 -> deleted)
        assertFalse(f1.exists());
    }

    @Test
    public void testCrissCrossLCA(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // Base revision (0)
        File f1 = new File(repoDir, "hello.txt");
        Files.writeString(f1.toPath(), "0\n");
        new AddCommand(repo).call();
        byte[] r0 = new CommitCommand(repo).setMessage("r0").call();

        // Branch A (1) - Add a.txt
        File fa = new File(repoDir, "a.txt");
        Files.writeString(fa.toPath(), "A\n");
        new AddCommand(repo).call();
        byte[] r1 = new CommitCommand(repo).setMessage("r1").call();

        // Branch B (2) - split from r0, Add b.txt (remove a.txt to branch out)
        Dirstate ds = repo.getDirstate();
        ds.setParents(r0, new byte[20]);
        repo.writeDirstate(ds);
        if (fa.exists()) fa.delete();
        ds.removeEntry("a.txt");
        repo.writeDirstate(ds);

        File fb = new File(repoDir, "b.txt");
        Files.writeString(fb.toPath(), "B\n");
        new AddCommand(repo).call();
        byte[] r2 = new CommitCommand(repo).setMessage("r2").call();

        // Merge r1 into r2 to create Merge X (3)
        new MergeCommand(repo).setNodeId(r1).call();
        byte[] r3 = new CommitCommand(repo).setMessage("r3").call();

        // Merge r2 into r1 to create Merge Y (4)
        ds = repo.getDirstate();
        ds.setParents(r1, new byte[20]);
        repo.writeDirstate(ds);
        // clean fb and add fa to match branch r1 state before merge
        if (fb.exists()) fb.delete();
        ds.removeEntry("b.txt");
        Files.writeString(fa.toPath(), "A\n");
        ds.addEntry("a.txt", new Dirstate.Entry('n', 0644, 2, System.currentTimeMillis() / 1000));
        repo.writeDirstate(ds);

        new MergeCommand(repo).setNodeId(r2).call();
        byte[] r4 = new CommitCommand(repo).setMessage("r4").call();

        // Now we have a criss-cross structure where both X (3) and Y (4) are ancestors,
        // and r1 (1) and r2 (2) are the dual LCAs.
        // If we merge r3 (3) and r4 (4), the candidates for LCA are r1 (1) and r2 (2).
        ds = repo.getDirstate();
        ds.setParents(r4, new byte[20]);
        repo.writeDirstate(ds);

        // Run the merge command which will call findLCA internally
        MergeCommand.MergeResult res = new MergeCommand(repo).setNodeId(r3).call();
        assertFalse(res.isConflicted());
    }

    @Test
    public void testMergePreservesExecutableAndSymlink(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Base commit: just a dummy file
        File baseFile = new File(repoDir, "base.txt");
        Files.writeString(baseFile.toPath(), "Base\n");
        new AddCommand(repo).call();
        byte[] baseNode = new CommitCommand(repo).setMessage("Base commit").call();

        // 2. Yours branch: Add executable and symlink files
        File execFile = new File(repoDir, "run.sh");
        Files.writeString(execFile.toPath(), "#!/bin/sh\necho Hello\n");
        execFile.setExecutable(true, false);

        File symlinkFile = new File(repoDir, "link.txt");
        boolean hasSymlink = true;
        try {
            Files.createSymbolicLink(symlinkFile.toPath(), java.nio.file.Path.of("run.sh"));
        } catch (Exception e) {
            hasSymlink = false;
        }

        new AddCommand(repo).call();
        byte[] yoursNode = new CommitCommand(repo).setMessage("Yours branch with exec and symlink").call();

        // 3. Switch back to Base, branch Theirs: just add dummy.txt (so exec and symlink don't exist here)
        Dirstate ds = repo.getDirstate();
        ds.setParents(baseNode, new byte[20]);
        if (execFile.exists()) execFile.delete();
        if (hasSymlink && Files.isSymbolicLink(symlinkFile.toPath())) {
            Files.delete(symlinkFile.toPath());
        }
        ds.removeEntry("run.sh");
        ds.removeEntry("link.txt");
        repo.writeDirstate(ds);
        
        File dummy = new File(repoDir, "dummy.txt");
        Files.writeString(dummy.toPath(), "Theirs dummy\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Theirs branch commit").call();

        // 4. Merge Yours (yoursNode) into Theirs (theirsNode)
        // Currently, dirstate parent is Theirs (theirsNode)
        
        File mfIdx = new File(repo.getStoreDir(), "00manifest.i");
        File mfDat = new File(repo.getStoreDir(), "00manifest.d");
        Revlog mfRevlog = new Revlog(mfIdx, mfDat);
        byte[] yoursCommitContent = new Revlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d")).getRevisionContent(1);
        String yoursClText = new String(yoursCommitContent, java.nio.charset.StandardCharsets.UTF_8);
        String yoursMfLine = yoursClText.split("\n")[0].trim();
        byte[] yoursMfNode = com.github.search5.hg4j.core.NodeIdUtil.fromHex(yoursMfLine);
        int yoursMfRev = com.github.search5.hg4j.core.NodeIdUtil.findRevisionByNodeId(mfRevlog, yoursMfNode);
        byte[] yoursMfContent = mfRevlog.getRevisionContent(yoursMfRev);
        String yoursMfText = new String(yoursMfContent, java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("YOURS MANIFEST LOG: " + yoursMfText);

        MergeCommand.MergeResult res = new MergeCommand(repo).setNodeId(yoursNode).call();
        assertFalse(res.isConflicted());

        // Verify that run.sh has been restored on disk and is executable!
        assertTrue(execFile.exists());
        assertTrue(execFile.canExecute());

        // Verify symlink is restored as a symbolic link (if supported)
        if (hasSymlink) {
            assertTrue(Files.isSymbolicLink(symlinkFile.toPath()));
            java.nio.file.Path target = Files.readSymbolicLink(symlinkFile.toPath());
            assertEquals("run.sh", target.toString());
        }
    }

    private boolean isAllZero(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) return false;
        }
        return true;
    }

    @Test
    public void testMergeCommandSetNodeIdNodeIdType(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        MergeCommand cmd = new MergeCommand(repo);
        
        byte[] dummyBytes = new byte[20];
        com.github.search5.hg4j.lib.NodeId nodeId = new com.github.search5.hg4j.lib.NodeId(dummyBytes);
        
        // setNodeId(NodeId) 테스트
        cmd.setNodeId(nodeId);
        // setNodeId(null) 테스트
        cmd.setNodeId((com.github.search5.hg4j.lib.NodeId) null);
    }
}
