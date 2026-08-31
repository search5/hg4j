package com.github.search5.hg4j.core;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.github.search5.hg4j.util.SafeFileIO;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class HgSubrepoTest {
    @org.junit.jupiter.api.io.TempDir
    java.io.File tempDir;

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

        assertThrows(com.github.search5.hg4j.errors.HgCorruptDataException.class, () -> {
            HgSubrepoParser.parseSubrepositories(badHgsub.getBytes(StandardCharsets.UTF_8), null);
        });
    }

    @Test
    public void testSubrepositoriesParsingMalformedStateThrows() {
        String hgsub = "libs/core = https://hg.example.com/libs/core\n";
        String badState = "1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b_libs/core\n"; // missing space

        assertThrows(com.github.search5.hg4j.errors.HgCorruptDataException.class, () -> {
            HgSubrepoParser.parseSubrepositories(hgsub.getBytes(StandardCharsets.UTF_8), badState.getBytes(StandardCharsets.UTF_8));
        });
    }

    @Test
    public void testSubrepoRecursiveCheckoutDuringUpdate() throws Exception {
        // Create parent and child temporary directories
        java.io.File parentDir = new java.io.File(tempDir, "parent");
        java.io.File childDir = new java.io.File(tempDir, "child");
        
        assertTrue(parentDir.mkdirs());
        assertTrue(childDir.mkdirs());

        // 1. Initialize and build child subrepo history
        com.github.search5.hg4j.core.HgRepository childRepo = com.github.search5.hg4j.api.Hg.init().setDirectory(childDir).call();
        try (com.github.search5.hg4j.api.Hg hgChild = com.github.search5.hg4j.api.Hg.wrap(childRepo)) {
            java.io.File subFile = new java.io.File(childDir, "sub.txt");
            java.nio.file.Files.writeString(subFile.toPath(), "Subrepo Payload Content");
            hgChild.add().addFile("sub.txt").call();
            byte[] childCommitNode = hgChild.commit().setMessage("Initial child commit").call();
            String childCommitHex = com.github.search5.hg4j.util.NodeIdUtil.toHex(childCommitNode);

            // 2. Initialize parent repo
            com.github.search5.hg4j.core.HgRepository parentRepo = com.github.search5.hg4j.api.Hg.init().setDirectory(parentDir).call();
            try (com.github.search5.hg4j.api.Hg hgParent = com.github.search5.hg4j.api.Hg.wrap(parentRepo)) {
                // Commit revision 0: initial baseline commit with nothing inside (to serve as an empty state checkout)
                java.io.File initFile = new java.io.File(parentDir, "init.txt");
                java.nio.file.Files.writeString(initFile.toPath(), "init");
                hgParent.add().addFile("init.txt").call();
                hgParent.commit().setMessage("Initial empty commit").call();

                // Commit revision 1: Write .hgsub and .hgsubstate
                java.io.File hgsubFile = new java.io.File(parentDir, ".hgsub");
                java.nio.file.Files.writeString(hgsubFile.toPath(), "subpath = " + childDir.getAbsolutePath());
                
                java.io.File hgsubstateFile = new java.io.File(parentDir, ".hgsubstate");
                java.nio.file.Files.writeString(hgsubstateFile.toPath(), childCommitHex + " subpath\n");

                hgParent.add().addFile(".hgsub").addFile(".hgsubstate").call();
                byte[] parentCommitNode = hgParent.commit().setMessage("Parent commit with subrepo").call();

                // 3. Clear working copy by force-updating parent to revision 0 (which has no subrepos configured)
                hgParent.update().setRevision("0").setForce(true).call();
                
                java.io.File checkedOutSubDir = new java.io.File(parentDir, "subpath");
                assertFalse(new java.io.File(checkedOutSubDir, "sub.txt").exists(), "Subrepo file should be deleted on empty update");

                // 4. Update back to parent commit: this must trigger subrepo checkout!
                hgParent.update().setRevision(com.github.search5.hg4j.util.NodeIdUtil.toHex(parentCommitNode)).setForce(true).call();

                // 5. Assert child file has been recursively checked out in the subpath
                java.io.File checkedOutSubFile = new java.io.File(checkedOutSubDir, "sub.txt");
                assertTrue(checkedOutSubFile.exists(), "Recursive subrepo checkout failed to restore sub.txt!");
                assertEquals("Subrepo Payload Content", java.nio.file.Files.readString(checkedOutSubFile.toPath()));
            }
        }
    }
}
