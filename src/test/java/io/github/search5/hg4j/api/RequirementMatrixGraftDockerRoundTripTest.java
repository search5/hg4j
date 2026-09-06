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
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest}
 * for the full 30-combo design this reuses verbatim) applied to {@link GraftCommand} -- the
 * Docker-only counterpart of {@link RequirementMatrixGraftCoreRoundTripTest}'s native 6-combo
 * scenario (a conflict-free graft plus a graft that genuinely conflicts, resumed via {@link
 * GraftCommand#continueGraft()}, both re-verified by real hg).
 *
 * <p>Each case gets its own fresh, short-lived container (never a class-shared one), matching
 * {@link RequirementMatrixRebaseDockerRoundTripTest}/{@link RequirementMatrixStripDockerRoundTripTest}
 * -- this is a correctness-critical write path and the parent class's javadoc documents a real,
 * reproducible corruption symptom from reusing one long-lived container across many write cases.
 * hg4j's own graft write runs in a dedicated {@code java} subprocess ({@link
 * RequirementMatrixGraftHelperMain}) rather than inline in this JVM, for the same
 * docker-exec-interleaving corruption reason documented on {@link RequirementMatrixCommitHelperMain}.
 */
@Tag("interop")
public class RequirementMatrixGraftDockerRoundTripTest {

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
        Path workDir = Files.createTempDirectory("hg4j-docker-graft-matrix").toRealPath();
        String containerName = "hg4j-reqmatrix-graft-" + UUID.randomUUID().toString().substring(0, 8);
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
                new ProcessBuilder("docker", "stop", containerName).redirectErrorStream(true).start().waitFor(15, TimeUnit.SECONDS);
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

    /** Runs {@code hg4j}'s graft {@code call()} in a dedicated subprocess. Returns {@code "OK
     * <hex>"} on a clean graft, or {@code "CONFLICT <comma-separated-paths>"} if it paused. */
    private static String graftCallInSubprocess(Path repoDir, String sourceHex) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        String out = runHost(javaBin, "-cp", classpath, RequirementMatrixGraftHelperMain.class.getName(),
                "call", repoDir.toString(), sourceHex);
        return out.trim();
    }

    /** Runs {@code hg4j}'s {@link GraftCommand#continueGraft()} in a dedicated subprocess. */
    private static String graftContinueInSubprocess(Path repoDir) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        String out = runHost(javaBin, "-cp", classpath, RequirementMatrixGraftHelperMain.class.getName(),
                "continue", repoDir.toString());
        return out.trim();
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
    public void hg4jGraftAcrossDockerCombo(RequirementCombo combo) throws Exception {
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

            // --- Scenario 1: conflict-free graft of a diverging branch ---
            Files.writeString(hostRepoDir.resolve("base.txt"), "base\n");
            dockerHgIn(containerName, repoRelPath, "add");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "c0");

            Files.writeString(hostRepoDir.resolve("target.txt"), "on-target\n");
            dockerHgIn(containerName, repoRelPath, "add");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "c1 target");
            String targetHex = dockerHgIn(containerName, repoRelPath, "log", "-r", ".", "--template", "{node}");

            dockerHgIn(containerName, repoRelPath, "update", "0");
            Files.writeString(hostRepoDir.resolve("source.txt"), "on-source\n");
            dockerHgIn(containerName, repoRelPath, "add");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "c2 source");
            String sourceHex = dockerHgIn(containerName, repoRelPath, "log", "-r", ".", "--template", "{node}");

            dockerHgIn(containerName, repoRelPath, "update", targetHex);

            String callResult = graftCallInSubprocess(hostRepoDir, sourceHex);
            assertTrue(callResult.startsWith("OK "), "expected a clean graft for combo " + combo + ": " + callResult);
            String graftedHex = callResult.substring("OK ".length()).trim();

            String verify = dockerHgIn(containerName, repoRelPath, "verify");
            assertFalse(verify.toLowerCase().contains("integrity error"),
                    "real hg verify must find no integrity errors after graft for combo " + combo + ": " + verify);

            String graftedParent = dockerHgIn(containerName, repoRelPath, "log", "-r", graftedHex, "--template", "{p1node}");
            assertEquals(targetHex, graftedParent, "grafted commit's parent must be the destination for combo " + combo);

            String catTarget = dockerHgIn(containerName, repoRelPath, "cat", "-r", graftedHex, "target.txt");
            assertEquals("on-target", catTarget.trim());
            String catSource = dockerHgIn(containerName, repoRelPath, "cat", "-r", graftedHex, "source.txt");
            assertEquals("on-source", catSource.trim());

            String logAll = dockerHgIn(containerName, repoRelPath, "log", "--template", "{node} ");
            assertTrue(logAll.contains(sourceHex),
                    "a plain graft must never hide its source revision (no obsmarker) for combo " + combo);

            // --- Scenario 2: a graft that genuinely conflicts, then continueGraft() ---
            dockerHgIn(containerName, repoRelPath, "update", graftedHex);
            Files.writeString(hostRepoDir.resolve("conflict.txt"), "line1\n");
            dockerHgIn(containerName, repoRelPath, "add");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "c3 conflict base");
            String conflictBaseHex = dockerHgIn(containerName, repoRelPath, "log", "-r", ".", "--template", "{node}");

            Files.writeString(hostRepoDir.resolve("conflict.txt"), "line1-dest\n");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "c4 dest modifies conflict.txt");
            String destHex = dockerHgIn(containerName, repoRelPath, "log", "-r", ".", "--template", "{node}");

            dockerHgIn(containerName, repoRelPath, "update", conflictBaseHex);
            Files.writeString(hostRepoDir.resolve("conflict.txt"), "line1-source\n");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "c5 source modifies conflict.txt (conflicts with dest)");
            String conflictSourceHex = dockerHgIn(containerName, repoRelPath, "log", "-r", ".", "--template", "{node}");

            dockerHgIn(containerName, repoRelPath, "update", destHex);

            String conflictCallResult = graftCallInSubprocess(hostRepoDir, conflictSourceHex);
            assertTrue(conflictCallResult.startsWith("CONFLICT "),
                    "expected a paused, conflicted graft for combo " + combo + ": " + conflictCallResult);
            assertEquals("CONFLICT conflict.txt", conflictCallResult);

            String resolveList = dockerHgIn(containerName, repoRelPath, "resolve", "--list");
            assertEquals("U conflict.txt", resolveList.trim(),
                    "real hg must see the same unresolved-file bookkeeping for combo " + combo);

            Files.writeString(hostRepoDir.resolve("conflict.txt"), "line1-dest\nline1-source\n");
            dockerHgIn(containerName, repoRelPath, "resolve", "--mark", "conflict.txt");

            String continueResult = graftContinueInSubprocess(hostRepoDir);
            assertTrue(continueResult.startsWith("OK "), "continueGraft() must succeed for combo " + combo + ": " + continueResult);
            String continuedHex = continueResult.substring("OK ".length()).trim();

            String verify2 = dockerHgIn(containerName, repoRelPath, "verify");
            assertFalse(verify2.toLowerCase().contains("integrity error"),
                    "real hg verify must find no integrity errors after continueGraft() for combo " + combo + ": " + verify2);

            String catConflict = dockerHgIn(containerName, repoRelPath, "cat", "-r", continuedHex, "conflict.txt");
            assertEquals("line1-dest\nline1-source", catConflict.trim());

            String resolveListAfter = dockerHgIn(containerName, repoRelPath, "resolve", "--list");
            assertEquals("", resolveListAfter.trim(), "no unresolved files must remain for combo " + combo);
        });
    }
}
