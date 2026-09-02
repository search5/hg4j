package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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

    /**
     * Real hg 7.2 (verified live: {@code ln -s missing-target.txt link.txt; hg add}) accepts a
     * dangling symlink exactly like any other tracked symlink — "A link.txt". hg4j's
     * {@code File.isFile()}/{@code .exists()} checks follow the link and see nothing, so a
     * broken symlink was previously silently rejected here instead.
     */
    @Test
    public void testAddExplicitBrokenSymlinkSucceeds(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File link = new File(repoDir, "broken-link.txt");
        Files.createSymbolicLink(link.toPath(), Path.of("missing-target.txt"));

        new AddCommand(repo).addFile("broken-link.txt").call();

        Dirstate.Entry entry = repo.getDirstate().getEntries().get("broken-link.txt");
        assertNotNull(entry, "broken symlink should have been added to the dirstate");
        assertEquals('a', entry.getState());
    }

    /**
     * Same bug as above, but through the whole-repository scan path ({@code hg add} with no
     * explicit files) — real hg's directory walk also discovers and adds dangling symlinks.
     */
    @Test
    public void testAddAllPicksUpBrokenSymlink(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        assertTrue(new File(repoDir, "a.txt").createNewFile());
        File link = new File(repoDir, "broken-link.txt");
        Files.createSymbolicLink(link.toPath(), Path.of("missing-target.txt"));

        new AddCommand(repo).call();

        Map<String, Dirstate.Entry> entries = repo.getDirstate().getEntries();
        assertEquals(2, entries.size());
        assertNotNull(entries.get("a.txt"));
        Dirstate.Entry linkEntry = entries.get("broken-link.txt");
        assertNotNull(linkEntry, "broken symlink should have been discovered by the whole-repo scan");
        assertEquals('a', linkEntry.getState());
    }

    /**
     * Full add-then-commit round trip for a broken symlink, mirroring
     * {@code CommitCommandTest#testSymlinkTrackedFileRecordsLFlagAndTargetPathAsContent} but for
     * a dangling target: the manifest entry must still carry the {@code l} flag and the filelog
     * content must still be the literal (unresolvable) target path text.
     */
    @Test
    public void testCommittedBrokenSymlinkRecordsLFlagAndTargetPathAsContent(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            File link = new File(repoDir, "broken-link.txt");
            Files.createSymbolicLink(link.toPath(), Path.of("missing-target.txt"));

            new AddCommand(repo).call();
            new CommitCommand(repo).setMessage("broken symlink commit").setAuthor("test").call();

            File mfIdx = new File(repo.getStoreDir(), "00manifest.i");
            File mfDat = new File(repo.getStoreDir(), "00manifest.d");
            Revlog mfRevlog = new Revlog(mfIdx, mfDat);
            String mfText = new String(mfRevlog.getRevisionContent(0), StandardCharsets.UTF_8);
            String linkEntryLine = Arrays.stream(mfText.split("\n"))
                    .filter(line -> line.startsWith("broken-link.txt\0"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(linkEntryLine.endsWith("l"), "Symlink manifest entry must carry the 'l' flag: " + linkEntryLine);

            File flIdx = new File(repo.getStoreDir(), "data/broken-link.txt.i");
            File flDat = new File(repo.getStoreDir(), "data/broken-link.txt.d");
            Revlog filelog = new Revlog(flIdx, flDat);
            assertArrayEquals("missing-target.txt".getBytes(StandardCharsets.UTF_8), filelog.getRevisionContent(0));
        }
    }
}
