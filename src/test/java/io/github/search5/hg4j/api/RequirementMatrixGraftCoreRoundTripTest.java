package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.errors.HgMergeConflictException;
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
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixCoreRoundTripTest} for the reused
 * pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to {@link
 * GraftCommand} across the native 6-combo grid (changelog family x treemanifest, dirstate fixed
 * at v1 -- v2 needs Docker, see {@code RequirementMatrixGraftDockerRoundTripTest}).
 *
 * <p>Each combo exercises two scenarios in the same repository:
 * <ol>
 *   <li>A conflict-free graft of a diverging source branch onto a destination branch (mirrors
 *       {@code RequirementMatrixRebaseCoreRoundTripTest}'s own scenario): real hg re-reads the
 *       result, {@code verify} must be clean, the grafted commit's parent must be the destination,
 *       both branches' file content must be present, and -- since 2026-09-05, see {@link
 *       GraftCommand}'s own class javadoc -- the ORIGINAL source revision must stay fully visible
 *       in a plain {@code hg log} (a plain graft is a copy, not a rewrite; it must never write an
 *       obsolescence marker the way {@link RebaseCommand}/{@link HisteditCommand} do).</li>
 *   <li>A graft that genuinely conflicts (same file changed differently on both sides since their
 *       common ancestor): {@link GraftCommand#call()} must pause with {@link
 *       HgMergeConflictException} and real hg's own {@code hg resolve --list} must agree; after a
 *       manual resolution, {@link GraftCommand#continueGraft()} must complete the commit and real
 *       hg must accept the result as valid.</li>
 * </ol>
 */
@Tag("interop")
public class RequirementMatrixGraftCoreRoundTripTest {

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jGraftAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "graft");

        // --- Scenario 1: conflict-free graft of a diverging branch ---
        Files.writeString(repoDir.toPath().resolve("base.txt"), "base\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");

        Files.writeString(repoDir.toPath().resolve("target.txt"), "on-target\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1 target");
        String targetHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "update", "0");
        Files.writeString(repoDir.toPath().resolve("source.txt"), "on-source\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c2 source");
        String sourceHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "update", targetHex);

        HgRepository repo = new HgRepository(repoDir);
        String graftedHex = new GraftCommand(repo).setSource(sourceHex).call();

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.toLowerCase().contains("integrity error"),
                "real hg verify must find no integrity errors after graft for combo " + combo + ": " + verify);

        String graftedParent = HgTestUtils.hg(repoDir, "log", "-r", graftedHex, "--template", "{p1node}");
        assertEquals(targetHex, graftedParent, "grafted commit's parent must be the destination for combo " + combo);

        String catTarget = HgTestUtils.hg(repoDir, "cat", "-r", graftedHex, "target.txt");
        assertEquals("on-target", catTarget.trim());
        String catSource = HgTestUtils.hg(repoDir, "cat", "-r", graftedHex, "source.txt");
        assertEquals("on-source", catSource.trim());

        String logAll = HgTestUtils.hg(repoDir, "log", "--template", "{node} ");
        assertTrue(logAll.contains(sourceHex),
                "a plain graft must never hide its source revision (no obsmarker) for combo " + combo);

        File obsstore = new File(repo.getStoreDir(), "obsstore");
        assertFalse(obsstore.exists() && obsstore.length() > 0,
                "a plain graft must not write any obsolescence marker for combo " + combo);

        // --- Scenario 2: a graft that genuinely conflicts, then continueGraft() ---
        HgTestUtils.hg(repoDir, "update", graftedHex);
        Files.writeString(repoDir.toPath().resolve("conflict.txt"), "line1\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c3 conflict base");
        String conflictBaseHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        Files.writeString(repoDir.toPath().resolve("conflict.txt"), "line1-dest\n");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c4 dest modifies conflict.txt");
        String destHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "update", conflictBaseHex);
        Files.writeString(repoDir.toPath().resolve("conflict.txt"), "line1-source\n");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c5 source modifies conflict.txt (conflicts with dest)");
        String conflictSourceHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "update", destHex);

        GraftCommand conflictGraft = new GraftCommand(repo).setSource(conflictSourceHex);
        HgMergeConflictException ex = assertThrows(HgMergeConflictException.class, conflictGraft::call,
                "a genuine same-file conflict must pause the graft for combo " + combo);
        assertEquals(List.of("conflict.txt"), ex.getConflictPaths());

        String resolveList = HgTestUtils.hg(repoDir, "resolve", "--list");
        assertEquals("U conflict.txt", resolveList.trim(),
                "real hg must see the same unresolved-file bookkeeping for combo " + combo);

        // Manually resolve, exactly like a user driving `hg resolve` would.
        Files.writeString(repoDir.toPath().resolve("conflict.txt"), "line1-dest\nline1-source\n");
        HgTestUtils.hg(repoDir, "resolve", "--mark", "conflict.txt");

        String continuedHex = new GraftCommand(repo).continueGraft();
        assertNotNull(continuedHex);

        String verify2 = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify2.toLowerCase().contains("integrity error"),
                "real hg verify must find no integrity errors after continueGraft() for combo " + combo + ": " + verify2);

        String catConflict = HgTestUtils.hg(repoDir, "cat", "-r", continuedHex, "conflict.txt");
        assertEquals("line1-dest\nline1-source", catConflict.trim());

        String resolveListAfter = HgTestUtils.hg(repoDir, "resolve", "--list");
        assertEquals("", resolveListAfter.trim(), "no unresolved files must remain for combo " + combo);
    }
}
