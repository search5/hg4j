package com.github.search5.hg4j.api;

import com.github.search5.hg4j.core.*;
import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

public class CrashInjectionTestHarness {
    public static class InterruptibleFileOutputStream extends FileOutputStream {
        private final long maxWritesBeforeCrash;
        private long currentWrites = 0;

        public InterruptibleFileOutputStream(File file, long maxWritesBeforeCrash) throws FileNotFoundException {
            super(file);
            this.maxWritesBeforeCrash = maxWritesBeforeCrash;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            for (int i = 0; i < len; i++) {
                if (currentWrites >= maxWritesBeforeCrash) {
                    throw new IOException("CRASH_INJECTED: Simulated process crash (kill -9) during disk I/O!");
                }
                super.write(b[off + i]);
                currentWrites++;
            }
        }
    }

    public static void verifyCommitCrashRollback(HgRepository repo, File testFile, long crashAtByte) throws Exception {
        File journalFile = new File(repo.getStoreDir(), "journal");
        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        long origClSize = clIdx.exists() ? clIdx.length() : 0L;

        CommitCommand commitCmd = new CommitCommand(repo);

        try {
            Files.writeString(testFile.toPath(), "Modified content for crash simulation\n");
            new AddCommand(repo).call();
            commitCmd.setMessage("This commit will crash").call();
            fail("Commit should have failed due to injected crash!");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("CRASH_INJECTED"));
        }

        assertFalse(journalFile.exists(), "Journal file must be cleaned up after rollback");
        if (clIdx.exists()) {
            assertEquals(origClSize, clIdx.length(), "Changelog index must rollback to original size");
        }
    }
}
