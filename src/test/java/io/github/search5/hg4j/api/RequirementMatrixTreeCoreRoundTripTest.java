package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixMergeCoreRoundTripTest} for the
 * reused pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to {@link
 * TreeCommand} across the native 6-combo grid (changelog family x treemanifest, dirstate fixed at
 * v1 -- v2 needs Docker, see {@code RequirementMatrixTreeDockerRoundTripTest}).
 *
 * <p>{@link TreeCommand} is a pure read command (only lists a revision's manifest entries) -- so
 * unlike most other requirement-matrix suites in this package, no subprocess helper is used at all.
 *
 * <p>One scenario, across every combo, verified live against real {@code hg} 7.2 (2026-09-05): a
 * root file, an executable file, a symlink, and a nested-subdirectory file (exercising
 * treemanifest's dirlog). {@link TreeCommand#call()}'s result is compared entry-by-entry (path,
 * node hex, and mode category) against real hg's own {@code hg manifest --debug} output, whose
 * format ({@code "<40hex> <mode3> <flag> <path>"}, flag being {@code @} for a symlink, {@code *}
 * for executable, or a blank for a plain file) is parsed directly.
 */
@Tag("interop")
public class RequirementMatrixTreeCoreRoundTripTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    record RequirementCombo(String label, List<String> initConfigArgs) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final List<String> CL_V1 = List.of();
    private static final List<String> CL_V2 = List.of("format.exp-use-changelog-v2=enable-unstable-format-and-corrupt-my-data");
    private static final List<String> CL_V2_SIDEDATA = List.of(
            "format.exp-use-changelog-v2=enable-unstable-format-and-corrupt-my-data",
            "format.exp-use-copies-side-data-changeset=yes");

    private static final List<String> TREEMANIFEST_OFF = List.of();
    private static final List<String> TREEMANIFEST_ON = List.of("experimental.treemanifest=1");

    static Stream<RequirementCombo> combos() {
        List<RequirementCombo> out = new java.util.ArrayList<>();
        for (var cl : List.of(java.util.Map.entry("cl1", CL_V1), java.util.Map.entry("cl2", CL_V2), java.util.Map.entry("cl2+sidedata", CL_V2_SIDEDATA))) {
            for (var tm : List.of(java.util.Map.entry("flatmanifest", TREEMANIFEST_OFF), java.util.Map.entry("treemanifest", TREEMANIFEST_ON))) {
                List<String> args = new java.util.ArrayList<>();
                args.addAll(cl.getValue());
                args.addAll(tm.getValue());
                out.add(new RequirementCombo(cl.getKey() + "/" + tm.getKey(), args));
            }
        }
        return out.stream();
    }

    private static File initWithCombo(Path tempDir, RequirementCombo combo, String suffix) throws Exception {
        File repoDir = tempDir.resolve("repo-" + combo.label().replace("/", "-").replace("+", "_") + "-" + suffix).toFile();
        repoDir.mkdirs();
        List<String> args = new java.util.ArrayList<>();
        args.add("init");
        for (String c : combo.initConfigArgs()) {
            args.add("--config");
            args.add(c);
        }
        HgTestUtils.hg(repoDir, args.toArray(new String[0]));
        return repoDir;
    }

    private static final Pattern MANIFEST_DEBUG_LINE = Pattern.compile("^(\\w{40}) (\\d{3}) (.) (.+)$");

    /** path -> [hex, mode-category] where mode-category is one of "regular"/"executable"/"symlink". */
    private static Map<String, String[]> parseManifestDebug(String output) {
        Map<String, String[]> result = new HashMap<>();
        for (String line : output.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            Matcher m = MANIFEST_DEBUG_LINE.matcher(line);
            assertTrue(m.matches(), "unexpected `hg manifest --debug` line format: " + line);
            String hex = m.group(1);
            String flag = m.group(3);
            String path = m.group(4);
            String category = flag.equals("@") ? "symlink" : flag.equals("*") ? "executable" : "regular";
            result.put(path, new String[]{hex, category});
        }
        return result;
    }

    private static String modeCategory(int mode) {
        if (mode == 0120000 || mode == 0120777) {
            return "symlink";
        }
        if ((mode & 0111) != 0) {
            return "executable";
        }
        return "regular";
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jTreeListingMatchesRealHgManifestAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "tree");
        Path root = repoDir.toPath();

        Files.writeString(root.resolve("root.txt"), "root content\n");
        Files.writeString(root.resolve("exec.sh"), "echo hi\n");
        new File(repoDir, "exec.sh").setExecutable(true, false);
        boolean symlinksSupported = true;
        try {
            Files.createSymbolicLink(root.resolve("link.txt"), Path.of("root.txt"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            symlinksSupported = false;
        }
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub/nested.txt"), "nested content\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");
        String tipHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        Map<String, String[]> oracle = parseManifestDebug(HgTestUtils.hg(repoDir, "manifest", "--debug"));

        HgRepository repo = new HgRepository(repoDir);
        List<TreeCommand.TreeEntry> entries = new TreeCommand(repo).setRevision(-1)
                .setNodeId(io.github.search5.hg4j.util.NodeIdUtil.fromHex(tipHex)).call();

        Map<String, TreeCommand.TreeEntry> byPath = new HashMap<>();
        for (TreeCommand.TreeEntry e : entries) {
            byPath.put(e.getPath(), e);
        }

        assertEquals(oracle.keySet(), byPath.keySet(), "TreeCommand's path set must match real hg's manifest for combo " + combo);

        for (Map.Entry<String, String[]> oracleEntry : oracle.entrySet()) {
            String path = oracleEntry.getKey();
            if (path.equals("link.txt") && !symlinksSupported) {
                continue;
            }
            String expectedHex = oracleEntry.getValue()[0];
            String expectedCategory = oracleEntry.getValue()[1];
            TreeCommand.TreeEntry actual = byPath.get(path);
            assertEquals(expectedHex, actual.getNodeId(), path + "'s filelog node hex must match real hg for combo " + combo);
            assertEquals(expectedCategory, modeCategory(actual.getMode()), path + "'s mode category must match real hg for combo " + combo);
        }
    }
}
