package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.RevlogIndex;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Standalone JVM entry point used ONLY by {@link RequirementMatrixRecoverDockerRoundTripTest},
 * mirroring {@link RequirementMatrixBackoutHelperMain}'s reason for existing -- see its javadoc
 * (which itself defers to {@link RequirementMatrixCommitHelperMain}) for the full root-cause
 * writeup on why hg4j's own revlog-writing commands must run in a dedicated subprocess rather than
 * inline in a JVM that also repeatedly spawns {@code docker exec}/{@code docker run} child
 * processes.
 *
 * <p>Duplicates {@link RequirementMatrixRecoverCoreRoundTripTest}'s crash-journal fabrication
 * helpers verbatim (see that class's own javadoc for why a genuinely-crashed transaction cannot be
 * triggered deterministically, and for the exact format being reproduced here) -- per the
 * established convention (see {@link RequirementMatrixStripDockerRoundTripTest}), this is copied
 * rather than shared.
 *
 * <p>Args: {@code repoDir}. The repository must already contain a single committed revision c0
 * with a root {@code a.txt} and a nested {@code sub/a.txt}. This: snapshots the pre-c1 physical
 * state of the changelog, manifest, {@code sub}'s directory manifest (if treemanifest), and
 * {@code sub/a.txt}'s filelog; commits c1 (changing {@code sub/a.txt}) via hg4j; hand-crafts
 * {@code .hg/store/journal} from that snapshot (simulating what a real interrupted commit would
 * have left behind); and finally runs {@link RecoverCommand}. Prints {@code
 * interrupted=<bool> success=<bool>} on success.
 */
public final class RequirementMatrixRecoverHelperMain {
    private RequirementMatrixRecoverHelperMain() {
    }

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

    /** Mirrors {@code CommitCommand#captureDirstateV2DataBackup} (see its javadoc for the exact
     * on-disk layout being read) -- duplicated per this file's own "copied rather than shared"
     * convention (see class javadoc). */
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

    public static void main(String[] args) throws Exception {
        File repoDir = new File(args[0]);
        HgRepository repo = new HgRepository(repoDir);
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
        File dirstateFile = new File(repoDir, ".hg/dirstate");
        byte[] dirstateBackup = Files.readAllBytes(dirstateFile.toPath());
        byte[] dirstateV2DataBackup = captureDirstateV2DataBackup(dirstateFile, dirstateBackup);

        Files.writeString(repoDir.toPath().resolve("sub/a.txt"), "sub-changed-by-c1\n");
        new CommitCommand(repo).setAuthor("hg4j").setMessage("c1").call();

        File journalFile = new File(storeDir, "journal");
        Files.deleteIfExists(journalFile.toPath());
        appendJournalLine(journalFile, "dirstate");
        File dirstateBackupFile = new File(repoDir, ".hg/dirstate.backup");
        Files.write(dirstateBackupFile.toPath(), dirstateBackup);
        // dirstate-v2's own companion data file -- see HgRepository#checkAndPerformAutoRollback's
        // matching "dirstate" branch javadoc / CommitCommand#recordRevlogRollbackState's
        // adjacent comment for the full story (found live 2026-09-05 via this exact scenario:
        // Dirstate.write()'s "W-LEAK" cleanup deletes the previous uid's data file the moment c1
        // durably writes its own new docket, so restoring dirstateBackup's docket bytes alone
        // would point at a data file that no longer exists).
        if (dirstateV2DataBackup != null) {
            Files.write(new File(repoDir, ".hg/dirstateV2.backup.data").toPath(), dirstateV2DataBackup);
        }
        writeRestoreJournalLines(journalFile, storeDir, clIdx, clDat, clSnap);
        writeRestoreJournalLines(journalFile, storeDir, mfIdx, mfDat, mfSnap);
        if (dlIdx.exists() || dlSnap.idxLen() > 0) {
            writeRestoreJournalLines(journalFile, storeDir, dlIdx, dlDat, dlSnap);
        }
        writeRestoreJournalLines(journalFile, storeDir, flIdx, flDat, flSnap);

        HgRepository crashedRepo = new HgRepository(repoDir);
        RecoverCommand.RecoverResult result = new RecoverCommand(crashedRepo).call();
        System.out.println("interrupted=" + result.wasInterrupted() + " success=" + result.isSuccess());
    }
}
