package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage-focused tests for {@link StripCommand}, targeting branches the original
 * {@code StripCommandTest} doesn't exercise: unresolvable revisions, an absent fncache, the
 * bookmark/phaseroots cleanup edge cases, and the mid-strip failure rollback path.
 *
 * <p>Two real behavioral bugs were found and fixed in {@code StripCommand} while writing these
 * tests (both verified against real hg 7.2 CLI, 2026-09-01, with {@code --config
 * extensions.strip=}):
 * <ul>
 *     <li>Bookmarks pointing at a stripped revision were being deleted outright. Real hg's
 *     strip instead moves such a bookmark back to the new tip (down to the null node,
 *     {@code 000...000}, if everything is stripped) -- see {@link #stripMovesBookmarkPointingAtStrippedRevisionToNewTip}
 *     and {@link #stripMovesBookmarkToNullNodeWhenStrippingEverything}.</li>
 *     <li>The mid-strip failure rollback path restored truncated revlogs via
 *     {@code FileChannel#truncate(origSize)}, which can only ever shrink a file -- so it was a
 *     silent no-op for undoing a strip's truncation and left the changelog/manifest corrupted
 *     after a failed strip. See {@link #stripFullyRestoresAllTruncatedFilesWhenAMidStripStepFails}.</li>
 * </ul>
 */
public class StripCommandCoverageTest {

    @Test
    public void stripThrowsWhenRevisionCannotBeResolved(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        assertThrows(IOException.class, () -> new StripCommand(repo).setRevision("not-a-revision").call());
    }

    @Test
    public void stripSucceedsWhenFncacheFileIsAbsent(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        Files.writeString(new File(repoDir, "a.txt").toPath(), "v1");
        new CommitCommand(repo).setMessage("rev1").call();

        File fncacheFile = new File(repo.getStoreDir(), "fncache");
        assertTrue(fncacheFile.exists(), "fncache should have been created by the commits above");
        Files.delete(fncacheFile.toPath());

        new StripCommand(repo).setRevision("1").call();

        assertEquals(1, new LogCommand(repo).call().size());
    }

    @Test
    public void stripMovesBookmarkPointingAtStrippedRevisionToNewTip(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();
        String hexRev0 = NodeIdUtil.toHex(new LogCommand(repo).call().get(0).getNodeId().getBytes());

        Files.writeString(new File(repoDir, "a.txt").toPath(), "v1");
        new CommitCommand(repo).setMessage("rev1").call();
        String hexRev1 = NodeIdUtil.toHex(new LogCommand(repo).call().get(0).getNodeId().getBytes());

        new BookmarkCommand(repo).setBookmarkName("feat").setRevision(hexRev1).call();
        Map<String, String> before = new BookmarkCommand(repo).call();
        assertEquals(1, before.size());

        new StripCommand(repo).setRevision("1").call();

        // Real hg moves (not deletes) a bookmark that pointed at a stripped revision --
        // verified with `hg bookmark -r <rev>` + `hg strip -r <rev>` on real hg 7.2.
        Map<String, String> after = new BookmarkCommand(repo).call();
        assertEquals(1, after.size(), "bookmark must survive the strip, relocated to the new tip");
        assertEquals(hexRev0, after.get("feat"));
    }

    @Test
    public void stripMovesBookmarkToNullNodeWhenStrippingEverything(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();
        String hexRev0 = NodeIdUtil.toHex(new LogCommand(repo).call().get(0).getNodeId().getBytes());

        new BookmarkCommand(repo).setBookmarkName("bk0").setRevision(hexRev0).call();

        new StripCommand(repo).setRevision("0").call();

        // Verified with real hg: stripping revision 0 leaves the bookmark at -1:000000000000
        // rather than removing it.
        Map<String, String> after = new BookmarkCommand(repo).call();
        assertEquals("0".repeat(40), after.get("bk0"));
    }

    @Test
    public void stripDropsBookmarkEntryThatAlreadyPointsAtAnUnresolvableNode(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        // A bookmark line referring to a well-formed but unknown node (simulating leftover
        // corruption from an unrelated prior strip/prune) -- not something this strip caused,
        // so it should simply be dropped rather than guessed at.
        File bookmarksFile = new File(repo.getHgDir(), "bookmarks");
        Files.writeString(bookmarksFile.toPath(), "f".repeat(40) + " ghost\n");

        new StripCommand(repo).setRevision("0").call();

        assertFalse(bookmarksFile.exists(),
                "the only bookmark entry was unresolvable, so the file should be deleted rather than left with an empty/stale entry");
    }

    @Test
    public void stripDropsPhaseRootEntryForAnUnresolvableNode(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        File phaserootsFile = new File(repo.getStoreDir(), "phaseroots");
        assertTrue(phaserootsFile.exists(), "committing sets a draft phase root for the new commit");
        // Append a stale entry pointing at a node no longer (or never) present.
        Files.writeString(phaserootsFile.toPath(), "1 " + "e".repeat(40) + "\n",
                StandardOpenOption.APPEND);

        new StripCommand(repo).setRevision("0").call();

        if (phaserootsFile.exists()) {
            List<String> lines = Files.readAllLines(phaserootsFile.toPath());
            assertTrue(lines.stream().noneMatch(l -> l.contains("e".repeat(40))),
                    "the unresolvable phase root entry must not survive the strip");
        }
    }

    @Test
    public void stripFullyRestoresAllTruncatedFilesWhenAMidStripStepFails(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        Files.writeString(new File(repoDir, "a.txt").toPath(), "v1");
        new CommitCommand(repo).setMessage("rev1").call();

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        File mfIdx = new File(repo.getStoreDir(), "00manifest.i");
        File mfDat = new File(repo.getStoreDir(), "00manifest.d");
        byte[] origClIdx = Files.readAllBytes(clIdx.toPath());
        byte[] origClDat = Files.readAllBytes(clDat.toPath());
        byte[] origMfIdx = Files.readAllBytes(mfIdx.toPath());
        // Backlog #35: a small manifest is now inline by default (matching real hg) and may have
        // no separate .d file of its own -- guard on mfDat's OWN existence, not clDat's (the
        // changelog stays non-inline always, so checking it here was never actually meaningful).
        byte[] origMfDat = mfDat.exists() ? Files.readAllBytes(mfDat.toPath()) : null;
        byte[] origDirstate = Files.readAllBytes(new File(repo.getDirectory(), ".hg/dirstate").toPath());

        // Corrupt the bookmarks file with an odd-length hex node so NodeIdUtil.fromHex() throws
        // partway through strip's bookmark-cleanup step -- i.e. *after* the changelog/manifest
        // (and any filelogs) have already been truncated on disk, exercising the failure-path
        // rollback.
        File bookmarksFile = new File(repo.getHgDir(), "bookmarks");
        Files.writeString(bookmarksFile.toPath(), "abc badbookmark\n");

        assertThrows(IllegalArgumentException.class, () -> new StripCommand(repo).setRevision("1").call());

        // The changelog/manifest must be restored byte-for-byte, not merely left at whatever
        // (smaller) size the failed truncation happened to leave them at.
        assertArrayEquals(origClIdx, Files.readAllBytes(clIdx.toPath()), "00changelog.i must be fully restored");
        assertArrayEquals(origClDat, Files.readAllBytes(clDat.toPath()), "00changelog.d must be fully restored");
        assertArrayEquals(origMfIdx, Files.readAllBytes(mfIdx.toPath()), "00manifest.i must be fully restored");
        if (origMfDat != null) {
            assertArrayEquals(origMfDat, Files.readAllBytes(mfDat.toPath()), "00manifest.d must be fully restored");
        }
        assertArrayEquals(origDirstate, Files.readAllBytes(new File(repo.getDirectory(), ".hg/dirstate").toPath()));

        // No leftover journal or internal backup directory.
        assertFalse(new File(repo.getStoreDir(), "journal").exists());
        assertFalse(new File(repo.getStoreDir(), "strip-journal-backup").exists());

        // The repository must be fully usable again -- both changesets still present.
        assertEquals(2, new LogCommand(repo).call().size());
    }

    @Test
    public void stripSucceedsEvenWhenObsoleteMarkerWriteFails(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        Files.writeString(new File(repoDir, "a.txt").toPath(), "v1");
        new CommitCommand(repo).setMessage("rev1").call();

        // Pre-create obsstore as a directory so HgObsMarker.writeMarker()'s
        // `new FileOutputStream(obsstoreFile, true)` fails with an IOException; strip treats
        // that as non-blocking and must still complete the rest of the operation.
        File obsstoreDir = new File(repo.getStoreDir(), "obsstore");
        Files.createDirectories(obsstoreDir.toPath());

        new StripCommand(repo).setRevision("1").call();

        assertEquals(1, new LogCommand(repo).call().size());
    }

    @Test
    public void stripDoesNotLeaveRollbackInformationBehind(@TempDir Path tempDir) throws Exception {
        // Verified against real hg 7.2: `hg rollback` right after a successful `hg strip`
        // reports "no rollback information available" -- strip relies on its own backup-bundle
        // mechanism, not the generic transaction-undo file, and this codebase's own
        // RollbackCommand/HgRepository auto-recovery restore store files via
        // FileChannel#truncate(origSize), which cannot un-shrink a file strip just truncated
        // down, so leaving undo info here would be both spec-incorrect and non-functional.
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        Files.writeString(new File(repoDir, "a.txt").toPath(), "v1");
        new CommitCommand(repo).setMessage("rev1").call();

        new StripCommand(repo).setRevision("1").call();

        assertThrows(IllegalStateException.class, () -> new RollbackCommand(repo).call());
    }

    @Test
    public void stripThrowsWhenRevisionIsEmptyString(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        assertThrows(IllegalArgumentException.class, () -> new StripCommand(repo).setRevision("").call());
    }

    @Test
    public void stripSucceedsWhenDirstateFileIsMissing(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        Files.writeString(new File(repoDir, "a.txt").toPath(), "v1");
        new CommitCommand(repo).setMessage("rev1").call();

        // Simulate a repository whose dirstate was already gone before the strip ran (e.g. a
        // previous crash) -- strip must not assume it exists.
        File dirstateFile = new File(repo.getDirectory(), ".hg/dirstate");
        Files.delete(dirstateFile.toPath());

        new StripCommand(repo).setRevision("1").call();

        assertEquals(1, new LogCommand(repo).call().size());
    }

    @Test
    public void stripRollsBackNewlyCreatedFilesAndSkipsAbsentDirstateBackupOnFailure(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        Files.writeString(new File(repoDir, "a.txt").toPath(), "v1");
        new CommitCommand(repo).setMessage("rev1").call();

        // Neither dirstate nor fncache exist going into this strip attempt, so the failed
        // attempt below (a) has no dirstate backup to restore and (b) will freshly re-create
        // fncache mid-attempt, which the rollback must then delete again rather than leave
        // behind.
        Files.delete(new File(repo.getDirectory(), ".hg/dirstate").toPath());
        File fncacheFile = new File(repo.getStoreDir(), "fncache");
        Files.delete(fncacheFile.toPath());

        File bookmarksFile = new File(repo.getHgDir(), "bookmarks");
        Files.writeString(bookmarksFile.toPath(), "abc badbookmark\n");

        assertThrows(IllegalArgumentException.class, () -> new StripCommand(repo).setRevision("1").call());

        assertFalse(fncacheFile.exists(),
                "fncache was freshly (re)created by the failed attempt and must be rolled back to absent");
        assertFalse(new File(repo.getDirectory(), ".hg/dirstate").exists(),
                "dirstate was already absent before the attempt and must stay absent, not be conjured from a null backup");
        assertFalse(new File(repo.getStoreDir(), "journal").exists());
        assertFalse(new File(repo.getStoreDir(), "strip-journal-backup").exists());
        assertEquals(2, new LogCommand(repo).call().size());
    }

    @Test
    public void stripIgnoresNonRevlogEntryInFncache(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        File fncacheFile = new File(repo.getStoreDir(), "fncache");
        Files.writeString(fncacheFile.toPath(), "not-a-dot-i-entry\n", StandardOpenOption.APPEND);

        new StripCommand(repo).setRevision("0").call();

        assertEquals(0, new LogCommand(repo).call().size());
    }

    @Test
    public void stripIgnoresStaleFncacheEntryForAFilelogThatNoLongerExists(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        File fncacheFile = new File(repo.getStoreDir(), "fncache");
        Files.writeString(fncacheFile.toPath(), "data/ghost.i\n", StandardOpenOption.APPEND);

        new StripCommand(repo).setRevision("0").call();

        assertEquals(0, new LogCommand(repo).call().size());
    }

    @Test
    public void stripSkipsBlankLinesInBookmarksAndPhaseroots(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();
        String hexRev0 = NodeIdUtil.toHex(new LogCommand(repo).call().get(0).getNodeId().getBytes());

        Files.writeString(new File(repoDir, "a.txt").toPath(), "v1");
        new CommitCommand(repo).setMessage("rev1").call();

        File bookmarksFile = new File(repo.getHgDir(), "bookmarks");
        Files.writeString(bookmarksFile.toPath(), "\n" + hexRev0 + " keep\n");
        File phaserootsFile = new File(repo.getStoreDir(), "phaseroots");
        Files.writeString(phaserootsFile.toPath(), "\n", StandardOpenOption.APPEND);

        new StripCommand(repo).setRevision("1").call();

        assertEquals(1, new BookmarkCommand(repo).call().size());
        assertEquals(1, new LogCommand(repo).call().size());
    }

    @Test
    public void stripDropsMalformedSingleTokenBookmarkAndPhaserootsLines(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();
        String hexRev0 = NodeIdUtil.toHex(new LogCommand(repo).call().get(0).getNodeId().getBytes());

        File bookmarksFile = new File(repo.getHgDir(), "bookmarks");
        Files.writeString(bookmarksFile.toPath(), "onlyonetoken\n" + hexRev0 + " keep\n");
        File phaserootsFile = new File(repo.getStoreDir(), "phaseroots");
        Files.writeString(phaserootsFile.toPath(), "onlyonetoken\n", StandardOpenOption.APPEND);

        new StripCommand(repo).setRevision("0").call();

        Map<String, String> bookmarks = new BookmarkCommand(repo).call();
        assertEquals(1, bookmarks.size());
        assertEquals("0".repeat(40), bookmarks.get("keep"));
    }

    @Test
    public void stripDeletesFilelogEvenWhenItsDatFileWasAlreadyMissing(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        // Backlog #35: a small new filelog is now inline by default (matching real hg), so
        // a.txt.d is naturally already absent -- exactly the scenario this test's name describes,
        // with no need to manually delete it first.
        assertFalse(new File(repo.getStoreDir(), "data/a.txt.d").exists());

        new StripCommand(repo).setRevision("0").call();

        assertEquals(0, new LogCommand(repo).call().size());
        assertFalse(new File(repo.getStoreDir(), "data/a.txt.i").exists());
    }

    @Test
    public void stripTruncatesFilelogEvenWhenItsDatFileWasAlreadyMissing(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        Files.writeString(new File(repoDir, "a.txt").toPath(), "v1");
        new CommitCommand(repo).setMessage("rev1").call();

        // Backlog #35: a small filelog is now inline by default (matching real hg), so
        // a.txt.d is naturally already absent -- exactly the scenario this test's name describes,
        // with no need to manually delete it first.
        assertFalse(new File(repo.getStoreDir(), "data/a.txt.d").exists());

        new StripCommand(repo).setRevision("1").call();

        assertEquals(1, new LogCommand(repo).call().size());
    }

    @Test
    public void stripSucceedsWhenPhaserootsFileIsAbsent(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        Files.writeString(new File(repoDir, "a.txt").toPath(), "v1");
        new CommitCommand(repo).setMessage("rev1").call();

        File phaserootsFile = new File(repo.getStoreDir(), "phaseroots");
        assertTrue(phaserootsFile.exists());
        Files.delete(phaserootsFile.toPath());

        new StripCommand(repo).setRevision("1").call();

        assertEquals(1, new LogCommand(repo).call().size());
        assertFalse(phaserootsFile.exists());
    }
}
