package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgValidationException;
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
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest}
 * for the full 30-combo design this reuses verbatim) applied to {@link CensorCommand} -- the
 * Docker-only counterpart of {@link RequirementMatrixCensorCoreRoundTripTest}'s native 6-combo
 * scenarios (censoring an older, no-longer-live revision, and being refused when targeting the
 * sole head/working-directory-parent revision), re-verified by real hg.
 *
 * <p>Each case gets its own fresh, short-lived container (never a class-shared one), matching
 * {@link RequirementMatrixBackoutDockerRoundTripTest} -- {@link CensorCommand} edits a filelog
 * revlog in place, a correctness-critical write path, so hg4j's own censor(+refusal check) runs
 * inline in this JVM, alongside the native rust-hg subprocess calls this class uses for the real
 * hg side.
 */
@Tag("interop")
public class RequirementMatrixCensorDockerRoundTripTest {

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

    /** Runs a real-hg command inside the container expecting it to fail (nonzero exit), returning
     * its combined output/error text. Fails the test itself (with {@code failureMessage}) if the
     * command unexpectedly succeeds -- structured so that sentinel is never accidentally
     * swallowed by a catch clause meant only for the command's own genuine failure. */
    private static String expectDockerHgFailure(Path workDir, String repoRelPath, String failureMessage, String... args) throws Exception {
        String out;
        try {
            out = NativeHgRust.hg(workDir, repoRelPath, args);
        } catch (AssertionError e) {
            return e.getMessage();
        }
        throw new AssertionError(failureMessage + " -- but it succeeded with output: " + out);
    }

    /** Runs hg4j's {@link CensorCommand} in a dedicated subprocess. {@code mode} is {@code
     * "censor"} (expects success) or {@code "refuse"} (expects the check-heads guard to throw --
     * returns the caught exception's message). */
    private static String censorInSubprocess(Path repoDir, String mode, String path, String nodeHex) throws Exception {
        HgRepository repo = new HgRepository(repoDir.toFile());

        if ("refuse".equals(mode)) {
            try {
                new CensorCommand(repo).setFile(path).setRevision(nodeHex).call();
                throw new IllegalStateException("Expected CensorCommand to refuse, but it succeeded");
            } catch (HgValidationException e) {
                return e.getMessage();
            }
        } else {
            new CensorCommand(repo).setFile(path).setRevision(nodeHex).call();
            return "";
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
    public void hg4jCensorsAnOlderRevisionAndRealHgConfirmsAcrossDockerCombo(RequirementCombo combo) throws Exception {
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

            Files.writeString(hostRepoDir.resolve("a.txt"), "secret1\n");
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c0");
            Files.writeString(hostRepoDir.resolve("a.txt"), "secret1\nsecret2\n");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c1");

            File flIdx = CommitCommand.getFilelogIndex(hostRepoDir.resolve(".hg/store").toFile(), "a.txt");
            assertTrue(flIdx.exists(), "a.txt's filelog index must exist for combo " + combo);
            Revlog filelog = new Revlog(flIdx,
                    new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d"));
            String rev0Hex = NodeIdUtil.toHex(filelog.getIndexRecord(0).getNodeId());

            censorInSubprocess(hostRepoDir, "censor", "a.txt", rev0Hex);

            String catEx = expectDockerHgFailure(workDir, repoRelPath,
                    "real hg must refuse to read hg4j-censored content for combo " + combo,
                    "--config", "extensions.censor=", "cat", "-r", "0", "a.txt");
            assertTrue(catEx.contains("censored node"),
                    "real hg must refuse to read hg4j-censored content for combo " + combo + ": " + catEx);

            String verifyOut;
            try {
                verifyOut = NativeHgRust.hg(workDir, repoRelPath, "verify");
            } catch (AssertionError verifyFailed) {
                verifyOut = verifyFailed.getMessage();
            }
            assertTrue(verifyOut.contains("censored file data"),
                    "real hg verify must recognize hg4j's REVIDX_ISCENSORED flag for combo " + combo + ": " + verifyOut);

            assertEquals("secret1\nsecret2", NativeHgRust.hg(workDir, repoRelPath, "cat", "-r", "1", "a.txt"),
                    "the untouched later revision must remain fully readable by real hg for combo " + combo);
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jRefusesToCensorAHeadRevisionMatchingRealHgAcrossDockerCombo(RequirementCombo combo) throws Exception {
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

            Files.writeString(hostRepoDir.resolve("a.txt"), "secret1\n");
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c0");

            String realCensorOut = expectDockerHgFailure(workDir, repoRelPath,
                    "sanity: real hg's own censor extension must refuse for combo " + combo,
                    "--config", "extensions.censor=", "censor", "-r", "0", "a.txt");
            assertTrue(realCensorOut.toLowerCase().contains("cannot censor"),
                    "sanity: real hg's own censor extension must refuse for combo " + combo + ": " + realCensorOut);

            File flIdx = CommitCommand.getFilelogIndex(hostRepoDir.resolve(".hg/store").toFile(), "a.txt");
            assertTrue(flIdx.exists(), "a.txt's filelog index must exist for combo " + combo);
            Revlog filelog = new Revlog(flIdx,
                    new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d"));
            String rev0Hex = NodeIdUtil.toHex(filelog.getIndexRecord(0).getNodeId());

            String hg4jRefusal = censorInSubprocess(hostRepoDir, "refuse", "a.txt", rev0Hex);
            assertTrue(hg4jRefusal.contains("cannot censor"),
                    "hg4j's CensorCommand must refuse to censor the sole head/working-directory-parent revision for combo "
                            + combo + ", got: " + hg4jRefusal);

            Revlog reread = new Revlog(flIdx,
                    new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d"));
            assertFalse(reread.isCensored(0), "a refused censor attempt must leave the revision untouched for combo " + combo);
        });
    }
}
