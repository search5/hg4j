package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest}
 * for the full 30-combo design this reuses verbatim) applied to {@link VerifyCommand} -- the
 * Docker-only counterpart of {@link RequirementMatrixVerifyCoreRoundTripTest}'s native 6-combo
 * scenarios (a healthy repository, a corrupted filelog, and -- treemanifest combos only -- a
 * corrupted treemanifest submanifest revlog under {@code meta/}), re-verified by real hg.
 *
 * <p>Each case gets its own fresh, short-lived container (never a class-shared one), matching
 * {@link RequirementMatrixBackoutDockerRoundTripTest}. {@link VerifyCommand} itself never writes
 * anything, but hg4j's read still runs in a dedicated {@code java} subprocess ({@link
 * RequirementMatrixVerifyHelperMain}) for consistency with every other matrix test in this
 * package -- see that class's javadoc.
 */
@Tag("interop")
public class RequirementMatrixVerifyDockerRoundTripTest {

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

    /** Runs hg4j's {@link VerifyCommand} in a dedicated subprocess; returns each error on its own
     * line (empty string when the repository verifies clean). */
    private static String verifyInSubprocess(Path repoDir) throws Exception {
        HgRepository repo = new HgRepository(repoDir.toFile());
        List<String> errors = new VerifyCommand(repo).call();
        StringBuilder sb = new StringBuilder();
        for (String e : errors) {
            sb.append(e).append('\n');
        }
        return sb.toString();
    }

    /** One point in the Docker-only quarter of the requirement matrix -- identical generation to
     * {@link RequirementMatrixDockerRoundTripTest#combos()} (see {@link
     * RequirementMatrixStripDockerRoundTripTest} for why this is copied rather than shared). */
    record RequirementCombo(String label, List<String> initConfigArgs, boolean treemanifest) {
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
                out.add(new RequirementCombo("dirstate2/" + cl.getKey() + "/" + tm.getKey() + "/none", args,
                        tm.getKey().equals("treemanifest")));
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
                    out.add(new RequirementCombo(dirstate.getKey() + "/" + cl.getKey() + "/" + tm.getKey() + "/pnodemap", args,
                            tm.getKey().equals("treemanifest")));
                }
                List<String> fileindexArgs = new ArrayList<>();
                fileindexArgs.addAll(dirstate.getValue());
                fileindexArgs.addAll(cl.getValue());
                fileindexArgs.addAll(FILEINDEX_V1);
                out.add(new RequirementCombo(dirstate.getKey() + "/" + cl.getKey() + "/flatmanifest/fileindex-v1", fileindexArgs, false));
                List<String> generalV2Args = new ArrayList<>();
                generalV2Args.addAll(dirstate.getValue());
                generalV2Args.addAll(cl.getValue());
                generalV2Args.addAll(GENERAL_V2);
                out.add(new RequirementCombo(dirstate.getKey() + "/" + cl.getKey() + "/flatmanifest/general-v2", generalV2Args, false));
            }
        }
        return out.stream();
    }

    static Stream<RequirementCombo> treemanifestCombos() {
        return combos().filter(RequirementCombo::treemanifest);
    }

    private static void buildNestedHistory(Path workDir, String repoRelPath, Path hostRepoDir) throws Exception {
        Files.createDirectories(hostRepoDir.resolve("sub/deep"));
        Files.writeString(hostRepoDir.resolve("a.txt"), "root v1\n");
        Files.writeString(hostRepoDir.resolve("sub/b.txt"), "sub v1\n");
        Files.writeString(hostRepoDir.resolve("sub/deep/c.txt"), "deep v1\n");
        NativeHgRust.hg(workDir, repoRelPath, "add");
        NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c0");

        Files.writeString(hostRepoDir.resolve("a.txt"), "root v2\n");
        Files.writeString(hostRepoDir.resolve("sub/deep/c.txt"), "deep v2\n");
        NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c1");
    }

    /**
     * general-v2 (`experimental.revlogv2=...`) splits each revlog into a tiny docket "pointer"
     * file at the classic {@code .i} path (magic {@code 00 00 de ad}, confirmed live via
     * `hg-rust-7.2.4`: {@code od -A d -t x1z data/sub/b.txt.i}) plus the REAL per-revision index
     * records in a companion {@code <basename>-<hash>.idx} file in the same directory -- unlike
     * every other storage-extension combo (including fileindex-v1), where the classic {@code .i}
     * path already holds the real 64(+)-byte index records directly, node id included at the same
     * fixed offset 32 this method targets. Resolves to that companion file when the docket magic
     * is present, otherwise returns {@code flIdx} unchanged.
     */
    private static File resolveActualIndexFileForCorruption(File flIdx) throws IOException {
        byte[] header = new byte[4];
        try (RandomAccessFile raf = new RandomAccessFile(flIdx, "r")) {
            int read = raf.read(header);
            if (read == 4 && (header[0] & 0xFF) == 0x00 && (header[1] & 0xFF) == 0x00
                    && (header[2] & 0xFF) == 0xde && (header[3] & 0xFF) == 0xad) {
                String base = flIdx.getName().substring(0, flIdx.getName().length() - 2);
                File dir = flIdx.getParentFile();
                File[] candidates = dir.listFiles((d, name) -> name.startsWith(base + "-") && name.endsWith(".idx"));
                if (candidates != null && candidates.length == 1) {
                    return candidates[0];
                }
            }
        }
        return flIdx;
    }

    private static void corruptNodeIdOfFirstRevision(File idxFile) throws Exception {
        File actualIdx = resolveActualIndexFileForCorruption(idxFile);
        try (RandomAccessFile raf = new RandomAccessFile(actualIdx, "rw")) {
            raf.seek(32);
            raf.write(new byte[]{9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9});
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jVerifiesAHealthyRealHgRepositoryAcrossDockerCombo(RequirementCombo combo) throws Exception {
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
            buildNestedHistory(workDir, repoRelPath, hostRepoDir);

            String realVerify = NativeHgRust.hg(workDir, repoRelPath, "verify");
            assertFalse(realVerify.toLowerCase().contains("error"),
                    "sanity: real hg itself must verify this repository clean for combo " + combo + ": " + realVerify);

            String hg4jErrors = verifyInSubprocess(hostRepoDir);
            assertTrue(hg4jErrors.isBlank(), "hg4j's VerifyCommand must report zero errors on a healthy real-hg-built "
                    + "repository for combo " + combo + ", but got: " + hg4jErrors);
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jDetectsFilelogCorruptionAcrossDockerCombo(RequirementCombo combo) throws Exception {
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
            buildNestedHistory(workDir, repoRelPath, hostRepoDir);

            File flIdx = CommitCommand.getFilelogIndex(hostRepoDir.resolve(".hg/store").toFile(), "sub/b.txt");
            assertTrue(flIdx.exists(), "sub/b.txt's filelog index must exist for combo " + combo);
            corruptNodeIdOfFirstRevision(flIdx);

            String realVerify;
            try {
                realVerify = NativeHgRust.hg(workDir, repoRelPath, "verify");
            } catch (AssertionError e) {
                realVerify = e.getMessage();
            }
            assertTrue(realVerify.toLowerCase().contains("error") || realVerify.toLowerCase().contains("integrity"),
                    "sanity: real hg itself must flag this corrupted filelog for combo " + combo + ": " + realVerify);

            String hg4jErrors = verifyInSubprocess(hostRepoDir);
            assertFalse(hg4jErrors.isBlank(), "hg4j's VerifyCommand must detect the corrupted filelog for combo " + combo);
            assertTrue(hg4jErrors.contains("b.txt"),
                    "the error must name the corrupted file for combo " + combo + ", got: " + hg4jErrors);
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("treemanifestCombos")
    public void hg4jDetectsTreemanifestSubmanifestCorruptionAcrossDockerCombo(RequirementCombo combo) throws Exception {
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
            buildNestedHistory(workDir, repoRelPath, hostRepoDir);

            File subManifestIdx = hostRepoDir.resolve(".hg/store/meta/sub/deep/00manifest.i").toFile();
            assertTrue(subManifestIdx.exists(), "sub/deep's submanifest revlog must exist for treemanifest combo " + combo);
            corruptNodeIdOfFirstRevision(subManifestIdx);

            String realVerify;
            try {
                realVerify = NativeHgRust.hg(workDir, repoRelPath, "verify");
            } catch (AssertionError e) {
                realVerify = e.getMessage();
            }
            assertTrue(realVerify.toLowerCase().contains("error") || realVerify.toLowerCase().contains("integrity"),
                    "sanity: real hg itself must flag this corrupted submanifest for combo " + combo + ": " + realVerify);

            String hg4jErrors = verifyInSubprocess(hostRepoDir);
            assertFalse(hg4jErrors.isBlank(), "hg4j's VerifyCommand must detect the corrupted treemanifest submanifest "
                    + "for combo " + combo + " -- before the backlog #39 wave 5 fix this silently reported zero errors");
            assertTrue(hg4jErrors.contains("meta/sub/deep/00manifest.i"),
                    "the error must name the corrupted submanifest path for combo " + combo + ", got: " + hg4jErrors);
        });
    }
}
