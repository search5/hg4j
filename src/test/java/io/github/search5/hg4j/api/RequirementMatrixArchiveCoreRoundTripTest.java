package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixMergeCoreRoundTripTest} for the
 * reused pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to {@link
 * ArchiveCommand} across the native 6-combo grid (changelog family x treemanifest, dirstate fixed
 * at v1 -- v2 needs Docker, see {@code RequirementMatrixArchiveDockerRoundTripTest}).
 *
 * <p>{@link ArchiveCommand} is a pure read/export command (never mutates the repository), so
 * unlike most other requirement-matrix suites in this package, no subprocess helper is needed --
 * the corruption this campaign otherwise routes around ({@code RequirementMatrixCommitHelperMain}'s
 * javadoc) is specific to writing revlogs from a JVM that's also spawning {@code docker exec}
 * children, which never happens here.
 *
 * <p>Two scenarios, both across every combo, both verified live against real {@code hg} 7.2
 * (2026-09-05) before being ported (see {@link ArchiveCommand}'s own javadoc for the full
 * behavioral writeup this is checking):
 * <ul>
 *   <li>{@link #hg4jStructuralArchiveMatchesRealHgAcrossCombo}: a commit with a root file, an
 *   executable file, a symlink, and a nested-subdirectory file (the last one exercising
 *   treemanifest's dirlog on the {@code treemanifest} half of the grid -- the very thing
 *   {@link ArchiveCommand}'s old hand-rolled, flat-manifest-only parser silently dropped) is
 *   archived to a directory, a zip, and a {@code tar.gz} by hg4j and, separately, by real hg
 *   itself -- entry sets, file contents, the executable bit, the symlink target, and the fixed
 *   {@code repo:}/{@code node:}/{@code branch:} + null-tag {@code latesttag}/{@code
 *   latesttagdistance}/{@code changessincelatesttag} block of {@code .hg_archival.txt} are all
 *   compared for exact agreement.</li>
 *   <li>{@link #hg4jOwnTagArchiveEmitsTagLineAcrossCombo}: archiving the exact revision a global
 *   tag points at must switch {@code .hg_archival.txt} to the {@code tag: <name>} form instead of
 *   the {@code latesttag}/distance block (verified live: real hg only ever does this when the
 *   archived revision itself carries the tag, distance 0).</li>
 * </ul>
 */
@Tag("interop")
public class RequirementMatrixArchiveCoreRoundTripTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    record RequirementCombo(String label, List<String> initConfigArgs) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final List<String> CL_V1 = List.of();
    private static final List<String> CL_V2 = List.of("format.exp-use-changelog-v2=enable-unstable-format-and-corrupt-my-data");
    private static final List<String> CL_V2_SIDEDATA = List.of(
            "format.exp-use-changelog-v2=enable-unstable-format-and-corrupt-my-data",
            "format.exp-use-copies-side-data-changeset=yes");

    private static final List<String> TREEMANIFEST_OFF = List.of();
    private static final List<String> TREEMANIFEST_ON = List.of("experimental.treemanifest=1");

    static Stream<RequirementCombo> combos() {
        List<RequirementCombo> out = new ArrayList<>();
        for (var cl : List.of(Map.entry("cl1", CL_V1), Map.entry("cl2", CL_V2), Map.entry("cl2+sidedata", CL_V2_SIDEDATA))) {
            for (var tm : List.of(Map.entry("flatmanifest", TREEMANIFEST_OFF), Map.entry("treemanifest", TREEMANIFEST_ON))) {
                List<String> args = new ArrayList<>();
                args.addAll(cl.getValue());
                args.addAll(tm.getValue());
                out.add(new RequirementCombo(cl.getKey() + "/" + tm.getKey(), args));
            }
        }
        return out.stream();
    }

    private static File initWithCombo(Path tempDir, RequirementCombo combo, String suffix) throws Exception {
        File repoDir = tempDir.resolve("repo-" + combo.label().replace("/", "-").replace("+", "_") + "-" + suffix).toFile();
        repoDir.mkdirs();
        List<String> args = new ArrayList<>();
        args.add("init");
        for (String c : combo.initConfigArgs()) {
            args.add("--config");
            args.add(c);
        }
        HgTestUtils.hg(repoDir, args.toArray(new String[0]));
        return repoDir;
    }

    private static void writeScenarioFiles(File repoDir) throws Exception {
        Files.writeString(repoDir.toPath().resolve("root.txt"), "root content\n");
        Files.writeString(repoDir.toPath().resolve("exec.sh"), "#!/bin/sh\necho hi\n");
        new File(repoDir, "exec.sh").setExecutable(true, false);
        Files.createSymbolicLink(repoDir.toPath().resolve("link.txt"), Path.of("root.txt"));
        Files.createDirectories(repoDir.toPath().resolve("sub"));
        Files.writeString(repoDir.toPath().resolve("sub/nested.txt"), "nested content\n");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jStructuralArchiveMatchesRealHgAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "archive-structural");
        writeScenarioFiles(repoDir);
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");
        String tipHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        // Oracle: real hg archives the exact same commit to its own directory/zip/tar.gz. hg4j's
        // outputs below use the SAME basenames (just a different parent directory) so both sides
        // compute the identical default prefix (real hg's own basename-minus-suffix rule) --
        // proving hg4j's prefix computation agrees with real hg's, not merely that it's internally
        // self-consistent.
        File oracleRoot = Files.createDirectory(tempDir.resolve("oracle")).toFile();
        File hg4jRoot = Files.createDirectory(tempDir.resolve("hg4j")).toFile();

        File oracleDir = new File(oracleRoot, "archive-dir");
        HgTestUtils.hg(repoDir, "archive", "-t", "files", oracleDir.getAbsolutePath());
        File oracleZip = new File(oracleRoot, "archive.zip");
        HgTestUtils.hg(repoDir, "archive", "-t", "zip", oracleZip.getAbsolutePath());
        File oracleTarGz = new File(oracleRoot, "archive.tar.gz");
        HgTestUtils.hg(repoDir, "archive", "-t", "tgz", oracleTarGz.getAbsolutePath());

        HgRepository repo = new HgRepository(repoDir);

        // hg4j: directory output.
        File hg4jDir = new File(hg4jRoot, "archive-dir");
        new ArchiveCommand(repo).setRevision("tip").setDestination(hg4jDir).call();
        assertDirectoriesMatch(oracleDir, hg4jDir, combo);
        assertArchivalMetadataMatches(
                Files.readString(oracleDir.toPath().resolve(".hg_archival.txt"), StandardCharsets.UTF_8),
                Files.readString(hg4jDir.toPath().resolve(".hg_archival.txt"), StandardCharsets.UTF_8),
                combo);

        // hg4j: zip output (default type inferred from ".zip", default prefix from basename).
        File hg4jZip = new File(hg4jRoot, "archive.zip");
        new ArchiveCommand(repo).setRevision("tip").setDestination(hg4jZip).call();
        assertZipsMatch(oracleZip, hg4jZip, "oracle", "hg4j", combo);

        // hg4j: tgz output.
        File hg4jTarGz = new File(hg4jRoot, "archive.tar.gz");
        new ArchiveCommand(repo).setRevision("tip").setDestination(hg4jTarGz).call();
        assertTarGzMatchesOracleZip(oracleZip, "oracle", hg4jTarGz, "hg4j", combo);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jOwnTagArchiveEmitsTagLineAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "archive-owntag");
        Files.writeString(repoDir.toPath().resolve("a.txt"), "a\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");
        String taggedHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");
        HgTestUtils.hg(repoDir, "tag", "-u", "dev", "v1.0");

        String oracleMeta;
        {
            File oracleDir = tempDir.resolve("oracle-owntag").toFile();
            HgTestUtils.hg(repoDir, "archive", "-t", "files", "-r", taggedHex, oracleDir.getAbsolutePath());
            oracleMeta = Files.readString(oracleDir.toPath().resolve(".hg_archival.txt"), StandardCharsets.UTF_8);
        }

        HgRepository repo = new HgRepository(repoDir);
        File hg4jDir = tempDir.resolve("hg4j-owntag").toFile();
        new ArchiveCommand(repo).setRevision(taggedHex).setDestination(hg4jDir).call();
        String hg4jMeta = Files.readString(hg4jDir.toPath().resolve(".hg_archival.txt"), StandardCharsets.UTF_8);

        assertEquals(oracleMeta, hg4jMeta, "the own-tag `tag: <name>` .hg_archival.txt form must match real hg exactly for combo " + combo);
        assertTrue(hg4jMeta.contains("tag: v1.0\n"), "must use the tag: form (distance 0), not latesttag/distance, for combo " + combo);
    }

    private static void assertDirectoriesMatch(File oracleDir, File hg4jDir, RequirementCombo combo) throws Exception {
        Set<String> oracleEntries = relativeFileListing(oracleDir);
        Set<String> hg4jEntries = relativeFileListing(hg4jDir);
        assertEquals(oracleEntries, hg4jEntries, "directory archive member set must match real hg for combo " + combo);

        for (String rel : oracleEntries) {
            Path oraclePath = oracleDir.toPath().resolve(rel);
            Path hg4jPath = hg4jDir.toPath().resolve(rel);
            if (Files.isSymbolicLink(oraclePath)) {
                assertTrue(Files.isSymbolicLink(hg4jPath), rel + " must be a real symlink in hg4j's directory archive for combo " + combo);
                assertEquals(Files.readSymbolicLink(oraclePath), Files.readSymbolicLink(hg4jPath),
                        rel + " symlink target must match for combo " + combo);
            } else {
                assertEquals(Files.readString(oraclePath), Files.readString(hg4jPath), rel + " content must match for combo " + combo);
                if (!rel.equals(".hg_archival.txt")) {
                    assertEquals(Files.isExecutable(oraclePath), Files.isExecutable(hg4jPath),
                            rel + " executable bit must match for combo " + combo);
                }
            }
        }
    }

    private static Set<String> relativeFileListing(File dir) throws Exception {
        Set<String> out = new TreeSet<>();
        Files.walk(dir.toPath()).filter(Files::isRegularFile).forEach(p -> out.add(dir.toPath().relativize(p).toString().replace('\\', '/')));
        // Symlinks aren't "regular files" per Files.isRegularFile -- add them explicitly.
        Files.walk(dir.toPath()).filter(Files::isSymbolicLink).forEach(p -> out.add(dir.toPath().relativize(p).toString().replace('\\', '/')));
        return out;
    }

    private static Set<String> zipNames(ZipFile zf) {
        Set<String> names = new TreeSet<>();
        Collections.list(zf.getEntries()).forEach(e -> names.add(e.getName()));
        return names;
    }

    private static void assertZipsMatch(File oracleZip, File hg4jZip, String oracleLabel, String hg4jLabel, RequirementCombo combo) throws Exception {
        try (ZipFile oz = new ZipFile(oracleZip); ZipFile hz = new ZipFile(hg4jZip)) {
            Set<String> oracleNames = zipNames(oz);
            Set<String> hg4jNames = zipNames(hz);
            assertEquals(oracleNames, hg4jNames, "zip member set must match real hg (including the shared prefix) for combo " + combo);

            String archivalMemberName = oracleNames.stream().filter(n -> n.endsWith(".hg_archival.txt")).findFirst().orElseThrow();
            String prefixOracle = archivalMemberName.substring(0, archivalMemberName.length() - ".hg_archival.txt".length());
            String archivalMemberNameHg4j = hg4jNames.stream().filter(n -> n.endsWith(".hg_archival.txt")).findFirst().orElseThrow();
            String prefixHg4j = archivalMemberNameHg4j.substring(0, archivalMemberNameHg4j.length() - ".hg_archival.txt".length());

            for (String oracleName : oracleNames) {
                if (!oracleName.endsWith("link.txt")) {
                    String rel = oracleName.substring(prefixOracle.length());
                    String hg4jName = prefixHg4j + rel;
                    ZipArchiveEntry oe = oz.getEntry(oracleName);
                    ZipArchiveEntry he = hz.getEntry(hg4jName);
                    byte[] oContent = oz.getInputStream(oe).readAllBytes();
                    byte[] hContent = hz.getInputStream(he).readAllBytes();
                    if (rel.equals(".hg_archival.txt")) {
                        assertArchivalMetadataMatches(new String(oContent, StandardCharsets.UTF_8),
                                new String(hContent, StandardCharsets.UTF_8), combo);
                    } else {
                        assertEquals(new String(oContent, StandardCharsets.UTF_8), new String(hContent, StandardCharsets.UTF_8),
                                rel + " zip content must match for combo " + combo);
                        boolean oracleExec = ((oe.getUnixMode() >> 6) & 0x1) != 0;
                        boolean hg4jExec = ((he.getUnixMode() >> 6) & 0x1) != 0;
                        assertEquals(oracleExec, hg4jExec, rel + " zip executable bit must match for combo " + combo);
                    }
                }
            }
        }
    }

    /** Compares hg4j's tar.gz archive against the (already-parsed) real-hg zip oracle's plain
     * file contents -- tar-specific structural checks (member names/prefix/symlink) are exercised
     * directly via {@code tar} member enumeration; content agreement is cross-checked against the
     * zip oracle to avoid needing a second oracle archiver. */
    private static void assertTarGzMatchesOracleZip(File oracleZip, String oracleLabel, File hg4jTarGz, String hg4jLabel, RequirementCombo combo) throws Exception {
        try (ZipFile oz = new ZipFile(oracleZip);
             var fis = new FileInputStream(hg4jTarGz);
             var gzin = new GzipCompressorInputStream(fis);
             var tin = new TarArchiveInputStream(gzin)) {

            Set<String> oracleNames = zipNames(oz);
            String archivalMemberName = oracleNames.stream().filter(n -> n.endsWith(".hg_archival.txt")).findFirst().orElseThrow();
            String prefixOracle = archivalMemberName.substring(0, archivalMemberName.length() - ".hg_archival.txt".length());

            Set<String> tarRelNames = new TreeSet<>();
            TarArchiveEntry te;
            String tarPrefix = null;
            Map<String, byte[]> tarContents = new HashMap<>();
            Map<String, Boolean> tarExec = new HashMap<>();
            Map<String, String> tarSymlinks = new HashMap<>();
            while ((te = tin.getNextEntry()) != null) {
                String name = te.getName();
                if (tarPrefix == null) {
                    int slash = name.indexOf('/');
                    tarPrefix = slash >= 0 ? name.substring(0, slash + 1) : "";
                }
                String rel = name.startsWith(tarPrefix) ? name.substring(tarPrefix.length()) : name;
                tarRelNames.add(rel);
                if (te.isSymbolicLink()) {
                    tarSymlinks.put(rel, te.getLinkName());
                } else {
                    tarContents.put(rel, tin.readAllBytes());
                    tarExec.put(rel, (te.getMode() & 0100) != 0);
                }
            }

            Set<String> oracleRelNames = new TreeSet<>();
            for (String n : oracleNames) {
                oracleRelNames.add(n.substring(prefixOracle.length()));
            }
            assertEquals(oracleRelNames, tarRelNames, "tar.gz member set (relative to its own prefix) must match real hg's zip oracle for combo " + combo);

            for (String rel : oracleRelNames) {
                if (rel.equals("link.txt")) {
                    assertEquals("root.txt", tarSymlinks.get(rel), "tar.gz symlink target must match for combo " + combo);
                    continue;
                }
                ZipArchiveEntry oe = oz.getEntry(prefixOracle + rel);
                byte[] oContent = oz.getInputStream(oe).readAllBytes();
                if (rel.equals(".hg_archival.txt")) {
                    assertArchivalMetadataMatches(new String(oContent, StandardCharsets.UTF_8),
                            new String(tarContents.get(rel), StandardCharsets.UTF_8), combo);
                } else {
                    assertEquals(new String(oContent, StandardCharsets.UTF_8), new String(tarContents.get(rel), StandardCharsets.UTF_8),
                            rel + " tar.gz content must match for combo " + combo);
                    boolean oracleExec = ((oe.getUnixMode() >> 6) & 0x1) != 0;
                    assertEquals(oracleExec, tarExec.get(rel), rel + " tar.gz executable bit must match for combo " + combo);
                }
            }
        }
    }

    private static void assertArchivalMetadataMatches(String oracleText, String hg4jText, RequirementCombo combo) {
        assertEquals(oracleText, hg4jText, ".hg_archival.txt content must match real hg exactly (repo/node/branch + null-tag block) for combo " + combo);
    }
}
