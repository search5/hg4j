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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixCoreRoundTripTest}, backlog #39 /
 * {@code exhaustive-interop-matrix-plan.md} §1) to {@link StripCommand} across the native 6-combo
 * grid (changelog family x treemanifest, dirstate fixed at v1 -- v2 needs Docker, see
 * {@code RequirementMatrixStripDockerRoundTripTest}).
 *
 * <p>Scenario mirrors {@code StripRealHgInteropTest#stripWithUnevenRevisionSizesLeavesVerifiableRepo}
 * (deliberately uneven revision sizes to stress the approximate {@code .d} truncate-offset
 * estimate {@link StripCommand#truncateRevlog} uses), parametrized across every combo instead of
 * just the host's default flat/revlogv1 format -- treemanifest and changelog-v2 both change what
 * "one revision" looks like on disk (multiple revlogs touched per commit, or a different
 * changelog encoding), which is exactly the kind of interaction the plain-format-only original
 * test could never exercise.
 */
@Tag("interop")
public class RequirementMatrixStripCoreRoundTripTest {

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
    public void hg4jStripAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "strip");
        HgRepository repo = new HgRepository(repoDir);

        File f = new File(repoDir, "a.txt");
        // rev0: small
        Files.writeString(f.toPath(), "x".repeat(50));
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("hg4j").setMessage("rev0-small").call();

        // rev1: large (forces the following revision's .d offset well past the naive "half" estimate)
        Files.writeString(f.toPath(), "y".repeat(50_000));
        new CommitCommand(repo).setAuthor("hg4j").setMessage("rev1-large").call();

        // rev2: small again -- this is the revision that must survive stripping rev3, byte-exact.
        Files.writeString(f.toPath(), "z".repeat(60));
        byte[] rev2Node = new CommitCommand(repo).setAuthor("hg4j").setMessage("rev2-small-to-keep").call();
        String rev2Hex = NodeIdUtil.toHex(rev2Node);

        // rev3: the one we strip
        Files.writeString(f.toPath(), "w".repeat(70));
        new CommitCommand(repo).setAuthor("hg4j").setMessage("rev3-to-strip").call();

        new StripCommand(repo).setRevision("3").call();

        String verifyOut = HgTestUtils.hg(repoDir, "verify");
        assertTrue(verifyOut.contains("0 integrity errors") || verifyOut.toLowerCase().contains("checked"),
                "real hg verify must report a clean repository after strip for combo " + combo + ", got: " + verifyOut);

        String log = HgTestUtils.hg(repoDir, "log", "--template", "{rev}:{node}\n");
        List<String> lines = List.of(log.split("\n"));
        assertEquals(3, lines.size(), "3 revisions must remain after stripping rev3 for combo " + combo + "; got:\n" + log);

        String catOut = HgTestUtils.hg(repoDir, "cat", "-r", rev2Hex, "a.txt");
        assertEquals("z".repeat(60), catOut, "surviving rev2's content must be byte-exact after strip for combo " + combo);
    }
}
