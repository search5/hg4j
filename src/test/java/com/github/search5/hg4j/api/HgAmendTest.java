package com.github.search5.hg4j.api;

import com.github.search5.hg4j.obsolete.HgObsMarker;
import com.github.search5.hg4j.obsolete.HgObsolescenceParser;
import com.github.search5.hg4j.lib.HgRepository;
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
}
