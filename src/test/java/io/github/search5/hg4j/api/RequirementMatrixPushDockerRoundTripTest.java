package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest}
 * for the full 30-combo design this reuses verbatim) applied to {@link PushCommand} -- the
 * Docker-only counterpart of {@link RequirementMatrixPushCoreRoundTripTest}'s native 6-combo
 * scenario (hg4j pushes into an empty same-combo destination, then an incremental second push).
 *
 * <p><b>Known gaps carried over from the native half (2026-09-05, root-caused, NOT fixed in this
 * pass -- see backlog #39):</b> every {@code treemanifest} combo and every {@code cl2+sidedata}
 * combo is EXPECTED to fail here too, for the exact same two reasons the native matrix already
 * found: (1) {@code PushCommand} only ever builds a changegroup for the ROOT manifest revlog
 * ({@code repository.getManifestRevlog()}) -- it never enumerates or sends per-directory manifest
 * ("dirlog") revisions the way {@code FetchCommand}'s OWN apply side already knows how to consume
 * ({@code bundle.manifestGroups}), so any subdirectory file pushed under treemanifest never
 * reaches the destination; (2) {@code PushCommand} has zero sidedata-transfer logic at all, which
 * a {@code exp-use-copies-side-data-changeset=yes} destination needs for {@code hg verify}'s
 * changeset/manifest crosschecking to succeed (confirmed hg4j-caused, not a real-hg quirk, via a
 * pure real-hg-to-real-hg push+verify control reproduction that stayed clean). This test still
 * exercises every combo (rather than skipping the known-broken half) so the matrix's real signal --
 * exactly which combos pass vs fail -- stays visible and doesn't quietly regress further; a fix for
 * either gap is out of scope for this pass and left for a follow-up wave.
 */
@Tag("interop")
public class RequirementMatrixPushDockerRoundTripTest {

    @BeforeAll
    static void checkNativeHgRust() {
        Assumptions.assumeTrue(NativeHgRust.isAvailable(),
                "Native rust-enabled hg (run docker/hg-rust-7.2.4/build-native.sh) is not built. Skipping the whole class.");
    }

    private static String runHost(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out;
        try (InputStream is = p.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        int code = p.waitFor();
        if (code != 0) {
            throw new AssertionError("host command " + Arrays.toString(cmd) + " failed with exit " + code + ": " + out);
        }
        return out;
    }

    private static String dockerHgTolerantIn(Path workDir, String repoRelPath, String... args) throws Exception {
        return NativeHgRust.hgTolerant(workDir, repoRelPath, args);
    }

    /** Runs hg4j's two add+commit+push cycles in a dedicated subprocess; returns {@code node1Hex
     * node2Hex}. */
    private static String[] pushInSubprocess(Path sourceRepoDir, Path destRepoDir) throws Exception {
        HgRepository source = new HgRepository(sourceRepoDir.toFile());

        Files.writeString(sourceRepoDir.resolve("a.txt"), "one");
        new AddCommand(source).call();
        byte[] node1 = new CommitCommand(source).setAuthor("hg4j").setMessage("c0").call();

        new PushCommand(source).setDestination(destRepoDir.toFile().getAbsolutePath()).call();

        Files.createDirectories(sourceRepoDir.resolve("dir"));
        Files.writeString(sourceRepoDir.resolve("dir").resolve("b.txt"), "two");
        new AddCommand(source).call();
        byte[] node2 = new CommitCommand(source).setAuthor("hg4j").setMessage("c1").call();

        new PushCommand(source).setDestination(destRepoDir.toFile().getAbsolutePath()).call();

        return new String[] {NodeIdUtil.toHex(node1), NodeIdUtil.toHex(node2)};
    }

    /** One point in the Docker-only quarter of the requirement matrix -- identical generation to
     * {@link RequirementMatrixDockerRoundTripTest#combos()} (see {@link
     * RequirementMatrixStripDockerRoundTripTest} for why this is copied rather than shared). */
    record RequirementCombo(String label, List<String> initConfigArgs) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final List<String> DIRSTATE_V2 = List.of("format.use-dirstate-v2=yes");
    private static final List<String> CL_V1 = List.of();
    private static final List<String> CL_V2 = List.of("format.exp-use-changelog-v2=enable-unstable-format-and-corrupt-my-data");
    private static final List<String> CL_V2_SIDEDATA = List.of(
            "format.exp-use-changelog-v2=enable-unstable-format-and-corrupt-my-data",
            "format.exp-use-copies-side-data-changeset=yes");
    private static final List<String> PERSISTENT_NODEMAP = List.of("format.use-persistent-nodemap=true");
    private static final List<String> FILEINDEX_V1 = List.of("format.use-fileindex-v1=yes");
    private static final List<String> GENERAL_V2 = List.of("experimental.revlogv2=enable-unstable-format-and-corrupt-my-data");
    private static final List<String> TREEMANIFEST = List.of("experimental.treemanifest=1");

    static Stream<RequirementCombo> combos() {
        List<RequirementCombo> out = new ArrayList<>();
        List<Map.Entry<String, List<String>>> dirstates = List.of(
                Map.entry("dirstate1", List.<String>of()), Map.entry("dirstate2", DIRSTATE_V2));
        List<Map.Entry<String, List<String>>> changelogs = List.of(
                Map.entry("cl1", CL_V1), Map.entry("cl2", CL_V2),
                Map.entry("cl2+sidedata", CL_V2_SIDEDATA));

        for (var cl : changelogs) {
            for (var tm : List.of(Map.entry("flatmanifest", List.<String>of()),
                    Map.entry("treemanifest", TREEMANIFEST))) {
                List<String> args = new ArrayList<>();
                args.addAll(DIRSTATE_V2);
                args.addAll(cl.getValue());
                args.addAll(tm.getValue());
                out.add(new RequirementCombo("dirstate2/" + cl.getKey() + "/" + tm.getKey() + "/none", args));
            }
        }

        for (var dirstate : dirstates) {
            for (var cl : changelogs) {
                for (var tm : List.of(Map.entry("flatmanifest", List.<String>of()),
                        Map.entry("treemanifest", TREEMANIFEST))) {
                    List<String> args = new ArrayList<>();
                    args.addAll(dirstate.getValue());
                    args.addAll(cl.getValue());
                    args.addAll(tm.getValue());
                    args.addAll(PERSISTENT_NODEMAP);
                    out.add(new RequirementCombo(dirstate.getKey() + "/" + cl.getKey() + "/" + tm.getKey() + "/pnodemap", args));
                }
                List<String> fileindexArgs = new ArrayList<>();
                fileindexArgs.addAll(dirstate.getValue());
                fileindexArgs.addAll(cl.getValue());
                fileindexArgs.addAll(FILEINDEX_V1);
                out.add(new RequirementCombo(dirstate.getKey() + "/" + cl.getKey() + "/flatmanifest/fileindex-v1", fileindexArgs));
                List<String> generalV2Args = new ArrayList<>();
                generalV2Args.addAll(dirstate.getValue());
                generalV2Args.addAll(cl.getValue());
                generalV2Args.addAll(GENERAL_V2);
                out.add(new RequirementCombo(dirstate.getKey() + "/" + cl.getKey() + "/flatmanifest/general-v2", generalV2Args));
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jPushToRealHgDestAcrossDockerCombo(RequirementCombo combo) throws Exception {
        NativeHgRust.withFreshWorkDir("hg4j-native-matrix", (workDir) -> {
            String sourceRelPath = "source";
            String destRelPath = "dest";
            Path hostSourceDir = workDir.resolve(sourceRelPath);
            Path hostDestDir = workDir.resolve(destRelPath);
            Files.createDirectories(hostSourceDir);
            Files.createDirectories(hostDestDir);

            List<String> initArgs = new ArrayList<>(List.of("init", "."));
            for (String c : combo.initConfigArgs()) {
                initArgs.add("--config");
                initArgs.add(c);
            }
            NativeHgRust.hg(workDir, sourceRelPath, initArgs.toArray(new String[0]));
            NativeHgRust.hg(workDir, destRelPath, initArgs.toArray(new String[0]));

            String[] nodes = pushInSubprocess(hostSourceDir, hostDestDir);
            String node1Hex = nodes[0];
            String node2Hex = nodes[1];

            String destTip1 = NativeHgRust.hg(workDir, destRelPath, "log", "-r", "0", "--template", "{node}");
            assertEquals(node1Hex, destTip1, "real hg dest must see the first pushed commit for combo " + combo);
            String cat1 = NativeHgRust.hg(workDir, destRelPath, "cat", "-r", "0", "a.txt");
            assertEquals("one", cat1);

            String destTip2 = NativeHgRust.hg(workDir, destRelPath, "log", "-r", "tip", "--template", "{node}");
            assertEquals(node2Hex, destTip2, "real hg dest must see the second pushed commit as tip for combo " + combo);
            String cat2 = NativeHgRust.hg(workDir, destRelPath, "cat", "-r", "tip", "dir/b.txt");
            assertEquals("two", cat2);

            String verify = dockerHgTolerantIn(workDir, destRelPath, "verify");
            assertFalse(verify.toLowerCase().contains("integrity error") || verify.toLowerCase().contains("error:"),
                    "real hg verify must find no integrity errors after push for combo " + combo + ": " + verify);
            String log = NativeHgRust.hg(workDir, destRelPath, "log", "--template", "{rev}:{node}\n");
            assertEquals(2, log.split("\n").length, "destination must have exactly 2 revisions for combo " + combo + ":\n" + log);
        });
    }
}
