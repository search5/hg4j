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
import java.util.ArrayList;
import java.util.Map;
import java.util.Random;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixBackoutCoreRoundTripTest} for the
 * reused pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to {@link
 * GcCommand} across the native 6-combo grid (changelog family x treemanifest, dirstate fixed at
 * v1 -- v2 needs Docker, see {@code RequirementMatrixGcDockerRoundTripTest}).
 *
 * <p>Unlike the other two commands in this wave, {@link GcCommand} has no literal real-hg
 * equivalent ({@code hg} has no built-in {@code gc}/compaction subcommand) -- so the round trip
 * here is "real hg creates the repository and commits real content, hg4j's {@link GcCommand}
 * recompacts the store in place, real hg must still be able to read every revision back
 * byte-for-byte and {@code hg verify} cleanly" rather than a literal command-for-command
 * comparison. Three real hg4j bugs were found and fixed via this expansion (2026-09-05, see {@link
 * GcCommand}'s own updated javadoc for the full story):
 * <ol>
 *   <li>fncache rebuild incorrectly listed the two fixed root revlogs
 *   ({@code 00changelog.i}/{@code 00manifest.i}), which real hg's own fncache never contains --
 *   this test asserts on fncache's exact contents to catch a regression.</li>
 *   <li>a v2/docket revlog (changelog-v2/general-v2) was silently corrupted by being rewritten as
 *   a classic v1 revlog -- covered by the changelog-v2 combos here and the general-v2 Docker
 *   combos.</li>
 *   <li>a legitimately-split (non-inline) filelog got silently re-inlined -- this test commits a
 *   file over the 128KB inline threshold via real hg specifically to exercise this.</li>
 * </ol>
 */
@Tag("interop")
public class RequirementMatrixGcCoreRoundTripTest {

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

    private static String bigContent() {
        // Comfortably over real hg's 128KB (131072 byte) inline-to-split threshold, so real hg's
        // OWN commit genuinely splits this filelog into a separate .d file -- exercising
        // GcCommand's inline-ness-preservation fix (see its own javadoc). The threshold is judged
        // against the COMPRESSED on-disk size, not the raw text length, so this must be
        // incompressible (a deterministic PRNG stream, not a repeating pattern -- an early
        // repeating-pattern version of this method compressed down small enough that real hg
        // never actually split it, silently defeating the whole scenario, caught live while first
        // writing this test).
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        Random rnd = new Random(42);
        StringBuilder sb = new StringBuilder(220_000);
        for (int i = 0; i < 220_000; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jGcAfterRealHgCommitsAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "gc");

        Files.writeString(repoDir.toPath().resolve("a.txt"), "root content\n");
        Files.createDirectories(repoDir.toPath().resolve("sub"));
        Files.writeString(repoDir.toPath().resolve("sub/nested.txt"), "nested content\n");
        String big = bigContent();
        Files.writeString(repoDir.toPath().resolve("big.txt"), big);
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");

        Files.writeString(repoDir.toPath().resolve("a.txt"), "root content v2\n");
        Files.writeString(repoDir.toPath().resolve("sub/nested.txt"), "nested content v2\n");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1");
        String tipHex = HgTestUtils.hg(repoDir, "log", "-r", "tip", "--template", "{node}");

        File bigFilelogIdx = CommitCommand.getFilelogIndex(new HgRepository(repoDir).getStoreDir(), "big.txt");
        File bigFilelogDat = new File(bigFilelogIdx.getPath().substring(0, bigFilelogIdx.getPath().length() - 2) + ".d");
        assertTrue(bigFilelogDat.exists(), "real hg must have split big.txt's filelog into a separate .d file for combo " + combo);

        HgRepository repo = new HgRepository(repoDir);
        String report = new GcCommand(repo).call();
        assertTrue(report.contains("GC / Compaction complete"), "combo " + combo + ": " + report);
        repo.clearRevlogCache();

        assertTrue(bigFilelogDat.exists(),
                "big.txt's filelog must remain split (non-inline) after GC for combo " + combo);

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.toLowerCase().contains("integrity error") || verify.toLowerCase().contains("error:"),
                "real hg verify must find no integrity errors after GC for combo " + combo + ": " + verify);

        assertEquals("root content v2", HgTestUtils.hg(repoDir, "cat", "-r", "tip", "a.txt").trim(), "combo " + combo);
        assertEquals("nested content v2", HgTestUtils.hg(repoDir, "cat", "-r", "tip", "sub/nested.txt").trim(), "combo " + combo);
        assertEquals(big, HgTestUtils.hg(repoDir, "cat", "-r", "tip", "big.txt"), "combo " + combo);
        assertEquals("root content", HgTestUtils.hg(repoDir, "cat", "-r", "0", "a.txt").trim(),
                "c0's own revision of a.txt must be unaffected by GC for combo " + combo);

        String revs = HgTestUtils.hg(repoDir, "log", "--template", "{rev}\\n").trim().replace("\n", ",");
        assertEquals("1,0", revs, "GC must not add/remove any revision for combo " + combo);

        String finalTipHex = HgTestUtils.hg(repoDir, "log", "-r", "tip", "--template", "{node}");
        assertEquals(tipHex, finalTipHex, "GC must not change any node hash for combo " + combo);

        // fncache must list every real per-file/per-directory revlog GC touched, and must NEVER
        // list the two fixed root revlogs (real hg's own fncache never does -- verified live,
        // see GcCommand's javadoc).
        File fncacheFile = new File(repo.getStoreDir(), "fncache");
        assertTrue(fncacheFile.exists(), "combo " + combo);
        List<String> fncacheLines = Files.readAllLines(fncacheFile.toPath());
        assertTrue(fncacheLines.contains("data/a.txt.i"), "combo " + combo + ": " + fncacheLines);
        assertTrue(fncacheLines.contains("data/big.txt.i"), "combo " + combo + ": " + fncacheLines);
        assertTrue(fncacheLines.contains("data/sub/nested.txt.i"), "combo " + combo + ": " + fncacheLines);
        assertFalse(fncacheLines.contains("00changelog.i"), "combo " + combo + ": " + fncacheLines);
        assertFalse(fncacheLines.contains("00manifest.i"), "combo " + combo + ": " + fncacheLines);
        if (combo.label().contains("treemanifest")) {
            assertTrue(fncacheLines.contains("meta/sub/00manifest.i"), "combo " + combo + ": " + fncacheLines);
        }

        assertEquals("", HgTestUtils.hg(repoDir, "status"), "GC must not touch the working copy for combo " + combo);
    }
}
