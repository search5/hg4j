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
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixBackoutCoreRoundTripTest} for the
 * reused pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to {@link
 * VerifyCommand} across the native 6-combo grid (changelog family x treemanifest, dirstate fixed
 * at v1 -- v2 needs Docker, see {@code RequirementMatrixVerifyDockerRoundTripTest}).
 *
 * <p>Unlike every other command extended so far, {@link VerifyCommand} never writes anything --
 * it is a pure read/hash-check over a repository built entirely by real {@code hg}. Three
 * scenarios are covered per combo, all verified live against real {@code hg} 7.2 (2026-09-05):
 * <ul>
 *   <li>{@link #hg4jVerifiesAHealthyRealHgRepositoryAcrossCombo}: a real-hg-built, multi-revision,
 *   nested-directory repository (nesting matters for the treemanifest combos, which split the
 *   manifest into a root revlog plus one submanifest revlog per subdirectory under {@code meta/})
 *   must verify with zero errors, matching real hg's own {@code hg verify} exit 0.</li>
 *   <li>{@link #hg4jDetectsFilelogCorruptionAcrossCombo}: corrupting a real-hg-written filelog's
 *   stored node id (independently of dirstate/changelog/manifest/storage-extension shape) must be
 *   caught by hg4j's {@link VerifyCommand}, cross-checked against real hg's own {@code hg verify}
 *   also flagging the same repository as broken (so this is asserted to be genuine corruption, not
 *   an hg4j false positive).</li>
 *   <li>{@link #hg4jDetectsTreemanifestSubmanifestCorruptionAcrossCombo} (treemanifest combos
 *   only): corrupting a submanifest revlog under {@code meta/<dir>/00manifest.i} must also be
 *   caught -- this is the exact real hg4j bug this wave fixed (see {@link VerifyCommand}'s own
 *   javadoc): before the fix, {@code VerifyCommand} never looked under {@code meta/} at all, so
 *   this scenario silently reported zero errors.</li>
 * </ul>
 */
@Tag("interop")
public class RequirementMatrixVerifyCoreRoundTripTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    record RequirementCombo(String label, List<String> initConfigArgs, boolean treemanifest) {
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
                out.add(new RequirementCombo(cl.getKey() + "/" + tm.getKey(), args, tm.getKey().equals("treemanifest")));
            }
        }
        return out.stream();
    }

    static Stream<RequirementCombo> treemanifestCombos() {
        return combos().filter(RequirementCombo::treemanifest);
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

    /** Builds a small nested-directory history (root file + two subdirectory levels, so
     * treemanifest combos get real submanifest revlogs under {@code meta/}) with two commits, so
     * every filelog has more than one revision to choose from. */
    private static void buildNestedHistory(File repoDir) throws Exception {
        Files.createDirectories(repoDir.toPath().resolve("sub/deep"));
        Files.writeString(repoDir.toPath().resolve("a.txt"), "root v1\n");
        Files.writeString(repoDir.toPath().resolve("sub/b.txt"), "sub v1\n");
        Files.writeString(repoDir.toPath().resolve("sub/deep/c.txt"), "deep v1\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");

        Files.writeString(repoDir.toPath().resolve("a.txt"), "root v2\n");
        Files.writeString(repoDir.toPath().resolve("sub/deep/c.txt"), "deep v2\n");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1");
    }

    private static void corruptNodeIdOfFirstRevision(File idxFile) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(idxFile, "rw")) {
            raf.seek(32);
            raf.write(new byte[]{9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9});
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jVerifiesAHealthyRealHgRepositoryAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "verify-healthy");
        buildNestedHistory(repoDir);

        String realVerify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(realVerify.toLowerCase().contains("error"),
                "sanity: real hg itself must verify this repository clean for combo " + combo + ": " + realVerify);

        HgRepository repo = new HgRepository(repoDir);
        List<String> errors = new VerifyCommand(repo).call();
        assertTrue(errors.isEmpty(), "hg4j's VerifyCommand must report zero errors on a healthy real-hg-built "
                + "repository for combo " + combo + ", but got: " + errors);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jDetectsFilelogCorruptionAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "verify-filelog-corrupt");
        buildNestedHistory(repoDir);

        File flIdx = CommitCommand.getFilelogIndex(new File(repoDir, ".hg/store"), "sub/b.txt");
        assertTrue(flIdx.exists(), "sub/b.txt's filelog index must exist for combo " + combo);
        corruptNodeIdOfFirstRevision(flIdx);

        String realVerify;
        try {
            realVerify = HgTestUtils.hg(repoDir, "verify");
        } catch (AssertionError e) {
            realVerify = e.getMessage();
        }
        assertTrue(realVerify.toLowerCase().contains("error") || realVerify.toLowerCase().contains("integrity"),
                "sanity: real hg itself must flag this corrupted filelog for combo " + combo + ": " + realVerify);

        HgRepository repo = new HgRepository(repoDir);
        List<String> errors = new VerifyCommand(repo).call();
        assertFalse(errors.isEmpty(), "hg4j's VerifyCommand must detect the corrupted filelog for combo " + combo);
        assertTrue(errors.stream().anyMatch(e -> e.contains("b.txt")),
                "the error must name the corrupted file for combo " + combo + ", got: " + errors);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("treemanifestCombos")
    public void hg4jDetectsTreemanifestSubmanifestCorruptionAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "verify-submanifest-corrupt");
        buildNestedHistory(repoDir);

        File subManifestIdx = new File(repoDir, ".hg/store/meta/sub/deep/00manifest.i");
        assertTrue(subManifestIdx.exists(), "sub/deep's submanifest revlog must exist for treemanifest combo " + combo);
        corruptNodeIdOfFirstRevision(subManifestIdx);

        String realVerify;
        try {
            realVerify = HgTestUtils.hg(repoDir, "verify");
        } catch (AssertionError e) {
            realVerify = e.getMessage();
        }
        assertTrue(realVerify.toLowerCase().contains("error") || realVerify.toLowerCase().contains("integrity"),
                "sanity: real hg itself must flag this corrupted submanifest for combo " + combo + ": " + realVerify);

        HgRepository repo = new HgRepository(repoDir);
        List<String> errors = new VerifyCommand(repo).call();
        assertFalse(errors.isEmpty(), "hg4j's VerifyCommand must detect the corrupted treemanifest submanifest for "
                + "combo " + combo + " -- before the backlog #39 wave 5 fix this silently reported zero errors "
                + "because VerifyCommand never looked under meta/ at all");
        assertTrue(errors.stream().anyMatch(e -> e.contains("meta/sub/deep/00manifest.i")),
                "the error must name the corrupted submanifest path for combo " + combo + ", got: " + errors);
    }
}
