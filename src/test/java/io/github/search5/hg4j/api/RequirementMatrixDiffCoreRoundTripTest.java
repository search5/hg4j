package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.NodeId;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.search5.hg4j.util.NodeIdUtil;
import java.util.ArrayList;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixBackoutCoreRoundTripTest} for the
 * reused pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to {@link
 * DiffCommand} across the native 6-combo grid (changelog family x treemanifest, dirstate fixed at
 * v1 -- v2 needs Docker, see {@code RequirementMatrixDiffDockerRoundTripTest}) as a first-class
 * porcelain command in its own right.
 *
 * <p>{@link DiffCommand}'s underlying unified-diff hunk generation ({@code generateUnifiedDiff})
 * is already exhaustively byte-verified across all 36 combos indirectly, via {@link ExportCommand}
 * (which calls straight into {@link DiffCommand}) in {@code RequirementMatrixExportImportCoreRoundTripTest}
 * -- that test caught and fixed the phantom-trailing-newline bug (backlog #39) by round-tripping
 * hg4j's exported patch through real {@code hg import} and comparing the resulting commit's node
 * hash byte-for-byte. This dedicated trio instead focuses on what that test does NOT cover: {@link
 * DiffCommand}'s own public contract across arbitrary (non-parent-adjacent) revision pairs, its
 * {@code int}- vs {@link NodeId}-based revision setters, ADD/MODIFY/DELETE classification
 * (including inside a nested treemanifest directory, exercising the treemanifest-aware {@code
 * ManifestTreeIterator} path DiffCommand uses), and cross-checking that classification against
 * real hg's own {@code hg status --rev A --rev B}. One MODIFY case per combo is also round-tripped
 * through real {@code hg import --no-commit} to confirm the emitted patch text is genuinely
 * apply-compatible, not just structurally plausible. Pure read -- no hg4j write step / {@code
 * HelperMain} subprocess needed.
 */
@Tag("interop")
public class RequirementMatrixDiffCoreRoundTripTest {

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
    public void diffClassificationAndApplyAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "diff");
        Path root = repoDir.toPath();

        Files.createDirectories(root.resolve("dir"));
        Files.writeString(root.resolve("a.txt"), "one\ntwo\nthree\n");
        Files.writeString(root.resolve("dir").resolve("b.txt"), "alpha\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");
        String hex0 = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        Files.writeString(root.resolve("a.txt"), "one\nTWO\nthree\n");
        Files.delete(root.resolve("dir").resolve("b.txt"));
        Files.writeString(root.resolve("dir").resolve("c.txt"), "gamma\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "remove", "dir/b.txt");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1");
        String hex1 = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        String realStatus = HgTestUtils.hg(repoDir, "status", "--rev", "0", "--rev", "1");
        Map<String, String> expected = new HashMap<>();
        for (String line : realStatus.split("\n")) {
            expected.put(line.substring(2), line.substring(0, 1));
        }
        assertEquals(Map.of("a.txt", "M", "dir/b.txt", "R", "dir/c.txt", "A"), expected,
                "sanity: real hg's own status --rev 0 --rev 1, combo " + combo);

        HgRepository repo = new HgRepository(repoDir);

        // int-based revision setters (0-based revision numbers, matching real hg's own -r 0/-r 1).
        List<DiffCommand.DiffEntry> diffsByInt = new DiffCommand(repo).setOldRevision(0).setNewRevision(1).call();
        Map<String, DiffCommand.ChangeType> byPathInt = new HashMap<>();
        for (DiffCommand.DiffEntry e : diffsByInt) {
            byPathInt.put(e.getPath(), e.getChangeType());
        }
        assertEquals(Map.of(
                "a.txt", DiffCommand.ChangeType.MODIFY,
                "dir/b.txt", DiffCommand.ChangeType.DELETE,
                "dir/c.txt", DiffCommand.ChangeType.ADD), byPathInt,
                "int-revision classification must match real hg's own status, combo " + combo);

        // NodeId-based revision setters must agree exactly with the int-based ones.
        List<DiffCommand.DiffEntry> diffsByNode = new DiffCommand(repo)
                .setOldRevision(new NodeId(NodeIdUtil.fromHex(hex0)))
                .setNewRevision(new NodeId(NodeIdUtil.fromHex(hex1)))
                .call();
        Map<String, DiffCommand.ChangeType> byPathNode = new HashMap<>();
        for (DiffCommand.DiffEntry e : diffsByNode) {
            byPathNode.put(e.getPath(), e.getChangeType());
        }
        assertEquals(byPathInt, byPathNode, "NodeId- and int-based revision setters must agree, combo " + combo);

        // Default oldRevision (unset -> newRevision's parent) must also agree.
        List<DiffCommand.DiffEntry> diffsDefaultOld = new DiffCommand(repo).setNewRevision(1).call();
        Map<String, DiffCommand.ChangeType> byPathDefaultOld = new HashMap<>();
        for (DiffCommand.DiffEntry e : diffsDefaultOld) {
            byPathDefaultOld.put(e.getPath(), e.getChangeType());
        }
        assertEquals(byPathInt, byPathDefaultOld, "default oldRevision=parent must agree, combo " + combo);

        // Real-hg-apply round trip for the MODIFY case: hg4j's diff text applied on top of hex0's
        // checkout via real `hg import --no-commit` must reproduce hex1's exact a.txt content.
        String modifyDiff = diffsByInt.stream()
                .filter(e -> e.getPath().equals("a.txt")).findFirst().orElseThrow().getDiffContent();
        File patchFile = tempDir.resolve("diff-" + combo.label().replace("/", "-") + ".patch").toFile();
        Files.writeString(patchFile.toPath(), modifyDiff);

        HgTestUtils.hg(repoDir, "update", "-r", "0", "-C");
        HgTestUtils.hg(repoDir, "import", "--no-commit", patchFile.getAbsolutePath());
        String appliedContent = Files.readString(root.resolve("a.txt"));
        String expectedContent = HgTestUtils.hg(repoDir, "cat", "-r", hex1, "a.txt") + "\n";
        assertEquals(expectedContent, appliedContent,
                "hg4j's MODIFY diff applied via real `hg import` must reproduce the target revision's content, combo " + combo);
    }
}
