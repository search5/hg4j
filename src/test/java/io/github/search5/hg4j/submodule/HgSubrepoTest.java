package io.github.search5.hg4j.submodule;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.util.SafeFileIO;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.errors.HgCorruptDataException;
import io.github.search5.hg4j.lib.HgRepository;
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
    public void testSubrepositoriesParsingSvnPrefixSuccess() throws IOException {
        String hgsubText = "libs/svnlib = [svn]https://svn.example.com/repo/trunk\n";
        String hgsubstateText = "42 libs/svnlib\n";

        Map<String, HgSubrepoEntry> subrepos = HgSubrepoParser.parseSubrepositories(
                hgsubText.getBytes(StandardCharsets.UTF_8),
                hgsubstateText.getBytes(StandardCharsets.UTF_8)
        );

        assertEquals(1, subrepos.size());
        HgSubrepoEntry entry = subrepos.get("libs/svnlib");
        assertNotNull(entry);
        assertEquals("https://svn.example.com/repo/trunk", entry.getSourceUrl());
        assertEquals("42", entry.getRevision());
        assertTrue(entry.isSvn());
        assertFalse(entry.isGit());
        assertEquals(HgSubrepoEntry.Type.SVN, entry.getType());
    }

    @Test
    public void testSubrepositoriesParsingSvnFallbackWithoutState() throws IOException {
        String hgsubText = "libs/svnlib = [svn]https://svn.example.com/repo/trunk\n";

        Map<String, HgSubrepoEntry> subrepos = HgSubrepoParser.parseSubrepositories(
                hgsubText.getBytes(StandardCharsets.UTF_8), null);

        HgSubrepoEntry entry = subrepos.get("libs/svnlib");
        assertNotNull(entry);
        assertTrue(entry.isSvn());
        assertEquals("", entry.getRevision());
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

                // Check out the subrepo locally BEFORE declaring/committing .hgsub. Real hg
                // only records a non-null revision in .hgsubstate for a subrepo that is
                // actually present in the working directory at commit time -- a
                // declared-but-not-checked-out path instead has its .hgsubstate entry reset
                // to the null revision (see mercurial-spec-compliance-requirement.md, backlog
                // 23/24, decided 2026-09-04). Committing .hgsub without checking "subpath"
                // out first would therefore record the null revision, and the later
                // recursive-checkout-during-update step below would restore an *empty*
                // subrepo instead of the real content this test is meant to verify.
                File checkedOutSubDir = new File(parentDir, "subpath");
                Hg.cloneRepository().setSource(childDir.getAbsolutePath()).setDirectory(checkedOutSubDir).call();
                assertTrue(new File(checkedOutSubDir, "sub.txt").exists(), "Subrepo must be checked out before commit");

                // Commit revision 1: Write .hgsub (.hgsubstate is now auto-managed by commit)
                File hgsubFile = new File(parentDir, ".hgsub");
                Files.writeString(hgsubFile.toPath(), "subpath = " + childDir.getAbsolutePath());

                hgParent.add().addFile(".hgsub").call();
                byte[] parentCommitNode = hgParent.commit().setMessage("Parent commit with subrepo").call();

                // Sanity: the commit auto-recorded the checked-out subrepo's real revision
                // (not the null revision) because "subpath" was checked out beforehand.
                File hgsubstateFile = new File(parentDir, ".hgsubstate");
                assertEquals(childCommitHex + " subpath\n", Files.readString(hgsubstateFile.toPath()));

                // 3. Simulate the subrepo no longer being present locally (e.g. a fresh clone
                // of the parent that has not run its own subrepo checkout yet) by deleting it
                // outright, then force-update parent to revision 0 (which has no subrepos
                // configured).
                deleteRecursively(checkedOutSubDir);
                hgParent.update().setRevision("0").setForce(true).call();

                assertFalse(new File(checkedOutSubDir, "sub.txt").exists(), "Subrepo file should be absent after moving away");

                // 4. Update back to parent commit: this must trigger subrepo checkout!
                hgParent.update().setRevision(NodeIdUtil.toHex(parentCommitNode)).setForce(true).call();

                // 5. Assert child file has been recursively checked out in the subpath
                File checkedOutSubFile = new File(checkedOutSubDir, "sub.txt");
                assertTrue(checkedOutSubFile.exists(), "Recursive subrepo checkout failed to restore sub.txt!");
                assertEquals("Subrepo Payload Content", Files.readString(checkedOutSubFile.toPath()));
            }
        }
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
