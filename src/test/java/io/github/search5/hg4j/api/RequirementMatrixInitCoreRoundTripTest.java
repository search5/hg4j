package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixBackoutCoreRoundTripTest} for the
 * reused pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to {@link
 * InitCommand} itself -- the foundational case every other command in this campaign depends on:
 * can hg4j <em>create</em> (not just read/write into an already-real-hg-created) a repository of
 * every combo, such that real hg fully accepts it (verify/log/cat), and can even append further
 * history of its own on top?
 *
 * <p>Unlike every other {@code RequirementMatrix*CoreRoundTripTest}, this one does NOT use real hg
 * to {@code init} the scratch repo -- that would defeat the point. hg4j's {@link InitCommand} does
 * the {@code init}, hg4j's {@link AddCommand}/{@link CommitCommand} do the first commit (a nested
 * file, to also exercise treemanifest's directory split), and only then does real hg get pointed at
 * the result.
 *
 * <p>Covers the native 6-combo grid (changelog family x treemanifest, dirstate fixed at v1 -- v2
 * needs Docker, see {@code RequirementMatrixInitDockerRoundTripTest}).
 */
@Tag("interop")
public class RequirementMatrixInitCoreRoundTripTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    record RequirementCombo(String label, boolean changelogV2, boolean sidedataCopies, boolean treemanifest,
                             List<String> expectedRequires) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static List<String> baseRequires(boolean treemanifest) {
        List<String> r = new ArrayList<>(List.of("dotencode", "fncache", "generaldelta", "revlogv1", "store"));
        if (treemanifest) {
            r.add("treemanifest");
        }
        return r;
    }

    static Stream<RequirementCombo> combos() {
        List<RequirementCombo> out = new ArrayList<>();
        record Cl(String label, boolean cl2, boolean sidedata) {
        }
        List<Cl> changelogs = List.of(new Cl("cl1", false, false), new Cl("cl2", true, false),
                new Cl("cl2+sidedata", true, true));
        for (Cl cl : changelogs) {
            for (var tm : List.of(Map.entry("flatmanifest", false), Map.entry("treemanifest", true))) {
                List<String> expected = baseRequires(tm.getValue());
                if (cl.cl2()) {
                    expected.add("exp-changelog-v2");
                }
                if (cl.sidedata()) {
                    expected.add("exp-copies-sidedata-changeset");
                }
                out.add(new RequirementCombo(cl.label() + "/" + tm.getKey(), cl.cl2(), cl.sidedata(), tm.getValue(), expected));
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jInitAndFirstCommitAcceptedByRealHgAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo-" + combo.label().replace("/", "-").replace("+", "_")).toFile();

        HgRepository repo = new InitCommand()
                .setDirectory(repoDir)
                .setChangelogV2(combo.changelogV2())
                .setSidedataCopies(combo.sidedataCopies())
                .setTreemanifest(combo.treemanifest())
                .call();

        // Assert the requires content BEFORE handing anything to real hg -- this is the actual
        // load-bearing claim of this test class (InitCommand writes the right tokens per combo).
        File requiresFile = new File(repoDir, ".hg/requires");
        List<String> actualRequires = Files.readAllLines(requiresFile.toPath());
        assertEquals(new TreeSet<>(combo.expectedRequires()), new TreeSet<>(actualRequires),
                "InitCommand must write exactly real hg's own requirement tokens for combo " + combo);

        Files.createDirectories(repoDir.toPath().resolve("sub"));
        Files.writeString(repoDir.toPath().resolve("sub/a.txt"), "original\n");
        new AddCommand(repo).addFile("sub/a.txt").call();
        byte[] c0Node = new CommitCommand(repo).setAuthor("hg4j").setMessage("c0").call();
        String c0Hex = NodeIdUtil.toHex(c0Node);

        // Real hg must accept the freshly hg4j-created+committed repository outright.
        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.toLowerCase().contains("integrity error") || verify.toLowerCase().contains("error:"),
                "real hg verify must find no integrity errors in an hg4j-created repo for combo " + combo + ": " + verify);

        assertEquals(c0Hex, HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}"),
                "real hg must see hg4j's own commit node for combo " + combo);
        assertEquals("original", HgTestUtils.hg(repoDir, "cat", "-r", c0Hex, "sub/a.txt").trim());

        String realRequires = HgTestUtils.hg(repoDir, "debugrequires");
        List<String> realRequiresLines = new ArrayList<>();
        for (String line : realRequires.split("\n")) {
            if (!line.isBlank()) {
                realRequiresLines.add(line.trim());
            }
        }
        assertEquals(new TreeSet<>(combo.expectedRequires()), new TreeSet<>(realRequiresLines),
                "real hg's own debugrequires must agree with what InitCommand wrote for combo " + combo);

        // Real hg must also be able to WRITE further history on top of the hg4j-created store, not
        // merely read it -- the real bar for "structurally acceptable", not just "parseable".
        Files.writeString(repoDir.toPath().resolve("sub/a.txt"), "changed by real hg\n");
        HgTestUtils.hg(repoDir, "commit", "-u", "realhg", "-m", "c1");
        String c1Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        String verify2 = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify2.toLowerCase().contains("integrity error") || verify2.toLowerCase().contains("error:"),
                "real hg verify must still find no integrity errors after its own commit on top for combo " + combo);

        // hg4j reading back real hg's own commit on top of an hg4j-created store closes the loop.
        HgRepository reopened = new HgRepository(repoDir);
        byte[] c1NodeBytes = NodeIdUtil.fromHex(c1Hex);
        byte[] catContent = new CatCommand(reopened).setFile("sub/a.txt").setRevision(NodeIdUtil.toHex(c1NodeBytes)).call();
        assertEquals("changed by real hg\n", new String(catContent, StandardCharsets.UTF_8));

        assertTrue(reopened.isTreemanifest() == combo.treemanifest(),
                "hg4j reopening its own init'd repo must see the same treemanifest flag for combo " + combo);
        assertTrue(reopened.isChangelogV2() == combo.changelogV2(),
                "hg4j reopening its own init'd repo must see the same changelog-v2 flag for combo " + combo);
        assertTrue(reopened.isSidedataCopies() == combo.sidedataCopies(),
                "hg4j reopening its own init'd repo must see the same sidedata-copies flag for combo " + combo);
    }
}
