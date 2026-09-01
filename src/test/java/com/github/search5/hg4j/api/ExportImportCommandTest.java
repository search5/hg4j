package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import com.github.search5.hg4j.storage.Revlog;
import java.nio.charset.StandardCharsets;

public class ExportImportCommandTest {

    @Test
    public void testExportAndImportRoundtrip(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("src").toFile();
        File dstDir = tempDir.resolve("dst").toFile();
        
        // 1. Create source repo and commit a changeset
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        File f1 = new File(srcDir, "a.txt");
        Files.writeString(f1.toPath(), "Original file content");
        new AddCommand(srcRepo).call();
        new CommitCommand(srcRepo).setMessage("Feature commit message").setAuthor("developer").call();

        // 2. Export the changeset commit as a patch
        String patch = new ExportCommand(srcRepo).setRevision("0").call();
        assertNotNull(patch);
        assertTrue(patch.contains("# User developer"));
        assertTrue(patch.contains("Feature commit message"));

        // 3. Create destination repo and import/apply the patch
        HgRepository dstRepo = Hg.init().setDirectory(dstDir).call();
        new ImportCommand(dstRepo).setPatchText(patch).call();

        // 4. Verify exact recreation of changeset metadata on the destination
        File clIdx = new File(dstRepo.getStoreDir(), "00changelog.i");
        File clDat = new File(dstRepo.getStoreDir(), "00changelog.d");
        Revlog cl = dstRepo.getRevlog(clIdx, clDat);
        
        assertEquals(1, cl.getRevisionCount());
        String clText = new String(cl.getRevisionContent(0), StandardCharsets.UTF_8);
        assertTrue(clText.contains("developer"));
        assertTrue(clText.contains("Feature commit message"));
    }
}
