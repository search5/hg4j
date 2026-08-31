package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

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
        String stateContent = Files.readString(stateFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
        String tamperedContent = stateContent.replace("test-shelve", "tampered-name");
        Files.writeString(stateFile.toPath(), tamperedContent, java.nio.charset.StandardCharsets.UTF_8);

        IOException exName = assertThrows(IOException.class, () -> unshelveCmd.call());
        assertTrue(exName.getMessage().contains("Shelve name mismatch"));

        // Restore state file name content
        Files.writeString(stateFile.toPath(), stateContent, java.nio.charset.StandardCharsets.UTF_8);

        // 5. Restore correct parent1 and perform unshelve successfully
        currentDirstate = repository.getDirstate();
        currentDirstate.setParents(baseCommit, new byte[20]);
        repository.writeDirstate(currentDirstate);

        unshelveCmd.call();
        assertFalse(new File(shelvedDir, "test-shelve.state").exists());
        assertFalse(new File(shelvedDir, "test-shelve.hg").exists());
        assertEquals("modified content", Files.readString(file.toPath()));
    }
}
