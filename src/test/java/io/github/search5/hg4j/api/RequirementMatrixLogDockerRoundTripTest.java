package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.testsupport.LocalHg;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Map;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest} for
 * the full 30-combo design this reuses verbatim) applied to {@link LogCommand} -- the Docker-only
 * counterpart of {@link RequirementMatrixLogCoreRoundTripTest}'s native 6-combo scenarios. No hg4j
 * write step / {@code HelperMain} subprocess is needed: {@link LogCommand} never mutates the
 * repository (see {@link RequirementMatrixDescribeDockerRoundTripTest}'s javadoc for why that
 * matters).
 */
@Tag("interop")
public class RequirementMatrixLogDockerRoundTripTest {






    @BeforeAll
    static void checkLocalHg() {
        Assumptions.assumeTrue(LocalHg.isAvailable(), LocalHg::unavailableReason);
    }

    @FunctionalInterface
    private interface FreshWorkDirTest {
        void run(Path workDir) throws Exception;
    }

    private static void withFreshWorkDir(FreshWorkDirTest test) throws Exception {
        Path workDir = Files.createTempDirectory("hg4j-docker-log-matrix").toRealPath();
        try {
            test.run(workDir);
        } finally {
            deleteRecursively(workDir.toFile());
        }
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        f.delete();
    }


    /** One point in the Docker-only quarter of the requirement matrix -- identical generation to
     * {@link RequirementMatrixDockerRoundTripTest#combos()}. */
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
    public void plainLogAcrossDockerCombo(RequirementCombo combo) throws Exception {
        withFreshWorkDir(workDir -> {
            String repoRelPath = "repo";
            Path hostRepoDir = workDir.resolve(repoRelPath);
            Files.createDirectories(hostRepoDir);

            List<String> initArgs = new ArrayList<>(List.of("init", "."));
            for (String c : combo.initConfigArgs()) {
                initArgs.add("--config");
                initArgs.add(c);
            }
            LocalHg.hgIn(workDir, repoRelPath, initArgs.toArray(new String[0]));

            Files.writeString(hostRepoDir.resolve("a.txt"), "one");
            LocalHg.hgIn(workDir, repoRelPath, "add");
            LocalHg.hgIn(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c0");
            String hex0 = LocalHg.hgIn(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            Files.createDirectories(hostRepoDir.resolve("dir"));
            Files.writeString(hostRepoDir.resolve("dir").resolve("b.txt"), "two");
            LocalHg.hgIn(workDir, repoRelPath, "add");
            LocalHg.hgIn(workDir, repoRelPath, "commit", "-u", "dev2", "-m", "c1 nested");
            String hex1 = LocalHg.hgIn(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            HgRepository repo = new HgRepository(hostRepoDir.toFile());
            List<HgCommit> log = new LogCommand(repo).call();

            assertEquals(2, log.size(), "combo " + combo);
            assertEquals(hex1, log.get(0).getNodeId().toHex(), "newest first, combo " + combo);
            assertEquals(hex0, log.get(1).getNodeId().toHex(), "combo " + combo);
            assertEquals("c1 nested", log.get(0).getMessage(), "combo " + combo);
            assertEquals("dev2", log.get(0).getAuthor(), "combo " + combo);
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void followPathAcrossRenameBoundaryAcrossDockerCombo(RequirementCombo combo) throws Exception {
        withFreshWorkDir(workDir -> {
            String repoRelPath = "repo";
            Path hostRepoDir = workDir.resolve(repoRelPath);
            Files.createDirectories(hostRepoDir);

            List<String> initArgs = new ArrayList<>(List.of("init", "."));
            for (String c : combo.initConfigArgs()) {
                initArgs.add("--config");
                initArgs.add(c);
            }
            LocalHg.hgIn(workDir, repoRelPath, initArgs.toArray(new String[0]));

            Files.writeString(hostRepoDir.resolve("a.txt"), "one");
            LocalHg.hgIn(workDir, repoRelPath, "add");
            LocalHg.hgIn(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c0");
            String hex0 = LocalHg.hgIn(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            LocalHg.hgIn(workDir, repoRelPath, "rename", "a.txt", "b.txt");
            LocalHg.hgIn(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c1-rename");
            String hex1 = LocalHg.hgIn(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            Files.writeString(hostRepoDir.resolve("b.txt"), "one\ntwo");
            LocalHg.hgIn(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c2-modify");
            String hex2 = LocalHg.hgIn(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            Files.writeString(hostRepoDir.resolve("unrelated.txt"), "x");
            LocalHg.hgIn(workDir, repoRelPath, "add");
            LocalHg.hgIn(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c3-unrelated");

            String realFollow = LocalHg.hgIn(workDir, repoRelPath, "log", "--follow", "b.txt", "--template", "{node}\n");
            List<String> realFollowHexes = List.of(realFollow.split("\n"));
            assertEquals(List.of(hex2, hex1, hex0), realFollowHexes, "sanity, combo " + combo);

            HgRepository repo = new HgRepository(hostRepoDir.toFile());
            List<HgCommit> log = new LogCommand(repo).setFollowPath("b.txt").call();
            List<String> hg4jFollowHexes = log.stream().map(c -> c.getNodeId().toHex()).toList();

            assertEquals(realFollowHexes, hg4jFollowHexes,
                    "hg4j's --follow must match real hg's revision set exactly, combo " + combo);
        });
    }
}
