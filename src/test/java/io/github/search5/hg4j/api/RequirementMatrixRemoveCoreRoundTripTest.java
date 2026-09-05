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
 * {@code exhaustive-interop-matrix-plan.md} §1) to {@link RemoveCommand} across the native
 * 6-combo grid (changelog family x treemanifest, dirstate fixed at v1 -- v2 needs Docker, see
 * {@code RequirementMatrixRemoveDockerRoundTripTest}).
 *
 * <p>Wave 4 (2026-09-05): unlike {@link ForgetCommand}, {@link RemoveCommand} both marks the
 * dirstate entry 'r' AND physically deletes the working copy file. This scenario removes a
 * root-level file and a nested file, then commits and verifies real hg reflects the deletion in
 * the resulting manifest/changelog (matching {@link RequirementMatrixAddCoreRoundTripTest}'s
 * multi-root-segment shape for the Docker dirstate-v2 half of this pair).
 */
@Tag("interop")
public class RequirementMatrixRemoveCoreRoundTripTest {

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
    public void hg4jRemoveAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "remove");

        Files.writeString(repoDir.toPath().resolve("top.txt"), "top content\n");
        Files.createDirectories(repoDir.toPath().resolve("adir"));
        Files.writeString(repoDir.toPath().resolve("adir/nested.txt"), "nested content\n");
        Files.writeString(repoDir.toPath().resolve("keep.txt"), "keep content\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0 base");

        HgRepository repo = new HgRepository(repoDir);
        assertTrue(new RemoveCommand(repo).setFile("top.txt").call());
        assertTrue(new RemoveCommand(repo).setFile("adir/nested.txt").call());

        // RemoveCommand must physically delete the working copy file, unlike ForgetCommand.
        assertFalse(Files.exists(repoDir.toPath().resolve("top.txt")), "remove must delete top.txt from disk");
        assertFalse(Files.exists(repoDir.toPath().resolve("adir/nested.txt")), "remove must delete adir/nested.txt from disk");

        String status = HgTestUtils.hg(repoDir, "status");
        assertTrue(status.lines().anyMatch(l -> l.equals("R top.txt")),
                "real hg status must see top.txt as removed for combo " + combo + ": " + status);
        assertTrue(status.lines().anyMatch(l -> l.equals("R adir/nested.txt")),
                "real hg status must see adir/nested.txt as removed for combo " + combo + ": " + status);

        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1 remove");

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.toLowerCase().contains("integrity error"),
                "real hg verify must find no integrity errors after remove+commit for combo " + combo + ": " + verify);

        String manifest = HgTestUtils.hg(repoDir, "manifest", "-r", "tip");
        assertFalse(manifest.lines().anyMatch(l -> l.equals("top.txt")), "manifest must not contain top.txt: " + manifest);
        assertFalse(manifest.lines().anyMatch(l -> l.equals("adir/nested.txt")), "manifest must not contain adir/nested.txt: " + manifest);
        assertTrue(manifest.lines().anyMatch(l -> l.equals("keep.txt")), "manifest: " + manifest);

        // The prior revision's content must still be retrievable by hash even though it's gone
        // from tip -- removal must not corrupt the filelog history.
        assertEquals("top content", HgTestUtils.hg(repoDir, "cat", "-r", "0", "top.txt").trim());
        assertEquals("nested content", HgTestUtils.hg(repoDir, "cat", "-r", "0", "adir/nested.txt").trim());
        assertEquals("keep content", HgTestUtils.hg(repoDir, "cat", "-r", "tip", "keep.txt").trim());

        String statusFile = HgTestUtils.hg(repoDir, "status", "--change", "tip");
        assertTrue(statusFile.lines().anyMatch(l -> l.equals("R top.txt")), "changeset status: " + statusFile);
        assertTrue(statusFile.lines().anyMatch(l -> l.equals("R adir/nested.txt")), "changeset status: " + statusFile);
    }
}
