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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest}
 * for the full 30-combo design this reuses verbatim) applied to {@link CatCommand}, {@link
 * FilesCommand}, {@link LocateCommand} and {@link ManifestCommand} together -- the Docker-only
 * counterpart of {@link RequirementMatrixCatFilesLocateManifestCoreRoundTripTest}'s native
 * 6-combo scenario (see that class's javadoc for why these four are grouped and why no {@code
 * HelperMain} subprocess is needed here: none of the four commands under test ever write to a
 * repository, so there is no analog of the docker-exec-interleaved hg4j-write corruption
 * documented on {@link RequirementMatrixCommitHelperMain}).
 *
 * <p>Each combo gets its own fresh, short-lived container (matching {@link
 * RequirementMatrixMergeDockerRoundTripTest}/{@link RequirementMatrixStripDockerRoundTripTest});
 * real hg (inside the container, the only place formats like dirstate-v2/persistent-nodemap/
 * fileindex-v1/general-v2 can even be created) builds the repository on a bind-mounted host
 * directory, and hg4j then opens that same directory directly from the host JVM to run the four
 * commands under test -- no hg4j process ever runs inside the container.
 */
@Tag("interop")
public class RequirementMatrixCatFilesLocateManifestDockerRoundTripTest {

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
        Path workDir = Files.createTempDirectory("hg4j-docker-catfileslocatemanifest-matrix").toRealPath();
        String containerName = "hg4j-reqmatrix-cflm-" + UUID.randomUUID().toString().substring(0, 8);
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

    /** Same {@code hg manifest --debug} line format as {@link
     * RequirementMatrixCatFilesLocateManifestCoreRoundTripTest.RealManifestLine}. */
    record RealManifestLine(String nodeHex, boolean executable, boolean symlink, String path) {
        static RealManifestLine parse(String line) {
            String nodeHex = line.substring(0, 40);
            String rest = line.substring(41);
            char flag = rest.charAt(4);
            String path = rest.substring(6);
            return new RealManifestLine(nodeHex, flag == '*', flag == '@', path);
        }
    }

    private static List<RealManifestLine> realManifest(String container, String repoRelPath, String rev) throws Exception {
        String out = dockerHgIn(container, repoRelPath, "manifest", "--debug", "-r", rev);
        if (out.isEmpty()) {
            return List.of();
        }
        List<RealManifestLine> lines = new ArrayList<>();
        for (String l : out.split("\n")) {
            lines.add(RealManifestLine.parse(l));
        }
        lines.sort(Comparator.comparing(RealManifestLine::path));
        return lines;
    }

    private static List<String> splitLines(String out) {
        return out.isEmpty() ? List.of() : List.of(out.split("\n"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void catFilesLocateManifestAcrossDockerCombo(RequirementCombo combo) throws Exception {
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

            Files.createDirectories(hostRepoDir.resolve("dir/sub"));
            Files.writeString(hostRepoDir.resolve("a.txt"), "hello\n");
            Files.writeString(hostRepoDir.resolve("dir/b.txt"), "world\n");
            Files.writeString(hostRepoDir.resolve("dir/sub/c.txt"), "deep\n");
            dockerHgIn(containerName, repoRelPath, "add");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "c0");
            String c0Hex = dockerHgIn(containerName, repoRelPath, "log", "-r", ".", "--template", "{node}");

            dockerHgIn(containerName, repoRelPath, "mv", "a.txt", "renamed.txt");
            Files.writeString(hostRepoDir.resolve("dir/d.txt"), "new\n");
            dockerHgIn(containerName, repoRelPath, "add", "dir/d.txt");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "c1");
            String c1Hex = dockerHgIn(containerName, repoRelPath, "log", "-r", ".", "--template", "{node}");

            hostRepoDir.resolve("dir/d.txt").toFile().setExecutable(true);
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "c2");
            String c2Hex = dockerHgIn(containerName, repoRelPath, "log", "-r", ".", "--template", "{node}");

            dockerHgIn(containerName, repoRelPath, "rm", "dir/b.txt");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "c3");
            String c3Hex = dockerHgIn(containerName, repoRelPath, "log", "-r", ".", "--template", "{node}");

            HgRepository repo = new HgRepository(hostRepoDir.toFile());

            // --- CatCommand ---
            assertEquals("hello", new String(new CatCommand(repo).setFile("a.txt").setRevision(c0Hex).call(), StandardCharsets.UTF_8).trim(),
                    "combo " + combo);
            assertEquals("deep", new String(new CatCommand(repo).setFile("dir/sub/c.txt").setRevision(c0Hex).call(), StandardCharsets.UTF_8).trim(),
                    "combo " + combo + " (nested treemanifest path)");
            assertEquals(dockerHgIn(containerName, repoRelPath, "cat", "-r", c1Hex, "renamed.txt"),
                    new String(new CatCommand(repo).setFile("renamed.txt").setRevision(c1Hex).call(), StandardCharsets.UTF_8).trim(),
                    "combo " + combo + " (content survives rename)");

            // --- FilesCommand ---
            assertEquals(splitLines(dockerHgIn(containerName, repoRelPath, "files", "-r", c1Hex)),
                    new FilesCommand(repo).setRevision(c1Hex).call(), "combo " + combo + " files -r c1");

            assertEquals(splitLines(dockerHgIn(containerName, repoRelPath, "files", "-r", c1Hex, "dir")),
                    new FilesCommand(repo).setRevision(c1Hex).setPattern("dir").call(),
                    "combo " + combo + " files -r c1 dir (pattern filter)");

            assertEquals(splitLines(dockerHgIn(containerName, repoRelPath, "files", "-r", c3Hex)),
                    new FilesCommand(repo).setRevision(c3Hex).call(),
                    "combo " + combo + " files -r c3 (after remove)");

            // --- LocateCommand ---
            assertEquals(splitLines(dockerHgIn(containerName, repoRelPath, "locate", "-r", c1Hex, "*.txt")),
                    new LocateCommand(repo).setRevision(c1Hex).setPattern("*.txt").call(),
                    "combo " + combo + " locate -r c1 *.txt");

            assertEquals(splitLines(dockerHgIn(containerName, repoRelPath, "locate", "-r", c1Hex, "dir/*.txt")),
                    new LocateCommand(repo).setRevision(c1Hex).setPattern("dir/*.txt").call(),
                    "combo " + combo + " locate -r c1 dir/*.txt");

            assertEquals(splitLines(dockerHgIn(containerName, repoRelPath, "locate", "*.txt")),
                    new LocateCommand(repo).setPattern("*.txt").call(),
                    "combo " + combo + " locate *.txt (working copy, no -r)");

            // --- ManifestCommand ---
            assertManifestMatches(repo, containerName, repoRelPath, c0Hex, combo);
            assertManifestMatches(repo, containerName, repoRelPath, c2Hex, combo);
            assertManifestMatches(repo, containerName, repoRelPath, c3Hex, combo);
        });
    }

    private static void assertManifestMatches(HgRepository repo, String container, String repoRelPath, String rev, RequirementCombo combo) throws Exception {
        List<RealManifestLine> expected = realManifest(container, repoRelPath, rev);
        List<ManifestCommand.ManifestEntry> actual = new ManifestCommand(repo).setRevision(rev).call();
        actual.sort(Comparator.comparing(ManifestCommand.ManifestEntry::getPath));

        List<String> expectedPaths = expected.stream().map(RealManifestLine::path).collect(Collectors.toList());
        List<String> actualPaths = actual.stream().map(ManifestCommand.ManifestEntry::getPath).collect(Collectors.toList());
        assertEquals(expectedPaths, actualPaths, "combo " + combo + " manifest paths -r " + rev);

        for (int i = 0; i < expected.size(); i++) {
            RealManifestLine e = expected.get(i);
            ManifestCommand.ManifestEntry a = actual.get(i);
            assertEquals(e.nodeHex(), a.getNodeHex(), "combo " + combo + " manifest nodeHex mismatch for " + e.path() + " -r " + rev);
            assertEquals(e.executable(), a.isExecutable(), "combo " + combo + " manifest executable flag mismatch for " + e.path() + " -r " + rev);
            assertEquals(e.symlink(), a.isSymlink(), "combo " + combo + " manifest symlink flag mismatch for " + e.path() + " -r " + rev);
        }
    }
}
