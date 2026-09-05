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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixBackoutCoreRoundTripTest} for the
 * reused pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to {@link
 * CatCommand}, {@link FilesCommand}, {@link LocateCommand} and {@link ManifestCommand} together
 * across the native 6-combo grid (changelog family x treemanifest, dirstate fixed at v1 -- v2
 * needs Docker, see {@code RequirementMatrixCatFilesLocateManifestDockerRoundTripTest}).
 *
 * <p>These four commands are grouped into one trio (matching earlier waves' precedent of grouping
 * closely-related commands, e.g. Copy/Rename/Forget/Remove/Addremove) because they are all
 * pure-read, path/tree-oriented commands that share the same underlying manifest-reading code path
 * ({@link HgRepository#getManifestAtCommit} / {@code ManifestTreeIterator}) -- exercising them
 * together against one shared repository history (built once per combo by real hg, never written
 * by hg4j) both avoids redundant setup and directly tests that shared code path under nested
 * directories (forcing real treemanifest dirlog traversal, not just root-level entries), renames,
 * removals and the executable bit. Because none of these four commands ever write to a repository,
 * there is no risk of the JVM-internal write corruption documented on {@link
 * RequirementMatrixCommitHelperMain} (that issue is specific to hg4j's own revlog-writing code
 * interleaved with spawned {@code docker exec}/{@code docker run} processes in the same JVM) --
 * consequently this trio has no {@code HelperMain} subprocess counterpart.
 */
@Tag("interop")
public class RequirementMatrixCatFilesLocateManifestCoreRoundTripTest {

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
        List<RequirementCombo> out = new ArrayList<>();
        for (var cl : List.of(java.util.Map.entry("cl1", CL_V1), java.util.Map.entry("cl2", CL_V2), java.util.Map.entry("cl2+sidedata", CL_V2_SIDEDATA))) {
            for (var tm : List.of(java.util.Map.entry("flatmanifest", TREEMANIFEST_OFF), java.util.Map.entry("treemanifest", TREEMANIFEST_ON))) {
                List<String> args = new ArrayList<>();
                args.addAll(cl.getValue());
                args.addAll(tm.getValue());
                out.add(new RequirementCombo(cl.getKey() + "/" + tm.getKey(), args));
            }
        }
        return out.stream();
    }

    static File initWithCombo(Path tempDir, RequirementCombo combo, String suffix) throws Exception {
        File repoDir = tempDir.resolve("repo-" + combo.label().replace("/", "-").replace("+", "_") + "-" + suffix).toFile();
        repoDir.mkdirs();
        List<String> args = new ArrayList<>();
        args.add("init");
        for (String c : combo.initConfigArgs()) {
            args.add("--config");
            args.add(c);
        }
        HgTestUtils.hg(repoDir, args.toArray(new String[0]));
        return repoDir;
    }

    /**
     * Parses one line of {@code hg manifest --debug} output (verified format, see {@link
     * ManifestCommand}'s own javadoc): {@code "<40hex> <mode3> <flagchar> <path>"} where flagchar is
     * {@code *} for executable, {@code @} for symlink, or a plain space otherwise.
     */
    record RealManifestLine(String nodeHex, boolean executable, boolean symlink, String path) {
        static RealManifestLine parse(String line) {
            String nodeHex = line.substring(0, 40);
            String rest = line.substring(41); // skip the separating space
            char flag = rest.charAt(4);
            String path = rest.substring(6);
            return new RealManifestLine(nodeHex, flag == '*', flag == '@', path);
        }
    }

    private static List<RealManifestLine> realManifest(File repoDir, String rev) throws Exception {
        String out = HgTestUtils.hg(repoDir, "manifest", "--debug", "-r", rev);
        if (out.isEmpty()) {
            return List.of();
        }
        List<RealManifestLine> lines = new ArrayList<>();
        for (String l : out.split("\n")) {
            lines.add(RealManifestLine.parse(l));
        }
        lines.sort(java.util.Comparator.comparing(RealManifestLine::path));
        return lines;
    }

    /**
     * Builds a shared history exercising nested directories (forces real treemanifest dirlog
     * traversal in the {@code treemanifest} half of the grid), a rename, a new file added with the
     * executable bit, and a removal -- then checks {@link CatCommand}, {@link FilesCommand}, {@link
     * LocateCommand} and {@link ManifestCommand} against real hg's own equivalent output at every
     * step, across all six combos.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void catFilesLocateManifestAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "read");
        Path root = repoDir.toPath();

        Files.createDirectories(root.resolve("dir/sub"));
        Files.writeString(root.resolve("a.txt"), "hello\n");
        Files.writeString(root.resolve("dir/b.txt"), "world\n");
        Files.writeString(root.resolve("dir/sub/c.txt"), "deep\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");
        String c0Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "mv", "a.txt", "renamed.txt");
        Files.writeString(root.resolve("dir/d.txt"), "new\n");
        HgTestUtils.hg(repoDir, "add", "dir/d.txt");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1");
        String c1Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        root.resolve("dir/d.txt").toFile().setExecutable(true);
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c2");
        String c2Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "rm", "dir/b.txt");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c3");
        String c3Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgRepository repo = new HgRepository(repoDir);

        // --- CatCommand ---
        assertEquals("hello", new String(new CatCommand(repo).setFile("a.txt").setRevision(c0Hex).call(), StandardCharsets.UTF_8).trim(),
                "combo " + combo);
        assertEquals("deep", new String(new CatCommand(repo).setFile("dir/sub/c.txt").setRevision(c0Hex).call(), StandardCharsets.UTF_8).trim(),
                "combo " + combo + " (nested treemanifest path)");
        assertEquals(HgTestUtils.hg(repoDir, "cat", "-r", c1Hex, "renamed.txt"),
                new String(new CatCommand(repo).setFile("renamed.txt").setRevision(c1Hex).call(), StandardCharsets.UTF_8).trim(),
                "combo " + combo + " (content survives rename)");

        // --- FilesCommand ---
        List<String> realFilesC1 = List.of(HgTestUtils.hg(repoDir, "files", "-r", c1Hex).split("\n"));
        assertEquals(realFilesC1, new FilesCommand(repo).setRevision(c1Hex).call(), "combo " + combo + " files -r c1");

        List<String> realFilesC1Dir = List.of(HgTestUtils.hg(repoDir, "files", "-r", c1Hex, "dir").split("\n"));
        assertEquals(realFilesC1Dir, new FilesCommand(repo).setRevision(c1Hex).setPattern("dir").call(),
                "combo " + combo + " files -r c1 dir (pattern filter)");

        List<String> realFilesC3 = List.of(HgTestUtils.hg(repoDir, "files", "-r", c3Hex).split("\n"));
        assertEquals(realFilesC3, new FilesCommand(repo).setRevision(c3Hex).call(),
                "combo " + combo + " files -r c3 (after remove)");

        // --- LocateCommand ---
        List<String> realLocateC1Txt = List.of(HgTestUtils.hg(repoDir, "locate", "-r", c1Hex, "*.txt").split("\n"));
        assertEquals(realLocateC1Txt, new LocateCommand(repo).setRevision(c1Hex).setPattern("*.txt").call(),
                "combo " + combo + " locate -r c1 *.txt");

        List<String> realLocateC1DirTxt = List.of(HgTestUtils.hg(repoDir, "locate", "-r", c1Hex, "dir/*.txt").split("\n"));
        assertEquals(realLocateC1DirTxt, new LocateCommand(repo).setRevision(c1Hex).setPattern("dir/*.txt").call(),
                "combo " + combo + " locate -r c1 dir/*.txt");

        // Working copy (no revision) -- the repository's working parent is c3 at this point.
        List<String> realLocateWc = List.of(HgTestUtils.hg(repoDir, "locate", "*.txt").split("\n"));
        assertEquals(realLocateWc, new LocateCommand(repo).setPattern("*.txt").call(),
                "combo " + combo + " locate *.txt (working copy, no -r)");

        // --- ManifestCommand ---
        assertManifestMatches(repo, repoDir, c0Hex, combo);
        assertManifestMatches(repo, repoDir, c2Hex, combo);
        assertManifestMatches(repo, repoDir, c3Hex, combo);
    }

    private static void assertManifestMatches(HgRepository repo, File repoDir, String rev, RequirementCombo combo) throws Exception {
        List<RealManifestLine> expected = realManifest(repoDir, rev);
        List<ManifestCommand.ManifestEntry> actual = new ManifestCommand(repo).setRevision(rev).call();
        actual.sort(java.util.Comparator.comparing(ManifestCommand.ManifestEntry::getPath));

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
