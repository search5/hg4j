package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import com.github.search5.hg4j.errors.HgValidationException;
import com.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CopyCommand} ({@code hg copy}). Real hg semantics used below were verified
 * live against the {@code hg} CLI (v7.2) on throwaway scratch repositories - see class javadoc
 * on {@link CopyCommand} for a summary of each verified behavior.
 */
public class CopyCommandTest {

    @TempDir
    File tempDir;

    @Test
    public void testConstructorRejectsNullRepository() {
        assertThrows(IllegalArgumentException.class, () -> new CopyCommand(null));
    }

    @Test
    public void testCallWithoutSourceThrows() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        CopyCommand cmd = new CopyCommand(repo).setDestination("dest.txt");
        assertThrows(IllegalStateException.class, cmd::call);
    }

    @Test
    public void testCallWithoutDestinationThrows() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        CopyCommand cmd = new CopyCommand(repo).setSource("source.txt");
        assertThrows(IllegalStateException.class, cmd::call);
    }

    @Test
    public void testCopyMissingSourceFileThrows() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        CopyCommand cmd = new CopyCommand(repo).setSource("missing.txt").setDestination("dest.txt");
        HgRepositoryNotFoundException ex = assertThrows(HgRepositoryNotFoundException.class, cmd::call);
        assertTrue(ex.getMessage().contains("missing.txt"));
    }

    @Test
    public void testCopyUntrackedSourceThrows() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        try (Hg hg = Hg.wrap(repo)) {
            File srcFile = new File(tempDir, "untracked.txt");
            Files.writeString(srcFile.toPath(), "untracked contents");

            // Never `hg add`-ed, so the dirstate has no entry for it at all.
            CopyCommand cmd = new CopyCommand(repo).setSource("untracked.txt").setDestination("dest.txt");
            HgValidationException ex = assertThrows(HgValidationException.class, cmd::call);
            assertTrue(ex.getMessage().contains("untracked.txt"));

            // Nothing should have been created at the destination.
            assertFalse(new File(tempDir, "dest.txt").exists());
        }
    }

    @Test
    public void testCopyRemovedSourceThrows() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        try (Hg hg = Hg.wrap(repo)) {
            File srcFile = new File(tempDir, "source.txt");
            Files.writeString(srcFile.toPath(), "content");
            hg.add().addFile("source.txt").call();
            hg.commit().setAuthor("Tester").setMessage("add source").call();

            // `hg remove` also deletes the working-copy file; recreate it at the same path so
            // this exercises the "state == 'r'" rejection specifically rather than the earlier
            // "file does not exist on disk" one. Verified live: real hg says exactly
            // "not copying - file has been marked for remove" in this scenario.
            hg.remove().setFile("source.txt").call();
            Files.writeString(srcFile.toPath(), "resurrected content");

            CopyCommand cmd = new CopyCommand(repo).setSource("source.txt").setDestination("dest.txt");
            assertThrows(HgValidationException.class, cmd::call);
        }
    }

    @Test
    public void testBasicCopyPreservesOriginalAndRecordsCopySource() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        try (Hg hg = Hg.wrap(repo)) {
            File srcFile = new File(tempDir, "a.txt");
            Files.writeString(srcFile.toPath(), "original contents");
            hg.add().addFile("a.txt").call();
            hg.commit().setAuthor("Tester").setMessage("add a").call();

            new CopyCommand(repo).setSource("a.txt").setDestination("b.txt").call();

            // Original is untouched, on disk and in the dirstate.
            assertTrue(srcFile.exists());
            assertEquals("original contents", Files.readString(srcFile.toPath()));

            File destFile = new File(tempDir, "b.txt");
            assertTrue(destFile.exists());
            assertEquals("original contents", Files.readString(destFile.toPath()));

            Dirstate dirstate = repo.getDirstate();
            Dirstate.Entry srcEntry = dirstate.getEntries().get("a.txt");
            assertNotNull(srcEntry);
            assertEquals('n', srcEntry.getState(), "source must remain tracked/clean, not removed");

            Dirstate.Entry destEntry = dirstate.getEntries().get("b.txt");
            assertNotNull(destEntry);
            assertEquals('a', destEntry.getState());

            assertEquals("a.txt", dirstate.getCopyMap().get("b.txt"));

            // No crash journal or dirstate backup should linger after a successful copy
            assertFalse(new File(repo.getStoreDir(), "journal").exists());
            assertFalse(new File(tempDir, ".hg/dirstate.backup").exists());
        }
    }

    @Test
    public void testCopyOntoExistingFileWithoutForceThrowsAndLeavesDestinationUntouched() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        try (Hg hg = Hg.wrap(repo)) {
            File srcFile = new File(tempDir, "a.txt");
            Files.writeString(srcFile.toPath(), "source contents");
            hg.add().addFile("a.txt").call();
            hg.commit().setAuthor("Tester").setMessage("add a").call();

            File existing = new File(tempDir, "existing.txt");
            Files.writeString(existing.toPath(), "pre-existing untracked contents");

            CopyCommand cmd = new CopyCommand(repo).setSource("a.txt").setDestination("existing.txt");
            HgValidationException ex = assertThrows(HgValidationException.class, cmd::call);
            assertTrue(ex.getMessage().contains("existing.txt"));

            // Real hg refuses and leaves the pre-existing file's contents alone.
            assertEquals("pre-existing untracked contents", Files.readString(existing.toPath()));
            assertNull(repo.getDirstate().getCopyMap().get("existing.txt"));
        }
    }

    @Test
    public void testCopyOntoExistingFileWithForceOverwrites() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        try (Hg hg = Hg.wrap(repo)) {
            File srcFile = new File(tempDir, "a.txt");
            Files.writeString(srcFile.toPath(), "source contents");
            hg.add().addFile("a.txt").call();
            hg.commit().setAuthor("Tester").setMessage("add a").call();

            File existing = new File(tempDir, "existing.txt");
            Files.writeString(existing.toPath(), "will be overwritten");

            new CopyCommand(repo).setSource("a.txt").setDestination("existing.txt").setForce(true).call();

            assertEquals("source contents", Files.readString(existing.toPath()));
            assertEquals("a.txt", repo.getDirstate().getCopyMap().get("existing.txt"));
        }
    }

    /**
     * Verified live against real hg: copying a file that is itself already an uncommitted copy
     * chains the destination's recorded source all the way back to the ORIGINAL original, not
     * the immediate source - because the immediate source's own copy-source metadata is still
     * live in the dirstate (it was never committed in between).
     * <p>
     * {@code a (committed) -> copy a b (uncommitted) -> copy b c (uncommitted)}: c's recorded
     * source is {@code a.txt}.
     */
    @Test
    public void testCopyOfUncommittedCopyChainsToOriginalSource() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        try (Hg hg = Hg.wrap(repo)) {
            File srcFile = new File(tempDir, "a.txt");
            Files.writeString(srcFile.toPath(), "chained content");
            hg.add().addFile("a.txt").call();
            hg.commit().setAuthor("Tester").setMessage("add a").call();

            new CopyCommand(repo).setSource("a.txt").setDestination("b.txt").call();
            new CopyCommand(repo).setSource("b.txt").setDestination("c.txt").call();

            assertTrue(new File(tempDir, "a.txt").exists());
            assertTrue(new File(tempDir, "b.txt").exists());
            assertTrue(new File(tempDir, "c.txt").exists());

            Dirstate dirstate = repo.getDirstate();
            assertEquals("a.txt", dirstate.getCopyMap().get("b.txt"));
            // Chains through to the ORIGINAL original, since b.txt was never committed.
            assertEquals("a.txt", dirstate.getCopyMap().get("c.txt"));
        }
    }

    /**
     * Verified live against real hg: once a copy is committed, its own copy-source metadata is
     * no longer live, so a further copy of it records the immediate (not original) source.
     * <p>
     * {@code a (committed) -> copy a b -> commit -> copy b c}: c's recorded source is
     * {@code b.txt}, not {@code a.txt}.
     */
    @Test
    public void testCopyOfCommittedCopyChainsToImmediateSourceOnly() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        try (Hg hg = Hg.wrap(repo)) {
            File srcFile = new File(tempDir, "a.txt");
            Files.writeString(srcFile.toPath(), "chained content");
            hg.add().addFile("a.txt").call();
            hg.commit().setAuthor("Tester").setMessage("add a").call();

            new CopyCommand(repo).setSource("a.txt").setDestination("b.txt").call();
            hg.commit().setAuthor("Tester").setMessage("copy a to b").call();

            new CopyCommand(repo).setSource("b.txt").setDestination("c.txt").call();

            Dirstate dirstate = repo.getDirstate();
            assertEquals("b.txt", dirstate.getCopyMap().get("c.txt"));
        }
    }

    /**
     * Verified live against real hg: copying a freshly `hg add`-ed file that was never itself a
     * copy of anything records NO copy metadata at all for the destination (real hg prints "has
     * not been committed yet, so no copy data will be stored" and just adds the destination as
     * a brand new file).
     */
    @Test
    public void testCopyOfUncommittedNonCopyAddedFileStoresNoCopyMetadata() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        try (Hg hg = Hg.wrap(repo)) {
            File srcFile = new File(tempDir, "fresh.txt");
            Files.writeString(srcFile.toPath(), "brand new, never committed");
            hg.add().addFile("fresh.txt").call();

            new CopyCommand(repo).setSource("fresh.txt").setDestination("freshcopy.txt").call();

            Dirstate dirstate = repo.getDirstate();
            Dirstate.Entry destEntry = dirstate.getEntries().get("freshcopy.txt");
            assertNotNull(destEntry);
            assertEquals('a', destEntry.getState());
            assertNull(dirstate.getCopyMap().get("freshcopy.txt"));
        }
    }

    @Test
    public void testCopySymlinkPreservesSymlinkness() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        try (Hg hg = Hg.wrap(repo)) {
            File targetFile = new File(tempDir, "real.txt");
            Files.writeString(targetFile.toPath(), "target contents");

            File linkFile = new File(tempDir, "link.txt");
            Files.createSymbolicLink(linkFile.toPath(), new File("real.txt").toPath());

            hg.add().addFile("real.txt").call();
            hg.add().addFile("link.txt").call();
            hg.commit().setAuthor("Tester").setMessage("add real and link").call();

            new CopyCommand(repo).setSource("link.txt").setDestination("linkcopy.txt").call();

            File copiedLink = new File(tempDir, "linkcopy.txt");
            assertTrue(Files.isSymbolicLink(copiedLink.toPath()), "copy of a symlink must itself be a symlink");
            assertEquals("real.txt", Files.readSymbolicLink(copiedLink.toPath()).toString());

            // The original symlink must be untouched.
            assertTrue(Files.isSymbolicLink(linkFile.toPath()));
            assertEquals("real.txt", Files.readSymbolicLink(linkFile.toPath()).toString());

            Dirstate dirstate = repo.getDirstate();
            assertEquals("link.txt", dirstate.getCopyMap().get("linkcopy.txt"));
        }
    }

    @Test
    public void testCopyPreservesExecuteBit() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        try (Hg hg = Hg.wrap(repo)) {
            File srcFile = new File(tempDir, "script.sh");
            Files.writeString(srcFile.toPath(), "#!/bin/sh\necho hi\n");
            assumeExecutableSupported(srcFile);
            hg.add().addFile("script.sh").call();
            hg.commit().setAuthor("Tester").setMessage("add script").call();

            new CopyCommand(repo).setSource("script.sh").setDestination("script-copy.sh").call();

            File destFile = new File(tempDir, "script-copy.sh");
            assertTrue(destFile.exists());
            assertTrue(destFile.canExecute());

            Dirstate dirstate = repo.getDirstate();
            assertEquals(0755, dirstate.getEntries().get("script-copy.sh").getMode());
        }
    }

    @Test
    public void testCopyIntoNewSubdirectoryCreatesParent() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        try (Hg hg = Hg.wrap(repo)) {
            File srcFile = new File(tempDir, "a.txt");
            Files.writeString(srcFile.toPath(), "content");
            hg.add().addFile("a.txt").call();
            hg.commit().setAuthor("Tester").setMessage("add a").call();

            File subdir = new File(tempDir, "nested/deeper");
            assertFalse(subdir.exists());

            new CopyCommand(repo).setSource("a.txt").setDestination("nested/deeper/b.txt").call();

            assertTrue(subdir.isDirectory());
            assertTrue(new File(tempDir, "nested/deeper/b.txt").exists());
            assertTrue(srcFile.exists());
        }
    }

    @Test
    public void testCopyFailureRestoresDirstateBackupAndCleansJournal() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        try (Hg hg = Hg.wrap(repo)) {
            File committedFile = new File(tempDir, "committed.txt");
            Files.writeString(committedFile.toPath(), "committed content");
            hg.add().addFile("committed.txt").call();
            hg.commit().setAuthor("Tester").setMessage("initial commit").call();

            byte[] dirstateBeforeFailedCopy = Files.readAllBytes(new File(tempDir, ".hg/dirstate").toPath());

            // Make the destination's parent directory impossible to create: a regular file
            // already occupies that path segment, so mkdirs() silently fails and the
            // subsequent Files.copy() throws.
            File blocker = new File(tempDir, "blocker");
            Files.writeString(blocker.toPath(), "I am a file, not a directory");

            CopyCommand cmd = new CopyCommand(repo)
                    .setSource("committed.txt")
                    .setDestination("blocker/sub/target.txt");

            assertThrows(java.io.IOException.class, cmd::call);

            // Source file must never be touched by a failed copy.
            assertTrue(committedFile.exists());
            assertEquals("committed content", Files.readString(committedFile.toPath()));

            byte[] dirstateAfterFailure = Files.readAllBytes(new File(tempDir, ".hg/dirstate").toPath());
            assertArrayEquals(dirstateBeforeFailedCopy, dirstateAfterFailure);

            assertFalse(new File(repo.getStoreDir(), "journal").exists());
        }
    }

    private static void assumeExecutableSupported(File file) {
        boolean toggled = file.setExecutable(true, false);
        org.junit.jupiter.api.Assumptions.assumeTrue(toggled && file.canExecute(),
                "Executable bit is not supported on this filesystem");
    }
}
