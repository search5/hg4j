package com.github.search5.hg4j.api;

import com.github.search5.hg4j.core.HgRepository;
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

    @Test
    public void testComplexMultiAuthorBlame() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        
        try (Hg hg = Hg.wrap(repo)) {
            File file = new File(tempDir, "blame.txt");
            
            // Revision 0: Alice writes Line 1 and Line 2
            Files.writeString(file.toPath(), "Line 1\nLine 2\n");
            hg.add().addFile("blame.txt").call();
            hg.commit().setAuthor("Alice <alice@example.com>").setMessage("Alice init").call();

            // Revision 1: Bob deletes Line 1, inserts Line 1.5, retains Line 2
            Files.writeString(file.toPath(), "Line 1.5\nLine 2\n");
            hg.commit().setAuthor("Bob <bob@example.com>").setMessage("Bob update").call();

            // Revision 2: Charlie inserts Line 3 between Line 1.5 and Line 2
            Files.writeString(file.toPath(), "Line 1.5\nLine 3\nLine 2\n");
            hg.commit().setAuthor("Charlie <charlie@example.com>").setMessage("Charlie update").call();

            // Run annotate/blame
            List<AnnotateCommand.BlameLine> lines = hg.annotate()
                    .setPath("blame.txt")
                    .call();

            assertNotNull(lines);
            // 3 non-empty lines + 1 empty line at the end
            assertEquals(4, lines.size());
            
            // Line 1.5 (should be Bob, revision 1)
            assertEquals("Line 1.5", lines.get(0).getContent());
            assertEquals(1, lines.get(0).getRevision());
            assertEquals("Bob <bob@example.com>", lines.get(0).getAuthor());

            // Line 3 (should be Charlie, revision 2)
            assertEquals("Line 3", lines.get(1).getContent());
            assertEquals(2, lines.get(1).getRevision());
            assertEquals("Charlie <charlie@example.com>", lines.get(1).getAuthor());

            // Line 2 (should be Alice, revision 0)
            assertEquals("Line 2", lines.get(2).getContent());
            assertEquals(0, lines.get(2).getRevision());
            assertEquals("Alice <alice@example.com>", lines.get(2).getAuthor());
        }
    }
}
