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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "requirement matrix" real-hg interop: instead of testing each on-disk format toggle in
 * isolation (as the existing per-feature {@code *RealHgInteropTest} files already do), this
 * cross-multiplies the toggles that real hg's <em>native, pure-Python</em> {@code hg} 7.2.2 build
 * on this host can actually create via {@code hg init --config ...} (no Docker/Rust extension
 * needed, confirmed empirically 2026-09-04 -- see below) and runs the same core read/write round
 * trip through every valid combination.
 *
 * <p><b>Why only 2 axes (3x2 = 6 combinations, NOT 3 axes / 12)</b>: the original design
 * (decisions/exhaustive-interop-matrix-plan.md's first draft) assumed {@code
 * format.use-dirstate-v2} was native-buildable alongside {@code format.exp-use-changelog-v2} and
 * {@code experimental.treemanifest} -- this turned out to be WRONG, caught by this very test
 * (2026-09-04): {@code hg init --config format.use-dirstate-v2=yes} on this host's native
 * pure-Python build aborts with "accessing `dirstate-v2` repository without associated fast
 * implementation", the exact same failure mode as general-v2/fileindex-v1/persistent-nodemap.
 * dirstate-v2 has therefore been moved out of this native-only file into the Docker-backed
 * requirement matrix (see {@code RequirementMatrixDockerRoundTripTest}) alongside those three --
 * the matrix plan doc's "native 12 / Docker 24" split needs a matching correction (native 6,
 * Docker 30). What IS genuinely native-buildable and covered here: {@code
 * format.exp-use-changelog-v2} (which {@code format.exp-use-copies-side-data-changeset} implies
 * automatically) x {@code experimental.treemanifest}, 3x2 = 6 combinations.
 * {@code narrowhg-experimental} is excluded for an unrelated reason: it is a per-clone attribute
 * (only appears via {@code hg clone --narrow}), not an {@code hg init}-time requirement, so it
 * does not compose with the other axes the same way.
 */
@Tag("interop")
public class RequirementMatrixCoreRoundTripTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    /** One point in the 3(changelog family) x 2(treemanifest) grid (dirstate-v2 moved to the
     * Docker-backed matrix, see the class javadoc). */
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

    /**
     * Real hg writes two commits under this combination; hg4j must read the exact same log/status/
     * file content back, matching the "real hg writes, hg4j reads" half of every existing
     * per-feature interop test.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void realHgWritesHg4jReadsAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "read");

        Files.writeString(repoDir.toPath().resolve("a.txt"), "one");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");
        Files.createDirectories(repoDir.toPath().resolve("dir"));
        Files.writeString(repoDir.toPath().resolve("dir").resolve("b.txt"), "two");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1");

        String realTipHex = HgTestUtils.hg(repoDir, "log", "-r", "tip", "--template", "{node}");

        HgRepository repo = new HgRepository(repoDir);
        List<HgCommit> log = new LogCommand(repo).call();
        assertEquals(2, log.size(), "hg4j must see both real-hg commits for combo " + combo);
        assertEquals(realTipHex, log.get(0).getNodeId().toHex(),
                "hg4j's tip must match real hg's tip hex for combo " + combo);

        byte[] aContent = new CatCommand(repo).setFile("a.txt").setRevision("tip").call();
        assertEquals("one", new String(aContent, StandardCharsets.UTF_8));
        byte[] bContent = new CatCommand(repo).setFile("dir/b.txt").setRevision("tip").call();
        assertEquals("two", new String(bContent, StandardCharsets.UTF_8));
    }

    /**
     * hg4j writes a commit into a real-hg-initialized repo of this combination; real hg must read
     * it back identically and {@code hg verify} must find no integrity errors (known, separately
     * tracked non-inline/fncache *warning*, backlog 35, is tolerated the same way every other
     * *RealHgInteropTest in this suite already tolerates it -- only "integrity error"/"error:" fail
     * this assertion, matching {@code CommitRealHgInteropTest}'s existing convention).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jWritesRealHgReadsAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "write");

        Files.writeString(repoDir.toPath().resolve("seed.txt"), "seed");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "seed");

        HgRepository repo = new HgRepository(repoDir);
        Files.writeString(repoDir.toPath().resolve("hg4j.txt"), "from hg4j");
        new AddCommand(repo).call();
        byte[] node = new CommitCommand(repo).setAuthor("hg4j").setMessage("hg4j commit for " + combo).call();
        String hg4jHex = NodeIdUtil.toHex(node);

        String realTipHex = HgTestUtils.hg(repoDir, "log", "-r", "tip", "--template", "{node}");
        assertEquals(hg4jHex, realTipHex, "real hg's tip must be the hg4j-written commit for combo " + combo);

        String catOut = HgTestUtils.hg(repoDir, "cat", "-r", "tip", "hg4j.txt");
        assertEquals("from hg4j", catOut);

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.toLowerCase().contains("integrity error") || verify.toLowerCase().contains("error:"),
                "real hg verify must find no integrity errors for combo " + combo + ": " + verify);

        Status status = new StatusCommand(repo).call();
        assertTrue(status.getAdded().isEmpty() && status.getModified().isEmpty(),
                "hg4j's own status must be clean immediately after its own commit for combo " + combo);
    }
}
