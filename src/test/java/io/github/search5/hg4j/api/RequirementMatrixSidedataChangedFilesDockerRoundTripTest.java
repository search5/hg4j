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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest} for
 * the full 30-combo design this reuses verbatim) applied to {@link SidedataChangedFilesCommand} --
 * the Docker-only counterpart of {@link RequirementMatrixSidedataChangedFilesCoreRoundTripTest}'s
 * native 6-combo scenario (see its javadoc for the full empty-vs-populated split rationale). Of
 * particular interest here: the {@code dirstate2}/{@code pnodemap}/{@code fileindex-v1}/{@code
 * general-v2} storage extensions combined with {@code cl2+sidedata} -- none of those storage
 * extensions change how changelog-v2 sidedata itself is laid out, but this is the first time they
 * are exercised together with real sidedata content by any test in this suite. No hg4j write step
 * is needed here: {@link SidedataChangedFilesCommand} never mutates the repository.
 */
@Tag("interop")
public class RequirementMatrixSidedataChangedFilesDockerRoundTripTest {

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
     * {@link RequirementMatrixDockerRoundTripTest#combos()}, annotated with whether this cell's
     * changelog family is {@code cl2+sidedata} (the only family that ever writes SD_FILES). */
    record RequirementCombo(String label, List<String> initConfigArgs, boolean sidedataExpected) {
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
                out.add(new RequirementCombo("dirstate2/" + cl.getKey() + "/" + tm.getKey() + "/none", args,
                        cl.getKey().equals("cl2+sidedata")));
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
                    out.add(new RequirementCombo(dirstate.getKey() + "/" + cl.getKey() + "/" + tm.getKey() + "/pnodemap", args,
                            cl.getKey().equals("cl2+sidedata")));
                }
                List<String> fileindexArgs = new ArrayList<>();
                fileindexArgs.addAll(dirstate.getValue());
                fileindexArgs.addAll(cl.getValue());
                fileindexArgs.addAll(FILEINDEX_V1);
                out.add(new RequirementCombo(dirstate.getKey() + "/" + cl.getKey() + "/flatmanifest/fileindex-v1", fileindexArgs,
                        cl.getKey().equals("cl2+sidedata")));
                List<String> generalV2Args = new ArrayList<>();
                generalV2Args.addAll(dirstate.getValue());
                generalV2Args.addAll(cl.getValue());
                generalV2Args.addAll(GENERAL_V2);
                out.add(new RequirementCombo(dirstate.getKey() + "/" + cl.getKey() + "/flatmanifest/general-v2", generalV2Args,
                        cl.getKey().equals("cl2+sidedata")));
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void sidedataChangedFilesAcrossDockerCombo(RequirementCombo combo) throws Exception {
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

            Files.createDirectories(hostRepoDir.resolve("dir"));
            Files.writeString(hostRepoDir.resolve("a.txt"), "a");
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c0");

            NativeHgRust.hg(workDir, repoRelPath, "copy", "a.txt", "dir/b.txt");
            Files.writeString(hostRepoDir.resolve("c.txt"), "c");
            NativeHgRust.hg(workDir, repoRelPath, "add", "c.txt");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c1");

            HgRepository repo = new HgRepository(hostRepoDir.toFile());

            ChangingFiles rev0 = new SidedataChangedFilesCommand(repo).setRevision(0).call();
            ChangingFiles rev1 = new SidedataChangedFilesCommand(repo).setRevision(1).call();

            if (!combo.sidedataExpected()) {
                assertEquals("", NativeHgRust.hg(workDir, repoRelPath, "debugchangedfiles", "0"), "sanity, combo " + combo);
                assertEquals("", NativeHgRust.hg(workDir, repoRelPath, "debugchangedfiles", "1"), "sanity, combo " + combo);

                assertTrue(rev0.getCopiedFromP1().isEmpty(), "combo " + combo);
                assertTrue(rev1.getCopiedFromP1().isEmpty(), "combo " + combo);
                assertNull(rev1.getCopySource("dir/b.txt"), "combo " + combo);
            } else {
                String realRev0 = NativeHgRust.hg(workDir, repoRelPath, "debugchangedfiles", "0");
                String realRev1 = NativeHgRust.hg(workDir, repoRelPath, "debugchangedfiles", "1");
                assertEquals("added      : a.txt, ;", realRev0, "sanity, combo " + combo);
                assertEquals("added      : c.txt, ;\nadded    p1: dir/b.txt, a.txt;", realRev1, "sanity, combo " + combo);

                assertEquals(Set.of("a.txt"), rev0.getAdded(), "combo " + combo);
                assertEquals(Set.of("dir/b.txt", "c.txt"), rev1.getAdded(), "combo " + combo);
                assertEquals("a.txt", rev1.getCopySource("dir/b.txt"), "combo " + combo);
                assertNull(rev1.getCopySource("c.txt"), "combo " + combo);
            }
        });
    }
}
