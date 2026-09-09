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
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.TimeUnit;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest}
 * for the full 30-combo design this reuses verbatim) applied to {@link TagsCommand}/
 * {@link PathsCommand}/{@link RootCommand} -- the Docker-only counterpart of
 * {@link RequirementMatrixTagsCoreRoundTripTest}'s native 6-combo scenario (see that class's
 * javadoc for the full scenario writeup).
 *
 * <p>All three commands are pure readers, same as {@link RequirementMatrixHeadsDockerRoundTripTest}.
 * Each case gets its own fresh, short-lived container, matching that class and
 * {@link RequirementMatrixBackoutDockerRoundTripTest}.
 */
@Tag("interop")
public class RequirementMatrixTagsDockerRoundTripTest {

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
    public void tagsPathsRootMatchRealHgAcrossDockerCombo(RequirementCombo combo) throws Exception {
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

            Files.writeString(hostRepoDir.resolve("a.txt"), "a0\n");
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c0");
            String c0Hex = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            Files.writeString(hostRepoDir.resolve("a.txt"), "a1\n");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c1");
            String c1Hex = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            NativeHgRust.hg(workDir, repoRelPath, "tag", "-u", "dev", "-r", c1Hex, "v1");
            NativeHgRust.hg(workDir, repoRelPath, "tag", "-u", "dev", "--local", "-r", c0Hex, "v2");

            // PathsCommand (empty case) checked with its own handle constructed BEFORE the
            // [paths] section is appended below -- see RequirementMatrixTagsCoreRoundTripTest for
            // why: HgRepository loads .hg/hgrc once, at construction time, not live.
            HgRepository repoBeforePaths = new HgRepository(hostRepoDir.toFile());
            String realPathsEmpty = NativeHgRust.hg(workDir, repoRelPath, "paths");
            assertEquals("", realPathsEmpty, "combo " + combo);
            assertEquals(Map.of(), new PathsCommand(repoBeforePaths).call(), "combo " + combo);

            Files.writeString(hostRepoDir.resolve(".hg/hgrc"),
                    "\n[paths]\ndefault = https://example.com/repo\n"
                            + "default-push = ssh://example.com/repo\n"
                            + "upstream = https://upstream.example.com/repo\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            HgRepository repo = new HgRepository(hostRepoDir.toFile());

            String realTagsVerboseOut = NativeHgRust.hg(workDir, repoRelPath, "tags", "-v");
            List<TagsCommand.Tag> hg4jTags = new TagsCommand(repo).call();
            StringBuilder rebuilt = new StringBuilder();
            for (int i = 0; i < hg4jTags.size(); i++) {
                TagsCommand.Tag t = hg4jTags.get(i);
                if (i > 0) {
                    rebuilt.append('\n');
                }
                String namePart = String.format("%-35s", t.getName());
                rebuilt.append(namePart).append(t.getRev()).append(':').append(NodeIdUtil.toHex(t.getNode()), 0, 12);
                if (t.isLocal()) {
                    rebuilt.append(" local");
                }
            }
            assertEquals(realTagsVerboseOut, rebuilt.toString(), "combo " + combo);

            assertEquals(3, hg4jTags.size(), "combo " + combo);
            TagsCommand.Tag v1Tag = hg4jTags.stream().filter(t -> t.getName().equals("v1")).findFirst().orElseThrow();
            TagsCommand.Tag v2Tag = hg4jTags.stream().filter(t -> t.getName().equals("v2")).findFirst().orElseThrow();
            assertFalse(v1Tag.isLocal(), "combo " + combo);
            assertTrue(v2Tag.isLocal(), "combo " + combo);
            assertEquals(c1Hex, NodeIdUtil.toHex(v1Tag.getNode()), "combo " + combo);
            assertEquals(c0Hex, NodeIdUtil.toHex(v2Tag.getNode()), "combo " + combo);

            String realPathsPopulated = NativeHgRust.hg(workDir, repoRelPath, "paths");
            Map<String, String> hg4jPaths = new PathsCommand(repo).call();
            StringBuilder rebuiltPaths = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, String> e : hg4jPaths.entrySet()) {
                if (!first) {
                    rebuiltPaths.append('\n');
                }
                first = false;
                rebuiltPaths.append(e.getKey()).append(" = ").append(e.getValue());
            }
            assertEquals(realPathsPopulated, rebuiltPaths.toString(), "combo " + combo);
            assertEquals(
                    Map.of("default", "https://example.com/repo",
                            "default-push", "ssh://example.com/repo",
                            "upstream", "https://upstream.example.com/repo"),
                    hg4jPaths, "combo " + combo);

            // NOTE: real hg's own `hg root` here reports the path as seen INSIDE the container
            // (e.g. "/repo-root/repo") since it runs via `docker exec`, while hg4j opens the
            // directory through the host-side bind mount -- the two paths are NOT expected to be
            // textually equal even though they name the same physical directory, so this only
            // checks hg4j's own self-consistency (unlike every other assertion in this class,
            // which does compare textually against real hg's own output).
            String hg4jRoot = new RootCommand(repo).call();
            assertEquals(hostRepoDir.toFile().getCanonicalPath(), new File(hg4jRoot).getCanonicalPath(), "combo " + combo);
        });
    }
}
