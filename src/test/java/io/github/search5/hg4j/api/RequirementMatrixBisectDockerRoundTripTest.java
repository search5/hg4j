package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
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
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest} for
 * the full 30-combo design this reuses verbatim) applied to {@link BisectCommand} -- the
 * Docker-only counterpart of {@link RequirementMatrixBisectCoreRoundTripTest}'s native 6-combo
 * scenario (see its javadoc for the treemanifest-checkout bug this suite found and fixed). A
 * shorter, 6-revision history is used here (vs. the native trio's 8) purely to keep the 30-combo
 * Docker run's wall-clock cost down -- the algorithm being exercised is identical.
 *
 * <p>hg4j's own bisect (a real working-copy write: dirstate + file checkout) runs inline in this
 * JVM, alongside the native rust-hg subprocess calls this class uses for the real hg side.
 */
@Tag("interop")
public class RequirementMatrixBisectDockerRoundTripTest {

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


    /** Runs hg4j's {@link BisectCommand#next()} in a dedicated subprocess; returns the resulting
     * candidate node's hex. */
    /** EXPERIMENT (2026-09-09): inline instead of subprocess. */
    private static String bisectNextInSubprocess(Path repoDir, String goodHex, String badHex) throws Exception {
        byte[] good = NodeIdUtil.fromHex(goodHex);
        byte[] bad = NodeIdUtil.fromHex(badHex);
        HgRepository repo = new HgRepository(repoDir.toFile());
        byte[] candidate = new BisectCommand(repo).setGood(good).setBad(bad).next();
        return NodeIdUtil.toHex(candidate);
    }

    /** One point in the Docker-only quarter of the requirement matrix -- identical generation to
     * {@link RequirementMatrixDockerRoundTripTest#combos()}. */
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

    private static String hexAt(Revlog changelog, int rev) throws Exception {
        return NodeIdUtil.toHex(changelog.getIndexRecord(rev).getNodeId());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void bisectConvergesAndChecksOutNestedFilesAcrossDockerCombo(RequirementCombo combo) throws Exception {
        NativeHgRust.withFreshWorkDir("hg4j-native-matrix", (workDir) -> {
            String repoRelPath = "repo";
            Path hostRepoDir = workDir.resolve(repoRelPath);
            Files.createDirectories(hostRepoDir);
            Files.createDirectories(hostRepoDir.resolve("dir"));

            List<String> initArgs = new ArrayList<>(List.of("init", "."));
            for (String c : combo.initConfigArgs()) {
                initArgs.add("--config");
                initArgs.add(c);
            }
            NativeHgRust.hg(workDir, repoRelPath, initArgs.toArray(new String[0]));

            int totalRevs = 6;
            int culpritRev = 3;
            for (int i = 0; i < totalRevs; i++) {
                String flag = (i < culpritRev) ? "0" : "1";
                Files.writeString(hostRepoDir.resolve("flag.txt"), flag);
                Files.writeString(hostRepoDir.resolve("dir").resolve("n.txt"), "rev" + i);
                NativeHgRust.hg(workDir, repoRelPath, "add");
                NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "rev" + i);
            }

            HgRepository repo = new HgRepository(hostRepoDir.toFile());
            Revlog changelog = repo.getRevlog(
                    new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"));
            assertEquals(totalRevs, changelog.getRevisionCount(), "combo " + combo);

            String goodHex = hexAt(changelog, 0);
            String badHex = hexAt(changelog, totalRevs - 1);
            String expectedCulpritHex = hexAt(changelog, culpritRev);

            int goodRev = 0;
            int badRev = totalRevs - 1;
            int guard = 0;
            while (badRev - goodRev > 1 && guard++ < totalRevs + 2) {
                String candidateHex = bisectNextInSubprocess(hostRepoDir, goodHex, badHex);
                int candidateRev = changelog.findRevision(NodeIdUtil.fromHex(candidateHex));

                String expectedNested = NativeHgRust.hg(workDir, repoRelPath, "cat", "-r", candidateHex, "dir/n.txt");
                String actualNested = Files.readString(hostRepoDir.resolve("dir").resolve("n.txt"));
                assertEquals(expectedNested, actualNested,
                        "nested treemanifest file must be correctly checked out at candidate rev " + candidateRev
                                + ", combo " + combo);

                String flagContent = Files.readString(hostRepoDir.resolve("flag.txt"));
                if ("0".equals(flagContent)) {
                    goodHex = candidateHex;
                    goodRev = candidateRev;
                } else {
                    badHex = candidateHex;
                    badRev = candidateRev;
                }
            }

            assertEquals(expectedCulpritHex, badHex,
                    "hg4j's bisect walk must converge on the real culprit, combo " + combo);
        });
    }
}
