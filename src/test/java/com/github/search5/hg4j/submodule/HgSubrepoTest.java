package com.github.search5.hg4j.submodule;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.github.search5.hg4j.util.SafeFileIO;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import com.github.search5.hg4j.api.Hg;
import com.github.search5.hg4j.errors.HgCorruptDataException;
import com.github.search5.hg4j.lib.HgRepository;
import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.io.TempDir;

public class HgSubrepoTest {
    @TempDir
    File tempDir;

    @Test
    public void testSubrepositoriesParsingSuccess() throws IOException {
        String hgsubText = "# Subrepositories configuration\n" +
                "libs/core = https://hg.example.com/libs/core\n" +
                "libs/external = [git]https://github.com/external/project.git\n" +
                "libs/unused = https://hg.example.com/libs/unused\n";

        String hgsubstateText = "1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b libs/core\n" +
                "7b1a2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b libs/external\n";

        Map<String, HgSubrepoEntry> subrepos = HgSubrepoParser.parseSubrepositories(
                hgsubText.getBytes(StandardCharsets.UTF_8),
                hgsubstateText.getBytes(StandardCharsets.UTF_8)
        );

        assertNotNull(subrepos);
        assertEquals(3, subrepos.size());

        // Check regular Mercurial subrepo
        HgSubrepoEntry core = subrepos.get("libs/core");
        assertNotNull(core);
        assertEquals("libs/core", core.getPath());
        assertEquals("https://hg.example.com/libs/core", core.getSourceUrl());
        assertEquals("1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b", core.getRevision());
        assertFalse(core.isGit());

        // Check Git subrepo
        HgSubrepoEntry external = subrepos.get("libs/external");
        assertNotNull(external);
        assertEquals("libs/external", external.getPath());
        assertEquals("https://github.com/external/project.git", external.getSourceUrl());
        assertEquals("7b1a2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b", external.getRevision());
        assertTrue(external.isGit());

        // Check unused config fallback
        HgSubrepoEntry unused = subrepos.get("libs/unused");
        assertNotNull(unused);
        assertEquals("libs/unused", unused.getPath());
        assertEquals("https://hg.example.com/libs/unused", unused.getSourceUrl());
        assertEquals("", unused.getRevision()); // No state recorded

        // ToString/Equals coverage
        HgSubrepoEntry unusedClone = new HgSubrepoEntry("libs/unused", "https://hg.example.com/libs/unused", "", false);
        assertEquals(unusedClone, unused);
        assertEquals(unusedClone.hashCode(), unused.hashCode());
        assertNotNull(unused.toString());
    }

    @Test
    public void testSubrepositoriesParsingMalformedConfigThrows() {
        String badHgsub = "libs/core https://hg.example.com/libs/core\n"; // missing '='

        assertThrows(HgCorruptDataException.class, () -> {
            HgSubrepoParser.parseSubrepositories(badHgsub.getBytes(StandardCharsets.UTF_8), null);
        });
    }

    @Test
    public void testSubrepositoriesParsingMalformedStateThrows() {
        String hgsub = "libs/core = https://hg.example.com/libs/core\n";
        String badState = "1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b_libs/core\n"; // missing space

        assertThrows(HgCorruptDataException.class, () -> {
            HgSubrepoParser.parseSubrepositories(hgsub.getBytes(StandardCharsets.UTF_8), badState.getBytes(StandardCharsets.UTF_8));
        });
    }

    @Test
    public void testSubrepoRecursiveCheckoutDuringUpdate() throws Exception {
        // Create parent and child temporary directories
        File parentDir = new File(tempDir, "parent");
        File childDir = new File(tempDir, "child");
        
        assertTrue(parentDir.mkdirs());
        assertTrue(childDir.mkdirs());

        // 1. Initialize and build child subrepo history
        HgRepository childRepo = Hg.init().setDirectory(childDir).call();
        try (Hg hgChild = Hg.wrap(childRepo)) {
            File subFile = new File(childDir, "sub.txt");
            Files.writeString(subFile.toPath(), "Subrepo Payload Content");
            hgChild.add().addFile("sub.txt").call();
            byte[] childCommitNode = hgChild.commit().setMessage("Initial child commit").call();
            String childCommitHex = NodeIdUtil.toHex(childCommitNode);

            // 2. Initialize parent repo
            HgRepository parentRepo = Hg.init().setDirectory(parentDir).call();
            try (Hg hgParent = Hg.wrap(parentRepo)) {
                // Commit revision 0: initial baseline commit with nothing inside (to serve as an empty state checkout)
                File initFile = new File(parentDir, "init.txt");
                Files.writeString(initFile.toPath(), "init");
                hgParent.add().addFile("init.txt").call();
                hgParent.commit().setMessage("Initial empty commit").call();

                // Commit revision 1: Write .hgsub and .hgsubstate
                File hgsubFile = new File(parentDir, ".hgsub");
                Files.writeString(hgsubFile.toPath(), "subpath = " + childDir.getAbsolutePath());
                
                File hgsubstateFile = new File(parentDir, ".hgsubstate");
                Files.writeString(hgsubstateFile.toPath(), childCommitHex + " subpath\n");

                hgParent.add().addFile(".hgsub").addFile(".hgsubstate").call();
                byte[] parentCommitNode = hgParent.commit().setMessage("Parent commit with subrepo").call();

                // 3. Clear working copy by force-updating parent to revision 0 (which has no subrepos configured)
                hgParent.update().setRevision("0").setForce(true).call();
                
                File checkedOutSubDir = new File(parentDir, "subpath");
                assertFalse(new File(checkedOutSubDir, "sub.txt").exists(), "Subrepo file should be deleted on empty update");

                // 4. Update back to parent commit: this must trigger subrepo checkout!
                hgParent.update().setRevision(NodeIdUtil.toHex(parentCommitNode)).setForce(true).call();

                // 5. Assert child file has been recursively checked out in the subpath
                File checkedOutSubFile = new File(checkedOutSubDir, "sub.txt");
                assertTrue(checkedOutSubFile.exists(), "Recursive subrepo checkout failed to restore sub.txt!");
                assertEquals("Subrepo Payload Content", Files.readString(checkedOutSubFile.toPath()));
            }
        }
    }
}
