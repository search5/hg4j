package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class ImportCommandTest {

    private static Revlog changelogOf(HgRepository repo) throws Exception {
        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        return repo.getRevlog(clIdx, clDat);
    }

    private static Revlog manifestOf(HgRepository repo) throws Exception {
        File mfIdx = new File(repo.getStoreDir(), "00manifest.i");
        File mfDat = new File(repo.getStoreDir(), "00manifest.d");
        return repo.getRevlog(mfIdx, mfDat);
    }

    @Test
    public void testNullPatchTextThrows(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        ImportCommand cmd = new ImportCommand(repo);
        assertThrows(IllegalArgumentException.class, cmd::call);
    }

    @Test
    public void testEmptyPatchTextThrows(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        ImportCommand cmd = new ImportCommand(repo).setPatchText("");
        assertThrows(IllegalArgumentException.class, cmd::call);
    }

    @Test
    public void testHeaderParsingWithDateNodeIdAndParent(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        String patch = "# HG changeset patch\n" +
                "# User Alice Example\n" +
                "# Date 1690000000 0\n" +
                "# Node ID abcdef0123456789abcdef0123456789abcdef01\n" +
                "# Parent  0000000000000000000000000000000000000000\n" +
                "Add greeting file\n" +
                "\n" +
                "diff -r 000000000000 -r 111111111111 hello.txt\n" +
                "--- /dev/null\n" +
                "+++ b/hello.txt\n" +
                "@@ -0,0 +1,1 @@\n" +
                "+hi there\n";

        new ImportCommand(repo).setPatchText(patch).call();

        Revlog cl = changelogOf(repo);
        assertEquals(1, cl.getRevisionCount());
        String text = new String(cl.getRevisionContent(0), StandardCharsets.UTF_8);
        String[] lines = text.split("\n");
        assertEquals("Alice Example", lines[1]);
        assertEquals("1690000000 0", lines[2]);
        assertTrue(text.contains("Add greeting file"));
        assertFalse(text.contains("branch:default"));

        File written = new File(repo.getDirectory(), "hello.txt");
        assertEquals("hi there\n", Files.readString(written.toPath()));
    }

    @Test
    public void testMultiLineDescriptionIsJoinedWithNewlines(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        String patch = "# HG changeset patch\n" +
                "# User Bob\n" +
                "First line of message\n" +
                "Second line of message\n" +
                "\n" +
                "diff -r 000000000000 -r 111111111111 a.txt\n" +
                "--- /dev/null\n" +
                "+++ b/a.txt\n" +
                "@@ -0,0 +1,1 @@\n" +
                "+content\n";

        new ImportCommand(repo).setPatchText(patch).call();

        Revlog cl = changelogOf(repo);
        String text = new String(cl.getRevisionContent(0), StandardCharsets.UTF_8);
        assertTrue(text.contains("First line of message\nSecond line of message"));
    }

    @Test
    public void testMissingDescriptionDefaultsToImportedPatch(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        String patch = "# HG changeset patch\n" +
                "# User Carol\n";

        new ImportCommand(repo).setPatchText(patch).call();

        Revlog cl = changelogOf(repo);
        String text = new String(cl.getRevisionContent(0), StandardCharsets.UTF_8);
        assertTrue(text.endsWith("Imported patch"));
    }

    @Test
    public void testNoDateHeaderFallsBackToCurrentTime(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        String patch = "# HG changeset patch\n" +
                "# User Dave\n" +
                "No date supplied\n" +
                "\n" +
                "diff -r 000000000000 -r 111111111111 a.txt\n" +
                "--- /dev/null\n" +
                "+++ b/a.txt\n" +
                "@@ -0,0 +1,1 @@\n" +
                "+content\n";

        long before = System.currentTimeMillis() / 1000;
        new ImportCommand(repo).setPatchText(patch).call();
        long after = System.currentTimeMillis() / 1000;

        Revlog cl = changelogOf(repo);
        String text = new String(cl.getRevisionContent(0), StandardCharsets.UTF_8);
        String dateLine = text.split("\n")[2];
        assertTrue(dateLine.endsWith(" 0"));
        long epoch = Long.parseLong(dateLine.substring(0, dateLine.indexOf(' ')));
        assertTrue(epoch >= before && epoch <= after, "epoch " + epoch + " should be within [" + before + "," + after + "]");
    }

    @Test
    public void testMultiFileAddThenChainedModifyCarriesForwardUnmodifiedFile(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();

        String patch1 = "# HG changeset patch\n" +
                "# User Tester\n" +
                "# Date 1000000 0\n" +
                "Add two files\n" +
                "\n" +
                "diff -r 000000000000 -r 000000000000 a.txt\n" +
                "--- /dev/null\n" +
                "+++ b/a.txt\n" +
                "@@ -0,0 +1,1 @@\n" +
                "+hello\n" +
                "diff -r 000000000000 -r 000000000000 b.txt\n" +
                "--- /dev/null\n" +
                "+++ b/b.txt\n" +
                "@@ -0,0 +1,1 @@\n" +
                "+world\n";
        new ImportCommand(repo).setPatchText(patch1).call();

        Revlog clAfter1 = changelogOf(repo);
        assertEquals(1, clAfter1.getRevisionCount());
        String cl0 = new String(clAfter1.getRevisionContent(0), StandardCharsets.UTF_8);
        assertTrue(cl0.contains("a.txt"));
        assertTrue(cl0.contains("b.txt"));

        Revlog mfAfter1 = manifestOf(repo);
        String mfText0 = new String(mfAfter1.getRevisionContent(0), StandardCharsets.UTF_8);
        String bHashBefore = extractHashForPath(mfText0, "b.txt");
        assertNotNull(bHashBefore);

        String patch2 = "# HG changeset patch\n" +
                "# User Tester\n" +
                "# Date 2000000 0\n" +
                "Modify a only\n" +
                "\n" +
                "diff -r 000000000000 -r 000000000000 a.txt\n" +
                "--- a/a.txt\n" +
                "+++ b/a.txt\n" +
                "@@ -1,1 +1,1 @@\n" +
                "-hello\n" +
                "+hello world\n";
        new ImportCommand(repo).setPatchText(patch2).call();

        Revlog clAfter2 = changelogOf(repo);
        assertEquals(2, clAfter2.getRevisionCount());
        String cl1 = new String(clAfter2.getRevisionContent(1), StandardCharsets.UTF_8);
        assertTrue(cl1.contains("a.txt"));
        assertFalse(cl1.contains("b.txt"), "second commit's file list should only mention the modified file");

        File aFile = new File(repo.getDirectory(), "a.txt");
        assertEquals("hello world\n", Files.readString(aFile.toPath()));
        File bFile = new File(repo.getDirectory(), "b.txt");
        assertEquals("world\n", Files.readString(bFile.toPath()));

        Revlog mfAfter2 = manifestOf(repo);
        String mfText1 = new String(mfAfter2.getRevisionContent(1), StandardCharsets.UTF_8);
        String bHashAfter = extractHashForPath(mfText1, "b.txt");
        assertEquals(bHashBefore, bHashAfter, "unmodified file must carry forward the same filelog node in the new manifest");

        String aHash0 = extractHashForPath(mfText0, "a.txt");
        String aHash1 = extractHashForPath(mfText1, "a.txt");
        assertNotEquals(aHash0, aHash1, "modified file must get a new filelog node");

        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "a.txt");
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        Revlog aFilelog = repo.getRevlog(flIdx, flDat);
        assertEquals(2, aFilelog.getRevisionCount());
        assertEquals(0, aFilelog.getIndexRecord(1).getParent1(), "second a.txt revision should chain off the first as its parent");
    }

    @Test
    public void testMultipleHunksAppliedSequentiallyToSameFile(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();

        File existing = new File(repo.getDirectory(), "pqr.txt");
        Files.writeString(existing.toPath(), "one\ntwo\nthree\nfour\n");

        String patch = "# HG changeset patch\n" +
                "# User Tester\n" +
                "# Date 3000000 0\n" +
                "Two hunks\n" +
                "\n" +
                "diff -r 000000000000 -r 111111111111 pqr.txt\n" +
                "--- a/pqr.txt\n" +
                "+++ b/pqr.txt\n" +
                "@@ -1,1 +1,1 @@\n" +
                "-one\n" +
                "+ONE\n" +
                "@@ -4,1 +4,1 @@\n" +
                "-four\n" +
                "+FOUR\n";

        new ImportCommand(repo).setPatchText(patch).call();

        File written = new File(repo.getDirectory(), "pqr.txt");
        assertEquals("ONE\ntwo\nthree\nFOUR\n", Files.readString(written.toPath()));
    }

    @Test
    public void testUnmatchedHunkContextFallsBackToAppending(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();

        File existing = new File(repo.getDirectory(), "mismatch.txt");
        Files.writeString(existing.toPath(), "alpha\nbeta\ngamma\n");

        String patch = "# HG changeset patch\n" +
                "# User Tester\n" +
                "# Date 4000000 0\n" +
                "Fuzzy mismatch\n" +
                "\n" +
                "diff -r 000000000000 -r 111111111111 mismatch.txt\n" +
                "--- a/mismatch.txt\n" +
                "+++ b/mismatch.txt\n" +
                "@@ -1,1 +1,1 @@\n" +
                "-does not exist in file\n" +
                "+replacement\n";

        new ImportCommand(repo).setPatchText(patch).call();

        File written = new File(repo.getDirectory(), "mismatch.txt");
        assertEquals("alpha\nbeta\ngamma\nreplacement\n", Files.readString(written.toPath()));
    }

    @Test
    public void testFirstCommitParentIsAllZero(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        String patch = "# HG changeset patch\n" +
                "# User Tester\n" +
                "# Date 5000000 0\n" +
                "Initial\n" +
                "\n" +
                "diff -r 000000000000 -r 111111111111 x.txt\n" +
                "--- /dev/null\n" +
                "+++ b/x.txt\n" +
                "@@ -0,0 +1,1 @@\n" +
                "+xyz\n";
        new ImportCommand(repo).setPatchText(patch).call();

        Revlog cl = changelogOf(repo);
        assertEquals(1, cl.getRevisionCount());
        assertEquals(-1, cl.getIndexRecord(0).getParent1(), "first import into an empty repo must not chain onto a parent revision");
    }

    @Test
    public void testHunkWithContextLinesPreservesUnchangedContent(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();

        File existing = new File(repo.getDirectory(), "ctx.txt");
        Files.writeString(existing.toPath(), "line1\nline2\nline3\nline4\nline5\n");

        String patch = "# HG changeset patch\n" +
                "# User Tester\n" +
                "# Date 6000000 0\n" +
                "Change middle line with context\n" +
                "\n" +
                "diff -r 000000000000 -r 111111111111 ctx.txt\n" +
                "--- a/ctx.txt\n" +
                "+++ b/ctx.txt\n" +
                "@@ -2,3 +2,3 @@\n" +
                " line2\n" +
                "-line3\n" +
                "+LINE3-CHANGED\n" +
                " line4\n";

        new ImportCommand(repo).setPatchText(patch).call();

        File written = new File(repo.getDirectory(), "ctx.txt");
        assertEquals("line1\nline2\nLINE3-CHANGED\nline4\nline5\n", Files.readString(written.toPath()));
    }

    /**
     * Backlog #39 (2026-09-05) regression: a delete-shaped hunk (real hg's own {@code --- a/<path>}
     * / {@code +++ /dev/null} convention, verified against real {@code hg export} of a removed
     * file) used to be silently no-op'd entirely -- the old parser only ever recognized a literal
     * {@code "+++ b/"} prefix, so a patch whose new side was {@code /dev/null} never matched at
     * all and the file was neither deleted from disk nor untracked. This is the first-ever test
     * exercising that this command's delete path actually works (via {@link RemoveCommand}).
     */
    @Test
    public void testDeleteHunkRemovesFileFromDiskAndManifest(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();

        File toDelete = new File(repo.getDirectory(), "gone.txt");
        Files.writeString(toDelete.toPath(), "line one\nline two\n");
        new AddCommand(repo).addFile("gone.txt").call();
        new CommitCommand(repo).setAuthor("Tester").setMessage("add gone.txt").call();

        String patch = "# HG changeset patch\n" +
                "# User Tester\n" +
                "# Date 7000000 0\n" +
                "Delete gone.txt\n" +
                "\n" +
                "diff -r 000000000000 -r 111111111111 gone.txt\n" +
                "--- a/gone.txt\n" +
                "+++ /dev/null\n" +
                "@@ -1,2 +0,0 @@\n" +
                "-line one\n" +
                "-line two\n";

        new ImportCommand(repo).setPatchText(patch).call();

        assertFalse(toDelete.exists(), "the deleted file must be removed from the working directory");

        Revlog cl = changelogOf(repo);
        assertEquals(2, cl.getRevisionCount());
        String cl1 = new String(cl.getRevisionContent(1), StandardCharsets.UTF_8);
        // Real hg's own changelog file list DOES include a removed path (it shows which paths
        // CHANGED, removals included) -- only the MANIFEST (checked below) must actually drop it.
        String[] cl1Lines = cl1.split("\n");
        assertEquals("gone.txt", cl1Lines[3], "second commit's file list must record the removal");

        Revlog mf = manifestOf(repo);
        String mfText1 = new String(mf.getRevisionContent(1), StandardCharsets.UTF_8);
        assertNull(extractHashForPath(mfText1, "gone.txt"), "the new manifest must not list the deleted file");
    }

    private static String extractHashForPath(String manifestText, String path) {
        for (String line : manifestText.split("\n")) {
            if (line.isEmpty()) continue;
            int nullIdx = line.indexOf('\0');
            if (nullIdx == -1) continue;
            if (line.substring(0, nullIdx).equals(path)) {
                return line.substring(nullIdx + 1);
            }
        }
        return null;
    }
}
