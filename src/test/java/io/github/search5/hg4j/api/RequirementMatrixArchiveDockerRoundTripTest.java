package io.github.search5.hg4j.api;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
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
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.FileInputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/**
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest}
 * for the full 30-combo design this reuses verbatim) applied to {@link ArchiveCommand} -- the
 * Docker-only counterpart of {@link RequirementMatrixArchiveCoreRoundTripTest}'s two scenarios
 * (structural directory/zip/tar.gz agreement including the executable bit, a real symlink, and a
 * nested-subdirectory treemanifest file; and the own-tag {@code tag:} form of {@code
 * .hg_archival.txt}), re-verified by real hg running inside {@code hg-rust-7.2.4}.
 *
 * <p>Each case gets its own fresh, short-lived container (never a class-shared one), matching
 * {@link RequirementMatrixMergeDockerRoundTripTest}/{@link RequirementMatrixStripDockerRoundTripTest}.
 * {@link ArchiveCommand} never mutates the repository (see its class javadoc), so -- unlike most
 * other requirement-matrix Docker suites in this package -- there is no revlog-write corruption
 * risk from interleaving hg4j calls with {@code docker exec}/{@code docker run} child processes;
 * {@link RequirementMatrixArchiveHelperMain} is still used (one subprocess call per combo) purely
 * for pattern parity, not correctness.
 */
@Tag("interop")
public class RequirementMatrixArchiveDockerRoundTripTest {

    private static final String IMAGE = "localhost/hg-rust-7.2.4";
    private static String hostUidGid;
    private static boolean dockerReady = false;

    @BeforeAll
    static void checkDocker() throws Exception {
        dockerReady = isDockerAvailable() && isImageAvailable();
        Assumptions.assumeTrue(dockerReady,
                "Docker (or the localhost/hg-rust-7.2.4 image) is not available. Skipping the whole class.");
        hostUidGid = runHost("id", "-u").trim() + ":" + runHost("id", "-g").trim();
    }

    private static boolean isDockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isImageAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "image", "inspect", IMAGE).redirectErrorStream(true).start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
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

    @FunctionalInterface
    private interface FreshContainerTest {
        void run(String containerName, Path workDir) throws Exception;
    }

    private static void withFreshContainer(FreshContainerTest test) throws Exception {
        Path workDir = Files.createTempDirectory("hg4j-docker-archive-matrix").toRealPath();
        String containerName = "hg4j-reqmatrix-archive-" + UUID.randomUUID().toString().substring(0, 8);
        runHost("docker", "run", "-d", "--rm", "--name", containerName,
                "-v", workDir + ":/repo-root", IMAGE, "sleep", "infinity");
        try {
            Exception last = null;
            for (int i = 0; i < 20; i++) {
                try {
                    runHost("docker", "exec", "--user", hostUidGid, containerName, "hg", "--version");
                    last = null;
                    break;
                } catch (Exception e) {
                    last = e;
                    Thread.sleep(250);
                }
            }
            if (last != null) {
                throw new AssertionError("Container " + containerName + " never became ready", last);
            }
            test.run(containerName, workDir);
        } finally {
            try {
                new ProcessBuilder("docker", "stop", containerName).redirectErrorStream(true).start().waitFor(15, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // best effort
            }
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

    private static String dockerHgIn(String container, String repoRelPath, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("docker", "exec", "--user", hostUidGid,
                "-w", "/repo-root/" + repoRelPath, container, "hg"));
        cmd.addAll(Arrays.asList(args));
        return runHost(cmd.toArray(new String[0])).trim();
    }

    /** Runs hg4j's archive (to all three destinations at once) in a dedicated subprocess -- see
     * the class javadoc for why this isn't strictly required for correctness here. */
    private static void archiveInSubprocess(Path repoDir, String revision, File filesDest, File zipDest, File tarGzDest) throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        runHost(javaBin, "-cp", classpath, RequirementMatrixArchiveHelperMain.class.getName(),
                repoDir.toString(), revision, filesDest.getAbsolutePath(), zipDest.getAbsolutePath(), tarGzDest.getAbsolutePath());
    }

    private static Set<String> relativeFileListing(File dir) throws Exception {
        Set<String> out = new TreeSet<>();
        Files.walk(dir.toPath()).filter(Files::isRegularFile).forEach(p -> out.add(dir.toPath().relativize(p).toString().replace('\\', '/')));
        Files.walk(dir.toPath()).filter(Files::isSymbolicLink).forEach(p -> out.add(dir.toPath().relativize(p).toString().replace('\\', '/')));
        return out;
    }

    private static Set<String> zipNames(ZipFile zf) {
        Set<String> names = new TreeSet<>();
        Collections.list(zf.getEntries()).forEach(e -> names.add(e.getName()));
        return names;
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
    public void hg4jStructuralArchiveMatchesRealHgAcrossDockerCombo(RequirementCombo combo) throws Exception {
        withFreshContainer((containerName, workDir) -> {
            String repoRelPath = "repo";
            Path hostRepoDir = workDir.resolve(repoRelPath);
            Files.createDirectories(hostRepoDir);

            List<String> initArgs = new ArrayList<>(List.of("init", "."));
            for (String c : combo.initConfigArgs()) {
                initArgs.add("--config");
                initArgs.add(c);
            }
            dockerHgIn(containerName, repoRelPath, initArgs.toArray(new String[0]));

            Files.writeString(hostRepoDir.resolve("root.txt"), "root content\n");
            Files.writeString(hostRepoDir.resolve("exec.sh"), "#!/bin/sh\necho hi\n");
            hostRepoDir.resolve("exec.sh").toFile().setExecutable(true, false);
            Files.createSymbolicLink(hostRepoDir.resolve("link.txt"), Path.of("root.txt"));
            Files.createDirectories(hostRepoDir.resolve("sub"));
            Files.writeString(hostRepoDir.resolve("sub/nested.txt"), "nested content\n");
            dockerHgIn(containerName, repoRelPath, "add");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "c0");
            String tipHex = dockerHgIn(containerName, repoRelPath, "log", "-r", ".", "--template", "{node}");

            // hg4j's outputs below use the SAME basenames as the oracle (just a different parent
            // directory) so both sides compute the identical default prefix (real hg's own
            // basename-minus-suffix rule) -- proving hg4j's prefix computation agrees with real
            // hg's, not merely that it's internally self-consistent.
            Files.createDirectories(workDir.resolve("oracle"));
            Files.createDirectories(workDir.resolve("hg4j"));

            File oracleDir = workDir.resolve("oracle/archive-dir").toFile();
            dockerHgIn(containerName, repoRelPath, "archive", "-t", "files", "/repo-root/oracle/archive-dir");
            File oracleZip = workDir.resolve("oracle/archive.zip").toFile();
            dockerHgIn(containerName, repoRelPath, "archive", "-t", "zip", "/repo-root/oracle/archive.zip");
            File oracleTarGz = workDir.resolve("oracle/archive.tar.gz").toFile();
            dockerHgIn(containerName, repoRelPath, "archive", "-t", "tgz", "/repo-root/oracle/archive.tar.gz");

            File hg4jDir = workDir.resolve("hg4j/archive-dir").toFile();
            File hg4jZip = workDir.resolve("hg4j/archive.zip").toFile();
            File hg4jTarGz = workDir.resolve("hg4j/archive.tar.gz").toFile();
            archiveInSubprocess(hostRepoDir, tipHex, hg4jDir, hg4jZip, hg4jTarGz);

            // Directory output.
            Set<String> oracleEntries = relativeFileListing(oracleDir);
            assertEquals(oracleEntries, relativeFileListing(hg4jDir), "directory archive member set must match real hg for combo " + combo);
            for (String rel : oracleEntries) {
                Path op = oracleDir.toPath().resolve(rel);
                Path hp = hg4jDir.toPath().resolve(rel);
                if (Files.isSymbolicLink(op)) {
                    assertTrue(Files.isSymbolicLink(hp), rel + " must be a real symlink for combo " + combo);
                    assertEquals(Files.readSymbolicLink(op), Files.readSymbolicLink(hp), rel + " symlink target must match for combo " + combo);
                } else {
                    assertEquals(Files.readString(op), Files.readString(hp), rel + " content must match for combo " + combo);
                    if (!rel.equals(".hg_archival.txt")) {
                        assertEquals(Files.isExecutable(op), Files.isExecutable(hp), rel + " executable bit must match for combo " + combo);
                    } else {
                        assertEquals(Files.readString(op), Files.readString(hp), ".hg_archival.txt must match real hg for combo " + combo);
                    }
                }
            }

            // Zip output.
            try (ZipFile oz = new ZipFile(oracleZip); ZipFile hz = new ZipFile(hg4jZip)) {
                Set<String> oracleNames = zipNames(oz);
                assertEquals(oracleNames, zipNames(hz), "zip member set must match real hg for combo " + combo);
                String archivalName = oracleNames.stream().filter(n -> n.endsWith(".hg_archival.txt")).findFirst().orElseThrow();
                String prefix = archivalName.substring(0, archivalName.length() - ".hg_archival.txt".length());
                for (String name : oracleNames) {
                    if (name.endsWith("link.txt")) {
                        continue;
                    }
                    ZipArchiveEntry oe = oz.getEntry(name);
                    ZipArchiveEntry he = hz.getEntry(name);
                    byte[] oc = oz.getInputStream(oe).readAllBytes();
                    byte[] hc = hz.getInputStream(he).readAllBytes();
                    assertEquals(new String(oc, StandardCharsets.UTF_8), new String(hc, StandardCharsets.UTF_8),
                            name + " zip content must match for combo " + combo);
                    if (!name.equals(prefix + ".hg_archival.txt")) {
                        assertEquals(((oe.getUnixMode() >> 6) & 0x1), ((he.getUnixMode() >> 6) & 0x1),
                                name + " zip executable bit must match for combo " + combo);
                    }
                }
            }

            // tar.gz output.
            try (ZipFile oz = new ZipFile(oracleZip);
                 var fis = new FileInputStream(hg4jTarGz);
                 var gzin = new GzipCompressorInputStream(fis);
                 var tin = new TarArchiveInputStream(gzin)) {
                Set<String> oracleNames = zipNames(oz);
                String archivalName = oracleNames.stream().filter(n -> n.endsWith(".hg_archival.txt")).findFirst().orElseThrow();
                String prefix = archivalName.substring(0, archivalName.length() - ".hg_archival.txt".length());
                Set<String> oracleRel = new TreeSet<>();
                for (String n : oracleNames) {
                    oracleRel.add(n.substring(prefix.length()));
                }

                Set<String> tarRel = new TreeSet<>();
                Map<String, byte[]> tarContents = new HashMap<>();
                Map<String, String> tarSymlinks = new HashMap<>();
                TarArchiveEntry te;
                String tarPrefix = null;
                while ((te = tin.getNextEntry()) != null) {
                    String name = te.getName();
                    if (tarPrefix == null) {
                        int slash = name.indexOf('/');
                        tarPrefix = slash >= 0 ? name.substring(0, slash + 1) : "";
                    }
                    String rel = name.startsWith(tarPrefix) ? name.substring(tarPrefix.length()) : name;
                    tarRel.add(rel);
                    if (te.isSymbolicLink()) {
                        tarSymlinks.put(rel, te.getLinkName());
                    } else {
                        tarContents.put(rel, tin.readAllBytes());
                    }
                }
                assertEquals(oracleRel, tarRel, "tar.gz member set must match real hg's zip oracle for combo " + combo);
                assertEquals("root.txt", tarSymlinks.get("link.txt"), "tar.gz symlink target must match for combo " + combo);
                for (String rel : oracleRel) {
                    if (rel.equals("link.txt")) {
                        continue;
                    }
                    byte[] oc = oz.getInputStream(oz.getEntry(prefix + rel)).readAllBytes();
                    assertEquals(new String(oc, StandardCharsets.UTF_8), new String(tarContents.get(rel), StandardCharsets.UTF_8),
                            rel + " tar.gz content must match for combo " + combo);
                }
            }
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jOwnTagArchiveEmitsTagLineAcrossDockerCombo(RequirementCombo combo) throws Exception {
        withFreshContainer((containerName, workDir) -> {
            String repoRelPath = "repo";
            Path hostRepoDir = workDir.resolve(repoRelPath);
            Files.createDirectories(hostRepoDir);

            List<String> initArgs = new ArrayList<>(List.of("init", "."));
            for (String c : combo.initConfigArgs()) {
                initArgs.add("--config");
                initArgs.add(c);
            }
            dockerHgIn(containerName, repoRelPath, initArgs.toArray(new String[0]));

            Files.writeString(hostRepoDir.resolve("a.txt"), "a\n");
            dockerHgIn(containerName, repoRelPath, "add");
            dockerHgIn(containerName, repoRelPath, "commit", "-u", "dev", "-m", "c0");
            String taggedHex = dockerHgIn(containerName, repoRelPath, "log", "-r", ".", "--template", "{node}");
            dockerHgIn(containerName, repoRelPath, "tag", "-u", "dev", "v1.0");

            File oracleDir = workDir.resolve("oracle-owntag").toFile();
            dockerHgIn(containerName, repoRelPath, "archive", "-t", "files", "-r", taggedHex, "/repo-root/oracle-owntag");
            String oracleMeta = Files.readString(oracleDir.toPath().resolve(".hg_archival.txt"), StandardCharsets.UTF_8);

            File hg4jDir = workDir.resolve("hg4j-owntag").toFile();
            File dummyZip = workDir.resolve("dummy.zip").toFile();
            File dummyTarGz = workDir.resolve("dummy.tar.gz").toFile();
            archiveInSubprocess(hostRepoDir, taggedHex, hg4jDir, dummyZip, dummyTarGz);
            String hg4jMeta = Files.readString(hg4jDir.toPath().resolve(".hg_archival.txt"), StandardCharsets.UTF_8);

            assertEquals(oracleMeta, hg4jMeta, "own-tag .hg_archival.txt form must match real hg exactly for combo " + combo);
            assertTrue(hg4jMeta.contains("tag: v1.0\n"), "must use the tag: form for combo " + combo);
        });
    }
}
