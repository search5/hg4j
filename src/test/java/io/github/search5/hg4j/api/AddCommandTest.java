package io.github.search5.hg4j.api;

import io.github.search5.hg4j.core.Dirstate;
import io.github.search5.hg4j.core.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class AddCommandTest {

    @Test
    public void testAddSpecificFiles(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // Create some untracked files
        File f1 = new File(repoDir, "file1.txt");
        assertTrue(f1.createNewFile());

        File subDir = new File(repoDir, "sub");
        assertTrue(subDir.mkdir());
        File f2 = new File(subDir, "file2.txt");
        assertTrue(f2.createNewFile());

        // Perform hg add on specific files
        new AddCommand(repo)
                .addFile("file1.txt")
                .addFile("sub/file2.txt")
                .call();

        // Verify dirstate entries
        Dirstate dirstate = repo.getDirstate();
        Map<String, Dirstate.Entry> entries = dirstate.getEntries();
        assertEquals(2, entries.size());

        Dirstate.Entry e1 = entries.get("file1.txt");
        assertNotNull(e1);
        assertEquals('a', e1.getState());

        Dirstate.Entry e2 = entries.get("sub/file2.txt");
        assertNotNull(e2);
        assertEquals('a', e2.getState());
    }

    @Test
    public void testAddAllUntrackedFiles(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // Create some files
        assertTrue(new File(repoDir, "a.txt").createNewFile());
        File nested = new File(repoDir, "nested");
        assertTrue(nested.mkdir());
        assertTrue(new File(nested, "b.txt").createNewFile());

        // Add all untracked files (no files specified)
        new AddCommand(repo).call();

        Dirstate dirstate = repo.getDirstate();
        Map<String, Dirstate.Entry> entries = dirstate.getEntries();
        assertEquals(2, entries.size());
        assertNotNull(entries.get("a.txt"));
        assertNotNull(entries.get("nested/b.txt"));
    }

    @Test
    public void testAddThrowsExceptionForNonExistentFile(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        AddCommand add = new AddCommand(repo).addFile("nonexistent.txt");
        assertThrows(IOException.class, add::call);
    }
}
