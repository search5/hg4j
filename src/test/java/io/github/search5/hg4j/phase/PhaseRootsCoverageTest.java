package io.github.search5.hg4j.phase;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.storage.Revlog;
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
import org.junit.jupiter.api.Assumptions;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage-focused tests for {@link PhaseRoots}: malformed persisted-file recovery,
 * BFS ancestor-traversal edge cases, round-trip write/read behavior and real-changelog-backed
 * lookups. See PhaseRootsTest for the original baseline behavioral tests.
 */
@DisplayName("PhaseRoots — Coverage gap tests")
public class PhaseRootsCoverageTest {

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

    // ------------------------------------------------------------------
    // Phase.fromValue
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Phase.fromValue defaults to PUBLIC for an unrecognized numeric value")
    void testFromValue_unknownValueDefaultsToPublic() {
        assertEquals(PhaseRoots.Phase.PUBLIC, PhaseRoots.Phase.fromValue(99));
        assertEquals(PhaseRoots.Phase.PUBLIC, PhaseRoots.Phase.fromValue(-1));
    }

    // ------------------------------------------------------------------
    // load(): malformed / noisy file recovery
    // ------------------------------------------------------------------

    @Test
    @DisplayName("load() skips blank lines and comment lines while parsing valid entries")
    void testLoad_skipsBlankAndCommentLines() throws IOException {
        String hexDraft = "3".repeat(40);
        String content = "\n" +
                "   \n" +
                "# a comment line\n" +
                "1 " + hexDraft + "\n";
        File file = createTempFile(content);

        PhaseRoots phaseRoots = new PhaseRoots(file);
        assertEquals(PhaseRoots.Phase.DRAFT,
                phaseRoots.getPhase(NodeId.fromHex(hexDraft), n -> new NodeId[0]));
    }

    @Test
    @DisplayName("load() ignores lines with fewer than two whitespace-separated tokens")
    void testLoad_skipsLineWithTooFewParts() throws IOException {
        String hexDraft = "4".repeat(40);
        String content = "justonetoken\n" + "1 " + hexDraft + "\n";
        File file = createTempFile(content);

        PhaseRoots phaseRoots = new PhaseRoots(file);
        assertEquals(PhaseRoots.Phase.DRAFT,
                phaseRoots.getPhase(NodeId.fromHex(hexDraft), n -> new NodeId[0]));
    }

    @Test
    @DisplayName("load() recovers from a non-numeric phase token instead of throwing")
    void testLoad_skipsNonNumericPhaseToken() throws IOException {
        String hexDraft = "5".repeat(40);
        String content = "notanumber " + hexDraft + "\n" + "1 " + hexDraft + "\n";
        File file = createTempFile(content);

        PhaseRoots phaseRoots = new PhaseRoots(file);
        // The malformed line is skipped; the later valid line for the same node still applies.
        assertEquals(PhaseRoots.Phase.DRAFT,
                phaseRoots.getPhase(NodeId.fromHex(hexDraft), n -> new NodeId[0]));
    }

    @Test
    @DisplayName("load() recovers from an invalid (non-hex / wrong-length) node token instead of throwing")
    void testLoad_skipsInvalidHexToken() throws IOException {
        String hexDraft = "6".repeat(40);
        String content = "1 nothex\n" + "2 " + hexDraft + "\n";
        File file = createTempFile(content);

        PhaseRoots phaseRoots = new PhaseRoots(file);
        assertEquals(PhaseRoots.Phase.SECRET,
                phaseRoots.getPhase(NodeId.fromHex(hexDraft), n -> new NodeId[0]));
    }

    // ------------------------------------------------------------------
    // getPhase(): BFS ancestor-traversal edge cases
    // ------------------------------------------------------------------

    @Test
    @DisplayName("BFS max-phase reduction is order-independent: a later, less-restrictive root does not downgrade an already-found higher phase")
    void testGetPhase_mergeChild_orderIndependentMax() throws IOException {
        NodeId nDraftRoot = NodeId.fromHex("1".repeat(40));
        NodeId nSecretRoot = NodeId.fromHex("2".repeat(40));
        NodeId nDraftChild = NodeId.fromHex("a".repeat(40));
        NodeId nSecretChild = NodeId.fromHex("b".repeat(40));
        // Note: parents deliberately listed SECRET-branch first, DRAFT-branch second — the
        // reverse order of PhaseRootsTest#testGetPhase_inheritance — to force the BFS to
        // encounter the higher (SECRET) phase before the lower (DRAFT) one.
        NodeId nMergeChild = NodeId.fromHex("c".repeat(40));

        Map<NodeId, NodeId[]> parents = new HashMap<>();
        parents.put(nDraftChild, new NodeId[]{nDraftRoot});
        parents.put(nSecretChild, new NodeId[]{nSecretRoot});
        parents.put(nMergeChild, new NodeId[]{nSecretChild, nDraftChild});

        String fileContent = "1 " + nDraftRoot.toHex() + "\n" + "2 " + nSecretRoot.toHex() + "\n";
        File file = createTempFile(fileContent);
        PhaseRoots phaseRoots = new PhaseRoots(file);
        Function<NodeId, NodeId[]> lookup = node -> parents.getOrDefault(node, new NodeId[0]);

        assertEquals(PhaseRoots.Phase.SECRET, phaseRoots.getPhase(nMergeChild, lookup));
    }

    @Test
    @DisplayName("getPhase() tolerates a parentLookup that returns null instead of an empty array")
    void testGetPhase_parentLookupReturnsNull() throws IOException {
        File file = createTempFile("");
        PhaseRoots phaseRoots = new PhaseRoots(file);

        NodeId node = NodeId.fromHex("7".repeat(40));
        assertEquals(PhaseRoots.Phase.PUBLIC, phaseRoots.getPhase(node, n -> null));
    }

    @Test
    @DisplayName("getPhase() ignores null and null-node (all-zero) entries within a parents array")
    void testGetPhase_parentArrayWithNullAndNullNodeEntries() throws IOException {
        NodeId draftRoot = NodeId.fromHex("1".repeat(40));
        NodeId child = NodeId.fromHex("8".repeat(40));

        Map<NodeId, NodeId[]> parents = new HashMap<>();
        // A parents array containing a literal null element and the well-known null NodeId,
        // alongside the real draft-root ancestor.
        parents.put(child, new NodeId[]{null, NodeId.NULL, draftRoot});

        String fileContent = "1 " + draftRoot.toHex() + "\n";
        File file = createTempFile(fileContent);
        PhaseRoots phaseRoots = new PhaseRoots(file);
        Function<NodeId, NodeId[]> lookup = node -> parents.getOrDefault(node, new NodeId[0]);

        assertEquals(PhaseRoots.Phase.DRAFT, phaseRoots.getPhase(child, lookup));
    }

    @Test
    @DisplayName("getPhase() BFS visited-set prevents reprocessing a common ancestor reached via two paths (diamond)")
    void testGetPhase_diamondAncestry_visitedPreventsReprocessing() throws IOException {
        NodeId root = NodeId.fromHex("9".repeat(40));
        NodeId left = NodeId.fromHex("a1".repeat(20));
        NodeId right = NodeId.fromHex("a2".repeat(20));
        NodeId mergeNode = NodeId.fromHex("a3".repeat(20));

        Map<NodeId, NodeId[]> parents = new HashMap<>();
        parents.put(left, new NodeId[]{root});
        parents.put(right, new NodeId[]{root});
        parents.put(mergeNode, new NodeId[]{left, right});

        String fileContent = "1 " + root.toHex() + "\n";
        File file = createTempFile(fileContent);
        PhaseRoots phaseRoots = new PhaseRoots(file);
        Function<NodeId, NodeId[]> lookup = node -> parents.getOrDefault(node, new NodeId[0]);

        // Reaches "root" twice (via left and via right); the visited set must prevent the
        // second path from re-queueing it, but the resulting phase is unaffected either way.
        assertEquals(PhaseRoots.Phase.DRAFT, phaseRoots.getPhase(mergeNode, lookup));
    }

    // ------------------------------------------------------------------
    // setPhase(): null-node / isNull-node / revert-to-public / save() filtering
    // ------------------------------------------------------------------

    @Test
    @DisplayName("setPhase() is a no-op for the well-known null (all-zero) NodeId, distinct from a Java null reference")
    void testSetPhase_nullNodeIdIsNoOp() throws IOException {
        File file = createTempFile("");
        PhaseRoots phaseRoots = new PhaseRoots(file);

        phaseRoots.setPhase(NodeId.NULL, PhaseRoots.Phase.SECRET, n -> new NodeId[0]);

        assertEquals(PhaseRoots.Phase.PUBLIC, phaseRoots.getPhase(NodeId.NULL, n -> new NodeId[0]));
        assertFalse(file.exists() && Files.size(file.toPath()) > 0);
    }

    @Test
    @DisplayName("setPhase() back to PUBLIC removes the node's root entry from memory and file")
    void testSetPhase_revertToPublicRemovesEntry() throws IOException {
        File file = createTempFile("");
        PhaseRoots phaseRoots = new PhaseRoots(file);
        NodeId node = NodeId.fromHex("d".repeat(40));

        phaseRoots.setPhase(node, PhaseRoots.Phase.DRAFT, n -> new NodeId[0]);
        assertEquals(PhaseRoots.Phase.DRAFT, phaseRoots.getPhase(node, n -> new NodeId[0]));

        phaseRoots.setPhase(node, PhaseRoots.Phase.PUBLIC, n -> new NodeId[0]);
        assertEquals(PhaseRoots.Phase.PUBLIC, phaseRoots.getPhase(node, n -> new NodeId[0]));

        String savedContent = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertFalse(savedContent.contains(node.toHex()));

        // Reload to confirm the removal was actually persisted, not just held in memory.
        PhaseRoots reloaded = new PhaseRoots(file);
        assertEquals(PhaseRoots.Phase.PUBLIC, reloaded.getPhase(node, n -> new NodeId[0]));
    }

    @Test
    @DisplayName("save() omits an explicit PUBLIC entry loaded from a hand-edited file when rewriting")
    void testSave_omitsExplicitPublicEntryFromLoadedFile() throws IOException {
        NodeId explicitPublicNode = NodeId.fromHex("e".repeat(40));
        String content = "0 " + explicitPublicNode.toHex() + "\n";
        File file = createTempFile(content);

        PhaseRoots phaseRoots = new PhaseRoots(file);
        // Sanity: the explicit "0 <node>" line was parsed into the in-memory map.
        assertEquals(PhaseRoots.Phase.PUBLIC,
                phaseRoots.getPhase(explicitPublicNode, n -> new NodeId[0]));

        // Trigger a save() by mutating a different node; the explicit PUBLIC entry must be
        // filtered out of the rewritten file content (phaseroots never lists public roots).
        NodeId other = NodeId.fromHex("f".repeat(40));
        phaseRoots.setPhase(other, PhaseRoots.Phase.DRAFT, n -> new NodeId[0]);

        String savedContent = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        assertFalse(savedContent.contains(explicitPublicNode.toHex()));
        assertTrue(savedContent.contains(other.toHex()));
    }

    // ------------------------------------------------------------------
    // is*(Function) overloads: true/false coverage gaps left by PhaseRootsTest
    // ------------------------------------------------------------------

    @Test
    @DisplayName("isPublic(Function) returns true for a node with no recorded phase boundary")
    void testIsPublic_function_trueCase() {
        NodeId node = NodeId.fromHex("11".repeat(20));
        PhaseRoots phaseRoots = assertDoesNotThrow(() -> new PhaseRoots(new File(tempDir.toFile(), "phaseroots")));
        assertTrue(phaseRoots.isPublic(node, n -> new NodeId[0]));
    }

    @Test
    @DisplayName("isDraft(Function) returns false for a node whose ancestry is entirely public")
    void testIsDraft_function_falseCase() {
        NodeId node = NodeId.fromHex("12".repeat(20));
        PhaseRoots phaseRoots = assertDoesNotThrow(() -> new PhaseRoots(new File(tempDir.toFile(), "phaseroots")));
        assertFalse(phaseRoots.isDraft(node, n -> new NodeId[0]));
    }

    @Test
    @DisplayName("isSecret(Function) returns false for a node whose ancestry is entirely public")
    void testIsSecret_function_falseCase() {
        NodeId node = NodeId.fromHex("13".repeat(20));
        PhaseRoots phaseRoots = assertDoesNotThrow(() -> new PhaseRoots(new File(tempDir.toFile(), "phaseroots")));
        assertFalse(phaseRoots.isSecret(node, n -> new NodeId[0]));
    }

    // ------------------------------------------------------------------
    // Real-changelog-backed lookups (root / single-parent / merge revisions)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("getPhase(Revlog) resolves ancestry correctly across root, single-parent and merge revisions of a real changelog")
    void testGetPhase_revlog_realChangelogAncestry() throws Exception {
        assumeHgAvailable();

        File repoDir = tempDir.resolve("realrepo").toFile();
        HgRepository repo = HgTestUtils.nativeRepo(repoDir, dir -> {
            try {
                Files.writeString(new File(dir, "a.txt").toPath(), "root");
                HgTestUtils.hg(dir, "add");
                HgTestUtils.hg(dir, "commit", "-m", "root", "-u", "tester");

                Files.writeString(new File(dir, "b.txt").toPath(), "left branch");
                HgTestUtils.hg(dir, "add");
                HgTestUtils.hg(dir, "commit", "-m", "left", "-u", "tester");

                HgTestUtils.hg(dir, "update", "0");
                Files.writeString(new File(dir, "c.txt").toPath(), "right branch");
                HgTestUtils.hg(dir, "add");
                HgTestUtils.hg(dir, "commit", "-m", "right", "-u", "tester");

                HgTestUtils.hg(dir, "merge", "1");
                HgTestUtils.hg(dir, "commit", "-m", "merge", "-u", "tester");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Revlog changelog = new Revlog(
                new File(repo.getStoreDir(), "00changelog.i"),
                new File(repo.getStoreDir(), "00changelog.d"));
        assertEquals(4, changelog.getRevisionCount());

        NodeId rootNode = new NodeId(changelog.getIndexRecord(0).getNodeId());
        NodeId leftNode = new NodeId(changelog.getIndexRecord(1).getNodeId());
        NodeId rightNode = new NodeId(changelog.getIndexRecord(2).getNodeId());
        NodeId mergeNode = new NodeId(changelog.getIndexRecord(3).getNodeId());

        File phaserootsFile = tempDir.resolve("phaseroots").toFile();
        PhaseRoots phaseRoots = new PhaseRoots(phaserootsFile);

        // With no roots recorded yet, everything (root, single-parent branches, and the
        // two-parent merge revision) resolves to PUBLIC by walking the real changelog parents.
        assertEquals(PhaseRoots.Phase.PUBLIC, phaseRoots.getPhase(rootNode, changelog));
        assertEquals(PhaseRoots.Phase.PUBLIC, phaseRoots.getPhase(leftNode, changelog));
        assertEquals(PhaseRoots.Phase.PUBLIC, phaseRoots.getPhase(rightNode, changelog));
        assertEquals(PhaseRoots.Phase.PUBLIC, phaseRoots.getPhase(mergeNode, changelog));

        // Marking the root as DRAFT must propagate to both single-parent branches and, via
        // the merge revision's two real changelog parents, to the merge commit as well.
        phaseRoots.setPhase(rootNode, PhaseRoots.Phase.DRAFT, n -> new NodeId[0]);
        assertEquals(PhaseRoots.Phase.DRAFT, phaseRoots.getPhase(leftNode, changelog));
        assertEquals(PhaseRoots.Phase.DRAFT, phaseRoots.getPhase(rightNode, changelog));
        assertEquals(PhaseRoots.Phase.DRAFT, phaseRoots.getPhase(mergeNode, changelog));

        // isSecret(Revlog): false while the branch is only DRAFT, true once its own root is
        // reassigned to SECRET (also exercises the direct-hit path, not just BFS ancestry).
        assertFalse(phaseRoots.isSecret(leftNode, changelog));
        phaseRoots.setPhase(leftNode, PhaseRoots.Phase.SECRET, n -> new NodeId[0]);
        assertTrue(phaseRoots.isSecret(leftNode, changelog));
        assertFalse(phaseRoots.isSecret(rightNode, changelog));

        // An unknown node absent from the changelog resolves to PUBLIC (findRevision == -1).
        NodeId unknown = NodeId.fromHex("ab".repeat(20));
        assertEquals(PhaseRoots.Phase.PUBLIC, phaseRoots.getPhase(unknown, changelog));
    }

    @Test
    @DisplayName("setPhase(Revlog) records the phase directly without needing to resolve ancestry via the changelog")
    void testSetPhase_revlog_realChangelog_directRootAssignment() throws Exception {
        assumeHgAvailable();

        File repoDir = tempDir.resolve("setphaserepo").toFile();
        HgRepository repo = HgTestUtils.nativeRepo(repoDir, dir -> {
            try {
                Files.writeString(new File(dir, "a.txt").toPath(), "root");
                HgTestUtils.hg(dir, "add");
                HgTestUtils.hg(dir, "commit", "-m", "root", "-u", "tester");

                Files.writeString(new File(dir, "b.txt").toPath(), "child");
                HgTestUtils.hg(dir, "add");
                HgTestUtils.hg(dir, "commit", "-m", "child", "-u", "tester");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Revlog changelog = new Revlog(
                new File(repo.getStoreDir(), "00changelog.i"),
                new File(repo.getStoreDir(), "00changelog.d"));
        assertEquals(2, changelog.getRevisionCount());

        NodeId rootNode = new NodeId(changelog.getIndexRecord(0).getNodeId());
        NodeId childNode = new NodeId(changelog.getIndexRecord(1).getNodeId());

        File phaserootsFile = tempDir.resolve("phaseroots").toFile();
        PhaseRoots phaseRoots = new PhaseRoots(phaserootsFile);

        // setPhase(Revlog) marks childNode itself as SECRET; the parent (rootNode) stays
        // PUBLIC since setPhase assigns a direct root boundary rather than walking ancestry.
        phaseRoots.setPhase(childNode, PhaseRoots.Phase.SECRET, changelog);
        assertEquals(PhaseRoots.Phase.SECRET, phaseRoots.getPhase(childNode, changelog));
        assertEquals(PhaseRoots.Phase.PUBLIC, phaseRoots.getPhase(rootNode, changelog));

        String savedContent = Files.readString(phaserootsFile.toPath(), StandardCharsets.UTF_8);
        assertTrue(savedContent.contains(childNode.toHex()));
        assertFalse(savedContent.contains(rootNode.toHex()));

        // A subsequent write reverting to PUBLIC removes the persisted root and both
        // revisions resolve to PUBLIC again through the real changelog-backed lookup.
        phaseRoots.setPhase(childNode, PhaseRoots.Phase.PUBLIC, changelog);
        assertEquals(PhaseRoots.Phase.PUBLIC, phaseRoots.getPhase(childNode, changelog));

        PhaseRoots reloaded = new PhaseRoots(phaserootsFile);
        assertEquals(PhaseRoots.Phase.PUBLIC, reloaded.getPhase(childNode, changelog));
    }

    private static void assumeHgAvailable() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(),
                "Real 'hg' CLI not available in this environment");
    }
}
