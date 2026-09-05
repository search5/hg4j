package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixBackoutCoreRoundTripTest} for the
 * reused pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to {@link
 * BisectCommand} across the native 6-combo grid (changelog family x treemanifest, dirstate fixed
 * at v1 -- v2 needs Docker, see {@code RequirementMatrixBisectDockerRoundTripTest}).
 *
 * <p>{@link BisectCommand}'s DAG bisection algorithm was already verified against real hg's own
 * {@code hg bisect} in {@code BisectRealHgInteropTest} (linear history + a merge-commit DAG), but
 * neither of those exercised the on-disk format matrix, and -- more importantly -- neither of them
 * had a file living inside a subdirectory, so they could never have caught the bug this test
 * suite's TDD found and fixed (backlog #39): {@link BisectCommand#next()}'s working-copy checkout
 * step used to hand-parse the ROOT manifest revlog directly and treat every one of its lines as a
 * real file, which is exactly wrong under {@code experimental.treemanifest=1} (root manifest
 * entries for subdirectories are "t"-flagged pointers into a nested {@code
 * meta/<dir>/00manifest.i} sub-manifest, not file content) -- fixed by switching to the same
 * treemanifest-aware {@link io.github.search5.hg4j.treewalk.ManifestWalk} {@link ManifestCommand}/
 * {@link StatusCommand}/{@link DiffCommand} already use. This test's history therefore always
 * includes a nested-directory file and asserts, after every bisect step, that hg4j's checkout of
 * it byte-for-byte matches {@code hg cat} for that exact candidate revision -- across every combo,
 * including {@code treemanifest}.
 */
@Tag("interop")
public class RequirementMatrixBisectCoreRoundTripTest {

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

    private static String hexAt(Revlog changelog, int rev) throws Exception {
        return NodeIdUtil.toHex(changelog.getIndexRecord(rev).getNodeId());
    }

    /**
     * A linear 8-revision history real hg writes (flag.txt flips to "1" starting at revision 5,
     * the true culprit) with a SECOND, always-changing file living inside {@code dir/} on every
     * revision -- hg4j's bisect walk must converge on the same culprit real hg's own {@code hg
     * bisect} does, following the identical candidate sequence, AND correctly restore the nested
     * file's exact content at every single checkout step along the way.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void bisectConvergesAndChecksOutNestedFilesAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "bisect");
        Path root = repoDir.toPath();
        Files.createDirectories(root.resolve("dir"));

        int totalRevs = 8;
        int culpritRev = 5;
        for (int i = 0; i < totalRevs; i++) {
            String flag = (i < culpritRev) ? "0" : "1";
            Files.writeString(root.resolve("flag.txt"), flag);
            Files.writeString(root.resolve("dir").resolve("n.txt"), "rev" + i);
            HgTestUtils.hg(repoDir, "add");
            HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "rev" + i);
        }

        HgRepository repo = new HgRepository(repoDir);
        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog changelog = repo.getRevlog(clIdx, clDat);
        assertEquals(totalRevs, changelog.getRevisionCount(), "combo " + combo);

        String goodHex = hexAt(changelog, 0);
        String badHex = hexAt(changelog, totalRevs - 1);
        String expectedCulpritHex = hexAt(changelog, culpritRev);

        byte[] good = NodeIdUtil.fromHex(goodHex);
        byte[] bad = NodeIdUtil.fromHex(badHex);

        List<Integer> hg4jCandidates = new ArrayList<>();
        int goodRev = 0;
        int badRev = totalRevs - 1;
        int guard = 0;
        while (badRev - goodRev > 1 && guard++ < totalRevs + 2) {
            byte[] candidateNode = new BisectCommand(repo).setGood(good).setBad(bad).next();
            int candidateRev = changelog.findRevision(candidateNode);
            hg4jCandidates.add(candidateRev);

            // The treemanifest-fix assertion: the nested file's checked-out content must exactly
            // match what real hg itself recorded for this exact candidate revision.
            String candidateHex = hexAt(changelog, candidateRev);
            String expectedNested = HgTestUtils.hg(repoDir, "cat", "-r", candidateHex, "dir/n.txt");
            String actualNested = Files.readString(root.resolve("dir").resolve("n.txt"));
            assertEquals(expectedNested, actualNested,
                    "nested treemanifest file must be correctly checked out at candidate rev " + candidateRev
                            + ", combo " + combo);

            String flagContent = Files.readString(root.resolve("flag.txt"));
            if ("0".equals(flagContent)) {
                good = candidateNode;
                goodRev = candidateRev;
            } else {
                bad = candidateNode;
                badRev = candidateRev;
            }
        }
        String hg4jCulpritHex = NodeIdUtil.toHex(bad);

        HgTestUtils.hg(repoDir, "bisect", "--reset");
        HgTestUtils.hg(repoDir, "bisect", "--good", goodHex);
        String out = HgTestUtils.hg(repoDir, "bisect", "--bad", badHex);
        List<Integer> nativeCandidates = new ArrayList<>();
        int nativeGuard = 0;
        while (!out.contains("The first bad revision is:") && !out.contains("The first bad changeset is:")
                && nativeGuard++ < totalRevs + 2) {
            Integer testingRev = parseTestingRev(out);
            if (testingRev != null) {
                nativeCandidates.add(testingRev);
            }
            String flagContent = Files.readString(root.resolve("flag.txt"));
            out = HgTestUtils.hg(repoDir, "bisect", "0".equals(flagContent) ? "--good" : "--bad");
        }

        assertEquals(expectedCulpritHex, hg4jCulpritHex,
                "hg4j's bisect walk must converge on the real culprit, combo " + combo);
        assertTrue(out.contains(expectedCulpritHex.substring(0, 12)) || out.contains(culpritRev + ":"),
                "real hg bisect must report the same culprit, combo " + combo + ": " + out);
        assertEquals(nativeCandidates, hg4jCandidates,
                "hg4j and real hg must pick the identical candidate sequence, combo " + combo);
    }

    private static Integer parseTestingRev(String out) {
        int idx = out.indexOf("Testing changeset ");
        if (idx == -1) return null;
        int start = idx + "Testing changeset ".length();
        int colon = out.indexOf(':', start);
        if (colon == -1) return null;
        return Integer.parseInt(out.substring(start, colon).trim());
    }
}
