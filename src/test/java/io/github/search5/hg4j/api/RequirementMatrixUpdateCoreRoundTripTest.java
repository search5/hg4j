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
import java.nio.file.Files;
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
 * UpdateCommand} across the native 6-combo grid (changelog family x treemanifest, dirstate fixed
 * at v1 -- v2 needs Docker, see {@code RequirementMatrixUpdateDockerRoundTripTest}).
 *
 * <p>{@link UpdateCommand} never appends any revlog revision (only reads them, and writes the
 * working directory + dirstate) -- so unlike most other requirement-matrix suites in this package,
 * no subprocess helper is strictly required for correctness; {@link
 * RequirementMatrixUpdateHelperMain} is used purely for pattern parity.
 *
 * <p>One comprehensive round-trip scenario, across every combo, verified live against real {@code
 * hg} 7.2 (2026-09-05): two commits exercising every kind of per-file transition {@code hg update}
 * has to handle at once -- a modified root file, a newly added root file, a removed
 * nested-subdirectory file (exercising treemanifest's dirlog and the empty-parent-directory
 * cleanup), an executable bit flip, and a symlink whose target content changes underneath it.
 * hg4j's {@link UpdateCommand} checks out c0, then forward to c1 again, and each step's resulting
 * working-copy content/mode/symlink state and dirstate parent are checked directly, plus real hg's
 * own {@code hg status}/{@code hg log -r .} reading hg4j's result back for a clean, correctly
 * parented working copy.
 */
@Tag("interop")
public class RequirementMatrixUpdateCoreRoundTripTest {

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
    public void hg4jUpdateRoundTripMatchesRealHgAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "update");
        Path root = repoDir.toPath();

        Files.writeString(root.resolve("root.txt"), "v0\n");
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub/nested.txt"), "nested v0\n");
        Files.writeString(root.resolve("exec.sh"), "echo hi\n");
        new File(repoDir, "exec.sh").setExecutable(true, false);
        boolean symlinksSupported = true;
        try {
            Files.createSymbolicLink(root.resolve("link.txt"), Path.of("root.txt"));
        } catch (UnsupportedOperationException | IOException e) {
            symlinksSupported = false;
        }
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");
        String c0Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        Files.writeString(root.resolve("root.txt"), "v1\n");
        Files.writeString(root.resolve("newfile.txt"), "new\n");
        Files.delete(root.resolve("sub/nested.txt"));
        new File(repoDir, "exec.sh").setExecutable(false, false);
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "remove", "sub/nested.txt");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1");
        String c1Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgRepository repo = new HgRepository(repoDir);

        // Step 1: hg4j checks out c0 (backward from the real-hg-checked-out c1).
        new UpdateCommand(repo).setRevision(c0Hex).call();

        assertEquals("v0\n", Files.readString(root.resolve("root.txt")), "root.txt must revert to v0 for combo " + combo);
        assertTrue(Files.exists(root.resolve("sub/nested.txt")), "removed-in-c1 nested file must reappear at c0 for combo " + combo);
        assertEquals("nested v0\n", Files.readString(root.resolve("sub/nested.txt")));
        assertFalse(Files.exists(root.resolve("newfile.txt")), "added-in-c1 file must not exist at c0 for combo " + combo);
        assertTrue(Files.isExecutable(root.resolve("exec.sh")), "exec bit must be restored to executable at c0 for combo " + combo);
        if (symlinksSupported) {
            assertTrue(Files.isSymbolicLink(root.resolve("link.txt")), "symlink must remain a real symlink for combo " + combo);
            assertEquals("v0\n", Files.readString(root.resolve("link.txt")), "symlink must resolve to c0's content for combo " + combo);
        }
        assertEquals(c0Hex, NodeIdUtil.toHex(repo.getDirstate().getParent1()), "dirstate parent must be c0 for combo " + combo);
        assertEquals(c0Hex, HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}"),
                "real hg reading hg4j's dirstate must agree the working copy is at c0 for combo " + combo);
        assertEquals("", HgTestUtils.hg(repoDir, "status"), "working copy must be clean after checkout to c0 for combo " + combo);

        // Step 2: hg4j checks out c1 again (forward).
        new UpdateCommand(repo).setRevision(c1Hex).call();

        assertEquals("v1\n", Files.readString(root.resolve("root.txt")), "root.txt must advance to v1 for combo " + combo);
        assertEquals("new\n", Files.readString(root.resolve("newfile.txt")), "newfile.txt must reappear at c1 for combo " + combo);
        assertFalse(Files.exists(root.resolve("sub/nested.txt")), "nested.txt must be removed again at c1 for combo " + combo);
        assertFalse(Files.exists(root.resolve("sub")), "the now-empty sub directory must be cleaned up for combo " + combo);
        assertFalse(Files.isExecutable(root.resolve("exec.sh")), "exec bit must be cleared again at c1 for combo " + combo);
        if (symlinksSupported) {
            assertEquals("v1\n", Files.readString(root.resolve("link.txt")), "symlink must resolve to c1's content for combo " + combo);
        }
        assertEquals(c1Hex, NodeIdUtil.toHex(repo.getDirstate().getParent1()), "dirstate parent must be c1 for combo " + combo);
        assertEquals(c1Hex, HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}"),
                "real hg reading hg4j's dirstate must agree the working copy is at c1 for combo " + combo);
        assertEquals("", HgTestUtils.hg(repoDir, "status"), "working copy must be clean after checkout to c1 for combo " + combo);
    }
}
