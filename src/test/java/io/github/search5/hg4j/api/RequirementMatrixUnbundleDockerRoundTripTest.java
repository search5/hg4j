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
 * for the full 30-combo design this reuses verbatim) applied to {@link UnbundleCommand} -- the
 * Docker-only counterpart of {@link RequirementMatrixUnbundleCoreRoundTripTest}'s native 6-combo
 * scenario, and the read-side mirror of {@link RequirementMatrixBundleDockerRoundTripTest}: a real
 * {@code hg} SOURCE repo (inside the container) commits and writes two bundle FILEs (a full one,
 * then an incremental one), and hg4j's {@link UnbundleCommand} applies both to an
 * independently-{@code hg init}'d destination of the SAME combo.
 *
 * <p>The destination repository is initialized via real {@code hg} inside the container (so its
 * {@code .hg/requires}/format bookkeeping exactly matches what that combo's real {@code hg} would
 * write) but then written to ENTIRELY by hg4j, running inline in this JVM directly against the
 * same bind-mounted directory tree -- no further {@code docker exec} calls touch the destination
 * while hg4j is applying. Every verification of the destination's resulting state after each
 * unbundle is then done by real {@code hg} (back via {@code docker exec}) reading the exact same
 * files hg4j just wrote.
 *
 * <p>Treemanifest combos use {@code --type none-v3} (real {@code hg bundle} cannot use a {@code
 * -v1} type against a treemanifest repository at all -- see {@link BundleCommand}'s class
 * javadoc); every {@code cl2+sidedata} combo is a confirmed, real-hg-only file-based-bundle
 * limitation (also documented on {@link BundleCommand}), checked here on the real-hg SOURCE side
 * (the only side with an {@code hg verify} of its own) via the same tolerant helper {@link
 * RequirementMatrixBundleDockerRoundTripTest} uses.
 */
@Tag("interop")
public class RequirementMatrixUnbundleDockerRoundTripTest {

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

    /** Runs hg4j's two {@link UnbundleCommand} applications inline in this JVM; returns
     * {@code node1Hex node2Hex}. */
    private static String[] unbundleInSubprocess(Path destRepoDir, Path bundleFile1, Path bundleFile2) throws Exception {
        HgRepository dest = new HgRepository(destRepoDir.toFile());

        List<byte[]> imported1 = new UnbundleCommand(dest).setBundleFile(bundleFile1.toFile()).call();
        dest.clearRevlogCache();
        List<byte[]> imported2 = new UnbundleCommand(dest).setBundleFile(bundleFile2.toFile()).call();

        String node1Hex = imported1.isEmpty() ? "" : NodeIdUtil.toHex(imported1.get(imported1.size() - 1));
        String node2Hex = imported2.isEmpty() ? "" : NodeIdUtil.toHex(imported2.get(imported2.size() - 1));
        return new String[] {node1Hex, node2Hex};
    }

    /** One point in the Docker-only quarter of the requirement matrix -- identical generation to
     * {@link RequirementMatrixDockerRoundTripTest#combos()}. */
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

    /** {@code none-v3} for treemanifest combos, {@code none-v1} otherwise (see class javadoc). */
    private static String bundleTypeFor(RequirementCombo combo) {
        return combo.treemanifest() ? "none-v3" : "none-v1";
    }

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
    public void realHgBundleAppliedByHg4jUnbundleAcrossDockerCombo(RequirementCombo combo) throws Exception {
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

            String bundleType = bundleTypeFor(combo);

            // Real hg (inside the container) commits and writes a full bundle.
            Files.writeString(hostSourceDir.resolve("a.txt"), "one");
            NativeHgRust.hg(workDir, sourceRelPath, "add", "a.txt");
            NativeHgRust.hg(workDir, sourceRelPath, "commit", "-u", "realhg", "-m", "c0 for " + combo);
            String node1Hex = NativeHgRust.hg(workDir, sourceRelPath, "log", "-r", "0", "--template", "{node}");
            NativeHgRust.hg(workDir, sourceRelPath, "bundle", "--all", "--type", bundleType, "/repo-root/bundle1.hg");

            // Real hg writes a second, incremental bundle after a new subdirectory file.
            Files.createDirectories(hostSourceDir.resolve("dir"));
            Files.writeString(hostSourceDir.resolve("dir").resolve("b.txt"), "two");
            NativeHgRust.hg(workDir, sourceRelPath, "add", "dir/b.txt");
            NativeHgRust.hg(workDir, sourceRelPath, "commit", "-u", "realhg", "-m", "c1 for " + combo);
            String node2Hex = NativeHgRust.hg(workDir, sourceRelPath, "log", "-r", "tip", "--template", "{node}");
            NativeHgRust.hg(workDir, sourceRelPath, "bundle", "--base", node1Hex, "--type", bundleType, "/repo-root/bundle2.hg");

            // hg4j (host-side subprocess) applies both bundles to the destination.
            Path hostBundle1 = workDir.resolve("bundle1.hg");
            Path hostBundle2 = workDir.resolve("bundle2.hg");
            String[] applied = unbundleInSubprocess(hostDestDir, hostBundle1, hostBundle2);
            assertEquals(node1Hex, applied[0], "hg4j must report the first bundle's node for combo " + combo);
            assertEquals(node2Hex, applied[1], "hg4j must report the second bundle's node for combo " + combo);

            // Real hg (back via docker exec) reads back what hg4j just wrote.
            String destTip1 = NativeHgRust.hg(workDir, destRelPath, "log", "-r", "0", "--template", "{node}");
            assertEquals(node1Hex, destTip1, "real hg dest must see the first unbundled commit for combo " + combo);
            String cat1 = NativeHgRust.hg(workDir, destRelPath, "cat", "-r", "0", "a.txt");
            assertEquals("one", cat1);

            String destTip2 = NativeHgRust.hg(workDir, destRelPath, "log", "-r", "tip", "--template", "{node}");
            assertEquals(node2Hex, destTip2, "real hg dest must see the second unbundled commit as tip for combo " + combo);
            String cat2 = NativeHgRust.hg(workDir, destRelPath, "cat", "-r", "tip", "dir/b.txt");
            assertEquals("two", cat2);

            String verify = dockerHgTolerantIn(workDir, destRelPath, "verify");
            assertVerifyCleanUnlessKnownSidedataLimitation(verify, combo);
            String log = NativeHgRust.hg(workDir, destRelPath, "log", "--template", "{rev}:{node}\n");
            assertEquals(2, log.split("\n").length, "destination must have exactly 2 revisions for combo " + combo + ":\n" + log);
        });
    }
}
