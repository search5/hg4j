package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
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
 * for the full 30-combo design this reuses verbatim) applied to {@link TreeMergeCommand} -- the
 * Docker-only counterpart of {@link RequirementMatrixTreeMergeCoreRoundTripTest}'s single scenario
 * (add-by-ours/take-theirs/real-removed/genuine-conflict all at once), re-verified by real hg
 * running inside {@code hg-rust-7.2.4}.
 *
 * <p>Each case gets its own fresh, short-lived container (never a class-shared one), matching
 * {@link RequirementMatrixMergeDockerRoundTripTest}/{@link RequirementMatrixStripDockerRoundTripTest}.
 * {@link TreeMergeCommand} never touches the working directory or dirstate at all (see its class
 * javadoc) -- so unlike most other requirement-matrix Docker suites in this package, hg4j's call
 * runs directly in this JVM against the host-mounted repo path, with no subprocess helper.
 */
@Tag("interop")
public class RequirementMatrixTreeMergeDockerRoundTripTest {

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

    /** Like {@link #runHost} but tolerates ANY exit code -- needed for {@code hg merge} left
     * deliberately unresolved by a genuine conflict. */
    private static String runHostTolerant(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        // Belt-and-suspenders against any interactive prompt hanging forever in a headless test
        // run (the real fix is forcing ui.merge=internal:merge at the call site) -- an immediate
        // EOF makes any prompt fail fast instead of blocking indefinitely on the parent's stdin.
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        Process p = pb.start();
        String out;
        try (InputStream is = p.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        p.waitFor();
        return out;
    }

    @FunctionalInterface
    private interface FreshContainerTest {
        void run(String containerName, Path workDir) throws Exception;
    }

    private static void withFreshContainer(FreshContainerTest test) throws Exception {
        Path workDir = Files.createTempDirectory("hg4j-docker-treemerge-matrix").toRealPath();
        String containerName = "hg4j-reqmatrix-treemerge-" + UUID.randomUUID().toString().substring(0, 8);
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

    private static String dockerHgInTolerant(String container, String repoRelPath, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("docker", "exec", "--user", hostUidGid,
                "-w", "/repo-root/" + repoRelPath, container, "hg"));
        cmd.addAll(Arrays.asList(args));
        return runHostTolerant(cmd.toArray(new String[0])).trim();
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
    public void hg4jTreeMergeMatchesRealHgMergeAcrossDockerCombo(RequirementCombo combo) throws Exception {
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

            Files.writeString(hostRepoDir.resolve("shared.txt"), "shared base\n");
            Files.writeString(hostRepoDir.resolve("removed-by-theirs.txt"), "bye\n");
            Files.writeString(hostRepoDir.resolve("conflict.txt"), "line1\nline2\nline3\n");
            dockerHgIn(containerName, repoRelPath, "add");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "base");
            String baseHex = dockerHgIn(containerName, repoRelPath, "log", "-r", ".", "--template", "{node}");

            Files.writeString(hostRepoDir.resolve("ours-only.txt"), "o\n");
            Files.writeString(hostRepoDir.resolve("conflict.txt"), "line1\nOURS\nline3\n");
            dockerHgIn(containerName, repoRelPath, "add");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "ours");
            String oursHex = dockerHgIn(containerName, repoRelPath, "log", "-r", ".", "--template", "{node}");

            dockerHgIn(containerName, repoRelPath, "update", baseHex);
            Files.writeString(hostRepoDir.resolve("shared.txt"), "shared base\nshared theirs addition\n");
            dockerHgIn(containerName, repoRelPath, "remove", "removed-by-theirs.txt");
            Files.writeString(hostRepoDir.resolve("conflict.txt"), "line1\nTHEIRS\nline3\n");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "theirs");
            String theirsHex = dockerHgIn(containerName, repoRelPath, "log", "-r", ".", "--template", "{node}");

            String oracleRelPath = "oracle";
            Path hostOracleDir = workDir.resolve(oracleRelPath);
            dockerHgIn(containerName, repoRelPath, "clone", "-q", "/repo-root/" + repoRelPath, "/repo-root/" + oracleRelPath);
            dockerHgIn(containerName, oracleRelPath, "update", "-q", oursHex);
            // --config ui.merge=internal:merge: force real hg's own portable, non-interactive
            // textual 3-way merge tool -- see the Core suite's javadoc for why (avoids hanging on
            // an interactive external merge tool in a headless test run).
            dockerHgInTolerant(containerName, oracleRelPath, "--config", "ui.merge=internal:merge", "merge", "-r", theirsHex);

            String oracleShared = Files.readString(hostOracleDir.resolve("shared.txt"));
            assertFalse(Files.exists(hostOracleDir.resolve("removed-by-theirs.txt")), "precondition for combo " + combo);
            assertEquals("U conflict.txt", dockerHgIn(containerName, oracleRelPath, "resolve", "--list"), "precondition for combo " + combo);

            HgRepository repo = new HgRepository(hostRepoDir.toFile());
            TreeMergeCommand.TreeMergeResult result = new TreeMergeCommand(repo)
                    .setOurs(NodeIdUtil.fromHex(oursHex)).setTheirs(NodeIdUtil.fromHex(theirsHex)).call();

            assertTrue(result.isConflicted(), "combo " + combo);
            assertEquals(List.of("conflict.txt"), result.getConflicts(), "combo " + combo);
            assertFalse(result.getChangedFiles().containsKey("ours-only.txt"), "combo " + combo);

            assertTrue(result.getChangedFiles().containsKey("shared.txt"), "combo " + combo);
            assertEquals(oracleShared, new String(result.getChangedFiles().get("shared.txt"), StandardCharsets.UTF_8),
                    "shared.txt's merged content must match real hg's own merge for combo " + combo);

            assertTrue(result.getRemovedFiles().contains("removed-by-theirs.txt"), "combo " + combo);

            String conflictText = new String(result.getChangedFiles().get("conflict.txt"), StandardCharsets.UTF_8);
            assertTrue(conflictText.contains("<<<<<<<") && conflictText.contains("=======") && conflictText.contains(">>>>>>>"),
                    "conflict.txt must carry real conflict marker syntax for combo " + combo + ": " + conflictText);
        });
    }
}
