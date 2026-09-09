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
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest}
 * for the full 30-combo design this reuses verbatim) applied to {@link InitCommand} itself -- the
 * Docker-only counterpart of {@link RequirementMatrixInitCoreRoundTripTest}'s native 6-combo
 * scenario, covering dirstate-v2 and all three mutually-exclusive storage-extensions
 * (persistent-nodemap / fileindex-v1 / general-v2), none of which real host-native Python hg can
 * create ("without associated fast implementation").
 *
 * <p>Each case gets its own fresh, short-lived container (never a class-shared one), matching
 * {@link RequirementMatrixBackoutDockerRoundTripTest}. hg4j's own init+add+commit write runs
 * inline in this JVM, alongside the native rust-hg subprocess calls this class uses for the real
 * hg side.
 */
@Tag("interop")
public class RequirementMatrixInitDockerRoundTripTest {

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


    /** Runs hg4j's own init+add+commit in a dedicated subprocess; returns the resulting commit's
     * hex. */
    private static String initAndCommitInSubprocess(Path repoDir, RequirementCombo combo) throws Exception {
        HgRepository repo = new InitCommand()
                .setDirectory(repoDir.toFile())
                .setDirstateV2(combo.dirstateV2())
                .setChangelogV2(combo.changelogV2())
                .setSidedataCopies(combo.sidedataCopies())
                .setTreemanifest(combo.treemanifest())
                .setPersistentNodemap(combo.persistentNodemap())
                .setFileIndexV1(combo.fileIndexV1())
                .setGeneralV2(combo.generalV2())
                .call();

        Files.createDirectories(repoDir.resolve("sub"));
        Files.writeString(repoDir.resolve("sub/a.txt"), "original\n", StandardCharsets.UTF_8);
        new AddCommand(repo).addFile("sub/a.txt").call();
        byte[] c0Node = new CommitCommand(repo).setAuthor("hg4j").setMessage("c0").call();
        return NodeIdUtil.toHex(c0Node);
    }

    /** One point in the Docker-only quarter of the requirement matrix -- identical generation to
     * {@link RequirementMatrixDockerRoundTripTest#combos()} (see {@link
     * RequirementMatrixBackoutDockerRoundTripTest} for why this is copied rather than shared), plus
     * the exact set of requires tokens real hg itself writes for each combo (independently measured
     * live via {@code hg-rust-7.2.4}, 2026-09-05, and asserted against below both as what hg4j wrote
     * and as what real hg's own {@code debugrequires} reports back). */
    record RequirementCombo(String label, boolean dirstateV2, boolean changelogV2, boolean sidedataCopies,
                             boolean treemanifest, boolean persistentNodemap, boolean fileIndexV1,
                             boolean generalV2, List<String> expectedRequires) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static List<String> baseRequires(boolean dirstateV2, boolean fileIndexV1OrGeneralV2, boolean generalV2) {
        List<String> r = new ArrayList<>();
        if (!fileIndexV1OrGeneralV2) {
            r.add("dotencode");
            r.add("fncache");
        }
        r.add("generaldelta");
        if (!generalV2) {
            r.add("revlogv1");
        }
        r.add("store");
        if (dirstateV2) {
            r.add("dirstate-v2");
        }
        return r;
    }

    static Stream<RequirementCombo> combos() {
        List<RequirementCombo> out = new ArrayList<>();
        record Cl(String label, boolean cl2, boolean sidedata) {
        }
        List<Cl> changelogs = List.of(new Cl("cl1", false, false), new Cl("cl2", true, false),
                new Cl("cl2+sidedata", true, true));
        List<Map.Entry<String, Boolean>> dirstates = List.of(
                Map.entry("dirstate1", false), Map.entry("dirstate2", true));

        // dirstate-v2 x changelog x manifest, storage-ext = none (cells 7-12)
        for (Cl cl : changelogs) {
            for (var tm : List.of(Map.entry("flatmanifest", false), Map.entry("treemanifest", true))) {
                List<String> expected = baseRequires(true, false, false);
                if (cl.cl2()) expected.add("exp-changelog-v2");
                if (cl.sidedata()) expected.add("exp-copies-sidedata-changeset");
                if (tm.getValue()) expected.add("treemanifest");
                out.add(new RequirementCombo("dirstate2/" + cl.label() + "/" + tm.getKey() + "/none",
                        true, cl.cl2(), cl.sidedata(), tm.getValue(), false, false, false, expected));
            }
        }

        // dirstate(v1/v2) x changelog x manifest x storage-ext(pnodemap/fileindex-v1/general-v2)
        for (var dirstate : dirstates) {
            for (Cl cl : changelogs) {
                for (var tm : List.of(Map.entry("flatmanifest", false), Map.entry("treemanifest", true))) {
                    List<String> expected = baseRequires(dirstate.getValue(), false, false);
                    if (cl.cl2()) expected.add("exp-changelog-v2");
                    if (cl.sidedata()) expected.add("exp-copies-sidedata-changeset");
                    if (tm.getValue()) expected.add("treemanifest");
                    expected.add("persistent-nodemap");
                    out.add(new RequirementCombo(dirstate.getKey() + "/" + cl.label() + "/" + tm.getKey() + "/pnodemap",
                            dirstate.getValue(), cl.cl2(), cl.sidedata(), tm.getValue(), true, false, false, expected));
                }
                List<String> fiExpected = baseRequires(dirstate.getValue(), true, false);
                if (cl.cl2()) fiExpected.add("exp-changelog-v2");
                if (cl.sidedata()) fiExpected.add("exp-copies-sidedata-changeset");
                fiExpected.add("persistent-nodemap");
                fiExpected.add("fileindex-v1");
                out.add(new RequirementCombo(dirstate.getKey() + "/" + cl.label() + "/flatmanifest/fileindex-v1",
                        dirstate.getValue(), cl.cl2(), cl.sidedata(), false, false, true, false, fiExpected));

                List<String> gvExpected = baseRequires(dirstate.getValue(), true, true);
                if (cl.cl2()) gvExpected.add("exp-changelog-v2");
                if (cl.sidedata()) gvExpected.add("exp-copies-sidedata-changeset");
                gvExpected.add("persistent-nodemap");
                gvExpected.add("fileindex-v1");
                gvExpected.add("exp-revlogv2.2");
                out.add(new RequirementCombo(dirstate.getKey() + "/" + cl.label() + "/flatmanifest/general-v2",
                        dirstate.getValue(), cl.cl2(), cl.sidedata(), false, false, false, true, gvExpected));
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jInitAndFirstCommitAcceptedByRealHgAcrossDockerCombo(RequirementCombo combo) throws Exception {
        NativeHgRust.withFreshWorkDir("hg4j-native-matrix", (workDir) -> {
            String repoRelPath = "repo";
            Path hostRepoDir = workDir.resolve(repoRelPath);

            String c0Hex = initAndCommitInSubprocess(hostRepoDir, combo);

            List<String> actualRequires = Files.readAllLines(hostRepoDir.resolve(".hg/requires"));
            assertEquals(new TreeSet<>(combo.expectedRequires()), new TreeSet<>(actualRequires),
                    "InitCommand must write exactly real hg's own requirement tokens for combo " + combo);

            String verify = NativeHgRust.hg(workDir, repoRelPath, "verify");
            assertFalse(verify.toLowerCase().contains("integrity error") || verify.toLowerCase().contains("error:"),
                    "real hg (Docker) verify must find no integrity errors in an hg4j-created repo for combo "
                            + combo + ": " + verify);

            assertEquals(c0Hex, NativeHgRust.hg(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}"),
                    "real hg (Docker) must see hg4j's own commit node for combo " + combo);
            assertEquals("original", NativeHgRust.hg(workDir, repoRelPath, "cat", "-r", c0Hex, "sub/a.txt"));

            String realRequires = NativeHgRust.hg(workDir, repoRelPath, "debugrequires");
            List<String> realRequiresLines = new ArrayList<>();
            for (String line : realRequires.split("\n")) {
                if (!line.isBlank()) {
                    realRequiresLines.add(line.trim());
                }
            }
            assertEquals(new TreeSet<>(combo.expectedRequires()), new TreeSet<>(realRequiresLines),
                    "real hg (Docker) debugrequires must agree with what InitCommand wrote for combo " + combo);

            // Real hg (Docker) must also be able to WRITE further history on top of the
            // hg4j-created store.
            Files.writeString(hostRepoDir.resolve("sub/a.txt"), "changed by real hg\n");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "realhg", "-m", "c1");

            String verify2 = NativeHgRust.hg(workDir, repoRelPath, "verify");
            assertFalse(verify2.toLowerCase().contains("integrity error") || verify2.toLowerCase().contains("error:"),
                    "real hg (Docker) verify must still find no integrity errors after its own commit for combo "
                            + combo + ": " + verify2);
        });
    }
}
