package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.phase.PhaseRoots;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest}
 * for the full 30-combo design this reuses verbatim) applied to {@link IdentifyCommand}/
 * {@link SummaryCommand} -- the Docker-only counterpart of
 * {@link RequirementMatrixIdentifyCoreRoundTripTest}'s native 6-combo scenario (see that class's
 * javadoc for the full scenario writeup and the real hg4j bugs found while designing it).
 *
 * <p>No {@code HelperMain} subprocess is used, for the same reason as
 * {@link RequirementMatrixHeadsDockerRoundTripTest}: both commands are pure readers. Each case
 * gets its own fresh, short-lived container, matching that class and
 * {@link RequirementMatrixBackoutDockerRoundTripTest}.
 */
@Tag("interop")
public class RequirementMatrixIdentifyDockerRoundTripTest {

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
        Path workDir = Files.createTempDirectory("hg4j-docker-identify-matrix").toRealPath();
        String containerName = "hg4j-reqmatrix-identify-" + UUID.randomUUID().toString().substring(0, 8);
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
    public void identifyAndSummaryMatchRealHgAcrossDockerCombo(RequirementCombo combo) throws Exception {
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

            Files.writeString(hostRepoDir.resolve("a.txt"), "a0\n");
            dockerHgIn(containerName, repoRelPath, "add");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "c0");
            String c0Hex = dockerHgIn(containerName, repoRelPath, "log", "-r", ".", "--template", "{node}");

            Files.writeString(hostRepoDir.resolve("a.txt"), "a1\n");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "c1");
            dockerHgIn(containerName, repoRelPath, "tag", "-u", "dev", "v1");
            String c1TagHex = dockerHgIn(containerName, repoRelPath, "log", "-r", ".", "--template", "{node}");

            dockerHgIn(containerName, repoRelPath, "branch", "feature");
            Files.writeString(hostRepoDir.resolve("b.txt"), "b0\n");
            dockerHgIn(containerName, repoRelPath, "add");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "c2");
            String c2Hex = dockerHgIn(containerName, repoRelPath, "log", "-r", ".", "--template", "{node}");
            dockerHgIn(containerName, repoRelPath, "bookmark", "mark1");

            HgRepository repo = new HgRepository(hostRepoDir.toFile());

            String realId1 = dockerHgIn(containerName, repoRelPath, "identify");
            String hg4jId1 = new IdentifyCommand(repo).call();
            assertEquals(realId1, hg4jId1, "combo " + combo);
            assertEquals(c2Hex.substring(0, 12) + " (feature) tip mark1", hg4jId1, "combo " + combo);

            SummaryCommand.SummaryInfo summary1 = new SummaryCommand(repo).call();
            assertEquals(1, summary1.parents().size(), "combo " + combo);
            assertEquals(c2Hex, summary1.parents().get(0).node(), "combo " + combo);
            assertEquals("c2", summary1.parents().get(0).description(), "combo " + combo);
            assertEquals("feature", summary1.branch(), "combo " + combo);
            assertEquals("mark1", summary1.activeBookmark(), "combo " + combo);
            assertEquals(0, summary1.modified(), "combo " + combo);
            assertEquals(0, summary1.added(), "combo " + combo);
            assertEquals(0, summary1.removed(), "combo " + combo);
            assertFalse(summary1.mergeInProgress(), "combo " + combo);
            int realPhase1 = new PhaseCommand(repo).setRevision(c2Hex).call();
            assertEquals(PhaseRoots.Phase.fromValue(realPhase1), summary1.currentPhase(), "combo " + combo);
            assertEquals(PhaseRoots.Phase.DRAFT, summary1.currentPhase(), "combo " + combo);

            Files.writeString(hostRepoDir.resolve("b.txt"), "b0-modified\n");
            String realIdDirty = dockerHgIn(containerName, repoRelPath, "identify");
            String hg4jIdDirty = new IdentifyCommand(repo).call();
            assertEquals(realIdDirty, hg4jIdDirty, "combo " + combo);
            assertEquals(c2Hex.substring(0, 12) + "+ (feature) tip mark1", hg4jIdDirty, "combo " + combo);
            assertEquals(1, new SummaryCommand(repo).call().modified(), "combo " + combo);
            dockerHgIn(containerName, repoRelPath, "revert", "--no-backup", "-a");

            Files.writeString(hostRepoDir.resolve("n.txt"), "new\n");
            dockerHgIn(containerName, repoRelPath, "add", "n.txt");
            String realIdAdded = dockerHgIn(containerName, repoRelPath, "identify");
            String hg4jIdAdded = new IdentifyCommand(repo).call();
            assertEquals(realIdAdded, hg4jIdAdded, "combo " + combo);
            assertEquals(1, new SummaryCommand(repo).call().added(), "combo " + combo);
            dockerHgIn(containerName, repoRelPath, "forget", "n.txt");
            Files.delete(hostRepoDir.resolve("n.txt"));

            Files.writeString(hostRepoDir.resolve("untracked.txt"), "untracked\n");
            String realIdUntracked = dockerHgIn(containerName, repoRelPath, "identify");
            String hg4jIdUntracked = new IdentifyCommand(repo).call();
            assertEquals(realIdUntracked, hg4jIdUntracked, "combo " + combo);
            assertEquals(c2Hex.substring(0, 12) + " (feature) tip mark1", hg4jIdUntracked, "combo " + combo);
            Files.delete(hostRepoDir.resolve("untracked.txt"));

            String realIdC0 = dockerHgIn(containerName, repoRelPath, "identify", "-r", c0Hex);
            String hg4jIdC0 = new IdentifyCommand(repo).setRevision(c0Hex).call();
            assertEquals(realIdC0, hg4jIdC0, "combo " + combo);
            assertEquals(c0Hex.substring(0, 12), hg4jIdC0, "combo " + combo);

            dockerHgIn(containerName, repoRelPath, "update", c1TagHex);
            dockerHgIn(containerName, repoRelPath, "branch", "other");
            Files.writeString(hostRepoDir.resolve("o.txt"), "o0\n");
            dockerHgIn(containerName, repoRelPath, "add");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "c3");
            String c3Hex = dockerHgIn(containerName, repoRelPath, "log", "-r", ".", "--template", "{node}");
            dockerHgIn(containerName, repoRelPath, "merge", c2Hex);

            // See RequirementMatrixIdentifyCoreRoundTripTest for why this is needed: this
            // long-lived HgRepository handle was constructed before c3 was committed externally,
            // and a changelog-v2 docket's own file size does not grow on append.
            repo.refreshIfChangedOnDisk();

            String realIdMerge = dockerHgIn(containerName, repoRelPath, "identify");
            String hg4jIdMerge = new IdentifyCommand(repo).call();
            assertEquals(realIdMerge, hg4jIdMerge, "combo " + combo);
            assertEquals(c3Hex.substring(0, 12) + "+" + c2Hex.substring(0, 12) + "+ (other) tip mark1",
                    hg4jIdMerge, "combo " + combo);

            SummaryCommand.SummaryInfo summaryMerge = new SummaryCommand(repo).call();
            assertEquals(2, summaryMerge.parents().size(), "combo " + combo);
            assertEquals(c3Hex, summaryMerge.parents().get(0).node(), "combo " + combo);
            assertEquals("c3", summaryMerge.parents().get(0).description(), "combo " + combo);
            assertEquals(c2Hex, summaryMerge.parents().get(1).node(), "combo " + combo);
            assertEquals("c2", summaryMerge.parents().get(1).description(), "combo " + combo);
            assertEquals("other", summaryMerge.branch(), "combo " + combo);
            assertNull(summaryMerge.activeBookmark(), "combo " + combo);
            assertTrue(summaryMerge.mergeInProgress(), "combo " + combo);
        });
    }
}
