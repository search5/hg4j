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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixMergeCoreRoundTripTest} for the
 * reused pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to {@link
 * RevertCommand} across the native 6-combo grid (changelog family x treemanifest, dirstate fixed
 * at v1 -- v2 needs Docker, see {@code RequirementMatrixRevertDockerRoundTripTest}).
 *
 * <p>Two independent scenarios are covered, both across every combo, both ported from live
 * verification against real {@code hg} 7.2 (2026-09-05 -- see {@link RevertCommand}'s own
 * javadoc for the full behavioral writeup):
 * <ul>
 *   <li>{@link #hg4jRevertsModifiedAddedRemovedAcrossCombo}: reverting a modified, an added
 *   (never committed), and a removed file all back to the working copy's parent -- exercising the
 *   {@code .orig} backup (only for the genuinely modified file), the untrack-without-delete
 *   behavior for the added file, and the restore-from-history behavior for the removed file.</li>
 *   <li>{@link #hg4jRevertsToOlderRevisionAcrossCombo}: reverting a clean file to an explicit
 *   older {@code -r} revision (no {@code .orig}, since nothing uncommitted was at risk), and
 *   reverting a file that was added after that older revision (deleted + marked removed, not
 *   merely untracked).</li>
 * </ul>
 */
@Tag("interop")
public class RequirementMatrixRevertCoreRoundTripTest {

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
    public void hg4jRevertsModifiedAddedRemovedAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "revert-mar");

        Files.writeString(repoDir.toPath().resolve("base.txt"), "base\n");
        Files.writeString(repoDir.toPath().resolve("keep.txt"), "keep\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");

        // Modify a tracked file (uncommitted).
        Files.writeString(repoDir.toPath().resolve("base.txt"), "modified locally\n");
        // Add a new file (never committed).
        Files.writeString(repoDir.toPath().resolve("new.txt"), "new content\n");
        HgTestUtils.hg(repoDir, "add", "new.txt");
        // Remove a tracked file (staged for removal, not yet committed).
        HgTestUtils.hg(repoDir, "remove", "keep.txt");

        HgRepository repo = new HgRepository(repoDir);

        assertTrue(new RevertCommand(repo).setFile("base.txt").call());
        assertTrue(new RevertCommand(repo).setFile("new.txt").call());
        assertTrue(new RevertCommand(repo).setFile("keep.txt").call());

        assertEquals("base", Files.readString(repoDir.toPath().resolve("base.txt")).trim(),
                "reverting a modified file must restore the parent's content for combo " + combo);
        assertEquals("keep", Files.readString(repoDir.toPath().resolve("keep.txt")).trim(),
                "reverting a removed file must restore its content for combo " + combo);
        assertTrue(new File(repoDir, "new.txt").exists(),
                "reverting an added-but-uncommitted file must NOT delete its on-disk content for combo " + combo);
        assertEquals("new content", Files.readString(repoDir.toPath().resolve("new.txt")).trim());

        // Real hg backs up the modified file's pre-revert content as base.txt.orig (verified
        // live against hg 7.2, 2026-09-05) -- but creates no backup for the added/removed files.
        assertTrue(new File(repoDir, "base.txt.orig").exists(),
                "a modified file being reverted must get an .orig backup for combo " + combo);
        assertEquals("modified locally", Files.readString(repoDir.toPath().resolve("base.txt.orig")).trim());

        String status = HgTestUtils.hg(repoDir, "status");
        assertEquals("? base.txt.orig\n? new.txt", status,
                "only the .orig backup and the now-untracked former-added file should remain outstanding for combo "
                        + combo + ": " + status);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jRevertsToOlderRevisionAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "revert-r");

        Files.writeString(repoDir.toPath().resolve("a.txt"), "v0\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");
        String c0Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        Files.writeString(repoDir.toPath().resolve("a.txt"), "v1\n");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1");

        Files.writeString(repoDir.toPath().resolve("later.txt"), "added later\n");
        HgTestUtils.hg(repoDir, "add", "later.txt");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c2");

        HgRepository repo = new HgRepository(repoDir);

        // a.txt is currently clean (matches c2/c1's own content) -- reverting it to the older c0
        // revision must NOT create an .orig backup (verified live: the backup protects
        // uncommitted work, not the delta between the working copy and the revert's target).
        assertTrue(new RevertCommand(repo).setFile("a.txt").setRevision(c0Hex).call());
        assertEquals("v0", Files.readString(repoDir.toPath().resolve("a.txt")).trim());
        assertEquals(false, new File(repoDir, "a.txt.orig").exists(),
                "reverting a clean file to an older revision must not create an .orig backup for combo " + combo);

        // later.txt didn't exist at c0 -- reverting it there deletes it AND marks it removed
        // (distinct from an added-but-never-committed file, which is only untracked).
        assertTrue(new RevertCommand(repo).setFile("later.txt").setRevision(c0Hex).call());
        assertEquals(false, new File(repoDir, "later.txt").exists());

        String status = HgTestUtils.hg(repoDir, "status");
        assertEquals("M a.txt\nR later.txt", status,
                "a.txt must show modified (reverted content differs from the current parent) and later.txt "
                        + "must show removed for combo " + combo + ": " + status);
    }
}
