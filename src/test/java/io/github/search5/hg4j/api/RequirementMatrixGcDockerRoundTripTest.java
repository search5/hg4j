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
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest} for
 * the full 30-combo design this reuses verbatim) applied to {@link GcCommand} -- the Docker-only
 * counterpart of {@link RequirementMatrixGcCoreRoundTripTest}'s native 6-combo scenario (see that
 * class's own javadoc for why this command's round trip is "real hg content survives GC
 * byte-for-byte" rather than a literal command-for-command comparison -- {@code hg} has no
 * built-in {@code gc}/compaction subcommand).
 *
 * <p>Each case gets its own fresh, short-lived container (never a class-shared one), matching
 * {@link RequirementMatrixMergeDockerRoundTripTest}/{@link RequirementMatrixStripDockerRoundTripTest}.
 * hg4j's own {@link GcCommand} write runs in a dedicated {@code java} subprocess ({@link
 * RequirementMatrixGcHelperMain}) rather than inline in this JVM, for the same
 * docker-exec-interleaving corruption reason documented on {@link RequirementMatrixCommitHelperMain}.
 *
 * <p>This is the ONLY one of the three quarters that specifically covers {@code persistent-
 * nodemap}/{@code fileindex-v1}/{@code general-v2} for {@link GcCommand} -- exactly the combos
 * {@link GcCommand}'s own javadoc documents as needing special handling (v2/docket revlogs must
 * never be rewritten; fncache must never be written for a fileindex-v1 repository), so this test
 * additionally asserts on those specifically.
 */
@Tag("interop")
public class RequirementMatrixGcDockerRoundTripTest {

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
        Path workDir = Files.createTempDirectory("hg4j-docker-gc-matrix").toRealPath();
        String containerName = "hg4j-reqmatrix-gc-" + UUID.randomUUID().toString().substring(0, 8);
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

    /** Runs hg4j's {@link GcCommand} in a dedicated subprocess; returns its report line. */
    private static String gcInSubprocess(Path repoDir) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        return runHost(javaBin, "-cp", classpath, RequirementMatrixGcHelperMain.class.getName(),
                repoDir.toString()).trim();
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

    private static String bigContent() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        Random rnd = new Random(42);
        StringBuilder sb = new StringBuilder(220_000);
        for (int i = 0; i < 220_000; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jGcAfterRealHgCommitsAcrossDockerCombo(RequirementCombo combo) throws Exception {
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

            Files.writeString(hostRepoDir.resolve("a.txt"), "root content\n");
            Files.createDirectories(hostRepoDir.resolve("sub"));
            Files.writeString(hostRepoDir.resolve("sub/nested.txt"), "nested content\n");
            String big = bigContent();
            Files.writeString(hostRepoDir.resolve("big.txt"), big);
            dockerHgIn(containerName, repoRelPath, "add");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "c0");

            Files.writeString(hostRepoDir.resolve("a.txt"), "root content v2\n");
            Files.writeString(hostRepoDir.resolve("sub/nested.txt"), "nested content v2\n");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "c1");
            String tipHex = dockerHgIn(containerName, repoRelPath, "log", "-r", "tip", "--template", "{node}");

            String report = gcInSubprocess(hostRepoDir);
            assertTrue(report.contains("GC / Compaction complete"), "combo " + combo + ": " + report);

            String verify = dockerHgIn(containerName, repoRelPath, "verify");
            assertFalse(verify.toLowerCase().contains("integrity error") || verify.toLowerCase().contains("error:"),
                    "real hg verify must find no integrity errors after GC for combo " + combo + ": " + verify);

            assertEquals("root content v2", dockerHgIn(containerName, repoRelPath, "cat", "-r", "tip", "a.txt"), "combo " + combo);
            assertEquals("nested content v2", dockerHgIn(containerName, repoRelPath, "cat", "-r", "tip", "sub/nested.txt"), "combo " + combo);
            assertEquals(big, dockerHgIn(containerName, repoRelPath, "cat", "-r", "tip", "big.txt"), "combo " + combo);
            assertEquals("root content", dockerHgIn(containerName, repoRelPath, "cat", "-r", "0", "a.txt"),
                    "c0's own revision of a.txt must be unaffected by GC for combo " + combo);

            String revs = dockerHgIn(containerName, repoRelPath, "log", "--template", "{rev}\\n");
            assertEquals("1\n0", revs, "GC must not add/remove any revision for combo " + combo);

            String finalTipHex = dockerHgIn(containerName, repoRelPath, "log", "-r", "tip", "--template", "{node}");
            assertEquals(tipHex, finalTipHex, "GC must not change any node hash for combo " + combo);

            // fileindex-v1 (and general-v2, which always implies it) must never get an fncache
            // file written by GC -- real hg drops fncache entirely for that combo (verified live,
            // see GcCommand's own javadoc).
            File fncacheFile = new File(hostRepoDir.toFile(), ".hg/store/fncache");
            if (combo.label().contains("fileindex-v1") || combo.label().contains("general-v2")) {
                assertFalse(fncacheFile.exists(),
                        "fileindex-v1/general-v2 repositories must never have an fncache file for combo " + combo);
            } else {
                assertTrue(fncacheFile.exists(), "combo " + combo);
                List<String> fncacheLines = Files.readAllLines(fncacheFile.toPath());
                assertTrue(fncacheLines.contains("data/a.txt.i"), "combo " + combo + ": " + fncacheLines);
                assertTrue(fncacheLines.contains("data/big.txt.i"), "combo " + combo + ": " + fncacheLines);
                assertTrue(fncacheLines.contains("data/sub/nested.txt.i"), "combo " + combo + ": " + fncacheLines);
                assertFalse(fncacheLines.contains("00changelog.i"), "combo " + combo + ": " + fncacheLines);
                assertFalse(fncacheLines.contains("00manifest.i"), "combo " + combo + ": " + fncacheLines);
            }

            assertEquals("", dockerHgIn(containerName, repoRelPath, "status"), "GC must not touch the working copy for combo " + combo);
        });
    }
}
