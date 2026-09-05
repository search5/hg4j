package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixBackoutCoreRoundTripTest} for the
 * reused pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to the three
 * lightweight repo-metadata query commands {@link HeadsCommand}, {@link TipCommand} and
 * {@link ParentsCommand} across the native 6-combo grid (changelog family x treemanifest,
 * dirstate fixed at v1 -- v2 needs Docker, see
 * {@code RequirementMatrixHeadsDockerRoundTripTest}). These three are grouped into one trio,
 * matching the precedent set by wave 4's combined {@code RequirementMatrixBranchCoreRoundTripTest}
 * (which covers both {@link BranchCommand} and {@link BranchesCommand}) -- all three are pure
 * read-only DAG queries over the same changelog, so a single scenario naturally exercises all
 * three at once.
 *
 * <p>No {@code HelperMain} subprocess is used here (unlike {@link RequirementMatrixBackoutHelperMain}
 * and friends): the corruption those exist to route around is specific to hg4j's own
 * zstd-compressing <em>write</em> path ({@code Revlog}/{@code CommitCommand}) running in the same
 * JVM that also spawns {@code docker exec}/{@code docker run} children (see
 * {@link RequirementMatrixCommitHelperMain}'s javadoc for the full root-cause writeup). All three
 * commands here are pure readers -- the repository itself is always built exclusively via the real
 * {@code hg} CLI (native here, {@code docker exec} in the Docker counterpart) -- so that failure
 * mode does not apply and hg4j's query methods are called directly in this JVM.
 *
 * <p>The scenario builds a DAG deliberately shaped to exercise {@link HeadsCommand}'s documented
 * branch-aware default semantics (verified live against real hg 7.2.2, see that class's own
 * javadoc for the 2026-09-04 fix this re-confirms): three named branches (default/feature/sub),
 * one of them ({@code feature}) closed, and -- critically -- a revision ({@code c1} on
 * {@code default}) that has a cross-branch child ({@code c4} on {@code sub}) but no same-branch
 * child, so it must still count as {@code default}'s own open head even though it is not a
 * repo-wide topological leaf. An uncommitted real-hg merge at the end also exercises
 * {@link ParentsCommand}'s two-parent (in-progress merge) case.
 *
 * <p>Designing the {@code --topo} scenario against real hg (multiple simultaneous topological
 * leaves) found a genuine hg4j ordering bug: {@link HeadsCommand}'s {@code --topo} branch used to
 * emit leaves in ascending changelog order, while real hg's {@code hg heads --topo} -- like every
 * other {@code hg heads} form -- lists them highest revision first. Fixed by iterating the
 * changelog descending (see that method's own updated javadoc).
 */
@Tag("interop")
public class RequirementMatrixHeadsCoreRoundTripTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    record RequirementCombo(String label, List<String> initConfigArgs) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final List<String> CL_V1 = List.of();
    private static final List<String> CL_V2 = List.of("format.exp-use-changelog-v2=enable-unstable-format-and-corrupt-my-data");
    private static final List<String> CL_V2_SIDEDATA = List.of(
            "format.exp-use-changelog-v2=enable-unstable-format-and-corrupt-my-data",
            "format.exp-use-copies-side-data-changeset=yes");

    private static final List<String> TREEMANIFEST_OFF = List.of();
    private static final List<String> TREEMANIFEST_ON = List.of("experimental.treemanifest=1");

    static Stream<RequirementCombo> combos() {
        List<RequirementCombo> out = new java.util.ArrayList<>();
        for (var cl : List.of(java.util.Map.entry("cl1", CL_V1), java.util.Map.entry("cl2", CL_V2), java.util.Map.entry("cl2+sidedata", CL_V2_SIDEDATA))) {
            for (var tm : List.of(java.util.Map.entry("flatmanifest", TREEMANIFEST_OFF), java.util.Map.entry("treemanifest", TREEMANIFEST_ON))) {
                List<String> args = new java.util.ArrayList<>();
                args.addAll(cl.getValue());
                args.addAll(tm.getValue());
                out.add(new RequirementCombo(cl.getKey() + "/" + tm.getKey(), args));
            }
        }
        return out.stream();
    }

    private static File initWithCombo(Path tempDir, RequirementCombo combo, String suffix) throws Exception {
        File repoDir = tempDir.resolve("repo-" + combo.label().replace("/", "-").replace("+", "_") + "-" + suffix).toFile();
        repoDir.mkdirs();
        List<String> args = new java.util.ArrayList<>();
        args.add("init");
        for (String c : combo.initConfigArgs()) {
            args.add("--config");
            args.add(c);
        }
        HgTestUtils.hg(repoDir, args.toArray(new String[0]));
        return repoDir;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void headsTipParentsMatchRealHgAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "heads");

        // c0: root, default branch.
        Files.writeString(repoDir.toPath().resolve("a.txt"), "a0\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");
        String c0Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        // c1: child of c0, still default -- this will remain default's own open head even after
        // c4 (a DIFFERENT branch) is built on top of it.
        Files.writeString(repoDir.toPath().resolve("a.txt"), "a1\n");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1");
        String c1Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        // c2: child of c0, branch "feature".
        HgTestUtils.hg(repoDir, "update", c0Hex);
        HgTestUtils.hg(repoDir, "branch", "feature");
        Files.writeString(repoDir.toPath().resolve("b.txt"), "b0\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c2");
        String c2Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        // c3: child of c2, branch "feature", closed -- must be excluded from plain `hg heads`.
        Files.writeString(repoDir.toPath().resolve("b.txt"), "b1\n");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c3", "--close-branch");
        String c3Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        // c4: child of c1, branch "sub" -- gives c1 a cross-branch child without a same-branch one.
        HgTestUtils.hg(repoDir, "update", c1Hex);
        HgTestUtils.hg(repoDir, "branch", "sub");
        Files.writeString(repoDir.toPath().resolve("c.txt"), "c0\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c4");
        String c4Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgRepository repo = new HgRepository(repoDir);

        // --- TipCommand: highest revision number, regardless of branch. ---
        String realTipHex = HgTestUtils.hg(repoDir, "log", "-r", "tip", "--template", "{node}");
        assertEquals(c4Hex, realTipHex, "sanity: c4 is tip in this scenario for combo " + combo);
        byte[] hg4jTip = new TipCommand(repo).call();
        assertEquals(realTipHex, io.github.search5.hg4j.util.NodeIdUtil.toHex(hg4jTip),
                "TipCommand must match real hg's tip for combo " + combo);
        assertEquals(4, new TipCommand(repo).getRevisionNumber(),
                "TipCommand revision number must match real hg's for combo " + combo);

        // --- HeadsCommand: default (branch-aware, open heads only). Real hg: c4 (sub, open),
        // c1 (default, open despite c4's cross-branch descent) -- c3 (feature) excluded (closed).
        // Real hg lists heads sorted by revision descending (verified against hg 7.2.2).
        List<String> hg4jHeadsDefault = new HeadsCommand(repo).call();
        assertEquals(List.of(c4Hex, c1Hex), hg4jHeadsDefault,
                "plain `hg heads` (branch-aware, open only) must match real hg for combo " + combo);
        String realHeadsPlainOut = HgTestUtils.hg(repoDir, "heads", "--template", "{node}\n");
        List<String> realHeadsPlain = List.of(realHeadsPlainOut.lines().filter(s -> !s.isBlank()).toArray(String[]::new));
        assertEquals(realHeadsPlain, hg4jHeadsDefault,
                "hg4j's default HeadsCommand output must match real `hg heads` verbatim for combo " + combo);

        // --- HeadsCommand --closed: also include c3 (feature's closed head). ---
        String realHeadsClosedOut = HgTestUtils.hg(repoDir, "heads", "--closed", "--template", "{node}\n");
        List<String> realHeadsClosed = List.of(realHeadsClosedOut.lines().filter(s -> !s.isBlank()).toArray(String[]::new));
        List<String> hg4jHeadsClosed = new HeadsCommand(repo).setIncludeClosed(true).call();
        assertEquals(List.of(c4Hex, c3Hex, c1Hex), hg4jHeadsClosed,
                "combo " + combo);
        assertEquals(realHeadsClosed, hg4jHeadsClosed,
                "hg4j's HeadsCommand --closed output must match real `hg heads --closed` verbatim for combo " + combo);

        // --- HeadsCommand --topo: pure topological leaves, ignoring branch/closed entirely. c2 has
        // a child (c3) so it's excluded even though feature is closed; c1, c3, c4 have no children
        // anywhere and are all leaves.
        String realHeadsTopoOut = HgTestUtils.hg(repoDir, "heads", "--topo", "--template", "{node}\n");
        List<String> realHeadsTopo = List.of(realHeadsTopoOut.lines().filter(s -> !s.isBlank()).toArray(String[]::new));
        List<String> hg4jHeadsTopo = new HeadsCommand(repo).setTopo(true).call();
        assertEquals(realHeadsTopo, hg4jHeadsTopo,
                "hg4j's HeadsCommand --topo output must match real `hg heads --topo` verbatim for combo " + combo);

        // --- HeadsCommand <branch>: filtered to one branch's own heads. ---
        String realHeadsSubOut = HgTestUtils.hg(repoDir, "heads", "sub", "--template", "{node}\n");
        List<String> realHeadsSub = List.of(realHeadsSubOut.lines().filter(s -> !s.isBlank()).toArray(String[]::new));
        List<String> hg4jHeadsSub = new HeadsCommand(repo).setBranch("sub").call();
        assertEquals(List.of(c4Hex), hg4jHeadsSub, "combo " + combo);
        assertEquals(realHeadsSub, hg4jHeadsSub, "combo " + combo);

        String realHeadsFeatureClosedOut = HgTestUtils.hg(repoDir, "heads", "feature", "--closed", "--template", "{node}\n");
        List<String> realHeadsFeatureClosed = List.of(realHeadsFeatureClosedOut.lines().filter(s -> !s.isBlank()).toArray(String[]::new));
        List<String> hg4jHeadsFeatureClosed = new HeadsCommand(repo).setBranch("feature").setIncludeClosed(true).call();
        assertEquals(List.of(c3Hex), hg4jHeadsFeatureClosed, "combo " + combo);
        assertEquals(realHeadsFeatureClosed, hg4jHeadsFeatureClosed, "combo " + combo);

        // `hg heads feature` (no --closed) aborts on real hg ("no open branch heads found") since
        // feature's only head is closed -- documented as this porcelain API returning an empty
        // list instead of throwing (see HeadsCommand#setBranch's javadoc).
        List<String> hg4jHeadsFeatureOpenOnly = new HeadsCommand(repo).setBranch("feature").call();
        assertEquals(List.of(), hg4jHeadsFeatureOpenOnly,
                "a branch with no open heads must yield an empty list (not throw) for combo " + combo);

        // --- ParentsCommand: single parent in the common case. ---
        HgTestUtils.hg(repoDir, "update", c4Hex);
        assertEquals(List.of(c4Hex), new ParentsCommand(repo).call(),
                "ParentsCommand must report the sole checked-out parent for combo " + combo);

        // --- ParentsCommand: two parents during an uncommitted merge. ---
        HgTestUtils.hg(repoDir, "merge", c2Hex);
        String realParentsOut = HgTestUtils.hg(repoDir, "parents", "--template", "{node}\n");
        List<String> realParents = List.of(realParentsOut.lines().filter(s -> !s.isBlank()).toArray(String[]::new));
        List<String> hg4jParents = new ParentsCommand(repo).call();
        assertEquals(List.of(c4Hex, c2Hex), hg4jParents,
                "ParentsCommand must report both parents, p1 then p2, during a merge for combo " + combo);
        assertEquals(realParents, hg4jParents,
                "hg4j's ParentsCommand must match real `hg parents` verbatim during a merge for combo " + combo);
    }
}
