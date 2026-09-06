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
import static org.junit.jupiter.api.Assertions.assertFalse;
import java.util.ArrayList;
import java.util.Map;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixCoreRoundTripTest} for the pattern
 * this reuses, and {@code llm-wiki/decisions/exhaustive-interop-matrix-plan.md} §1 / backlog #39
 * for why) to {@link ExportCommand} and {@link ImportCommand} together across the native 6-combo
 * grid ({@code format.exp-use-changelog-v2}: v1 / changelog-v2 / changelog-v2+sidedata x {@code
 * experimental.treemanifest}: off / on; {@code dirstate} fixed at v1 -- v2 needs Docker, see
 * {@link RequirementMatrixExportImportDockerRoundTripTest}).
 *
 * <p>Grouped as one trio (rather than two) because they are each other's round-trip counterpart:
 * one method per combo covers BOTH directions --
 * <ol>
 *   <li><b>hg4j export, real hg import:</b> hg4j commits a two-file change (one root file, one in
 *   a subdirectory -- the latter exercises a treemanifest dirlog for the {@code treemanifest}
 *   combos) and {@link ExportCommand} exports it to patch text; real {@code hg import} applies
 *   that patch text to an independently {@code hg init}'d destination of the SAME combo. Since
 *   the patch carries the exact same content/user/date/message hg4j committed with, and a
 *   changeset's node hash is a pure function of that content (not of which implementation wrote
 *   it), real hg's import must reproduce a BYTE-IDENTICAL commit node -- a much stronger check
 *   than merely "some commit was created".</li>
 *   <li><b>real hg export, hg4j import:</b> real hg commits an equivalent two-file change and
 *   {@code hg export -r 0} produces patch text; hg4j's {@link ImportCommand} applies that patch
 *   text to an independently {@code hg init}'d destination of the SAME combo. Verified the same
 *   way, in reverse -- real hg (reading files hg4j alone wrote) must see the identical node,
 *   content, and a clean {@code hg verify}.</li>
 * </ol>
 *
 * <p>Unlike {@link RequirementMatrixBundleCoreRoundTripTest}/{@link
 * RequirementMatrixUnbundleCoreRoundTripTest}, no {@code cl2+sidedata} tolerance is needed here:
 * the confirmed real-hg limitation those two document is specific to {@code hg bundle}/{@code hg
 * unbundle}'s FILE-based changegroup path (see {@link BundleCommand}'s class javadoc) -- {@code hg
 * import}/{@code hg export} never touch a changegroup at all, they go through the ordinary
 * per-changeset commit path, which stays clean for every combo (confirmed live, 2026-09-05).
 */
@Tag("interop")
public class RequirementMatrixExportImportCoreRoundTripTest {

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
    public void exportImportRoundTripAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        // Direction 1: hg4j commits + exports; real hg imports the resulting patch text.
        File repoADir = initWithCombo(tempDir, combo, "export-src");
        HgRepository repoA = new HgRepository(repoADir);
        Files.writeString(repoADir.toPath().resolve("a.txt"), "hello from hg4j\n");
        Files.createDirectories(repoADir.toPath().resolve("dir"));
        Files.writeString(repoADir.toPath().resolve("dir").resolve("b.txt"), "nested content\n");
        new AddCommand(repoA).call();
        byte[] commitNode = new CommitCommand(repoA)
                .setAuthor("hg4j export <export@example.com>")
                .setDate(1700000000, 0)
                .setMessage("hg4j export commit for " + combo)
                .call();
        String hg4jNodeHex = NodeIdUtil.toHex(commitNode);

        String patch = new ExportCommand(repoA).setRevision("0").call();
        File patchFile1 = tempDir.resolve("hg4j-export-" + combo.label().replace("/", "-") + ".patch").toFile();
        Files.writeString(patchFile1.toPath(), patch);

        File repoBDir = initWithCombo(tempDir, combo, "export-dst");
        HgTestUtils.hg(repoBDir, "import", patchFile1.getAbsolutePath());

        String importedHex = HgTestUtils.hg(repoBDir, "log", "-r", "tip", "--template", "{node}");
        assertEquals(hg4jNodeHex, importedHex,
                "real hg import of hg4j's exported patch must reproduce a byte-identical commit node for combo " + combo);
        assertEquals("hello from hg4j", HgTestUtils.hg(repoBDir, "cat", "-r", "tip", "a.txt"));
        assertEquals("nested content", HgTestUtils.hg(repoBDir, "cat", "-r", "tip", "dir/b.txt"));
        String verifyB = HgTestUtils.hg(repoBDir, "verify");
        assertFalse(verifyB.toLowerCase().contains("integrity error"),
                "real hg verify must find no integrity errors after importing hg4j's exported patch for combo " + combo + ": " + verifyB);

        // Direction 2: real hg commits + exports; hg4j's ImportCommand applies the resulting
        // patch text.
        File repoCDir = initWithCombo(tempDir, combo, "import-src");
        Files.writeString(repoCDir.toPath().resolve("x.txt"), "hello from real hg\n");
        HgTestUtils.hg(repoCDir, "add");
        Files.createDirectories(repoCDir.toPath().resolve("dir2"));
        Files.writeString(repoCDir.toPath().resolve("dir2").resolve("y.txt"), "nested from real hg\n");
        HgTestUtils.hg(repoCDir, "add");
        HgTestUtils.hg(repoCDir, "commit", "-u", "realhg", "-m", "real hg export commit for " + combo);
        String realHgNodeHex = HgTestUtils.hg(repoCDir, "log", "-r", "0", "--template", "{node}");
        String realHgPatch = HgTestUtils.hg(repoCDir, "export", "-r", "0");

        File repoDDir = initWithCombo(tempDir, combo, "import-dst");
        HgRepository repoD = new HgRepository(repoDDir);
        new ImportCommand(repoD).setPatchText(realHgPatch).call();

        String hg4jImportedHex = HgTestUtils.hg(repoDDir, "log", "-r", "tip", "--template", "{node}");
        assertEquals(realHgNodeHex, hg4jImportedHex,
                "hg4j's ImportCommand applying a real-hg-exported patch must reproduce the exact same commit node for combo " + combo);
        assertEquals("hello from real hg", HgTestUtils.hg(repoDDir, "cat", "-r", "tip", "x.txt"));
        assertEquals("nested from real hg", HgTestUtils.hg(repoDDir, "cat", "-r", "tip", "dir2/y.txt"));
        String verifyD = HgTestUtils.hg(repoDDir, "verify");
        assertFalse(verifyD.toLowerCase().contains("integrity error"),
                "real hg verify must find no integrity errors after hg4j imported real hg's exported patch for combo " + combo + ": " + verifyD);
    }
}
