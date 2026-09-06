package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
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
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixMergeCoreRoundTripTest}, whose
 * conflicting-merge scenario this reuses as the setup step, and backlog #39 / {@code
 * exhaustive-interop-matrix-plan.md} §1) to {@link ResolveCommand} across the native 6-combo grid
 * (changelog family x treemanifest, dirstate fixed at v1 -- v2 needs Docker, see {@code
 * RequirementMatrixResolveDockerRoundTripTest}).
 *
 * <p>{@link ResolveCommand} is the read/manage counterpart of {@link MergeCommand}'s conflict
 * bookkeeping ({@code .hg/merge/state2}): a real conflicting merge is triggered first (identical
 * setup to {@link RequirementMatrixMergeCoreRoundTripTest#hg4jConflictingMergeAcrossCombo}), then
 * this exercises the full {@code --list}/{@code --mark}/{@code --unmark} lifecycle through {@link
 * ResolveCommand} itself (not {@link MergeCommand}'s own inline resolve path), cross-checking
 * every state transition against real hg's own {@code hg resolve --list} at each step, before
 * finally resolving and committing.
 */
@Tag("interop")
public class RequirementMatrixResolveCoreRoundTripTest {

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
    public void hg4jResolveListMarkUnmarkLifecycleAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "resolve");

        Files.writeString(repoDir.toPath().resolve("conflict.txt"), "line1\nline2\nline3\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");

        Files.writeString(repoDir.toPath().resolve("conflict.txt"), "line1\nTARGET\nline3\n");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1 target");
        String targetHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "update", "0");
        Files.writeString(repoDir.toPath().resolve("conflict.txt"), "line1\nSOURCE\nline3\n");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c2 source");
        String sourceHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "update", targetHex);

        HgRepository repo = new HgRepository(repoDir);
        MergeCommand.MergeResult result = new MergeCommand(repo)
                .setNodeId(NodeIdUtil.fromHex(sourceHex))
                .call();
        assertTrue(result.isConflicted(), "editing the same line on both branches must conflict for combo " + combo);
        assertEquals(List.of("conflict.txt"), result.getConflicts());

        // Before any resolution: hg4j's ResolveCommand#call() (list=true) must agree with real
        // hg's own `hg resolve --list` that conflict.txt is unresolved.
        Map<String, Boolean> beforeAny = new ResolveCommand(repo).list(true).call();
        assertEquals(Map.of("conflict.txt", false), beforeAny,
                "ResolveCommand must report conflict.txt as unresolved before any mark for combo " + combo);
        assertEquals("U conflict.txt", HgTestUtils.hg(repoDir, "resolve", "--list"),
                "real hg's own `hg resolve --list` must agree for combo " + combo);

        // Mark resolved via hg4j's ResolveCommand and cross-check with real hg.
        Files.writeString(repoDir.toPath().resolve("conflict.txt"), "line1\nRESOLVED\nline3\n");
        Map<String, Boolean> afterMark = new ResolveCommand(repo).setFile("conflict.txt").markResolved(true).call();
        assertEquals(Map.of("conflict.txt", true), afterMark);
        assertEquals("R conflict.txt", HgTestUtils.hg(repoDir, "resolve", "--list"),
                "real hg must see conflict.txt as resolved once hg4j's ResolveCommand marks it for combo " + combo);

        // Unmark it again via ResolveCommand#markUnresolved -- real hg's own `--unmark` round trip.
        Map<String, Boolean> afterUnmark = new ResolveCommand(repo).setFile("conflict.txt").markUnresolved(true).call();
        assertEquals(Map.of("conflict.txt", false), afterUnmark);
        assertEquals("U conflict.txt", HgTestUtils.hg(repoDir, "resolve", "--list"),
                "real hg must see conflict.txt as unresolved again once hg4j's ResolveCommand unmarks it for combo " + combo);

        // Finally mark resolved (again) and finish the commit, matching
        // RequirementMatrixMergeCoreRoundTripTest's own post-commit assertions.
        Map<String, Boolean> finalMark = new ResolveCommand(repo).setFile("conflict.txt").markResolved(true).call();
        assertEquals(Map.of("conflict.txt", true), finalMark);

        byte[] mergeNode = new CommitCommand(repo).setAuthor("dev").setMessage("merge with conflict resolution").call();
        String mergeHex = NodeIdUtil.toHex(mergeNode);

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.toLowerCase().contains("integrity error") || verify.toLowerCase().contains("error:"),
                "real hg verify must find no integrity errors for combo " + combo + ": " + verify);

        assertEquals("line1\nRESOLVED\nline3", HgTestUtils.hg(repoDir, "cat", "-r", mergeHex, "conflict.txt").trim());

        assertFalse(new File(repoDir, ".hg/merge").exists(),
                "real hg CLI reading hg4j's result must see .hg/merge fully cleaned up after the merge commit for combo "
                        + combo);
        assertEquals("", HgTestUtils.hg(repoDir, "resolve", "--list"),
                "no unresolved/resolved entries should remain once the merge is committed for combo " + combo);

        assertEquals("", HgTestUtils.hg(repoDir, "status"),
                "working copy must be clean right after the merge commit for combo " + combo);
    }
}
