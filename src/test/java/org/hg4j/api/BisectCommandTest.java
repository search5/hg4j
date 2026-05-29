package org.hg4j.api;

import org.hg4j.core.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

public class BisectCommandTest {

    @Test
    public void testBisectFlow(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Good state commit (Rev 0)
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Good state");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit 1").call();
        byte[] goodNode = repo.getDirstate().getParent1();

        // 2. Intermediate state commit (Rev 1)
        File f2 = new File(repoDir, "a.txt");
        Files.writeString(f2.toPath(), "Intermediate state");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit 2").call();
        byte[] midNode = repo.getDirstate().getParent1();

        // 3. Bad state commit (Rev 2)
        File f3 = new File(repoDir, "a.txt");
        Files.writeString(f3.toPath(), "Bad state");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit 3").call();
        byte[] badNode = repo.getDirstate().getParent1();

        // 4. Bisect query
        BisectCommand bisect = new BisectCommand(repo)
            .setGood(goodNode)
            .setBad(badNode);
        
        byte[] candidate = bisect.next();
        assertArrayEquals(midNode, candidate); // index 1 is mid between 0 and 2
    }
}
