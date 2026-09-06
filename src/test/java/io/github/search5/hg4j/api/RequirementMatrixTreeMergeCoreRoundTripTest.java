package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.Map;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixMergeCoreRoundTripTest} for the
 * reused pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to {@link
 * TreeMergeCommand} across the native 6-combo grid (changelog family x treemanifest, dirstate
 * fixed at v1 -- v2 needs Docker, see {@code RequirementMatrixTreeMergeDockerRoundTripTest}).
 *
 * <p>{@link TreeMergeCommand} never touches the working directory or dirstate at all (see its
 * class javadoc) -- so unlike most other requirement-matrix suites in this package, no subprocess
 * helper is used at all.
 *
 * <p>One scenario, across every combo, combining every per-file decision {@link TreeMergeCommand}
 * has to make at once: an add-by-ours-only file (no delta needed), a take-theirs file (content
 * changed only on "theirs"), a real-hg-removed-by-theirs file, and a genuinely conflicting file
 * both sides edited on the same line. A real hg oracle actually performs {@code hg merge} (left
 * unresolved by design -- verified live: real hg leaves the working copy in the paused,
 * conflict-marked state rather than aborting the whole operation) in a separate clone to get the
 * ground truth for the non-conflicting merged content and the removed-file set; the conflicted
 * file is checked only for conflict-marker syntax and the reported path, not byte-exact label text
 * (real hg's default {@code internal:merge} tool and hg4j's {@link
 * io.github.search5.hg4j.merge.Merge3} use different marker labels by design -- this class's own
 * javadoc already documents that as an intentional simplification for this working-copy-free API).
 */
@Tag("interop")
public class RequirementMatrixTreeMergeCoreRoundTripTest {

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
        for (var cl : List.of(Map.entry("cl1", CL_V1), Map.entry("cl2", CL_V2), Map.entry("cl2+sidedata", CL_V2_SIDEDATA))) {
            for (var tm : List.of(Map.entry("flatmanifest", TREEMANIFEST_OFF), Map.entry("treemanifest", TREEMANIFEST_ON))) {
                List<String> args = new ArrayList<>();
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
        List<String> args = new ArrayList<>();
        args.add("init");
        for (String c : combo.initConfigArgs()) {
            args.add("--config");
            args.add(c);
        }
        HgTestUtils.hg(repoDir, args.toArray(new String[0]));
        return repoDir;
    }

    /** Runs a real hg command tolerating ANY exit code (needed for {@code hg merge} left
     * deliberately unresolved by a genuine conflict) and returns combined stdout+stderr. */
    private static String hgTolerant(File repoDir, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "hg";
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(repoDir);
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jTreeMergeMatchesRealHgMergeAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "treemerge");
        Path root = repoDir.toPath();

        Files.writeString(root.resolve("shared.txt"), "shared base\n");
        Files.writeString(root.resolve("removed-by-theirs.txt"), "bye\n");
        Files.writeString(root.resolve("conflict.txt"), "line1\nline2\nline3\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "base");
        String baseHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        // "ours": adds a new file, edits conflict.txt's middle line; leaves shared.txt/removed-by-theirs.txt alone.
        Files.writeString(root.resolve("ours-only.txt"), "o\n");
        Files.writeString(root.resolve("conflict.txt"), "line1\nOURS\nline3\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "ours");
        String oursHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        // "theirs": forks from base, edits shared.txt, removes removed-by-theirs.txt, edits conflict.txt's same middle line differently.
        HgTestUtils.hg(repoDir, "update", baseHex);
        Files.writeString(root.resolve("shared.txt"), "shared base\nshared theirs addition\n");
        HgTestUtils.hg(repoDir, "remove", "removed-by-theirs.txt");
        Files.writeString(root.resolve("conflict.txt"), "line1\nTHEIRS\nline3\n");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "theirs");
        String theirsHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        // Real hg oracle: an actual `hg merge` in a separate clone, checked out at "ours".
        File oracleDir = tempDir.resolve("oracle").toFile();
        HgTestUtils.hg(repoDir, "clone", "-q", repoDir.getAbsolutePath(), oracleDir.getAbsolutePath());
        HgTestUtils.hg(oracleDir, "update", "-q", oursHex);
        // --config ui.merge=internal:merge: force real hg's own portable, non-interactive
        // textual 3-way merge tool -- without this, real hg falls back to whatever external
        // merge tool is configured in the environment (e.g. vimdiff), which launches a
        // full-screen interactive session and hangs indefinitely waiting for terminal input in a
        // headless test run.
        hgTolerant(oracleDir, "--config", "ui.merge=internal:merge", "merge", "-r", theirsHex);

        String oracleShared = Files.readString(oracleDir.toPath().resolve("shared.txt"));
        assertFalse(Files.exists(oracleDir.toPath().resolve("removed-by-theirs.txt")), "precondition: real hg merge must remove removed-by-theirs.txt");
        String oracleResolveList = HgTestUtils.hg(oracleDir, "resolve", "--list");
        assertEquals("U conflict.txt", oracleResolveList, "precondition: real hg merge must leave exactly conflict.txt unresolved");

        // hg4j: pure computation, no working copy or dirstate involved.
        HgRepository repo = new HgRepository(repoDir);
        TreeMergeCommand.TreeMergeResult result = new TreeMergeCommand(repo)
                .setOurs(NodeIdUtil.fromHex(oursHex)).setTheirs(NodeIdUtil.fromHex(theirsHex)).call();

        assertTrue(result.isConflicted(), "combo " + combo);
        assertEquals(List.of("conflict.txt"), result.getConflicts(), "combo " + combo);

        assertFalse(result.getChangedFiles().containsKey("ours-only.txt"), "ours' own unopposed addition needs no delta for combo " + combo);

        assertTrue(result.getChangedFiles().containsKey("shared.txt"), "theirs' edit to shared.txt must be reported for combo " + combo);
        assertEquals(oracleShared, new String(result.getChangedFiles().get("shared.txt"), StandardCharsets.UTF_8),
                "shared.txt's merged content must match real hg's own merge for combo " + combo);

        assertTrue(result.getRemovedFiles().contains("removed-by-theirs.txt"), "combo " + combo);
        assertFalse(result.getChangedFiles().containsKey("removed-by-theirs.txt"), "combo " + combo);

        byte[] conflictContent = result.getChangedFiles().get("conflict.txt");
        assertTrue(conflictContent != null, "a conflicted file's markers must still be returned for combo " + combo);
        String conflictText = new String(conflictContent, StandardCharsets.UTF_8);
        assertTrue(conflictText.contains("<<<<<<<") && conflictText.contains("=======") && conflictText.contains(">>>>>>>"),
                "conflict.txt must carry real conflict marker syntax for combo " + combo + ": " + conflictText);
    }
}
