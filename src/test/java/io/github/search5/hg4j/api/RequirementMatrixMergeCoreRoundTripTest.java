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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.Map;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixCoreRoundTripTest} for the reused
 * pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to {@link
 * MergeCommand} across the native 6-combo grid (changelog family x treemanifest, dirstate fixed
 * at v1 -- v2 needs Docker, see {@code RequirementMatrixMergeDockerRoundTripTest}).
 *
 * <p>Two independent scenarios are covered, both across every combo:
 * <ul>
 *   <li>{@link #hg4jCleanMergeAcrossCombo}: a genuinely diverging two-branch history that merges
 *   cleanly (no file touched by both branches) -- real hg builds it, hg4j's {@link MergeCommand}
 *   performs the merge and {@link CommitCommand} finalizes it, real hg re-reads the result.</li>
 *   <li>{@link #hg4jConflictingMergeAcrossCombo}: both branches edit the same line of the same
 *   file, so the merge must genuinely conflict -- verifies hg4j's conflict markers, {@code .hg/
 *   merge/state2} bookkeeping (read back live by real hg's own {@code hg resolve --list}), and
 *   that resolving (via hg4j's {@link ResolveCommand}) + committing produces a result real hg
 *   accepts as fully resolved (verify clean, {@code hg resolve --list} empty, {@code .hg/merge}
 *   itself gone -- matching real hg's own post-merge-commit {@code mergestate.reset()}).</li>
 * </ul>
 */
@Tag("interop")
public class RequirementMatrixMergeCoreRoundTripTest {

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
    public void hg4jCleanMergeAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "merge-clean");

        Files.writeString(repoDir.toPath().resolve("base.txt"), "base\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");

        Files.writeString(repoDir.toPath().resolve("target.txt"), "on-target\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1 target");
        String targetHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "update", "0");
        Files.writeString(repoDir.toPath().resolve("source.txt"), "on-source\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c2 source");
        String sourceHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        // Working copy must be checked out at the target revision before merging the source in.
        HgTestUtils.hg(repoDir, "update", targetHex);

        HgRepository repo = new HgRepository(repoDir);
        MergeCommand.MergeResult result = new MergeCommand(repo)
                .setNodeId(NodeIdUtil.fromHex(sourceHex))
                .call();
        assertFalse(result.isConflicted(), "a merge of two non-overlapping branches must not conflict for combo " + combo);
        assertTrue(result.getConflicts().isEmpty());

        byte[] mergeNode = new CommitCommand(repo).setAuthor("dev").setMessage("merge").call();
        String mergeHex = NodeIdUtil.toHex(mergeNode);

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.toLowerCase().contains("integrity error") || verify.toLowerCase().contains("error:"),
                "real hg verify must find no integrity errors after merge+commit for combo " + combo + ": " + verify);

        String parents = HgTestUtils.hg(repoDir, "log", "-r", mergeHex, "--template", "{p1node} {p2node}");
        assertEquals(targetHex + " " + sourceHex, parents,
                "the merge commit's parents must be exactly target then source for combo " + combo);

        assertEquals("base", HgTestUtils.hg(repoDir, "cat", "-r", mergeHex, "base.txt").trim());
        assertEquals("on-target", HgTestUtils.hg(repoDir, "cat", "-r", mergeHex, "target.txt").trim());
        assertEquals("on-source", HgTestUtils.hg(repoDir, "cat", "-r", mergeHex, "source.txt").trim());

        String status = HgTestUtils.hg(repoDir, "status");
        assertEquals("", status, "working copy must be clean right after the merge commit for combo " + combo + ": " + status);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jConflictingMergeAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "merge-conflict");

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

        String workingContent = Files.readString(repoDir.toPath().resolve("conflict.txt"));
        assertTrue(workingContent.contains("<<<<<<<") && workingContent.contains("=======") && workingContent.contains(">>>>>>>"),
                "the conflicted file must carry real conflict markers for combo " + combo + ": " + workingContent);

        // Real hg must recognize the merge state hg4j just wrote (.hg/merge/state2) as an
        // unresolved conflict on "conflict.txt", exactly like it would after its own `hg merge`.
        String resolveListBefore = HgTestUtils.hg(repoDir, "resolve", "--list");
        assertEquals("U conflict.txt", resolveListBefore,
                "real hg's own `hg resolve --list` must see conflict.txt as unresolved for combo " + combo);

        // Resolve by picking a merged result, then mark resolved via hg4j's ResolveCommand
        // (the read/manage counterpart of MergeCommand, sharing the same on-disk state).
        Files.writeString(repoDir.toPath().resolve("conflict.txt"), "line1\nRESOLVED\nline3\n");
        new ResolveCommand(repo).setFile("conflict.txt").markResolved(true).call();

        String resolveListAfter = HgTestUtils.hg(repoDir, "resolve", "--list");
        assertEquals("R conflict.txt", resolveListAfter,
                "real hg must see conflict.txt as resolved once hg4j's ResolveCommand marks it for combo " + combo);

        byte[] mergeNode = new CommitCommand(repo).setAuthor("dev").setMessage("merge with conflict resolution").call();
        String mergeHex = NodeIdUtil.toHex(mergeNode);

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.toLowerCase().contains("integrity error") || verify.toLowerCase().contains("error:"),
                "real hg verify must find no integrity errors after the conflicted merge+commit for combo " + combo + ": " + verify);

        String parents = HgTestUtils.hg(repoDir, "log", "-r", mergeHex, "--template", "{p1node} {p2node}");
        assertEquals(targetHex + " " + sourceHex, parents,
                "the merge commit's parents must be exactly target then source for combo " + combo);

        assertEquals("line1\nRESOLVED\nline3", HgTestUtils.hg(repoDir, "cat", "-r", mergeHex, "conflict.txt").trim());

        // Real hg fully clears its merge bookkeeping once a merge is finalized by a successful
        // commit -- `.hg/merge` itself disappears, and `hg resolve --list` reports nothing left.
        assertFalse(new File(repoDir, ".hg/merge").exists(),
                "real hg CLI reading hg4j's result must see .hg/merge fully cleaned up after the merge commit for combo "
                        + combo);
        String resolveListAfterCommit = HgTestUtils.hg(repoDir, "resolve", "--list");
        assertEquals("", resolveListAfterCommit,
                "no unresolved/resolved entries should remain once the merge is committed for combo " + combo);

        String status = HgTestUtils.hg(repoDir, "status");
        assertEquals("", status, "working copy must be clean right after the merge commit for combo " + combo + ": " + status);
    }
}
