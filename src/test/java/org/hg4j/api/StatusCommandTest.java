package org.hg4j.api;

import org.hg4j.core.Dirstate;
import org.hg4j.core.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class StatusCommandTest {

    @Test
    public void testStatusCommandFlow(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Initial status of empty repo
        Status st0 = new StatusCommand(repo).call();
        assertTrue(st0.getAdded().isEmpty());
        assertTrue(st0.getModified().isEmpty());
        assertTrue(st0.getRemoved().isEmpty());
        assertTrue(st0.getClean().isEmpty());
        assertTrue(st0.getUntracked().isEmpty());

        // 2. Create an untracked file
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Hello Status");
        
        Status st1 = new StatusCommand(repo).call();
        assertTrue(st1.getAdded().isEmpty());
        assertEquals(1, st1.getUntracked().size());
        assertTrue(st1.getUntracked().contains("a.txt"));

        // 3. Add the file
        new AddCommand(repo).call();

        Status st2 = new StatusCommand(repo).call();
        assertEquals(1, st2.getAdded().size());
        assertTrue(st2.getAdded().contains("a.txt"));
        assertTrue(st2.getUntracked().isEmpty());

        // 4. Commit the file
        new CommitCommand(repo).setMessage("Commit a").call();

        Status st3 = new StatusCommand(repo).call();
        assertTrue(st3.getAdded().isEmpty());
        assertEquals(1, st3.getClean().size());
        assertTrue(st3.getClean().contains("a.txt"));

        // 5. Modify the file
        Files.writeString(f1.toPath(), "Hello Status Modified");

        Status st4 = new StatusCommand(repo).call();
        assertTrue(st4.getClean().isEmpty());
        assertEquals(1, st4.getModified().size());
        assertTrue(st4.getModified().contains("a.txt"));

        // 6. Remove the file (simulated)
        Dirstate dirstate = repo.getDirstate();
        dirstate.addEntry("a.txt", new Dirstate.Entry('r', 0644, 0, 0));
        repo.writeDirstate(dirstate);
        assertTrue(f1.delete()); // delete from disk

        Status st5 = new StatusCommand(repo).call();
        assertTrue(st5.getModified().isEmpty());
        assertEquals(1, st5.getRemoved().size());
        assertTrue(st5.getRemoved().contains("a.txt"));
    }

    @Test
    public void testStatusCommandTreeFilter(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // Create src/a.txt (untracked) and doc/b.txt (untracked)
        File srcDir = new File(repoDir, "src");
        srcDir.mkdirs();
        File fa = new File(srcDir, "a.txt");
        Files.writeString(fa.toPath(), "Hello Src");

        File docDir = new File(repoDir, "doc");
        docDir.mkdirs();
        File fb = new File(docDir, "b.txt");
        Files.writeString(fb.toPath(), "Hello Doc");

        // Status without filter
        Status stAll = new StatusCommand(repo).call();
        assertEquals(2, stAll.getUntracked().size());

        // Status with filter (only "src/")
        org.hg4j.core.HgTreeFilter filter = org.hg4j.core.HgTreeFilter.createPathPrefixFilter(java.util.List.of("src/"), java.util.List.of());
        Status stFiltered = new StatusCommand(repo).setTreeFilter(filter).call();

        assertEquals(1, stFiltered.getUntracked().size());
        assertTrue(stFiltered.getUntracked().contains("src/a.txt"));
        assertFalse(stFiltered.getUntracked().contains("doc/b.txt"));
    }
}
