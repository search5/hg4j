package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Named-branch ({@code hg branch}/{@code hg branches}/{@code hg heads <branch>}) real hg CLI
 * interop verification (backlog 23's "branch" category). Every scenario here is checked against
 * an actual native {@code hg} 7.2.2 process, not just hg4j's own round trip -- see
 * {@code llm-wiki/decisions/mercurial-spec-compliance-requirement.md} backlog item 23.
 */
@Tag("interop")
public class BranchRealHgInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    /** Parses a real {@code hg branches} line, e.g. "feature   3:abcdef012345 (inactive)". */
    private static final Pattern BRANCHES_LINE =
            Pattern.compile("^(\\S+)\\s+(\\d+):([0-9a-f]+)(?:\\s+\\((\\S+)\\))?$");

    @Test
    public void hg4jCreatedBranchAndCommitAreRecognizedByRealHg(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);

        Files.writeString(new File(repoDir, "a.txt").toPath(), "one");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("T").setMessage("c0").call();

        assertEquals("feature", new BranchCommand(repo).setBranchName("feature").call());
        Files.writeString(new File(repoDir, "b.txt").toPath(), "two");
        new AddCommand(repo).call();
        byte[] c1 = new CommitCommand(repo).setAuthor("T").setMessage("c1-feature").call();

        String nativeLog = HgTestUtils.hg(repoDir, "log", "-r", "tip", "--template", "{branch} {node}");
        assertTrue(nativeLog.startsWith("feature " + NodeIdUtil.toHex(c1)),
                "real hg must see the hg4j-created commit on branch 'feature': " + nativeLog);

        String nativeBranches = HgTestUtils.hg(repoDir, "branches");
        assertTrue(nativeBranches.contains("feature"), nativeBranches);
    }

    @Test
    public void hg4jBranchesListMatchesRealHgIncludingOrderAndActiveMarker(@TempDir Path tempDir) throws Exception {
        // Reproduces the exact scenario that, on real hg, sorts by (active, rev) rather than by
        // revision alone: branch A (rev2) is made inactive by a later commit (rev3, branch Z)
        // built on top of it, while branch Y (rev1) is untouched and stays active despite its
        // lower revision number.
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);

        Files.writeString(new File(repoDir, "a.txt").toPath(), "0");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("T").setMessage("c0").call();

        new BranchCommand(repo).setBranchName("Y").call();
        Files.writeString(new File(repoDir, "y.txt").toPath(), "y");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("T").setMessage("c1-Y").call();

        setParentTo(repo, c0);
        new BranchCommand(repo).setBranchName("A").call();
        Files.writeString(new File(repoDir, "aa.txt").toPath(), "aa");
        new AddCommand(repo).call();
        byte[] c2 = new CommitCommand(repo).setAuthor("T").setMessage("c2-A").call();

        setParentTo(repo, c2);
        new BranchCommand(repo).setBranchName("Z").call();
        Files.writeString(new File(repoDir, "zz.txt").toPath(), "zz");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("T").setMessage("c3-Z").call();

        List<BranchesCommand.BranchHead> hg4jBranches = new BranchesCommand(repo).call();
        List<String> hg4jOrder = hg4jBranches.stream().map(BranchesCommand.BranchHead::getBranch).toList();

        String nativeOut = HgTestUtils.hg(repoDir, "branches");
        List<String> nativeOrder = new java.util.ArrayList<>();
        Map<String, Boolean> nativeActive = new java.util.HashMap<>();
        for (String line : nativeOut.split("\n")) {
            Matcher m = BRANCHES_LINE.matcher(line.trim());
            assertTrue(m.matches(), "unparsable hg branches line: [" + line + "]");
            nativeOrder.add(m.group(1));
            nativeActive.put(m.group(1), m.group(4) == null); // no suffix at all => active
        }

        assertEquals(nativeOrder, hg4jOrder,
                "hg4j branch listing order must match real hg's (active, rev, name) sort exactly");
        for (BranchesCommand.BranchHead h : hg4jBranches) {
            assertEquals(nativeActive.get(h.getBranch()), h.isActive(),
                    "active flag mismatch for branch " + h.getBranch());
        }
    }

    @Test
    public void closeBranchRemovesItFromDefaultBranchesListingInBothHg4jAndRealHg(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);

        Files.writeString(new File(repoDir, "a.txt").toPath(), "0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("T").setMessage("c0").call();

        new BranchCommand(repo).setBranchName("feature").call();
        Files.writeString(new File(repoDir, "b.txt").toPath(), "1");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("T").setMessage("c1-feature").setCloseBranch(true).call();

        // hg4j side: branch must be hidden by default, shown with --closed
        List<BranchesCommand.BranchHead> defaultListing = new BranchesCommand(repo).call();
        assertEquals(1, defaultListing.size());
        assertEquals("default", defaultListing.get(0).getBranch());
        List<BranchesCommand.BranchHead> closedListing = new BranchesCommand(repo).setIncludeClosed(true).call();
        assertEquals(2, closedListing.size());

        // real hg side, reading the exact same repo hg4j just wrote
        String nativeDefault = HgTestUtils.hg(repoDir, "branches");
        assertFalse(nativeDefault.contains("feature"),
                "real hg's default 'hg branches' must also hide the fully-closed branch: " + nativeDefault);
        String nativeClosed = HgTestUtils.hg(repoDir, "branches", "--closed");
        assertTrue(nativeClosed.contains("feature") && nativeClosed.contains("(closed)"),
                "real hg's 'hg branches --closed' must show it marked closed: " + nativeClosed);
    }

    @Test
    public void branchesClosedOrdersActiveBeforeClosedBeforeMerelyInactiveMatchingRealHg(@TempDir Path tempDir) throws Exception {
        // A distinct ordering scenario from hg4jBranchesListMatchesRealHgIncludingOrderAndActiveMarker:
        // here one branch is fully closed (never "active" by definition) rather than merely
        // shadowed by a later commit elsewhere, mixed with one active and one plain-inactive branch.
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);

        Files.writeString(new File(repoDir, "a.txt").toPath(), "0");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("T").setMessage("c0").call();

        new BranchCommand(repo).setBranchName("closedb").call();
        Files.writeString(new File(repoDir, "b.txt").toPath(), "1");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("T").setMessage("c1-closedb").call();
        new CommitCommand(repo).setAuthor("T").setMessage("close-closedb").setCloseBranch(true).call();

        setParentTo(repo, c0);
        new BranchCommand(repo).setBranchName("openb").call();
        Files.writeString(new File(repoDir, "c.txt").toPath(), "2");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("T").setMessage("c-openb").call();

        List<String> hg4jOrder = new BranchesCommand(repo).setIncludeClosed(true).call()
                .stream().map(BranchesCommand.BranchHead::getBranch).toList();

        String nativeOut = HgTestUtils.hg(repoDir, "branches", "--closed");
        List<String> nativeOrder = new java.util.ArrayList<>();
        for (String line : nativeOut.split("\n")) {
            Matcher m = BRANCHES_LINE.matcher(line.trim());
            assertTrue(m.matches(), "unparsable hg branches line: [" + line + "]");
            nativeOrder.add(m.group(1));
        }
        assertEquals(nativeOrder, hg4jOrder,
                "hg4j --closed ordering must match real hg's (active, rev, name) sort exactly: " + nativeOut);
        assertEquals(List.of("openb", "closedb", "default"), hg4jOrder);
    }

    @Test
    public void branchInternalForkProducesTwoHeadsSeenIdenticallyByHg4jAndRealHg(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);

        Files.writeString(new File(repoDir, "a.txt").toPath(), "0");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("T").setMessage("c0").call();

        new BranchCommand(repo).setBranchName("feature").call();
        Files.writeString(new File(repoDir, "b.txt").toPath(), "1");
        new AddCommand(repo).call();
        byte[] c1a = new CommitCommand(repo).setAuthor("T").setMessage("c1a-feature").call();

        setParentTo(repo, c0);
        new BranchCommand(repo).setBranchName("feature").call();
        Files.writeString(new File(repoDir, "c.txt").toPath(), "2");
        new AddCommand(repo).call();
        byte[] c1b = new CommitCommand(repo).setAuthor("T").setMessage("c1b-feature-fork").call();

        // hg4j: hg heads feature must report both branch-internal heads.
        List<String> hg4jHeads = new HeadsCommand(repo).setBranch("feature").call();
        assertEquals(2, hg4jHeads.size());
        assertTrue(hg4jHeads.contains(NodeIdUtil.toHex(c1a)));
        assertTrue(hg4jHeads.contains(NodeIdUtil.toHex(c1b)));

        // real hg reading the same repo must agree.
        String nativeHeads = HgTestUtils.hg(repoDir, "heads", "feature", "--template", "{node}\\n");
        List<String> nativeHeadHexes = List.of(nativeHeads.split("\n"));
        assertEquals(2, nativeHeadHexes.size(), "real hg: " + nativeHeads);
        assertTrue(nativeHeadHexes.contains(NodeIdUtil.toHex(c1a)));
        assertTrue(nativeHeadHexes.contains(NodeIdUtil.toHex(c1b)));

        // hg4j's per-branch head *set* (order-independent) must exactly equal real hg's.
        assertEquals(
                hg4jHeads.stream().sorted().collect(Collectors.toList()),
                nativeHeadHexes.stream().sorted().collect(Collectors.toList()));
    }

    @Test
    public void headsBranchExcludesClosedHeadsByDefaultInBothHg4jAndRealHg(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);

        Files.writeString(new File(repoDir, "a.txt").toPath(), "0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("T").setMessage("c0").call();

        new BranchCommand(repo).setBranchName("feature").call();
        Files.writeString(new File(repoDir, "b.txt").toPath(), "1");
        new AddCommand(repo).call();
        byte[] closedHead = new CommitCommand(repo).setAuthor("T").setMessage("c1-feature")
                .setCloseBranch(true).call();

        // hg4j: default (no --closed) has no open heads for feature.
        assertTrue(new HeadsCommand(repo).setBranch("feature").call().isEmpty());
        List<String> withClosed = new HeadsCommand(repo).setBranch("feature").setIncludeClosed(true).call();
        assertEquals(List.of(NodeIdUtil.toHex(closedHead)), withClosed);

        // real hg agrees: `hg heads feature` (no --closed) errors out with exit 1 ("no open
        // branch heads found"); `hg heads feature --closed` reports the closed head.
        boolean threw = false;
        try {
            HgTestUtils.hg(repoDir, "heads", "feature");
        } catch (AssertionError expected) {
            threw = true;
        }
        assertTrue(threw, "real hg must report no open heads for a fully-closed branch");
        String nativeClosedHeads = HgTestUtils.hg(repoDir, "heads", "feature", "--closed", "--template", "{node}");
        assertEquals(NodeIdUtil.toHex(closedHead), nativeClosedHeads);
    }

    private static void setParentTo(HgRepository repo, byte[] node) throws Exception {
        io.github.search5.hg4j.dirstate.Dirstate dirstate = repo.getDirstate();
        dirstate.setParents(new io.github.search5.hg4j.lib.NodeId(node), io.github.search5.hg4j.lib.NodeId.NULL);
        repo.writeDirstate(dirstate);
    }
}
