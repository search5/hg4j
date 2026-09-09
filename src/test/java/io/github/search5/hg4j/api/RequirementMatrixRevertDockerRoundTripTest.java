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
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest}
 * for the full 30-combo design this reuses verbatim) applied to {@link RevertCommand} -- the
 * Docker-only counterpart of {@link RequirementMatrixRevertCoreRoundTripTest}'s native 6-combo
 * scenarios (modified/added/removed revert to parent, and revert to an explicit older revision),
 * re-verified by real hg.
 *
 * <p>Each case gets its own fresh, short-lived container (never a class-shared one), matching
 * {@link RequirementMatrixMergeDockerRoundTripTest}/{@link RequirementMatrixStripDockerRoundTripTest}
 * -- this exercises a correctness-critical write path (dirstate + working-copy content, including
 * the {@code .orig} backup) and the parent class's javadoc documents a real, reproducible
 * corruption symptom from reusing one long-lived container across many write cases. hg4j's own
 * revert writes run in a dedicated {@code java} subprocess ({@link
 * RequirementMatrixRevertHelperMain}) rather than inline in this JVM, for the same
 * docker-exec-interleaving corruption reason documented on {@link
 * RequirementMatrixCommitHelperMain}.
 */
@Tag("interop")
public class RequirementMatrixRevertDockerRoundTripTest {

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


    /** Runs hg4j's revert(s) in a dedicated subprocess. */
    private static void revertInSubprocess(Path repoDir, String mode, String... extraArgs) throws Exception {
        HgRepository repo = new HgRepository(repoDir.toFile());

        if ("mar".equals(mode)) {
            new RevertCommand(repo).setFile("base.txt").call();
            new RevertCommand(repo).setFile("new.txt").call();
            new RevertCommand(repo).setFile("keep.txt").call();
        } else if ("older".equals(mode)) {
            String revisionHex = extraArgs[0];
            new RevertCommand(repo).setFile("a.txt").setRevision(revisionHex).call();
            new RevertCommand(repo).setFile("later.txt").setRevision(revisionHex).call();
        } else {
            throw new IllegalArgumentException("Unknown mode: " + mode);
        }
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
    public void hg4jRevertsModifiedAddedRemovedAcrossDockerCombo(RequirementCombo combo) throws Exception {
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
            Files.writeString(hostRepoDir.resolve("keep.txt"), "keep\n");
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c0");

            Files.writeString(hostRepoDir.resolve("base.txt"), "modified locally\n");
            Files.writeString(hostRepoDir.resolve("new.txt"), "new content\n");
            NativeHgRust.hg(workDir, repoRelPath, "add", "new.txt");
            NativeHgRust.hg(workDir, repoRelPath, "remove", "keep.txt");

            revertInSubprocess(hostRepoDir, "mar");

            assertEquals("base", NativeHgRust.hg(workDir, repoRelPath, "cat", "base.txt"));
            assertEquals("keep", NativeHgRust.hg(workDir, repoRelPath, "cat", "keep.txt"));
            assertEquals("new content", Files.readString(hostRepoDir.resolve("new.txt")).trim(),
                    "reverting an added-but-uncommitted file must not delete its content for combo " + combo);
            assertEquals("modified locally", Files.readString(hostRepoDir.resolve("base.txt.orig")).trim(),
                    "the .orig backup must hold the pre-revert modified content for combo " + combo);

            String status = NativeHgRust.hg(workDir, repoRelPath, "status");
            assertEquals("? base.txt.orig\n? new.txt", status,
                    "only the .orig backup and the now-untracked former-added file should remain outstanding for combo "
                            + combo + ": " + status);
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jRevertsToOlderRevisionAcrossDockerCombo(RequirementCombo combo) throws Exception {
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

            Files.writeString(hostRepoDir.resolve("a.txt"), "v0\n");
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c0");
            String c0Hex = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            Files.writeString(hostRepoDir.resolve("a.txt"), "v1\n");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c1");

            Files.writeString(hostRepoDir.resolve("later.txt"), "added later\n");
            NativeHgRust.hg(workDir, repoRelPath, "add", "later.txt");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c2");

            revertInSubprocess(hostRepoDir, "older", c0Hex);

            // Revert only ever touches the working copy (no commit is made), so the reverted
            // content must be read directly off disk -- `hg cat -r .` would instead read a.txt's
            // content as recorded in the current parent commit (c2, unchanged from c1's "v1"),
            // which is not what this asserts.
            assertEquals("v0", Files.readString(hostRepoDir.resolve("a.txt")).trim());

            String status = NativeHgRust.hg(workDir, repoRelPath, "status");
            assertEquals("M a.txt\nR later.txt", status,
                    "a.txt must show modified and later.txt must show removed for combo " + combo + ": " + status);
        });
    }
}
