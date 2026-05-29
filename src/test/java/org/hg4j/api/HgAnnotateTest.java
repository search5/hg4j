package org.hg4j.api;

import org.hg4j.core.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class HgAnnotateTest {

    @TempDir
    File tempDir;

    @Test
    public void testFileBlameAnnotateScenario() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        
        try (Hg hg = Hg.wrap(repo)) {
            File file = new File(tempDir, "blame.txt");
            
            // Revision 0: written by Alice
            Files.writeString(file.toPath(), "Alice Line 1\nAlice Line 2\n");
            hg.add().addFile("blame.txt").call();
            byte[] node0 = hg.commit().setAuthor("Alice <alice@example.com>").setMessage("alice commit").call();

            // Execute blame (annotate)
            List<AnnotateCommand.BlameLine> lines = hg.annotate()
                    .setPath("blame.txt")
                    .call();

            assertNotNull(lines);
            assertEquals(3, lines.size()); // 2 lines + trailing empty line from split("\n", -1)
            
            assertEquals(1, lines.get(0).getLineNumber());
            assertEquals(0, lines.get(0).getRevision());
            assertEquals("Alice <alice@example.com>", lines.get(0).getAuthor());
            assertEquals("Alice Line 1", lines.get(0).getContent());
            
            assertEquals(2, lines.get(1).getLineNumber());
            assertEquals(0, lines.get(1).getRevision());
            assertEquals("Alice <alice@example.com>", lines.get(1).getAuthor());
            assertEquals("Alice Line 2", lines.get(1).getContent());
        }
    }
}
