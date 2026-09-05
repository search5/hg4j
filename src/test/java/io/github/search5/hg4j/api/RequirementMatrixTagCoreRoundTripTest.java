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
 * Extends the requirement matrix (see {@link RequirementMatrixCoreRoundTripTest}, backlog #39 /
 * {@code exhaustive-interop-matrix-plan.md} §1) to {@link TagCommand} across the native 6-combo
 * grid (changelog family x treemanifest, dirstate fixed at v1 -- v2 needs Docker, see
 * {@code RequirementMatrixTagDockerRoundTripTest}).
 *
 * <p>Wave 3 (2026-09-05): {@link TagCommand} already has thorough real-hg-CLI behavioural
 * coverage from an earlier backlog item ({@link TagRealHgInteropTest} -- create/move/remove/local
 * tags, the backlog #36 force gate, tagging a merge commit), but every one of those scenarios runs
 * against a single, default-format repository -- none of it varies changelog version, manifest
 * shape, or dirstate version. This class deliberately does NOT re-derive those same behavioural
 * assertions; it focuses purely on the one dimension {@link TagRealHgInteropTest} never covered:
 * does {@link TagCommand}'s own write (append a line to the tracked {@code .hgtags} file, add it,
 * then delegate the actual commit to {@link CommitCommand}) survive intact across every
 * changelog-v1/v2(+sidedata) x flat/tree-manifest combination, including retagging (a second
 * append) and removal (a nullid append) each committed on top of the last.
 */
@Tag("interop")
public class RequirementMatrixTagCoreRoundTripTest {

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
    public void hg4jTagAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "tag");

        Files.writeString(repoDir.toPath().resolve("base.txt"), "base\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0 base");
        String rev0Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgRepository repo = new HgRepository(repoDir);

        // 1. Create a global tag -- writes .hgtags, adds it, commits via CommitCommand.
        new TagCommand(repo).setTagName("v1.0").setNodeId(NodeIdUtil.fromHex(rev0Hex)).call();

        String tags1 = HgTestUtils.hg(repoDir, "tags");
        assertTrue(tags1.contains("v1.0") && tags1.contains(rev0Hex.substring(0, 12)),
                "real hg tags for combo " + combo + ": " + tags1);
        String tagCommitMsg = HgTestUtils.hg(repoDir, "log", "-r", "tip", "--template", "{desc}");
        assertEquals("Added tag v1.0 for changeset " + rev0Hex.substring(0, 12), tagCommitMsg);
        String hgtagsAtTip = HgTestUtils.hg(repoDir, "cat", "-r", "tip", ".hgtags");
        assertEquals(rev0Hex + " v1.0", hgtagsAtTip.trim());

        String verify1 = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify1.toLowerCase().contains("integrity error"),
                "real hg verify after tag creation must find no integrity errors for combo " + combo + ": " + verify1);

        // 2. A local tag must be written to .hg/localtags (untracked, no commit) even under this combo.
        int logCountBeforeLocal = HgTestUtils.hg(repoDir, "log", "--template", "{rev}\n").split("\n").length;
        new TagCommand(repo).setTagName("loc").setNodeId(NodeIdUtil.fromHex(rev0Hex)).setLocal(true).call();
        int logCountAfterLocal = HgTestUtils.hg(repoDir, "log", "--template", "{rev}\n").split("\n").length;
        assertEquals(logCountBeforeLocal, logCountAfterLocal, "a local tag must not create a commit for combo " + combo);
        assertTrue(new File(repo.getHgDir(), "localtags").exists());
        String tags2 = HgTestUtils.hg(repoDir, "tags");
        assertTrue(tags2.contains("loc"), "real hg tags for combo " + combo + ": " + tags2);

        // 3. Move (retag) v1.0 onto a second commit -- requires force, matching backlog #36's gate.
        Files.writeString(repoDir.toPath().resolve("b.txt"), "two\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1");
        String rev1Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        // Real hg just wrote a new commit directly to disk with a bare CLI process -- exactly
        // like every other test in this suite, get a FRESH HgRepository/Revlog handle for the
        // next hg4j command rather than reusing the one from before that external write (matches
        // how every real invocation of an hg4j porcelain command works: one fresh HgRepository
        // per command, never a long-lived handle kept across an out-of-band mutation -- that
        // latter case is what HgRepository#refreshIfChangedOnDisk() exists for, and is opt-in for
        // long-lived callers like the wire servers, not automatic).
        repo = new HgRepository(repoDir);
        new TagCommand(repo).setTagName("v1.0").setNodeId(NodeIdUtil.fromHex(rev1Hex)).setForce(true).call();
        String tags3 = HgTestUtils.hg(repoDir, "tags");
        assertTrue(tags3.contains(rev1Hex.substring(0, 12)), "real hg tags must resolve v1.0 to the new target for combo " + combo + ": " + tags3);
        String hgtagsContent = Files.readString(repoDir.toPath().resolve(".hgtags"), StandardCharsets.UTF_8);
        assertEquals(2, hgtagsContent.lines().filter(l -> l.endsWith(" v1.0")).count(),
                "moving a tag must append a new line, keeping the old one for combo " + combo);

        String verify3 = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify3.toLowerCase().contains("integrity error"),
                "real hg verify after retag must find no integrity errors for combo " + combo + ": " + verify3);

        // 4. Remove the tag -- appends a nullid line and commits "Removed tag v1.0".
        new TagCommand(repo).setTagName("v1.0").setRemove(true).call();
        String tags4 = HgTestUtils.hg(repoDir, "tags");
        assertFalse(tags4.lines().anyMatch(l -> l.trim().startsWith("v1.0")),
                "real hg tags must no longer list removed v1.0 for combo " + combo + ": " + tags4);
        String removeCommitMsg = HgTestUtils.hg(repoDir, "log", "-r", "tip", "--template", "{desc}");
        assertEquals("Removed tag v1.0", removeCommitMsg);

        String verify4 = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify4.toLowerCase().contains("integrity error"),
                "real hg verify after tag removal must find no integrity errors for combo " + combo + ": " + verify4);
    }
}
