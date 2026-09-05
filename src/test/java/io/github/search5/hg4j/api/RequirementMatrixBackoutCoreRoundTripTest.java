package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.errors.HgMergeConflictException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
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

/**
 * Extends the requirement matrix (see {@link RequirementMatrixMergeCoreRoundTripTest} for the
 * reused pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to {@link
 * BackoutCommand} across the native 6-combo grid (changelog family x treemanifest, dirstate fixed
 * at v1 -- v2 needs Docker, see {@code RequirementMatrixBackoutDockerRoundTripTest}).
 *
 * <p>Two independent scenarios are covered, both across every combo, both verified live against
 * real {@code hg} 7.2 (2026-09-05) before being ported (see {@link BackoutCommand}'s own javadoc
 * for the full behavioral writeup):
 * <ul>
 *   <li>{@link #hg4jCleanTipBackoutAcrossCombo}: backing out the working copy's own parent (the
 *   common, single-file-diff case) -- can never conflict, and real hg commits it immediately with
 *   its default {@code "Backed out changeset <hex>"} message.</li>
 *   <li>{@link #hg4jConflictingOlderAncestorBackoutAcrossCombo}: backing out an older ancestor
 *   whose own effect was independently touched again by later history on the exact same line --
 *   real hg performs a genuine 3-way merge here and conflicts, leaving {@code hg resolve --list}
 *   showing the file unresolved and creating <em>no</em> changeset (verified live: real hg does
 *   NOT remember any default commit message across a paused/conflicted backout -- the user must
 *   resolve and commit manually with their own message, exactly like this test does via hg4j's
 *   {@link ResolveCommand} + a plain {@link CommitCommand}).</li>
 * </ul>
 */
@Tag("interop")
public class RequirementMatrixBackoutCoreRoundTripTest {

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
    public void hg4jCleanTipBackoutAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "backout-clean");

        Files.writeString(repoDir.toPath().resolve("a.txt"), "original\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");
        String c0Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        Files.writeString(repoDir.toPath().resolve("a.txt"), "changed\n");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1");
        String c1Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgRepository repo = new HgRepository(repoDir);
        byte[] backoutNode = new BackoutCommand(repo).setRevision(c1Hex).setAuthor("dev").call();
        String backoutHex = NodeIdUtil.toHex(backoutNode);

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.toLowerCase().contains("integrity error") || verify.toLowerCase().contains("error:"),
                "real hg verify must find no integrity errors after backout+commit for combo " + combo + ": " + verify);

        String parents = HgTestUtils.hg(repoDir, "log", "-r", backoutHex, "--template", "{p1node} {p2node}");
        assertEquals(c1Hex + " " + "0".repeat(40), parents,
                "the backout commit must be a single-parent child of the backed-out revision for combo " + combo);

        assertEquals("original", HgTestUtils.hg(repoDir, "cat", "-r", backoutHex, "a.txt").trim());

        String desc = HgTestUtils.hg(repoDir, "log", "-r", backoutHex, "--template", "{desc}");
        assertEquals("Backed out changeset " + c1Hex.substring(0, 12), desc,
                "real hg's own default backout commit message for combo " + combo);

        assertEquals("", HgTestUtils.hg(repoDir, "status"),
                "working copy must be clean right after the backout commit for combo " + combo);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jConflictingOlderAncestorBackoutAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "backout-conflict");

        Files.writeString(repoDir.toPath().resolve("conflict.txt"), "line1\nline2\nline3\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");

        // c1 will be the revision we back out.
        Files.writeString(repoDir.toPath().resolve("conflict.txt"), "line1\nTARGET\nline3\n");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1");
        String c1Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        // c2 is a later, independent edit of the exact same line -- must conflict with c1's own
        // reverse-diff when c1 is backed out from c2 (verified live against real hg 7.2).
        Files.writeString(repoDir.toPath().resolve("conflict.txt"), "line1\nAFTER\nline3\n");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c2");
        String c2Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgRepository repo = new HgRepository(repoDir);
        HgMergeConflictException ex = org.junit.jupiter.api.Assertions.assertThrows(HgMergeConflictException.class,
                () -> new BackoutCommand(repo).setRevision(c1Hex).setAuthor("dev").call(),
                "backing out c1 from c2 (both touching the same line) must conflict for combo " + combo);
        assertEquals(List.of("conflict.txt"), ex.getConflictPaths());

        String workingContent = Files.readString(repoDir.toPath().resolve("conflict.txt"));
        assertTrue(workingContent.contains("<<<<<<<") && workingContent.contains("=======") && workingContent.contains(">>>>>>>"),
                "the conflicted file must carry real conflict markers for combo " + combo + ": " + workingContent);

        String resolveListBefore = HgTestUtils.hg(repoDir, "resolve", "--list");
        assertEquals("U conflict.txt", resolveListBefore,
                "real hg's own `hg resolve --list` must see conflict.txt as unresolved for combo " + combo);

        // No changeset must have been created by the failed attempt.
        String parentsBeforeCommit = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");
        assertEquals(c2Hex, parentsBeforeCommit,
                "a conflicted backout must not create any changeset for combo " + combo);

        // Resolve (real hg has no `backout --continue` -- the user resolves and commits manually,
        // with their own message, verified live) via hg4j's ResolveCommand, then commit directly.
        Files.writeString(repoDir.toPath().resolve("conflict.txt"), "line1\nRESOLVED\nline3\n");
        new ResolveCommand(repo).setFile("conflict.txt").markResolved(true).call();

        String resolveListAfter = HgTestUtils.hg(repoDir, "resolve", "--list");
        assertEquals("R conflict.txt", resolveListAfter,
                "real hg must see conflict.txt as resolved once hg4j's ResolveCommand marks it for combo " + combo);

        byte[] finalNode = new CommitCommand(repo).setAuthor("dev").setMessage("manual backout resolution").call();
        String finalHex = NodeIdUtil.toHex(finalNode);

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.toLowerCase().contains("integrity error") || verify.toLowerCase().contains("error:"),
                "real hg verify must find no integrity errors for combo " + combo + ": " + verify);

        String parents = HgTestUtils.hg(repoDir, "log", "-r", finalHex, "--template", "{p1node} {p2node}");
        assertEquals(c2Hex + " " + "0".repeat(40), parents,
                "the resolved backout commit must remain a single-parent child of c2 for combo " + combo);

        assertEquals("line1\nRESOLVED\nline3", HgTestUtils.hg(repoDir, "cat", "-r", finalHex, "conflict.txt").trim());

        assertFalse(new File(repoDir, ".hg/merge").exists(),
                "real hg CLI reading hg4j's result must see .hg/merge fully cleaned up after the commit for combo "
                        + combo);
        assertEquals("", HgTestUtils.hg(repoDir, "resolve", "--list"),
                "no unresolved/resolved entries should remain once the backout is committed for combo " + combo);
        assertEquals("", HgTestUtils.hg(repoDir, "status"),
                "working copy must be clean right after the backout commit for combo " + combo);
    }
}
