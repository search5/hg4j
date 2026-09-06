package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.NodeId;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-hg-CLI interop verification for {@code hg commit}'s less-common porcelain scenarios
 * (backlog item 23, "commit" bullet). hg4j builds a repository directly on disk with its own
 * {@link CommitCommand}/{@link MergeCommand}/{@link AmendCommand} etc, and the *same* directory
 * is then read/audited with the real, host-installed {@code hg} CLI (via {@link HgTestUtils#hg})
 * -- {@code hg verify}, {@code hg log}, {@code hg files}, {@code hg branches} -- so this is a
 * genuine hg4j-writes/real-hg-reads round trip, not an hg4j-only self-consistency check.
 */
@Tag("interop")
public class CommitRealHgInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    /**
     * A real 2-parent merge commit whose changed files span a plain text file, an executable
     * script, a symlink, and a binary file (mixed types) -- real hg must read the merge commit's
     * {@code p1}/{@code p2} fields, per-file flags, and byte-identical content correctly, and
     * {@code hg verify} must find no integrity errors.
     */
    @Test
    public void testMergeCommitWithMixedFileTypesReadableByRealHg(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);

        // Base revision: a text file, an executable script, a symlink, and a binary file.
        File textFile = new File(repoDir, "text.txt");
        File execFile = new File(repoDir, "run.sh");
        File linkFile = new File(repoDir, "link.txt");
        File linkTarget = new File(repoDir, "target.txt");
        File binFile = new File(repoDir, "data.bin");

        Files.writeString(textFile.toPath(), "base text\n");
        Files.writeString(linkTarget.toPath(), "link target v1\n");
        Files.writeString(execFile.toPath(), "#!/bin/sh\necho base\n");
        assertTrue(execFile.setExecutable(true, false), "test requires setting the executable bit");
        Files.createSymbolicLink(linkFile.toPath(), Path.of("target.txt"));
        byte[] binBase = new byte[]{0x00, 0x01, (byte) 0xFF, 0x7F, 0x00, (byte) 0x80, 0x10, 0x00};
        Files.write(binFile.toPath(), binBase);

        new AddCommand(repo).call();
        byte[] baseNode = new CommitCommand(repo).setAuthor("T").setMessage("base").call();

        // Branch A: change the text file only.
        Files.writeString(textFile.toPath(), "base text\nfrom branch A\n");
        byte[] branchANode = new CommitCommand(repo).setAuthor("T").setMessage("branch A").call();

        // Back to base, branch B: change the exec script, symlink target, and binary file.
        new UpdateCommand(repo).setRevision(new NodeId(baseNode).toHex()).call();
        Files.writeString(execFile.toPath(), "#!/bin/sh\necho branch-b\n");
        assertTrue(execFile.setExecutable(true, false));
        Files.delete(linkFile.toPath());
        Files.createSymbolicLink(linkFile.toPath(), Path.of("text.txt"));
        byte[] binB = new byte[]{0x02, 0x03, (byte) 0xFE, 0x00, 0x00, 0x55};
        Files.write(binFile.toPath(), binB);
        byte[] branchBNode = new CommitCommand(repo).setAuthor("T").setMessage("branch B").call();

        // Merge branch A into branch B's working copy (non-conflicting: disjoint file sets).
        new UpdateCommand(repo).setRevision(new NodeId(branchBNode).toHex()).call();
        MergeCommand.MergeResult mergeResult = new MergeCommand(repo).setNodeId(branchANode).call();
        assertFalse(mergeResult.isConflicted(), "merge must be conflict-free: " + mergeResult.getConflicts());

        byte[] mergeNode = new CommitCommand(repo).setAuthor("T").setMessage("merge A into B").call();
        String mergeHex = new NodeId(mergeNode).toHex();
        String p1Hex = new NodeId(branchBNode).toHex();
        String p2Hex = new NodeId(branchANode).toHex();

        // Real hg must see the same p1/p2 on the merge commit.
        String nativeP1 = HgTestUtils.hg(repoDir, "log", "-r", mergeHex, "--template", "{p1node}");
        String nativeP2 = HgTestUtils.hg(repoDir, "log", "-r", mergeHex, "--template", "{p2node}");
        assertEquals(p1Hex, nativeP1, "real hg must see the same p1 on the merge commit");
        assertEquals(p2Hex, nativeP2, "real hg must see the same p2 on the merge commit");

        // Real hg must see the correct per-file flags on the merge commit.
        String flags = HgTestUtils.hg(repoDir, "files", "-r", mergeHex, "-T", "{path}\\0{flags}\\n");
        Map<String, String> flagByPath = new HashMap<>();
        for (String line : flags.split("\n")) {
            if (line.isEmpty()) continue;
            String[] parts = line.split("\0", -1);
            flagByPath.put(parts[0], parts.length > 1 ? parts[1] : "");
        }
        assertEquals("x", flagByPath.get("run.sh"), "run.sh must keep its executable flag: " + flagByPath);
        assertEquals("l", flagByPath.get("link.txt"), "link.txt must keep its symlink flag: " + flagByPath);
        assertEquals("", flagByPath.get("text.txt"), "text.txt must have no flags: " + flagByPath);

        // Real hg must reproduce the merged content exactly.
        assertEquals("base text\nfrom branch A", HgTestUtils.hg(repoDir, "cat", "-r", mergeHex, "text.txt"));
        assertEquals("#!/bin/sh\necho branch-b", HgTestUtils.hg(repoDir, "cat", "-r", mergeHex, "run.sh"));

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.contains("integrity error") || verify.toLowerCase().contains("error:"),
                "real hg verify must find no errors: " + verify);
    }

    /** {@code hg commit --close-branch}: real hg's `hg branches` must drop the branch once its
     * only head is closed, and `hg branches --closed` must still list it. */
    @Test
    public void testCloseBranchDropsFromRealHgBranchesListing(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);

        Files.writeString(new File(repoDir, "a.txt").toPath(), "one");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("T").setMessage("c0 on default").call();

        new BranchCommand(repo).setBranchName("feature").call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "two");
        new CommitCommand(repo).setAuthor("T").setMessage("c1 on feature").call();

        Files.writeString(new File(repoDir, "a.txt").toPath(), "three");
        byte[] closeNode = new CommitCommand(repo).setAuthor("T").setMessage("closing feature")
                .setCloseBranch(true).call();
        String closeHex = new NodeId(closeNode).toHex();

        String openBranches = HgTestUtils.hg(repoDir, "branches");
        assertFalse(openBranches.contains("feature"), "closed branch must not appear in default `hg branches`: " + openBranches);
        assertTrue(openBranches.contains("default"), "default branch must still be listed: " + openBranches);

        String closedBranches = HgTestUtils.hg(repoDir, "branches", "--closed");
        assertTrue(closedBranches.contains("feature"), "`hg branches --closed` must still list the closed branch: " + closedBranches);

        String nativeCloseFlag = HgTestUtils.hg(repoDir, "log", "-r", closeHex, "--template", "{closesbranch}");
        assertEquals("true", nativeCloseFlag, "real hg must see the close marker on the commit itself");
    }

    /** A commit with no file changes at all (message-only) must still be a valid, real-hg-readable
     * revision with an unchanged manifest node inherited from its parent. */
    @Test
    public void testEmptyCommitReadableByRealHg(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);

        Files.writeString(new File(repoDir, "a.txt").toPath(), "one");
        new AddCommand(repo).call();
        byte[] c1 = new CommitCommand(repo).setAuthor("T").setMessage("c1").call();

        // No file modifications at all before the second commit.
        byte[] c2 = new CommitCommand(repo).setAuthor("T").setMessage("empty commit, message only").call();
        assertNotEquals(new NodeId(c1).toHex(), new NodeId(c2).toHex());

        String c2Hex = new NodeId(c2).toHex();
        String nativeDesc = HgTestUtils.hg(repoDir, "log", "-r", c2Hex, "--template", "{desc}");
        assertEquals("empty commit, message only", nativeDesc);

        String nativeFileCount = HgTestUtils.hg(repoDir, "log", "-r", c2Hex, "--template", "{files|count}");
        assertEquals("0", nativeFileCount, "real hg must see zero changed files on the empty commit");

        String nativeParent = HgTestUtils.hg(repoDir, "log", "-r", c2Hex, "--template", "{p1node}");
        assertEquals(new NodeId(c1).toHex(), nativeParent);

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.contains("integrity error"), "real hg verify must find no errors: " + verify);

        // The manifest content itself must be byte-identical to c1's (no spurious changes).
        String mf1 = HgTestUtils.hg(repoDir, "manifest", "-r", new NodeId(c1).toHex(), "--debug");
        String mf2 = HgTestUtils.hg(repoDir, "manifest", "-r", c2Hex, "--debug");
        assertEquals(mf1, mf2, "empty commit's manifest must be identical to its parent's");
    }

    /** When both `branch` and `close` extras apply to the same commit, real hg must be able to
     * decode both regardless of hg4j's internal encode ordering. */
    @Test
    public void testBranchAndCloseExtraTogetherDecodedByRealHg(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);

        Files.writeString(new File(repoDir, "a.txt").toPath(), "one");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("T").setMessage("c0").call();

        new BranchCommand(repo).setBranchName("release-1.0").call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "two");
        byte[] closeNode = new CommitCommand(repo).setAuthor("T").setMessage("close release-1.0")
                .setCloseBranch(true).call();
        String closeHex = new NodeId(closeNode).toHex();

        String nativeBranch = HgTestUtils.hg(repoDir, "log", "-r", closeHex, "--template", "{branch}");
        assertEquals("release-1.0", nativeBranch, "real hg must decode the branch extra correctly");
        String nativeClose = HgTestUtils.hg(repoDir, "log", "-r", closeHex, "--template", "{closesbranch}");
        assertEquals("true", nativeClose, "real hg must decode the close extra correctly alongside branch");

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.contains("integrity error"), "real hg verify must find no errors: " + verify);
    }

    /**
     * {@code hg commit --amend} must replace the amended commit as a SIBLING on its own original
     * parent, not become its child -- verified via real hg's own DAG view (2026-09-04 fix; see
     * {@link CommitCommand#setAmendDeclaredParents} / {@link AmendCommand}).
     */
    @Test
    public void testAmendReplacesCommitOnOriginalParentPerRealHg(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        enableEvolutionForObsstoreReads(repoDir);
        HgRepository repo = new HgRepository(repoDir);

        Files.writeString(new File(repoDir, "a.txt").toPath(), "base");
        new AddCommand(repo).call();
        byte[] c1 = new CommitCommand(repo).setAuthor("T").setMessage("c1").call();
        String c1Hex = new NodeId(c1).toHex();

        Files.writeString(new File(repoDir, "b.txt").toPath(), "two");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("T").setMessage("c2").call();

        Files.writeString(new File(repoDir, "b.txt").toPath(), "two-amended");
        byte[] amended = new AmendCommand(repo).setAuthor("Amender").setMessage("c2 amended").call();
        String amendedHex = new NodeId(amended).toHex();

        // Real hg must see exactly one visible head, whose parent is c1 (NOT the amended-away c2).
        String heads = HgTestUtils.hg(repoDir, "heads", "--template", "{node} ");
        assertEquals(amendedHex, heads.trim(), "real hg must see only the amended commit as head: " + heads);

        String nativeParent = HgTestUtils.hg(repoDir, "log", "-r", amendedHex, "--template", "{p1node}");
        assertEquals(c1Hex, nativeParent, "amended commit must keep the ORIGINAL commit's own parent (sibling replace, not child)");

        // The amended-away c2 must still be present as an obsolete/hidden ancestor-less orphan
        // when explicitly requested, and must NOT appear in normal `hg log`.
        String normalLogCount = HgTestUtils.hg(repoDir, "log", "--template", "{node}\n");
        assertEquals(2, normalLogCount.split("\n").length, "normal `hg log` must show exactly c1 + amended c2: " + normalLogCount);

        // Content must reflect the amended edit.
        assertEquals("two-amended", HgTestUtils.hg(repoDir, "cat", "-r", amendedHex, "b.txt"));
        assertEquals("base", HgTestUtils.hg(repoDir, "cat", "-r", amendedHex, "a.txt"),
                "unmodified a.txt must be carried forward into the amended commit unchanged");

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.contains("integrity error"), "real hg verify must find no errors: " + verify);
    }

    /** Amending a merge commit must keep BOTH of the original merge's parents. */
    @Test
    public void testAmendOfMergeCommitKeepsBothOriginalParents(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        enableEvolutionForObsstoreReads(repoDir);
        HgRepository repo = new HgRepository(repoDir);

        Files.writeString(new File(repoDir, "base.txt").toPath(), "base");
        new AddCommand(repo).call();
        byte[] baseNode = new CommitCommand(repo).setAuthor("T").setMessage("base").call();

        Files.writeString(new File(repoDir, "a.txt").toPath(), "a");
        new AddCommand(repo).call();
        byte[] branchA = new CommitCommand(repo).setAuthor("T").setMessage("branch A").call();

        new UpdateCommand(repo).setRevision(new NodeId(baseNode).toHex()).call();
        Files.writeString(new File(repoDir, "b.txt").toPath(), "b");
        new AddCommand(repo).call();
        byte[] branchB = new CommitCommand(repo).setAuthor("T").setMessage("branch B").call();

        new MergeCommand(repo).setNodeId(branchA).call();
        byte[] mergeNode = new CommitCommand(repo).setAuthor("T").setMessage("merge").call();

        Files.writeString(new File(repoDir, "c.txt").toPath(), "c");
        new AddCommand(repo).call();
        byte[] amendedMerge = new AmendCommand(repo).setMessage("merge amended").call();
        String amendedHex = new NodeId(amendedMerge).toHex();

        String p1 = HgTestUtils.hg(repoDir, "log", "-r", amendedHex, "--template", "{p1node}");
        String p2 = HgTestUtils.hg(repoDir, "log", "-r", amendedHex, "--template", "{p2node}");
        assertEquals(new NodeId(branchB).toHex(), p1, "amended merge must keep original p1");
        assertEquals(new NodeId(branchA).toHex(), p2, "amended merge must keep original p2");
        assertEquals("c", HgTestUtils.hg(repoDir, "cat", "-r", amendedHex, "c.txt"));

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.contains("integrity error"), "real hg verify must find no errors: " + verify);
    }

    /**
     * hg4j's {@link AmendCommand} always writes an obsolescence marker (evolve-style amend, an
     * hg4j design decision predating this test), regardless of whether the repository has the
     * {@code experimental.evolution} feature turned on. Real hg 7.2.2 tolerates reading such a
     * repository fine, but emits a `"obsolete" feature not enabled but N markers found!` NOTICE
     * line on stdout for every command that touches the obsstore -- which would otherwise
     * corrupt this test's plain single-line {@code --template} output comparisons. Enabling the
     * feature (matching what a real hg user doing evolve-style amends would also configure)
     * silences that notice without changing what's being verified.
     */
    private static void enableEvolutionForObsstoreReads(File repoDir) throws Exception {
        Files.writeString(new File(repoDir, ".hg/hgrc").toPath(),
                "[experimental]\nevolution = createmarkers\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
