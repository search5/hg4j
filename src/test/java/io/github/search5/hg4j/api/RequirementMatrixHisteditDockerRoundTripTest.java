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
 * for the full 30-combo design this reuses verbatim) applied to {@link HisteditCommand} -- the
 * Docker-only counterpart of {@link RequirementMatrixHisteditCoreRoundTripTest}'s native 6-combo
 * scenario (a PICK/DROP/PICK/FOLD/ROLL histedit run, re-verified by real hg with {@code
 * experimental.evolution=all} to tolerate the expected post-histedit obsmarker warning).
 *
 * <p>Each case gets its own fresh, short-lived container (never a class-shared one), matching
 * {@link RequirementMatrixRebaseDockerRoundTripTest}/{@link RequirementMatrixStripDockerRoundTripTest}
 * -- this is a correctness-critical write path and the parent class's javadoc documents a real,
 * reproducible corruption symptom from reusing one long-lived container across many write cases.
 * hg4j's own histedit write runs in a dedicated {@code java} subprocess ({@link
 * RequirementMatrixHisteditHelperMain}) rather than inline in this JVM, for the same
 * docker-exec-interleaving corruption reason documented on {@link RequirementMatrixCommitHelperMain}.
 */
@Tag("interop")
public class RequirementMatrixHisteditDockerRoundTripTest {

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
     * post-histedit repository (which always carries obsmarkers) can be queried without the
     * unrelated "obsolete feature not enabled" warning polluting the output being asserted on --
     * mirrors {@code HisteditRealHgInteropTest}/{@code RequirementMatrixHisteditCoreRoundTripTest}'s
     * own {@code hgEvolution} helper. */
    private static String dockerHgEvolutionIn(Path workDir, String repoRelPath, String... args) throws Exception {
        List<String> full = new ArrayList<>(List.of("--config", "experimental.evolution=all"));
        full.addAll(Arrays.asList(args));
        return NativeHgRust.hg(workDir, repoRelPath, full.toArray(new String[0]));
    }

    /** Runs hg4j's histedit in a dedicated subprocess; returns the new tip node's hex. */
    private static String histeditInSubprocess(Path repoDir, String hexPick, String hexDrop, String hexKeep,
                                                String hexFold, String hexRoll) throws Exception {
        HgRepository repo = new HgRepository(repoDir.toFile());

        HisteditCommand cmd = new HisteditCommand(repo);
        cmd.addRule(HisteditCommand.Action.PICK, hexPick);
        cmd.addRule(HisteditCommand.Action.DROP, hexDrop);
        cmd.addRule(HisteditCommand.Action.PICK, hexKeep);
        cmd.addRule(HisteditCommand.Action.FOLD, hexFold);
        cmd.addRule(HisteditCommand.Action.ROLL, hexRoll);
        cmd.call();

        return NodeIdUtil.toHex(repo.getDirstate().getParent1());
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
    public void hg4jHisteditAcrossDockerCombo(RequirementCombo combo) throws Exception {
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

            Files.writeString(hostRepoDir.resolve("target.txt"), "target\n");
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c1 pick target");
            String hexPick = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            Files.writeString(hostRepoDir.resolve("drop.txt"), "drop-me\n");
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c2 dropped");
            String hexDrop = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            Files.writeString(hostRepoDir.resolve("keep.txt"), "keep\n");
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c3 pick keep");
            String hexKeep = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            Files.writeString(hostRepoDir.resolve("folded.txt"), "folded\n");
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c4 fold in");
            String hexFold = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            Files.writeString(hostRepoDir.resolve("rolled.txt"), "rolled\n");
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c5 roll in (message must be discarded)");
            String hexRoll = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            String newTipHex = histeditInSubprocess(hostRepoDir, hexPick, hexDrop, hexKeep, hexFold, hexRoll);

            String verify = dockerHgEvolutionIn(workDir, repoRelPath, "verify");
            assertFalse(verify.toLowerCase().contains("integrity error"),
                    "real hg verify must find no integrity errors after histedit for combo " + combo + ": " + verify);

            String message = dockerHgEvolutionIn(workDir, repoRelPath, "log", "-r", newTipHex, "--template", "{desc}");
            assertEquals("c3 pick keep\nc4 fold in", message,
                    "fold's message must be appended to the pick's; roll's own message must be discarded for combo " + combo);

            String catTarget = dockerHgEvolutionIn(workDir, repoRelPath, "cat", "-r", newTipHex, "target.txt");
            assertEquals("target", catTarget.trim());
            String catKeep = dockerHgEvolutionIn(workDir, repoRelPath, "cat", "-r", newTipHex, "keep.txt");
            assertEquals("keep", catKeep.trim());
            String catFolded = dockerHgEvolutionIn(workDir, repoRelPath, "cat", "-r", newTipHex, "folded.txt");
            assertEquals("folded", catFolded.trim());
            String catRolled = dockerHgEvolutionIn(workDir, repoRelPath, "cat", "-r", newTipHex, "rolled.txt");
            assertEquals("rolled", catRolled.trim());

            String logAll = dockerHgEvolutionIn(workDir, repoRelPath, "log", "--template", "{node} ");
            assertFalse(logAll.contains(hexDrop), "dropped revision must not appear in a plain log for combo " + combo);

            assertFalse(Files.exists(hostRepoDir.resolve("drop.txt")),
                    "dropped commit's file must be gone from the working copy for combo " + combo);
            assertTrue(Files.exists(hostRepoDir.resolve("base.txt")), "untouched root file must survive for combo " + combo);
        });
    }
}
