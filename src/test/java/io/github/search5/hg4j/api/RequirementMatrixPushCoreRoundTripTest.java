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
 * for why) to {@link PushCommand} across the native 6-combo grid: {@code
 * format.exp-use-changelog-v2} (v1 / changelog-v2 / changelog-v2+sidedata) x {@code
 * experimental.treemanifest} (off / on), {@code dirstate} fixed at v1 (v2 needs Docker, see
 * {@code RequirementMatrixPushDockerRoundTripTest}).
 *
 * <p>Unlike the commit/log/status/cat matrix (single repo, one side writes and the other reads),
 * push is inherently a TWO-repository interaction: hg4j reads the destination's current heads
 * (via {@code HgLocalClient}, exercising hg4j's read path against that combo's on-disk format),
 * computes and applies a changegroup into it (exercising hg4j's write path into that combo's
 * format), and the destination is then re-verified by real hg. Both a push into an EMPTY
 * destination and a second, INCREMENTAL push into an already-non-empty destination are covered in
 * one method, since the second push is the only way to exercise hg4j reading an existing
 * real-hg-written head of this combo before deciding what to send.
 */
@Tag("interop")
public class RequirementMatrixPushCoreRoundTripTest {

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
    public void hg4jPushToRealHgDestAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File sourceRepoDir = initWithCombo(tempDir, combo, "source");
        File destRepoDir = initWithCombo(tempDir, combo, "dest");
        HgRepository source = new HgRepository(sourceRepoDir);

        // First push: destination starts empty for this combo.
        Files.writeString(sourceRepoDir.toPath().resolve("a.txt"), "one");
        new AddCommand(source).call();
        byte[] node1 = new CommitCommand(source).setAuthor("hg4j").setMessage("c0 for " + combo).call();
        String node1Hex = NodeIdUtil.toHex(node1);

        new PushCommand(source).setDestination(destRepoDir.getAbsolutePath()).call();

        String destTip1 = HgTestUtils.hg(destRepoDir, "log", "-r", "tip", "--template", "{node}");
        assertEquals(node1Hex, destTip1, "real hg dest must see the pushed commit as tip for combo " + combo);
        String cat1 = HgTestUtils.hg(destRepoDir, "cat", "-r", "tip", "a.txt");
        assertEquals("one", cat1);
        String verify1 = HgTestUtils.hg(destRepoDir, "verify");
        assertFalse(verify1.toLowerCase().contains("integrity error") || verify1.toLowerCase().contains("error:"),
                "real hg verify must find no integrity errors after first push for combo " + combo + ": " + verify1);

        // Second, incremental push: destination is now non-empty -- hg4j must correctly read the
        // existing real-hg-written head of this combo (via HgLocalClient.getHeads()) before
        // deciding what needs to be sent.
        Files.createDirectories(sourceRepoDir.toPath().resolve("dir"));
        Files.writeString(sourceRepoDir.toPath().resolve("dir").resolve("b.txt"), "two");
        new AddCommand(source).call();
        byte[] node2 = new CommitCommand(source).setAuthor("hg4j").setMessage("c1 for " + combo).call();
        String node2Hex = NodeIdUtil.toHex(node2);

        new PushCommand(source).setDestination(destRepoDir.getAbsolutePath()).call();

        String destTip2 = HgTestUtils.hg(destRepoDir, "log", "-r", "tip", "--template", "{node}");
        assertEquals(node2Hex, destTip2, "real hg dest must see the second pushed commit as tip for combo " + combo);
        String cat2 = HgTestUtils.hg(destRepoDir, "cat", "-r", "tip", "dir/b.txt");
        assertEquals("two", cat2);
        String verify2 = HgTestUtils.hg(destRepoDir, "verify");
        assertFalse(verify2.toLowerCase().contains("integrity error") || verify2.toLowerCase().contains("error:"),
                "real hg verify must find no integrity errors after incremental push for combo " + combo + ": " + verify2);
        String log = HgTestUtils.hg(destRepoDir, "log", "--template", "{rev}:{node}\n");
        assertEquals(2, log.split("\n").length, "destination must have exactly 2 revisions for combo " + combo + ":\n" + log);
    }
}
