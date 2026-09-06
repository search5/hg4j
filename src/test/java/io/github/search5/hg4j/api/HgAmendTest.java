package io.github.search5.hg4j.api;

import io.github.search5.hg4j.obsolete.HgObsMarker;
import io.github.search5.hg4j.obsolete.HgObsolescenceParser;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import io.github.search5.hg4j.storage.Revlog;
import java.nio.charset.StandardCharsets;

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
    public void testAmendWithoutExplicitMessageOrAuthorReusesOriginalCommitValues() throws Exception {
        // Verified against real hg 7.2.2: `hg commit --amend` with neither `-m` nor `-u` reuses
        // the amended-away commit's own message and user unchanged (2026-09-04).
        HgRepository repo = Hg.init().setDirectory(tempDir).call();

        try (Hg hg = Hg.wrap(repo)) {
            File file = new File(tempDir, "b.txt");

            Files.writeString(file.toPath(), "Original file text");
            hg.add().addFile("b.txt").call();
            hg.commit().setAuthor("Developer").setMessage("Original message").call();

            Files.writeString(file.toPath(), "Second revision text");
            hg.add().addFile("b.txt").call();

            // Amend without calling setAuthor(...)/setMessage(...) must reuse "Developer" /
            // "Original message" from the commit being amended, not throw and not fall back to
            // CommitCommand's own generic default author.
            byte[] amendedNode = hg.amend().call();
            assertNotNull(amendedNode);

            Revlog changelog = new Revlog(
                    new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"));
            byte[] content = changelog.getRevisionContent(changelog.getRevisionCount() - 1);
            String text = new String(content, StandardCharsets.UTF_8);
            String[] lines = text.split("\n", -1);
            assertEquals("Developer", lines[1], "author must be reused from the amended-away commit");
            assertTrue(text.endsWith("Original message"), "message must be reused from the amended-away commit: " + text);
        }
    }
}
