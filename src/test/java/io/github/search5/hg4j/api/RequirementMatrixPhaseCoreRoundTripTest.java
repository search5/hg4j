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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixCoreRoundTripTest}, backlog #39 /
 * {@code exhaustive-interop-matrix-plan.md} §1) to {@link PhaseCommand} across the native 6-combo
 * grid (changelog family x treemanifest, dirstate fixed at v1 -- v2 needs Docker, see
 * {@code RequirementMatrixPhaseDockerRoundTripTest}).
 *
 * <p>Wave 4 (2026-09-05): {@link PhaseCommand} had no dedicated real-hg-CLI interop coverage of
 * its own before this class (only same-process unit coverage in {@code PhaseCommandCoverageTest}
 * and the storage-layer {@code PhaseRootsTest}/{@code PhaseRootsCoverageTest}). This class
 * verifies the strongest possible signal -- byte-for-byte identity of {@code
 * .hg/store/phaseroots} -- by driving <em>two</em> otherwise-identical repositories (same combo,
 * same commits at fixed dates so the changeset hashes are byte-identical) through the same
 * sequence of phase moves: one via {@link PhaseCommand}, the other via the real {@code hg phase}
 * CLI, then diffing {@code phaseroots} after every step. Covers: advancing towards public (no
 * force), retracting towards secret (requires {@link PhaseCommand#setForce}), advancing back
 * (the reverse direction real hg allows without force), retracting again (force required again),
 * and the blocked-without-force case leaving the file untouched.
 */
@Tag("interop")
public class RequirementMatrixPhaseCoreRoundTripTest {

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

    /** Three linear commits (rev0/1/2) at a fixed date so the resulting node hashes are
     * byte-identical between the hg4j-driven and reference repositories. */
    private static void createLinearCommits(File repoDir) throws Exception {
        for (int i = 0; i < 3; i++) {
            Files.writeString(repoDir.toPath().resolve("f" + i + ".txt"), "content-" + i + "\n");
            HgTestUtils.hg(repoDir, "add");
            HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-d", "0 0", "-m", "c" + i);
        }
    }

    private static File phaseRootsFile(File repoDir) throws IOException {
        return new File(new HgRepository(repoDir).getStoreDir(), "phaseroots");
    }

    private static int nativePhaseOf(File repoDir, int rev) throws Exception {
        String out = HgTestUtils.hg(repoDir, "phase", "-r", String.valueOf(rev));
        // Real hg prints "<rev>: <phasename>", e.g. "0: draft".
        String name = out.substring(out.indexOf(':') + 1).trim();
        return switch (name) {
            case "public" -> 0;
            case "draft" -> 1;
            case "secret" -> 2;
            default -> throw new AssertionError("unrecognized hg phase output: " + out);
        };
    }

    private static void assertPhaseRootsMatch(File repoA, File repoB, RequirementCombo combo, String when) throws Exception {
        byte[] a = Files.readAllBytes(phaseRootsFile(repoA).toPath());
        byte[] b = Files.readAllBytes(phaseRootsFile(repoB).toPath());
        assertArrayEquals(b, a, "combo " + combo + ": phaseroots must match real hg byte-for-byte " + when
                + "\n  hg4j : " + new String(a, StandardCharsets.UTF_8)
                + "\n  real : " + new String(b, StandardCharsets.UTF_8));
    }

    private static void assertPhaseQueriesMatch(HgRepository repoA, File repoB, RequirementCombo combo) throws Exception {
        for (int rev = 0; rev < 3; rev++) {
            int hg4jPhase = new PhaseCommand(repoA).setRevision(String.valueOf(rev)).call();
            int nativePhase = nativePhaseOf(repoB, rev);
            assertEquals(nativePhase, hg4jPhase, "combo " + combo + ": phase query mismatch for rev " + rev);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jPhaseAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoA = initWithCombo(tempDir, combo, "phase-hg4j");
        File repoB = initWithCombo(tempDir, combo, "phase-ref");
        createLinearCommits(repoA);
        createLinearCommits(repoB);

        // Sanity: fixed-date commits must produce byte-identical hashes in both repos, or the
        // phaseroots comparison below would be meaningless.
        for (int rev = 0; rev < 3; rev++) {
            String hexA = HgTestUtils.hg(repoA, "log", "-r", String.valueOf(rev), "--template", "{node}");
            String hexB = HgTestUtils.hg(repoB, "log", "-r", String.valueOf(rev), "--template", "{node}");
            assertEquals(hexB, hexA, "combo " + combo + ": rev " + rev + " hash must match between the two repos");
        }
        assertPhaseRootsMatch(repoA, repoB, combo, "immediately after the initial linear commits");

        HgRepository repo = new HgRepository(repoA);

        // 1. Advance rev1 to public -- unconditional (lower phase number), affects rev1 and its
        // ancestor rev0 too.
        new PhaseCommand(repo).setRevision("1").setPhase(0).call();
        HgTestUtils.hg(repoB, "phase", "-r", "1", "--public");
        assertPhaseRootsMatch(repoA, repoB, combo, "after advancing rev1 to public");
        assertPhaseQueriesMatch(repo, repoB, combo);

        // 2. Retract rev2 to secret -- requires force (draft -> secret is a higher phase number).
        new PhaseCommand(repo).setRevision("2").setPhase(2).setForce(true).call();
        HgTestUtils.hg(repoB, "phase", "-r", "2", "--secret", "--force");
        assertPhaseRootsMatch(repoA, repoB, combo, "after retracting rev2 to secret with --force");
        assertPhaseQueriesMatch(repo, repoB, combo);

        // 3. Advance rev2 back to draft -- the reverse direction, unconditional (secret -> draft
        // is a lower phase number, no force needed).
        new PhaseCommand(repo).setRevision("2").setPhase(1).call();
        HgTestUtils.hg(repoB, "phase", "-r", "2", "--draft");
        assertPhaseRootsMatch(repoA, repoB, combo, "after advancing rev2 back to draft");
        assertPhaseQueriesMatch(repo, repoB, combo);

        // 4. Retract rev0 back to draft -- requires force again (public -> draft is a higher
        // phase number): the reverse of step 1, exercising --force on an ancestor/root revision.
        new PhaseCommand(repo).setRevision("0").setPhase(1).setForce(true).call();
        HgTestUtils.hg(repoB, "phase", "-r", "0", "--draft", "--force");
        assertPhaseRootsMatch(repoA, repoB, combo, "after retracting rev0 back to draft with --force");
        assertPhaseQueriesMatch(repo, repoB, combo);

        // 5. Blocked without force: moving rev0 from draft to secret without --force must be
        // rejected on both sides, leaving phaseroots byte-for-byte unchanged.
        byte[] beforeA = Files.readAllBytes(phaseRootsFile(repoA).toPath());
        byte[] beforeB = Files.readAllBytes(phaseRootsFile(repoB).toPath());

        IOException ex = assertThrows(IOException.class,
                () -> new PhaseCommand(new HgRepository(repoA)).setRevision("0").setPhase(2).call());
        assertTrue(ex.getMessage().toLowerCase().contains("force"), "combo " + combo + ": " + ex.getMessage());

        boolean nativeRejected = false;
        try {
            HgTestUtils.hg(repoB, "phase", "-r", "0", "--secret");
        } catch (AssertionError expected) {
            nativeRejected = true;
        }
        assertTrue(nativeRejected, "combo " + combo + ": real hg must also reject moving rev0 to secret without --force");

        assertArrayEquals(beforeA, Files.readAllBytes(phaseRootsFile(repoA).toPath()),
                "combo " + combo + ": a rejected hg4j phase move must not touch phaseroots");
        assertArrayEquals(beforeB, Files.readAllBytes(phaseRootsFile(repoB).toPath()),
                "combo " + combo + ": a rejected real-hg phase move must not touch phaseroots");
        assertPhaseRootsMatch(repoA, repoB, combo, "after the blocked (no-op) move attempt");

        // 6. Both repositories must still verify clean -- phase moves never touch revlog content.
        String verifyA = HgTestUtils.hg(repoA, "verify");
        assertFalse(verifyA.toLowerCase().contains("integrity error"), "combo " + combo + ": " + verifyA);
        String verifyB = HgTestUtils.hg(repoB, "verify");
        assertFalse(verifyB.toLowerCase().contains("integrity error"), "combo " + combo + ": " + verifyB);
    }
}
