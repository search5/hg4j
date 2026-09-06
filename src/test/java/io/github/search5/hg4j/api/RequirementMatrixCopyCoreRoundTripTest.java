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
 * {@code exhaustive-interop-matrix-plan.md} §1) to {@link CopyCommand} across the native 6-combo
 * grid (changelog family x treemanifest, dirstate fixed at v1 -- v2 needs Docker, see
 * {@code RequirementMatrixCopyDockerRoundTripTest}).
 *
 * <p>Wave 4 (2026-09-05): {@link CopyCommand} writes only the working-copy dirstate (a new 'a'
 * entry for the destination plus a copyMap record), like {@link AddCommand}, but its filelog-side
 * effect only shows up once {@link CommitCommand} consumes that copyMap record into the new
 * revision's {@code copy}/{@code copyrev} filelog metadata. This scenario copies a root-level
 * file to both another root-level path and a nested directory (matching {@link
 * RequirementMatrixAddCoreRoundTripTest}'s multi-root-segment shape, which is exactly what
 * exercises the dirstate-v2 tree-node-ordering class of bug on the Docker side of this pair), then
 * commits and verifies real hg can both see the copy as a copy (`{file_copies}` changeset
 * metadata, `hg log --follow`) and reconstruct the destination's exact content independently of
 * the source.
 */
@Tag("interop")
public class RequirementMatrixCopyCoreRoundTripTest {

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
    public void hg4jCopyAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "copy");

        Files.writeString(repoDir.toPath().resolve("orig.txt"), "original content\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0 base");

        HgRepository repo = new HgRepository(repoDir);
        Files.createDirectories(repoDir.toPath().resolve("adir"));
        new CopyCommand(repo).setSource("orig.txt").setDestination("copy-top.txt").call();
        new CopyCommand(repo).setSource("orig.txt").setDestination("adir/copy-nested.txt").call();

        String status = HgTestUtils.hg(repoDir, "status", "-C");
        assertTrue(status.lines().anyMatch(l -> l.equals("A copy-top.txt")),
                "real hg status must see copy-top.txt as added for combo " + combo + ": " + status);
        assertTrue(status.lines().anyMatch(l -> l.equals("  orig.txt")),
                "real hg status -C must record copy-top.txt's source as orig.txt for combo " + combo + ": " + status);
        assertTrue(status.lines().anyMatch(l -> l.equals("A adir/copy-nested.txt")),
                "real hg status must see adir/copy-nested.txt as added for combo " + combo + ": " + status);

        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1 copy");

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.toLowerCase().contains("integrity error"),
                "real hg verify must find no integrity errors after copy+commit for combo " + combo + ": " + verify);

        String manifest = HgTestUtils.hg(repoDir, "manifest", "-r", "tip");
        assertTrue(manifest.lines().anyMatch(l -> l.equals("orig.txt")), "manifest: " + manifest);
        assertTrue(manifest.lines().anyMatch(l -> l.equals("copy-top.txt")), "manifest: " + manifest);
        assertTrue(manifest.lines().anyMatch(l -> l.equals("adir/copy-nested.txt")), "manifest: " + manifest);

        // The committed changeset's own copy metadata must record BOTH destinations' source.
        String fileCopies = HgTestUtils.hg(repoDir, "log", "-r", "tip", "--template", "{file_copies}\n");
        assertTrue(fileCopies.contains("copy-top.txt (orig.txt)"),
                "changeset file_copies must record copy-top.txt <- orig.txt for combo " + combo + ": " + fileCopies);
        assertTrue(fileCopies.contains("adir/copy-nested.txt (orig.txt)"),
                "changeset file_copies must record adir/copy-nested.txt <- orig.txt for combo " + combo + ": " + fileCopies);

        // Once committed, the dirstate's pending-copy record must be gone (real hg clears it --
        // `hg status -C` on a clean working copy shows nothing at all for either destination).
        String cleanStatus = HgTestUtils.hg(repoDir, "status", "-C");
        assertTrue(cleanStatus.isEmpty(), "working copy must be clean after commit for combo " + combo + ": " + cleanStatus);

        // History must follow through the copy for both destinations.
        String followTop = HgTestUtils.hg(repoDir, "log", "--follow", "-r", "tip", "copy-top.txt", "--template", "{rev} ");
        assertTrue(followTop.contains("0"), "log --follow must reach c0 through copy-top.txt for combo " + combo + ": " + followTop);
        String followNested = HgTestUtils.hg(repoDir, "log", "--follow", "-r", "tip", "adir/copy-nested.txt", "--template", "{rev} ");
        assertTrue(followNested.contains("0"), "log --follow must reach c0 through adir/copy-nested.txt for combo " + combo + ": " + followNested);

        assertEquals("original content", HgTestUtils.hg(repoDir, "cat", "-r", "tip", "orig.txt").trim());
        assertEquals("original content", HgTestUtils.hg(repoDir, "cat", "-r", "tip", "copy-top.txt").trim());
        assertEquals("original content", HgTestUtils.hg(repoDir, "cat", "-r", "tip", "adir/copy-nested.txt").trim());
    }
}
