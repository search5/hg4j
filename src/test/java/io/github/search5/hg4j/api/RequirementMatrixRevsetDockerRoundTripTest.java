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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest} for
 * the full 30-combo design this reuses verbatim) applied to {@link RevsetCommand} -- the
 * Docker-only counterpart of {@link RequirementMatrixRevsetCoreRoundTripTest}'s native 6-combo
 * scenario (see its javadoc for the full expression list and the {@code heads()} caveat).
 * {@link RevsetCommand} never mutates the repository, so no hg4j write step is needed here.
 */
@Tag("interop")
public class RequirementMatrixRevsetDockerRoundTripTest {

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

    private static Set<String> realHexes(Path workDir, String repoRelPath, String expr) throws Exception {
        String out = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", expr, "--template", "{node}\n");
        Set<String> result = new HashSet<>();
        for (String line : out.split("\n")) {
            if (!line.isBlank()) {
                result.add(line);
            }
        }
        return result;
    }

    private static Set<String> hg4jHexes(HgRepository repo, String expr) throws Exception {
        return new HashSet<>(new RevsetCommand(repo).setExpression(expr).call());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void revsetExpressionsAcrossDockerCombo(RequirementCombo combo) throws Exception {
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

            Files.writeString(hostRepoDir.resolve("a.txt"), "a");
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "Alice <a@x.com>", "-m", "c0");
            String hex0 = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", "0", "--template", "{node}");

            NativeHgRust.hg(workDir, repoRelPath, "branch", "feature");
            Files.writeString(hostRepoDir.resolve("b.txt"), "b");
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "Bob <b@x.com>", "-m", "c1");
            String hex1 = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", "1", "--template", "{node}");

            NativeHgRust.hg(workDir, repoRelPath, "update", "0");
            NativeHgRust.hg(workDir, repoRelPath, "branch", "default");
            Files.writeString(hostRepoDir.resolve("c.txt"), "c");
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "Alice <a@x.com>", "-m", "c2");
            String hex2 = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", "2", "--template", "{node}");

            Files.writeString(hostRepoDir.resolve(".hgtags"), hex0 + " v1.0\n");
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "Alice <a@x.com>", "-m", "c3 tag");
            String hex3 = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", "3", "--template", "{node}");

            HgRepository repo = new HgRepository(hostRepoDir.toFile());

            record Case(String label, String hg4jExpr, String realHgExpr) {
            }
            List<Case> cases = List.of(
                    new Case("all()", "all()", "all()"),
                    new Case("author(Alice)", "author(Alice)", "author(Alice)"),
                    new Case("author(Bob)", "author(Bob)", "author(Bob)"),
                    new Case("branch(feature)", "branch(feature)", "branch(feature)"),
                    new Case("branch(default)", "branch(default)", "branch(default)"),
                    new Case("parents(1)", "parents(1)", "parents(1)"),
                    new Case("ancestors(2)", "ancestors(2)", "ancestors(2)"),
                    new Case("descendants(0)", "descendants(0)", "descendants(0)"),
                    new Case("tag(v1.0)", "tag(v1.0)", "tag(v1.0)"),
                    new Case("0 and author(Alice)", "0 and author(Alice)", "0 and author(Alice)"),
                    new Case("author(Alice) or author(Bob)", "author(Alice) or author(Bob)", "author(Alice) or author(Bob)"),
                    new Case("not author(Bob)", "not author(Bob)", "not author(Bob)"),
                    new Case("bare revision 2", "2", "2"),
                    new Case("full hex of rev1", hex1, hex1),
                    new Case("heads()", "heads()", "heads(all())")
            );

            for (Case c : cases) {
                Set<String> expected = realHexes(workDir, repoRelPath, c.realHgExpr());
                Set<String> actual = hg4jHexes(repo, c.hg4jExpr());
                assertEquals(expected, actual, "revset '" + c.label() + "', combo " + combo);
            }

            assertEquals(Set.of(hex1, hex3), realHexes(workDir, repoRelPath, "heads(all())"), "sanity, combo " + combo);
        });
    }
}
