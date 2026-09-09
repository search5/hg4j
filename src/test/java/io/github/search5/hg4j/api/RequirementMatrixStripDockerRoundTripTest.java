package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;

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
import java.util.concurrent.TimeUnit;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest}
 * for the full 30-combo design this reuses verbatim, and {@link RequirementMatrixStripCoreRoundTripTest}
 * for the native 6-combo half and the scenario this parametrizes across the Docker-only quarter of
 * the matrix -- deliberately uneven revision sizes, to stress {@link StripCommand}'s exact-offset
 * {@code .d} truncation and (found 2026-09-05 fixing this exact command's native half) the
 * inline-revlog and v2/docket-based branches of {@link io.github.search5.hg4j.storage.Revlog#truncate}).
 *
 * <p>Each case gets its own fresh, short-lived container (never the class-shared one) -- like
 * {@link RequirementMatrixDockerRoundTripTest}'s own write-direction test, this is a
 * correctness-critical write path and the parent class's javadoc documents a real, reproducible
 * corruption symptom from reusing one long-lived container across many write cases. hg4j's own
 * writes (commit x4 + strip) run in a dedicated {@code java} subprocess ({@link
 * RequirementMatrixStripHelperMain}) rather than inline in this JVM, for the same
 * docker-exec-interleaving corruption reason documented on {@link RequirementMatrixCommitHelperMain}.
 */
@Tag("interop")
public class RequirementMatrixStripDockerRoundTripTest {

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

    private static String dockerHgTolerantIn(Path workDir, String repoRelPath, String... args) throws Exception {
        return NativeHgRust.hgTolerant(workDir, repoRelPath, args);
    }

    /** Runs hg4j's commit x4 + strip in a dedicated subprocess; returns the surviving rev2's hex. */
    private static String stripInSubprocess(Path repoDir) throws Exception {
        HgRepository repo = new HgRepository(repoDir.toFile());

        File f = new File(repoDir.toFile(), "a.txt");
        Files.writeString(f.toPath(), "x".repeat(50));
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("hg4j").setMessage("rev0-small").call();

        Files.writeString(f.toPath(), "y".repeat(50_000));
        new CommitCommand(repo).setAuthor("hg4j").setMessage("rev1-large").call();

        Files.writeString(f.toPath(), "z".repeat(60));
        byte[] rev2Node = new CommitCommand(repo).setAuthor("hg4j").setMessage("rev2-small-to-keep").call();

        Files.writeString(f.toPath(), "w".repeat(70));
        new CommitCommand(repo).setAuthor("hg4j").setMessage("rev3-to-strip").call();

        new StripCommand(repo).setRevision("3").call();

        return NodeIdUtil.toHex(rev2Node);
    }

    /** One point in the Docker-only quarter of the requirement matrix -- identical generation to
     * {@link RequirementMatrixDockerRoundTripTest#combos()}, copied rather than shared since that
     * class's combos() is an instance-private detail of a different, already-large test class. */
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jStripAcrossDockerCombo(RequirementCombo combo) throws Exception {
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

            String rev2Hex = stripInSubprocess(hostRepoDir);

            String verify = dockerHgTolerantIn(workDir, repoRelPath, "verify");
            assertTrue(verify.contains("0 integrity errors") || verify.toLowerCase().contains("checked"),
                    "real hg verify must report a clean repository after strip for combo " + combo + ", got: " + verify);

            String log = NativeHgRust.hg(workDir, repoRelPath, "log", "--template", "{rev}:{node}\n");
            List<String> lines = List.of(log.split("\n"));
            assertEquals(3, lines.size(), "3 revisions must remain after stripping rev3 for combo " + combo + "; got:\n" + log);

            String catOut = NativeHgRust.hg(workDir, repoRelPath, "cat", "-r", rev2Hex, "a.txt");
            assertEquals("z".repeat(60), catOut, "surviving rev2's content must be byte-exact after strip for combo " + combo);
        });
    }
}
