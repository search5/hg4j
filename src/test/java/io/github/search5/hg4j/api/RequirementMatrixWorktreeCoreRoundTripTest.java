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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.Map;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixMergeCoreRoundTripTest} for the
 * reused pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to {@link
 * WorktreeCommand} across the native 6-combo grid (changelog family x treemanifest, dirstate fixed
 * at v1 -- v2 needs Docker, see {@code RequirementMatrixWorktreeDockerRoundTripTest}).
 *
 * <p>{@link WorktreeCommand} never appends a revlog revision on its own (see its class javadoc) --
 * so unlike most other requirement-matrix suites in this package, no subprocess helper is strictly
 * required for correctness; {@link RequirementMatrixWorktreeHelperMain} is used purely for pattern
 * parity.
 *
 * <p>One scenario, across every combo, verified live against real hg 7.2's own {@code share}
 * extension (2026-09-05, {@code --config extensions.share=}): a two-commit main repository (a root
 * file plus a nested-subdirectory file, exercising treemanifest) is shared via hg4j's {@link
 * WorktreeCommand} -- the new worktree must come out actually checked out to the shared store's tip
 * (see {@link WorktreeCommand}'s own javadoc for the real functional gap this fixes: it used to
 * leave the new worktree with zero files), its {@code .hg/requires} must carry the same "shared"
 * marker line real hg's own share always adds, and real hg itself, invoked directly against the
 * hg4j-created worktree directory, must see a clean, correctly-parented, fully populated working
 * copy sharing the exact same store as the main repository.
 */
@Tag("interop")
public class RequirementMatrixWorktreeCoreRoundTripTest {

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
    public void hg4jWorktreeSharesAndChecksOutMatchingRealHgAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File mainDir = initWithCombo(tempDir, combo, "worktree-main");
        Path mainRoot = mainDir.toPath();

        Files.writeString(mainRoot.resolve("root.txt"), "root content\n");
        Files.createDirectories(mainRoot.resolve("sub"));
        Files.writeString(mainRoot.resolve("sub/nested.txt"), "nested content\n");
        HgTestUtils.hg(mainDir, "add");
        HgTestUtils.hg(mainDir, "commit", "-u", "dev", "-m", "c0");
        String tipHex = HgTestUtils.hg(mainDir, "log", "-r", ".", "--template", "{node}");

        HgRepository mainRepo = new HgRepository(mainDir);
        File worktreeDir = tempDir.resolve("worktree-" + combo.label().replace("/", "-")).toFile();
        HgRepository worktreeRepo = new WorktreeCommand(mainRepo).setNewWorktreeDir(worktreeDir).call();

        // The worktree must be an ACTUAL checkout, not the empty stub the old implementation left.
        assertEquals("root content\n", Files.readString(worktreeDir.toPath().resolve("root.txt")),
                "worktree must be checked out to the shared store's tip for combo " + combo);
        assertEquals("nested content\n", Files.readString(worktreeDir.toPath().resolve("sub/nested.txt")),
                "worktree checkout must include nested-subdirectory files for combo " + combo);

        // .hg/requires must carry the real-hg "shared" marker alongside whatever main had.
        String mainRequires = Files.readString(mainDir.toPath().resolve(".hg/requires"), StandardCharsets.UTF_8);
        String worktreeRequires = Files.readString(worktreeDir.toPath().resolve(".hg/requires"), StandardCharsets.UTF_8);
        assertTrue(worktreeRequires.contains("shared"), "worktree requires must carry the shared marker for combo " + combo + ": " + worktreeRequires);
        for (String line : mainRequires.split("\n")) {
            if (!line.isBlank()) {
                assertTrue(worktreeRequires.contains(line), "worktree requires must preserve main's own requires lines for combo " + combo);
            }
        }

        // Real hg itself, run directly against the hg4j-created worktree directory, must see a
        // clean, correctly-parented, fully populated working copy sharing the main store.
        assertEquals(tipHex, HgTestUtils.hg(worktreeDir, "log", "-r", ".", "--template", "{node}"),
                "real hg reading the worktree must agree it is checked out to the main repo's tip for combo " + combo);
        assertEquals("", HgTestUtils.hg(worktreeDir, "status"), "worktree must be a clean working copy for combo " + combo);
        assertEquals(mainDir.getCanonicalPath() + "/.hg",
                Files.readString(worktreeDir.toPath().resolve(".hg/sharedpath"), StandardCharsets.UTF_8),
                "sharedpath must point back at the main repository's .hg directory for combo " + combo);
    }
}
