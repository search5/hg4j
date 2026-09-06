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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixMergeCoreRoundTripTest} for the
 * reused pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to {@link
 * PurgeCommand} across the native 6-combo grid (changelog family x treemanifest, dirstate fixed at
 * v1 -- v2 needs Docker, see {@code RequirementMatrixPurgeDockerRoundTripTest}).
 *
 * <p>{@link PurgeCommand} never touches store/changelog/manifest data at all (only the working
 * directory) -- so unlike most other requirement-matrix suites in this package, no subprocess
 * helper is used; the corruption {@link RequirementMatrixCommitHelperMain} documents only affects
 * revlog writes interleaved with {@code docker exec}/{@code docker run} children, which never
 * happens here.
 *
 * <p>One comprehensive scenario, across every combo, verified live against real {@code hg} 7.2
 * (2026-09-05) before being ported: a tracked root file and a tracked nested-subdirectory file
 * (exercising treemanifest), alongside an untracked root file, an untracked non-empty subdirectory,
 * an untracked already-empty subdirectory, an ignored file, and a dangling (broken-target) symlink.
 * After {@link PurgeCommand#call()} (default settings -- see {@link PurgeCommand}'s own javadoc for
 * the two real bugs fixed here: the wrong {@code purgeDirectories} default, and the directory-
 * symlink-traversal data-loss bug), exactly the untracked+non-ignored files/dirs must be gone,
 * confirmed both directly and via real hg's own {@code hg status} seeing a clean, fully-tracked
 * working copy afterward.
 */
@Tag("interop")
public class RequirementMatrixPurgeCoreRoundTripTest {

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
    public void hg4jPurgeMatchesRealHgAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "purge");
        Path root = repoDir.toPath();

        // Tracked content (committed via real hg, so the on-disk store matches the combo's format).
        Files.writeString(root.resolve("tracked.txt"), "tracked\n");
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub/tracked-nested.txt"), "tracked nested\n");
        Files.writeString(root.resolve(".hgignore"), "^ignored\\.log$\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");

        // Untracked/ignored content created AFTER the commit -- purge's actual target set.
        Files.writeString(root.resolve("untracked.txt"), "junk\n");
        Files.createDirectories(root.resolve("junkdir"));
        Files.writeString(root.resolve("junkdir/junk2.txt"), "junk2\n");
        Files.createDirectories(root.resolve("emptydir"));
        Files.writeString(root.resolve("ignored.log"), "keep me\n");
        boolean symlinksSupported = true;
        try {
            Files.createSymbolicLink(root.resolve("broken-link"), root.resolve("does-not-exist"));
        } catch (UnsupportedOperationException | IOException e) {
            symlinksSupported = false;
        }

        HgRepository repo = new HgRepository(repoDir);
        new PurgeCommand(repo).call();

        assertTrue(Files.exists(root.resolve("tracked.txt")), "tracked root file must survive for combo " + combo);
        assertTrue(Files.exists(root.resolve("sub/tracked-nested.txt")), "tracked nested file must survive for combo " + combo);
        assertTrue(Files.exists(root.resolve("ignored.log")), "ignored file must survive for combo " + combo);
        assertTrue(Files.isDirectory(root.resolve("sub")), "directory still holding a tracked file must survive for combo " + combo);

        assertFalse(Files.exists(root.resolve("untracked.txt")), "untracked root file must be purged for combo " + combo);
        assertFalse(Files.exists(root.resolve("junkdir/junk2.txt")), "file inside untracked dir must be purged for combo " + combo);
        assertFalse(Files.exists(root.resolve("junkdir")), "untracked dir emptied by purge must itself be purged (default purgeDirectories) for combo " + combo);
        assertFalse(Files.exists(root.resolve("emptydir")), "pre-existing empty untracked dir must be purged by default for combo " + combo);
        if (symlinksSupported) {
            assertFalse(Files.exists(root.resolve("broken-link"), LinkOption.NOFOLLOW_LINKS),
                    "a dangling symlink must be purged, matching real hg for combo " + combo);
        }

        // Cross-check with real hg itself: nothing unexpected should remain unknown/missing.
        String status = HgTestUtils.hg(repoDir, "status");
        assertEquals("", status, "real hg status must see a fully clean working copy after hg4j's purge for combo " + combo + ": " + status);
    }
}
