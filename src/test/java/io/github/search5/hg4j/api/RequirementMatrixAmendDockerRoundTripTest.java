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
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest}
 * for the full 30-combo design this reuses verbatim) applied to {@link AmendCommand} -- the
 * Docker-only counterpart of {@link RequirementMatrixAmendCoreRoundTripTest}'s native 6-combo
 * scenario (amend the tip commit's message/author/content, verify the original is hidden not
 * gone and the amended commit keeps the original's own parent).
 *
 * <p>Each case gets its own fresh, short-lived container, matching every other write-direction
 * test in this matrix family (see {@link RequirementMatrixDockerRoundTripTest}'s class javadoc
 * for why). hg4j's own {@link AmendCommand} call runs inline in this JVM, alongside the native
 * rust-hg subprocess calls this class uses for the real hg side.
 */
@Tag("interop")
public class RequirementMatrixAmendDockerRoundTripTest {

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

    /** Like {@link #dockerHgIn} but with {@code experimental.evolution=all} added, so a
     * post-amend repository (which always carries an obsmarker) can be queried without the
     * unrelated "obsolete feature not enabled" warning polluting the output being asserted on. */
    private static String dockerHgEvolutionIn(Path workDir, String repoRelPath, String... args) throws Exception {
        List<String> full = new ArrayList<>(List.of("--config", "experimental.evolution=all"));
        full.addAll(Arrays.asList(args));
        return NativeHgRust.hg(workDir, repoRelPath, full.toArray(new String[0]));
    }

    /** Runs hg4j's amend in a dedicated subprocess; returns the amended node's hex. */
    private static String amendInSubprocess(Path repoDir, String author, String message) throws Exception {
        HgRepository repo = new HgRepository(repoDir.toFile());
        byte[] amendedNode = new AmendCommand(repo).setAuthor(author).setMessage(message).call();
        return NodeIdUtil.toHex(amendedNode);
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
    public void hg4jAmendAcrossDockerCombo(RequirementCombo combo) throws Exception {
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

            Files.writeString(hostRepoDir.resolve("base.txt"), "base\n");
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c0 base");
            String baseHex = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            Files.writeString(hostRepoDir.resolve("a.txt"), "one\n");
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c1 to amend");
            String originalHex = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            Files.writeString(hostRepoDir.resolve("a.txt"), "one-amended\n");
            String amendedHex = amendInSubprocess(hostRepoDir, "amender", "c1 amended");

            String verify = dockerHgEvolutionIn(workDir, repoRelPath, "verify");
            assertFalse(verify.toLowerCase().contains("integrity error"),
                    "real hg verify must find no integrity errors after amend for combo " + combo + ": " + verify);

            String tipHex = dockerHgEvolutionIn(workDir, repoRelPath, "log", "-r", "tip", "--template", "{node}");
            assertEquals(amendedHex, tipHex, "amended commit must be the new tip for combo " + combo);

            String tipParent = dockerHgEvolutionIn(workDir, repoRelPath, "log", "-r", "tip", "--template", "{p1node}");
            assertEquals(baseHex, tipParent, "amended commit must keep the original commit's own parent for combo " + combo);

            String tipDesc = dockerHgEvolutionIn(workDir, repoRelPath, "log", "-r", "tip", "--template", "{desc}");
            assertEquals("c1 amended", tipDesc.trim());

            String catContent = dockerHgEvolutionIn(workDir, repoRelPath, "cat", "-r", "tip", "a.txt");
            assertEquals("one-amended", catContent.trim());

            String logAll = dockerHgEvolutionIn(workDir, repoRelPath, "log", "--template", "{node} ");
            assertFalse(logAll.contains(originalHex), "the amended-away original revision must be hidden from a plain log for combo " + combo);

            String log = dockerHgEvolutionIn(workDir, repoRelPath, "log", "--template", "{rev}:{node}\n");
            assertEquals(2, log.split("\n").length, "2 visible revisions for combo " + combo + ":\n" + log);
        });
    }
}
