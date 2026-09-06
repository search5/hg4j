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

/**
 * Extends the requirement matrix (see {@link RequirementMatrixCoreRoundTripTest}, backlog #39 /
 * {@code exhaustive-interop-matrix-plan.md} §1) to {@link AddCommand} across the native 6-combo
 * grid (changelog family x treemanifest, dirstate fixed at v1 -- v2 needs Docker, see
 * {@code RequirementMatrixAddDockerRoundTripTest}).
 *
 * <p>Wave 3 (2026-09-05): {@link AddCommand} only writes the working-copy dirstate (no revlog
 * writes of its own), so the scenario below exercises hg4j writing new 'a'(dded) dirstate entries
 * -- one at the repository root and one nested a directory deep, alongside an already-committed
 * root-level file -- so the dirstate ends up with 3 distinct root-level path segments. That is
 * exactly the shape backlog #37's dirstate-v2 tree-corruption bug (root/child node ordering) needs
 * more than one sibling to reproduce, so the Docker half of this pair re-uses the same file layout
 * against the {@code dirstate-v2} combos where that bug was originally found (and already fixed).
 */
@Tag("interop")
public class RequirementMatrixAddCoreRoundTripTest {

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
    public void hg4jAddAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "add");

        Files.writeString(repoDir.toPath().resolve("base.txt"), "base\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0 base");

        Files.writeString(repoDir.toPath().resolve("top.txt"), "top content\n");
        Files.createDirectories(repoDir.toPath().resolve("adir"));
        Files.writeString(repoDir.toPath().resolve("adir/nested.txt"), "nested content\n");

        HgRepository repo = new HgRepository(repoDir);
        new AddCommand(repo).addFile("top.txt").addFile("adir/nested.txt").call();

        String status = HgTestUtils.hg(repoDir, "status");
        assertTrue(status.lines().anyMatch(l -> l.equals("A top.txt")),
                "real hg status must see top.txt as added for combo " + combo + ": " + status);
        assertTrue(status.lines().anyMatch(l -> l.equals("A adir/nested.txt")),
                "real hg status must see adir/nested.txt as added for combo " + combo + ": " + status);

        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1 add");

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.toLowerCase().contains("integrity error"),
                "real hg verify must find no integrity errors after add+commit for combo " + combo + ": " + verify);

        String manifest = HgTestUtils.hg(repoDir, "manifest", "-r", "tip");
        assertTrue(manifest.lines().anyMatch(l -> l.equals("top.txt")), "manifest: " + manifest);
        assertTrue(manifest.lines().anyMatch(l -> l.equals("adir/nested.txt")), "manifest: " + manifest);
        assertTrue(manifest.lines().anyMatch(l -> l.equals("base.txt")), "manifest: " + manifest);

        String topContent = HgTestUtils.hg(repoDir, "cat", "-r", "tip", "top.txt");
        assertEquals("top content", topContent.trim());
        String nestedContent = HgTestUtils.hg(repoDir, "cat", "-r", "tip", "adir/nested.txt");
        assertEquals("nested content", nestedContent.trim());
    }
}
