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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixCoreRoundTripTest}, backlog #39 /
 * {@code exhaustive-interop-matrix-plan.md} §1) to {@link BranchCommand}/{@link BranchesCommand}
 * across the native 6-combo grid (changelog family x treemanifest, dirstate fixed at v1 -- v2
 * needs Docker, see {@code RequirementMatrixBranchDockerRoundTripTest}).
 *
 * <p>Wave 4 (2026-09-05): {@link BranchCommand}/{@link BranchesCommand} already have thorough
 * real-hg-CLI behavioural coverage from backlog 23 ({@link BranchRealHgInteropTest} --
 * branch-name recognition, the (active, rev, name) ordering rule, closed-branch hiding, internal
 * forks, {@code hg heads <branch>}), but every one of those scenarios runs against a single,
 * default-format repository -- none of it varies changelog version or manifest shape. This class
 * deliberately does NOT re-derive those same behavioural assertions; it focuses purely on the one
 * dimension {@link BranchRealHgInteropTest} never covered: does a named branch created by {@link
 * BranchCommand} (writes {@code .hg/branch}), committed on via {@link CommitCommand} (writes the
 * branch name into the changelog extra data), closed via {@code setCloseBranch(true)}, and read
 * back via {@link BranchesCommand} survive intact across every changelog-v1/v2(+sidedata) x
 * flat/tree-manifest combination.
 */
@Tag("interop")
public class RequirementMatrixBranchCoreRoundTripTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    /** Parses a real {@code hg branches} line, e.g. "feature   3:abcdef012345 (inactive)". */
    private static final Pattern BRANCHES_LINE =
            Pattern.compile("^(\\S+)\\s+(\\d+):([0-9a-f]+)(?:\\s+\\((\\S+)\\))?$");

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
    public void hg4jBranchAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "branch");

        Files.writeString(repoDir.toPath().resolve("base.txt"), "base\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0 base");

        HgRepository repo = new HgRepository(repoDir);

        // 1. hg4j creates a named branch and commits on it.
        assertEquals("feature", new BranchCommand(repo).setBranchName("feature").call());
        Files.writeString(repoDir.toPath().resolve("f1.txt"), "feature work\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("dev").setMessage("c1-feature").call();

        String nativeBranchOfTip = HgTestUtils.hg(repoDir, "log", "-r", "tip", "--template", "{branch}");
        assertEquals("feature", nativeBranchOfTip, "real hg must see the hg4j-committed branch name for combo " + combo);

        String verify1 = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify1.toLowerCase().contains("integrity error"),
                "real hg verify after branch commit must find no integrity errors for combo " + combo + ": " + verify1);

        // 2. hg4j's BranchesCommand must agree with real hg's `hg branches` (both branches open).
        List<BranchesCommand.BranchHead> open = new BranchesCommand(repo).call();
        assertEquals(2, open.size(), "combo " + combo + ": " + open);
        String nativeBranchesOpen = HgTestUtils.hg(repoDir, "branches");
        assertBranchesMatch(nativeBranchesOpen, open, combo);

        // 3. Close the feature branch via hg4j's CommitCommand.setCloseBranch(true).
        new CommitCommand(repo).setAuthor("dev").setMessage("close-feature").setCloseBranch(true).call();

        String verify2 = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify2.toLowerCase().contains("integrity error"),
                "real hg verify after closing the branch must find no integrity errors for combo " + combo + ": " + verify2);

        // 4. Default listing hides the fully-closed branch in both hg4j and real hg.
        List<BranchesCommand.BranchHead> defaultAfterClose = new BranchesCommand(repo).call();
        assertEquals(1, defaultAfterClose.size(), "combo " + combo + ": " + defaultAfterClose);
        assertEquals("default", defaultAfterClose.get(0).getBranch());
        String nativeDefaultAfterClose = HgTestUtils.hg(repoDir, "branches");
        assertFalse(nativeDefaultAfterClose.contains("feature"),
                "real hg's default 'hg branches' must also hide the fully-closed branch for combo " + combo + ": " + nativeDefaultAfterClose);

        // 5. --closed / includeClosed=true both show it, marked closed, and agree on ordering.
        List<BranchesCommand.BranchHead> closedListing = new BranchesCommand(repo).setIncludeClosed(true).call();
        assertEquals(2, closedListing.size(), "combo " + combo + ": " + closedListing);
        String nativeClosed = HgTestUtils.hg(repoDir, "branches", "--closed");
        assertTrue(nativeClosed.contains("(closed)"), "real hg 'hg branches --closed' must mark it closed for combo " + combo + ": " + nativeClosed);
        assertBranchesMatch(nativeClosed, closedListing, combo);
    }

    private static void assertBranchesMatch(String nativeOut, List<BranchesCommand.BranchHead> hg4jBranches, RequirementCombo combo) {
        List<String> hg4jOrder = hg4jBranches.stream().map(BranchesCommand.BranchHead::getBranch).toList();
        List<String> nativeOrder = new ArrayList<>();
        Map<String, Boolean> nativeClosed = new HashMap<>();
        for (String line : nativeOut.split("\n")) {
            if (line.isBlank()) continue;
            Matcher m = BRANCHES_LINE.matcher(line.trim());
            assertTrue(m.matches(), "unparsable hg branches line for combo " + combo + ": [" + line + "]");
            nativeOrder.add(m.group(1));
            nativeClosed.put(m.group(1), "closed".equals(m.group(4)));
        }
        assertEquals(nativeOrder, hg4jOrder, "hg4j branch listing order must match real hg's for combo " + combo);
        for (BranchesCommand.BranchHead h : hg4jBranches) {
            assertEquals(nativeClosed.get(h.getBranch()), h.isClosed(),
                    "closed flag mismatch for branch " + h.getBranch() + " in combo " + combo);
        }
    }
}
