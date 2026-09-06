package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.errors.HgValidationException;
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
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.Map;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixBackoutCoreRoundTripTest} for the
 * reused pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to {@link
 * CensorCommand} across the native 6-combo grid (changelog family x treemanifest, dirstate fixed
 * at v1 -- v2 needs Docker, see {@code RequirementMatrixCensorDockerRoundTripTest}).
 *
 * <p>Two independent scenarios are covered, both across every combo, both verified live against
 * real {@code hg} 7.2 (2026-09-05):
 * <ul>
 *   <li>{@link #hg4jCensorsAnOlderRevisionAndRealHgConfirmsAcrossCombo}: censoring an older, no-
 *   longer-live filelog revision (a later revision on the same path exists, so real hg's own
 *   check-heads guard would allow it too) -- real hg must then refuse to materialize that content
 *   (e.g. {@code hg cat}) and {@code hg verify} must recognize the {@code REVIDX_ISCENSORED} flag
 *   hg4j wrote, while the later, untouched revision must remain fully readable.</li>
 *   <li>{@link #hg4jRefusesToCensorAHeadRevisionMatchingRealHgAcrossCombo}: censoring the file's
 *   only (head) revision must be refused by hg4j's {@link CensorCommand} with the same
 *   {@code "cannot censor file in heads"} reasoning real hg's own {@code hgext.censor} enforces
 *   (confirmed live: real hg's own censor extension aborts identically on this exact repo shape)
 *   -- this is the real hg4j bug this wave found and fixed (see {@link CensorCommand}'s javadoc):
 *   before the fix hg4j had no such guard at all.</li>
 * </ul>
 */
@Tag("interop")
public class RequirementMatrixCensorCoreRoundTripTest {

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

    private static File filelogIndex(File repoDir, String path) {
        return CommitCommand.getFilelogIndex(new File(repoDir, ".hg/store"), path);
    }

    private static File filelogData(File flIdx) {
        return new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jCensorsAnOlderRevisionAndRealHgConfirmsAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "censor-older-rev");

        Files.writeString(repoDir.toPath().resolve("a.txt"), "secret1\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");
        Files.writeString(repoDir.toPath().resolve("a.txt"), "secret1\nsecret2\n");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1");

        File flIdx = filelogIndex(repoDir, "a.txt");
        File flDat = filelogData(flIdx);
        HgRepository repo = new HgRepository(repoDir);
        Revlog filelog = repo.getRevlog(flIdx, flDat);
        byte[] rev0Node = filelog.getIndexRecord(0).getNodeId().clone();

        new CensorCommand(repo).setFile("a.txt").setRevision(NodeIdUtil.toHex(rev0Node)).call();

        Throwable ex = assertThrows(Throwable.class, () ->
                HgTestUtils.hg(repoDir, "--config", "extensions.censor=", "cat", "-r", "0", "a.txt"));
        assertTrue(ex.getMessage().contains("censored node"),
                "real hg must refuse to read hg4j-censored content for combo " + combo + ": " + ex.getMessage());

        String verifyOut;
        try {
            verifyOut = HgTestUtils.hg(repoDir, "verify");
        } catch (Throwable verifyFailed) {
            verifyOut = verifyFailed.getMessage();
        }
        assertTrue(verifyOut.contains("censored file data"),
                "real hg verify must recognize hg4j's REVIDX_ISCENSORED flag for combo " + combo + ": " + verifyOut);

        assertEquals("secret1\nsecret2", HgTestUtils.hg(repoDir, "cat", "-r", "1", "a.txt"),
                "the untouched later revision must remain fully readable by real hg for combo " + combo);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jRefusesToCensorAHeadRevisionMatchingRealHgAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "censor-head-refused");

        Files.writeString(repoDir.toPath().resolve("a.txt"), "secret1\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");

        // real hg's own censor extension must itself refuse -- this is the only revision, so it
        // is both the repository's sole head and the working directory's parent.
        Throwable realEx = assertThrows(Throwable.class, () ->
                HgTestUtils.hg(repoDir, "--config", "extensions.censor=", "censor", "-r", "0", "a.txt"));
        assertTrue(realEx.getMessage().toLowerCase().contains("cannot censor"),
                "sanity: real hg's own censor extension must refuse for combo " + combo + ": " + realEx.getMessage());

        File flIdx = filelogIndex(repoDir, "a.txt");
        File flDat = filelogData(flIdx);
        HgRepository repo = new HgRepository(repoDir);
        Revlog filelog = repo.getRevlog(flIdx, flDat);
        byte[] rev0Node = filelog.getIndexRecord(0).getNodeId().clone();

        HgValidationException ex = assertThrows(HgValidationException.class, () ->
                new CensorCommand(repo).setFile("a.txt").setRevision(NodeIdUtil.toHex(rev0Node)).call(),
                "hg4j's CensorCommand must refuse to censor the sole head/working-directory-parent revision for combo " + combo);
        assertTrue(ex.getMessage().contains("cannot censor"), ex.getMessage());

        Revlog reread = repo.getRevlog(flIdx, flDat);
        assertFalse(reread.isCensored(0), "a refused censor attempt must leave the revision untouched for combo " + combo);
    }
}
