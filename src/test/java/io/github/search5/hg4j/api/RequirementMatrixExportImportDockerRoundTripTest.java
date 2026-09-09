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
import static org.junit.jupiter.api.Assertions.assertFalse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest}
 * for the full 30-combo design this reuses verbatim) applied to {@link ExportCommand}/{@link
 * ImportCommand} together -- the Docker-only counterpart of {@link
 * RequirementMatrixExportImportCoreRoundTripTest}'s native 6-combo scenario. Both hg4j-side steps
 * (the export direction's commit+export, and the import direction's patch application) run inside
 * {@link RequirementMatrixExportImportHelperMain}, a dedicated subprocess -- required for the same
 * reason {@link RequirementMatrixBundleDockerRoundTripTest} needs {@link
 * RequirementMatrixBundleHelperMain} (see {@link RequirementMatrixCommitHelperMain}'s javadoc for
 * the full root-cause writeup).
 *
 * <p>No {@code cl2+sidedata} tolerance is needed here (see {@link
 * RequirementMatrixExportImportCoreRoundTripTest}'s class javadoc for why: the confirmed real-hg
 * limitation is specific to {@code hg bundle}/{@code hg unbundle}'s FILE-based changegroup path,
 * which {@code hg import}/{@code hg export} never touch).
 */
@Tag("interop")
public class RequirementMatrixExportImportDockerRoundTripTest {

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


    /** Runs one hg4j-side step ({@code export}/{@code import}) in a dedicated subprocess; returns
     * the printed node hex. */
    private static String hg4jStepInSubprocess(String mode, Path repoDir, Path patchFile) throws Exception {
        HgRepository repo = new HgRepository(repoDir.toFile());

        if ("export".equals(mode)) {
            Files.writeString(repoDir.resolve("a.txt"), "hello from hg4j\n");
            Files.createDirectories(repoDir.resolve("dir"));
            Files.writeString(repoDir.resolve("dir").resolve("b.txt"), "nested content\n");
            new AddCommand(repo).call();
            byte[] commitNode = new CommitCommand(repo)
                    .setAuthor("hg4j export <export@example.com>")
                    .setDate(1700000000, 0)
                    .setMessage("hg4j export commit")
                    .call();
            String patch = new ExportCommand(repo).setRevision("0").call();
            Files.writeString(patchFile, patch);
            return NodeIdUtil.toHex(commitNode);
        } else if ("import".equals(mode)) {
            String patchText = Files.readString(patchFile);
            new ImportCommand(repo).setPatchText(patchText).call();

            File clIdx = new File(repo.getStoreDir(), "00changelog.i");
            File clDat = new File(repo.getStoreDir(), "00changelog.d");
            Revlog cl = repo.getRevlog(clIdx, clDat);
            byte[] tipNode = cl.getIndexRecord(cl.getRevisionCount() - 1).getNodeId();
            return NodeIdUtil.toHex(tipNode);
        } else {
            throw new IllegalArgumentException("Unknown mode: " + mode);
        }
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

    record ClEntry(String key, List<String> args) {
    }

    static Stream<RequirementCombo> combos() {
        List<RequirementCombo> out = new ArrayList<>();
        List<Map.Entry<String, List<String>>> dirstates = List.of(
                Map.entry("dirstate1", List.<String>of()), Map.entry("dirstate2", DIRSTATE_V2));
        List<ClEntry> changelogs = List.of(
                new ClEntry("cl1", CL_V1), new ClEntry("cl2", CL_V2),
                new ClEntry("cl2+sidedata", CL_V2_SIDEDATA));

        for (ClEntry cl : changelogs) {
            for (var tm : List.of(Map.entry("flatmanifest", List.<String>of()),
                    Map.entry("treemanifest", TREEMANIFEST))) {
                List<String> args = new ArrayList<>();
                args.addAll(DIRSTATE_V2);
                args.addAll(cl.args());
                args.addAll(tm.getValue());
                out.add(new RequirementCombo("dirstate2/" + cl.key() + "/" + tm.getKey() + "/none", args));
            }
        }

        for (var dirstate : dirstates) {
            for (ClEntry cl : changelogs) {
                for (var tm : List.of(Map.entry("flatmanifest", List.<String>of()),
                        Map.entry("treemanifest", TREEMANIFEST))) {
                    List<String> args = new ArrayList<>();
                    args.addAll(dirstate.getValue());
                    args.addAll(cl.args());
                    args.addAll(tm.getValue());
                    args.addAll(PERSISTENT_NODEMAP);
                    out.add(new RequirementCombo(dirstate.getKey() + "/" + cl.key() + "/" + tm.getKey() + "/pnodemap", args));
                }
                List<String> fileindexArgs = new ArrayList<>();
                fileindexArgs.addAll(dirstate.getValue());
                fileindexArgs.addAll(cl.args());
                fileindexArgs.addAll(FILEINDEX_V1);
                out.add(new RequirementCombo(dirstate.getKey() + "/" + cl.key() + "/flatmanifest/fileindex-v1", fileindexArgs));
                List<String> generalV2Args = new ArrayList<>();
                generalV2Args.addAll(dirstate.getValue());
                generalV2Args.addAll(cl.args());
                generalV2Args.addAll(GENERAL_V2);
                out.add(new RequirementCombo(dirstate.getKey() + "/" + cl.key() + "/flatmanifest/general-v2", generalV2Args));
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void exportImportRoundTripAcrossDockerCombo(RequirementCombo combo) throws Exception {
        NativeHgRust.withFreshWorkDir("hg4j-native-matrix", (workDir) -> {
            List<String> initArgs = new ArrayList<>(List.of("init", "."));
            for (String c : combo.initConfigArgs()) {
                initArgs.add("--config");
                initArgs.add(c);
            }

            // Direction 1: hg4j commits + exports (in a subprocess, against the container's
            // bind-mounted "export-src" dir); real hg (via docker exec) imports the resulting
            // patch text into a same-combo "export-dst".
            String exportSrcRel = "export-src";
            String exportDstRel = "export-dst";
            Path hostExportSrcDir = workDir.resolve(exportSrcRel);
            Path hostExportDstDir = workDir.resolve(exportDstRel);
            Files.createDirectories(hostExportSrcDir);
            Files.createDirectories(hostExportDstDir);
            NativeHgRust.hg(workDir, exportSrcRel, initArgs.toArray(new String[0]));
            NativeHgRust.hg(workDir, exportDstRel, initArgs.toArray(new String[0]));

            Path hostPatch1 = workDir.resolve("hg4j-export.patch");
            String hg4jNodeHex = hg4jStepInSubprocess("export", hostExportSrcDir, hostPatch1);

            NativeHgRust.hg(workDir, exportDstRel, "import", "/repo-root/hg4j-export.patch");
            String importedHex = NativeHgRust.hg(workDir, exportDstRel, "log", "-r", "tip", "--template", "{node}");
            assertEquals(hg4jNodeHex, importedHex,
                    "real hg import of hg4j's exported patch must reproduce a byte-identical commit node for combo " + combo);
            assertEquals("hello from hg4j", NativeHgRust.hg(workDir, exportDstRel, "cat", "-r", "tip", "a.txt"));
            assertEquals("nested content", NativeHgRust.hg(workDir, exportDstRel, "cat", "-r", "tip", "dir/b.txt"));
            String verifyB = NativeHgRust.hg(workDir, exportDstRel, "verify");
            assertFalse(verifyB.toLowerCase().contains("integrity error"),
                    "real hg verify must find no integrity errors after importing hg4j's exported patch for combo " + combo + ": " + verifyB);

            // Direction 2: real hg commits + exports; hg4j's ImportCommand (in a subprocess)
            // applies the resulting patch text to a same-combo "import-dst".
            String importSrcRel = "import-src";
            String importDstRel = "import-dst";
            Path hostImportSrcDir = workDir.resolve(importSrcRel);
            Path hostImportDstDir = workDir.resolve(importDstRel);
            Files.createDirectories(hostImportSrcDir);
            Files.createDirectories(hostImportDstDir);
            NativeHgRust.hg(workDir, importSrcRel, initArgs.toArray(new String[0]));
            NativeHgRust.hg(workDir, importDstRel, initArgs.toArray(new String[0]));

            Files.writeString(hostImportSrcDir.resolve("x.txt"), "hello from real hg\n");
            NativeHgRust.hg(workDir, importSrcRel, "add", "x.txt");
            Files.createDirectories(hostImportSrcDir.resolve("dir2"));
            Files.writeString(hostImportSrcDir.resolve("dir2").resolve("y.txt"), "nested from real hg\n");
            NativeHgRust.hg(workDir, importSrcRel, "add", "dir2/y.txt");
            NativeHgRust.hg(workDir, importSrcRel, "commit", "-u", "realhg", "-m", "real hg export commit for " + combo);
            String realHgNodeHex = NativeHgRust.hg(workDir, importSrcRel, "log", "-r", "0", "--template", "{node}");
            String realHgPatch = NativeHgRust.hg(workDir, importSrcRel, "export", "-r", "0");

            Path hostPatch2 = workDir.resolve("realhg-export.patch");
            Files.writeString(hostPatch2, realHgPatch);
            String hg4jImportedHex = hg4jStepInSubprocess("import", hostImportDstDir, hostPatch2);
            assertEquals(realHgNodeHex, hg4jImportedHex,
                    "hg4j's ImportCommand applying a real-hg-exported patch must reproduce the exact same commit node for combo " + combo);

            assertEquals("hello from real hg", NativeHgRust.hg(workDir, importDstRel, "cat", "-r", "tip", "x.txt"));
            assertEquals("nested from real hg", NativeHgRust.hg(workDir, importDstRel, "cat", "-r", "tip", "dir2/y.txt"));
            String verifyD = NativeHgRust.hg(workDir, importDstRel, "verify");
            assertFalse(verifyD.toLowerCase().contains("integrity error"),
                    "real hg verify must find no integrity errors after hg4j imported real hg's exported patch for combo " + combo + ": " + verifyD);
        });
    }
}
