package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;

import static org.junit.jupiter.api.Assertions.*;

public class HgRenameTest {

    @TempDir
    File tempDir;

    @Test
    public void testFileRenameAndCopyMapRegistration() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();

        try (Hg hg = Hg.wrap(repo)) {
            // Write source file
            File srcFile = new File(tempDir, "source.txt");
            Files.writeString(srcFile.toPath(), "Copy-rename target SCM contents");

            hg.add().addFile("source.txt").call();
            hg.commit().setAuthor("Tester").setMessage("add source file").call();

            // Execute RenameCommand
            hg.rename()
              .setSource("source.txt")
              .setTarget("target.txt")
              .call();

            // Verify physical move
            assertFalse(new File(tempDir, "source.txt").exists());
            assertTrue(new File(tempDir, "target.txt").exists());

            // Verify SCM dirstate states
            Dirstate dirstate = repo.getDirstate();

            // Source: marked as removed ('r')
            Dirstate.Entry srcEntry = dirstate.getEntries().get("source.txt");
            assertNotNull(srcEntry);
            assertEquals('r', srcEntry.getState());

            // Target: marked as added ('a')
            Dirstate.Entry destEntry = dirstate.getEntries().get("target.txt");
            assertNotNull(destEntry);
            assertEquals('a', destEntry.getState());

            // Copy mapping must be linked Target -> Source
            assertEquals("source.txt", dirstate.getCopyMap().get("target.txt"));

            // The physical file content moved along with the rename
            assertEquals("Copy-rename target SCM contents", Files.readString(new File(tempDir, "target.txt").toPath()));

            // No crash journal or dirstate backup should linger after a successful rename
            assertFalse(new File(repo.getStoreDir(), "journal").exists());
            assertFalse(new File(tempDir, ".hg/dirstate.backup").exists());
        }
    }

    @Test
    public void testConstructorRejectsNullRepository() {
        assertThrows(IllegalArgumentException.class, () -> new RenameCommand(null));
    }

    @Test
    public void testCallWithoutSourceThrows() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        try (Hg hg = Hg.wrap(repo)) {
            RenameCommand cmd = hg.rename().setTarget("target.txt");
            assertThrows(IllegalStateException.class, cmd::call);
        }
    }

    @Test
    public void testCallWithoutTargetThrows() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        try (Hg hg = Hg.wrap(repo)) {
            RenameCommand cmd = hg.rename().setSource("source.txt");
            assertThrows(IllegalStateException.class, cmd::call);
        }
    }

    @Test
    public void testRenameMissingSourceFileThrows() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        try (Hg hg = Hg.wrap(repo)) {
            RenameCommand cmd = hg.rename().setSource("missing.txt").setTarget("target.txt");
            HgRepositoryNotFoundException ex = assertThrows(HgRepositoryNotFoundException.class, cmd::call);
            assertTrue(ex.getMessage().contains("missing.txt"));
        }
    }

    @Test
    public void testRenameUntrackedFileBeforeAnyDirstateExists() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();

        // No add/commit has happened yet, so .hg/dirstate does not exist -
        // exercises the "no prior dirstate to back up" branch.
        assertFalse(new File(tempDir, ".hg/dirstate").exists());

        try (Hg hg = Hg.wrap(repo)) {
            File srcFile = new File(tempDir, "untracked.txt");
            Files.writeString(srcFile.toPath(), "untracked contents");

            hg.rename()
              .setSource("untracked.txt")
              .setTarget("renamed.txt")
              .call();

            assertFalse(srcFile.exists());
            assertTrue(new File(tempDir, "renamed.txt").exists());

            Dirstate dirstate = repo.getDirstate();
            assertEquals('r', dirstate.getEntries().get("untracked.txt").getState());
            assertEquals('a', dirstate.getEntries().get("renamed.txt").getState());
            assertEquals("untracked.txt", dirstate.getCopyMap().get("renamed.txt"));
        }
    }

    @Test
    public void testRenameExecutableFilePreservesExecuteBit() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        try (Hg hg = Hg.wrap(repo)) {
            File srcFile = new File(tempDir, "script.sh");
            Files.writeString(srcFile.toPath(), "#!/bin/sh\necho hi\n");
            assumeExecutableSupported(srcFile);

            hg.rename()
              .setSource("script.sh")
              .setTarget("script-renamed.sh")
              .call();

            File destFile = new File(tempDir, "script-renamed.sh");
            assertTrue(destFile.exists());
            assertTrue(destFile.canExecute());

            Dirstate dirstate = repo.getDirstate();
            Dirstate.Entry destEntry = dirstate.getEntries().get("script-renamed.sh");
            assertEquals(0755, destEntry.getMode());
        }
    }

    @Test
    public void testRenameNonExecutableFileUsesRegularMode() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        try (Hg hg = Hg.wrap(repo)) {
            File srcFile = new File(tempDir, "plain.txt");
            Files.writeString(srcFile.toPath(), "plain contents");
            srcFile.setExecutable(false, false);

            hg.rename()
              .setSource("plain.txt")
              .setTarget("plain-renamed.txt")
              .call();

            Dirstate dirstate = repo.getDirstate();
            Dirstate.Entry destEntry = dirstate.getEntries().get("plain-renamed.txt");
            assertEquals(0644, destEntry.getMode());
        }
    }

    @Test
    public void testRenameIntoNewSubdirectoryCreatesParent() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        try (Hg hg = Hg.wrap(repo)) {
            File srcFile = new File(tempDir, "source.txt");
            Files.writeString(srcFile.toPath(), "content");

            File subdir = new File(tempDir, "nested/deeper");
            assertFalse(subdir.exists());

            hg.rename()
              .setSource("source.txt")
              .setTarget("nested/deeper/target.txt")
              .call();

            assertTrue(subdir.isDirectory());
            assertTrue(new File(tempDir, "nested/deeper/target.txt").exists());
        }
    }

    @Test
    public void testRenameOverwritesExistingTargetFile() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        try (Hg hg = Hg.wrap(repo)) {
            File srcFile = new File(tempDir, "source.txt");
            Files.writeString(srcFile.toPath(), "new contents");

            File existingTarget = new File(tempDir, "target.txt");
            Files.writeString(existingTarget.toPath(), "old contents that should be replaced");

            hg.rename()
              .setSource("source.txt")
              .setTarget("target.txt")
              .call();

            assertEquals("new contents", Files.readString(existingTarget.toPath()));
        }
    }

    @Test
    public void testChainedRenameKeepsLatestCopySource() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        try (Hg hg = Hg.wrap(repo)) {
            File srcFile = new File(tempDir, "a.txt");
            Files.writeString(srcFile.toPath(), "chained rename content");
            hg.add().addFile("a.txt").call();
            hg.commit().setAuthor("Tester").setMessage("add a").call();

            hg.rename().setSource("a.txt").setTarget("b.txt").call();
            hg.rename().setSource("b.txt").setTarget("c.txt").call();

            assertFalse(new File(tempDir, "a.txt").exists());
            assertFalse(new File(tempDir, "b.txt").exists());
            assertTrue(new File(tempDir, "c.txt").exists());

            Dirstate dirstate = repo.getDirstate();
            assertEquals("b.txt", dirstate.getCopyMap().get("c.txt"));
            assertEquals("a.txt", dirstate.getCopyMap().get("b.txt"));
            assertEquals('r', dirstate.getEntries().get("b.txt").getState());
        }
    }

    @Test
    public void testRenameFailureRestoresDirstateBackupAndCleansJournal() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        try (Hg hg = Hg.wrap(repo)) {
            File committedFile = new File(tempDir, "committed.txt");
            Files.writeString(committedFile.toPath(), "committed content");
            hg.add().addFile("committed.txt").call();
            hg.commit().setAuthor("Tester").setMessage("initial commit").call();

            byte[] dirstateBeforeFailedRename = Files.readAllBytes(new File(tempDir, ".hg/dirstate").toPath());

            File srcFile = new File(tempDir, "victim.txt");
            Files.writeString(srcFile.toPath(), "will fail to move");

            // Make the destination's parent directory impossible to create:
            // a regular file already occupies that path segment, so mkdirs()
            // silently fails and the subsequent Files.move() throws.
            File blocker = new File(tempDir, "blocker");
            Files.writeString(blocker.toPath(), "I am a file, not a directory");

            RenameCommand cmd = hg.rename()
                    .setSource("victim.txt")
                    .setTarget("blocker/sub/target.txt");

            assertThrows(IOException.class, cmd::call);

            // Source file was never moved because the failure happened during Files.move
            assertTrue(srcFile.exists());

            // Dirstate on disk must be restored to its pre-attempt content
            byte[] dirstateAfterFailure = Files.readAllBytes(new File(tempDir, ".hg/dirstate").toPath());
            assertArrayEquals(dirstateBeforeFailedRename, dirstateAfterFailure);

            // Crash-recovery scaffolding must be cleaned up even on failure
            assertFalse(new File(repo.getStoreDir(), "journal").exists());
        }
    }

    @Test
    public void testRenameFailureWithoutPriorDirstateStillCleansJournal() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        assertFalse(new File(tempDir, ".hg/dirstate").exists());

        try (Hg hg = Hg.wrap(repo)) {
            File srcFile = new File(tempDir, "victim.txt");
            Files.writeString(srcFile.toPath(), "will fail to move, no prior dirstate");

            File blocker = new File(tempDir, "blocker");
            Files.writeString(blocker.toPath(), "I am a file, not a directory");

            RenameCommand cmd = hg.rename()
                    .setSource("victim.txt")
                    .setTarget("blocker/sub/target.txt");

            assertThrows(IOException.class, cmd::call);

            assertTrue(srcFile.exists());
            assertFalse(new File(tempDir, ".hg/dirstate").exists());
            assertFalse(new File(repo.getStoreDir(), "journal").exists());
        }
    }

    private static void assumeExecutableSupported(File file) {
        boolean toggled = file.setExecutable(true, false);
        Assumptions.assumeTrue(toggled && file.canExecute(),
                "Executable bit is not supported on this filesystem");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Symlink handling: RenameCommand had NO lstat-aware handling at all
    // (unlike AddCommand/CommitCommand/CopyCommand/...) -- File#exists()/
    // canExecute()/length()/lastModified() all follow a symlink to whatever
    // it points at instead of describing the link itself. Fixed 2026-09-03.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    public void testRenameSymlinkPreservesLinkAndRecordsLstatAwareDirstate() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        try (Hg hg = Hg.wrap(repo)) {
            File targetFile = new File(tempDir, "target.txt");
            Files.writeString(targetFile.toPath(), "hello");
            File linkFile = new File(tempDir, "link.txt");
            Files.createSymbolicLink(linkFile.toPath(), Path.of("target.txt"));

            hg.rename().setSource("link.txt").setTarget("renamed-link.txt").call();

            File renamed = new File(tempDir, "renamed-link.txt");
            assertTrue(Files.isSymbolicLink(renamed.toPath()), "the rename must move the symlink itself, not dereference it into a copy of its target's content");
            assertEquals("target.txt", Files.readSymbolicLink(renamed.toPath()).toString());
            assertFalse(Files.isSymbolicLink(linkFile.toPath()) || linkFile.exists());

            Dirstate.Entry destEntry = repo.getDirstate().getEntries().get("renamed-link.txt");
            assertNotNull(destEntry);
            assertEquals(0120000, destEntry.getMode(), "a renamed symlink's dirstate mode must be the 0120000 symlink sentinel, not derived from File#canExecute() following the link");
            assertEquals("target.txt".length(), destEntry.getSize(), "a symlink's dirstate size must be its own target-path-string length (lstat), not File#length() of whatever it points at");
        }
    }

    @Test
    public void testRenameDanglingSymlinkDoesNotThrow() throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir).call();
        try (Hg hg = Hg.wrap(repo)) {
            File linkFile = new File(tempDir, "dangling-link.txt");
            Files.createSymbolicLink(linkFile.toPath(), Path.of("does-not-exist.txt"));

            // File#exists() follows a symlink and returns false for a dangling target -- the
            // command's own source-existence check must use lstat semantics instead, exactly
            // like AddCommand already does ("A symlink is valid to commit even when its target
            // is missing... real hg tracks it regardless").
            hg.rename().setSource("dangling-link.txt").setTarget("renamed-dangling.txt").call();

            File renamed = new File(tempDir, "renamed-dangling.txt");
            assertTrue(Files.isSymbolicLink(renamed.toPath()));
            assertEquals("does-not-exist.txt", Files.readSymbolicLink(renamed.toPath()).toString());
        }
    }
}
