package io.github.search5.hg4j.api;

import io.github.search5.hg4j.obsolete.HgObsMarker;
import io.github.search5.hg4j.obsolete.HgObsolescenceParser;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class HgAmendTest {

    @TempDir
    File tempDir;

    @Test
    public void testAmendTipCommitAndObsstoreIntegration() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        
        try (Hg hg = Hg.wrap(repo)) {
            File file = new File(tempDir, "a.txt");
            
            // 1. Initial Commit (Revision 0)
            Files.writeString(file.toPath(), "Original file text");
            hg.add().addFile("a.txt").call();
            byte[] obsoleteNode = hg.commit().setAuthor("Developer").setMessage("Original message").call();

            // 2. Amend target commit
            Files.writeString(file.toPath(), "Amended file text");
            hg.add().addFile("a.txt").call();
            
            byte[] amendedNode = hg.amend()
                    .setAuthor("Amender <amend@example.com>")
                    .setMessage("Amended message")
                    .call();

            assertNotNull(amendedNode);
            assertNotEquals(obsoleteNode, amendedNode);

            // 3. Verify Obsolescence Marker is generated and saved correctly in obsstore
            File obsstoreFile = new File(repo.getStoreDir(), "obsstore");
            assertTrue(obsstoreFile.exists());

            byte[] obsBytes = Files.readAllBytes(obsstoreFile.toPath());
            List<HgObsMarker> markers = HgObsolescenceParser.parse(obsBytes);

            assertNotNull(markers);
            assertEquals(1, markers.size());
            
            HgObsMarker marker = markers.get(0);
            assertArrayEquals(obsoleteNode, marker.getPredecessor());
            assertEquals(1, marker.getSuccessors().size());
            assertArrayEquals(amendedNode, marker.getSuccessors().get(0));
            assertEquals("amend", marker.getMetadata().get("operation"));
        }
    }

    @Test
    public void testConstructorRejectsNullRepository() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new AmendCommand(null));
        assertEquals("Repository cannot be null", ex.getMessage());
    }

    @Test
    public void testAmendOnEmptyRepositoryThrows() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();

        try (Hg hg = Hg.wrap(repo)) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> hg.amend().call());
            assertEquals("No commits exist to amend (empty repository)", ex.getMessage());
        }
    }

    @Test
    public void testAmendWithoutExplicitMessagePropagatesCommitMessageRequirement() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();

        try (Hg hg = Hg.wrap(repo)) {
            File file = new File(tempDir, "b.txt");

            Files.writeString(file.toPath(), "Original file text");
            hg.add().addFile("b.txt").call();
            hg.commit().setAuthor("Developer").setMessage("Original message").call();

            Files.writeString(file.toPath(), "Second revision text");
            hg.add().addFile("b.txt").call();

            // Amend without calling setMessage(...) exercises AmendCommand's message == null
            // branch (the message is not forwarded to the underlying CommitCommand, which then
            // enforces its own "message must be specified" requirement).
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> hg.amend().setAuthor("Amender <amend@example.com>").call());
            assertEquals("Commit message must be specified.", ex.getMessage());
        }
    }
}
