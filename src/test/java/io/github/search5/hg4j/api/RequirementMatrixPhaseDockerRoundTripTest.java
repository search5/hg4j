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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest}
 * for the full 30-combo design this reuses verbatim) applied to {@link PhaseCommand} -- the
 * Docker-only counterpart of {@link RequirementMatrixPhaseCoreRoundTripTest}'s native 6-combo
 * two-repo byte-for-byte {@code phaseroots} comparison, extended to dirstate v2, persistent
 * nodemap, fileindex-v1 and general-delta-v2 combos.
 *
 * <p>Two repositories (same combo, same fixed-date commits so hashes match byte-for-byte) live
 * side by side in the same container's bind-mounted workdir: {@code repoA} is mutated only via
 * {@link PhaseCommand} (through {@link RequirementMatrixPhaseHelperMain}, isolated in its own
 * subprocess for the same docker-exec-interleaving reason as every other write-direction matrix
 * test here), {@code repoB} only via the real {@code hg phase} CLI. {@code phaseroots} is read
 * directly off the host-mounted directory (no extra {@code docker exec} needed) and diffed after
 * every step.
 */
@Tag("interop")
public class RequirementMatrixPhaseDockerRoundTripTest {

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
        Path workDir = Files.createTempDirectory("hg4j-docker-phase-matrix").toRealPath();
        String containerName = "hg4j-reqmatrix-phase-" + UUID.randomUUID().toString().substring(0, 8);
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

    private static void phaseInSubprocess(Path repoDir, String revision, int targetPhase, boolean force) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        runHost(javaBin, "-cp", classpath, RequirementMatrixPhaseHelperMain.class.getName(),
                repoDir.toString(), revision, Integer.toString(targetPhase), Boolean.toString(force));
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

    private static int nativePhaseOf(String container, String repoRelPath, int rev) throws Exception {
        String out = dockerHgIn(container, repoRelPath, "phase", "-r", String.valueOf(rev));
        String name = out.substring(out.indexOf(':') + 1).trim();
        return switch (name) {
            case "public" -> 0;
            case "draft" -> 1;
            case "secret" -> 2;
            default -> throw new AssertionError("unrecognized hg phase output: " + out);
        };
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jPhaseAcrossDockerCombo(RequirementCombo combo) throws Exception {
        withFreshContainer((containerName, workDir) -> {
            String relA = "repoA";
            String relB = "repoB";
            Path hostRepoA = workDir.resolve(relA);
            Path hostRepoB = workDir.resolve(relB);
            Files.createDirectories(hostRepoA);
            Files.createDirectories(hostRepoB);

            List<String> initArgs = new ArrayList<>(List.of("init", "."));
            for (String c : combo.initConfigArgs()) {
                initArgs.add("--config");
                initArgs.add(c);
            }
            dockerHgIn(containerName, relA, initArgs.toArray(new String[0]));
            dockerHgIn(containerName, relB, initArgs.toArray(new String[0]));

            // Three linear commits at a fixed date in both repos, via the real hg CLI only, so
            // the resulting node hashes are byte-identical -- hg4j never touches revlogs here,
            // only phaseroots, via PhaseCommand below.
            for (int i = 0; i < 3; i++) {
                Files.writeString(hostRepoA.resolve("f" + i + ".txt"), "content-" + i + "\n");
                Files.writeString(hostRepoB.resolve("f" + i + ".txt"), "content-" + i + "\n");
                dockerHgIn(containerName, relA, "add");
                dockerHgIn(containerName, relB, "add");
                dockerHgIn(containerName, relA, "commit", "-u", "dev", "-d", "0 0", "-m", "c" + i);
                dockerHgIn(containerName, relB, "commit", "-u", "dev", "-d", "0 0", "-m", "c" + i);
            }

            for (int rev = 0; rev < 3; rev++) {
                String hexA = dockerHgIn(containerName, relA, "log", "-r", String.valueOf(rev), "--template", "{node}");
                String hexB = dockerHgIn(containerName, relB, "log", "-r", String.valueOf(rev), "--template", "{node}");
                assertEquals(hexB, hexA, "combo " + combo + ": rev " + rev + " hash must match between the two repos");
            }

            File phaseRootsA = new File(new HgRepository(hostRepoA.toFile()).getStoreDir(), "phaseroots");
            File phaseRootsB = new File(new HgRepository(hostRepoB.toFile()).getStoreDir(), "phaseroots");
            assertArrayEquals(Files.readAllBytes(phaseRootsB.toPath()), Files.readAllBytes(phaseRootsA.toPath()),
                    "combo " + combo + ": phaseroots must match immediately after the initial commits");

            // 1. Advance rev1 to public (no force needed).
            phaseInSubprocess(hostRepoA, "1", 0, false);
            dockerHgIn(containerName, relB, "phase", "-r", "1", "--public");
            assertArrayEquals(Files.readAllBytes(phaseRootsB.toPath()), Files.readAllBytes(phaseRootsA.toPath()),
                    "combo " + combo + ": phaseroots mismatch after advancing rev1 to public");
            for (int rev = 0; rev < 3; rev++) {
                assertEquals(nativePhaseOf(containerName, relB, rev),
                        new PhaseCommand(new HgRepository(hostRepoA.toFile())).setRevision(String.valueOf(rev)).call(),
                        "combo " + combo + ": phase query mismatch for rev " + rev);
            }

            // 2. Retract rev2 to secret -- requires force.
            phaseInSubprocess(hostRepoA, "2", 2, true);
            dockerHgIn(containerName, relB, "phase", "-r", "2", "--secret", "--force");
            assertArrayEquals(Files.readAllBytes(phaseRootsB.toPath()), Files.readAllBytes(phaseRootsA.toPath()),
                    "combo " + combo + ": phaseroots mismatch after retracting rev2 to secret with --force");

            // 3. Advance rev2 back to draft -- the reverse direction, no force needed.
            phaseInSubprocess(hostRepoA, "2", 1, false);
            dockerHgIn(containerName, relB, "phase", "-r", "2", "--draft");
            assertArrayEquals(Files.readAllBytes(phaseRootsB.toPath()), Files.readAllBytes(phaseRootsA.toPath()),
                    "combo " + combo + ": phaseroots mismatch after advancing rev2 back to draft");

            // 4. Retract rev0 back to draft -- requires force again (public -> draft).
            phaseInSubprocess(hostRepoA, "0", 1, true);
            dockerHgIn(containerName, relB, "phase", "-r", "0", "--draft", "--force");
            assertArrayEquals(Files.readAllBytes(phaseRootsB.toPath()), Files.readAllBytes(phaseRootsA.toPath()),
                    "combo " + combo + ": phaseroots mismatch after retracting rev0 back to draft with --force");

            // 5. Blocked without force: both sides must reject and leave phaseroots untouched.
            byte[] beforeA = Files.readAllBytes(phaseRootsA.toPath());
            byte[] beforeB = Files.readAllBytes(phaseRootsB.toPath());

            boolean hg4jRejected = false;
            try {
                phaseInSubprocess(hostRepoA, "0", 2, false);
            } catch (AssertionError expected) {
                hg4jRejected = true;
            }
            assertTrue(hg4jRejected, "combo " + combo + ": hg4j's PhaseCommand must reject moving rev0 to secret without --force");

            boolean nativeRejected = false;
            try {
                dockerHgIn(containerName, relB, "phase", "-r", "0", "--secret");
            } catch (AssertionError expected) {
                nativeRejected = true;
            }
            assertTrue(nativeRejected, "combo " + combo + ": real hg must also reject moving rev0 to secret without --force");

            assertArrayEquals(beforeA, Files.readAllBytes(phaseRootsA.toPath()), "combo " + combo + ": rejected hg4j move must not touch phaseroots");
            assertArrayEquals(beforeB, Files.readAllBytes(phaseRootsB.toPath()), "combo " + combo + ": rejected real-hg move must not touch phaseroots");

            String verifyA = dockerHgIn(containerName, relA, "verify");
            assertFalse(verifyA.toLowerCase().contains("integrity error"), "combo " + combo + ": " + verifyA);
            String verifyB = dockerHgIn(containerName, relB, "verify");
            assertFalse(verifyB.toLowerCase().contains("integrity error"), "combo " + combo + ": " + verifyB);
        });
    }
}
