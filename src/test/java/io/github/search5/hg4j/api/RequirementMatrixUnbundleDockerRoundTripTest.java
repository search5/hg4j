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
 * for the full 30-combo design this reuses verbatim) applied to {@link UnbundleCommand} -- the
 * Docker-only counterpart of {@link RequirementMatrixUnbundleCoreRoundTripTest}'s native 6-combo
 * scenario, and the read-side mirror of {@link RequirementMatrixBundleDockerRoundTripTest}: a real
 * {@code hg} SOURCE repo (inside the container) commits and writes two bundle FILEs (a full one,
 * then an incremental one), and hg4j's {@link UnbundleCommand} applies both to an
 * independently-{@code hg init}'d destination of the SAME combo.
 *
 * <p>The destination repository is initialized via real {@code hg} inside the container (so its
 * {@code .hg/requires}/format bookkeeping exactly matches what that combo's real {@code hg} would
 * write) but then written to ENTIRELY by hg4j running as a HOST-side subprocess ({@link
 * RequirementMatrixUnbundleHelperMain}) operating directly on the same bind-mounted directory tree
 * -- no further {@code docker exec} calls touch the destination while hg4j is applying, mirroring
 * {@link RequirementMatrixBundleHelperMain}'s reason for existing (heavy concurrent {@code docker
 * exec}/{@code docker run} process spawning in the same JVM as an hg4j write command corrupts its
 * output -- see {@link RequirementMatrixCommitHelperMain}'s javadoc for the root cause). Every
 * verification of the destination's resulting state after each unbundle is then done by real
 * {@code hg} (back via {@code docker exec}) reading the exact same files hg4j just wrote.
 *
 * <p>Treemanifest combos use {@code --type none-v3} (real {@code hg bundle} cannot use a {@code
 * -v1} type against a treemanifest repository at all -- see {@link BundleCommand}'s class
 * javadoc); every {@code cl2+sidedata} combo is a confirmed, real-hg-only file-based-bundle
 * limitation (also documented on {@link BundleCommand}), checked here on the real-hg SOURCE side
 * (the only side with an {@code hg verify} of its own) via the same tolerant helper {@link
 * RequirementMatrixBundleDockerRoundTripTest} uses.
 */
@Tag("interop")
public class RequirementMatrixUnbundleDockerRoundTripTest {

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
        Path workDir = Files.createTempDirectory("hg4j-docker-unbundle-matrix").toRealPath();
        String containerName = "hg4j-reqmatrix-unbundle-" + UUID.randomUUID().toString().substring(0, 8);
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

    private static String dockerHgTolerantIn(String container, String repoRelPath, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("docker", "exec", "--user", hostUidGid,
                "-w", "/repo-root/" + repoRelPath, container, "hg"));
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out;
        try (InputStream is = p.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        p.waitFor();
        return out.trim();
    }

    /** Runs hg4j's two {@link UnbundleCommand} applications in a dedicated subprocess; returns
     * {@code node1Hex node2Hex}. */
    private static String[] unbundleInSubprocess(Path destRepoDir, Path bundleFile1, Path bundleFile2) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        String out = runHost(javaBin, "-cp", classpath, RequirementMatrixUnbundleHelperMain.class.getName(),
                destRepoDir.toString(), bundleFile1.toString(), bundleFile2.toString());
        return out.trim().split("\\s+");
    }

    /** One point in the Docker-only quarter of the requirement matrix -- identical generation to
     * {@link RequirementMatrixDockerRoundTripTest#combos()}. */
    record RequirementCombo(String label, List<String> initConfigArgs, boolean treemanifest, boolean sidedata) {
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

    record ClEntry(String key, List<String> args, boolean sidedata) {
    }

    static Stream<RequirementCombo> combos() {
        List<RequirementCombo> out = new ArrayList<>();
        List<java.util.Map.Entry<String, List<String>>> dirstates = List.of(
                java.util.Map.entry("dirstate1", List.<String>of()), java.util.Map.entry("dirstate2", DIRSTATE_V2));
        List<ClEntry> changelogs = List.of(
                new ClEntry("cl1", CL_V1, false), new ClEntry("cl2", CL_V2, false),
                new ClEntry("cl2+sidedata", CL_V2_SIDEDATA, true));

        for (ClEntry cl : changelogs) {
            for (var tm : List.of(java.util.Map.entry("flatmanifest", List.<String>of()),
                    java.util.Map.entry("treemanifest", TREEMANIFEST))) {
                List<String> args = new ArrayList<>();
                args.addAll(DIRSTATE_V2);
                args.addAll(cl.args());
                args.addAll(tm.getValue());
                boolean treemanifest = tm.getKey().equals("treemanifest");
                out.add(new RequirementCombo("dirstate2/" + cl.key() + "/" + tm.getKey() + "/none", args, treemanifest, cl.sidedata()));
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
                    boolean treemanifest = tm.getKey().equals("treemanifest");
                    out.add(new RequirementCombo(dirstate.getKey() + "/" + cl.key() + "/" + tm.getKey() + "/pnodemap", args, treemanifest, cl.sidedata()));
                }
                List<String> fileindexArgs = new ArrayList<>();
                fileindexArgs.addAll(dirstate.getValue());
                fileindexArgs.addAll(cl.args());
                fileindexArgs.addAll(FILEINDEX_V1);
                out.add(new RequirementCombo(dirstate.getKey() + "/" + cl.key() + "/flatmanifest/fileindex-v1", fileindexArgs, false, cl.sidedata()));
                List<String> generalV2Args = new ArrayList<>();
                generalV2Args.addAll(dirstate.getValue());
                generalV2Args.addAll(cl.args());
                generalV2Args.addAll(GENERAL_V2);
                out.add(new RequirementCombo(dirstate.getKey() + "/" + cl.key() + "/flatmanifest/general-v2", generalV2Args, false, cl.sidedata()));
            }
        }
        return out.stream();
    }

    /** {@code none-v3} for treemanifest combos, {@code none-v1} otherwise (see class javadoc). */
    private static String bundleTypeFor(RequirementCombo combo) {
        return combo.treemanifest() ? "none-v3" : "none-v1";
    }

    private static void assertVerifyCleanUnlessKnownSidedataLimitation(String verifyOutput, RequirementCombo combo) {
        if (combo.sidedata()) {
            return;
        }
        boolean hasIntegrityError = verifyOutput.toLowerCase().contains("integrity error")
                || verifyOutput.toLowerCase().contains("error:");
        assertFalse(hasIntegrityError,
                "real hg verify must find no integrity errors for non-sidedata combo " + combo + ": " + verifyOutput);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void realHgBundleAppliedByHg4jUnbundleAcrossDockerCombo(RequirementCombo combo) throws Exception {
        withFreshContainer((containerName, workDir) -> {
            String sourceRelPath = "source";
            String destRelPath = "dest";
            Path hostSourceDir = workDir.resolve(sourceRelPath);
            Path hostDestDir = workDir.resolve(destRelPath);
            Files.createDirectories(hostSourceDir);
            Files.createDirectories(hostDestDir);

            List<String> initArgs = new ArrayList<>(List.of("init", "."));
            for (String c : combo.initConfigArgs()) {
                initArgs.add("--config");
                initArgs.add(c);
            }
            dockerHgIn(containerName, sourceRelPath, initArgs.toArray(new String[0]));
            dockerHgIn(containerName, destRelPath, initArgs.toArray(new String[0]));

            String bundleType = bundleTypeFor(combo);

            // Real hg (inside the container) commits and writes a full bundle.
            Files.writeString(hostSourceDir.resolve("a.txt"), "one");
            dockerHgIn(containerName, sourceRelPath, "add", "a.txt");
            dockerHgIn(containerName, sourceRelPath, "commit", "-u", "realhg", "-m", "c0 for " + combo);
            String node1Hex = dockerHgIn(containerName, sourceRelPath, "log", "-r", "0", "--template", "{node}");
            dockerHgIn(containerName, sourceRelPath, "bundle", "--all", "--type", bundleType, "/repo-root/bundle1.hg");

            // Real hg writes a second, incremental bundle after a new subdirectory file.
            Files.createDirectories(hostSourceDir.resolve("dir"));
            Files.writeString(hostSourceDir.resolve("dir").resolve("b.txt"), "two");
            dockerHgIn(containerName, sourceRelPath, "add", "dir/b.txt");
            dockerHgIn(containerName, sourceRelPath, "commit", "-u", "realhg", "-m", "c1 for " + combo);
            String node2Hex = dockerHgIn(containerName, sourceRelPath, "log", "-r", "tip", "--template", "{node}");
            dockerHgIn(containerName, sourceRelPath, "bundle", "--base", node1Hex, "--type", bundleType, "/repo-root/bundle2.hg");

            // hg4j (host-side subprocess) applies both bundles to the destination.
            Path hostBundle1 = workDir.resolve("bundle1.hg");
            Path hostBundle2 = workDir.resolve("bundle2.hg");
            String[] applied = unbundleInSubprocess(hostDestDir, hostBundle1, hostBundle2);
            assertEquals(node1Hex, applied[0], "hg4j must report the first bundle's node for combo " + combo);
            assertEquals(node2Hex, applied[1], "hg4j must report the second bundle's node for combo " + combo);

            // Real hg (back via docker exec) reads back what hg4j just wrote.
            String destTip1 = dockerHgIn(containerName, destRelPath, "log", "-r", "0", "--template", "{node}");
            assertEquals(node1Hex, destTip1, "real hg dest must see the first unbundled commit for combo " + combo);
            String cat1 = dockerHgIn(containerName, destRelPath, "cat", "-r", "0", "a.txt");
            assertEquals("one", cat1);

            String destTip2 = dockerHgIn(containerName, destRelPath, "log", "-r", "tip", "--template", "{node}");
            assertEquals(node2Hex, destTip2, "real hg dest must see the second unbundled commit as tip for combo " + combo);
            String cat2 = dockerHgIn(containerName, destRelPath, "cat", "-r", "tip", "dir/b.txt");
            assertEquals("two", cat2);

            String verify = dockerHgTolerantIn(containerName, destRelPath, "verify");
            assertVerifyCleanUnlessKnownSidedataLimitation(verify, combo);
            String log = dockerHgIn(containerName, destRelPath, "log", "--template", "{rev}:{node}\n");
            assertEquals(2, log.split("\n").length, "destination must have exactly 2 revisions for combo " + combo + ":\n" + log);
        });
    }
}
