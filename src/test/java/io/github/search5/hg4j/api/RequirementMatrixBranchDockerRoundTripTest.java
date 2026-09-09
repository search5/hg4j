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
import io.github.search5.hg4j.lib.HgRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest}
 * for the full 30-combo design this reuses verbatim) applied to {@link BranchCommand}/{@link
 * BranchesCommand} -- the Docker-only counterpart of {@link RequirementMatrixBranchCoreRoundTripTest}'s
 * native 6-combo scenario (create a named branch, commit on it, close it, list via {@link
 * BranchesCommand}) across every one of the 30 combos, including dirstate v2.
 *
 * <p>Each case gets its own fresh, short-lived container, matching every other write-direction
 * test in this matrix family. hg4j's own {@link BranchCommand}/{@link CommitCommand} calls run
 * inline in this JVM, alongside the native rust-hg subprocess calls this class uses for the real
 * hg side.
 */
@Tag("interop")
public class RequirementMatrixBranchDockerRoundTripTest {

    /** Parses a real {@code hg branches} line, e.g. "feature   3:abcdef012345 (inactive)". */
    private static final Pattern BRANCHES_LINE =
            Pattern.compile("^(\\S+)\\s+(\\d+):([0-9a-f]+)(?:\\s+\\((\\S+)\\))?$");

    @BeforeAll
    static void checkNativeHgRust() {
        Assumptions.assumeTrue(NativeHgRust.isAvailable(),
                "Native rust-enabled hg (run docker/hg-rust-7.2.4/build-native.sh) is not built. Skipping the whole class.");
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

    /** EXPERIMENT (2026-09-09): inline instead of subprocess. */
    private static void branchCommitInSubprocess(Path repoDir, String branchName, String fileName, String fileContent,
                                                  String author, String message, boolean closeBranch) throws Exception {
        HgRepository repo = new HgRepository(repoDir.toFile());
        if (branchName != null && !branchName.isEmpty()) {
            new BranchCommand(repo).setBranchName(branchName).call();
        }
        Files.writeString(new File(repoDir.toFile(), fileName).toPath(), fileContent, StandardCharsets.UTF_8);
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor(author).setMessage(message).setCloseBranch(closeBranch).call();
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
    public void hg4jBranchAcrossDockerCombo(RequirementCombo combo) throws Exception {
        NativeHgRust.withFreshWorkDir("hg4j-native-matrix", (workDir) -> {
            String repoRelPath = "repo";
            Path hostRepoDir = workDir.resolve(repoRelPath);
            Files.createDirectories(hostRepoDir);

            List<String> initArgs = new ArrayList<>(List.of("init", "."));
            for (String c : combo.initConfigArgs()) {
                initArgs.add("--config");
                initArgs.add(c);
            }
            NativeHgRust.hg(workDir, repoRelPath, initArgs.toArray(new String[0]));

            NativeHgRust.hg(workDir, repoRelPath, "id"); // sanity: repo usable before hg4j touches it

            // 1. hg4j creates the "default" branch's first commit, then a named branch + commit.
            branchCommitInSubprocess(hostRepoDir, null, "base.txt", "base\n", "dev", "c0 base", false);
            branchCommitInSubprocess(hostRepoDir, "feature", "f1.txt", "feature work\n", "dev", "c1-feature", false);

            String nativeBranchOfTip = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", "tip", "--template", "{branch}");
            assertEquals("feature", nativeBranchOfTip, "real hg must see the hg4j-committed branch name for combo " + combo);

            String verify1 = NativeHgRust.hg(workDir, repoRelPath, "verify");
            assertFalse(verify1.toLowerCase().contains("integrity error"),
                    "real hg verify after branch commit must find no integrity errors for combo " + combo + ": " + verify1);

            String nativeBranchesOpen = NativeHgRust.hg(workDir, repoRelPath, "branches");
            assertBranchesMatch(nativeBranchesOpen, hostRepoDir, combo);

            // 2. Close the feature branch via hg4j's CommitCommand.setCloseBranch(true).
            branchCommitInSubprocess(hostRepoDir, null, "f1.txt", "feature work, closing\n", "dev", "close-feature", true);

            String verify2 = NativeHgRust.hg(workDir, repoRelPath, "verify");
            assertFalse(verify2.toLowerCase().contains("integrity error"),
                    "real hg verify after closing the branch must find no integrity errors for combo " + combo + ": " + verify2);

            String nativeDefaultAfterClose = NativeHgRust.hg(workDir, repoRelPath, "branches");
            assertFalse(nativeDefaultAfterClose.contains("feature"),
                    "real hg's default 'hg branches' must hide the fully-closed branch for combo " + combo + ": " + nativeDefaultAfterClose);

            String nativeClosed = NativeHgRust.hg(workDir, repoRelPath, "branches", "--closed");
            assertTrue(nativeClosed.contains("(closed)"), "real hg 'hg branches --closed' must mark it closed for combo " + combo + ": " + nativeClosed);
            assertBranchesMatch(nativeClosed, hostRepoDir, combo);
        });
    }

    /** Reads {@link BranchesCommand} in-process against the host-mounted repo directory and
     * compares its listing (order + closed flag) against an already-fetched real-hg text listing. */
    private static void assertBranchesMatch(String nativeOut, Path repoDir, RequirementCombo combo) throws Exception {
        List<BranchesCommand.BranchHead> hg4jBranches =
                new BranchesCommand(new HgRepository(repoDir.toFile()))
                        .setIncludeClosed(true).call();
        // Filter to whichever set real hg's own listing actually shows (default listing hides
        // fully-closed branches; --closed shows everything) so both sides describe the same set.
        Set<String> nativeNames = new HashSet<>();
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
        Map<String, Boolean> nativeClosed = new HashMap<>();
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
