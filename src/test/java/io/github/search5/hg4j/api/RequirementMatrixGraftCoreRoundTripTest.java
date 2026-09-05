package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.errors.HgMergeConflictException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.RevlogIndex;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixCoreRoundTripTest} for the reused
 * pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to {@link
 * GraftCommand} across the native 6-combo grid (changelog family x treemanifest, dirstate fixed
 * at v1 -- v2 needs Docker, see {@code RequirementMatrixGraftDockerRoundTripTest}).
 *
 * <p>Each combo exercises two scenarios in the same repository:
 * <ol>
 *   <li>A conflict-free graft of a diverging source branch onto a destination branch (mirrors
 *       {@code RequirementMatrixRebaseCoreRoundTripTest}'s own scenario): real hg re-reads the
 *       result, {@code verify} must be clean, the grafted commit's parent must be the destination,
 *       both branches' file content must be present, and -- since 2026-09-05, see {@link
 *       GraftCommand}'s own class javadoc -- the ORIGINAL source revision must stay fully visible
 *       in a plain {@code hg log} (a plain graft is a copy, not a rewrite; it must never write an
 *       obsolescence marker the way {@link RebaseCommand}/{@link HisteditCommand} do).</li>
 *   <li>A graft that genuinely conflicts (same file changed differently on both sides since their
 *       common ancestor): {@link GraftCommand#call()} must pause with {@link
 *       HgMergeConflictException} and real hg's own {@code hg resolve --list} must agree; after a
 *       manual resolution, {@link GraftCommand#continueGraft()} must complete the commit and real
 *       hg must accept the result as valid.</li>
 * </ol>
 */
@Tag("interop")
public class RequirementMatrixGraftCoreRoundTripTest {

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
        List<RequirementCombo> out = new java.util.ArrayList<>();
        for (var cl : List.of(java.util.Map.entry("cl1", CL_V1), java.util.Map.entry("cl2", CL_V2), java.util.Map.entry("cl2+sidedata", CL_V2_SIDEDATA))) {
            for (var tm : List.of(java.util.Map.entry("flatmanifest", TREEMANIFEST_OFF), java.util.Map.entry("treemanifest", TREEMANIFEST_ON))) {
                List<String> args = new java.util.ArrayList<>();
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
        List<String> args = new java.util.ArrayList<>();
        args.add("init");
        for (String c : combo.initConfigArgs()) {
            args.add("--config");
            args.add(c);
        }
        HgTestUtils.hg(repoDir, args.toArray(new String[0]));
        return repoDir;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jGraftAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "graft");

        // --- Scenario 1: conflict-free graft of a diverging branch ---
        Files.writeString(repoDir.toPath().resolve("base.txt"), "base\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");

        Files.writeString(repoDir.toPath().resolve("target.txt"), "on-target\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1 target");
        String targetHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "update", "0");
        Files.writeString(repoDir.toPath().resolve("source.txt"), "on-source\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c2 source");
        String sourceHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "update", targetHex);

        HgRepository repo = new HgRepository(repoDir);
        String graftedHex = new GraftCommand(repo).setSource(sourceHex).call();

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.toLowerCase().contains("integrity error"),
                "real hg verify must find no integrity errors after graft for combo " + combo + ": " + verify);

        String graftedParent = HgTestUtils.hg(repoDir, "log", "-r", graftedHex, "--template", "{p1node}");
        assertEquals(targetHex, graftedParent, "grafted commit's parent must be the destination for combo " + combo);

        String catTarget = HgTestUtils.hg(repoDir, "cat", "-r", graftedHex, "target.txt");
        assertEquals("on-target", catTarget.trim());
        String catSource = HgTestUtils.hg(repoDir, "cat", "-r", graftedHex, "source.txt");
        assertEquals("on-source", catSource.trim());

        String logAll = HgTestUtils.hg(repoDir, "log", "--template", "{node} ");
        assertTrue(logAll.contains(sourceHex),
                "a plain graft must never hide its source revision (no obsmarker) for combo " + combo);

        File obsstore = new File(repo.getStoreDir(), "obsstore");
        assertFalse(obsstore.exists() && obsstore.length() > 0,
                "a plain graft must not write any obsolescence marker for combo " + combo);

        // --- Scenario 2: a graft that genuinely conflicts, then continueGraft() ---
        HgTestUtils.hg(repoDir, "update", graftedHex);
        Files.writeString(repoDir.toPath().resolve("conflict.txt"), "line1\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c3 conflict base");
        String conflictBaseHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        Files.writeString(repoDir.toPath().resolve("conflict.txt"), "line1-dest\n");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c4 dest modifies conflict.txt");
        String destHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "update", conflictBaseHex);
        Files.writeString(repoDir.toPath().resolve("conflict.txt"), "line1-source\n");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c5 source modifies conflict.txt (conflicts with dest)");
        String conflictSourceHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "update", destHex);

        GraftCommand conflictGraft = new GraftCommand(repo).setSource(conflictSourceHex);
        HgMergeConflictException ex = assertThrows(HgMergeConflictException.class, conflictGraft::call,
                "a genuine same-file conflict must pause the graft for combo " + combo);
        assertEquals(List.of("conflict.txt"), ex.getConflictPaths());

        String resolveList = HgTestUtils.hg(repoDir, "resolve", "--list");
        assertEquals("U conflict.txt", resolveList.trim(),
                "real hg must see the same unresolved-file bookkeeping for combo " + combo);

        // Manually resolve, exactly like a user driving `hg resolve` would.
        Files.writeString(repoDir.toPath().resolve("conflict.txt"), "line1-dest\nline1-source\n");
        HgTestUtils.hg(repoDir, "resolve", "--mark", "conflict.txt");

        String continuedHex = new GraftCommand(repo).continueGraft();
        assertNotNull(continuedHex);

        String verify2 = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify2.toLowerCase().contains("integrity error"),
                "real hg verify must find no integrity errors after continueGraft() for combo " + combo + ": " + verify2);

        String catConflict = HgTestUtils.hg(repoDir, "cat", "-r", continuedHex, "conflict.txt");
        assertEquals("line1-dest\nline1-source", catConflict.trim());

        String resolveListAfter = HgTestUtils.hg(repoDir, "resolve", "--list");
        assertEquals("", resolveListAfter.trim(), "no unresolved files must remain for combo " + combo);
    }

    // ------------------------------------------------------------------------------------------
    // Backlog #39 follow-up (2026-09-06): GraftCommand's own crash-safety journal had the exact
    // v2-docket byte-length-only gap CommitCommand/RollbackCommand/RecoverCommand already fixed
    // (flagged live during that fix, deliberately left unaddressed pending this GraftCommand-
    // specific matrix pass -- see llm-wiki/decisions/mercurial-spec-compliance-requirement.md).
    // Two scenarios below mirror the two ways this was broken:
    //   (a) commitGraftedRevision()'s own IN-PROCESS catch-block restore (triggered here by a
    //       genuine failure injected via chmod, the same "no deterministic hook point" constraint
    //       RequirementMatrixRecoverCoreRoundTripTest's own class javadoc documents for
    //       CommitCommand -- see below for exactly which write this blocks and why);
    //   (b) the ON-DISK journal a real crash between a successful graft-commit and this class's
    //       own post-success cleanup would leave behind (hand-crafted exactly like
    //       RequirementMatrixRecoverCoreRoundTripTest does for CommitCommand, since there is no
    //       deterministic hook point for a real interrupted process here either).
    // ------------------------------------------------------------------------------------------

    private record RevlogSnapshot(boolean v2, long idxLen, long datLen, byte[] docketBytes,
                                   long resolvedIdxLen, long resolvedDatLen) {
    }

    private static RevlogSnapshot snapshotRevlog(File idxFile, File datFile) throws Exception {
        if (!idxFile.exists()) {
            return new RevlogSnapshot(false, 0, 0, null, 0, 0);
        }
        RevlogIndex probe = new RevlogIndex(idxFile);
        if (probe.isV2()) {
            byte[] docketBytes = Files.readAllBytes(idxFile.toPath());
            File ridx = probe.getResolvedIndexFile();
            File rdat = probe.getResolvedDataFile();
            long ridxLen = ridx != null && ridx.exists() ? ridx.length() : 0;
            long rdatLen = rdat != null && rdat.exists() ? rdat.length() : 0;
            return new RevlogSnapshot(true, 0, 0, docketBytes, ridxLen, rdatLen);
        }
        long idxLen = idxFile.length();
        long datLen = datFile.exists() ? datFile.length() : 0;
        return new RevlogSnapshot(false, idxLen, datLen, null, 0, 0);
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
            appendJournalLine(journalFile, "trunc " + storeRel(storeDir, ridx) + "\t" + snap.resolvedIdxLen());
            appendJournalLine(journalFile, "trunc " + storeRel(storeDir, rdat) + "\t" + snap.resolvedDatLen());
        } else {
            appendJournalLine(journalFile, storeRel(storeDir, idxFile) + "\t" + snap.idxLen());
            appendJournalLine(journalFile, storeRel(storeDir, datFile) + "\t" + snap.datLen());
        }
    }

    /**
     * Scenario (a): the in-process catch block inside {@code GraftCommand#commitGraftedRevision}.
     * {@code 00changelog.i} is made read-only right before the graft -- for a v2/docket combo,
     * {@code RevlogIndex#updateV2DocketSizes} ({@link Revlog#appendRevisionV2}'s final step)
     * writes the new {@code index_end}/{@code data_end} pointers directly in place via {@code
     * FileChannel.open(idxFile, WRITE)} (confirmed by reading its source -- no atomic rename is
     * involved, unlike e.g. bookmark/dirstate writes, which is exactly why this specific write is
     * the one that can be blocked this way), and by the time it runs, {@code
     * appendRevisionV2} has ALREADY appended the new revision's bytes to the docket's resolved
     * companion {@code .idx}/{@code .dat} files. So this reliably reproduces a genuine partial
     * write: the resolved companion files grow, then the transaction aborts. Pre-fix, {@code
     * GraftCommand} never even recorded those resolved companion files for a v2 changelog (it
     * only ever tracked {@code 00changelog.i}/{@code .d} by path, and {@code .d} does not exist
     * for a v2 changelog at all) -- so a failed graft permanently leaked the orphaned appended
     * bytes into the companion files. This asserts they are correctly truncated back afterward.
     * For the {@code cl1} combo this exercises the pre-existing (never broken) classic path as a
     * same-mechanism control.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jFailedGraftRestoresChangelogOnInProcessFailureAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "graft-inprocess-crash");

        Files.writeString(repoDir.toPath().resolve("base.txt"), "base\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");

        Files.writeString(repoDir.toPath().resolve("target.txt"), "on-target\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1 target");
        String targetHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "update", "0");
        Files.writeString(repoDir.toPath().resolve("source.txt"), "on-source\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c2 source");
        String sourceHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "update", targetHex);

        HgRepository repo = new HgRepository(repoDir);
        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        RevlogSnapshot preSnap = snapshotRevlog(clIdx, new File(repo.getStoreDir(), "00changelog.d"));
        String revsBefore = HgTestUtils.hg(repoDir, "log", "--template", "{node} ").trim();

        clIdx.setWritable(false);
        try {
            GraftCommand graft = new GraftCommand(repo).setSource(sourceHex);
            assertThrows(IOException.class, graft::call,
                    "the injected permission failure must surface as a real exception, not be silently swallowed, for combo " + combo);
        } finally {
            clIdx.setWritable(true);
        }

        // Belt-and-suspenders (matches CommitCommand's own design): the classic byte-length
        // restore truncates 00changelog.i itself IN PLACE, so while the injected permission fault
        // is still active it cannot complete either -- the secondary failure aborts the in-process
        // catch block before it reaches manifest/filelog restoration, correctly LEAVING the
        // on-disk journal behind for the next repository open's crash recovery to finish (this is
        // why only the v2/docket restore -- which replaces 00changelog.i via an atomic rename that
        // needs no write permission on the file itself -- can fully self-heal in-process). Finish
        // the job the same way a real restarted process would.
        if (new File(repo.getStoreDir(), "journal").exists()) {
            try (HgLock lock = repo.lockStore()) {
                // acquiring the lock alone triggers checkAndPerformAutoRollback()
            }
        }

        // The companion files a v2 changelog actually grows into must be back to their pre-graft
        // sizes -- pre-fix, GraftCommand never tracked them at all for a v2 changelog, leaking the
        // orphaned partial write. For cl1 the plain byte-length restore already covered this.
        RevlogSnapshot postSnap = snapshotRevlog(clIdx, new File(repo.getStoreDir(), "00changelog.d"));
        if (preSnap.v2()) {
            assertEquals(preSnap.resolvedIdxLen(), postSnap.resolvedIdxLen(),
                    "changelog's resolved index companion file must be truncated back after a failed graft for combo " + combo);
            assertEquals(preSnap.resolvedDatLen(), postSnap.resolvedDatLen(),
                    "changelog's resolved data companion file must be truncated back after a failed graft for combo " + combo);
        } else {
            assertEquals(preSnap.idxLen(), postSnap.idxLen(),
                    "classic changelog index must be truncated back after a failed graft for combo " + combo);
        }

        // No leftover crash-safety bookkeeping -- in-process (v2) or after crash recovery
        // finishes the job (classic, see above).
        assertFalse(new File(repo.getStoreDir(), "journal").exists(),
                "no leftover journal must remain after a handled failure for combo " + combo);

        String revsAfter = HgTestUtils.hg(repoDir, "log", "--template", "{node} ").trim();
        assertEquals(revsBefore, revsAfter, "no phantom grafted revision may remain visible for combo " + combo);

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.toLowerCase().contains("integrity error"),
                "real hg verify must find no integrity errors after a failed-and-recovered graft for combo " + combo + ": " + verify);
    }

    /**
     * Scenario (b): the on-disk crash-safety journal {@code commitGraftedRevision} leaves behind
     * for {@link HgRepository#checkAndPerformAutoRollback()} to replay on the next repository
     * open, simulating a real process kill between a successful graft-commit and this class's own
     * post-success journal cleanup (same hand-crafting technique as {@code
     * RequirementMatrixRecoverCoreRoundTripTest}, for the same reason: there is no deterministic
     * hook point for a genuinely interrupted process). Pre-fix, the byte-length-only journal
     * entries this used to write for a v2/docket changelog were a COMPLETE no-op restore target
     * (the docket's own byte length never changes across commits) -- so replaying them left the
     * grafted commit's changelog-v2 docket pointers exactly as the "crashed" transaction had
     * already advanced them, meaning the phantom grafted revision would have stayed fully visible
     * to real hg afterward instead of being rolled back. This proves the fixed journal format
     * (full-content docket backup + truncate-only companion-file entries) round-trips correctly
     * through {@link HgRepository#checkAndPerformAutoRollback()}.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jCrashRecoveryUndoesSuccessfulGraftFromHandCraftedJournalAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "graft-journal-crash");

        Files.writeString(repoDir.toPath().resolve("base.txt"), "base\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");

        Files.writeString(repoDir.toPath().resolve("target.txt"), "on-target\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c1 target");
        String targetHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "update", "0");
        Files.writeString(repoDir.toPath().resolve("source.txt"), "on-source\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c2 source");
        String sourceHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "update", targetHex);

        HgRepository repo = new HgRepository(repoDir);
        File storeDir = repo.getStoreDir();
        File clIdx = new File(storeDir, "00changelog.i");
        File clDat = new File(storeDir, "00changelog.d");
        File mfIdx = new File(storeDir, "00manifest.i");
        File mfDat = new File(storeDir, "00manifest.d");
        File flIdx = CommitCommand.getFilelogIndex(storeDir, "source.txt");
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");

        // 1. Snapshot the pre-graft physical state of every revlog the graft is about to touch,
        // plus the pre-graft dirstate (the working copy's clean, pre-graft state).
        RevlogSnapshot clSnap = snapshotRevlog(clIdx, clDat);
        RevlogSnapshot mfSnap = snapshotRevlog(mfIdx, mfDat);
        RevlogSnapshot flSnap = snapshotRevlog(flIdx, flDat);
        File dirstateFile = new File(repoDir, ".hg/dirstate");
        byte[] dirstateBackup = Files.readAllBytes(dirstateFile.toPath());
        String revsBeforeGraft = HgTestUtils.hg(repoDir, "log", "--template", "{node} ").trim();

        // 2. hg4j performs a REAL, successful graft (exercises the exact same code this fix
        // touches, on the success path -- must not regress).
        String graftedHex = new GraftCommand(repo).setSource(sourceHex).call();
        assertNotNull(graftedHex);
        String verifyAfterGraft = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verifyAfterGraft.toLowerCase().contains("integrity error"),
                "the successful graft itself must still be clean for combo " + combo + ": " + verifyAfterGraft);

        // 3. Hand-craft the journal + dirstate backup a crash between the graft's commit and its
        // own post-success cleanup would have left behind.
        File journalFile = new File(storeDir, "journal");
        Files.deleteIfExists(journalFile.toPath());
        appendJournalLine(journalFile, "dirstate");
        File dirstateBackupFile = new File(repoDir, ".hg/dirstate.backup");
        Files.write(dirstateBackupFile.toPath(), dirstateBackup);
        writeRestoreJournalLines(journalFile, storeDir, clIdx, clDat, clSnap);
        writeRestoreJournalLines(journalFile, storeDir, mfIdx, mfDat, mfSnap);
        writeRestoreJournalLines(journalFile, storeDir, flIdx, flDat, flSnap);

        // 4. Trigger crash recovery on a fresh repository handle, exactly as a real re-opened
        // process would (HgRepository#lockStore() calls checkAndPerformAutoRollback() first).
        HgRepository crashedRepo = new HgRepository(repoDir);
        try (HgLock lock = crashedRepo.lockStore()) {
            // acquiring the lock alone triggers checkAndPerformAutoRollback()
        }
        assertFalse(journalFile.exists(), "journal must be consumed after a successful crash recovery for combo " + combo);

        // 5. real hg must see exactly the pre-graft commit set -- NOT the phantom grafted
        // revision. Pre-fix, this is exactly where a changelog-v2/general-v2 combo would have
        // failed: the byte-length-only journal entry was a no-op, so the grafted revision would
        // have stayed fully visible here.
        String revsAfterRecovery = HgTestUtils.hg(repoDir, "log", "--template", "{node} ").trim();
        assertEquals(revsBeforeGraft, revsAfterRecovery,
                "the phantom grafted revision must be rolled back by crash recovery for combo " + combo);
        assertFalse(revsAfterRecovery.contains(graftedHex),
                "the grafted commit must no longer be visible after crash recovery for combo " + combo);

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.toLowerCase().contains("integrity error"),
                "real hg verify must find no integrity errors after crash recovery for combo " + combo + ": " + verify);

        String parents = HgTestUtils.hg(repoDir, "parents", "--template", "{node}");
        assertEquals(targetHex, parents, "dirstate parent must be reverted to the pre-graft destination for combo " + combo);
    }
}
