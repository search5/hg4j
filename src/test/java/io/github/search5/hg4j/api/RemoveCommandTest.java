package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

public class RemoveCommandTest {

    @Test
    public void testRemoveMissingFileThrows(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        
        RemoveCommand cmd = new RemoveCommand(repo).setFile("non_existent.txt");
        assertThrows(IOException.class, cmd::call);
    }

    @Test
    public void testRemoveAddedFileWithoutForceThrows(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f = new File(repoDir, "added.txt");
        Files.writeString(f.toPath(), "added");
        new AddCommand(repo).call();

        // Newly added file cannot be removed without force
        RemoveCommand cmd = new RemoveCommand(repo).setFile("added.txt");
        IOException ex = assertThrows(IOException.class, cmd::call);
        assertTrue(ex.getMessage().contains("uncommitted changes (added)"));
    }

    @Test
    public void testRemoveModifiedFileRacyHgThrows(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f = new File(repoDir, "test.txt");
        Files.writeString(f.toPath(), "Original content\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("commit 1").call();

        // Track original dirstate size and time
        Dirstate d = repo.getDirstate();
        Dirstate.Entry origEntry = d.getEntries().get("test.txt");
        assertNotNull(origEntry);

        // Modify the file but manipulate size and timestamp to mock racy-hg
        // (same size, same timestamp, but content changed)
        Files.writeString(f.toPath(), "Modified content\n"); // same length (17 bytes)
        f.setLastModified(origEntry.getTime() * 1000); // restore original timestamp

        // Trying to remove without force should throw IOException because of content mismatch (racy-hg check)
        RemoveCommand cmd = new RemoveCommand(repo).setFile("test.txt");
        IOException ex = assertThrows(IOException.class, cmd::call);
        assertTrue(ex.getMessage().contains("uncommitted changes (modified)"), 
                "Must detect modification via byte level content check: " + ex.getMessage());

        // Under force, it should succeed and mark as removed
        new RemoveCommand(repo).setFile("test.txt").setForce(true).call();
        assertFalse(f.exists(), "File should be deleted from disk");
        
        Dirstate finalDirstate = repo.getDirstate();
        assertEquals('r', finalDirstate.getEntries().get("test.txt").getState(), "State must be 'r'");
    }

    @Test
    public void testRemoveNullFilePathThrows(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        RemoveCommand cmd = new RemoveCommand(repo).setFile(null);
        IllegalStateException ex = assertThrows(IllegalStateException.class, cmd::call);
        assertTrue(ex.getMessage().contains("File path must be specified"));
    }

    @Test
    public void testRemoveEmptyFilePathThrows(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        RemoveCommand cmd = new RemoveCommand(repo).setFile("");
        IllegalStateException ex = assertThrows(IllegalStateException.class, cmd::call);
        assertTrue(ex.getMessage().contains("File path must be specified"));
    }

    @Test
    public void testRemoveTrackedFileWithNoFilelogSkipsRacyCheck(@TempDir Path tempDir) throws Exception {
        // Simulates a dirstate entry manually marked 'n' (normal) whose filelog was never
        // actually created (e.g. no commit happened yet). The racy-hg content check must be
        // skipped entirely (flIdx.exists() == false) rather than throwing.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f = new File(repoDir, "ghost.txt");
        Files.writeString(f.toPath(), "ghost content");

        Dirstate dirstate = repo.getDirstate();
        long size = f.length();
        long time = f.lastModified() / 1000;
        dirstate.addEntry("ghost.txt", new Dirstate.Entry('n', 0, (int) size, time));
        repo.writeDirstate(dirstate);

        // Sanity check: no filelog index exists for this file since it was never committed.
        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "ghost.txt");
        assertFalse(flIdx.exists());

        new RemoveCommand(repo).setFile("ghost.txt").call();

        assertFalse(f.exists(), "File should be deleted from disk");
        Dirstate finalDirstate = repo.getDirstate();
        assertEquals('r', finalDirstate.getEntries().get("ghost.txt").getState());
    }

    @Test
    public void testRemoveTrackedFileWithEmptyFilelogSkipsContentCheck(@TempDir Path tempDir) throws Exception {
        // Filelog index file exists but has zero revisions (revisionCount() == 0), so the
        // content-level racy-hg comparison must be skipped.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f = new File(repoDir, "empty_flog.txt");
        Files.writeString(f.toPath(), "some content");

        Dirstate dirstate = repo.getDirstate();
        long size = f.length();
        long time = f.lastModified() / 1000;
        dirstate.addEntry("empty_flog.txt", new Dirstate.Entry('n', 0, (int) size, time));
        repo.writeDirstate(dirstate);

        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "empty_flog.txt");
        flIdx.getParentFile().mkdirs();
        Files.createFile(flIdx.toPath()); // zero-length index file => revisionCount() == 0

        new RemoveCommand(repo).setFile("empty_flog.txt").call();

        assertFalse(f.exists(), "File should be deleted from disk");
        Dirstate finalDirstate = repo.getDirstate();
        assertEquals('r', finalDirstate.getEntries().get("empty_flog.txt").getState());
    }

    @Test
    public void testRemoveSwallowsExceptionFromCorruptFilelogDuringRacyCheck(@TempDir Path tempDir) throws Exception {
        // The racy-hg content-level comparison is wrapped in a broad try/catch that swallows
        // any exception (e.g. a corrupt or missing revlog data file) and simply treats the
        // file as not dirty. Deleting the filelog's .d data file after a real commit forces
        // Revlog#getRevisionContent to throw, exercising that catch block.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f = new File(repoDir, "corrupt.txt");
        Files.writeString(f.toPath(), "Original content\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("commit 1").call();

        Dirstate d = repo.getDirstate();
        Dirstate.Entry origEntry = d.getEntries().get("corrupt.txt");
        assertNotNull(origEntry);
        // Keep size/time matching so isDirty starts false and the racy-hg content check runs.
        f.setLastModified(origEntry.getTime() * 1000);

        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "corrupt.txt");
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        assertTrue(flIdx.exists());
        Files.deleteIfExists(flDat.toPath());

        new RemoveCommand(repo).setFile("corrupt.txt").call();

        assertFalse(f.exists(), "File should be deleted from disk despite corrupt filelog data");
        Dirstate finalDirstate = repo.getDirstate();
        assertEquals('r', finalDirstate.getEntries().get("corrupt.txt").getState());
    }

    @Test
    public void testRemoveSwallowsExceptionWhenDirstateRestoreItselfFails(@TempDir Path tempDir) throws Exception {
        // RemoveCommand's failure handler tries to restore the pre-call dirstate bytes via
        // SafeFileIO.writeAtomic and swallows any exception from that restore attempt. To reach
        // that specific catch, both (1) the primary repository.writeDirstate(...) call and (2)
        // the subsequent restore call must fail. Both go through SafeFileIO.writeAtomic on the
        // same dirstate file, which serializes via a same-named ".lock" file. Occupying that
        // lock-file path with a NON-EMPTY directory makes RandomAccessFile(lockFile, "rw") fail
        // with "Is a directory" on every attempt: a plain/empty directory would get silently
        // removed by SafeFileIO's own best-effort finally-block cleanup after the first failure
        // (letting the second, restore, attempt succeed and leaving this catch uncovered), but
        // Files.deleteIfExists on a non-empty directory throws DirectoryNotEmptyException, which
        // that same finally block also swallows -- so the obstruction persists for both calls.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f = new File(repoDir, "willfail.txt");
        Files.writeString(f.toPath(), "content");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("commit 1").call();

        File dirstateFile = new File(repoDir, ".hg/dirstate");
        assertTrue(dirstateFile.exists());
        byte[] originalDirstateBytes = Files.readAllBytes(dirstateFile.toPath());

        File lockDir = new File(repoDir, ".hg/dirstate.lock");
        Files.createDirectory(lockDir.toPath());
        Files.write(new File(lockDir, "blocker").toPath(), "x".getBytes(), StandardOpenOption.CREATE);

        try {
            RemoveCommand cmd = new RemoveCommand(repo).setFile("willfail.txt");
            assertThrows(IOException.class, cmd::call);

            // The lock obstruction means writeAtomic never got past lock acquisition to actually
            // replace dirstateFile, in either the primary write or the restore attempt -- so the
            // on-disk dirstate bytes must be exactly what they were before the call.
            assertArrayEquals(originalDirstateBytes, Files.readAllBytes(dirstateFile.toPath()));
        } finally {
            Files.deleteIfExists(new File(lockDir, "blocker").toPath());
            Files.deleteIfExists(lockDir.toPath());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Symlink handling: RemoveCommand had NO lstat-aware handling at all --
    // File#isFile()/length()/lastModified() all follow a symlink to whatever
    // it points at. For a symlink pointing at an EXISTING file, this made the
    // "is it dirty?" check compare the TARGET's size against the symlink's own
    // (unrelated) tracked size, spuriously reporting an untouched symlink as
    // modified. For a DANGLING symlink, File#exists() follows and returns
    // false, so the physical delete step was skipped entirely -- the link was
    // marked removed in the dirstate but never actually deleted from disk.
    // Fixed 2026-09-03.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    public void testRemoveUntouchedSymlinkToExistingTargetDoesNotSpuriouslyThrow(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File targetFile = new File(repoDir, "target.txt");
        // Deliberately NOT the same length as the symlink's own target-path string ("target.txt"
        // is 10 chars) -- if the dirty check ever compares the TARGET's size instead of the
        // symlink's own, this reproduces the spurious-dirty bug deterministically.
        Files.writeString(targetFile.toPath(), "a much longer file body than the symlink's own name");

        File linkFile = new File(repoDir, "link.txt");
        Files.createSymbolicLink(linkFile.toPath(), Path.of("target.txt"));

        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("v1").setAuthor("dev").call();

        // The symlink itself was never touched after the commit -- removing it without force
        // must succeed, not throw "uncommitted changes (modified)".
        RemoveCommand cmd = new RemoveCommand(repo).setFile("link.txt");
        assertTrue(cmd.call());

        assertEquals('r', repo.getDirstate().getEntries().get("link.txt").getState());
    }

    @Test
    public void testRemoveDanglingSymlinkActuallyDeletesItFromDisk(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File linkFile = new File(repoDir, "dangling.txt");
        Files.createSymbolicLink(linkFile.toPath(), Path.of("does-not-exist.txt"));

        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("v1").setAuthor("dev").call();

        RemoveCommand cmd = new RemoveCommand(repo).setFile("dangling.txt");
        assertTrue(cmd.call());

        assertFalse(Files.isSymbolicLink(linkFile.toPath()) || linkFile.exists(),
                "a removed dangling symlink must actually be deleted from disk, not just marked removed in the dirstate");
        assertEquals('r', repo.getDirstate().getEntries().get("dangling.txt").getState());
    }
}
