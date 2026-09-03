package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.errors.HgCorruptDataException;

public class RevertCommandTest {

    @Test
    public void callThrowsWhenFileNotSet(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        assertThrows(IllegalStateException.class, () -> new RevertCommand(repo).call());
    }

    @Test
    public void callThrowsWhenFileIsEmptyString(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        assertThrows(IllegalStateException.class, () -> new RevertCommand(repo).setFile("").call());
    }

    @Test
    public void emptyRevisionStringBehavesLikeUnsetRevision(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File f = new File(tempDir.toFile(), "a.txt");
        Files.writeString(f.toPath(), "original");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("first").call();

        Files.writeString(f.toPath(), "changed locally");
        assertTrue(new RevertCommand(repo).setFile("a.txt").setRevision("").call());

        assertEquals("original", Files.readString(f.toPath()));
    }

    @Test
    public void revertsModifiedTrackedFileBackToLastCommittedContent(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File f = new File(tempDir.toFile(), "a.txt");
        Files.writeString(f.toPath(), "original");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("first").call();

        Files.writeString(f.toPath(), "changed locally");
        assertTrue(new RevertCommand(repo).setFile("a.txt").call());

        assertEquals("original", Files.readString(f.toPath()));
        assertEquals('n', repo.getDirstate().getEntries().get("a.txt").getState());
    }

    @Test
    public void revertsAddedButUncommittedFileByDeletingAndUntracking(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File f = new File(tempDir.toFile(), "new.txt");
        Files.writeString(f.toPath(), "not committed yet");
        new AddCommand(repo).addFile("new.txt").call();

        assertTrue(new RevertCommand(repo).setFile("new.txt").call());

        assertFalse(f.exists());
        assertFalse(repo.getDirstate().getEntries().containsKey("new.txt"));
    }

    @Test
    public void throwsWhenNoCommitsExistAndFileIsNotAnAddedEntry(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        assertThrows(HgValidationException.class,
                () -> new RevertCommand(repo).setFile("untracked.txt").call());
    }

    @Test
    public void revertsToAnExplicitlySpecifiedOlderRevision(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File f = new File(tempDir.toFile(), "a.txt");
        Files.writeString(f.toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        Files.writeString(f.toPath(), "v1");
        new CommitCommand(repo).setMessage("rev1").call();

        Files.writeString(f.toPath(), "v1 with local edits");
        assertTrue(new RevertCommand(repo).setFile("a.txt").setRevision("0").call());

        assertEquals("v0", Files.readString(f.toPath()));
    }

    @Test
    public void revertsFileNotPresentAtTargetRevisionByDeletingAndUntracking(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File f = new File(tempDir.toFile(), "a.txt");
        Files.writeString(f.toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        // File is added in a later commit, so it did not exist at rev0.
        File added = new File(tempDir.toFile(), "later.txt");
        Files.writeString(added.toPath(), "added later");
        new AddCommand(repo).addFile("later.txt").call();
        new CommitCommand(repo).setMessage("rev1").call();

        assertTrue(new RevertCommand(repo).setFile("later.txt").setRevision("0").call());

        assertFalse(added.exists());
        assertFalse(repo.getDirstate().getEntries().containsKey("later.txt"));
    }

    @Test
    public void revertsExecutableFilePreservingExecutableBit(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File f = new File(tempDir.toFile(), "run.sh");
        Files.writeString(f.toPath(), "#!/bin/sh\necho hi\n");
        assertTrue(f.setExecutable(true, false));
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("add executable").call();

        Files.writeString(f.toPath(), "tampered");
        assertTrue(new RevertCommand(repo).setFile("run.sh").call());

        assertEquals("#!/bin/sh\necho hi\n", Files.readString(f.toPath()));
        assertTrue(f.canExecute(), "Executable bit must be restored on revert");
    }

    @Test
    public void revertsAddedButUncommittedFileAlreadyDeletedFromDiskByOnlyUntracking(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File f = new File(tempDir.toFile(), "new.txt");
        Files.writeString(f.toPath(), "not committed yet");
        new AddCommand(repo).addFile("new.txt").call();
        Files.delete(f.toPath());

        assertTrue(new RevertCommand(repo).setFile("new.txt").call());

        assertFalse(f.exists());
        assertFalse(repo.getDirstate().getEntries().containsKey("new.txt"));
    }

    @Test
    public void revertsUntrackedFileAtTargetRevisionAlreadyDeletedFromDisk(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File f = new File(tempDir.toFile(), "a.txt");
        Files.writeString(f.toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        File added = new File(tempDir.toFile(), "later.txt");
        Files.writeString(added.toPath(), "added later");
        new AddCommand(repo).addFile("later.txt").call();
        new CommitCommand(repo).setMessage("rev1").call();
        Files.delete(added.toPath());

        assertTrue(new RevertCommand(repo).setFile("later.txt").setRevision("0").call());

        assertFalse(added.exists());
        assertFalse(repo.getDirstate().getEntries().containsKey("later.txt"));
    }

    @Test
    public void revertsDeletedTrackedFileByRecreatingItFromHistory(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File f = new File(tempDir.toFile(), "a.txt");
        Files.writeString(f.toPath(), "original");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("first").call();

        Files.delete(f.toPath());
        assertFalse(f.exists());

        assertTrue(new RevertCommand(repo).setFile("a.txt").call());

        assertEquals("original", Files.readString(f.toPath()));
    }

    @Test
    public void revertsTrackedFileOverADanglingSymlinkLeftAtItsPath(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File f = new File(tempDir.toFile(), "a.txt");
        Files.writeString(f.toPath(), "original");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("first").call();

        Files.delete(f.toPath());
        Files.createSymbolicLink(f.toPath(), Path.of("does-not-exist.txt"));
        assertTrue(Files.isSymbolicLink(f.toPath()));
        assertFalse(f.exists(), "A dangling symlink must not report as existing");

        assertTrue(new RevertCommand(repo).setFile("a.txt").call());

        assertFalse(Files.isSymbolicLink(f.toPath()), "Dangling symlink must be replaced by the reverted file");
        assertEquals("original", Files.readString(f.toPath()));
    }

    @Test
    public void propagatesUnrelatedIOExceptionsFromCatCommand(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File f = new File(tempDir.toFile(), "a.txt");
        Files.writeString(f.toPath(), "original");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("first").call();

        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "a.txt");
        assertTrue(flIdx.exists());
        Files.delete(flIdx.toPath());

        IOException ex = assertThrows(IOException.class, () -> new RevertCommand(repo).setFile("a.txt").call());
        assertTrue(ex instanceof HgCorruptDataException, "Expected the underlying CatCommand failure to propagate unchanged");
        assertFalse(ex.getMessage().contains("File not tracked at target revision"));
    }

    @Test
    public void revertsSymlinkToItsCommittedTarget(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File target = new File(tempDir.toFile(), "target.txt");
        Files.writeString(target.toPath(), "target content");
        File link = new File(tempDir.toFile(), "link.txt");
        Files.createSymbolicLink(link.toPath(), Path.of("target.txt"));
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("add symlink").call();

        Files.delete(link.toPath());
        Files.writeString(link.toPath(), "no longer a symlink");

        assertTrue(new RevertCommand(repo).setFile("link.txt").call());

        assertTrue(Files.isSymbolicLink(link.toPath()), "Revert must restore the symlink form");
        assertEquals("target.txt", Files.readSymbolicLink(link.toPath()).toString());
    }
}
