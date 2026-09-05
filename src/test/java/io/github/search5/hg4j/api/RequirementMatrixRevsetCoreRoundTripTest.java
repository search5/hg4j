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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixBackoutCoreRoundTripTest} for the
 * reused pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to {@link
 * RevsetCommand} across the native 6-combo grid (changelog family x treemanifest, dirstate fixed
 * at v1 -- v2 needs Docker, see {@code RequirementMatrixRevsetDockerRoundTripTest}).
 *
 * <p>{@link RevsetCommand}/{@link io.github.search5.hg4j.revset.HgRevsetEngine} implement a
 * hg-revset-INSPIRED query DSL over the changelog, not a byte-identical reimplementation of real
 * hg's full revset grammar (e.g. real hg's {@code heads()} requires an argument -- {@code
 * heads(<setexpr>)} -- while hg4j's {@code heads()} is a bare zero-arg "heads of the whole DAG"
 * shorthand; this test uses real hg's equivalent {@code heads(all())} when cross-checking that
 * one expression). Every other expression exercised here (a numeric revision, a full hex node,
 * {@code all()}, {@code author()}, {@code branch()}, {@code parents()}, {@code ancestors()}, {@code
 * descendants()}, {@code tag()}, {@code and}/{@code or}/{@code not}) uses identical syntax and
 * semantics in both engines, verified against real hg 7.2 CLI output as sets (revset result
 * ordering is not part of {@link RevsetCommand}'s documented contract). Pure read -- no hg4j write
 * step / {@code HelperMain} subprocess needed.
 */
@Tag("interop")
public class RequirementMatrixRevsetCoreRoundTripTest {

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

    private static Set<String> realHexes(File repoDir, String expr) throws Exception {
        String out = HgTestUtils.hg(repoDir, "log", "-r", expr, "--template", "{node}\n");
        Set<String> result = new HashSet<>();
        for (String line : out.split("\n")) {
            if (!line.isBlank()) {
                result.add(line);
            }
        }
        return result;
    }

    private static Set<String> hg4jHexes(HgRepository repo, String expr) throws Exception {
        return new HashSet<>(new RevsetCommand(repo).setExpression(expr).call());
    }

    /**
     * Four commits forming a real, non-linear DAG (two heads, two branches, one tag) real hg
     * writes under each combo -- exercises every revset function {@link RevsetCommand} shares
     * identical syntax with real hg for.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void revsetExpressionsAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "revset");
        Path root = repoDir.toPath();

        Files.writeString(root.resolve("a.txt"), "a");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "Alice <a@x.com>", "-m", "c0");
        String hex0 = HgTestUtils.hg(repoDir, "log", "-r", "0", "--template", "{node}");

        HgTestUtils.hg(repoDir, "branch", "feature");
        Files.writeString(root.resolve("b.txt"), "b");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "Bob <b@x.com>", "-m", "c1");
        String hex1 = HgTestUtils.hg(repoDir, "log", "-r", "1", "--template", "{node}");

        HgTestUtils.hg(repoDir, "update", "0");
        HgTestUtils.hg(repoDir, "branch", "default");
        Files.writeString(root.resolve("c.txt"), "c");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "Alice <a@x.com>", "-m", "c2");
        String hex2 = HgTestUtils.hg(repoDir, "log", "-r", "2", "--template", "{node}");

        Files.writeString(root.resolve(".hgtags"), hex0 + " v1.0\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "Alice <a@x.com>", "-m", "c3 tag");
        String hex3 = HgTestUtils.hg(repoDir, "log", "-r", "3", "--template", "{node}");

        HgRepository repo = new HgRepository(repoDir);

        record Case(String label, String hg4jExpr, String realHgExpr) {
        }
        List<Case> cases = List.of(
                new Case("all()", "all()", "all()"),
                new Case("author(Alice)", "author(Alice)", "author(Alice)"),
                new Case("author(Bob)", "author(Bob)", "author(Bob)"),
                new Case("branch(feature)", "branch(feature)", "branch(feature)"),
                new Case("branch(default)", "branch(default)", "branch(default)"),
                new Case("parents(1)", "parents(1)", "parents(1)"),
                new Case("ancestors(2)", "ancestors(2)", "ancestors(2)"),
                new Case("descendants(0)", "descendants(0)", "descendants(0)"),
                new Case("tag(v1.0)", "tag(v1.0)", "tag(v1.0)"),
                new Case("0 and author(Alice)", "0 and author(Alice)", "0 and author(Alice)"),
                new Case("author(Alice) or author(Bob)", "author(Alice) or author(Bob)", "author(Alice) or author(Bob)"),
                new Case("not author(Bob)", "not author(Bob)", "not author(Bob)"),
                new Case("bare revision 2", "2", "2"),
                new Case("full hex of rev1", hex1, hex1),
                // hg4j's bare heads() == real hg's heads(all()) -- see class javadoc.
                new Case("heads()", "heads()", "heads(all())")
        );

        for (Case c : cases) {
            Set<String> expected = realHexes(repoDir, c.realHgExpr());
            Set<String> actual = hg4jHexes(repo, c.hg4jExpr());
            assertEquals(expected, actual, "revset '" + c.label() + "', combo " + combo);
        }

        // Sanity: the DAG really is what this test assumes (two heads, not a straight line).
        assertEquals(Set.of(hex1, hex3), realHexes(repoDir, "heads(all())"), "sanity, combo " + combo);
    }
}
