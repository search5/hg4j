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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest}
 * for the full 30-combo design this reuses verbatim) applied to {@link SubrepoCommand} -- the
 * Docker-only counterpart of {@link RequirementMatrixSubrepoCoreRoundTripTest}'s native 6-combo
 * scenario (add + init pinned at v1, commit, bump the pin to v2, update, commit again),
 * re-verified by real hg.
 *
 * <p>Each case gets its own fresh, short-lived container (never a class-shared one), matching
 * {@link RequirementMatrixDockerRoundTripTest}'s own write-direction test and {@link
 * RequirementMatrixStripDockerRoundTripTest} -- this is a correctness-critical write path and the
 * parent class's javadoc documents a real, reproducible corruption symptom from reusing one
 * long-lived container across many write cases. hg4j's own subrepo add/init/commit/update/commit
 * write sequence runs in a dedicated {@code java} subprocess ({@link
 * RequirementMatrixSubrepoHelperMain}) rather than inline in this JVM, for the same
 * docker-exec-interleaving corruption reason documented on {@link RequirementMatrixCommitHelperMain}.
 */
@Tag("interop")
public class RequirementMatrixSubrepoDockerRoundTripTest {

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
        Path workDir = Files.createTempDirectory("hg4j-docker-subrepo-matrix").toRealPath();
        String containerName = "hg4j-reqmatrix-subrepo-" + UUID.randomUUID().toString().substring(0, 8);
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

    /** Runs hg4j's whole add/init/commit/bump/update/commit sequence in a dedicated subprocess;
     * returns the two parent commit hexes (first commit, second commit). */
    private static String[] subrepoRoundTripInSubprocess(Path parentDir, Path subSourceDir, String subV1, String subV2) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        String out = runHost(javaBin, "-cp", classpath, RequirementMatrixSubrepoHelperMain.class.getName(),
                parentDir.toString(), subSourceDir.toString(), subV1, subV2);
        String[] lines = out.strip().split("\\R");
        if (lines.length != 2) {
            throw new AssertionError("Expected exactly two lines of output from the subrepo helper, got: " + out);
        }
        return lines;
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
    public void hg4jSubrepoAddInitUpdateAcrossDockerCombo(RequirementCombo combo) throws Exception {
        withFreshContainer((containerName, workDir) -> {
            String subSourceRelPath = "sub-source";
            Path hostSubSourceDir = workDir.resolve(subSourceRelPath);
            Files.createDirectories(hostSubSourceDir);
            dockerHgIn(containerName, subSourceRelPath, "init", ".");
            Files.writeString(hostSubSourceDir.resolve("hello.txt"), "v1");
            dockerHgIn(containerName, subSourceRelPath, "add");
            dockerHgIn(containerName, subSourceRelPath, "commit", "-u", "dev", "-m", "sub v1");
            String subV1 = dockerHgIn(containerName, subSourceRelPath, "log", "-r", "tip", "--template", "{node}");
            Files.writeString(hostSubSourceDir.resolve("hello.txt"), "v2");
            dockerHgIn(containerName, subSourceRelPath, "commit", "-u", "dev", "-m", "sub v2");
            String subV2 = dockerHgIn(containerName, subSourceRelPath, "log", "-r", "tip", "--template", "{node}");

            String repoRelPath = "repo";
            Path hostRepoDir = workDir.resolve(repoRelPath);
            Files.createDirectories(hostRepoDir);
            List<String> initArgs = new ArrayList<>(List.of("init", "."));
            for (String c : combo.initConfigArgs()) {
                initArgs.add("--config");
                initArgs.add(c);
            }
            dockerHgIn(containerName, repoRelPath, initArgs.toArray(new String[0]));
            Files.writeString(hostRepoDir.resolve("init.txt"), "init\n");
            dockerHgIn(containerName, repoRelPath, "add");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "c0");

            String[] commitHexes = subrepoRoundTripInSubprocess(hostRepoDir, hostSubSourceDir, subV1, subV2);
            String parentC1Hex = commitHexes[0];
            String parentC2Hex = commitHexes[1];

            String verify1 = dockerHgIn(containerName, repoRelPath, "verify");
            assertTrue(verify1.toLowerCase().contains("0 integrity errors") || !verify1.toLowerCase().contains("error"),
                    "real hg verify must find no integrity errors after the first subrepo commit for combo " + combo + ": " + verify1);
            assertEquals(subV1 + " sub", dockerHgIn(containerName, repoRelPath, "cat", "-r", parentC1Hex, ".hgsubstate"),
                    "real hg must read back .hgsubstate pinned at v1 for combo " + combo);

            assertEquals("", dockerHgIn(containerName, repoRelPath + "/sub", "status"),
                    "real hg must see the subrepo working copy as clean after update for combo " + combo);
            assertEquals(subV2, dockerHgIn(containerName, repoRelPath + "/sub", "log", "-r", ".", "--template", "{node}"),
                    "real hg must see the subrepo checked out at exactly v2 after update for combo " + combo);

            String verify2 = dockerHgIn(containerName, repoRelPath, "verify");
            assertTrue(verify2.toLowerCase().contains("0 integrity errors") || !verify2.toLowerCase().contains("error"),
                    "real hg verify must find no integrity errors after the second subrepo commit for combo " + combo + ": " + verify2);
            assertEquals(subV2 + " sub", dockerHgIn(containerName, repoRelPath, "cat", "-r", parentC2Hex, ".hgsubstate"),
                    "real hg must read back .hgsubstate pinned at v2 for combo " + combo);
            assertEquals("", dockerHgIn(containerName, repoRelPath, "status"),
                    "working copy must be clean right after the second subrepo commit for combo " + combo);
            assertEquals(parentC1Hex, dockerHgIn(containerName, repoRelPath, "log", "-r", parentC2Hex, "--template", "{p1node}"),
                    "the second commit's parent must be the first for combo " + combo);
        });
    }
}
