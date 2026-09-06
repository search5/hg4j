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
 * for the full 30-combo design this reuses verbatim) applied to {@link ForgetCommand} -- the
 * Docker-only counterpart of {@link RequirementMatrixForgetCoreRoundTripTest}'s native 6-combo
 * scenario, including the re-add-after-forget history-continuity check (see that class's javadoc
 * for the {@link AddCommand} "normallookup" gap this exercises). Reaches every dirstate-v2
 * sub-combo, which pure-Python hg on this host cannot create (see {@link
 * RequirementMatrixDockerRoundTripTest}'s class javadoc).
 *
 * <p>Each case gets its own fresh, short-lived container, matching every other write-direction
 * test in this matrix family. hg4j's own {@link ForgetCommand}/{@link AddCommand} calls each run
 * in a dedicated {@code java} subprocess ({@link RequirementMatrixForgetHelperMain} / {@link
 * RequirementMatrixAddHelperMain}) for the same docker-exec-interleaving corruption reason
 * documented on {@link RequirementMatrixCommitHelperMain}.
 */
@Tag("interop")
public class RequirementMatrixForgetDockerRoundTripTest {

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
        Path workDir = Files.createTempDirectory("hg4j-docker-forget-matrix").toRealPath();
        String containerName = "hg4j-reqmatrix-forget-" + UUID.randomUUID().toString().substring(0, 8);
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

    /** Runs hg4j's forget on the given paths in a dedicated subprocess. */
    private static void forgetInSubprocess(Path repoDir, String... files) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        List<String> cmd = new ArrayList<>(List.of(javaBin, "-cp", classpath, RequirementMatrixForgetHelperMain.class.getName(), repoDir.toString()));
        cmd.addAll(Arrays.asList(files));
        runHost(cmd.toArray(new String[0]));
    }

    /** Runs hg4j's add (re-add) on the given paths in a dedicated subprocess. */
    private static void addInSubprocess(Path repoDir, String... files) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        List<String> cmd = new ArrayList<>(List.of(javaBin, "-cp", classpath, RequirementMatrixAddHelperMain.class.getName(), repoDir.toString()));
        cmd.addAll(Arrays.asList(files));
        runHost(cmd.toArray(new String[0]));
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
    public void hg4jForgetAcrossDockerCombo(RequirementCombo combo) throws Exception {
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

            Files.writeString(hostRepoDir.resolve("top.txt"), "top v1\n");
            Files.createDirectories(hostRepoDir.resolve("adir"));
            Files.writeString(hostRepoDir.resolve("adir/nested.txt"), "nested v1\n");
            Files.writeString(hostRepoDir.resolve("base.txt"), "base v1\n");
            dockerHgIn(containerName, repoRelPath, "add");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "c0 base");

            forgetInSubprocess(hostRepoDir, "top.txt", "adir/nested.txt");

            String status = dockerHgIn(containerName, repoRelPath, "status");
            assertTrue(status.lines().anyMatch(l -> l.equals("R top.txt")),
                    "real hg status must see top.txt as removed (forgotten) for combo " + combo + ": " + status);
            assertTrue(status.lines().anyMatch(l -> l.equals("R adir/nested.txt")),
                    "real hg status must see adir/nested.txt as removed (forgotten) for combo " + combo + ": " + status);
            assertTrue(Files.exists(hostRepoDir.resolve("top.txt")), "forget must leave top.txt on disk");
            assertTrue(Files.exists(hostRepoDir.resolve("adir/nested.txt")), "forget must leave adir/nested.txt on disk");

            Files.writeString(hostRepoDir.resolve("top.txt"), "top v2 after forget\n");
            Files.writeString(hostRepoDir.resolve("adir/nested.txt"), "nested v2 after forget\n");
            addInSubprocess(hostRepoDir, "top.txt", "adir/nested.txt");

            String statusAfterReadd = dockerHgIn(containerName, repoRelPath, "status");
            assertTrue(statusAfterReadd.lines().anyMatch(l -> l.equals("M top.txt")),
                    "real hg status must see top.txt as modified after forget+re-add for combo " + combo + ": " + statusAfterReadd);
            assertTrue(statusAfterReadd.lines().anyMatch(l -> l.equals("M adir/nested.txt")),
                    "real hg status must see adir/nested.txt as modified after forget+re-add for combo " + combo + ": " + statusAfterReadd);

            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "c1 forget+re-add");

            String verify = dockerHgIn(containerName, repoRelPath, "verify");
            assertFalse(verify.toLowerCase().contains("integrity error"),
                    "real hg verify must find no integrity errors after forget+re-add+commit for combo " + combo + ": " + verify);

            String manifest = dockerHgIn(containerName, repoRelPath, "manifest", "-r", "tip");
            assertTrue(manifest.lines().anyMatch(l -> l.equals("top.txt")), "manifest: " + manifest);
            assertTrue(manifest.lines().anyMatch(l -> l.equals("adir/nested.txt")), "manifest: " + manifest);
            assertTrue(manifest.lines().anyMatch(l -> l.equals("base.txt")), "manifest: " + manifest);

            assertEquals("top v2 after forget", dockerHgIn(containerName, repoRelPath, "cat", "-r", "tip", "top.txt"));
            assertEquals("nested v2 after forget", dockerHgIn(containerName, repoRelPath, "cat", "-r", "tip", "adir/nested.txt"));

            String followTop = dockerHgIn(containerName, repoRelPath, "log", "--follow", "-r", "tip", "top.txt", "--template", "{rev} ");
            assertTrue(followTop.contains("0"),
                    "log --follow must reach c0 through top.txt across the forget+re-add boundary for combo " + combo + ": " + followTop);
            String followNested = dockerHgIn(containerName, repoRelPath, "log", "--follow", "-r", "tip", "adir/nested.txt", "--template", "{rev} ");
            assertTrue(followNested.contains("0"),
                    "log --follow must reach c0 through adir/nested.txt across the forget+re-add boundary for combo " + combo + ": " + followNested);

            String debugIndexTop = dockerHgIn(containerName, repoRelPath, "debugindex", "top.txt");
            String[] topLines = debugIndexTop.lines().skip(1).toArray(String[]::new);
            assertTrue(topLines.length >= 2, "top.txt filelog must have 2 revisions across forget+re-add: " + debugIndexTop);
            assertFalse(topLines[1].trim().split("\\s+")[3].equals("000000000000"),
                    "top.txt's post-re-add filelog revision must chain to the pre-forget revision (non-null p1): " + debugIndexTop);
        });
    }
}
