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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixCoreRoundTripTest}, backlog #39 /
 * {@code exhaustive-interop-matrix-plan.md} §1) to {@link AddremoveCommand} across the native
 * 6-combo grid (changelog family x treemanifest, dirstate fixed at v1 -- v2 needs Docker, see
 * {@code RequirementMatrixAddremoveDockerRoundTripTest}).
 *
 * <p>Wave 4 (2026-09-05): {@link AddremoveCommand} auto-detects BOTH untracked new files (which
 * it hands off to {@link AddCommand}) and missing tracked files (handed off to {@link
 * RemoveCommand} with force) in a single pass. This scenario creates a genuinely new untracked
 * file, physically deletes an already-tracked file WITHOUT running {@code hg remove} first (the
 * exact working-copy state {@code addremove} exists to reconcile), and verifies real hg sees both
 * halves land correctly after commit -- plus a nested-directory case on each side for the same
 * multi-root-segment shape {@link RequirementMatrixAddCoreRoundTripTest} uses.
 */
@Tag("interop")
public class RequirementMatrixAddremoveCoreRoundTripTest {

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
    public void hg4jAddremoveAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "addremove");

        Files.writeString(repoDir.toPath().resolve("gone.txt"), "will be deleted\n");
        Files.createDirectories(repoDir.toPath().resolve("adir"));
        Files.writeString(repoDir.toPath().resolve("adir/gone-nested.txt"), "nested will be deleted\n");
        Files.writeString(repoDir.toPath().resolve("keep.txt"), "keep content\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0 base");

        // Reconcile the working copy: one new untracked file at the root, one new untracked
        // nested file, and two already-tracked files physically deleted WITHOUT `hg remove`.
        Files.writeString(repoDir.toPath().resolve("new.txt"), "brand new\n");
        Files.createDirectories(repoDir.toPath().resolve("bdir"));
        Files.writeString(repoDir.toPath().resolve("bdir/new-nested.txt"), "brand new nested\n");
        Files.delete(repoDir.toPath().resolve("gone.txt"));
        Files.delete(repoDir.toPath().resolve("adir/gone-nested.txt"));

        HgRepository repo = new HgRepository(repoDir);
        List<String> affected = new AddremoveCommand(repo).call();

        assertTrue(affected.contains("A new.txt"), "affected list must report new.txt as added: " + affected);
        assertTrue(affected.contains("A bdir/new-nested.txt"), "affected list must report bdir/new-nested.txt as added: " + affected);
        assertTrue(affected.contains("R gone.txt"), "affected list must report gone.txt as removed: " + affected);
        assertTrue(affected.contains("R adir/gone-nested.txt"), "affected list must report adir/gone-nested.txt as removed: " + affected);
        // keep.txt must be untouched -- neither added nor removed.
        assertFalse(affected.stream().anyMatch(l -> l.endsWith("keep.txt")), "affected list must not mention keep.txt: " + affected);

        String status = HgTestUtils.hg(repoDir, "status");
        assertTrue(status.lines().anyMatch(l -> l.equals("A new.txt")), "real hg status: " + status);
        assertTrue(status.lines().anyMatch(l -> l.equals("A bdir/new-nested.txt")), "real hg status: " + status);
        assertTrue(status.lines().anyMatch(l -> l.equals("R gone.txt")), "real hg status: " + status);
        assertTrue(status.lines().anyMatch(l -> l.equals("R adir/gone-nested.txt")), "real hg status: " + status);

        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1 addremove");

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.toLowerCase().contains("integrity error"),
                "real hg verify must find no integrity errors after addremove+commit for combo " + combo + ": " + verify);

        String manifest = HgTestUtils.hg(repoDir, "manifest", "-r", "tip");
        assertTrue(manifest.lines().anyMatch(l -> l.equals("new.txt")), "manifest: " + manifest);
        assertTrue(manifest.lines().anyMatch(l -> l.equals("bdir/new-nested.txt")), "manifest: " + manifest);
        assertTrue(manifest.lines().anyMatch(l -> l.equals("keep.txt")), "manifest: " + manifest);
        assertFalse(manifest.lines().anyMatch(l -> l.equals("gone.txt")), "manifest must not contain gone.txt: " + manifest);
        assertFalse(manifest.lines().anyMatch(l -> l.equals("adir/gone-nested.txt")), "manifest must not contain adir/gone-nested.txt: " + manifest);

        assertEquals("brand new", HgTestUtils.hg(repoDir, "cat", "-r", "tip", "new.txt").trim());
        assertEquals("brand new nested", HgTestUtils.hg(repoDir, "cat", "-r", "tip", "bdir/new-nested.txt").trim());
        assertEquals("keep content", HgTestUtils.hg(repoDir, "cat", "-r", "tip", "keep.txt").trim());
        assertEquals("will be deleted", HgTestUtils.hg(repoDir, "cat", "-r", "0", "gone.txt").trim());
    }
}
