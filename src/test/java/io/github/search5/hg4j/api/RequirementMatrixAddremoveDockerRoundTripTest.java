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
import java.util.concurrent.TimeUnit;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest}
 * for the full 30-combo design this reuses verbatim) applied to {@link AddremoveCommand} -- the
 * Docker-only counterpart of {@link RequirementMatrixAddremoveCoreRoundTripTest}'s native 6-combo
 * scenario. Reaches every dirstate-v2 sub-combo, which pure-Python hg on this host cannot create
 * (see {@link RequirementMatrixDockerRoundTripTest}'s class javadoc) -- exercising {@link
 * AddremoveCommand}'s combined new-'a'-entry (via {@link AddCommand}) and physically-missing-file
 * 'r'-entry (via {@link RemoveCommand}) writes in the same pass against every dirstate-v2
 * sub-combo.
 *
 * <p>Each case gets its own fresh, short-lived container, matching every other write-direction
 * test in this matrix family. hg4j's own {@link AddremoveCommand} call runs in a dedicated {@code
 * java} subprocess ({@link RequirementMatrixAddremoveHelperMain}) for the same
 * docker-exec-interleaving corruption reason documented on {@link RequirementMatrixCommitHelperMain}.
 */
@Tag("interop")
public class RequirementMatrixAddremoveDockerRoundTripTest {

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


    /** EXPERIMENT (2026-09-09): inline instead of subprocess, verified safe by repeated-run
     * testing (see docs/guide -- native hg + inline hg4j writes, no docker-exec interleaving). */
    private static void addremoveInSubprocess(Path repoDir) throws Exception {
        HgRepository repo = new HgRepository(repoDir.toFile());
        new AddremoveCommand(repo).call();
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jAddremoveAcrossDockerCombo(RequirementCombo combo) throws Exception {
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

            Files.writeString(hostRepoDir.resolve("gone.txt"), "will be deleted\n");
            Files.createDirectories(hostRepoDir.resolve("adir"));
            Files.writeString(hostRepoDir.resolve("adir/gone-nested.txt"), "nested will be deleted\n");
            Files.writeString(hostRepoDir.resolve("keep.txt"), "keep content\n");
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c0 base");

            Files.writeString(hostRepoDir.resolve("new.txt"), "brand new\n");
            Files.createDirectories(hostRepoDir.resolve("bdir"));
            Files.writeString(hostRepoDir.resolve("bdir/new-nested.txt"), "brand new nested\n");
            Files.delete(hostRepoDir.resolve("gone.txt"));
            Files.delete(hostRepoDir.resolve("adir/gone-nested.txt"));

            addremoveInSubprocess(hostRepoDir);

            String status = NativeHgRust.hg(workDir, repoRelPath, "status");
            assertTrue(status.lines().anyMatch(l -> l.equals("A new.txt")), "real hg status: " + status);
            assertTrue(status.lines().anyMatch(l -> l.equals("A bdir/new-nested.txt")), "real hg status: " + status);
            assertTrue(status.lines().anyMatch(l -> l.equals("R gone.txt")), "real hg status: " + status);
            assertTrue(status.lines().anyMatch(l -> l.equals("R adir/gone-nested.txt")), "real hg status: " + status);

            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c1 addremove");

            String verify = NativeHgRust.hg(workDir, repoRelPath, "verify");
            assertFalse(verify.toLowerCase().contains("integrity error"),
                    "real hg verify must find no integrity errors after addremove+commit for combo " + combo + ": " + verify);

            String manifest = NativeHgRust.hg(workDir, repoRelPath, "manifest", "-r", "tip");
            assertTrue(manifest.lines().anyMatch(l -> l.equals("new.txt")), "manifest: " + manifest);
            assertTrue(manifest.lines().anyMatch(l -> l.equals("bdir/new-nested.txt")), "manifest: " + manifest);
            assertTrue(manifest.lines().anyMatch(l -> l.equals("keep.txt")), "manifest: " + manifest);
            assertFalse(manifest.lines().anyMatch(l -> l.equals("gone.txt")), "manifest must not contain gone.txt: " + manifest);
            assertFalse(manifest.lines().anyMatch(l -> l.equals("adir/gone-nested.txt")), "manifest must not contain adir/gone-nested.txt: " + manifest);

            assertEquals("brand new", NativeHgRust.hg(workDir, repoRelPath, "cat", "-r", "tip", "new.txt"));
            assertEquals("brand new nested", NativeHgRust.hg(workDir, repoRelPath, "cat", "-r", "tip", "bdir/new-nested.txt"));
            assertEquals("keep content", NativeHgRust.hg(workDir, repoRelPath, "cat", "-r", "tip", "keep.txt"));
            assertEquals("will be deleted", NativeHgRust.hg(workDir, repoRelPath, "cat", "-r", "0", "gone.txt"));
        });
    }
}
