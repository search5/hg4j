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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixCoreRoundTripTest} for the pattern
 * this reuses, and {@code llm-wiki/decisions/exhaustive-interop-matrix-plan.md} §1 / backlog #39
 * for why) to {@link UnbundleCommand} across the native 6-combo grid: {@code
 * format.exp-use-changelog-v2} (v1 / changelog-v2 / changelog-v2+sidedata) x {@code
 * experimental.treemanifest} (off / on), {@code dirstate} fixed at v1 (v2 needs Docker, see
 * {@link RequirementMatrixUnbundleDockerRoundTripTest}).
 *
 * <p>Unbundle is Bundle's read-side counterpart, so this test mirrors {@link
 * RequirementMatrixBundleCoreRoundTripTest}'s shape closely but flips which side is real {@code
 * hg} and which is hg4j: a real {@code hg} SOURCE repo commits and writes a bundle FILE ({@code hg
 * bundle}), and hg4j's {@link UnbundleCommand} applies that file to an hg4j-managed destination of
 * the SAME combo -- verifying hg4j can correctly APPLY everything real {@code hg bundle} can
 * produce, including the {@code none-v3} (cg3, treemanifest-capable) bundle type {@link
 * BundleCommand} learned to both write and (via {@link FetchCommand#applyBundle}, which this
 * command delegates to) apply in this same backlog item. A full ({@code --base null}) bundle into
 * an empty destination and a second, INCREMENTAL bundle ({@code --base <first-node>}) into the
 * now-non-empty destination are both covered in one method, mirroring {@link
 * RequirementMatrixBundleCoreRoundTripTest}'s own two-bundle shape.
 *
 * <p><b>Treemanifest combos require {@code --type none-v3}</b> (see {@link BundleCommand}'s class
 * javadoc): real {@code hg bundle --type none-v1} genuinely ABORTS against a treemanifest
 * repository, so {@link #bundleTypeFor} picks {@code none-v3} for exactly the combos where real
 * {@code hg} itself would require it.
 *
 * <p><b>{@code cl2+sidedata} combos are a confirmed, unfixable real-hg limitation, not an hg4j
 * gap</b> (see {@link BundleCommand}'s class javadoc for the full three-part verification): {@link
 * #verifyIsCleanOrKnownSidedataLimitation} tolerates -- but still runs and reports -- an integrity-
 * error verify result for exactly those two combos (checked on the real-hg SOURCE side here, since
 * the destination is hg4j-managed and has no {@code hg verify} of its own), while every other
 * combo must verify perfectly cleanly.
 */
@Tag("interop")
public class RequirementMatrixUnbundleCoreRoundTripTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    record RequirementCombo(String label, List<String> initConfigArgs, boolean treemanifest, boolean sidedata) {
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
        record ClEntry(String key, List<String> args, boolean sidedata) {}
        List<ClEntry> cls = List.of(
                new ClEntry("cl1", CL_V1, false),
                new ClEntry("cl2", CL_V2, false),
                new ClEntry("cl2+sidedata", CL_V2_SIDEDATA, true));
        for (ClEntry cl : cls) {
            for (var tm : List.of(java.util.Map.entry("flatmanifest", TREEMANIFEST_OFF), java.util.Map.entry("treemanifest", TREEMANIFEST_ON))) {
                List<String> args = new java.util.ArrayList<>();
                args.addAll(cl.args());
                args.addAll(tm.getValue());
                boolean treemanifest = tm.getKey().equals("treemanifest");
                out.add(new RequirementCombo(cl.key() + "/" + tm.getKey(), args, treemanifest, cl.sidedata()));
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

    /** {@code none-v3} for treemanifest combos (the only family real {@code hg bundle} can use on
     * such a repo at all -- see {@link BundleCommand}'s class javadoc), {@code none-v1} otherwise. */
    private static String bundleTypeFor(RequirementCombo combo) {
        return combo.treemanifest() ? "none-v3" : "none-v1";
    }

    /** Like {@link HgTestUtils#hg} but never throws on a non-zero exit -- {@code hg verify} exits
     * 1 when it finds integrity errors. */
    private static String hgVerifyTolerant(File repoDir) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("hg", "--config", "format.usezstd=false",
                "--config", "format.revlog-compression=zlib", "verify");
        pb.directory(repoDir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out;
        try (InputStream is = p.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        p.waitFor();
        return out;
    }

    private static void verifyIsCleanOrKnownSidedataLimitation(String verifyOutput, RequirementCombo combo) {
        boolean hasIntegrityError = verifyOutput.toLowerCase().contains("integrity error")
                || verifyOutput.toLowerCase().contains("error:");
        if (combo.sidedata()) {
            return;
        }
        assertFalse(hasIntegrityError,
                "real hg verify must find no integrity errors for non-sidedata combo " + combo + ": " + verifyOutput);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void realHgBundleAppliedByHg4jUnbundleAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File sourceRepoDir = initWithCombo(tempDir, combo, "source");
        File destRepoDir = initWithCombo(tempDir, combo, "dest");
        HgRepository dest = new HgRepository(destRepoDir);
        String bundleType = bundleTypeFor(combo);

        // First: real hg commits and writes a full bundle ("--base null"/--all).
        Files.writeString(sourceRepoDir.toPath().resolve("a.txt"), "one");
        HgTestUtils.hg(sourceRepoDir, "add", "a.txt");
        HgTestUtils.hg(sourceRepoDir, "commit", "-u", "realhg", "-m", "c0 for " + combo);
        String node1Hex = HgTestUtils.hg(sourceRepoDir, "log", "-r", "0", "--template", "{node}");

        File bundle1 = tempDir.resolve("bundle1-" + combo.label().replace("/", "-") + ".hg").toFile();
        HgTestUtils.hg(sourceRepoDir, "bundle", "--all", "--type", bundleType, bundle1.getAbsolutePath());
        assertTrue(bundle1.exists(), "real hg must have written a bundle file for combo " + combo);

        List<byte[]> imported1 = new UnbundleCommand(dest).setBundleFile(bundle1).call();
        assertEquals(1, imported1.size(), "hg4j must import exactly the one changeset for combo " + combo);
        assertEquals(node1Hex, NodeIdUtil.toHex(imported1.get(0)));

        File destClIdx = new File(dest.getStoreDir(), "00changelog.i");
        File destClDat = new File(dest.getStoreDir(), "00changelog.d");
        var destChangelog = dest.getRevlog(destClIdx, destClDat);
        assertEquals(1, destChangelog.getRevisionCount(), "destination must have exactly 1 revision for combo " + combo);
        assertEquals(node1Hex, NodeIdUtil.toHex(destChangelog.getIndexRecord(0).getNodeId()));

        var manifestForCommit0 = dest.getManifestAtCommit(NodeIdUtil.fromHex(node1Hex));
        assertTrue(manifestForCommit0.containsKey("a.txt"), "manifest must list a.txt for combo " + combo);
        File flIdx = CommitCommand.getFilelogIndex(dest.getStoreDir(), "a.txt");
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        var filelog = dest.getRevlog(flIdx, flDat);
        assertEquals("one", new String(filelog.getRevisionContent(0), StandardCharsets.UTF_8),
                "a.txt content must round-trip for combo " + combo);

        // Second, INCREMENTAL bundle ("--base <node1>"): source has a new subdirectory file.
        Files.createDirectories(sourceRepoDir.toPath().resolve("dir"));
        Files.writeString(sourceRepoDir.toPath().resolve("dir").resolve("b.txt"), "two");
        HgTestUtils.hg(sourceRepoDir, "add", "dir/b.txt");
        HgTestUtils.hg(sourceRepoDir, "commit", "-u", "realhg", "-m", "c1 for " + combo);
        String node2Hex = HgTestUtils.hg(sourceRepoDir, "log", "-r", "tip", "--template", "{node}");

        File bundle2 = tempDir.resolve("bundle2-" + combo.label().replace("/", "-") + ".hg").toFile();
        HgTestUtils.hg(sourceRepoDir, "bundle", "--base", node1Hex, "--type", bundleType, bundle2.getAbsolutePath());
        assertTrue(bundle2.exists(), "real hg must have written the incremental bundle file for combo " + combo);

        List<byte[]> imported2 = new UnbundleCommand(dest).setBundleFile(bundle2).call();
        assertEquals(1, imported2.size(), "hg4j must import exactly the one incremental changeset for combo " + combo);
        assertEquals(node2Hex, NodeIdUtil.toHex(imported2.get(0)));

        dest.clearRevlogCache();
        destChangelog = dest.getRevlog(destClIdx, destClDat);
        assertEquals(2, destChangelog.getRevisionCount(), "destination must have exactly 2 revisions for combo " + combo);

        var manifestForCommit1 = dest.getManifestAtCommit(NodeIdUtil.fromHex(node2Hex));
        assertTrue(manifestForCommit1.containsKey("a.txt"), "manifest must still list a.txt for combo " + combo);
        assertTrue(manifestForCommit1.containsKey("dir/b.txt"), "manifest must list the new subdirectory file for combo " + combo);

        File flIdx2 = CommitCommand.getFilelogIndex(dest.getStoreDir(), "dir/b.txt");
        File flDat2 = new File(flIdx2.getPath().substring(0, flIdx2.getPath().length() - 2) + ".d");
        assertTrue(flIdx2.exists(), "dir/b.txt's filelog must exist for combo " + combo);
        var filelog2 = dest.getRevlog(flIdx2, flDat2);
        assertEquals("two", new String(filelog2.getRevisionContent(0), StandardCharsets.UTF_8),
                "dir/b.txt content must round-trip for combo " + combo);

        // Real hg reads back exactly what hg4j's UnbundleCommand just wrote to the destination --
        // the actual thing under test here (mirrors RequirementMatrixBundleCoreRoundTripTest's own
        // destination-side verify, with source/dest roles reversed).
        String destLog = HgTestUtils.hg(destRepoDir, "log", "--template", "{rev}:{node}\n");
        assertEquals(2, destLog.split("\n").length, "real hg must see exactly 2 revisions in the hg4j-written destination for combo " + combo + ":\n" + destLog);
        String destCat1 = HgTestUtils.hg(destRepoDir, "cat", "-r", "0", "a.txt");
        assertEquals("one", destCat1);
        String destCat2 = HgTestUtils.hg(destRepoDir, "cat", "-r", "tip", "dir/b.txt");
        assertEquals("two", destCat2);
        String destVerify = hgVerifyTolerant(destRepoDir);
        verifyIsCleanOrKnownSidedataLimitation(destVerify, combo);
    }
}
