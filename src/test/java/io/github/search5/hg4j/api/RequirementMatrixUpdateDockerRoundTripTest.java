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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest}
 * for the full 30-combo design this reuses verbatim) applied to {@link UpdateCommand} -- the
 * Docker-only counterpart of {@link RequirementMatrixUpdateCoreRoundTripTest}'s single
 * comprehensive round-trip scenario (modify/add/remove/exec-bit-flip/symlink, checked out
 * backward then forward again), re-verified by real hg running inside {@code hg-rust-7.2.4}.
 *
 * <p>Each case gets its own fresh, short-lived container (never a class-shared one), matching
 * {@link RequirementMatrixMergeDockerRoundTripTest}/{@link RequirementMatrixStripDockerRoundTripTest}.
 * {@link UpdateCommand} never appends a revlog revision (see its class javadoc), so -- unlike most
 * other requirement-matrix Docker suites in this package -- there is no revlog-write corruption
 * risk from interleaving hg4j calls with {@code docker exec}/{@code docker run} child processes;
 * {@link RequirementMatrixUpdateHelperMain} is still used purely for pattern parity.
 */
@Tag("interop")
public class RequirementMatrixUpdateDockerRoundTripTest {

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
        Path workDir = Files.createTempDirectory("hg4j-docker-update-matrix").toRealPath();
        String containerName = "hg4j-reqmatrix-update-" + UUID.randomUUID().toString().substring(0, 8);
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

    /** Checks out {@code revision} via hg4j's {@link UpdateCommand} in a dedicated subprocess --
     * see the class javadoc for why this isn't strictly required for correctness here. */
    private static void updateInSubprocess(Path repoDir, String revision) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        runHost(javaBin, "-cp", classpath, RequirementMatrixUpdateHelperMain.class.getName(), repoDir.toString(), revision);
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
        List<java.util.Map.Entry<String, List<String>>> dirstates = List.of(
                java.util.Map.entry("dirstate1", List.<String>of()), java.util.Map.entry("dirstate2", DIRSTATE_V2));
        List<java.util.Map.Entry<String, List<String>>> changelogs = List.of(
                java.util.Map.entry("cl1", CL_V1), java.util.Map.entry("cl2", CL_V2),
                java.util.Map.entry("cl2+sidedata", CL_V2_SIDEDATA));

        for (var cl : changelogs) {
            for (var tm : List.of(java.util.Map.entry("flatmanifest", List.<String>of()),
                    java.util.Map.entry("treemanifest", TREEMANIFEST))) {
                List<String> args = new ArrayList<>();
                args.addAll(DIRSTATE_V2);
                args.addAll(cl.getValue());
                args.addAll(tm.getValue());
                out.add(new RequirementCombo("dirstate2/" + cl.getKey() + "/" + tm.getKey() + "/none", args));
            }
        }

        for (var dirstate : dirstates) {
            for (var cl : changelogs) {
                for (var tm : List.of(java.util.Map.entry("flatmanifest", List.<String>of()),
                        java.util.Map.entry("treemanifest", TREEMANIFEST))) {
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
    public void hg4jUpdateRoundTripMatchesRealHgAcrossDockerCombo(RequirementCombo combo) throws Exception {
        withFreshContainer((containerName, workDir) -> {
            String repoRelPath = "repo";
            Path hostRepoDir = workDir.resolve(repoRelPath);
            Files.createDirectories(hostRepoDir);

            List<String> initArgs = new ArrayList<>(List.of("init", "."));
            for (String c : combo.initConfigArgs()) {
                initArgs.add("--config");
                initArgs.add(c);
            }
            dockerHgIn(containerName, repoRelPath, initArgs.toArray(new String[0]));

            Files.writeString(hostRepoDir.resolve("root.txt"), "v0\n");
            Files.createDirectories(hostRepoDir.resolve("sub"));
            Files.writeString(hostRepoDir.resolve("sub/nested.txt"), "nested v0\n");
            Files.writeString(hostRepoDir.resolve("exec.sh"), "echo hi\n");
            hostRepoDir.resolve("exec.sh").toFile().setExecutable(true, false);
            Files.createSymbolicLink(hostRepoDir.resolve("link.txt"), Path.of("root.txt"));
            dockerHgIn(containerName, repoRelPath, "add");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "c0");
            String c0Hex = dockerHgIn(containerName, repoRelPath, "log", "-r", ".", "--template", "{node}");

            Files.writeString(hostRepoDir.resolve("root.txt"), "v1\n");
            Files.writeString(hostRepoDir.resolve("newfile.txt"), "new\n");
            Files.delete(hostRepoDir.resolve("sub/nested.txt"));
            hostRepoDir.resolve("exec.sh").toFile().setExecutable(false, false);
            dockerHgIn(containerName, repoRelPath, "add");
            dockerHgIn(containerName, repoRelPath, "remove", "sub/nested.txt");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "c1");
            String c1Hex = dockerHgIn(containerName, repoRelPath, "log", "-r", ".", "--template", "{node}");

            // Step 1: hg4j checks out c0 (backward from the real-hg-checked-out c1).
            updateInSubprocess(hostRepoDir, c0Hex);

            assertEquals("v0\n", Files.readString(hostRepoDir.resolve("root.txt")), "root.txt must revert to v0 for combo " + combo);
            assertTrue(Files.exists(hostRepoDir.resolve("sub/nested.txt")), "removed-in-c1 nested file must reappear at c0 for combo " + combo);
            assertFalse(Files.exists(hostRepoDir.resolve("newfile.txt")), "added-in-c1 file must not exist at c0 for combo " + combo);
            assertTrue(Files.isExecutable(hostRepoDir.resolve("exec.sh")), "exec bit must be restored at c0 for combo " + combo);
            assertEquals(c0Hex, dockerHgIn(containerName, repoRelPath, "log", "-r", ".", "--template", "{node}"),
                    "real hg reading hg4j's dirstate must agree the working copy is at c0 for combo " + combo);
            assertEquals("", dockerHgIn(containerName, repoRelPath, "status"), "working copy must be clean after checkout to c0 for combo " + combo);

            // Step 2: hg4j checks out c1 again (forward).
            updateInSubprocess(hostRepoDir, c1Hex);

            assertEquals("v1\n", Files.readString(hostRepoDir.resolve("root.txt")), "root.txt must advance to v1 for combo " + combo);
            assertEquals("new\n", Files.readString(hostRepoDir.resolve("newfile.txt")), "newfile.txt must reappear at c1 for combo " + combo);
            assertFalse(Files.exists(hostRepoDir.resolve("sub/nested.txt")), "nested.txt must be removed again at c1 for combo " + combo);
            assertFalse(Files.exists(hostRepoDir.resolve("sub")), "the now-empty sub directory must be cleaned up for combo " + combo);
            assertFalse(Files.isExecutable(hostRepoDir.resolve("exec.sh")), "exec bit must be cleared again at c1 for combo " + combo);
            assertEquals(c1Hex, dockerHgIn(containerName, repoRelPath, "log", "-r", ".", "--template", "{node}"),
                    "real hg reading hg4j's dirstate must agree the working copy is at c1 for combo " + combo);
            assertEquals("", dockerHgIn(containerName, repoRelPath, "status"), "working copy must be clean after checkout to c1 for combo " + combo);
        });
    }
}
