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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest}
 * for the full 30-combo design this reuses verbatim) applied to {@link GrepCommand} and {@link
 * AnnotateCommand} together -- the Docker-only counterpart of {@link
 * RequirementMatrixGrepAnnotateCoreRoundTripTest}'s native 6-combo scenarios (see that class's
 * javadoc for the shared-repository rationale and why no {@code HelperMain} subprocess is needed:
 * neither command ever writes to a repository).
 *
 * <p>This is also where the {@code fileindex-v1}/{@code general-v2} cells matter most: those two
 * storage extensions replace {@code fncache} with their own internal sidecar files and never
 * write an {@code fncache} at all (verified against the real {@code hg-rust-7.2.4} image), which
 * is exactly the condition that exposed a real hg4j completeness bug in {@link GrepCommand} --
 * see {@code GrepCommand#enumerateTrackedFilelogs}'s javadoc for the fix. Every combo in this
 * class re-verifies both commands, so the {@code fileindex-v1}/{@code general-v2} cells are
 * regression coverage for that exact fix, not merely more of the same.
 */
@Tag("interop")
public class RequirementMatrixGrepAnnotateDockerRoundTripTest {

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
        Path workDir = Files.createTempDirectory("hg4j-docker-grepannotate-matrix").toRealPath();
        String containerName = "hg4j-reqmatrix-grepann-" + UUID.randomUUID().toString().substring(0, 8);
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
     * {@link RequirementMatrixDockerRoundTripTest#combos()}. */
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

    /** Same {@code hg --debug debugindex} parsing as {@link
     * RequirementMatrixGrepAnnotateCoreRoundTripTest#realFilelogNodeHexForLinkrev}. */
    private static String realFilelogNodeHexForLinkrev(String container, String repoRelPath, String file, int linkrev) throws Exception {
        String out = dockerHgIn(container, repoRelPath, "--debug", "debugindex", file);
        String[] lines = out.split("\n");
        for (int i = 1; i < lines.length; i++) {
            String[] tokens = lines[i].trim().split("\\s+");
            if (tokens.length < 4) {
                continue;
            }
            if (Integer.parseInt(tokens[2]) == linkrev) {
                return tokens[3];
            }
        }
        throw new AssertionError("No debugindex row for " + file + " with linkrev " + linkrev + ": " + out);
    }

    private static GrepCommand.GrepResult findResult(List<GrepCommand.GrepResult> results, String path, int lineNumber) {
        for (GrepCommand.GrepResult r : results) {
            if (r.path.equals(path) && r.lineNumber == lineNumber) {
                return r;
            }
        }
        throw new AssertionError("No grep result for path=" + path + " lineNumber=" + lineNumber + " in " + results.size() + " results");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void grepAcrossDockerCombo(RequirementCombo combo) throws Exception {
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

            Files.writeString(hostRepoDir.resolve("file1.txt"), "apple\nbanana\n");
            dockerHgIn(containerName, repoRelPath, "add");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "g0");

            Files.writeString(hostRepoDir.resolve("file1.txt"), "apple\ncherry\n");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "g1");

            Files.writeString(hostRepoDir.resolve("file1.txt"), "grape\ncherry\n");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "g2");

            HgRepository repo = new HgRepository(hostRepoDir.toFile());

            String file1Link0Hex = realFilelogNodeHexForLinkrev(containerName, repoRelPath, "file1.txt", 0);
            String file1Link1Hex = realFilelogNodeHexForLinkrev(containerName, repoRelPath, "file1.txt", 1);
            String file1Link2Hex = realFilelogNodeHexForLinkrev(containerName, repoRelPath, "file1.txt", 2);

            List<GrepCommand.GrepResult> appleResults = new GrepCommand(repo).setQuery("apple").call();
            assertEquals(2, appleResults.size(), "combo " + combo + ": apple must match exactly linkrev 0 and 1's line 1");
            assertTrue(appleResults.stream().anyMatch(r -> r.hexNode.equals(file1Link0Hex)), "combo " + combo);
            assertTrue(appleResults.stream().anyMatch(r -> r.hexNode.equals(file1Link1Hex)), "combo " + combo);

            List<GrepCommand.GrepResult> cherryResults = new GrepCommand(repo).setQuery("cherry").call();
            assertEquals(2, cherryResults.size(), "combo " + combo + ": cherry must match exactly linkrev 1 and 2's line 2");
            assertTrue(cherryResults.stream().anyMatch(r -> r.hexNode.equals(file1Link1Hex)), "combo " + combo);
            assertTrue(cherryResults.stream().anyMatch(r -> r.hexNode.equals(file1Link2Hex)), "combo " + combo);

            List<GrepCommand.GrepResult> caseInsensitive = new GrepCommand(repo).setQuery("GRAPE").setCaseInsensitive(true).call();
            assertEquals(1, caseInsensitive.size(), "combo " + combo + ": case-insensitive GRAPE must match linkrev 2's line 1 only");
            assertEquals(file1Link2Hex, caseInsensitive.get(0).hexNode, "combo " + combo);
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void grepAcrossRenameAndNestedDirsAcrossDockerCombo(RequirementCombo combo) throws Exception {
        withFreshContainer((containerName, workDir) -> {
            String repoRelPath = "repo";
            Path hostRepoDir = workDir.resolve(repoRelPath);
            Files.createDirectories(hostRepoDir.resolve("content"));

            List<String> initArgs = new ArrayList<>(List.of("init", "."));
            for (String c : combo.initConfigArgs()) {
                initArgs.add("--config");
                initArgs.add(c);
            }
            dockerHgIn(containerName, repoRelPath, initArgs.toArray(new String[0]));

            Files.writeString(hostRepoDir.resolve("content/orig.txt"), "line1\nline2\n");
            dockerHgIn(containerName, repoRelPath, "add");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "n0");

            Files.writeString(hostRepoDir.resolve("content/orig.txt"), "line1\nline2changed\n");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "n1");

            dockerHgIn(containerName, repoRelPath, "mv", "content/orig.txt", "content/renamed.txt");
            Files.writeString(hostRepoDir.resolve("content/renamed.txt"), "line1\nline2changed\nline3\n");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "n2");

            HgRepository repo = new HgRepository(hostRepoDir.toFile());
            List<GrepCommand.GrepResult> results = new GrepCommand(repo).setQuery("line2changed").call();
            assertEquals(2, results.size(), "combo " + combo
                    + ": line2changed must be found once in each of the two independent filelogs (orig.txt AND renamed.txt)");

            GrepCommand.GrepResult origMatch = findResult(results, "content/orig.txt", 2);
            assertEquals(realFilelogNodeHexForLinkrev(containerName, repoRelPath, "content/orig.txt", 1), origMatch.hexNode, "combo " + combo);

            GrepCommand.GrepResult renamedMatch = findResult(results, "content/renamed.txt", 2);
            assertEquals(realFilelogNodeHexForLinkrev(containerName, repoRelPath, "content/renamed.txt", 2), renamedMatch.hexNode, "combo " + combo);
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void annotateAcrossRenameAcrossDockerCombo(RequirementCombo combo) throws Exception {
        withFreshContainer((containerName, workDir) -> {
            String repoRelPath = "repo";
            Path hostRepoDir = workDir.resolve(repoRelPath);
            Files.createDirectories(hostRepoDir.resolve("content"));

            List<String> initArgs = new ArrayList<>(List.of("init", "."));
            for (String c : combo.initConfigArgs()) {
                initArgs.add("--config");
                initArgs.add(c);
            }
            dockerHgIn(containerName, repoRelPath, initArgs.toArray(new String[0]));

            Files.writeString(hostRepoDir.resolve("content/orig.txt"), "line1\nline2\n");
            dockerHgIn(containerName, repoRelPath, "add");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "a0");

            Files.writeString(hostRepoDir.resolve("content/orig.txt"), "line1\nline2changed\n");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "a1");

            dockerHgIn(containerName, repoRelPath, "mv", "content/orig.txt", "content/renamed.txt");
            Files.writeString(hostRepoDir.resolve("content/renamed.txt"), "line1\nline2changed\nline3\n");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "a2");
            String tipHex = dockerHgIn(containerName, repoRelPath, "log", "-r", ".", "--template", "{node}");

            String realAnnotate = dockerHgIn(containerName, repoRelPath, "annotate", "-r", tipHex, "-n", "content/renamed.txt");
            List<String> expectedContent = new ArrayList<>();
            List<Integer> expectedRev = new ArrayList<>();
            for (String line : realAnnotate.split("\n")) {
                int colon = line.indexOf(':');
                expectedRev.add(Integer.parseInt(line.substring(0, colon).trim()));
                expectedContent.add(line.substring(colon + 2));
            }
            assertEquals(List.of("line1", "line2changed", "line3"), expectedContent, "sanity: real hg's own annotate content for combo " + combo);
            assertEquals(List.of(0, 1, 2), expectedRev, "sanity: real hg's own annotate line-introducing revs for combo " + combo);

            HgRepository repo = new HgRepository(hostRepoDir.toFile());
            List<AnnotateCommand.BlameLine> blame = new AnnotateCommand(repo).setPath("content/renamed.txt").call();
            assertEquals(expectedContent.size(), blame.size(), "combo " + combo);
            for (int i = 0; i < expectedContent.size(); i++) {
                assertEquals(expectedContent.get(i), blame.get(i).getContent(), "combo " + combo + " line " + (i + 1));
                assertEquals(expectedRev.get(i).intValue(), blame.get(i).getRevision(), "combo " + combo + " line " + (i + 1) + " introducing revision");
            }
        });
    }
}
