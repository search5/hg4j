package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.RevlogIndex;
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
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Map;

/**
 * Extends the requirement matrix (see {@link RequirementMatrixBackoutCoreRoundTripTest} for the
 * reused pattern, and backlog #39 / {@code exhaustive-interop-matrix-plan.md} §1) to {@link
 * RecoverCommand} across the native 6-combo grid (changelog family x treemanifest, dirstate fixed
 * at v1 -- v2 needs Docker, see {@code RequirementMatrixRecoverDockerRoundTripTest}).
 *
 * <p>A genuinely-crashed transaction cannot be triggered deterministically (there is no hook point
 * inside {@link CommitCommand#call()} between "journal written" and "journal cleaned up on
 * success" -- pre-commit hooks run before the transaction even starts and post-commit hooks run
 * after it has already succeeded and cleaned up). This test therefore hand-crafts the leftover
 * {@code .hg/store/journal} a real interrupted commit would have left behind, exactly the
 * technique {@link RecoverCommandTest#testRecoverRestoresSimulatedCrashedTransaction} already uses
 * for the default combo (see its own javadoc) -- extended here to be v2/docket-aware (a
 * changelog-v2/general-v2 revlog's "index" file never changes size across commits, only its own
 * content does, so restoring it needs a full-content "{@code backup <orig>\t<backup>}" journal
 * line instead of a byte-length one; see {@code CommitCommand#recordRevlogRollbackState}'s javadoc
 * for the full story, and the private helpers below for how this test reproduces that exact
 * format):
 * <ol>
 *   <li>real hg creates the combo repository and commits a baseline revision c0 (a root file plus
 *   a nested {@code sub/a.txt}, so treemanifest combos actually engage a directory manifest).</li>
 *   <li>this test snapshots the changelog/manifest/dirstate's pre-c1 physical state.</li>
 *   <li>hg4j commits c1 (a successful, non-interrupted commit -- its own journal is written then
 *   cleaned up normally).</li>
 *   <li>this test hand-crafts {@code .hg/store/journal} from the step-2 snapshot, simulating what
 *   would be left behind had the process crashed between c1's writes and its journal cleanup.</li>
 *   <li>hg4j's {@link RecoverCommand} is run and must detect + successfully roll back the journal.</li>
 *   <li>real hg verifies the repository is back to exactly c0 (single revision, correct tip
 *   content, correct dirstate parent, no integrity errors).</li>
 * </ol>
 */
@Tag("interop")
public class RequirementMatrixRecoverCoreRoundTripTest {

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

    // -- crash-journal fabrication helpers (see class javadoc) -----------------------------

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

    private static void appendJournalLine(File journalFile, String entry) throws Exception {
        Files.writeString(journalFile.toPath(), entry + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Re-derives {@code idxFile}'s relative-to-storeDir path with a {@code store/} prefix
     * (relative to hgDir), matching the journal line convention {@code
     * HgRepository#checkAndPerformAutoRollback} parses. */
    private static String storeRel(File storeDir, File f) {
        return "store/" + storeDir.toPath().relativize(f.toPath()).toString().replace('\\', '/');
    }

    /** Writes journal lines that would restore {@code idxFile}/{@code datFile} back to {@code
     * snap}'s pre-commit state, re-resolving the (unchanged, since ordinary appends never rotate a
     * v2 docket's UUIDs) v2 companion files AFTER the commit to get their current paths. */
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
            // "trunc " (never delete-on-zero): a v2 revlog's companion files always physically
            // exist as long as the docket references them, even at 0 bytes (e.g. a fresh
            // sidedata companion before anything ever needed sidedata) -- see
            // CommitCommand#recordRevlogRollbackState's javadoc for the full story.
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jRecoverAfterSimulatedCrashedCommitAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "recover");

        Files.writeString(repoDir.toPath().resolve("a.txt"), "root-base\n");
        Files.createDirectories(repoDir.toPath().resolve("sub"));
        Files.writeString(repoDir.toPath().resolve("sub/a.txt"), "sub-base\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");
        String c0Hex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgRepository repo = new HgRepository(repoDir);
        File storeDir = repo.getStoreDir();
        File clIdx = new File(storeDir, "00changelog.i");
        File clDat = new File(storeDir, "00changelog.d");
        File mfIdx = new File(storeDir, "00manifest.i");
        File mfDat = new File(storeDir, "00manifest.d");
        File flIdx = CommitCommand.getFilelogIndex(storeDir, "sub/a.txt");
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        // meta/sub/00manifest.i -- only ever populated for treemanifest combos; harmless no-op
        // (stays absent both before and after) for flatmanifest ones.
        File dlIdx = new File(storeDir, "meta/sub/00manifest.i");
        File dlDat = new File(storeDir, "meta/sub/00manifest.d");

        // 1. Snapshot pre-c1 physical state: changelog, manifest, the directory manifest (if
        // treemanifest), AND the filelog for the one file c1 actually changes (a real interrupted
        // commit's journal covers every revlog it touched, not just changelog/manifest --
        // omitting the filelog here left it un-rolled-back and made real hg's own `hg verify`
        // immediately flag it as pointing at a nonexistent changeset, caught live while first
        // writing this test).
        RevlogSnapshot clSnap = snapshotRevlog(clIdx, clDat);
        RevlogSnapshot mfSnap = snapshotRevlog(mfIdx, mfDat);
        RevlogSnapshot dlSnap = snapshotRevlog(dlIdx, dlDat);
        RevlogSnapshot flSnap = snapshotRevlog(flIdx, flDat);
        File dirstateFile = new File(repoDir, ".hg/dirstate");
        byte[] dirstateBackup = Files.readAllBytes(dirstateFile.toPath());

        // 2. hg4j commits c1 successfully (its own journal is written then cleaned up normally).
        Files.writeString(repoDir.toPath().resolve("sub/a.txt"), "sub-changed-by-c1\n");
        new CommitCommand(repo).setAuthor("dev").setMessage("c1").call();

        // 3. Hand-craft the journal + dirstate backup a crash between c1's writes and its own
        // journal cleanup would have left behind.
        File journalFile = new File(storeDir, "journal");
        Files.deleteIfExists(journalFile.toPath());
        appendJournalLine(journalFile, "dirstate");
        File dirstateBackupFile = new File(repoDir, ".hg/dirstate.backup");
        Files.write(dirstateBackupFile.toPath(), dirstateBackup);
        writeRestoreJournalLines(journalFile, storeDir, clIdx, clDat, clSnap);
        writeRestoreJournalLines(journalFile, storeDir, mfIdx, mfDat, mfSnap);
        if (dlIdx.exists() || dlSnap.idxLen() > 0) {
            writeRestoreJournalLines(journalFile, storeDir, dlIdx, dlDat, dlSnap);
        }
        writeRestoreJournalLines(journalFile, storeDir, flIdx, flDat, flSnap);

        // 4. Run hg4j's RecoverCommand against the "crashed" repository.
        HgRepository crashedRepo = new HgRepository(repoDir);
        RecoverCommand.RecoverResult result = new RecoverCommand(crashedRepo).call();
        assertTrue(result.wasInterrupted(), "the fabricated journal must be detected for combo " + combo);
        assertTrue(result.isSuccess(), "recovery of a well-formed journal must succeed for combo " + combo);
        assertFalse(journalFile.exists(), "journal must be consumed after a successful recovery for combo " + combo);

        // 5. real hg must see exactly c0, nothing more.
        String revs = HgTestUtils.hg(repoDir, "log", "--template", "{rev}\\n").trim();
        assertEquals("0", revs, "only c0 must remain after recovery for combo " + combo);

        String tipHex = HgTestUtils.hg(repoDir, "log", "-r", "tip", "--template", "{node}");
        assertEquals(c0Hex, tipHex, "tip must be c0 after recovery for combo " + combo);

        assertEquals("sub-base", HgTestUtils.hg(repoDir, "cat", "-r", "tip", "sub/a.txt").trim(),
                "sub/a.txt content must be reverted to c0 for combo " + combo);

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.toLowerCase().contains("integrity error") || verify.toLowerCase().contains("error:"),
                "real hg verify must find no integrity errors after recovery for combo " + combo + ": " + verify);

        String parents = HgTestUtils.hg(repoDir, "parents", "--template", "{node}");
        assertEquals(c0Hex, parents, "dirstate parent must be reverted to c0 for combo " + combo);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("combos")
    public void hg4jRecoverWithNoJournalIsNoOpAcrossCombo(RequirementCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = initWithCombo(tempDir, combo, "recover-noop");

        Files.writeString(repoDir.toPath().resolve("a.txt"), "content\n");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "dev", "-m", "c0");

        HgRepository repo = new HgRepository(repoDir);
        RecoverCommand.RecoverResult result = new RecoverCommand(repo).call();
        assertFalse(result.wasInterrupted(), "a healthy repository has nothing to recover for combo " + combo);
        assertTrue(result.isSuccess());

        String revs = HgTestUtils.hg(repoDir, "log", "--template", "{rev}\\n").trim();
        assertEquals("0", revs, "the no-op recover must not disturb the repository for combo " + combo);
    }
}
