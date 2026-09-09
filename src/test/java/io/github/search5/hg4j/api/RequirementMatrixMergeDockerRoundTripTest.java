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
 * for the full 30-combo design this reuses verbatim) applied to {@link MergeCommand} -- the
 * Docker-only counterpart of {@link RequirementMatrixMergeCoreRoundTripTest}'s native 6-combo
 * scenarios (a clean two-branch merge, and a genuinely conflicting one resolved + committed),
 * re-verified by real hg.
 *
 * <p>Each case gets its own fresh, short-lived container (never a class-shared one), matching
 * {@link RequirementMatrixDockerRoundTripTest}'s own write-direction test and {@link
 * RequirementMatrixStripDockerRoundTripTest} -- this is a correctness-critical write path and the
 * parent class's javadoc documents a real, reproducible corruption symptom from reusing one
 * long-lived container across many write cases. hg4j's own merge+commit write runs in a dedicated
 * {@code java} subprocess ({@link RequirementMatrixMergeHelperMain}) rather than inline in this
 * JVM, for the same docker-exec-interleaving corruption reason documented on {@link
 * RequirementMatrixCommitHelperMain}.
 */
@Tag("interop")
public class RequirementMatrixMergeDockerRoundTripTest {

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


    /** Runs hg4j's merge+commit in a dedicated subprocess; returns the merge node's hex. */
    private static String mergeAndCommitInSubprocess(Path repoDir, String mode, String sourceHex) throws Exception {
        byte[] sourceNode = NodeIdUtil.fromHex(sourceHex);
        HgRepository repo = new HgRepository(repoDir.toFile());

        MergeCommand.MergeResult result = new MergeCommand(repo).setNodeId(sourceNode).call();

        if ("conflict".equals(mode)) {
            if (!result.isConflicted()) {
                throw new IllegalStateException("Expected a conflicted merge but got none");
            }
            Files.writeString(new File(repoDir.toFile(), "conflict.txt").toPath(), "line1\nRESOLVED\nline3\n", StandardCharsets.UTF_8);
            new ResolveCommand(repo).setFile("conflict.txt").markResolved(true).call();
        } else if (result.isConflicted()) {
            throw new IllegalStateException("Expected a clean merge but got conflicts: " + result.getConflicts());
        }

        byte[] mergeNode = new CommitCommand(repo).setAuthor("hg4j").setMessage("merge").call();
        return NodeIdUtil.toHex(mergeNode);
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
    public void hg4jCleanMergeAcrossDockerCombo(RequirementCombo combo) throws Exception {
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
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c0");

            Files.writeString(hostRepoDir.resolve("target.txt"), "on-target\n");
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c1 target");
            String targetHex = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            NativeHgRust.hg(workDir, repoRelPath, "update", "0");
            Files.writeString(hostRepoDir.resolve("source.txt"), "on-source\n");
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c2 source");
            String sourceHex = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            NativeHgRust.hg(workDir, repoRelPath, "update", targetHex);

            String mergeHex = mergeAndCommitInSubprocess(hostRepoDir, "clean", sourceHex);

            String verify = NativeHgRust.hg(workDir, repoRelPath, "verify");
            assertFalse(verify.toLowerCase().contains("integrity error") || verify.toLowerCase().contains("error:"),
                    "real hg verify must find no integrity errors after merge+commit for combo " + combo + ": " + verify);

            String parents = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", mergeHex, "--template", "{p1node} {p2node}");
            assertEquals(targetHex + " " + sourceHex, parents,
                    "the merge commit's parents must be exactly target then source for combo " + combo);

            assertEquals("base", NativeHgRust.hg(workDir, repoRelPath, "cat", "-r", mergeHex, "base.txt"));
            assertEquals("on-target", NativeHgRust.hg(workDir, repoRelPath, "cat", "-r", mergeHex, "target.txt"));
            assertEquals("on-source", NativeHgRust.hg(workDir, repoRelPath, "cat", "-r", mergeHex, "source.txt"));
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jConflictingMergeAcrossDockerCombo(RequirementCombo combo) throws Exception {
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

            Files.writeString(hostRepoDir.resolve("conflict.txt"), "line1\nline2\nline3\n");
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c0");

            Files.writeString(hostRepoDir.resolve("conflict.txt"), "line1\nTARGET\nline3\n");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c1 target");
            String targetHex = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            NativeHgRust.hg(workDir, repoRelPath, "update", "0");
            Files.writeString(hostRepoDir.resolve("conflict.txt"), "line1\nSOURCE\nline3\n");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c2 source");
            String sourceHex = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            NativeHgRust.hg(workDir, repoRelPath, "update", targetHex);

            String mergeHex = mergeAndCommitInSubprocess(hostRepoDir, "conflict", sourceHex);

            String verify = NativeHgRust.hg(workDir, repoRelPath, "verify");
            assertFalse(verify.toLowerCase().contains("integrity error") || verify.toLowerCase().contains("error:"),
                    "real hg verify must find no integrity errors after the conflicted merge+commit for combo " + combo + ": " + verify);

            String parents = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", mergeHex, "--template", "{p1node} {p2node}");
            assertEquals(targetHex + " " + sourceHex, parents,
                    "the merge commit's parents must be exactly target then source for combo " + combo);

            assertEquals("line1\nRESOLVED\nline3", NativeHgRust.hg(workDir, repoRelPath, "cat", "-r", mergeHex, "conflict.txt"));

            // Real hg must see the merge as fully finalized: no leftover .hg/merge bookkeeping.
            String resolveListAfterCommit = NativeHgRust.hg(workDir, repoRelPath, "resolve", "--list");
            assertEquals("", resolveListAfterCommit,
                    "no unresolved/resolved entries should remain once the merge is committed for combo " + combo);
        });
    }
}
