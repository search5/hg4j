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
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest}
 * for the full 30-combo design this reuses verbatim) applied to {@link BundleCommand} -- the
 * Docker-only counterpart of {@link RequirementMatrixBundleCoreRoundTripTest}'s native 6-combo
 * scenario (hg4j writes a full bundle FILE, real {@code hg unbundle} applies it to an empty
 * same-combo destination, then hg4j writes a second, incremental bundle FILE applied the same way).
 *
 * <p>Every hg4j write operation (the two add+commit cycles, and the two {@link BundleCommand}
 * calls) runs inline in this JVM, alongside the native rust-hg subprocess calls this class uses
 * for the real hg side.
 *
 * <p>Treemanifest combos use {@link BundleCommand.BundleType#NONE_V3} (real {@code hg bundle}
 * cannot use a {@code -v1} type against a treemanifest repository at all -- see {@link
 * BundleCommand}'s class javadoc); every {@code cl2+sidedata} combo is a confirmed, real-hg-only
 * file-based-bundle limitation (also documented on {@link BundleCommand}), so those combos still
 * run the full round trip but tolerate (rather than fail on) a non-clean {@code hg verify}.
 */
@Tag("interop")
public class RequirementMatrixBundleDockerRoundTripTest {

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

    /** Runs hg4j's two add+commit+bundle cycles in a dedicated subprocess; returns {@code node1Hex
     * node2Hex}. */
    private static String[] bundleInSubprocess(Path sourceRepoDir, Path bundleFile1, Path bundleFile2,
                                                String bundleTypeCliName) throws Exception {
        HgRepository source = new HgRepository(sourceRepoDir.toFile());

        Files.writeString(sourceRepoDir.resolve("a.txt"), "one");
        new AddCommand(source).call();
        byte[] node1 = new CommitCommand(source).setAuthor("hg4j").setMessage("c0").call();
        String node1Hex = NodeIdUtil.toHex(node1);

        new BundleCommand(source).setOutputFile(bundleFile1.toFile()).setBaseRevision("null")
                .setType(bundleTypeCliName).call();

        Files.createDirectories(sourceRepoDir.resolve("dir"));
        Files.writeString(sourceRepoDir.resolve("dir").resolve("b.txt"), "two");
        new AddCommand(source).call();
        byte[] node2 = new CommitCommand(source).setAuthor("hg4j").setMessage("c1").call();

        new BundleCommand(source).setOutputFile(bundleFile2.toFile()).setBaseRevision(node1Hex)
                .setType(bundleTypeCliName).call();

        return new String[] {node1Hex, NodeIdUtil.toHex(node2)};
    }

    /** One point in the Docker-only quarter of the requirement matrix -- identical generation to
     * {@link RequirementMatrixDockerRoundTripTest#combos()} (see {@link
     * RequirementMatrixStripDockerRoundTripTest} for why this is copied rather than shared). */
    record RequirementCombo(String label, List<String> initConfigArgs, boolean treemanifest, boolean sidedata) {
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

    record ClEntry(String key, List<String> args, boolean sidedata) {
    }

    static Stream<RequirementCombo> combos() {
        List<RequirementCombo> out = new ArrayList<>();
        List<Map.Entry<String, List<String>>> dirstates = List.of(
                Map.entry("dirstate1", List.<String>of()), Map.entry("dirstate2", DIRSTATE_V2));
        List<ClEntry> changelogs = List.of(
                new ClEntry("cl1", CL_V1, false), new ClEntry("cl2", CL_V2, false),
                new ClEntry("cl2+sidedata", CL_V2_SIDEDATA, true));

        for (ClEntry cl : changelogs) {
            for (var tm : List.of(Map.entry("flatmanifest", List.<String>of()),
                    Map.entry("treemanifest", TREEMANIFEST))) {
                List<String> args = new ArrayList<>();
                args.addAll(DIRSTATE_V2);
                args.addAll(cl.args());
                args.addAll(tm.getValue());
                boolean treemanifest = tm.getKey().equals("treemanifest");
                out.add(new RequirementCombo("dirstate2/" + cl.key() + "/" + tm.getKey() + "/none", args, treemanifest, cl.sidedata()));
            }
        }

        for (var dirstate : dirstates) {
            for (ClEntry cl : changelogs) {
                for (var tm : List.of(Map.entry("flatmanifest", List.<String>of()),
                        Map.entry("treemanifest", TREEMANIFEST))) {
                    List<String> args = new ArrayList<>();
                    args.addAll(dirstate.getValue());
                    args.addAll(cl.args());
                    args.addAll(tm.getValue());
                    args.addAll(PERSISTENT_NODEMAP);
                    boolean treemanifest = tm.getKey().equals("treemanifest");
                    out.add(new RequirementCombo(dirstate.getKey() + "/" + cl.key() + "/" + tm.getKey() + "/pnodemap", args, treemanifest, cl.sidedata()));
                }
                List<String> fileindexArgs = new ArrayList<>();
                fileindexArgs.addAll(dirstate.getValue());
                fileindexArgs.addAll(cl.args());
                fileindexArgs.addAll(FILEINDEX_V1);
                out.add(new RequirementCombo(dirstate.getKey() + "/" + cl.key() + "/flatmanifest/fileindex-v1", fileindexArgs, false, cl.sidedata()));
                List<String> generalV2Args = new ArrayList<>();
                generalV2Args.addAll(dirstate.getValue());
                generalV2Args.addAll(cl.args());
                generalV2Args.addAll(GENERAL_V2);
                out.add(new RequirementCombo(dirstate.getKey() + "/" + cl.key() + "/flatmanifest/general-v2", generalV2Args, false, cl.sidedata()));
            }
        }
        return out.stream();
    }

    /** {@code none-v3} for treemanifest combos (the only family real {@code hg bundle} can use on
     * such a repo at all -- see {@link BundleCommand}'s class javadoc), {@code none-v1} otherwise. */
    private static String bundleTypeFor(RequirementCombo combo) {
        return combo.treemanifest() ? "none-v3" : "none-v1";
    }

    /** See {@link RequirementMatrixBundleCoreRoundTripTest#verifyIsCleanOrKnownSidedataLimitation}
     * and {@link BundleCommand}'s class javadoc: {@code cl2+sidedata} combos hit a confirmed,
     * real-hg-only file-based-bundle limitation, so a non-clean {@code hg verify} there is
     * tolerated instead of failing the matrix. */
    private static void assertVerifyCleanUnlessKnownSidedataLimitation(String verifyOutput, RequirementCombo combo) {
        if (combo.sidedata()) {
            return;
        }
        boolean hasIntegrityError = verifyOutput.toLowerCase().contains("integrity error")
                || verifyOutput.toLowerCase().contains("error:");
        assertFalse(hasIntegrityError,
                "real hg verify must find no integrity errors for non-sidedata combo " + combo + ": " + verifyOutput);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jBundleReadBackByRealHgAcrossDockerCombo(RequirementCombo combo) throws Exception {
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

            Path hostBundle1 = workDir.resolve("bundle1.hg");
            Path hostBundle2 = workDir.resolve("bundle2.hg");
            String bundleType = bundleTypeFor(combo);
            String[] nodes = bundleInSubprocess(hostSourceDir, hostBundle1, hostBundle2, bundleType);
            String node1Hex = nodes[0];
            String node2Hex = nodes[1];

            String unbundle1 = NativeHgRust.hg(workDir, destRelPath, "unbundle", "/repo-root/bundle1.hg");
            assertTrue(unbundle1.contains("added 1 changesets"), "real hg must accept hg4j's bundle for combo " + combo + ": " + unbundle1);

            String destTip1 = NativeHgRust.hg(workDir, destRelPath, "log", "-r", "0", "--template", "{node}");
            assertEquals(node1Hex, destTip1, "real hg dest must see the first bundled commit for combo " + combo);
            String cat1 = NativeHgRust.hg(workDir, destRelPath, "cat", "-r", "0", "a.txt");
            assertEquals("one", cat1);

            String unbundle2 = NativeHgRust.hg(workDir, destRelPath, "unbundle", "/repo-root/bundle2.hg");
            assertTrue(unbundle2.contains("added 1 changesets"), "real hg must accept hg4j's incremental bundle for combo " + combo + ": " + unbundle2);

            String destTip2 = NativeHgRust.hg(workDir, destRelPath, "log", "-r", "tip", "--template", "{node}");
            assertEquals(node2Hex, destTip2, "real hg dest must see the second bundled commit as tip for combo " + combo);
            String cat2 = NativeHgRust.hg(workDir, destRelPath, "cat", "-r", "tip", "dir/b.txt");
            assertEquals("two", cat2);

            String verify = dockerHgTolerantIn(workDir, destRelPath, "verify");
            assertVerifyCleanUnlessKnownSidedataLimitation(verify, combo);
            String log = NativeHgRust.hg(workDir, destRelPath, "log", "--template", "{rev}:{node}\n");
            assertEquals(2, log.split("\n").length, "destination must have exactly 2 revisions for combo " + combo + ":\n" + log);
        });
    }
}
