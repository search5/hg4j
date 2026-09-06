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
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixBackoutCoreRoundTripTest} for the
 * reused pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to {@link
 * StatusCommand} across the native 6-combo grid (changelog family x treemanifest, dirstate fixed
 * at v1 -- v2 needs Docker, see {@code RequirementMatrixStatusDockerRoundTripTest}) as a
 * first-class porcelain command in its own right -- {@link StatusCommand}'s logic was already
 * incidentally touched by the Resolve/Backout/Revert wave's dirstate-v2 "possibly dirty" sentinel
 * fix and by {@code RequirementMatrixCoreRoundTripTest}'s clean-after-commit sanity check, but
 * neither of those exercised its full six-way status classification (added/modified/removed
 * [both explicit {@code hg remove} <b>and</b> a plain missing-from-disk file]/untracked/clean)
 * against real hg's own {@code hg status -A} across every format combination, including a nested
 * treemanifest path.
 *
 * <p>This is a pure read (dirstate + manifest + working directory, never a write) so no hg4j write
 * step / {@code HelperMain} subprocess is needed.
 */
@Tag("interop")
public class RequirementMatrixStatusCoreRoundTripTest {

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

    /**
     * One commit (five tracked files, one nested under a directory for treemanifest coverage)
     * followed by every kind of working-copy change real hg's {@code hg status -A} distinguishes:
     * a plain edit (M), a newly {@code hg add}ed file (A), an explicitly {@code hg remove}d file
     * (R), a file deleted from disk WITHOUT {@code hg remove} (!, "missing"), an untracked file
     * (?), and two untouched files (C, "clean") -- one of them the nested one.
     *
     * <p>hg4j's {@link Status} has no separate "missing" bucket the way real hg's CLI rendering
     * does -- both R and ! map into {@link Status#getRemoved()} (an intentional, pre-existing
     * design simplification, not a bug: verified against {@code StatusCommand}'s own existing unit
     * tests), so this test folds real hg's R+! together before comparing.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void statusAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "status");
        Path root = repoDir.toPath();

        Files.createDirectories(root.resolve("dir"));
        for (String f : List.of("a.txt", "c.txt", "d.txt", "e.txt")) {
            Files.writeString(root.resolve(f), "orig");
        }
        Files.writeString(root.resolve("dir").resolve("b.txt"), "orig");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");

        Files.writeString(root.resolve("a.txt"), "orig\nmodified");
        Files.writeString(root.resolve("new.txt"), "new");
        HgTestUtils.hg(repoDir, "add", "new.txt");
        HgTestUtils.hg(repoDir, "remove", "c.txt");
        Files.delete(root.resolve("d.txt"));
        Files.writeString(root.resolve("untracked.txt"), "untracked");

        String realStatus = HgTestUtils.hg(repoDir, "status", "-A");
        Map<String, String> byPath = new HashMap<>();
        for (String line : realStatus.split("\n")) {
            if (line.isBlank()) continue;
            byPath.put(line.substring(2), line.substring(0, 1));
        }
        // Sanity: this is exactly the scenario the assertions below assume.
        assertEquals(Map.of(
                "a.txt", "M", "new.txt", "A", "c.txt", "R", "d.txt", "!",
                "untracked.txt", "?", "dir/b.txt", "C", "e.txt", "C"), byPath,
                "sanity: real hg's own status, combo " + combo);

        HgRepository repo = new HgRepository(repoDir);
        Status status = new StatusCommand(repo).call();

        assertEquals(Set.of("a.txt"), status.getModified(), "modified, combo " + combo);
        assertEquals(Set.of("new.txt"), status.getAdded(), "added, combo " + combo);
        assertEquals(Set.of("c.txt", "d.txt"), status.getRemoved(), "removed (R+!), combo " + combo);
        assertEquals(Set.of("untracked.txt"), status.getUntracked(), "untracked, combo " + combo);
        assertEquals(Set.of("dir/b.txt", "e.txt"), status.getClean(), "clean, combo " + combo);
    }
}
