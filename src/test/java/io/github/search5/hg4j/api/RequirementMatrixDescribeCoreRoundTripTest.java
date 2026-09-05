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

/**
 * Extends the requirement matrix (see {@link RequirementMatrixBackoutCoreRoundTripTest} for the
 * reused pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to {@link
 * DescribeCommand} across the native 6-combo grid (changelog family x treemanifest, dirstate fixed
 * at v1 -- v2 needs Docker, see {@code RequirementMatrixDescribeDockerRoundTripTest}).
 *
 * <p>{@link DescribeCommand} is a pure read (changelog + {@code .hgtags} + dirstate only, never the
 * manifest) with no real-hg CLI equivalent to compare against directly ({@code hg describe} does
 * not exist -- this command mimics {@code git describe}'s tag-relative naming convention using
 * real hg's own tag storage) so every assertion here is a self-consistency check of the documented
 * algorithm (see {@link DescribeCommand#call()}'s javadoc and {@code DescribeCommandTest}) applied
 * to a repository real {@code hg} 7.2 wrote under each of the 6 native combos, rather than a
 * byte-for-byte comparison against a real-hg-computed string. No hg4j write step is needed (and
 * therefore no {@code HelperMain} subprocess): {@link DescribeCommand} never mutates the
 * repository.
 */
@Tag("interop")
public class RequirementMatrixDescribeCoreRoundTripTest {

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

    /**
     * Exercises all three of {@link DescribeCommand}'s documented branches on top of a single
     * real-hg-authored linear history: (1) no tag reachable yet -> {@code v0.0-<distance>-g<hex>}
     * fallback, (2) the working copy's parent IS the exact tagged revision -> the bare tag name,
     * (3) an ancestor tag with distance -> {@code <tag>-<distance>-g<hex>}.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void describeAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "describe");

        Files.writeString(repoDir.toPath().resolve("f.txt"), "v1");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");
        String hex0 = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgRepository repo = new HgRepository(repoDir);

        // 1. No tag anywhere yet: v0.0 fallback, distance 1 (the single existing revision itself).
        assertEquals("v0.0-1-g" + hex0.substring(0, 12), new DescribeCommand(repo).call(),
                "v0.0 fallback before any tag exists, combo " + combo);

        // 2. Tag hex0 as v1.0 directly in the working copy WITHOUT committing -- the dirstate
        // parent (hex0) now exactly matches the tag target.
        Files.writeString(repoDir.toPath().resolve(".hgtags"), hex0 + " v1.0\n");
        assertEquals("v1.0", new DescribeCommand(repo).call(),
                "exact tag match on the current working-copy parent, combo " + combo);

        // 3. Commit that tag file for real (real hg, not hg4j) alongside one more content change
        // -- current is now one revision past the tagged ancestor.
        Files.writeString(repoDir.toPath().resolve("f.txt"), "v2");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1");
        String hex1 = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        assertEquals("v1.0-1-g" + hex1.substring(0, 12), new DescribeCommand(repo).call(),
                "ancestor tag with distance 1, combo " + combo);
    }
}
