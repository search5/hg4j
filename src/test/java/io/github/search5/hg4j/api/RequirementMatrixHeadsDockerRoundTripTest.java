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
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest}
 * for the full 30-combo design this reuses verbatim) applied to {@link HeadsCommand}/
 * {@link TipCommand}/{@link ParentsCommand} -- the Docker-only counterpart of
 * {@link RequirementMatrixHeadsCoreRoundTripTest}'s native 6-combo scenario (see that class's
 * javadoc for the full scenario writeup): all three commands are pure readers, and the repository
 * itself is always built exclusively via real {@code hg} -- {@code docker exec} here.
 *
 * <p>Each case gets its own fresh, short-lived container (never a class-shared one), matching
 * {@link RequirementMatrixBackoutDockerRoundTripTest} and friends.
 */
@Tag("interop")
public class RequirementMatrixHeadsDockerRoundTripTest {

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


    private static List<String> lines(String s) {
        return List.of(s.lines().filter(x -> !x.isBlank()).toArray(String[]::new));
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
    public void headsTipParentsMatchRealHgAcrossDockerCombo(RequirementCombo combo) throws Exception {
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

            Files.writeString(hostRepoDir.resolve("a.txt"), "a0\n");
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c0");
            String c0Hex = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            Files.writeString(hostRepoDir.resolve("a.txt"), "a1\n");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c1");
            String c1Hex = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            NativeHgRust.hg(workDir, repoRelPath, "update", c0Hex);
            NativeHgRust.hg(workDir, repoRelPath, "branch", "feature");
            Files.writeString(hostRepoDir.resolve("b.txt"), "b0\n");
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c2");
            String c2Hex = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            Files.writeString(hostRepoDir.resolve("b.txt"), "b1\n");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c3", "--close-branch");
            String c3Hex = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            NativeHgRust.hg(workDir, repoRelPath, "update", c1Hex);
            NativeHgRust.hg(workDir, repoRelPath, "branch", "sub");
            Files.writeString(hostRepoDir.resolve("c.txt"), "c0\n");
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c4");
            String c4Hex = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            HgRepository repo = new HgRepository(hostRepoDir.toFile());

            String realTipHex = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", "tip", "--template", "{node}");
            assertEquals(c4Hex, realTipHex, "combo " + combo);
            byte[] hg4jTip = new TipCommand(repo).call();
            assertEquals(realTipHex, NodeIdUtil.toHex(hg4jTip), "combo " + combo);
            assertEquals(4, new TipCommand(repo).getRevisionNumber(), "combo " + combo);

            List<String> realHeadsPlain = lines(NativeHgRust.hg(workDir, repoRelPath, "heads", "--template", "{node}\n"));
            List<String> hg4jHeadsDefault = new HeadsCommand(repo).call();
            assertEquals(List.of(c4Hex, c1Hex), hg4jHeadsDefault, "combo " + combo);
            assertEquals(realHeadsPlain, hg4jHeadsDefault, "combo " + combo);

            List<String> realHeadsClosed = lines(NativeHgRust.hg(workDir, repoRelPath, "heads", "--closed", "--template", "{node}\n"));
            List<String> hg4jHeadsClosed = new HeadsCommand(repo).setIncludeClosed(true).call();
            assertEquals(List.of(c4Hex, c3Hex, c1Hex), hg4jHeadsClosed, "combo " + combo);
            assertEquals(realHeadsClosed, hg4jHeadsClosed, "combo " + combo);

            List<String> realHeadsTopo = lines(NativeHgRust.hg(workDir, repoRelPath, "heads", "--topo", "--template", "{node}\n"));
            List<String> hg4jHeadsTopo = new HeadsCommand(repo).setTopo(true).call();
            assertEquals(realHeadsTopo, hg4jHeadsTopo, "combo " + combo);

            List<String> realHeadsSub = lines(NativeHgRust.hg(workDir, repoRelPath, "heads", "sub", "--template", "{node}\n"));
            List<String> hg4jHeadsSub = new HeadsCommand(repo).setBranch("sub").call();
            assertEquals(List.of(c4Hex), hg4jHeadsSub, "combo " + combo);
            assertEquals(realHeadsSub, hg4jHeadsSub, "combo " + combo);

            List<String> realHeadsFeatureClosed = lines(NativeHgRust.hg(workDir, repoRelPath, "heads", "feature", "--closed", "--template", "{node}\n"));
            List<String> hg4jHeadsFeatureClosed = new HeadsCommand(repo).setBranch("feature").setIncludeClosed(true).call();
            assertEquals(List.of(c3Hex), hg4jHeadsFeatureClosed, "combo " + combo);
            assertEquals(realHeadsFeatureClosed, hg4jHeadsFeatureClosed, "combo " + combo);

            List<String> hg4jHeadsFeatureOpenOnly = new HeadsCommand(repo).setBranch("feature").call();
            assertEquals(List.of(), hg4jHeadsFeatureOpenOnly, "combo " + combo);

            NativeHgRust.hg(workDir, repoRelPath, "update", c4Hex);
            assertEquals(List.of(c4Hex), new ParentsCommand(repo).call(), "combo " + combo);

            NativeHgRust.hg(workDir, repoRelPath, "merge", c2Hex);
            List<String> realParents = lines(NativeHgRust.hg(workDir, repoRelPath, "parents", "--template", "{node}\n"));
            List<String> hg4jParents = new ParentsCommand(repo).call();
            assertEquals(List.of(c4Hex, c2Hex), hg4jParents, "combo " + combo);
            assertEquals(realParents, hg4jParents, "combo " + combo);
        });
    }
}
