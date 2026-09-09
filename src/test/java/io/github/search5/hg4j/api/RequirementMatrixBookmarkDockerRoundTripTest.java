package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;

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
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest}
 * for the full 30-combo design this reuses verbatim) applied to {@link BookmarkCommand} -- the
 * Docker-only counterpart of {@link RequirementMatrixBookmarkCoreRoundTripTest}'s native 6-combo
 * scenario. The negative (force-gate rejection) path is exhaustively covered natively already
 * (backlog #39 wave-3 finding, see that class's javadoc) -- the gate is a pure changelog-ancestor
 * check with no dependency on any of the storage-확장 axes this Docker matrix varies, so this half
 * only re-runs the happy path (create active, create explicit, fast-forward move, delete) across
 * all 30 combos to confirm the bookmark file I/O itself is unaffected by them.
 *
 * <p>Each case gets its own fresh, short-lived container, matching every other write-direction
 * test in this matrix family. hg4j's own {@link BookmarkCommand} calls run in a dedicated
 * {@code java} subprocess ({@link RequirementMatrixBookmarkHelperMain}) for the same
 * docker-exec-interleaving corruption reason documented on {@link RequirementMatrixCommitHelperMain}.
 */
@Tag("interop")
public class RequirementMatrixBookmarkDockerRoundTripTest {

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


    /** EXPERIMENT (2026-09-09): inline instead of subprocess. */
    private static void bookmarkInSubprocess(Path repoDir, String... args) throws Exception {
        String op = args[0];
        HgRepository repo = new HgRepository(repoDir.toFile());
        BookmarkCommand cmd = new BookmarkCommand(repo);
        switch (op) {
            case "create-active":
                cmd.setBookmarkName(args[1]).call();
                break;
            case "create-explicit":
                cmd.setBookmarkName(args[1]).setRevision(args[2]);
                if (args.length > 3 && Boolean.parseBoolean(args[3])) {
                    cmd.setForce(true);
                }
                cmd.call();
                break;
            case "delete":
                cmd.setDelete(true).setBookmarkName(args[1]).call();
                break;
            default:
                throw new IllegalArgumentException("Unknown bookmark op: " + op);
        }
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
    public void hg4jBookmarkAcrossDockerCombo(RequirementCombo combo) throws Exception {
        NativeHgRust.withFreshWorkDir("hg4j-native-matrix", (workDir) -> {
            String repoRelPath = "repo";
            Path hostRepoDir = workDir.resolve(repoRelPath);
            Files.createDirectories(hostRepoDir);

            List<String> initArgs = new ArrayList<>(List.of("init", "."));
            for (String c : combo.initConfigArgs()) {
                initArgs.add("--config");
                initArgs.add(c);
            }
            NativeHgRust.hg(workDir, repoRelPath, initArgs.toArray(new String[0]));

            Files.writeString(hostRepoDir.resolve("base.txt"), "base\n");
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c0 base");
            String rev0Hex = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            bookmarkInSubprocess(hostRepoDir, "create-active", "cur");
            String bm1 = NativeHgRust.hg(workDir, repoRelPath, "bookmarks");
            assertTrue(bm1.contains("* cur"), "cur must be active for combo " + combo + ": " + bm1);
            assertEquals(rev0Hex, NativeHgRust.hg(workDir, repoRelPath, "log", "-r", "cur", "--template", "{node}"));

            Files.writeString(hostRepoDir.resolve("b.txt"), "two\n");
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c1");
            String rev1Hex = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");
            assertEquals(rev1Hex, NativeHgRust.hg(workDir, repoRelPath, "log", "-r", "cur", "--template", "{node}"),
                    "real hg's own commit must auto-advance the hg4j-created active bookmark for combo " + combo);

            bookmarkInSubprocess(hostRepoDir, "create-explicit", "stable", rev0Hex);
            String bm3 = NativeHgRust.hg(workDir, repoRelPath, "bookmarks");
            assertFalse(bm3.lines().anyMatch(l -> l.trim().startsWith("* stable")),
                    "an explicitly-targeted new bookmark must not become active for combo " + combo + ": " + bm3);
            assertEquals(rev0Hex, NativeHgRust.hg(workDir, repoRelPath, "log", "-r", "stable", "--template", "{node}"));

            bookmarkInSubprocess(hostRepoDir, "create-explicit", "stable", rev1Hex);
            assertEquals(rev1Hex, NativeHgRust.hg(workDir, repoRelPath, "log", "-r", "stable", "--template", "{node}"),
                    "fast-forward bookmark move must succeed for combo " + combo);

            bookmarkInSubprocess(hostRepoDir, "delete", "cur");
            String bm5 = NativeHgRust.hg(workDir, repoRelPath, "bookmarks");
            assertFalse(bm5.lines().anyMatch(l -> l.contains("cur")), "real hg bookmarks for combo " + combo + ": " + bm5);
            assertTrue(bm5.lines().anyMatch(l -> l.contains("stable")), "real hg bookmarks for combo " + combo + ": " + bm5);

            String verify = NativeHgRust.hg(workDir, repoRelPath, "verify");
            assertFalse(verify.toLowerCase().contains("integrity error"),
                    "real hg verify must find no integrity errors for combo " + combo + ": " + verify);
        });
    }
}
