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
import java.util.ArrayList;
import java.util.Map;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixCoreRoundTripTest} for the reused
 * pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to {@link
 * RebaseCommand} across the native 6-combo grid (changelog family x treemanifest, dirstate fixed
 * at v1 -- v2 needs Docker, see {@code RequirementMatrixRebaseDockerRoundTripTest}).
 *
 * <p>Real hg builds a diverging two-branch history (matching {@code
 * RebaseRealHgInteropTest#conflictFreeRebaseVerifiedByRealHg}'s scenario, here parametrized across
 * every combo instead of just the host's default format), hg4j performs the rebase, and real hg
 * re-reads the result: {@code verify} must be clean, the rebased commit's parent must be the
 * target, and both branches' file content must be present. Reads use {@code
 * experimental.evolution=all} to suppress the (expected, unrelated) "obsolete feature not
 * enabled" warning real hg prints after any hg4j rebase, exactly like {@code
 * RebaseRealHgInteropTest} already does.
 */
@Tag("interop")
public class RequirementMatrixRebaseCoreRoundTripTest {

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
     * post-rebase repository (which always carries an obsmarker) can be queried without the
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
    public void hg4jRebaseAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "rebase");

        Files.writeString(repoDir.toPath().resolve("base.txt"), "base\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");

        Files.writeString(repoDir.toPath().resolve("target.txt"), "on-target\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1 target");
        String targetHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "update", "0");
        Files.writeString(repoDir.toPath().resolve("source.txt"), "on-source\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c2 source");
        String sourceHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgRepository repo = new HgRepository(repoDir);
        byte[] rebased = new RebaseCommand(repo)
                .setSource(NodeIdUtil.fromHex(sourceHex))
                .setTarget(NodeIdUtil.fromHex(targetHex))
                .call();
        String rebasedHex = NodeIdUtil.toHex(rebased);

        String verify = hgEvolution(repoDir, "verify");
        assertFalse(verify.toLowerCase().contains("integrity error"),
                "real hg verify must find no integrity errors after rebase for combo " + combo + ": " + verify);

        String rebasedParent = hgEvolution(repoDir, "log", "-r", rebasedHex, "--template", "{p1node}");
        assertEquals(targetHex, rebasedParent, "rebased commit's parent must be target for combo " + combo);

        String catTarget = hgEvolution(repoDir, "cat", "-r", rebasedHex, "target.txt");
        assertEquals("on-target", catTarget.trim());
        String catSource = hgEvolution(repoDir, "cat", "-r", rebasedHex, "source.txt");
        assertEquals("on-source", catSource.trim());

        String logAll = hgEvolution(repoDir, "log", "--template", "{node} ");
        assertFalse(logAll.contains(sourceHex), "hidden pre-rebase revision must not appear in a plain log for combo " + combo);
    }
}
