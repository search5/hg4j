package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;

public class ShelveCommandTest {

    @Test
    public void testShelveAndUnshelveBasic(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        
        // 1. Initialize empty repo and create baseline commit
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repository = new HgRepository(repoDir);

        File file = new File(repoDir, "a.txt");
        Files.writeString(file.toPath(), "initial content");

        new AddCommand(repository).addFile("a.txt").call();
        byte[] baseCommit = new CommitCommand(repository)
                .setAuthor("user <user@example.com>")
                .setMessage("Initial commit")
                .call();

        // 2. Modify file and write to dirstate
        Files.writeString(file.toPath(), "modified content");
        Dirstate dirstate = repository.getDirstate();
        dirstate.addEntry("a.txt", new Dirstate.Entry('m', 0644, 16, System.currentTimeMillis() / 1000));
        repository.writeDirstate(dirstate);

        // 3. Perform Shelve
        ShelveCommand cmd = new ShelveCommand(repository);
        cmd.setName("test-shelve");
        cmd.call();

        // Check that shelve folder and files exist
        File shelvedDir = new File(repository.getHgDir(), "shelved");
        assertTrue(new File(shelvedDir, "test-shelve.state").exists());
        assertTrue(new File(shelvedDir, "test-shelve.hg").exists());

        // 4. Try to Unshelve but with modified parent1 to trigger mismatch exception (W1)
        Dirstate currentDirstate = repository.getDirstate();
        byte[] differentParent1 = new byte[20];
        differentParent1[0] = 0x0B; // Change parent
        currentDirstate.setParents(differentParent1, new byte[20]);
        repository.writeDirstate(currentDirstate);

        ShelveCommand unshelveCmd = new ShelveCommand(repository);
        unshelveCmd.setName("test-shelve");
        unshelveCmd.setUnshelve(true);

        IOException ex = assertThrows(IOException.class, () -> unshelveCmd.call());
        assertTrue(ex.getMessage().contains("does not match shelved parent"));

        // 4a. Restore correct parent1 but try mismatched shelveName
        currentDirstate = repository.getDirstate();
        currentDirstate.setParents(baseCommit, new byte[20]);
        repository.writeDirstate(currentDirstate);

        ShelveCommand unshelveCmdMismatchedName = new ShelveCommand(repository);
        unshelveCmdMismatchedName.setName("mismatched-name");
        unshelveCmdMismatchedName.setUnshelve(true);

        // Mismatched name should throw file not found, but if we rename file to match it should throw name mismatch
        // Actually, if stateFile doesn't exist under mismatched-name, it throws "Shelve file not found".
        // Let's test mismatched parent2 instead!
        currentDirstate = repository.getDirstate();
        byte[] differentParent2 = new byte[20];
        differentParent2[0] = 0x0C; // Change parent2
        currentDirstate.setParents(baseCommit, differentParent2);
        repository.writeDirstate(currentDirstate);

        IOException exParent2 = assertThrows(IOException.class, () -> unshelveCmd.call());
        assertTrue(exParent2.getMessage().contains("does not match shelved parent2"));

        // 4b. Test mismatched shelveName in state file
        currentDirstate = repository.getDirstate();
        currentDirstate.setParents(baseCommit, new byte[20]);
        repository.writeDirstate(currentDirstate);

        File stateFile = new File(shelvedDir, "test-shelve.state");
        String stateContent = Files.readString(stateFile.toPath(), StandardCharsets.UTF_8);
        String tamperedContent = stateContent.replace("test-shelve", "tampered-name");
        Files.writeString(stateFile.toPath(), tamperedContent, StandardCharsets.UTF_8);

        IOException exName = assertThrows(IOException.class, () -> unshelveCmd.call());
        assertTrue(exName.getMessage().contains("Shelve name mismatch"));

        // Restore state file name content
        Files.writeString(stateFile.toPath(), stateContent, StandardCharsets.UTF_8);

        // 5. Restore correct parent1 and perform unshelve successfully
        currentDirstate = repository.getDirstate();
        currentDirstate.setParents(baseCommit, new byte[20]);
        repository.writeDirstate(currentDirstate);

        unshelveCmd.call();
        assertFalse(new File(shelvedDir, "test-shelve.state").exists());
        assertFalse(new File(shelvedDir, "test-shelve.hg").exists());
        assertEquals("modified content", Files.readString(file.toPath()));
    }

    @Test
    public void shelveAndUnshelveHandlesAddedModifiedAndRemovedFilesTogether(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repository = new HgRepository(repoDir);

        File keep = new File(repoDir, "keep.txt");
        Files.writeString(keep.toPath(), "keep original");
        File toRemove = new File(repoDir, "toremove.txt");
        Files.writeString(toRemove.toPath(), "will be removed");
        new AddCommand(repository).call();
        new CommitCommand(repository).setAuthor("u <u@example.com>").setMessage("baseline").call();

        // Pending changes: modify keep.txt, remove toremove.txt, add new.txt.
        Files.writeString(keep.toPath(), "keep modified");
        new RemoveCommand(repository).setFile("toremove.txt").call();
        File added = new File(repoDir, "new.txt");
        Files.writeString(added.toPath(), "brand new file");
        new AddCommand(repository).addFile("new.txt").call();

        new ShelveCommand(repository).setName("mixed").call();

        // Working directory must be restored to the clean baseline.
        assertEquals("keep original", Files.readString(keep.toPath()));
        assertTrue(toRemove.exists(), "Removed file must be restored to disk after shelving");
        assertFalse(added.exists(), "Newly added file must be removed from disk after shelving");

        new ShelveCommand(repository).setName("mixed").setUnshelve(true).call();

        // Pending changes must be reapplied exactly as they were before shelving.
        assertEquals("keep modified", Files.readString(keep.toPath()));
        assertFalse(toRemove.exists(), "Removed file must stay removed after unshelving");
        assertEquals("brand new file", Files.readString(added.toPath()));
        assertEquals('r', repository.getDirstate().getEntries().get("toremove.txt").getState());
        assertEquals('a', repository.getDirstate().getEntries().get("new.txt").getState());
    }

    @Test
    public void shelveDetectsARacyWriteThatKeepsTheSameSizeAndMtimeAsDirstate(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repository = new HgRepository(repoDir);

        File file = new File(repoDir, "a.txt");
        Files.writeString(file.toPath(), "AAAAAAAAAA");
        new AddCommand(repository).call();
        new CommitCommand(repository).setAuthor("u <u@example.com>").setMessage("baseline").call();

        long recordedMtime = repository.getDirstate().getEntries().get("a.txt").getTime();

        // Same byte length as the committed content, but different bytes, with the on-disk mtime
        // forced back to exactly the dirstate-recorded second -- indistinguishable from "unchanged"
        // by size/mtime alone.
        Files.writeString(file.toPath(), "BBBBBBBBBB");
        assertTrue(file.setLastModified(recordedMtime * 1000));

        new ShelveCommand(repository).setName("racy").call();

        assertEquals("AAAAAAAAAA", Files.readString(file.toPath()),
                "The racy content change must have been detected and shelved");

        new ShelveCommand(repository).setName("racy").setUnshelve(true).call();
        assertEquals("BBBBBBBBBB", Files.readString(file.toPath()));
    }

    @Test
    public void shelveIgnoresAByteIdenticalRacyRewrite(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repository = new HgRepository(repoDir);

        File file = new File(repoDir, "a.txt");
        Files.writeString(file.toPath(), "AAAAAAAAAA");
        new AddCommand(repository).call();
        new CommitCommand(repository).setAuthor("u <u@example.com>").setMessage("baseline").call();

        long recordedMtime = repository.getDirstate().getEntries().get("a.txt").getTime();

        // Rewritten with byte-identical content and the mtime forced back to the same second --
        // the racy-write guard must fall back to a real content comparison and find no change.
        Files.writeString(file.toPath(), "AAAAAAAAAA");
        assertTrue(file.setLastModified(recordedMtime * 1000));

        new ShelveCommand(repository).setName("racy-same").call();

        assertFalse(new File(repository.getHgDir(), "shelved/racy-same.state").exists(),
                "A byte-identical racy rewrite must not be treated as a pending change");
    }

    @Test
    public void callIsNoOpWhenThereAreNoPendingChangesToShelve(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repository = new InitCommand().setDirectory(repoDir).call();
        new ShelveCommand(repository).setName("empty").call();

        assertFalse(new File(repository.getHgDir(), "shelved/empty.state").exists(),
                "No shelve artifacts should be created when there is nothing pending");
    }
}
