package org.hg4j.api;

import org.hg4j.core.HgRepository;
import org.hg4j.core.NodeIdUtil;
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
