package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RecoverCommand}, the explicit "hg recover" porcelain equivalent of
 * {@link HgRepository#checkAndPerformAutoRollback()}.
 *
 * Real hg semantics verified against `hg` v7.2 on scratch repositories (2026-09-02):
 * <ul>
 *   <li>Nothing to recover: prints {@code no interrupted transaction available} and exits 1.</li>
 *   <li>An interrupted transaction present: prints {@code rolling back interrupted transaction}
 *       (plus a note that the verify step was skipped) and exits 0; the journal is removed and
 *       backed-up store/dirstate files are restored.</li>
 *   <li>{@code hg recover --verify} performs the same rollback and then also runs
 *       {@code hg verify}, printing its output and folding its exit code into recover's own.</li>
 * </ul>
 */
public class RecoverCommandTest {

    @Test
    public void testRecoverWithNoInterruptedTransactionIsNoOp(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            RecoverCommand.RecoverResult result = new RecoverCommand(repo).call();

            assertFalse(result.wasInterrupted(), "no journal present -> nothing to recover");
            assertTrue(result.isSuccess(), "no-op recovery is trivially successful");
            assertNull(result.getVerifyErrors(), "verify was not requested");
        }
    }

    @Test
    public void testRecoverRestoresSimulatedCrashedTransaction(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        File clIdx;
        long origClIdxLen;
        File journalFile;
        File dirstateBackupFile;

        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            // 1. First successful commit establishes a known-good baseline.
            File f1 = new File(repoDir, "stable.txt");
            Files.writeString(f1.toPath(), "Stable Content");
            new AddCommand(repo).call();
            byte[] commitNode1 = new CommitCommand(repo).setMessage("Stable commit").call();
            assertNotNull(commitNode1);

            clIdx = new File(repo.getStoreDir(), "00changelog.i");
            origClIdxLen = clIdx.length();

            // 2. Simulate a crash mid-transaction: append garbage to changelog.i, mimicking a
            // half-completed commit, and hand-craft the journal that a real interrupted
            // transaction would have left behind (same fabrication technique used by
            // CommitCommandTest#testTransactionAutoRollbackRestoresChangelogAndDirstate).
            Files.write(clIdx.toPath(), new byte[100], StandardOpenOption.APPEND);
            assertEquals(origClIdxLen + 100, clIdx.length());

            journalFile = new File(repo.getStoreDir(), "journal");
            List<String> journalEntries = Arrays.asList(
                    "dirstate",
                    "store/00changelog.i " + origClIdxLen
            );
            Files.write(journalFile.toPath(), journalEntries, StandardCharsets.UTF_8);

            File dirstateFile = new File(repoDir, ".hg/dirstate");
            dirstateBackupFile = new File(repoDir, ".hg/dirstate.backup");
            Files.copy(dirstateFile.toPath(), dirstateBackupFile.toPath());
        }

        // 3. Re-open the "crashed" repository and explicitly run `hg recover`.
        try (HgRepository crashedRepo = new HgRepository(repoDir)) {
            RecoverCommand.RecoverResult result = new RecoverCommand(crashedRepo).call();

            assertTrue(result.wasInterrupted(), "journal was present -> there was something to recover");
            assertTrue(result.isSuccess(), "rollback of a well-formed journal must succeed");
            assertNull(result.getVerifyErrors(), "verify was not requested");
        }

        // 4. Matches real `hg recover`'s observable outcome: changelog.i truncated back to its
        // pre-crash size, journal and dirstate.backup consumed/removed.
        assertEquals(origClIdxLen, clIdx.length());
        assertFalse(journalFile.exists());
        assertFalse(dirstateBackupFile.exists());
    }

    @Test
    public void testRecoverWithVerifyRunsVerifyAfterSuccessfulRollback(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();

        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            File f1 = new File(repoDir, "a.txt");
            Files.writeString(f1.toPath(), "content");
            new AddCommand(repo).call();
            new CommitCommand(repo).setMessage("initial").call();

            // No journal -> nothing to recover, so verify must not run either.
            RecoverCommand.RecoverResult noJournal = new RecoverCommand(repo).setVerify(true).call();
            assertFalse(noJournal.wasInterrupted());
            assertNull(noJournal.getVerifyErrors(), "verify only runs after an actual recovery");

            // Fabricate a trivial interrupted transaction (a journal with nothing to touch)
            // purely to exercise the recover+verify path on an otherwise-healthy repository.
            File journalFile = new File(repo.getStoreDir(), "journal");
            Files.writeString(journalFile.toPath(), "");

            RecoverCommand.RecoverResult recovered = new RecoverCommand(repo).setVerify(true).call();
            assertTrue(recovered.wasInterrupted());
            assertTrue(recovered.isSuccess());
            assertNotNull(recovered.getVerifyErrors(), "verify was requested and rollback succeeded");
            assertTrue(recovered.getVerifyErrors().isEmpty(), "repository content itself was never touched");
        }
    }

    @Test
    public void testRecoverConstructorRejectsNullRepository() {
        assertThrows(IllegalArgumentException.class, () -> new RecoverCommand(null));
    }

    @Test
    public void testRecoverRetainsJournalAndReportsFailureWhenRollbackCannotComplete(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            repo.getStoreDir().mkdirs();

            // A malformed size entry makes the underlying rollback throw internally; the journal
            // must be retained for a future retry, matching checkAndPerformAutoRollback's own
            // failure contract (see HgRepositoryCoverageTest#testAutoRollbackRetainsJournalOnFailure).
            File journalFile = new File(repo.getStoreDir(), "journal");
            Files.writeString(journalFile.toPath(), "somefile.i not-a-number\n");

            RecoverCommand.RecoverResult result = new RecoverCommand(repo).call();

            assertTrue(result.wasInterrupted(), "a journal was present");
            assertFalse(result.isSuccess(), "rollback of a malformed journal cannot complete");
            assertTrue(journalFile.exists(), "journal must be retained for retry when rollback fails");
        }
    }
}
