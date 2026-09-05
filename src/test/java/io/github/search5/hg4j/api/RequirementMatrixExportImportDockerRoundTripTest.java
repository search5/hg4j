package io.github.search5.hg4j.api;

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

    private static final String IMAGE = "localhost/hg-rust-7.2.4";
    private static String hostUidGid;
    private static boolean dockerReady = false;

    @BeforeAll
    static void checkDocker() throws Exception {
        dockerReady = isDockerAvailable() && isImageAvailable();
        Assumptions.assumeTrue(dockerReady,
                "Docker (or the localhost/hg-rust-7.2.4 image) is not available. Skipping the whole class.");
        hostUidGid = runHost("id", "-u").trim() + ":" + runHost("id", "-g").trim();
    }

    private static boolean isDockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isImageAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "image", "inspect", IMAGE).redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
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

    @FunctionalInterface
    private interface FreshContainerTest {
        void run(String containerName, Path workDir) throws Exception;
    }

    private static void withFreshContainer(FreshContainerTest test) throws Exception {
        Path workDir = Files.createTempDirectory("hg4j-docker-exportimport-matrix").toRealPath();
        String containerName = "hg4j-reqmatrix-exportimport-" + UUID.randomUUID().toString().substring(0, 8);
        runHost("docker", "run", "-d", "--rm", "--name", containerName,
                "-v", workDir + ":/repo-root", IMAGE, "sleep", "infinity");
        try {
            Exception last = null;
            for (int i = 0; i < 20; i++) {
                try {
                    runHost("docker", "exec", "--user", hostUidGid, containerName, "hg", "--version");
                    last = null;
                    break;
                } catch (Exception e) {
                    last = e;
                    Thread.sleep(250);
                }
            }
            if (last != null) {
                throw new AssertionError("Container " + containerName + " never became ready", last);
            }
            test.run(containerName, workDir);
        } finally {
            try {
                new ProcessBuilder("docker", "stop", containerName).redirectErrorStream(true).start().waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best effort
            }
            deleteRecursively(workDir.toFile());
        }
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        f.delete();
    }

    private static String dockerHgIn(String container, String repoRelPath, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("docker", "exec", "--user", hostUidGid,
                "-w", "/repo-root/" + repoRelPath, container, "hg"));
        cmd.addAll(Arrays.asList(args));
        return runHost(cmd.toArray(new String[0])).trim();
    }

    /** Runs one hg4j-side step ({@code export}/{@code import}) in a dedicated subprocess; returns
     * the printed node hex. */
    private static String hg4jStepInSubprocess(String mode, Path repoDir, Path patchFile) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        return runHost(javaBin, "-cp", classpath, RequirementMatrixExportImportHelperMain.class.getName(),
                mode, repoDir.toString(), patchFile.toString()).trim();
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
        List<java.util.Map.Entry<String, List<String>>> dirstates = List.of(
                java.util.Map.entry("dirstate1", List.<String>of()), java.util.Map.entry("dirstate2", DIRSTATE_V2));
        List<ClEntry> changelogs = List.of(
                new ClEntry("cl1", CL_V1), new ClEntry("cl2", CL_V2),
                new ClEntry("cl2+sidedata", CL_V2_SIDEDATA));

        for (ClEntry cl : changelogs) {
            for (var tm : List.of(java.util.Map.entry("flatmanifest", List.<String>of()),
                    java.util.Map.entry("treemanifest", TREEMANIFEST))) {
                List<String> args = new ArrayList<>();
                args.addAll(DIRSTATE_V2);
                args.addAll(cl.args());
                args.addAll(tm.getValue());
                out.add(new RequirementCombo("dirstate2/" + cl.key() + "/" + tm.getKey() + "/none", args));
            }
        }

        for (var dirstate : dirstates) {
            for (ClEntry cl : changelogs) {
                for (var tm : List.of(java.util.Map.entry("flatmanifest", List.<String>of()),
                        java.util.Map.entry("treemanifest", TREEMANIFEST))) {
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
        withFreshContainer((containerName, workDir) -> {
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
            dockerHgIn(containerName, exportSrcRel, initArgs.toArray(new String[0]));
            dockerHgIn(containerName, exportDstRel, initArgs.toArray(new String[0]));

            Path hostPatch1 = workDir.resolve("hg4j-export.patch");
            String hg4jNodeHex = hg4jStepInSubprocess("export", hostExportSrcDir, hostPatch1);

            dockerHgIn(containerName, exportDstRel, "import", "/repo-root/hg4j-export.patch");
            String importedHex = dockerHgIn(containerName, exportDstRel, "log", "-r", "tip", "--template", "{node}");
            assertEquals(hg4jNodeHex, importedHex,
                    "real hg import of hg4j's exported patch must reproduce a byte-identical commit node for combo " + combo);
            assertEquals("hello from hg4j", dockerHgIn(containerName, exportDstRel, "cat", "-r", "tip", "a.txt"));
            assertEquals("nested content", dockerHgIn(containerName, exportDstRel, "cat", "-r", "tip", "dir/b.txt"));
            String verifyB = dockerHgIn(containerName, exportDstRel, "verify");
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
            dockerHgIn(containerName, importSrcRel, initArgs.toArray(new String[0]));
            dockerHgIn(containerName, importDstRel, initArgs.toArray(new String[0]));

            Files.writeString(hostImportSrcDir.resolve("x.txt"), "hello from real hg\n");
            dockerHgIn(containerName, importSrcRel, "add", "x.txt");
            Files.createDirectories(hostImportSrcDir.resolve("dir2"));
            Files.writeString(hostImportSrcDir.resolve("dir2").resolve("y.txt"), "nested from real hg\n");
            dockerHgIn(containerName, importSrcRel, "add", "dir2/y.txt");
            dockerHgIn(containerName, importSrcRel, "commit", "-u", "realhg", "-m", "real hg export commit for " + combo);
            String realHgNodeHex = dockerHgIn(containerName, importSrcRel, "log", "-r", "0", "--template", "{node}");
            String realHgPatch = dockerHgIn(containerName, importSrcRel, "export", "-r", "0");

            Path hostPatch2 = workDir.resolve("realhg-export.patch");
            Files.writeString(hostPatch2, realHgPatch);
            String hg4jImportedHex = hg4jStepInSubprocess("import", hostImportDstDir, hostPatch2);
            assertEquals(realHgNodeHex, hg4jImportedHex,
                    "hg4j's ImportCommand applying a real-hg-exported patch must reproduce the exact same commit node for combo " + combo);

            assertEquals("hello from real hg", dockerHgIn(containerName, importDstRel, "cat", "-r", "tip", "x.txt"));
            assertEquals("nested from real hg", dockerHgIn(containerName, importDstRel, "cat", "-r", "tip", "dir2/y.txt"));
            String verifyD = dockerHgIn(containerName, importDstRel, "verify");
            assertFalse(verifyD.toLowerCase().contains("integrity error"),
                    "real hg verify must find no integrity errors after hg4j imported real hg's exported patch for combo " + combo + ": " + verifyD);
        });
    }
}
