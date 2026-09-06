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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.ArrayList;
import java.util.Map;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixCoreRoundTripTest}, backlog #39 /
 * {@code exhaustive-interop-matrix-plan.md} §1) to {@link ForgetCommand} across the native
 * 6-combo grid (changelog family x treemanifest, dirstate fixed at v1 -- v2 needs Docker, see
 * {@code RequirementMatrixForgetDockerRoundTripTest}).
 *
 * <p>Wave 4 (2026-09-05): {@link ForgetCommand} on an already-committed file writes a dirstate
 * 'r' (removed) entry without touching the working copy file. The scenario below then re-tracks
 * the SAME path with {@link AddCommand} (as {@code hg forget} followed by an explicit {@code hg
 * add} on the same path would be used to do) and verifies real hg's own filelog-history
 * continuity requirement: re-adding a path that still carries a dirstate entry (rather than a
 * genuinely brand-new path) must land the new revision as a CHILD of the pre-forget revision,
 * not sever it into an unrelated new file (verified live against real hg 7.2: `hg log --follow`
 * across a forget+re-add boundary keeps walking into the pre-forget history, and
 * `hg debugindex` shows the post-re-add revision's p1 pointing at the pre-forget revision's
 * node, not the null node). This is the {@link AddCommand} "normallookup" gap this wave's audit
 * found and fixed (see {@code AddCommand.call()}) -- without it, this scenario's
 * {@code log --follow} assertion below would fail.
 */
@Tag("interop")
public class RequirementMatrixForgetCoreRoundTripTest {

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
    public void hg4jForgetAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "forget");

        Files.writeString(repoDir.toPath().resolve("top.txt"), "top v1\n");
        Files.createDirectories(repoDir.toPath().resolve("adir"));
        Files.writeString(repoDir.toPath().resolve("adir/nested.txt"), "nested v1\n");
        Files.writeString(repoDir.toPath().resolve("base.txt"), "base v1\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0 base");

        HgRepository repo = new HgRepository(repoDir);
        new ForgetCommand(repo).setFile("top.txt").call();
        new ForgetCommand(repo).setFile("adir/nested.txt").call();

        String status = HgTestUtils.hg(repoDir, "status");
        assertTrue(status.lines().anyMatch(l -> l.equals("R top.txt")),
                "real hg status must see top.txt as removed (forgotten) for combo " + combo + ": " + status);
        assertTrue(status.lines().anyMatch(l -> l.equals("R adir/nested.txt")),
                "real hg status must see adir/nested.txt as removed (forgotten) for combo " + combo + ": " + status);
        // Forget must NOT touch the working copy file.
        assertTrue(Files.exists(repoDir.toPath().resolve("top.txt")), "forget must leave top.txt on disk");
        assertTrue(Files.exists(repoDir.toPath().resolve("adir/nested.txt")), "forget must leave adir/nested.txt on disk");

        // Re-track the same paths with genuinely different content, exercising the re-add path.
        Files.writeString(repoDir.toPath().resolve("top.txt"), "top v2 after forget\n");
        Files.writeString(repoDir.toPath().resolve("adir/nested.txt"), "nested v2 after forget\n");
        new AddCommand(repo).addFile("top.txt").addFile("adir/nested.txt").call();

        String statusAfterReadd = HgTestUtils.hg(repoDir, "status");
        assertTrue(statusAfterReadd.lines().anyMatch(l -> l.equals("M top.txt")),
                "real hg status must see top.txt as modified after forget+re-add for combo " + combo + ": " + statusAfterReadd);
        assertTrue(statusAfterReadd.lines().anyMatch(l -> l.equals("M adir/nested.txt")),
                "real hg status must see adir/nested.txt as modified after forget+re-add for combo " + combo + ": " + statusAfterReadd);

        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1 forget+re-add");

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.toLowerCase().contains("integrity error"),
                "real hg verify must find no integrity errors after forget+re-add+commit for combo " + combo + ": " + verify);

        String manifest = HgTestUtils.hg(repoDir, "manifest", "-r", "tip");
        assertTrue(manifest.lines().anyMatch(l -> l.equals("top.txt")), "manifest: " + manifest);
        assertTrue(manifest.lines().anyMatch(l -> l.equals("adir/nested.txt")), "manifest: " + manifest);
        assertTrue(manifest.lines().anyMatch(l -> l.equals("base.txt")), "manifest: " + manifest);

        assertEquals("top v2 after forget", HgTestUtils.hg(repoDir, "cat", "-r", "tip", "top.txt").trim());
        assertEquals("nested v2 after forget", HgTestUtils.hg(repoDir, "cat", "-r", "tip", "adir/nested.txt").trim());

        // The whole point: the re-add must NOT sever the file's history -- `hg log --follow`
        // must still reach the pre-forget c0 revision.
        String followTop = HgTestUtils.hg(repoDir, "log", "--follow", "-r", "tip", "top.txt", "--template", "{rev} ");
        assertTrue(followTop.contains("0"),
                "log --follow must reach c0 through top.txt across the forget+re-add boundary for combo " + combo + ": " + followTop);
        String followNested = HgTestUtils.hg(repoDir, "log", "--follow", "-r", "tip", "adir/nested.txt", "--template", "{rev} ");
        assertTrue(followNested.contains("0"),
                "log --follow must reach c0 through adir/nested.txt across the forget+re-add boundary for combo " + combo + ": " + followNested);

        // And the filelog parentage itself must chain, not restart at a null p1.
        String debugIndexTop = HgTestUtils.hg(repoDir, "debugindex", "top.txt");
        String[] topLines = debugIndexTop.lines().skip(1).toArray(String[]::new);
        assertTrue(topLines.length >= 2, "top.txt filelog must have 2 revisions across forget+re-add: " + debugIndexTop);
        assertFalse(topLines[1].trim().split("\\s+")[3].equals("000000000000"),
                "top.txt's post-re-add filelog revision must chain to the pre-forget revision (non-null p1): " + debugIndexTop);
    }
}
