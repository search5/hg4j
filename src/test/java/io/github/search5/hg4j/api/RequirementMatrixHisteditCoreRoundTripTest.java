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
import java.util.ArrayList;
import java.util.Map;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixCoreRoundTripTest} for the reused
 * pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to {@link
 * HisteditCommand} across the native 6-combo grid (changelog family x treemanifest, dirstate fixed
 * at v1 -- v2 needs Docker, see {@code RequirementMatrixHisteditDockerRoundTripTest}).
 *
 * <p>Real hg builds a 6-commit linear history, hg4j runs a single histedit combining PICK, DROP,
 * FOLD and ROLL (the same rule kinds {@link HisteditCommand} supports -- there is no separate
 * "edit message" action; ROLL exercises the message-mutating side of histedit, since it keeps the
 * pick's own message and discards the rolled-in commit's), and real hg re-reads the result:
 * {@code verify} must be clean, the dropped commit's file must be gone, the folded/rolled files
 * must all be present in one combined commit whose message is the fold's own message appended to
 * the anchor pick's (never the rolled-in commit's message), and the untouched root commit's file
 * must survive. Reads use {@code experimental.evolution=all} to suppress the (expected, unrelated)
 * "obsolete feature not enabled" warning real hg prints after any hg4j histedit, exactly like
 * {@code RebaseRealHgInteropTest}/{@code RequirementMatrixRebaseCoreRoundTripTest} already do.
 */
@Tag("interop")
public class RequirementMatrixHisteditCoreRoundTripTest {

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

    /** Same as {@link HgTestUtils#hg} but with {@code experimental.evolution=all} added, so a
     * post-histedit repository (which always carries obsmarkers) can be queried without the
     * unrelated "obsolete feature not enabled" warning polluting the output being asserted on. */
    private static String hgEvolution(File repoDir, String... args) throws Exception {
        String[] cmd = new String[args.length + 3];
        cmd[0] = "hg";
        cmd[1] = "--config";
        cmd[2] = "experimental.evolution=all";
        System.arraycopy(args, 0, cmd, 3, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(repoDir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out;
        try (InputStream is = p.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new AssertionError("hg " + String.join(" ", args) + " failed with exit " + code + ": " + out);
        }
        return out;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jHisteditAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "histedit");

        Files.writeString(repoDir.toPath().resolve("base.txt"), "base\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0 base");

        Files.writeString(repoDir.toPath().resolve("target.txt"), "target\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1 pick target");
        String hexPick = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        Files.writeString(repoDir.toPath().resolve("drop.txt"), "drop-me\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c2 dropped");
        String hexDrop = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        Files.writeString(repoDir.toPath().resolve("keep.txt"), "keep\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c3 pick keep");
        String hexKeep = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        Files.writeString(repoDir.toPath().resolve("folded.txt"), "folded\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c4 fold in");
        String hexFold = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        Files.writeString(repoDir.toPath().resolve("rolled.txt"), "rolled\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c5 roll in (message must be discarded)");
        String hexRoll = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgRepository repo = new HgRepository(repoDir);
        new HisteditCommand(repo)
                .addRule(HisteditCommand.Action.PICK, hexPick)
                .addRule(HisteditCommand.Action.DROP, hexDrop)
                .addRule(HisteditCommand.Action.PICK, hexKeep)
                .addRule(HisteditCommand.Action.FOLD, hexFold)
                .addRule(HisteditCommand.Action.ROLL, hexRoll)
                .call();

        String newTipHex = NodeIdUtil.toHex(repo.getDirstate().getParent1());

        String verify = hgEvolution(repoDir, "verify");
        assertFalse(verify.toLowerCase().contains("integrity error"),
                "real hg verify must find no integrity errors after histedit for combo " + combo + ": " + verify);

        assertFalse(new File(repoDir, "drop.txt").exists(),
                "dropped commit's file must be gone from the working copy for combo " + combo);
        assertTrue(new File(repoDir, "base.txt").exists(), "untouched root file must survive for combo " + combo);
        assertTrue(new File(repoDir, "keep.txt").exists());
        assertTrue(new File(repoDir, "folded.txt").exists());
        assertTrue(new File(repoDir, "rolled.txt").exists());

        String message = hgEvolution(repoDir, "log", "-r", newTipHex, "--template", "{desc}");
        assertEquals("c3 pick keep\nc4 fold in", message,
                "fold's message must be appended to the pick's; roll's own message must be discarded for combo " + combo);

        String catTarget = hgEvolution(repoDir, "cat", "-r", newTipHex, "target.txt");
        assertEquals("target", catTarget.trim());
        String catKeep = hgEvolution(repoDir, "cat", "-r", newTipHex, "keep.txt");
        assertEquals("keep", catKeep.trim());
        String catFolded = hgEvolution(repoDir, "cat", "-r", newTipHex, "folded.txt");
        assertEquals("folded", catFolded.trim());
        String catRolled = hgEvolution(repoDir, "cat", "-r", newTipHex, "rolled.txt");
        assertEquals("rolled", catRolled.trim());

        String logAll = hgEvolution(repoDir, "log", "--template", "{node} ");
        assertFalse(logAll.contains(hexDrop), "dropped revision must not appear in a plain log for combo " + combo);
    }
}
