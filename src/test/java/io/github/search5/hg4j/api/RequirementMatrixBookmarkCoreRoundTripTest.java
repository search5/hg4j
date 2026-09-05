package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.errors.HgValidationException;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixCoreRoundTripTest}, backlog #39 /
 * {@code exhaustive-interop-matrix-plan.md} §1) to {@link BookmarkCommand} across the native
 * 6-combo grid (changelog family x treemanifest, dirstate fixed at v1 -- v2 needs Docker, see
 * {@code RequirementMatrixBookmarkDockerRoundTripTest}).
 *
 * <p>Wave 3 (2026-09-05): {@link BookmarkCommand} writes a plain-text {@code .hg/bookmarks}/
 * {@code .hg/bookmarks.current} pair, format-independent of changelog/manifest/dirstate version --
 * the value of running it through this matrix is mostly to confirm real hg (built with whatever
 * combo of storage features) parses hg4j's bookmark files identically to its own regardless of
 * combo, and to exercise the active-bookmark auto-advance-on-commit interaction with the rest of
 * the format-varying pipeline. This test also caught and fixed two real hg4j bugs while writing
 * it (see {@code mercurial-spec-compliance-requirement.md} item 39's wave-3 writeup): (1)
 * {@link BookmarkCommand} had no equivalent of {@link TagCommand}'s backlog-#36 force gate --
 * moving an existing bookmark to a non-descendant revision silently succeeded instead of requiring
 * {@code -f} like real hg 7.2 does; (2) deleting the last remaining bookmark deleted
 * {@code .hg/bookmarks} outright instead of leaving it behind as an empty file the way real hg
 * does.
 */
@Tag("interop")
public class RequirementMatrixBookmarkCoreRoundTripTest {

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
    public void hg4jBookmarkAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "bookmark");

        Files.writeString(repoDir.toPath().resolve("base.txt"), "base\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0 base");
        String rev0Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgRepository repo = new HgRepository(repoDir);

        // 1. Implicit target (no -r) -> becomes the active bookmark, points at the wdir parent.
        new BookmarkCommand(repo).setBookmarkName("cur").call();
        String bm1 = HgTestUtils.hg(repoDir, "bookmarks");
        assertTrue(bm1.contains("* cur"), "cur must be active for combo " + combo + ": " + bm1);
        assertEquals(rev0Hex, HgTestUtils.hg(repoDir, "log", "-r", "cur", "--template", "{node}"));

        // 2. A real-hg commit on top must auto-advance the active bookmark (real hg's own
        // behaviour, exercised here only to confirm real hg genuinely recognizes hg4j's file as
        // "active", not something hg4j itself does).
        Files.writeString(repoDir.toPath().resolve("b.txt"), "two\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1");
        String rev1Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");
        assertEquals(rev1Hex, HgTestUtils.hg(repoDir, "log", "-r", "cur", "--template", "{node}"),
                "real hg's own commit must auto-advance the hg4j-created active bookmark for combo " + combo);

        // Real hg just wrote a new commit directly to disk -- get a fresh HgRepository/Revlog
        // handle for the rest of this scenario rather than reusing the one from before that
        // external write (matches how every real invocation of an hg4j command works: one fresh
        // HgRepository per command; a long-lived handle kept across an out-of-band mutation is
        // what HgRepository#refreshIfChangedOnDisk() is for, and that's opt-in).
        repo = new HgRepository(repoDir);

        // 3. Explicit target (-r) -> NOT active, even though bookmarks.current already holds "cur".
        new BookmarkCommand(repo).setBookmarkName("stable").setRevision(rev0Hex).call();
        String bm3 = HgTestUtils.hg(repoDir, "bookmarks");
        assertFalse(bm3.lines().anyMatch(l -> l.trim().startsWith("* stable")),
                "an explicitly-targeted new bookmark must not become active for combo " + combo + ": " + bm3);
        assertEquals(rev0Hex, HgTestUtils.hg(repoDir, "log", "-r", "stable", "--template", "{node}"));
        assertEquals("cur", new BookmarkCommand(repo).getActiveBookmark());

        // 4. Fast-forward move (rev0 -> rev1, a descendant) must succeed WITHOUT -f/force.
        new BookmarkCommand(repo).setBookmarkName("stable").setRevision(rev1Hex).call();
        assertEquals(rev1Hex, HgTestUtils.hg(repoDir, "log", "-r", "stable", "--template", "{node}"),
                "fast-forward bookmark move must not require force for combo " + combo);

        // 5. Backward move (rev1 -> rev0, NOT a descendant) without force must be rejected, byte
        // for byte matching real hg's own abort message (verified against the CLI, 2026-09-05).
        final HgRepository repoForLambda = repo;
        HgValidationException ex = assertThrows(HgValidationException.class,
                () -> new BookmarkCommand(repoForLambda).setBookmarkName("stable").setRevision(rev0Hex).call());
        assertEquals("bookmark 'stable' already exists (use -f to force)", ex.getMessage());
        assertEquals(rev1Hex, HgTestUtils.hg(repoDir, "log", "-r", "stable", "--template", "{node}"),
                "a rejected move must not have touched the bookmark for combo " + combo);

        // Cross-check: real hg's own CLI must reject the identical backward move with the same
        // message (same pattern as TagRealHgInteropTest's force-gate parity check).
        String nativeAbort = "";
        try {
            HgTestUtils.hg(repoDir, "bookmark", "-r", rev0Hex, "stable");
            fail("real hg must also abort a backward bookmark move without -f for combo " + combo);
        } catch (AssertionError expected) {
            nativeAbort = expected.getMessage();
        }
        assertTrue(nativeAbort.contains("bookmark 'stable' already exists (use -f to force)"),
                "real hg abort message for combo " + combo + ": " + nativeAbort);

        // 6. With force, the backward move must succeed.
        new BookmarkCommand(repo).setBookmarkName("stable").setRevision(rev0Hex).setForce(true).call();
        assertEquals(rev0Hex, HgTestUtils.hg(repoDir, "log", "-r", "stable", "--template", "{node}"),
                "forced backward move must succeed for combo " + combo);

        // 7. Deleting the active bookmark must clear bookmarks.current, and real hg must agree
        // it is gone (but real hg must still see the OTHER bookmark, "stable").
        new BookmarkCommand(repo).setDelete(true).setBookmarkName("cur").call();
        assertNull(new BookmarkCommand(repo).getActiveBookmark(), "active bookmark must be cleared for combo " + combo);
        String bm7 = HgTestUtils.hg(repoDir, "bookmarks");
        assertFalse(bm7.lines().anyMatch(l -> l.contains("cur")), "real hg bookmarks for combo " + combo + ": " + bm7);
        assertTrue(bm7.lines().anyMatch(l -> l.contains("stable")), "real hg bookmarks for combo " + combo + ": " + bm7);

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.toLowerCase().contains("integrity error"),
                "real hg verify must find no integrity errors for combo " + combo + ": " + verify);
    }
}
