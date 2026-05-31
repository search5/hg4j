package io.github.search5.hg4j.api;

import io.github.search5.hg4j.core.HgRepository;
import io.github.search5.hg4j.core.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

public class HisteditCommandTest {

    @Test
    public void testHisteditFlow(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // Create base commit
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Content A");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit A").setAuthor("dev").call();

        String hexA = NodeIdUtil.toHex(repo.getDirstate().getParent1());

        // Create second commit to rewrite
        File f2 = new File(repoDir, "b.txt");
        Files.writeString(f2.toPath(), "Content B");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit B").setAuthor("dev").call();

        String hexB = NodeIdUtil.toHex(repo.getDirstate().getParent1());

        // Execute histedit PICK on A and B
        new HisteditCommand(repo)
            .addRule(HisteditCommand.Action.PICK, hexA)
            .addRule(HisteditCommand.Action.PICK, hexB)
            .call();

        String hexAfter = NodeIdUtil.toHex(repo.getDirstate().getParent1());
        assertNotNull(hexAfter);
        assertNotEquals(hexB, hexAfter); // rewritten history yields different node hash
    }
}
