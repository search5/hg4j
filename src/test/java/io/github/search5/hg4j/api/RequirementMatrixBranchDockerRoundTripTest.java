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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest}
 * for the full 30-combo design this reuses verbatim) applied to {@link BranchCommand}/{@link
 * BranchesCommand} -- the Docker-only counterpart of {@link RequirementMatrixBranchCoreRoundTripTest}'s
 * native 6-combo scenario (create a named branch, commit on it, close it, list via {@link
 * BranchesCommand}) across every one of the 30 combos, including dirstate v2.
 *
 * <p>Each case gets its own fresh, short-lived container, matching every other write-direction
 * test in this matrix family. hg4j's own {@link BranchCommand}/{@link CommitCommand} calls run in
 * a dedicated {@code java} subprocess ({@link RequirementMatrixBranchHelperMain}) for the same
 * docker-exec-interleaving corruption reason documented on {@link RequirementMatrixCommitHelperMain}.
 */
@Tag("interop")
public class RequirementMatrixBranchDockerRoundTripTest {

    private static final String IMAGE = "localhost/hg-rust-7.2.4";
    private static String hostUidGid;
    private static boolean dockerReady = false;

    /** Parses a real {@code hg branches} line, e.g. "feature   3:abcdef012345 (inactive)". */
    private static final Pattern BRANCHES_LINE =
            Pattern.compile("^(\\S+)\\s+(\\d+):([0-9a-f]+)(?:\\s+\\((\\S+)\\))?$");

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
        Path workDir = Files.createTempDirectory("hg4j-docker-branch-matrix").toRealPath();
        String containerName = "hg4j-reqmatrix-branch-" + UUID.randomUUID().toString().substring(0, 8);
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

    private static void branchCommitInSubprocess(Path repoDir, String branchName, String fileName, String fileContent,
                                                  String author, String message, boolean closeBranch) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        runHost(javaBin, "-cp", classpath, RequirementMatrixBranchHelperMain.class.getName(),
                repoDir.toString(), branchName == null ? "" : branchName, fileName, fileContent, author, message,
                Boolean.toString(closeBranch));
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
    public void hg4jBranchAcrossDockerCombo(RequirementCombo combo) throws Exception {
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

            dockerHgIn(containerName, repoRelPath, "id"); // sanity: repo usable before hg4j touches it

            // 1. hg4j creates the "default" branch's first commit, then a named branch + commit.
            branchCommitInSubprocess(hostRepoDir, null, "base.txt", "base\n", "dev", "c0 base", false);
            branchCommitInSubprocess(hostRepoDir, "feature", "f1.txt", "feature work\n", "dev", "c1-feature", false);

            String nativeBranchOfTip = dockerHgIn(containerName, repoRelPath, "log", "-r", "tip", "--template", "{branch}");
            assertEquals("feature", nativeBranchOfTip, "real hg must see the hg4j-committed branch name for combo " + combo);

            String verify1 = dockerHgIn(containerName, repoRelPath, "verify");
            assertFalse(verify1.toLowerCase().contains("integrity error"),
                    "real hg verify after branch commit must find no integrity errors for combo " + combo + ": " + verify1);

            String nativeBranchesOpen = dockerHgIn(containerName, repoRelPath, "branches");
            assertBranchesMatch(nativeBranchesOpen, hostRepoDir, combo);

            // 2. Close the feature branch via hg4j's CommitCommand.setCloseBranch(true).
            branchCommitInSubprocess(hostRepoDir, null, "f1.txt", "feature work, closing\n", "dev", "close-feature", true);

            String verify2 = dockerHgIn(containerName, repoRelPath, "verify");
            assertFalse(verify2.toLowerCase().contains("integrity error"),
                    "real hg verify after closing the branch must find no integrity errors for combo " + combo + ": " + verify2);

            String nativeDefaultAfterClose = dockerHgIn(containerName, repoRelPath, "branches");
            assertFalse(nativeDefaultAfterClose.contains("feature"),
                    "real hg's default 'hg branches' must hide the fully-closed branch for combo " + combo + ": " + nativeDefaultAfterClose);

            String nativeClosed = dockerHgIn(containerName, repoRelPath, "branches", "--closed");
            assertTrue(nativeClosed.contains("(closed)"), "real hg 'hg branches --closed' must mark it closed for combo " + combo + ": " + nativeClosed);
            assertBranchesMatch(nativeClosed, hostRepoDir, combo);
        });
    }

    /** Reads {@link BranchesCommand} in-process against the host-mounted repo directory and
     * compares its listing (order + closed flag) against an already-fetched real-hg text listing.
     * BranchesCommand is read-only, so -- unlike the hg4j write-side helper subprocess -- it is
     * safe to invoke directly from this JVM without risking the docker-exec-interleaving
     * corruption {@link RequirementMatrixBranchHelperMain} exists to avoid. */
    private static void assertBranchesMatch(String nativeOut, Path repoDir, RequirementCombo combo) throws Exception {
        List<BranchesCommand.BranchHead> hg4jBranches =
                new BranchesCommand(new io.github.search5.hg4j.lib.HgRepository(repoDir.toFile()))
                        .setIncludeClosed(true).call();
        // Filter to whichever set real hg's own listing actually shows (default listing hides
        // fully-closed branches; --closed shows everything) so both sides describe the same set.
        java.util.Set<String> nativeNames = new java.util.HashSet<>();
        for (String line : nativeOut.split("\n")) {
            if (line.isBlank()) continue;
            Matcher m = BRANCHES_LINE.matcher(line.trim());
            assertTrue(m.matches(), "unparsable hg branches line for combo " + combo + ": [" + line + "]");
            nativeNames.add(m.group(1));
        }
        List<BranchesCommand.BranchHead> filtered = hg4jBranches.stream()
                .filter(h -> nativeNames.contains(h.getBranch())).toList();

        List<String> hg4jOrder = filtered.stream().map(BranchesCommand.BranchHead::getBranch).toList();
        List<String> nativeOrder = new ArrayList<>();
        java.util.Map<String, Boolean> nativeClosed = new java.util.HashMap<>();
        for (String line : nativeOut.split("\n")) {
            if (line.isBlank()) continue;
            Matcher m = BRANCHES_LINE.matcher(line.trim());
            assertTrue(m.matches(), "unparsable hg branches line for combo " + combo + ": [" + line + "]");
            nativeOrder.add(m.group(1));
            nativeClosed.put(m.group(1), "closed".equals(m.group(4)));
        }
        assertEquals(nativeOrder, hg4jOrder, "hg4j branch listing order must match real hg's for combo " + combo);
        for (BranchesCommand.BranchHead h : filtered) {
            assertEquals(nativeClosed.get(h.getBranch()), h.isClosed(),
                    "closed flag mismatch for branch " + h.getBranch() + " in combo " + combo);
        }
    }
}
