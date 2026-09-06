package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.phase.PhaseRoots;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.Map;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixBackoutCoreRoundTripTest} for the
 * reused pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to
 * {@link IdentifyCommand} and {@link SummaryCommand} across the native 6-combo grid (changelog
 * family x treemanifest, dirstate fixed at v1 -- v2 needs Docker, see
 * {@code RequirementMatrixIdentifyDockerRoundTripTest}). These two are grouped into one trio,
 * matching the precedent set by wave 4's combined branch/branches trio -- both are one-shot
 * working-copy "snapshot" queries built from the same underlying pieces (parents, branch,
 * bookmarks, tags, phase), so one scenario naturally exercises both.
 *
 * <p>No {@code HelperMain} subprocess is used (see {@link RequirementMatrixHeadsCoreRoundTripTest}'s
 * javadoc for why: both commands are pure readers over a repository built exclusively via the real
 * {@code hg} CLI, so hg4j's own write path -- the only thing those subprocesses exist to
 * isolate -- never runs in this JVM).
 *
 * <p>While designing this test against real hg 7.2.2 (2026-09-05), two genuine {@link
 * IdentifyCommand} bugs were found and fixed (see that class's own javadoc for the full writeup):
 * the default branch used to always print as a literal {@code "default"} suffix instead of being
 * omitted (real hg only shows non-default branches, in parentheses), and tags/bookmarks were
 * looked up only against the working copy's first parent, so during a merge a tag or bookmark
 * pointing solely at {@code p2} was dropped even though real hg still shows it.
 */
@Tag("interop")
public class RequirementMatrixIdentifyCoreRoundTripTest {

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
        List<RequirementCombo> out = new ArrayList<>();
        for (var cl : List.of(Map.entry("cl1", CL_V1), Map.entry("cl2", CL_V2), Map.entry("cl2+sidedata", CL_V2_SIDEDATA))) {
            for (var tm : List.of(Map.entry("flatmanifest", TREEMANIFEST_OFF), Map.entry("treemanifest", TREEMANIFEST_ON))) {
                List<String> args = new ArrayList<>();
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
        List<String> args = new ArrayList<>();
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
    public void identifyAndSummaryMatchRealHgAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "identify");

        // c0: root, default branch.
        Files.writeString(repoDir.toPath().resolve("a.txt"), "a0\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");
        String c0Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        // c1: child of c0, default branch, then tagged "v1" (creates a tag commit on top).
        Files.writeString(repoDir.toPath().resolve("a.txt"), "a1\n");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1");
        String c1Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");
        HgTestUtils.hg(repoDir, "tag", "-u", "dev", "v1");
        String c1TagHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        // c2: child of c1tag, branch "feature", with an active bookmark "mark1".
        HgTestUtils.hg(repoDir, "branch", "feature");
        Files.writeString(repoDir.toPath().resolve("b.txt"), "b0\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c2");
        String c2Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");
        HgTestUtils.hg(repoDir, "bookmark", "mark1");

        HgRepository repo = new HgRepository(repoDir);

        // --- clean working copy at c2: tip, non-default branch, active bookmark. ---
        String realId1 = HgTestUtils.hg(repoDir, "identify");
        String hg4jId1 = new IdentifyCommand(repo).call();
        assertEquals(realId1, hg4jId1, "IdentifyCommand must match real `hg identify` verbatim for combo " + combo);
        assertEquals(c2Hex.substring(0, 12) + " (feature) tip mark1", hg4jId1, "combo " + combo);

        SummaryCommand.SummaryInfo summary1 = new SummaryCommand(repo).call();
        assertEquals(1, summary1.parents().size(), "combo " + combo);
        assertEquals(c2Hex, summary1.parents().get(0).node(), "combo " + combo);
        assertEquals("c2", summary1.parents().get(0).description(), "combo " + combo);
        assertEquals("feature", summary1.branch(), "combo " + combo);
        assertEquals("mark1", summary1.activeBookmark(), "combo " + combo);
        assertEquals(0, summary1.modified(), "combo " + combo);
        assertEquals(0, summary1.added(), "combo " + combo);
        assertEquals(0, summary1.removed(), "combo " + combo);
        assertFalse(summary1.mergeInProgress(), "combo " + combo);
        int realPhase1 = new PhaseCommand(repo).setRevision(c2Hex).call();
        assertEquals(PhaseRoots.Phase.fromValue(realPhase1), summary1.currentPhase(), "combo " + combo);
        assertEquals(PhaseRoots.Phase.DRAFT, summary1.currentPhase(), "combo " + combo);

        // --- dirty (modified tracked file): trailing "+", SummaryCommand sees 1 modified. ---
        Files.writeString(repoDir.toPath().resolve("b.txt"), "b0-modified\n");
        String realIdDirty = HgTestUtils.hg(repoDir, "identify");
        String hg4jIdDirty = new IdentifyCommand(repo).call();
        assertEquals(realIdDirty, hg4jIdDirty, "combo " + combo);
        assertEquals(c2Hex.substring(0, 12) + "+ (feature) tip mark1", hg4jIdDirty, "combo " + combo);
        SummaryCommand.SummaryInfo summaryDirty = new SummaryCommand(repo).call();
        assertEquals(1, summaryDirty.modified(), "combo " + combo);
        HgTestUtils.hg(repoDir, "revert", "--no-backup", "-a");

        // --- dirty (added file): trailing "+", SummaryCommand sees 1 added. ---
        Files.writeString(repoDir.toPath().resolve("n.txt"), "new\n");
        HgTestUtils.hg(repoDir, "add", "n.txt");
        String realIdAdded = HgTestUtils.hg(repoDir, "identify");
        String hg4jIdAdded = new IdentifyCommand(repo).call();
        assertEquals(realIdAdded, hg4jIdAdded, "combo " + combo);
        assertEquals(c2Hex.substring(0, 12) + "+ (feature) tip mark1", hg4jIdAdded, "combo " + combo);
        SummaryCommand.SummaryInfo summaryAdded = new SummaryCommand(repo).call();
        assertEquals(1, summaryAdded.added(), "combo " + combo);
        HgTestUtils.hg(repoDir, "forget", "n.txt");
        Files.delete(repoDir.toPath().resolve("n.txt"));

        // --- untracked file: must NOT set the dirty marker (verified live against real hg). ---
        Files.writeString(repoDir.toPath().resolve("untracked.txt"), "untracked\n");
        String realIdUntracked = HgTestUtils.hg(repoDir, "identify");
        String hg4jIdUntracked = new IdentifyCommand(repo).call();
        assertEquals(realIdUntracked, hg4jIdUntracked, "combo " + combo);
        assertEquals(c2Hex.substring(0, 12) + " (feature) tip mark1", hg4jIdUntracked, "combo " + combo);
        Files.delete(repoDir.toPath().resolve("untracked.txt"));

        // --- setRevision(-r c0): default branch (omitted), not tip, no tags. ---
        String realIdC0 = HgTestUtils.hg(repoDir, "identify", "-r", c0Hex);
        String hg4jIdC0 = new IdentifyCommand(repo).setRevision(c0Hex).call();
        assertEquals(realIdC0, hg4jIdC0, "combo " + combo);
        assertEquals(c0Hex.substring(0, 12), hg4jIdC0, "combo " + combo);

        // --- merge scenario: branch "other" forked off c1tag, merged with feature's tip (c2).
        // p2 (c2) alone carries the bookmark "mark1" (p1, c3, is on branch "other" and is tip) --
        // both must show up in the aggregated tag/bookmark line (the bug fixed above).
        HgTestUtils.hg(repoDir, "update", c1TagHex);
        HgTestUtils.hg(repoDir, "branch", "other");
        Files.writeString(repoDir.toPath().resolve("o.txt"), "o0\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c3");
        String c3Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");
        HgTestUtils.hg(repoDir, "merge", c2Hex);

        // This HgRepository handle was constructed before c3 was committed by the real hg CLI --
        // for changelog-v2 (docket-based) stores the docket FILE's own size does not grow when a
        // revision is appended (only internal index_end/data_end offsets change), so a naive
        // size-based staleness check misses the growth. See HgRepository#refreshIfChangedOnDisk's
        // own javadoc for the full explanation of why this exists.
        repo.refreshIfChangedOnDisk();

        String realIdMerge = HgTestUtils.hg(repoDir, "identify");
        String hg4jIdMerge = new IdentifyCommand(repo).call();
        assertEquals(realIdMerge, hg4jIdMerge, "combo " + combo);
        assertEquals(c3Hex.substring(0, 12) + "+" + c2Hex.substring(0, 12) + "+ (other) tip mark1",
                hg4jIdMerge, "combo " + combo);

        SummaryCommand.SummaryInfo summaryMerge = new SummaryCommand(repo).call();
        assertEquals(2, summaryMerge.parents().size(), "combo " + combo);
        assertEquals(c3Hex, summaryMerge.parents().get(0).node(), "combo " + combo);
        assertEquals("c3", summaryMerge.parents().get(0).description(), "combo " + combo);
        assertEquals(c2Hex, summaryMerge.parents().get(1).node(), "combo " + combo);
        assertEquals("c2", summaryMerge.parents().get(1).description(), "combo " + combo);
        assertEquals("other", summaryMerge.branch(), "combo " + combo);
        assertNull(summaryMerge.activeBookmark(),
                "moving away from mark1's own node deactivates it (real hg's \"(leaving bookmark)\") for combo "
                        + combo);
        assertTrue(summaryMerge.mergeInProgress(), "combo " + combo);
    }
}
