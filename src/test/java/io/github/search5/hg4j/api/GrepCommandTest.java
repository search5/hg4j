package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class GrepCommandTest {

    @Test
    public void testGrepHistory(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // Commit first revision with target string
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Hello World\nFindMeSpecialPattern\nGoodBye");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("First Commit").call();

        // Execute grep search
        List<GrepCommand.GrepResult> results = new GrepCommand(repo)
            .setQuery("SpecialPattern")
            .call();

        assertEquals(1, results.size());
        assertEquals("a.txt", results.get(0).path);
        assertEquals(2, results.get(0).lineNumber);
        assertEquals("FindMeSpecialPattern", results.get(0).lineContent);
    }
}
