package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.github.search5.hg4j.errors.HgValidationException;

public class RevertCommandTest {

    @Test
    public void callThrowsWhenFileNotSet(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        assertThrows(IllegalStateException.class, () -> new RevertCommand(repo).call());
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
