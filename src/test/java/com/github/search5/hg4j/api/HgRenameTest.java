package com.github.search5.hg4j.api;

import com.github.search5.hg4j.core.Dirstate;
import com.github.search5.hg4j.core.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;

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
        }
    }
}
