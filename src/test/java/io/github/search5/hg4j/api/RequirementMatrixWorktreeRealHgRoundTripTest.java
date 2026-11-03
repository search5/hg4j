package io.github.search5.hg4j.api;

import io.github.search5.hg4j.testsupport.LocalHg;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Map;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixRealHgRoundTripTest}
 * for the full 30-combo design this reuses verbatim) applied to {@link WorktreeCommand} -- the
 * Docker-only counterpart of {@link RequirementMatrixWorktreeCoreRoundTripTest}'s single scenario
 * (share + actual checkout, "shared" requires marker, real hg reading the result back), re-verified
 * by real hg running inside {@code hg-rust-7.2.4}.
 *
 * <p>Each case gets its own fresh, short-lived container (never a class-shared one), matching
 * {@link RequirementMatrixMergeRealHgRoundTripTest}/{@link RequirementMatrixStripRealHgRoundTripTest}.
 * {@link WorktreeCommand} never appends a revlog revision on its own (see its class javadoc), so --
 * unlike most other requirement-matrix Docker suites in this package -- there is no revlog-write
 * corruption risk from interleaving hg4j calls with {@code docker exec}/{@code docker run} child
 * processes; {@link RequirementMatrixWorktreeHelperMain} is still used purely for pattern parity.
 */
@Tag("interop")
public class RequirementMatrixWorktreeRealHgRoundTripTest {





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

    @BeforeAll
    static void checkLocalHg() {
        Assumptions.assumeTrue(LocalHg.isAvailable(), LocalHg::unavailableReason);
    }

    @FunctionalInterface
    private interface FreshWorkDirTest {
        void run(Path workDir) throws Exception;
    }

    private static void withFreshWorkDir(FreshWorkDirTest test) throws Exception {
        Path workDir = Files.createTempDirectory("hg4j-docker-worktree-matrix").toRealPath();
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


    /** Runs hg4j's {@link WorktreeCommand} in a dedicated subprocess -- see the class javadoc for
     * why this isn't strictly required for correctness here. */
    private static void worktreeInSubprocess(File mainRepoDir, File newWorktreeDir) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        runHost(javaBin, "-cp", classpath, RequirementMatrixWorktreeHelperMain.class.getName(),
                mainRepoDir.getAbsolutePath(), newWorktreeDir.getAbsolutePath());
    }

    /** One point in the Docker-only quarter of the requirement matrix -- identical generation to
     * {@link RequirementMatrixRealHgRoundTripTest#combos()} (see {@link
     * RequirementMatrixStripRealHgRoundTripTest} for why this is copied rather than shared). */
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
    public void hg4jWorktreeSharesAndChecksOutMatchingRealHgAcrossDockerCombo(RequirementCombo combo) throws Exception {
        withFreshWorkDir(workDir -> {
            String repoRelPath = "main";
            Path hostMainDir = workDir.resolve(repoRelPath);
            Files.createDirectories(hostMainDir);

            List<String> initArgs = new ArrayList<>(List.of("init", "."));
            for (String c : combo.initConfigArgs()) {
                initArgs.add("--config");
                initArgs.add(c);
            }
            LocalHg.hgIn(workDir, repoRelPath, initArgs.toArray(new String[0]));

            Files.writeString(hostMainDir.resolve("root.txt"), "root content\n");
            Files.createDirectories(hostMainDir.resolve("sub"));
            Files.writeString(hostMainDir.resolve("sub/nested.txt"), "nested content\n");
            LocalHg.hgIn(workDir, repoRelPath, "add");
            LocalHg.hgIn(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c0");
            String tipHex = LocalHg.hgIn(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            File hostWorktreeDir = workDir.resolve("worktree").toFile();
            worktreeInSubprocess(hostMainDir.toFile(), hostWorktreeDir);

            assertEquals("root content\n", Files.readString(hostWorktreeDir.toPath().resolve("root.txt")),
                    "worktree must be checked out to the shared store's tip for combo " + combo);
            assertEquals("nested content\n", Files.readString(hostWorktreeDir.toPath().resolve("sub/nested.txt")),
                    "worktree checkout must include nested-subdirectory files for combo " + combo);

            String worktreeRequires = Files.readString(hostWorktreeDir.toPath().resolve(".hg/requires"), StandardCharsets.UTF_8);
            assertTrue(worktreeRequires.contains("shared"), "worktree requires must carry the shared marker for combo " + combo + ": " + worktreeRequires);

            assertEquals(hostMainDir.toFile().getCanonicalPath() + "/.hg",
                    Files.readString(hostWorktreeDir.toPath().resolve(".hg/sharedpath"), StandardCharsets.UTF_8),
                    "sharedpath must point back at the main repository's .hg directory (host-absolute view) for combo " + combo);

            // Real hg now runs on the host too, so it resolves the absolute host path hg4j wrote into
            // sharedpath directly (the earlier container mount-point translation is gone).
            String worktreeRelPath = "worktree";

            assertEquals(tipHex, LocalHg.hgIn(workDir, worktreeRelPath, "log", "-r", ".", "--template", "{node}"),
                    "real hg reading the worktree must agree it is checked out to the main repo's tip for combo " + combo);
            assertEquals("", LocalHg.hgIn(workDir, worktreeRelPath, "status"), "worktree must be a clean working copy for combo " + combo);
        });
    }
}
