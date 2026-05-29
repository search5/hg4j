package org.hg4j.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class JournalingCrashRecoveryTest {

    @Test
    public void testCrashRecoveryFromJournal(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        
        File clIdx;
        File clDat;
        File dirstateFile;
        long origIdxSize;
        long origDatSize;
        File dirstateBackupFile;
        File journalFile;

        try (HgRepository repo = new HgRepository(repoDir)) {
            repo.getStoreDir().mkdirs();

            clIdx = new File(repo.getStoreDir(), "00changelog.i");
            clDat = new File(repo.getStoreDir(), "00changelog.d");
            dirstateFile = new File(repo.getHgDir(), "dirstate");

            // Prepare initial files
            Files.writeString(clIdx.toPath(), "Changelog Index V1\n");
            Files.writeString(clDat.toPath(), "Changelog Data V1\n");
            Files.writeString(dirstateFile.toPath(), "Dirstate V1\n");

            origIdxSize = clIdx.length();
            origDatSize = clDat.length();

            // Create backup files (dirstate backup simulating pre-transaction state)
            dirstateBackupFile = new File(repo.getHgDir(), "dirstate.backup");
            Files.writeString(dirstateBackupFile.toPath(), "Dirstate V1\n");

            // Simulate a partial commit by writing journal entries and then appending partial data
            // 실제 hg journal 포맷: .hg/ 기준 상대 경로 (store/... 형식)
            journalFile = new File(repo.getStoreDir(), "journal");
            String journalContent = "dirstate\n" +
                    "store/00changelog.i " + origIdxSize + "\n" +
                    "store/00changelog.d " + origDatSize + "\n";
            Files.writeString(journalFile.toPath(), journalContent);

            // Write partial updates to files (simulating a crash mid-transaction)
            Files.writeString(clIdx.toPath(), "Changelog Index V1\nPARTIAL_INDEX_GARBAGE_APPEND\n");
            Files.writeString(clDat.toPath(), "Changelog Data V1\nPARTIAL_DATA_GARBAGE_APPEND\n");
            Files.writeString(dirstateFile.toPath(), "Dirstate V2_PARTIAL_CRASH_GARBAGE\n");
        }

        // Instantiate a new HgRepository and trigger auto-rollback via store lock acquisition
        try (HgRepository recoveredRepo = new HgRepository(repoDir)) {
            try (HgLock lock = recoveredRepo.lockStore()) {
                // lockStore() will trigger checkAndPerformAutoRollback
            }
        }

        // Verify journal and backups are deleted after successful rollback
        assertFalse(journalFile.exists(), "Journal should be deleted after successful rollback");
        assertFalse(dirstateBackupFile.exists(), "Backup files should be cleaned up");

        // Verify file sizes and contents are rolled back perfectly
        assertEquals("Changelog Index V1\n", Files.readString(clIdx.toPath()), "Index should be truncated to pre-transaction state");
        assertEquals("Changelog Data V1\n", Files.readString(clDat.toPath()), "Data should be truncated to pre-transaction state");
        assertEquals("Dirstate V1\n", Files.readString(dirstateFile.toPath()), "Dirstate should be restored from backup");
    }

    @Test
    public void testRebaseCrashRecoveryFromJournal(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        
        File clIdx;
        File clDat;
        File backupDir;
        File journalFile;

        try (HgRepository repo = new HgRepository(repoDir)) {
            repo.getStoreDir().mkdirs();

            clIdx = new File(repo.getStoreDir(), "00changelog.i");
            clDat = new File(repo.getStoreDir(), "00changelog.d");

            // 1. Prepare original history files
            Files.writeString(clIdx.toPath(), "Changelog Index Original Content (Large)\n");
            Files.writeString(clDat.toPath(), "Changelog Data Original Content (Large)\n");

            // 2. Prepare rebase-backup folder and physical backups
            backupDir = new File(repo.getStoreDir(), "rebase-backup");
            backupDir.mkdirs();
            File clIdxBackup = new File(backupDir, "00changelog.i");
            File clDatBackup = new File(backupDir, "00changelog.d");
            Files.writeString(clIdxBackup.toPath(), "Changelog Index Original Content (Large)\n");
            Files.writeString(clDatBackup.toPath(), "Changelog Data Original Content (Large)\n");

            // 3. Simulate a crash right after a physical strip (files are truncated/shrunk)
            Files.writeString(clIdx.toPath(), "Shrunk Index\n");
            Files.writeString(clDat.toPath(), "Shrunk Data\n");

            // 4. 리베이스 저널 시뮬레이션 (backup 항목의 경로는 프로제트 루트 기준)
            // 다른 항목은 네이티브 backup 엔트리로 다른 포맷 사용
            journalFile = new File(repo.getStoreDir(), "journal");
            String journalContent = "backup store/00changelog.i store/rebase-backup/00changelog.i\n" +
                    "backup store/00changelog.d store/rebase-backup/00changelog.d\n";
            Files.writeString(journalFile.toPath(), journalContent);
        }

        // 5. Instantiate a new HgRepository and trigger auto-rollback via lock acquisition
        try (HgRepository recoveredRepo = new HgRepository(repoDir)) {
            try (HgLock lock = recoveredRepo.lockStore()) {
                // lockStore() triggers checkAndPerformAutoRollback
            }
        }

        // 6. Verify journal and rebase-backup dir are deleted after successful recovery
        assertFalse(journalFile.exists(), "Rebase journal should be deleted");
        assertFalse(backupDir.exists(), "rebase-backup directory should be recursively deleted");

        // 7. Verify files are completely restored to original LARGE sizes from physical backups (Bypassing FileChannel.truncate limitation)
        assertEquals("Changelog Index Original Content (Large)\n", Files.readString(clIdx.toPath()), "Index should be recovered from physical copy");
        assertEquals("Changelog Data Original Content (Large)\n", Files.readString(clDat.toPath()), "Data should be recovered from physical copy");
    }
}
