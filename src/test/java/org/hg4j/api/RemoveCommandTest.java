package org.hg4j.api;

import org.hg4j.core.Dirstate;
import org.hg4j.core.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class RemoveCommandTest {

    @Test
    public void testRemoveMissingFileThrows(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        
        RemoveCommand cmd = Hg.remove(repo).setFile("non_existent.txt");
        assertThrows(IOException.class, cmd::call);
    }

    @Test
    public void testRemoveAddedFileWithoutForceThrows(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f = new File(repoDir, "added.txt");
        Files.writeString(f.toPath(), "added");
        Hg.add(repo).call();

        // Newly added file cannot be removed without force
        RemoveCommand cmd = Hg.remove(repo).setFile("added.txt");
        IOException ex = assertThrows(IOException.class, cmd::call);
        assertTrue(ex.getMessage().contains("uncommitted changes (added)"));
    }

    @Test
    public void testRemoveModifiedFileRacyHgThrows(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f = new File(repoDir, "test.txt");
        Files.writeString(f.toPath(), "Original content\n");
        Hg.add(repo).call();
        Hg.commit(repo).setMessage("commit 1").call();

        // Track original dirstate size and time
        Dirstate d = repo.getDirstate();
        Dirstate.Entry origEntry = d.getEntries().get("test.txt");
        assertNotNull(origEntry);

        // Modify the file but manipulate size and timestamp to mock racy-hg
        // (same size, same timestamp, but content changed)
        Files.writeString(f.toPath(), "Modified content\n"); // same length (17 bytes)
        f.setLastModified(origEntry.getTime() * 1000); // restore original timestamp

        // Trying to remove without force should throw IOException because of content mismatch (racy-hg check)
        RemoveCommand cmd = Hg.remove(repo).setFile("test.txt");
        IOException ex = assertThrows(IOException.class, cmd::call);
        assertTrue(ex.getMessage().contains("uncommitted changes (modified)"), 
                "Must detect modification via byte level content check: " + ex.getMessage());

        // Under force, it should succeed and mark as removed
        Hg.remove(repo).setFile("test.txt").setForce(true).call();
        assertFalse(f.exists(), "File should be deleted from disk");
        
        Dirstate finalDirstate = repo.getDirstate();
        assertEquals('r', finalDirstate.getEntries().get("test.txt").getState(), "State must be 'r'");
    }
}
