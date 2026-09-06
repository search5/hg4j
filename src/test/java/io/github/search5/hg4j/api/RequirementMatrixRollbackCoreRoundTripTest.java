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
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.Map;
import org.junit.jupiter.api.Assertions;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixBackoutCoreRoundTripTest} for the
 * reused pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to {@link
 * RollbackCommand} across the native 6-combo grid (changelog family x treemanifest, dirstate fixed
 * at v1 -- v2 needs Docker, see {@code RequirementMatrixRollbackDockerRoundTripTest}).
 *
 * <p>Unlike {@link RequirementMatrixRecoverCoreRoundTripTest} (which must hand-fabricate a
 * leftover journal), this is fully organic: {@link CommitCommand} already writes {@code
 * .hg/store/undo}/{@code undo.docket.*.bck}/{@code .hg/undo.backup.dirstate}/{@code
 * .hg/undo.backup.bookmarks} after every successful commit (see {@code
 * CommitCommand#recordRevlogRollbackState}'s javadoc for the v2/docket-aware bookkeeping this
 * requirement-matrix expansion added, 2026-09-05), so this test just performs two real hg4j
 * commits and rolls the second one back:
 * <ol>
 *   <li>real hg creates the combo repository and commits c0 (a root file plus a nested
 *   {@code sub/a.txt}, so treemanifest combos actually engage a directory manifest).</li>
 *   <li>hg4j commits c1, changing {@code sub/a.txt}.</li>
 *   <li>hg4j's {@link RollbackCommand} undoes c1.</li>
 *   <li>real hg verifies the repository is back to exactly c0's store content (single revision,
 *   correct tip content, correct dirstate parent, no integrity errors) -- matching real hg's own
 *   {@code hg rollback} semantics, the working copy's on-disk {@code sub/a.txt} content is left
 *   untouched by design (verified live against real hg 7.2, 2026-09-05: after {@code hg rollback},
 *   {@code hg status} shows the file as locally modified, it does not restore it), so {@code
 *   status} must show {@code M sub/a.txt} rather than a clean working copy.</li>
 * </ol>
 */
@Tag("interop")
public class RequirementMatrixRollbackCoreRoundTripTest {

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jRollbackAfterHg4jCommitAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "rollback");

        Files.writeString(repoDir.toPath().resolve("a.txt"), "root-base\n");
        Files.createDirectories(repoDir.toPath().resolve("sub"));
        Files.writeString(repoDir.toPath().resolve("sub/a.txt"), "sub-base\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");
        String c0Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgRepository repo = new HgRepository(repoDir);
        Files.writeString(repoDir.toPath().resolve("sub/a.txt"), "sub-changed-by-c1\n");
        new CommitCommand(repo).setAuthor("dev").setMessage("c1").call();

        new RollbackCommand(repo).call();

        String revs = HgTestUtils.hg(repoDir, "log", "--template", "{rev}\\n").trim();
        assertEquals("0", revs, "only c0 must remain after rollback for combo " + combo);

        String tipHex = HgTestUtils.hg(repoDir, "log", "-r", "tip", "--template", "{node}");
        assertEquals(c0Hex, tipHex, "tip must be c0 after rollback for combo " + combo);

        assertEquals("sub-base", HgTestUtils.hg(repoDir, "cat", "-r", "tip", "sub/a.txt").trim(),
                "sub/a.txt content must be reverted to c0 for combo " + combo);

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.toLowerCase().contains("integrity error") || verify.toLowerCase().contains("error:"),
                "real hg verify must find no integrity errors after rollback for combo " + combo + ": " + verify);

        String parents = HgTestUtils.hg(repoDir, "parents", "--template", "{node}");
        assertEquals(c0Hex, parents, "dirstate parent must be reverted to c0 for combo " + combo);

        // Real hg's own `hg rollback` never touches the working copy -- verified live (2026-09-05):
        // the on-disk file keeps its c1 content, so `hg status` sees it as locally modified.
        assertEquals("M sub/a.txt", HgTestUtils.hg(repoDir, "status"),
                "the working copy is left untouched by rollback (matches real hg) for combo " + combo);
        assertEquals("sub-changed-by-c1", Files.readString(repoDir.toPath().resolve("sub/a.txt")).trim());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jRollbackWithNoUndoInfoThrowsAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        // Deliberately no commits at all (not even via real hg): a real `hg commit` would itself
        // create a `store/undo` file (in real hg's own NUL-separated format, distinct from -- but
        // same filename as -- hg4j's own), which would make undoFile.exists() true and defeat the
        // point of this "nothing to roll back" scenario.
        File repoDir = initWithCombo(tempDir, combo, "rollback-noundo");

        HgRepository repo = new HgRepository(repoDir);
        assertTrue(Assertions.assertThrows(IllegalStateException.class,
                () -> new RollbackCommand(repo).call()).getMessage().contains("No rollback information available"),
                "combo " + combo);
    }
}
