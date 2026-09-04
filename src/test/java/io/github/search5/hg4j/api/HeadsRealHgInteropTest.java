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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real hg CLI interop verification for {@link HeadsCommand}'s no-argument default (backlog 23's
 * "branch"/"heads" follow-up, decided 2026-09-04): real hg's plain {@code hg heads} is branch-aware
 * (every named branch's own open head(s), even when not a repo-wide topological leaf) while
 * {@code hg heads --topo} is purely topological (revisions with no children anywhere in the
 * repository). Every scenario here is checked against an actual native {@code hg} 7.2.2 process,
 * not just hg4j's own round trip -- see
 * {@code llm-wiki/decisions/mercurial-spec-compliance-requirement.md}.
 */
@Tag("interop")
public class HeadsRealHgInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    private static void setParentTo(HgRepository repo, byte[] node) throws Exception {
        io.github.search5.hg4j.dirstate.Dirstate dirstate = repo.getDirstate();
        dirstate.setParents(new io.github.search5.hg4j.lib.NodeId(node), io.github.search5.hg4j.lib.NodeId.NULL);
        repo.writeDirstate(dirstate);
    }

    private static List<String> hexSorted(List<String> hexNodes) {
        return hexNodes.stream().sorted().collect(Collectors.toList());
    }

    private static List<String> nativeHeadHexes(File repoDir, String... extraArgs) throws Exception {
        String[] args = new String[extraArgs.length + 2];
        args[0] = "heads";
        System.arraycopy(extraArgs, 0, args, 1, extraArgs.length);
        args[args.length - 1] = "--template={node}\\n";
        String out = HgTestUtils.hg(repoDir, args);
        if (out.isEmpty()) {
            return List.of();
        }
        return List.of(out.split("\n"));
    }

    /**
     * Reproduces the exact divergence confirmed in backlog 23: branch "feature" commits once from
     * the root, then branch "default" forks from that same root and merges feature's tip into a new
     * default-branch commit. Merging makes feature's tip the parent of a *different-branch*
     * revision, so it is no longer a repo-wide topological leaf -- but it still has no *same-branch*
     * child, so it remains branch "feature"'s own open head. Real hg's plain {@code hg heads} must
     * therefore still list it, while {@code hg heads --topo} must not.
     */
    @Test
    public void defaultHeadsListsEveryBranchsOwnHeadEvenWhenNotATopologicalLeaf(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);

        Files.writeString(new File(repoDir, "root.txt").toPath(), "root");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("T").setMessage("c0").call();

        // Branch "feature": one commit from the root. This becomes feature's own head.
        new BranchCommand(repo).setBranchName("feature").call();
        Files.writeString(new File(repoDir, "feature.txt").toPath(), "f");
        new AddCommand(repo).call();
        byte[] featureHead = new CommitCommand(repo).setAuthor("T").setMessage("c1-feature").call();

        // Fork back to the root on branch "default" and merge feature's tip into a fresh default
        // commit. This makes featureHead a *parent* of a default-branch revision -- featureHead now
        // has a cross-branch child, so it is no longer a repo-wide topological leaf, but it is still
        // branch "feature"'s own head (no same-branch child).
        setParentTo(repo, c0);
        new BranchCommand(repo).setBranchName("default").call();
        Files.writeString(new File(repoDir, "default2.txt").toPath(), "d2");
        new AddCommand(repo).call();
        byte[] default2 = new CommitCommand(repo).setAuthor("T").setMessage("c2-default").call();

        MergeCommand.MergeResult mergeRes = new MergeCommand(repo).setNodeId(featureHead).call();
        assertFalse(mergeRes.isConflicted());
        byte[] mergeCommit = new CommitCommand(repo).setAuthor("T").setMessage("merge feature into default").call();

        String hexFeatureHead = NodeIdUtil.toHex(featureHead);
        String hexDefault2 = NodeIdUtil.toHex(default2);
        String hexMerge = NodeIdUtil.toHex(mergeCommit);

        // Sanity: featureHead is indeed not a repo-wide topological leaf any more (it is a parent
        // of the merge commit), but default2 was superseded by the merge commit on branch default.
        List<String> hg4jTopo = new HeadsCommand(repo).setTopo(true).call();
        assertFalse(hg4jTopo.contains(hexFeatureHead),
                "featureHead must NOT be a topological leaf once merged into default: " + hg4jTopo);
        assertEquals(List.of(hexMerge), hg4jTopo,
                "the merge commit must be the sole repo-wide topological leaf: " + hg4jTopo);

        // hg4j's new no-arg default must list feature's own head (featureHead) *and* default's own
        // head (the merge commit) -- two heads, not one.
        List<String> hg4jDefault = new HeadsCommand(repo).call();
        assertEquals(hexSorted(List.of(hexFeatureHead, hexMerge)), hexSorted(hg4jDefault),
                "no-arg heads() must include feature's own head even though it is not a topological leaf: "
                        + hg4jDefault);

        // Real hg must agree on both fronts.
        List<String> nativeDefault = nativeHeadHexes(repoDir);
        List<String> nativeTopo = nativeHeadHexes(repoDir, "--topo");

        assertEquals(hexSorted(nativeDefault), hexSorted(hg4jDefault),
                "hg4j's new no-arg default must match real hg's plain 'hg heads' exactly");
        assertEquals(hexSorted(nativeTopo), hexSorted(hg4jTopo),
                "hg4j's setTopo(true) must match real hg's 'hg heads --topo' exactly");

        assertTrue(nativeDefault.contains(hexFeatureHead),
                "real hg's plain 'hg heads' must list feature's own head: " + nativeDefault);
        assertFalse(nativeTopo.contains(hexFeatureHead),
                "real hg's 'hg heads --topo' must NOT list feature's own head: " + nativeTopo);
    }

    /** Order check: real hg's plain {@code hg heads} sorts the aggregated list by revision descending. */
    @Test
    public void defaultHeadsOrdersAllBranchesByRevisionDescendingMatchingRealHg(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);

        Files.writeString(new File(repoDir, "a.txt").toPath(), "0");
        new AddCommand(repo).call();
        byte[] c0 = new CommitCommand(repo).setAuthor("T").setMessage("c0").call();

        new BranchCommand(repo).setBranchName("alpha").call();
        Files.writeString(new File(repoDir, "alpha.txt").toPath(), "a");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("T").setMessage("c1-alpha").call();

        setParentTo(repo, c0);
        new BranchCommand(repo).setBranchName("beta").call();
        Files.writeString(new File(repoDir, "beta.txt").toPath(), "b");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("T").setMessage("c2-beta").call();

        List<String> hg4jDefault = new HeadsCommand(repo).call();
        List<String> nativeDefault = nativeHeadHexes(repoDir);

        assertEquals(nativeDefault, hg4jDefault,
                "hg4j's no-arg heads() order must match real hg's revision-descending sort exactly");
    }

    /** {@code setTopo(true)} plus {@code setIncludeClosed} together are a real-hg no-op for closed. */
    @Test
    public void topoModeIgnoresBranchClosureMatchingRealHg(@TempDir Path tempDir) throws Exception {
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

        List<String> hg4jTopo = new HeadsCommand(repo).setTopo(true).call();
        List<String> nativeTopo = nativeHeadHexes(repoDir, "--topo");

        assertEquals(hexSorted(nativeTopo), hexSorted(hg4jTopo));
        assertTrue(hg4jTopo.contains(NodeIdUtil.toHex(closedHead)),
                "a closed head with no children is still a topological leaf: " + hg4jTopo);
    }
}
