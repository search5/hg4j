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
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixBackoutCoreRoundTripTest} for the
 * reused pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to {@link
 * SidedataChangedFilesCommand} across the native 6-combo grid (changelog family x treemanifest,
 * dirstate fixed at v1 -- v2 needs Docker, see {@code
 * RequirementMatrixSidedataChangedFilesDockerRoundTripTest}).
 *
 * <p>This command is inherently sidedata-specific (see its own class javadoc): only a repository
 * with the {@code exp-copies-sidedata-changeset} requirement (which itself requires
 * changelog-v2) ever writes the {@code SD_FILES} sidedata this command decodes -- on any other
 * combo it must return {@link ChangingFiles#empty()}, exactly matching real hg's own {@code hg
 * debugchangedfiles} behavior in that case (verified live, 2026-09-05: a plain/{@code cl2}-without-
 * sidedata repository's {@code hg debugchangedfiles N} prints nothing at all). This test therefore
 * exercises BOTH ends of the matrix deliberately: {@code cl1}/{@code cl2} combos assert the empty
 * result, and {@code cl2+sidedata} combos (flat and treemanifest, the latter with the copy
 * destination itself living in a nested directory) assert the real copy-tracing data, cross-checked
 * against real {@code hg debugchangedfiles}'s own output. Pure read -- no hg4j write step / {@code
 * HelperMain} subprocess needed.
 */
@Tag("interop")
public class RequirementMatrixSidedataChangedFilesCoreRoundTripTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    record RequirementCombo(String label, List<String> initConfigArgs, boolean sidedataExpected) {
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
        for (var cl : List.of(java.util.Map.entry("cl1", CL_V1), java.util.Map.entry("cl2", CL_V2),
                java.util.Map.entry("cl2+sidedata", CL_V2_SIDEDATA))) {
            for (var tm : List.of(java.util.Map.entry("flatmanifest", TREEMANIFEST_OFF), java.util.Map.entry("treemanifest", TREEMANIFEST_ON))) {
                List<String> args = new java.util.ArrayList<>();
                args.addAll(cl.getValue());
                args.addAll(tm.getValue());
                boolean sidedataExpected = cl.getKey().equals("cl2+sidedata");
                out.add(new RequirementCombo(cl.getKey() + "/" + tm.getKey(), args, sidedataExpected));
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
    public void sidedataChangedFilesAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "sdcf");
        Path root = repoDir.toPath();

        Files.createDirectories(root.resolve("dir"));
        Files.writeString(root.resolve("a.txt"), "a");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");

        // rev1: copy a.txt -> dir/b.txt (a nested treemanifest path when treemanifest is on), plus
        // a freshly added, non-copied file.
        HgTestUtils.hg(repoDir, "copy", "a.txt", "dir/b.txt");
        Files.writeString(root.resolve("c.txt"), "c");
        HgTestUtils.hg(repoDir, "add", "c.txt");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1");

        HgRepository repo = new HgRepository(repoDir);

        ChangingFiles rev0 = new SidedataChangedFilesCommand(repo).setRevision(0).call();
        ChangingFiles rev1 = new SidedataChangedFilesCommand(repo).setRevision(1).call();

        if (!combo.sidedataExpected()) {
            // Sanity: real hg itself reports nothing for `hg debugchangedfiles` without the
            // sidedata requirement (verified live, 2026-09-05).
            assertEquals("", HgTestUtils.hg(repoDir, "debugchangedfiles", "0"), "sanity, combo " + combo);
            assertEquals("", HgTestUtils.hg(repoDir, "debugchangedfiles", "1"), "sanity, combo " + combo);

            assertEquals(ChangingFiles.empty().getAdded(), rev0.getAdded(), "combo " + combo);
            assertTrue(rev0.getCopiedFromP1().isEmpty(), "combo " + combo);
            assertTrue(rev1.getCopiedFromP1().isEmpty(), "combo " + combo);
            assertNull(rev1.getCopySource("dir/b.txt"), "combo " + combo);
        } else {
            String realRev0 = HgTestUtils.hg(repoDir, "debugchangedfiles", "0");
            String realRev1 = HgTestUtils.hg(repoDir, "debugchangedfiles", "1");
            assertEquals("added      : a.txt, ;", realRev0, "sanity: real hg's own debugchangedfiles, combo " + combo);
            assertEquals("added      : c.txt, ;\nadded    p1: dir/b.txt, a.txt;", realRev1,
                    "sanity: real hg's own debugchangedfiles, combo " + combo);

            assertEquals(Set.of("a.txt"), rev0.getAdded(), "combo " + combo);
            assertTrue(rev0.getCopiedFromP1().isEmpty(), "combo " + combo);

            assertEquals(Set.of("dir/b.txt", "c.txt"), rev1.getAdded(), "combo " + combo);
            assertEquals("a.txt", rev1.getCopySource("dir/b.txt"), "combo " + combo);
            assertNull(rev1.getCopySource("c.txt"), "c.txt was freshly added, not copied, combo " + combo);
        }
    }
}
