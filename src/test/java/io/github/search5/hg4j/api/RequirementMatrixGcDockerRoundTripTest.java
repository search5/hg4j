package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest} for
 * the full 30-combo design this reuses verbatim) applied to {@link GcCommand} -- the Docker-only
 * counterpart of {@link RequirementMatrixGcCoreRoundTripTest}'s native 6-combo scenario (see that
 * class's own javadoc for why this command's round trip is "real hg content survives GC
 * byte-for-byte" rather than a literal command-for-command comparison -- {@code hg} has no
 * built-in {@code gc}/compaction subcommand).
 *
 * <p>Each case gets its own fresh, short-lived container (never a class-shared one), matching
 * {@link RequirementMatrixMergeDockerRoundTripTest}/{@link RequirementMatrixStripDockerRoundTripTest}.
 * hg4j's own {@link GcCommand} write runs inline in this JVM, alongside the native rust-hg
 * subprocess calls this class uses for the real hg side.
 *
 * <p>This is the ONLY one of the three quarters that specifically covers {@code persistent-
 * nodemap}/{@code fileindex-v1}/{@code general-v2} for {@link GcCommand} -- exactly the combos
 * {@link GcCommand}'s own javadoc documents as needing special handling (v2/docket revlogs must
 * never be rewritten; fncache must never be written for a fileindex-v1 repository), so this test
 * additionally asserts on those specifically.
 */
@Tag("interop")
public class RequirementMatrixGcDockerRoundTripTest {

    @BeforeAll
    static void checkNativeHgRust() {
        Assumptions.assumeTrue(NativeHgRust.isAvailable(),
                "Native rust-enabled hg (run docker/hg-rust-7.2.4/build-native.sh) is not built. Skipping the whole class.");
    }

    private static String runHost(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out;
        try (InputStream is = p.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new AssertionError("host command " + Arrays.toString(cmd) + " failed with exit " + code + ": " + out);
        }
        return out;
    }


    /** Runs hg4j's {@link GcCommand} in a dedicated subprocess; returns its report line. */
    private static String gcInSubprocess(Path repoDir) throws Exception {
        HgRepository repo = new HgRepository(repoDir.toFile());
        return new GcCommand(repo).call();
    }

    /** One point in the Docker-only quarter of the requirement matrix -- identical generation to
     * {@link RequirementMatrixDockerRoundTripTest#combos()} (see {@link
     * RequirementMatrixStripDockerRoundTripTest} for why this is copied rather than shared). */
    record RequirementCombo(String label, List<String> initConfigArgs) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final List<String> DIRSTATE_V2 = List.of("format.use-dirstate-v2=yes");
    private static final List<String> CL_V1 = List.of();
    private static final List<String> CL_V2 = List.of("format.exp-use-changelog-v2=enable-unstable-format-and-corrupt-my-data");
    private static final List<String> CL_V2_SIDEDATA = List.of(
            "format.exp-use-changelog-v2=enable-unstable-format-and-corrupt-my-data",
            "format.exp-use-copies-side-data-changeset=yes");
    private static final List<String> PERSISTENT_NODEMAP = List.of("format.use-persistent-nodemap=true");
    private static final List<String> FILEINDEX_V1 = List.of("format.use-fileindex-v1=yes");
    private static final List<String> GENERAL_V2 = List.of("experimental.revlogv2=enable-unstable-format-and-corrupt-my-data");
    private static final List<String> TREEMANIFEST = List.of("experimental.treemanifest=1");

    static Stream<RequirementCombo> combos() {
        List<RequirementCombo> out = new ArrayList<>();
        List<Map.Entry<String, List<String>>> dirstates = List.of(
                Map.entry("dirstate1", List.<String>of()), Map.entry("dirstate2", DIRSTATE_V2));
        List<Map.Entry<String, List<String>>> changelogs = List.of(
                Map.entry("cl1", CL_V1), Map.entry("cl2", CL_V2),
                Map.entry("cl2+sidedata", CL_V2_SIDEDATA));

        for (var cl : changelogs) {
            for (var tm : List.of(Map.entry("flatmanifest", List.<String>of()),
                    Map.entry("treemanifest", TREEMANIFEST))) {
                List<String> args = new ArrayList<>();
                args.addAll(DIRSTATE_V2);
                args.addAll(cl.getValue());
                args.addAll(tm.getValue());
                out.add(new RequirementCombo("dirstate2/" + cl.getKey() + "/" + tm.getKey() + "/none", args));
            }
        }

        for (var dirstate : dirstates) {
            for (var cl : changelogs) {
                for (var tm : List.of(Map.entry("flatmanifest", List.<String>of()),
                        Map.entry("treemanifest", TREEMANIFEST))) {
                    List<String> args = new ArrayList<>();
                    args.addAll(dirstate.getValue());
                    args.addAll(cl.getValue());
                    args.addAll(tm.getValue());
                    args.addAll(PERSISTENT_NODEMAP);
                    out.add(new RequirementCombo(dirstate.getKey() + "/" + cl.getKey() + "/" + tm.getKey() + "/pnodemap", args));
                }
                List<String> fileindexArgs = new ArrayList<>();
                fileindexArgs.addAll(dirstate.getValue());
                fileindexArgs.addAll(cl.getValue());
                fileindexArgs.addAll(FILEINDEX_V1);
                out.add(new RequirementCombo(dirstate.getKey() + "/" + cl.getKey() + "/flatmanifest/fileindex-v1", fileindexArgs));
                List<String> generalV2Args = new ArrayList<>();
                generalV2Args.addAll(dirstate.getValue());
                generalV2Args.addAll(cl.getValue());
                generalV2Args.addAll(GENERAL_V2);
                out.add(new RequirementCombo(dirstate.getKey() + "/" + cl.getKey() + "/flatmanifest/general-v2", generalV2Args));
            }
        }
        return out.stream();
    }

    private static String bigContent() {
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
    public void hg4jGcAfterRealHgCommitsAcrossDockerCombo(RequirementCombo combo) throws Exception {
        NativeHgRust.withFreshWorkDir("hg4j-native-matrix", (workDir) -> {
            String repoRelPath = "repo";
            Path hostRepoDir = workDir.resolve(repoRelPath);
            Files.createDirectories(hostRepoDir);

            List<String> initArgs = new ArrayList<>(List.of("init", "."));
            for (String c : combo.initConfigArgs()) {
                initArgs.add("--config");
                initArgs.add(c);
            }
            NativeHgRust.hg(workDir, repoRelPath, initArgs.toArray(new String[0]));

            Files.writeString(hostRepoDir.resolve("a.txt"), "root content\n");
            Files.createDirectories(hostRepoDir.resolve("sub"));
            Files.writeString(hostRepoDir.resolve("sub/nested.txt"), "nested content\n");
            String big = bigContent();
            Files.writeString(hostRepoDir.resolve("big.txt"), big);
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c0");

            Files.writeString(hostRepoDir.resolve("a.txt"), "root content v2\n");
            Files.writeString(hostRepoDir.resolve("sub/nested.txt"), "nested content v2\n");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c1");
            String tipHex = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", "tip", "--template", "{node}");

            String report = gcInSubprocess(hostRepoDir);
            assertTrue(report.contains("GC / Compaction complete"), "combo " + combo + ": " + report);

            String verify = NativeHgRust.hg(workDir, repoRelPath, "verify");
            assertFalse(verify.toLowerCase().contains("integrity error") || verify.toLowerCase().contains("error:"),
                    "real hg verify must find no integrity errors after GC for combo " + combo + ": " + verify);

            assertEquals("root content v2", NativeHgRust.hg(workDir, repoRelPath, "cat", "-r", "tip", "a.txt"), "combo " + combo);
            assertEquals("nested content v2", NativeHgRust.hg(workDir, repoRelPath, "cat", "-r", "tip", "sub/nested.txt"), "combo " + combo);
            assertEquals(big, NativeHgRust.hg(workDir, repoRelPath, "cat", "-r", "tip", "big.txt"), "combo " + combo);
            assertEquals("root content", NativeHgRust.hg(workDir, repoRelPath, "cat", "-r", "0", "a.txt"),
                    "c0's own revision of a.txt must be unaffected by GC for combo " + combo);

            String revs = NativeHgRust.hg(workDir, repoRelPath, "log", "--template", "{rev}\\n");
            assertEquals("1\n0", revs, "GC must not add/remove any revision for combo " + combo);

            String finalTipHex = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", "tip", "--template", "{node}");
            assertEquals(tipHex, finalTipHex, "GC must not change any node hash for combo " + combo);

            // fileindex-v1 (and general-v2, which always implies it) must never get an fncache
            // file written by GC -- real hg drops fncache entirely for that combo (verified live,
            // see GcCommand's own javadoc).
            File fncacheFile = new File(hostRepoDir.toFile(), ".hg/store/fncache");
            if (combo.label().contains("fileindex-v1") || combo.label().contains("general-v2")) {
                assertFalse(fncacheFile.exists(),
                        "fileindex-v1/general-v2 repositories must never have an fncache file for combo " + combo);
            } else {
                assertTrue(fncacheFile.exists(), "combo " + combo);
                List<String> fncacheLines = Files.readAllLines(fncacheFile.toPath());
                assertTrue(fncacheLines.contains("data/a.txt.i"), "combo " + combo + ": " + fncacheLines);
                assertTrue(fncacheLines.contains("data/big.txt.i"), "combo " + combo + ": " + fncacheLines);
                assertTrue(fncacheLines.contains("data/sub/nested.txt.i"), "combo " + combo + ": " + fncacheLines);
                assertFalse(fncacheLines.contains("00changelog.i"), "combo " + combo + ": " + fncacheLines);
                assertFalse(fncacheLines.contains("00manifest.i"), "combo " + combo + ": " + fncacheLines);
            }

            assertEquals("", NativeHgRust.hg(workDir, repoRelPath, "status"), "GC must not touch the working copy for combo " + combo);
        });
    }
}
