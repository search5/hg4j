package io.github.search5.hg4j.phase;
import io.github.search5.hg4j.storage.Revlog;

import io.github.search5.hg4j.lib.NodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PhaseRoots — Unit tests for Phase metadata control")
public class PhaseRootsTest {

    @TempDir
    Path tempDir;

    private File createTempFile(String content) throws IOException {
        File file = tempDir.resolve("phaseroots").toFile();
        if (content != null) {
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
        }
        return file;
    }

    // Map-based helper function for parent lookup
    private Function<NodeId, NodeId[]> createParentLookup(Map<NodeId, NodeId[]> parentMap) {
        return node -> parentMap.getOrDefault(node, new NodeId[0]);
    }

    @Test
    @DisplayName("All nodes are PUBLIC by default if the file does not exist")
    void testGetPhase_noFile() throws IOException {
        File file = new File(tempDir.toFile(), "non_existent_phaseroots");
        PhaseRoots phaseRoots = new PhaseRoots(file);

        NodeId node = NodeId.fromHex("a".repeat(40));
        assertEquals(PhaseRoots.Phase.PUBLIC, phaseRoots.getPhase(node, n -> new NodeId[0]));
    }

    @Test
    @DisplayName("Verify parsing of a valid phaseroots file and directly specified node phases")
    void testGetPhase_directRoots() throws IOException {
        String hexDraft = "1".repeat(40);
        String hexSecret = "2".repeat(40);

        String fileContent = "1 " + hexDraft + "\n" + "2 " + hexSecret + "\n";
        File file = createTempFile(fileContent);

        PhaseRoots phaseRoots = new PhaseRoots(file);

        assertEquals(PhaseRoots.Phase.DRAFT, phaseRoots.getPhase(NodeId.fromHex(hexDraft), n -> new NodeId[0]));
        assertEquals(PhaseRoots.Phase.SECRET, phaseRoots.getPhase(NodeId.fromHex(hexSecret), n -> new NodeId[0]));
        assertEquals(PhaseRoots.Phase.PUBLIC, phaseRoots.getPhase(NodeId.fromHex("0".repeat(40)), n -> new NodeId[0]));
    }

    @Test
    @DisplayName("Verify that the phase of an ancestor is correctly propagated to its children in the hierarchy")
    void testGetPhase_inheritance() throws IOException {
        NodeId nRoot = NodeId.fromHex("0".repeat(40));     // Public Root
        NodeId nDraftRoot = NodeId.fromHex("1".repeat(40)); // Draft Root
        NodeId nDraftChild = NodeId.fromHex("a".repeat(40)); // Child of Draft Root
        NodeId nSecretRoot = NodeId.fromHex("2".repeat(40)); // Secret Root
        NodeId nSecretChild = NodeId.fromHex("b".repeat(40)); // Child of Secret Root
        NodeId nMergeChild = NodeId.fromHex("c".repeat(40)); // Merge Child (Draft Parent + Secret Parent)

        // Define parent relationships
        Map<NodeId, NodeId[]> parents = new HashMap<>();
        parents.put(nDraftChild, new NodeId[]{nDraftRoot});
        parents.put(nSecretChild, new NodeId[]{nSecretRoot});
        parents.put(nMergeChild, new NodeId[]{nDraftChild, nSecretChild});

        String fileContent = "1 " + nDraftRoot.toHex() + "\n" + "2 " + nSecretRoot.toHex() + "\n";
        File file = createTempFile(fileContent);

        PhaseRoots phaseRoots = new PhaseRoots(file);
        Function<NodeId, NodeId[]> lookup = createParentLookup(parents);

        // 1. A node with no ancestors is PUBLIC
        assertEquals(PhaseRoots.Phase.PUBLIC, phaseRoots.getPhase(nRoot, lookup));

        // 2. Descendants of a Draft Root inherit DRAFT
        assertEquals(PhaseRoots.Phase.DRAFT, phaseRoots.getPhase(nDraftChild, lookup));

        // 3. Descendants of a Secret Root inherit SECRET
        assertEquals(PhaseRoots.Phase.SECRET, phaseRoots.getPhase(nSecretChild, lookup));

        // 4. A child with both Draft and Secret parents inherits the more restrictive SECRET phase
        assertEquals(PhaseRoots.Phase.SECRET, phaseRoots.getPhase(nMergeChild, lookup));
    }

    @Test
    @DisplayName("Verify memory update and file serialization when Phase is changed via setPhase")
    void testSetPhase_andSerialization() throws IOException {
        File file = createTempFile("");
        PhaseRoots phaseRoots = new PhaseRoots(file);

        NodeId n1 = NodeId.fromHex("1".repeat(40));
        NodeId n2 = NodeId.fromHex("2".repeat(40));

        // Initial state is PUBLIC
        assertEquals(PhaseRoots.Phase.PUBLIC, phaseRoots.getPhase(n1, n -> new NodeId[0]));

        // Update Phase
        phaseRoots.setPhase(n1, PhaseRoots.Phase.DRAFT, n -> new NodeId[0]);
        phaseRoots.setPhase(n2, PhaseRoots.Phase.SECRET, n -> new NodeId[0]);

        assertEquals(PhaseRoots.Phase.DRAFT, phaseRoots.getPhase(n1, n -> new NodeId[0]));
        assertEquals(PhaseRoots.Phase.SECRET, phaseRoots.getPhase(n2, n -> new NodeId[0]));

        // Reload with a new instance to verify file write synchronization
        PhaseRoots reloaded = new PhaseRoots(file);
        assertEquals(PhaseRoots.Phase.DRAFT, reloaded.getPhase(n1, n -> new NodeId[0]));
        assertEquals(PhaseRoots.Phase.SECRET, reloaded.getPhase(n2, n -> new NodeId[0]));

        // Verify that the data is saved in the correct string format in the file's text content
        String savedContent = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertTrue(savedContent.contains("1 " + n1.toHex()));
        assertTrue(savedContent.contains("2 " + n2.toHex()));
    }

    @Test
    @DisplayName("Verify getPhase, setPhase, and isPublic/isDraft/isSecret using a Revlog instance")
    void testRevlogOverloads() throws Exception {
        File clIdx = tempDir.resolve("00changelog.i").toFile();
        File clDat = tempDir.resolve("00changelog.d").toFile();
        
        Files.write(clIdx.toPath(), new byte[0]);
        Files.write(clDat.toPath(), new byte[0]);
        
        Revlog changelog = new Revlog(clIdx, clDat);
        
        File file = createTempFile("");
        PhaseRoots phaseRoots = new PhaseRoots(file);
        
        NodeId n1 = NodeId.fromHex("3".repeat(40));
        
        // 1. Verify getPhase(Revlog)
        assertEquals(PhaseRoots.Phase.PUBLIC, phaseRoots.getPhase(n1, changelog));
        assertTrue(phaseRoots.isPublic(n1, changelog));
        assertFalse(phaseRoots.isDraft(n1, changelog));
        assertFalse(phaseRoots.isSecret(n1, changelog));
        
        // 2. Verify setPhase(Revlog)
        phaseRoots.setPhase(n1, PhaseRoots.Phase.DRAFT, changelog);
        assertEquals(PhaseRoots.Phase.DRAFT, phaseRoots.getPhase(n1, changelog));
        assertFalse(phaseRoots.isPublic(n1, changelog));
        assertTrue(phaseRoots.isDraft(n1, changelog));
        assertFalse(phaseRoots.isSecret(n1, changelog));
        
        // Verify null / empty node
        assertEquals(PhaseRoots.Phase.PUBLIC, phaseRoots.getPhase((NodeId) null, n -> new NodeId[0]));
        phaseRoots.setPhase(null, PhaseRoots.Phase.SECRET, n -> new NodeId[0]);
        
        // Verify helper methods (Function)
        assertTrue(phaseRoots.isDraft(n1, n -> new NodeId[0]));
        assertFalse(phaseRoots.isPublic(n1, n -> new NodeId[0]));
        assertFalse(phaseRoots.isSecret(n1, n -> new NodeId[0]));
        
        phaseRoots.setPhase(n1, PhaseRoots.Phase.SECRET, n -> new NodeId[0]);
        assertTrue(phaseRoots.isSecret(n1, n -> new NodeId[0]));
    }
}
