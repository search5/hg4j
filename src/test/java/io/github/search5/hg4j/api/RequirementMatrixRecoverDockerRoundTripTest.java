package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.RevlogIndex;

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
import java.nio.file.StandardOpenOption;
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
 * Docker-only half of the requirement matrix (see {@link RequirementMatrixDockerRoundTripTest} for
 * the full 30-combo design this reuses verbatim) applied to {@link RecoverCommand} -- the
 * Docker-only counterpart of {@link RequirementMatrixRecoverCoreRoundTripTest}'s native 6-combo
 * scenario (see that class's own javadoc for the crash-journal fabrication technique this reuses).
 *
 * <p>Each case gets its own fresh, short-lived container (never a class-shared one), matching
 * {@link RequirementMatrixMergeDockerRoundTripTest}/{@link RequirementMatrixStripDockerRoundTripTest}.
 * hg4j's own commit+journal-fabrication+recover write runs in a dedicated {@code java} subprocess
 * ({@link RequirementMatrixRecoverHelperMain}) rather than inline in this JVM, for the same
 * docker-exec-interleaving corruption reason documented on {@link RequirementMatrixCommitHelperMain}.
 */
@Tag("interop")
public class RequirementMatrixRecoverDockerRoundTripTest {

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


    /** EXPERIMENT (2026-09-09): inline instead of subprocess -- logic copied verbatim from
     * {@link RequirementMatrixRecoverHelperMain} (crash-journal fabrication helpers included). */
    private record RevlogSnapshot(boolean v2, long idxLen, long datLen, byte[] docketBytes,
                                   long resolvedIdxLen, long resolvedDatLen, boolean hasSda, long resolvedSdaLen) {
    }

    private static RevlogSnapshot snapshotRevlog(File idxFile, File datFile) throws Exception {
        if (!idxFile.exists()) {
            return new RevlogSnapshot(false, 0, 0, null, 0, 0, false, 0);
        }
        RevlogIndex probe = new RevlogIndex(idxFile);
        if (probe.isV2()) {
            byte[] docketBytes = Files.readAllBytes(idxFile.toPath());
            File ridx = probe.getResolvedIndexFile();
            File rdat = probe.getResolvedDataFile();
            File rsda = probe.getResolvedSidedataFile();
            long ridxLen = ridx != null && ridx.exists() ? ridx.length() : 0;
            long rdatLen = rdat != null && rdat.exists() ? rdat.length() : 0;
            boolean hasSda = rsda != null;
            long rsdaLen = hasSda && rsda.exists() ? rsda.length() : 0;
            return new RevlogSnapshot(true, 0, 0, docketBytes, ridxLen, rdatLen, hasSda, rsdaLen);
        }
        long idxLen = idxFile.length();
        long datLen = datFile.exists() ? datFile.length() : 0;
        return new RevlogSnapshot(false, idxLen, datLen, null, 0, 0, false, 0);
    }

    private static byte[] captureDirstateV2DataBackup(File dirstateFile, byte[] docketBytes) {
        if (docketBytes == null || docketBytes.length < 125) {
            return null;
        }
        try {
            if (!new String(docketBytes, 0, 12, StandardCharsets.US_ASCII).equals("dirstate-v2\n")) {
                return null;
            }
            int uidSize = docketBytes[124] & 0xFF;
            if (docketBytes.length < 125 + uidSize) {
                return null;
            }
            String uid = new String(docketBytes, 125, uidSize, StandardCharsets.US_ASCII);
            File dataFile = new File(dirstateFile.getParentFile(), "dirstate." + uid);
            return dataFile.exists() ? Files.readAllBytes(dataFile.toPath()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void appendJournalLine(File journalFile, String entry) throws Exception {
        Files.writeString(journalFile.toPath(), entry + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String storeRel(File storeDir, File f) {
        return "store/" + storeDir.toPath().relativize(f.toPath()).toString().replace('\\', '/');
    }

    private static void writeRestoreJournalLines(File journalFile, File storeDir, File idxFile, File datFile,
                                                  RevlogSnapshot snap) throws Exception {
        if (snap.v2()) {
            String backupRel = "journal.docket." + UUID.randomUUID() + ".bck";
            Files.write(new File(storeDir, backupRel).toPath(), snap.docketBytes());
            appendJournalLine(journalFile, "backup " + storeRel(storeDir, idxFile) + "\tstore/" + backupRel);
            RevlogIndex postCommit = new RevlogIndex(idxFile);
            File ridx = postCommit.getResolvedIndexFile();
            File rdat = postCommit.getResolvedDataFile();
            File rsda = postCommit.getResolvedSidedataFile();
            appendJournalLine(journalFile, "trunc " + storeRel(storeDir, ridx) + "\t" + snap.resolvedIdxLen());
            appendJournalLine(journalFile, "trunc " + storeRel(storeDir, rdat) + "\t" + snap.resolvedDatLen());
            if (snap.hasSda() && rsda != null) {
                appendJournalLine(journalFile, "trunc " + storeRel(storeDir, rsda) + "\t" + snap.resolvedSdaLen());
            }
        } else {
            appendJournalLine(journalFile, storeRel(storeDir, idxFile) + "\t" + snap.idxLen());
            appendJournalLine(journalFile, storeRel(storeDir, datFile) + "\t" + snap.datLen());
        }
    }

    private static String recoverInSubprocess(Path repoDir) throws Exception {
        File repoDirFile = repoDir.toFile();
        HgRepository repo = new HgRepository(repoDirFile);
        File storeDir = repo.getStoreDir();
        File clIdx = new File(storeDir, "00changelog.i");
        File clDat = new File(storeDir, "00changelog.d");
        File mfIdx = new File(storeDir, "00manifest.i");
        File mfDat = new File(storeDir, "00manifest.d");
        File flIdx = CommitCommand.getFilelogIndex(storeDir, "sub/a.txt");
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        File dlIdx = new File(storeDir, "meta/sub/00manifest.i");
        File dlDat = new File(storeDir, "meta/sub/00manifest.d");

        RevlogSnapshot clSnap = snapshotRevlog(clIdx, clDat);
        RevlogSnapshot mfSnap = snapshotRevlog(mfIdx, mfDat);
        RevlogSnapshot dlSnap = snapshotRevlog(dlIdx, dlDat);
        RevlogSnapshot flSnap = snapshotRevlog(flIdx, flDat);
        File dirstateFile = new File(repoDirFile, ".hg/dirstate");
        byte[] dirstateBackup = Files.readAllBytes(dirstateFile.toPath());
        byte[] dirstateV2DataBackup = captureDirstateV2DataBackup(dirstateFile, dirstateBackup);

        Files.writeString(repoDir.resolve("sub/a.txt"), "sub-changed-by-c1\n");
        new CommitCommand(repo).setAuthor("hg4j").setMessage("c1").call();

        File journalFile = new File(storeDir, "journal");
        Files.deleteIfExists(journalFile.toPath());
        appendJournalLine(journalFile, "dirstate");
        File dirstateBackupFile = new File(repoDirFile, ".hg/dirstate.backup");
        Files.write(dirstateBackupFile.toPath(), dirstateBackup);
        if (dirstateV2DataBackup != null) {
            Files.write(new File(repoDirFile, ".hg/dirstateV2.backup.data").toPath(), dirstateV2DataBackup);
        }
        writeRestoreJournalLines(journalFile, storeDir, clIdx, clDat, clSnap);
        writeRestoreJournalLines(journalFile, storeDir, mfIdx, mfDat, mfSnap);
        if (dlIdx.exists() || dlSnap.idxLen() > 0) {
            writeRestoreJournalLines(journalFile, storeDir, dlIdx, dlDat, dlSnap);
        }
        writeRestoreJournalLines(journalFile, storeDir, flIdx, flDat, flSnap);

        HgRepository crashedRepo = new HgRepository(repoDirFile);
        RecoverCommand.RecoverResult result = new RecoverCommand(crashedRepo).call();
        return "interrupted=" + result.wasInterrupted() + " success=" + result.isSuccess();
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
    public void hg4jRecoverAfterSimulatedCrashedCommitAcrossDockerCombo(RequirementCombo combo) throws Exception {
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

            Files.writeString(hostRepoDir.resolve("a.txt"), "root-base\n");
            Files.createDirectories(hostRepoDir.resolve("sub"));
            Files.writeString(hostRepoDir.resolve("sub/a.txt"), "sub-base\n");
            NativeHgRust.hg(workDir, repoRelPath, "add");
            NativeHgRust.hg(workDir, repoRelPath, "commit", "-u", "dev", "-m", "c0");
            String c0Hex = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", ".", "--template", "{node}");

            String out = recoverInSubprocess(hostRepoDir);
            assertTrue(out.contains("interrupted=true"), "the fabricated journal must be detected for combo " + combo + ": " + out);
            assertTrue(out.contains("success=true"), "recovery must succeed for combo " + combo + ": " + out);

            String revs = NativeHgRust.hg(workDir, repoRelPath, "log", "--template", "{rev}\\n");
            assertEquals("0", revs, "only c0 must remain after recovery for combo " + combo);

            String tipHex = NativeHgRust.hg(workDir, repoRelPath, "log", "-r", "tip", "--template", "{node}");
            assertEquals(c0Hex, tipHex, "tip must be c0 after recovery for combo " + combo);

            assertEquals("sub-base", NativeHgRust.hg(workDir, repoRelPath, "cat", "-r", "tip", "sub/a.txt"),
                    "sub/a.txt content must be reverted to c0 for combo " + combo);

            String verify = NativeHgRust.hg(workDir, repoRelPath, "verify");
            assertFalse(verify.toLowerCase().contains("integrity error") || verify.toLowerCase().contains("error:"),
                    "real hg verify must find no integrity errors after recovery for combo " + combo + ": " + verify);

            String parents = NativeHgRust.hg(workDir, repoRelPath, "parents", "--template", "{node}");
            assertEquals(c0Hex, parents, "dirstate parent must be reverted to c0 for combo " + combo);
        });
    }
}
