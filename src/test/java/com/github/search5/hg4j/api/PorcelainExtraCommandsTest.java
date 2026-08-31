package com.github.search5.hg4j.api;

import com.github.search5.hg4j.core.HgRepository;
import com.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class PorcelainExtraCommandsTest {

    @Test
    public void testExtraPorcelainCommandsRoundtrip(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Commit A
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Content A");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit A").setAuthor("dev").call();

        byte[] nodeA = repo.getDirstate().getParent1();
        String hexA = NodeIdUtil.toHex(nodeA);

        // 2. Commit B
        File f2 = new File(repoDir, "b.txt");
        Files.writeString(f2.toPath(), "Content B");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit B").setAuthor("dev").call();

        byte[] nodeB = repo.getDirstate().getParent1();
        String hexB = NodeIdUtil.toHex(nodeB);

        // 3. Test Identify & Heads
        String identity = new IdentifyCommand(repo).call();
        assertTrue(identity.contains("default"));
        assertTrue(identity.contains(hexB.substring(0, 12)));

        List<String> heads = new HeadsCommand(repo).call();
        assertEquals(1, heads.size());
        assertEquals(hexB, heads.get(0));

        // 4. Test Purge (clean)
        File untracked = new File(repoDir, "untracked.txt");
        Files.writeString(untracked.toPath(), "Untracked Content");
        assertTrue(untracked.exists());

        new PurgeCommand(repo).setPurgeDirectories(true).call();
        assertFalse(untracked.exists(), "Purge should delete untracked files");

        // 5. Test Phase Roots
        int ph = new PhaseCommand(repo).setRevision("tip").call();
        assertEquals(0, ph, "Default phase should be public (0)");

        int setPh = new PhaseCommand(repo).setRevision("tip").setPhase(1).call();
        assertEquals(1, setPh);
        assertEquals(1, new PhaseCommand(repo).setRevision("tip").call());

        // 6. Test Revset expression
        List<String> revsAll = new RevsetCommand(repo).setExpression("all()").call();
        assertEquals(2, revsAll.size());
        assertEquals(hexA, revsAll.get(0));
        assertEquals(hexB, revsAll.get(1));

        List<String> revsParents = new RevsetCommand(repo).setExpression("parents(tip)").call();
        assertEquals(1, revsParents.size());
        assertEquals(hexA, revsParents.get(0));

        // heads() 테스트
        List<String> revsHeads = new RevsetCommand(repo).setExpression("heads()").call();
        assertEquals(1, revsHeads.size());
        assertEquals(hexB, revsHeads.get(0));

        // ancestors(tip) 테스트
        List<String> revsAncestors = new RevsetCommand(repo).setExpression("ancestors(tip)").call();
        assertEquals(2, revsAncestors.size()); // tip(hexB)과 p1(hexA) 모두 포함

        // descendants(0) 테스트
        List<String> revsDescendants = new RevsetCommand(repo).setExpression("descendants(0)").call();
        assertEquals(2, revsDescendants.size()); // 0(hexA)의 후손은 0, 1(hexB)

        // logical NOT (!) 연산자 테스트
        List<String> revsNotHeads = new RevsetCommand(repo).setExpression("!heads()").call();
        assertEquals(1, revsNotHeads.size());
        assertEquals(hexA, revsNotHeads.get(0)); // heads()가 아닌 것은 hexA뿐

        // bookmark() 및 tag() 테스트
        new BookmarkCommand(repo).setBookmarkName("bookA").setNodeId(nodeA).call();
        List<String> revsBkmk = new RevsetCommand(repo).setExpression("bookmark(bookA)").call();
        assertEquals(1, revsBkmk.size());
        assertEquals(hexA, revsBkmk.get(0));

        // 7. Test Archive SNAPSHOT ZIP
        File zipFile = new File(repoDir, "archive.zip");
        new ArchiveCommand(repo).setRevision("tip").setDestination(zipFile).call();
        assertTrue(zipFile.exists() && zipFile.length() > 0);

        // 8. Test Gc optimization
        String gcReport = new GcCommand(repo).call();
        assertTrue(gcReport.contains("GC / Compaction complete"));

        // 9. Test Subrepo (hgsub) config
        new SubrepoCommand(repo)
            .setAction("add")
            .setSubrepoPath("libs/sub")
            .setSubrepoUrl("https://github.com/org/sub")
            .call();
        assertTrue(new File(repoDir, ".hgsub").exists());
        assertTrue(new File(repoDir, ".hgsubstate").exists());

        // 10. Test Strip Rollback (rollback to commit A)
        new StripCommand(repo).setRevision(hexB).call();
        assertEquals(1, repo.getRevlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d")).getRevisionCount());
        assertArrayEquals(nodeA, repo.getDirstate().getParent1());

        // 11. Test Describe tag tracing
        File hgtags = new File(repoDir, ".hgtags");
        Files.writeString(hgtags.toPath(), hexA + " v1.0\n");
        String desc = new DescribeCommand(repo).call();
        assertTrue(desc.startsWith("v1.0"));
    }
}
