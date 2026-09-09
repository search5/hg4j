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
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest}
 * for the full 30-combo design this reuses verbatim) applied to {@link SubrepoCommand} -- the
 * Docker-only counterpart of {@link RequirementMatrixSubrepoCoreRoundTripTest}'s native 6-combo
 * scenario (add + init pinned at v1, commit, bump the pin to v2, update, commit again),
 * re-verified by real hg.
 *
 * <p>Each case gets its own fresh, short-lived container (never a class-shared one), matching
 * {@link RequirementMatrixDockerRoundTripTest}'s own write-direction test and {@link
 * RequirementMatrixStripDockerRoundTripTest} -- this is a correctness-critical write path and the
 * parent class's javadoc documents a real, reproducible corruption symptom from reusing one
 * long-lived container across many write cases. hg4j's own subrepo add/init/commit/update/commit
 * write sequence runs inline in this JVM, alongside the native rust-hg subprocess calls this
 * class uses for the real hg side.
 */
@Tag("interop")
public class RequirementMatrixSubrepoDockerRoundTripTest {

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


    /** Runs hg4j's whole add/init/commit/bump/update/commit sequence in a dedicated subprocess;
     * returns the two parent commit hexes (first commit, second commit). */
    private static String[] subrepoRoundTripInSubprocess(Path parentDir, Path subSourceDir, String subV1, String subV2) throws Exception {
        HgRepository parentRepo = new HgRepository(parentDir.toFile());

        new SubrepoCommand(parentRepo)
                .setAction("add")
                .setSubrepoPath("sub")
                .setSubrepoUrl(subSourceDir.toFile().getAbsolutePath())
                .setRevision(subV1)
                .call();

        new SubrepoCommand(parentRepo).setAction("init").call();

        new AddCommand(parentRepo).call();
        byte[] parentC1 = new CommitCommand(parentRepo).setAuthor("dev").setMessage("add subrepo").call();

        Files.writeString(new File(parentDir.toFile(), ".hgsubstate").toPath(), subV2 + " sub\n", StandardCharsets.UTF_8);
        new SubrepoCommand(parentRepo).setAction("update").call();

        byte[] parentC2 = new CommitCommand(parentRepo).setAuthor("dev").setMessage("bump sub to v2").call();

        return new String[] {NodeIdUtil.toHex(parentC1), NodeIdUtil.toHex(parentC2)};
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
    public void hg4jSubrepoAddInitUpdateAcrossDockerCombo(RequirementCombo combo) throws Exception {
        NativeHgRust.withFreshWorkDir("hg4j-native-matrix", (workDir) -> {
            String subSourceRelPath = "sub-source";
            Path hostSubSourceDir = workDir.resolve(subSourceRelPath);
            Files.createDirectories(hostSubSourceDir);
            NativeHgRust.hg(workDir, subSourceRelPath, "init", ".");
            Files.writeString(hostSubSourceDir.resolve("hello.txt"), "v1");
            NativeHgRust.hg(workDir, subSourceRelPath, "add");
            NativeHgRust.hg(workDir, subSourceRelPath, "commit", "-u", "dev", "-m", "sub v1");
            String subV1 = NativeHgRust.hg(workDir, subSourceRelPath, "log", "-r", "tip", "--template", "{node}");
            Files.writeString(hostSubSourceDir.resolve("hello.txt"), "v2");
            NativeHgRust.hg(workDir, subSourceRelPath, "commit", "-u", "dev", "-m", "sub v2");
            String subV2 = NativeHgRust.hg(workDir, subSourceRelPath, "log", "-r", "tip", "--template", "{node}");

            String repoRelPath = "repo";
            Path hostRepoDir = workDir.resolve(repoRelPath);
            Files.createDirectories(hostRepoDir);
            List<String> initArgs = new ArrayList<>(List.of("init", "."));
            for (String c : combo.initConfigArgs()) {
                initArgs.add("--config");
                initArgs.add(c);
            }
            NativeHgRust.hg(workDir, repoRelPath, initArgs.toArray(new String[0]));
            Files.writeString(hostRepoDir.resolve("init.txt"), "init\n");
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c0");

            String[] commitHexes = subrepoRoundTripInSubprocess(hostRepoDir, hostSubSourceDir, subV1, subV2);
            String parentC1Hex = commitHexes[0];
            String parentC2Hex = commitHexes[1];

            String verify1 = NativeHgRust.hg(workDir, repoRelPath, "verify");
            assertTrue(verify1.toLowerCase().contains("0 integrity errors") || !verify1.toLowerCase().contains("error"),
                    "real hg verify must find no integrity errors after the first subrepo commit for combo " + combo + ": " + verify1);
            assertEquals(subV1 + " sub", NativeHgRust.hg(workDir, repoRelPath, "cat", "-r", parentC1Hex, ".hgsubstate"),
                    "real hg must read back .hgsubstate pinned at v1 for combo " + combo);

            assertEquals("", NativeHgRust.hg(workDir, repoRelPath + "/sub", "status"),
                    "real hg must see the subrepo working copy as clean after update for combo " + combo);
            assertEquals(subV2, NativeHgRust.hg(workDir, repoRelPath + "/sub", "log", "-r", ".", "--template", "{node}"),
                    "real hg must see the subrepo checked out at exactly v2 after update for combo " + combo);

            String verify2 = NativeHgRust.hg(workDir, repoRelPath, "verify");
            assertTrue(verify2.toLowerCase().contains("0 integrity errors") || !verify2.toLowerCase().contains("error"),
                    "real hg verify must find no integrity errors after the second subrepo commit for combo " + combo + ": " + verify2);
            assertEquals(subV2 + " sub", NativeHgRust.hg(workDir, repoRelPath, "cat", "-r", parentC2Hex, ".hgsubstate"),
                    "real hg must read back .hgsubstate pinned at v2 for combo " + combo);
            assertEquals("", NativeHgRust.hg(workDir, repoRelPath, "status"),
                    "working copy must be clean right after the second subrepo commit for combo " + combo);
            assertEquals(parentC1Hex, NativeHgRust.hg(workDir, repoRelPath, "log", "-r", parentC2Hex, "--template", "{p1node}"),
                    "the second commit's parent must be the first for combo " + combo);
        });
    }
}
