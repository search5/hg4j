package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.testsupport.LocalHg;
import io.github.search5.hg4j.lib.NodeId;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import io.github.search5.hg4j.util.NodeIdUtil;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixRealHgRoundTripTest} for
 * the full 30-combo design this reuses verbatim) applied to {@link DiffCommand} -- the Docker-only
 * counterpart of {@link RequirementMatrixDiffCoreRoundTripTest}'s native 6-combo scenario. No hg4j
 * write step / {@code HelperMain} subprocess is needed: {@link DiffCommand} never mutates the
 * repository.
 */
@Tag("interop")
public class RequirementMatrixDiffRealHgRoundTripTest {






    @BeforeAll
    static void checkLocalHg() {
        Assumptions.assumeTrue(LocalHg.isAvailable(), LocalHg::unavailableReason);
    }

    @FunctionalInterface
    private interface FreshWorkDirTest {
        void run(Path workDir) throws Exception;
    }

    private static void withFreshWorkDir(FreshWorkDirTest test) throws Exception {
        Path workDir = Files.createTempDirectory("hg4j-docker-diff-matrix").toRealPath();
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
     * {@link RequirementMatrixRealHgRoundTripTest#combos()}. */
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
    public void diffClassificationAndApplyAcrossDockerCombo(RequirementCombo combo) throws Exception {
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

            Files.createDirectories(hostRepoDir.resolve("dir"));
            Files.writeString(hostRepoDir.resolve("a.txt"), "one\ntwo\nthree\n");
            Files.writeString(hostRepoDir.resolve("dir").resolve("b.txt"), "alpha\n");
            LocalHg.hgIn(workDir, repoRelPath, "add");
            LocalHg.hgIn(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c0");
            String hex0 = LocalHg.hgIn(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            Files.writeString(hostRepoDir.resolve("a.txt"), "one\nTWO\nthree\n");
            Files.delete(hostRepoDir.resolve("dir").resolve("b.txt"));
            Files.writeString(hostRepoDir.resolve("dir").resolve("c.txt"), "gamma\n");
            LocalHg.hgIn(workDir, repoRelPath, "add");
            LocalHg.hgIn(workDir, repoRelPath, "remove", "dir/b.txt");
            LocalHg.hgIn(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c1");
            String hex1 = LocalHg.hgIn(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            String realStatus = LocalHg.hgIn(workDir, repoRelPath, "status", "--rev", "0", "--rev", "1");
            Map<String, String> expected = new HashMap<>();
            for (String line : realStatus.split("\n")) {
                expected.put(line.substring(2), line.substring(0, 1));
            }
            assertEquals(Map.of("a.txt", "M", "dir/b.txt", "R", "dir/c.txt", "A"), expected,
                    "sanity, combo " + combo);

            HgRepository repo = new HgRepository(hostRepoDir.toFile());

            List<DiffCommand.DiffEntry> diffsByInt = new DiffCommand(repo).setOldRevision(0).setNewRevision(1).call();
            Map<String, DiffCommand.ChangeType> byPathInt = new HashMap<>();
            for (DiffCommand.DiffEntry e : diffsByInt) {
                byPathInt.put(e.getPath(), e.getChangeType());
            }
            assertEquals(Map.of(
                    "a.txt", DiffCommand.ChangeType.MODIFY,
                    "dir/b.txt", DiffCommand.ChangeType.DELETE,
                    "dir/c.txt", DiffCommand.ChangeType.ADD), byPathInt,
                    "classification must match real hg's own status, combo " + combo);

            List<DiffCommand.DiffEntry> diffsByNode = new DiffCommand(repo)
                    .setOldRevision(new NodeId(NodeIdUtil.fromHex(hex0)))
                    .setNewRevision(new NodeId(NodeIdUtil.fromHex(hex1)))
                    .call();
            Map<String, DiffCommand.ChangeType> byPathNode = new HashMap<>();
            for (DiffCommand.DiffEntry e : diffsByNode) {
                byPathNode.put(e.getPath(), e.getChangeType());
            }
            assertEquals(byPathInt, byPathNode, "NodeId- and int-based revision setters must agree, combo " + combo);

            String modifyDiff = diffsByInt.stream()
                    .filter(e -> e.getPath().equals("a.txt")).findFirst().orElseThrow().getDiffContent();
            Path patchFile = workDir.resolve("diff.patch");
            Files.writeString(patchFile, modifyDiff);
            String containerPatchPath = "/repo-root/diff.patch";

            LocalHg.hgIn(workDir, repoRelPath, "update", "-r", "0", "-C");
            LocalHg.hgIn(workDir, repoRelPath, "import", "--no-commit", containerPatchPath);
            String appliedContent = Files.readString(hostRepoDir.resolve("a.txt"));
            String expectedContent = LocalHg.hgIn(workDir, repoRelPath, "cat", "-r", hex1, "a.txt") + "\n";
            assertEquals(expectedContent, appliedContent,
                    "hg4j's MODIFY diff applied via real `hg import` must reproduce the target revision's content, combo " + combo);
        });
    }
}
