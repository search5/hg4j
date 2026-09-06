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
import java.util.ArrayList;
import java.util.Map;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixBackoutCoreRoundTripTest} for the
 * reused pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to {@link
 * LogCommand} across the native 6-combo grid (changelog family x treemanifest, dirstate fixed at
 * v1 -- v2 needs Docker, see {@code RequirementMatrixLogDockerRoundTripTest}).
 *
 * <p>{@link LogCommand} was already read indirectly by {@code RequirementMatrixCoreRoundTripTest}
 * (tip-hex + commit-count sanity check), and its {@code --follow}/copy-tracing behavior was
 * verified against real hg in backlog 27's CLI-level tests -- but neither of those exercised the
 * full matrix of on-disk format combinations against {@link LogCommand}'s own dedicated contract
 * (ordering, per-field content, and {@code --follow} crossing a rename boundary). This is a pure
 * read (changelog + a renamed file's filelog metadata only, never the manifest) so no hg4j write
 * step / {@code HelperMain} subprocess is needed.
 */
@Tag("interop")
public class RequirementMatrixLogCoreRoundTripTest {

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

    /**
     * Basic ordering/content: real hg writes three commits (one touching a nested treemanifest
     * path) and hg4j's plain {@link LogCommand#call()} (no follow) must report all three, newest
     * first, with the exact node/author/message/branch real hg itself recorded.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void plainLogAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "log");

        Files.writeString(repoDir.toPath().resolve("a.txt"), "one");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");
        String hex0 = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        Files.createDirectories(repoDir.toPath().resolve("dir"));
        Files.writeString(repoDir.toPath().resolve("dir").resolve("b.txt"), "two");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev2", "-m", "c1 nested");
        String hex1 = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "branch", "feature");
        Files.writeString(repoDir.toPath().resolve("a.txt"), "three");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c2 branch");
        String hex2 = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgRepository repo = new HgRepository(repoDir);
        List<HgCommit> log = new LogCommand(repo).call();

        assertEquals(3, log.size(), "combo " + combo);
        assertEquals(hex2, log.get(0).getNodeId().toHex(), "newest first, combo " + combo);
        assertEquals(hex1, log.get(1).getNodeId().toHex(), "combo " + combo);
        assertEquals(hex0, log.get(2).getNodeId().toHex(), "combo " + combo);

        assertEquals("c2 branch", log.get(0).getMessage(), "combo " + combo);
        assertEquals("dev", log.get(0).getAuthor(), "combo " + combo);
        assertEquals("feature", log.get(0).getBranch(), "combo " + combo);

        assertEquals("c1 nested", log.get(1).getMessage(), "combo " + combo);
        assertEquals("dev2", log.get(1).getAuthor(), "combo " + combo);
        assertEquals("default", log.get(1).getBranch(), "combo " + combo);

        assertEquals("c0", log.get(2).getMessage(), "combo " + combo);
        assertEquals("default", log.get(2).getBranch(), "combo " + combo);
    }

    /**
     * {@code hg log --follow <path>} crossing a rename boundary: real hg renames {@code a.txt} to
     * {@code b.txt} then modifies it once more -- hg4j's {@link LogCommand#setFollowPath} must
     * report exactly the same revision set real {@code hg log --follow b.txt} does (all three
     * commits, including the pre-rename one), across every combo.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void followPathAcrossRenameBoundaryAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "follow");

        Files.writeString(repoDir.toPath().resolve("a.txt"), "one");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");
        String hex0 = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "rename", "a.txt", "b.txt");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1-rename");
        String hex1 = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        Files.writeString(repoDir.toPath().resolve("b.txt"), "one\ntwo");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c2-modify");
        String hex2 = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        // Add one unrelated, later commit real hg's own --follow must NOT include.
        Files.writeString(repoDir.toPath().resolve("unrelated.txt"), "x");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c3-unrelated");

        String realFollow = HgTestUtils.hg(repoDir, "log", "--follow", "b.txt", "--template", "{node}\n");
        List<String> realFollowHexes = List.of(realFollow.split("\n"));
        assertEquals(List.of(hex2, hex1, hex0), realFollowHexes, "sanity: real hg's own --follow set, combo " + combo);

        HgRepository repo = new HgRepository(repoDir);
        List<HgCommit> log = new LogCommand(repo).setFollowPath("b.txt").call();
        List<String> hg4jFollowHexes = log.stream().map(c -> c.getNodeId().toHex()).toList();

        assertEquals(realFollowHexes, hg4jFollowHexes,
                "hg4j's --follow must match real hg's revision set exactly, combo " + combo);
    }
}
