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
import java.util.ArrayList;
import java.util.Map;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixCoreRoundTripTest} for the reused
 * pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to {@link
 * SubrepoCommand} across the native 6-combo grid (changelog family x treemanifest, dirstate fixed
 * at v1 -- v2 needs Docker, see {@code RequirementMatrixSubrepoDockerRoundTripTest}).
 *
 * <p>{@code SubrepoCommand} itself (add/init/update) mostly manipulates plain working-copy files
 * ({@code .hgsub}/{@code .hgsubstate}) and a nested subrepo's own (unparametrized) repository, so
 * this specifically targets the dimension the extensive {@code SubrepoRealHgInteropTest} does
 * NOT already cover: whether the PARENT repository's own revlog format (changelog v1/v2(+
 * sidedata), flat/treemanifest, and on Docker also dirstate v1/v2, persistent nodemap, fileindex
 * v1, general delta v2) affects committing/reading back {@code .hgsub}/{@code .hgsubstate} state
 * that {@link SubrepoCommand} produced.
 *
 * <p>Scenario: real hg builds a two-revision subrepo source (v1, v2); a combo-configured parent
 * repository is set up, {@link SubrepoCommand#setAction(String) "add"} declares the subrepo
 * pinned at v1, {@code "init"} clones+checks it out, hg4j's {@link CommitCommand} commits the
 * parent (auto-snapshotting {@code .hgsubstate} from the actual checkout, matching {@code
 * SubrepoRealHgInteropTest}'s already-verified behavior); {@code .hgsubstate} is then bumped to
 * v2 (simulating a pull/merge that advanced the pin without yet touching the subrepo's working
 * copy -- exactly {@code "update"}'s real job), {@code "update"} is run, and a second parent
 * commit is made -- real hg re-reads every step.
 */
@Tag("interop")
public class RequirementMatrixSubrepoCoreRoundTripTest {

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
    public void hg4jSubrepoAddInitUpdateAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        // A plain (unparametrized) real-hg subrepo source with two revisions.
        File subSourceDir = tempDir.resolve("sub-source-" + combo.label().replace("/", "-")).toFile();
        HgTestUtils.nativeRepo(subSourceDir, dir -> {
            try {
                Files.writeString(new File(dir, "hello.txt").toPath(), "v1");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        HgTestUtils.hg(subSourceDir, "add");
        HgTestUtils.hg(subSourceDir, "commit", "-u", "dev", "-m", "sub v1");
        String subV1 = HgTestUtils.hg(subSourceDir, "log", "-r", "tip", "--template", "{node}");

        Files.writeString(new File(subSourceDir, "hello.txt").toPath(), "v2");
        HgTestUtils.hg(subSourceDir, "commit", "-u", "dev", "-m", "sub v2");
        String subV2 = HgTestUtils.hg(subSourceDir, "log", "-r", "tip", "--template", "{node}");

        // Combo-configured parent repository.
        File parentDir = initWithCombo(tempDir, combo, "subrepo");
        Files.writeString(parentDir.toPath().resolve("init.txt"), "init\n");
        HgTestUtils.hg(parentDir, "add");
        HgTestUtils.hg(parentDir, "commit", "-u", "dev", "-m", "c0");

        HgRepository parentRepo = new HgRepository(parentDir);

        // "add": declares the subrepo, pinned at v1, in .hgsub/.hgsubstate.
        new SubrepoCommand(parentRepo)
                .setAction("add")
                .setSubrepoPath("sub")
                .setSubrepoUrl(subSourceDir.getAbsolutePath())
                .setRevision(subV1)
                .call();

        // "init": clones the subrepo and must check it out to the EXACT pinned revision (v1),
        // not merely the clone's own default tip -- the real bug found and fixed here: a plain
        // clone checks out its own tip, and previously SubrepoCommand only rewrote the subrepo's
        // dirstate parent pointer afterward without ever touching a single working-copy file, so
        // the working copy would have silently stayed at whatever the clone's default checkout
        // was (v2, since the source's tip is v2) while dirstate falsely claimed v1.
        new SubrepoCommand(parentRepo).setAction("init").call();

        File subInParent = new File(parentDir, "sub");
        assertEquals("v1", Files.readString(subInParent.toPath().resolve("hello.txt")),
                "init must check the subrepo out to the exact pinned revision (v1), not the clone's default tip, for combo "
                        + combo);
        HgRepository subInParentRepo = new HgRepository(subInParent);
        assertEquals(subV1, subInParentRepo.getDirstate().getParent1Node().toHex(),
                "subrepo dirstate must point at the pinned revision v1 for combo " + combo);

        // Commit the parent (hg4j) -- .hgsubstate is auto-snapshotted from the actual subrepo
        // checkout (already verified in detail by SubrepoRealHgInteropTest; here parametrized
        // across the parent's own revlog format).
        new AddCommand(parentRepo).call();
        byte[] parentC1 = new CommitCommand(parentRepo).setAuthor("dev").setMessage("add subrepo").call();
        String parentC1Hex = NodeIdUtil.toHex(parentC1);

        String verify1 = HgTestUtils.hg(parentDir, "verify");
        assertFalse(verify1.toLowerCase().contains("integrity error") || verify1.toLowerCase().contains("error:"),
                "real hg verify must find no integrity errors after the first subrepo commit for combo " + combo + ": " + verify1);
        assertEquals(subV1 + " sub", HgTestUtils.hg(parentDir, "cat", "-r", parentC1Hex, ".hgsubstate").trim(),
                "real hg must read back .hgsubstate pinned at v1 for combo " + combo);
        assertEquals("", HgTestUtils.hg(parentDir, "status"),
                "working copy must be clean right after the first subrepo commit for combo " + combo);

        // Bump the pin to v2 in .hgsubstate WITHOUT touching the subrepo's own working copy --
        // exactly the situation "update" exists to fix (e.g. after a pull/merge advanced the
        // pin at the parent level, subrepos must still catch up locally).
        Files.writeString(parentDir.toPath().resolve(".hgsubstate"), subV2 + " sub\n", StandardCharsets.UTF_8);
        assertEquals("v1", Files.readString(subInParent.toPath().resolve("hello.txt")),
                "sanity: the subrepo working copy must still be at v1 right after bumping the pin file alone");

        new SubrepoCommand(parentRepo).setAction("update").call();

        assertEquals("v2", Files.readString(subInParent.toPath().resolve("hello.txt")),
                "update must actually check the subrepo's working copy out to the newly pinned v2, for combo " + combo);
        HgRepository subInParentRepo2 = new HgRepository(subInParent);
        assertEquals(subV2, subInParentRepo2.getDirstate().getParent1Node().toHex(),
                "subrepo dirstate must point at the newly pinned revision v2 for combo " + combo);

        // Real hg, run directly against the (unparametrized) subrepo, must see a clean checkout
        // at exactly v2 -- confirms the working-copy content hg4j wrote is byte-correct, not just
        // hg4j-self-consistent.
        assertEquals("", HgTestUtils.hg(subInParent, "status"),
                "real hg must see the subrepo working copy as clean after update for combo " + combo);
        assertEquals(subV2, HgTestUtils.hg(subInParent, "log", "-r", ".", "--template", "{node}"),
                "real hg must see the subrepo checked out at exactly v2 after update for combo " + combo);

        // Finalize with a second parent commit (hg4j, under the combo's format) and let real hg
        // re-read the whole thing.
        byte[] parentC2 = new CommitCommand(parentRepo).setAuthor("dev").setMessage("bump sub to v2").call();
        String parentC2Hex = NodeIdUtil.toHex(parentC2);

        String verify2 = HgTestUtils.hg(parentDir, "verify");
        assertFalse(verify2.toLowerCase().contains("integrity error") || verify2.toLowerCase().contains("error:"),
                "real hg verify must find no integrity errors after the second subrepo commit for combo " + combo + ": " + verify2);
        assertEquals(subV2 + " sub", HgTestUtils.hg(parentDir, "cat", "-r", parentC2Hex, ".hgsubstate").trim(),
                "real hg must read back .hgsubstate pinned at v2 for combo " + combo);
        assertEquals("", HgTestUtils.hg(parentDir, "status"),
                "working copy must be clean right after the second subrepo commit for combo " + combo);
        assertEquals(parentC1Hex, HgTestUtils.hg(parentDir, "log", "-r", parentC2Hex, "--template", "{p1node}"),
                "the second commit's parent must be the first for combo " + combo);
    }
}
