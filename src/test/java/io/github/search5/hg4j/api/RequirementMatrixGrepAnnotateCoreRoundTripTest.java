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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixBackoutCoreRoundTripTest} for the
 * reused pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to {@link
 * GrepCommand} and {@link AnnotateCommand} together across the native 6-combo grid (changelog
 * family x treemanifest, dirstate fixed at v1 -- v2 needs Docker, see {@code
 * RequirementMatrixGrepAnnotateDockerRoundTripTest}).
 *
 * <p>Grouped into one trio (see {@link RequirementMatrixCatFilesLocateManifestCoreRoundTripTest}
 * for the same rationale applied to a different foursome) because both are read-only,
 * content-across-history commands that share a single built-once repository. Both commands only
 * ever read, so -- exactly as with the Cat/Files/Locate/Manifest trio -- there is no {@code
 * HelperMain} subprocess: the docker-exec-interleaved corruption documented on {@link
 * RequirementMatrixCommitHelperMain} is specific to hg4j's own revlog *writes*.
 *
 * <p>The shared history has two independent parts, verified live against real {@code hg} 7.2
 * (2026-09-05) before being ported:
 * <ul>
 *   <li>{@code file1.txt} at the repository root goes through three whole-file rewrites (a plain,
 *   rename-free history) so {@link GrepCommand}'s full-content-per-revision scan (it has no
 *   {@code --all}-style diff notion -- it reports every filelog revision whose content matches,
 *   full stop) can be checked against the exact filelog node id real hg's own {@code hg --debug
 *   debugindex} reports for each of those revisions.</li>
 *   <li>{@code content/orig.txt} (deliberately nested under a directory to force real treemanifest
 *   dirlog traversal in the {@code treemanifest} half of the grid) is edited once, then renamed to
 *   {@code content/renamed.txt} with one more line added in the same commit -- exercising {@link
 *   AnnotateCommand}'s copy-tracing rename-boundary crossing (backlog #27), checked line-by-line
 *   against real {@code hg annotate -n} (which shows the introducing changelog revision number,
 *   the same number {@link AnnotateCommand.BlameLine#getRevision()} reports).</li>
 * </ul>
 */
@Tag("interop")
public class RequirementMatrixGrepAnnotateCoreRoundTripTest {

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

    /** One row of {@code hg --debug debugindex FILE}: column 3 (0-indexed 2) is {@code linkrev},
     * column 4 (0-indexed 3) is the full 40-hex {@code nodeid} -- verified stable across every
     * combo in this grid (2026-09-05). */
    static String realFilelogNodeHexForLinkrev(File repoDir, String file, int linkrev) throws Exception {
        String out = HgTestUtils.hg(repoDir, "--debug", "debugindex", file);
        String[] lines = out.split("\n");
        for (int i = 1; i < lines.length; i++) { // skip header
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
    public void grepAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "grep");
        Path root = repoDir.toPath();

        Files.writeString(root.resolve("file1.txt"), "apple\nbanana\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "g0"); // linkrev 0

        Files.writeString(root.resolve("file1.txt"), "apple\ncherry\n");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "g1"); // linkrev 1

        Files.writeString(root.resolve("file1.txt"), "grape\ncherry\n");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "g2"); // linkrev 2

        HgRepository repo = new HgRepository(repoDir);

        String file1Link0Hex = realFilelogNodeHexForLinkrev(repoDir, "file1.txt", 0);
        String file1Link1Hex = realFilelogNodeHexForLinkrev(repoDir, "file1.txt", 1);
        String file1Link2Hex = realFilelogNodeHexForLinkrev(repoDir, "file1.txt", 2);

        List<GrepCommand.GrepResult> appleResults = new GrepCommand(repo).setQuery("apple").call();
        assertEquals(2, appleResults.size(), "combo " + combo + ": apple must match exactly linkrev 0 and 1's line 1");
        GrepCommand.GrepResult apple0 = findResult(appleResults, "file1.txt", 1);
        assertTrue(appleResults.stream().anyMatch(r -> r.hexNode.equals(file1Link0Hex)),
                "combo " + combo + ": must include linkrev 0's filelog node");
        assertTrue(appleResults.stream().anyMatch(r -> r.hexNode.equals(file1Link1Hex)),
                "combo " + combo + ": must include linkrev 1's filelog node");
        assertEquals("apple", apple0.lineContent);

        List<GrepCommand.GrepResult> cherryResults = new GrepCommand(repo).setQuery("cherry").call();
        assertEquals(2, cherryResults.size(), "combo " + combo + ": cherry must match exactly linkrev 1 and 2's line 2");
        for (GrepCommand.GrepResult r : cherryResults) {
            assertEquals(2, r.lineNumber, "combo " + combo);
            assertEquals("cherry", r.lineContent, "combo " + combo);
        }
        assertTrue(cherryResults.stream().anyMatch(r -> r.hexNode.equals(file1Link1Hex)),
                "combo " + combo);
        assertTrue(cherryResults.stream().anyMatch(r -> r.hexNode.equals(file1Link2Hex)),
                "combo " + combo);

        List<GrepCommand.GrepResult> caseInsensitive = new GrepCommand(repo).setQuery("GRAPE").setCaseInsensitive(true).call();
        assertEquals(1, caseInsensitive.size(), "combo " + combo + ": case-insensitive GRAPE must match linkrev 2's line 1 only");
        assertEquals("file1.txt", caseInsensitive.get(0).path);
        assertEquals(1, caseInsensitive.get(0).lineNumber);
        assertEquals(realFilelogNodeHexForLinkrev(repoDir, "file1.txt", 2), caseInsensitive.get(0).hexNode);

        List<GrepCommand.GrepResult> exactCaseGrapeLower = new GrepCommand(repo).setQuery("GRAPE").setCaseInsensitive(false).call();
        assertEquals(0, exactCaseGrapeLower.size(), "combo " + combo + ": case-sensitive GRAPE (uppercase) must not match lowercase grape");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void grepAcrossRenameAndNestedDirsAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "grep-nested");
        Path root = repoDir.toPath();
        Files.createDirectories(root.resolve("content"));

        Files.writeString(root.resolve("content/orig.txt"), "line1\nline2\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "n0"); // linkrev 0

        Files.writeString(root.resolve("content/orig.txt"), "line1\nline2changed\n");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "n1"); // linkrev 1

        HgTestUtils.hg(repoDir, "mv", "content/orig.txt", "content/renamed.txt");
        Files.writeString(root.resolve("content/renamed.txt"), "line1\nline2changed\nline3\n");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "n2"); // linkrev 2

        HgRepository repo = new HgRepository(repoDir);
        List<GrepCommand.GrepResult> results = new GrepCommand(repo).setQuery("line2changed").call();
        assertEquals(2, results.size(), "combo " + combo
                + ": line2changed must be found once in each of the two independent filelogs (orig.txt AND renamed.txt), the copy metadata does not merge their histories for a plain content grep");

        GrepCommand.GrepResult origMatch = findResult(results, "content/orig.txt", 2);
        assertEquals(realFilelogNodeHexForLinkrev(repoDir, "content/orig.txt", 1), origMatch.hexNode, "combo " + combo);

        GrepCommand.GrepResult renamedMatch = findResult(results, "content/renamed.txt", 2);
        assertEquals(realFilelogNodeHexForLinkrev(repoDir, "content/renamed.txt", 2), renamedMatch.hexNode, "combo " + combo);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void annotateAcrossRenameAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "annotate");
        Path root = repoDir.toPath();
        Files.createDirectories(root.resolve("content"));

        Files.writeString(root.resolve("content/orig.txt"), "line1\nline2\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "a0"); // changelog rev 0

        Files.writeString(root.resolve("content/orig.txt"), "line1\nline2changed\n");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "a1"); // changelog rev 1

        HgTestUtils.hg(repoDir, "mv", "content/orig.txt", "content/renamed.txt");
        Files.writeString(root.resolve("content/renamed.txt"), "line1\nline2changed\nline3\n");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "a2"); // changelog rev 2
        String tipHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        String realAnnotate = HgTestUtils.hg(repoDir, "annotate", "-r", tipHex, "-n", "content/renamed.txt");
        List<String> expectedContent = new ArrayList<>();
        List<Integer> expectedRev = new ArrayList<>();
        for (String line : realAnnotate.split("\n")) {
            int colon = line.indexOf(':');
            expectedRev.add(Integer.parseInt(line.substring(0, colon).trim()));
            expectedContent.add(line.substring(colon + 2));
        }
        assertEquals(List.of("line1", "line2changed", "line3"), expectedContent, "sanity: real hg's own annotate content for combo " + combo);
        assertEquals(List.of(0, 1, 2), expectedRev, "sanity: real hg's own annotate line-introducing revs for combo " + combo);

        HgRepository repo = new HgRepository(repoDir);
        List<AnnotateCommand.BlameLine> blame = new AnnotateCommand(repo).setPath("content/renamed.txt").call();
        assertEquals(expectedContent.size(), blame.size(), "combo " + combo);
        for (int i = 0; i < expectedContent.size(); i++) {
            assertEquals(expectedContent.get(i), blame.get(i).getContent(), "combo " + combo + " line " + (i + 1));
            assertEquals(expectedRev.get(i).intValue(), blame.get(i).getRevision(), "combo " + combo + " line " + (i + 1) + " introducing revision");
        }
    }
}
