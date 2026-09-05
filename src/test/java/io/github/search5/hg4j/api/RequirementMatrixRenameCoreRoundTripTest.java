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

/**
 * Extends the requirement matrix (see {@link RequirementMatrixCoreRoundTripTest}, backlog #39 /
 * {@code exhaustive-interop-matrix-plan.md} §1) to {@link RenameCommand} across the native
 * 6-combo grid (changelog family x treemanifest, dirstate fixed at v1 -- v2 needs Docker, see
 * {@code RequirementMatrixRenameDockerRoundTripTest}).
 *
 * <p>Wave 4 (2026-09-05): {@link RenameCommand} is {@link CopyCommand}'s sibling -- same new 'a'
 * destination entry and copyMap record, plus marking the source 'r' (removed) instead of leaving
 * it untouched. This scenario renames one root-level file to another root-level path and a second
 * root-level file into a nested directory (multi-root-segment shape, matching {@link
 * RequirementMatrixAddCoreRoundTripTest}'s rationale for the Docker dirstate-v2 half of this
 * pair), then commits and verifies real hg sees the rename as a copy-with-source-removed
 * (`{file_copies}`, `hg log --follow`) and that the source path is truly gone from the resulting
 * manifest.
 */
@Tag("interop")
public class RequirementMatrixRenameCoreRoundTripTest {

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
    public void hg4jRenameAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "rename");

        Files.writeString(repoDir.toPath().resolve("old1.txt"), "content one\n");
        Files.writeString(repoDir.toPath().resolve("old2.txt"), "content two\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0 base");

        HgRepository repo = new HgRepository(repoDir);
        Files.createDirectories(repoDir.toPath().resolve("adir"));
        new RenameCommand(repo).setSource("old1.txt").setTarget("new1.txt").call();
        new RenameCommand(repo).setSource("old2.txt").setTarget("adir/new2.txt").call();

        String status = HgTestUtils.hg(repoDir, "status", "-C");
        assertTrue(status.lines().anyMatch(l -> l.equals("A new1.txt")),
                "real hg status must see new1.txt as added for combo " + combo + ": " + status);
        assertTrue(status.lines().anyMatch(l -> l.equals("  old1.txt")),
                "real hg status -C must record new1.txt's source as old1.txt for combo " + combo + ": " + status);
        assertTrue(status.lines().anyMatch(l -> l.equals("A adir/new2.txt")),
                "real hg status must see adir/new2.txt as added for combo " + combo + ": " + status);
        assertTrue(status.lines().anyMatch(l -> l.equals("R old1.txt")),
                "real hg status must see old1.txt as removed for combo " + combo + ": " + status);
        assertTrue(status.lines().anyMatch(l -> l.equals("R old2.txt")),
                "real hg status must see old2.txt as removed for combo " + combo + ": " + status);

        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1 rename");

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.toLowerCase().contains("integrity error"),
                "real hg verify must find no integrity errors after rename+commit for combo " + combo + ": " + verify);

        String manifest = HgTestUtils.hg(repoDir, "manifest", "-r", "tip");
        assertFalse(manifest.lines().anyMatch(l -> l.equals("old1.txt")), "manifest must not contain old1.txt: " + manifest);
        assertFalse(manifest.lines().anyMatch(l -> l.equals("old2.txt")), "manifest must not contain old2.txt: " + manifest);
        assertTrue(manifest.lines().anyMatch(l -> l.equals("new1.txt")), "manifest: " + manifest);
        assertTrue(manifest.lines().anyMatch(l -> l.equals("adir/new2.txt")), "manifest: " + manifest);

        String fileCopies = HgTestUtils.hg(repoDir, "log", "-r", "tip", "--template", "{file_copies}\n");
        assertTrue(fileCopies.contains("new1.txt (old1.txt)"),
                "changeset file_copies must record new1.txt <- old1.txt for combo " + combo + ": " + fileCopies);
        assertTrue(fileCopies.contains("adir/new2.txt (old2.txt)"),
                "changeset file_copies must record adir/new2.txt <- old2.txt for combo " + combo + ": " + fileCopies);

        String cleanStatus = HgTestUtils.hg(repoDir, "status", "-C");
        assertTrue(cleanStatus.isEmpty(), "working copy must be clean after commit for combo " + combo + ": " + cleanStatus);

        String followTop = HgTestUtils.hg(repoDir, "log", "--follow", "-r", "tip", "new1.txt", "--template", "{rev} ");
        assertTrue(followTop.contains("0"), "log --follow must reach c0 through new1.txt for combo " + combo + ": " + followTop);
        String followNested = HgTestUtils.hg(repoDir, "log", "--follow", "-r", "tip", "adir/new2.txt", "--template", "{rev} ");
        assertTrue(followNested.contains("0"), "log --follow must reach c0 through adir/new2.txt for combo " + combo + ": " + followNested);

        assertEquals("content one", HgTestUtils.hg(repoDir, "cat", "-r", "tip", "new1.txt").trim());
        assertEquals("content two", HgTestUtils.hg(repoDir, "cat", "-r", "tip", "adir/new2.txt").trim());
    }
}
