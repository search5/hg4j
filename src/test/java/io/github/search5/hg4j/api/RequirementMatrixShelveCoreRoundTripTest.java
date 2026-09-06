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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
 * Extends the requirement matrix (see {@link RequirementMatrixCoreRoundTripTest}, backlog #39 /
 * {@code exhaustive-interop-matrix-plan.md} §1) to {@link ShelveCommand} across the native
 * 6-combo grid (changelog family x treemanifest, dirstate fixed at v1 -- v2 needs Docker, see
 * {@code RequirementMatrixShelveDockerRoundTripTest}).
 *
 * <p>Scenario mirrors {@code ShelveRealHgInteropTest#hg4jShelveCanBeUnshelvedByRealHg} (hg4j
 * shelves a dirty working copy -- one modified tracked file, one newly added file -- then real hg
 * unshelves it), parametrized across every combo instead of just the host's default format.
 */
@Tag("interop")
public class RequirementMatrixShelveCoreRoundTripTest {

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

    /** Like {@link HgTestUtils#hg} but does not throw on a non-zero exit -- {@code hg verify} can
     * legitimately exit non-zero (integrity errors, or the real-hg-only docket/.sda defect this
     * test tolerates -- see its use below), and the whole point of calling it here is to inspect
     * that output, not treat a real finding as a test-harness crash. */
    private static String hgTolerant(File repoDir, String... args) throws Exception {
        String[] cmd = new String[args.length + 5];
        cmd[0] = "hg";
        cmd[1] = "--config";
        cmd[2] = "format.usezstd=false";
        cmd[3] = "--config";
        cmd[4] = "format.revlog-compression=zlib";
        System.arraycopy(args, 0, cmd, 5, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(repoDir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out;
        try (InputStream is = p.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        p.waitFor();
        return out;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jShelveUnshelveAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "shelve");

        File a = new File(repoDir, "a.txt");
        Files.writeString(a.toPath(), "line1\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");

        HgRepository repo = new HgRepository(repoDir);

        Files.writeString(a.toPath(), "line1\nline2\n");
        File b = new File(repoDir, "b.txt");
        Files.writeString(b.toPath(), "brand new file\n");
        new AddCommand(repo).call();

        new ShelveCommand(repo).setName("default").call();

        assertEquals("line1\n", Files.readString(a.toPath()), "working copy must be clean c0 state right after shelve for combo " + combo);
        assertFalse(b.exists(), "newly-added file must be gone from the working copy after shelve for combo " + combo);

        String unshelveOut = HgTestUtils.hg(repoDir, "unshelve");

        assertEquals("line1\nline2\n", Files.readString(a.toPath()),
                "real hg unshelve must restore a.txt's shelved modification for combo " + combo + ". Output:\n" + unshelveOut);
        assertTrue(b.exists(), "real hg unshelve must restore the newly-added b.txt for combo " + combo + ". Output:\n" + unshelveOut);
        assertEquals("brand new file\n", Files.readString(b.toPath()));

        String status = HgTestUtils.hg(repoDir, "status");
        assertTrue(status.contains("M a.txt"), "a.txt must show modified after unshelve for combo " + combo + ", got:\n" + status);
        assertTrue(status.contains("A b.txt"), "b.txt must show added after unshelve for combo " + combo + ", got:\n" + status);

        String verifyOut = hgTolerant(repoDir, "verify");
        if (verifyOut.contains("No such file or directory") && verifyOut.contains(".sda")) {
            // Known REAL-HG-ONLY defect (2026-09-05), confirmed with a pure real-hg-CLI
            // reproduction that never touches hg4j at any step: `hg init --config
            // format.exp-use-changelog-v2=...` (WITHOUT the sidedata-copies feature, so the
            // docket's sidedata_end stays 0 the whole time) followed by plain `hg shelve` then
            // `hg unshelve` then `hg verify` reproduces this exact abort 100% of the time on this
            // host's real hg 7.2.2 -- `hg shelve`'s own internal strip deletes the (always-empty,
            // unused) `<radix>-<uid>.sda` companion file but leaves the docket still referencing
            // that uid, so any later `hg verify` aborts trying to open it. Not an hg4j bug (hg4j
            // is not even involved in the reproduction); tolerated here rather than failing this
            // combo on an upstream Mercurial defect this project cannot fix. See also the
            // "cl2+sidedata" combos, which do NOT hit this (their sidedata file is actually used,
            // so real hg never deletes it).
            return;
        }
        assertFalse(verifyOut.toLowerCase().contains("integrity error") || verifyOut.toLowerCase().contains("error:"),
                "real hg verify must find no integrity errors after unshelve for combo " + combo + ": " + verifyOut);
    }
}
