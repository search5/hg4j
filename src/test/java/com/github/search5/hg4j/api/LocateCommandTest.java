package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behavior verified against real {@code hg} (v7.2) on scratch repos and
 * {@code mercurial/commands.py}'s {@code locate} function (see class javadoc
 * on {@link LocateCommand} for the exact semantics and citations).
 */
public class LocateCommandTest {

    private static void write(File dir, String relPath, String content) throws Exception {
        File f = new File(dir, relPath);
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), content);
    }

    @Test
    public void testExplicitEmptyStringRevisionBehavesLikeDefault(@TempDir Path tempDir) throws Exception {
        // revision == null (never set) and an explicit empty string are two different branch
        // outcomes that both fall back to the working-copy listing.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "a.txt", "a");
        new AddCommand(repo).call();

        List<String> result = new LocateCommand(repo).setRevision("").call();
        assertEquals(List.of("a.txt"), result);
    }

    @Test
    public void testExplicitEmptyStringPatternBehavesLikeNoPattern(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "a.txt", "a");
        new AddCommand(repo).call();

        List<String> result = new LocateCommand(repo).setPattern("").call();
        assertEquals(List.of("a.txt"), result);
    }

    @Test
    public void testQuestionMarkGlobMatchesExactlyOneCharacter(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "a1.txt", "a");
        write(repoDir, "a22.txt", "a");
        new AddCommand(repo).call();

        List<String> result = new LocateCommand(repo).setPattern("a?.txt").call();
        assertEquals(List.of("a1.txt"), result);
    }

    @Test
    public void testDoubleStarNotFollowedBySlashMatchesAcrossDirectoriesWithoutAbsorbingASeparator(@TempDir Path tempDir) throws Exception {
        // Distinct from testDoubleStarGlobMatchesAcrossDirectories, which only ever uses the
        // "**/" form (the (?:.*/)? absorbing branch). "**" not immediately followed by '/' takes
        // the plain ".*" branch instead.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "sub/deep/a.txt", "a");
        new AddCommand(repo).call();

        List<String> result = new LocateCommand(repo).setPattern("sub**a.txt").call();
        assertEquals(List.of("sub/deep/a.txt"), result);
    }

    @Test
    public void testDoubleStarAtEndOfPatternWithNothingAfterIt(@TempDir Path tempDir) throws Exception {
        // "**" as the pattern's very last characters: i+1 < n is false (there is no next
        // character to check for '/'), a distinct branch outcome from "**" followed by more text.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "sub/deep/a.txt", "a");
        new AddCommand(repo).call();

        List<String> result = new LocateCommand(repo).setPattern("sub/**").call();
        assertEquals(List.of("sub/deep/a.txt"), result);
    }

    @Test
    public void testPatternWithRegexMetacharacterMatchesItLiterally(@TempDir Path tempDir) throws Exception {
        // compileRelglobPattern's escaping branch for regex-special characters that are not glob
        // wildcards (e.g. parentheses) -- untested by the other glob-focused tests, which only use
        // plain names, '*', and '**'.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        write(repoDir, "file(1).txt", "a");
        new AddCommand(repo).call();

        List<String> result = new LocateCommand(repo).setPattern("file(1).txt").call();
        assertEquals(List.of("file(1).txt"), result);
    }

    @Test
    public void testNoPatternListsEverythingTrackedInWorkingCopy(@TempDir Path tempDir) throws Exception {
        // Verified: `hg locate` with no args prints every file under Mercurial
        // control in the working directory (mercurial/commands.py locate() docstring
        // and `sorted(repo.dirstate.matches(m))` with an "always" matcher).
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        write(repoDir, "a.txt", "a");
        write(repoDir, "sub/b.txt", "b");
        write(repoDir, "c.py", "c");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("initial").call();

        List<String> result = new LocateCommand(repo).call();
        assertEquals(List.of("a.txt", "c.py", "sub/b.txt"), result);
    }

    @Test
    public void testExplicitGlobPatternMatchesAnywhereInTree(@TempDir Path tempDir) throws Exception {
        // Verified: `hg locate '*.txt'` in a repo with a.txt, sub/b.txt, c.py
        // returns both a.txt and sub/b.txt -- the default pattern kind for
        // locate is "relglob" (unrooted glob), unlike most other commands
        // whose default is "relpath" (cwd-anchored).
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        write(repoDir, "a.txt", "a");
        write(repoDir, "sub/b.txt", "b");
        write(repoDir, "c.py", "c");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("initial").call();

        List<String> result = new LocateCommand(repo).setPattern("*.txt").call();
        assertEquals(List.of("a.txt", "sub/b.txt"), result);
    }

    @Test
    public void testGlobPatternAnchoredToDirectoryMatchesOnlyWithinIt(@TempDir Path tempDir) throws Exception {
        // Verified: `hg locate 'sub/*.txt'` returns only sub/b.txt, not a.txt.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        write(repoDir, "a.txt", "a");
        write(repoDir, "sub/b.txt", "b");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("initial").call();

        List<String> result = new LocateCommand(repo).setPattern("sub/*.txt").call();
        assertEquals(List.of("sub/b.txt"), result);
    }

    @Test
    public void testLiteralPatternWithoutWildcardsStillMatchesAnywhere(@TempDir Path tempDir) throws Exception {
        // Verified: `hg locate 'b.txt'` matches sub/b.txt even though the
        // pattern has no directory component and no glob metacharacters --
        // relglob still searches the whole tree for a bare filename.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        write(repoDir, "a.txt", "a");
        write(repoDir, "sub/b.txt", "b");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("initial").call();

        List<String> result = new LocateCommand(repo).setPattern("b.txt").call();
        assertEquals(List.of("sub/b.txt"), result);
    }

    @Test
    public void testNoMatchReturnsEmptyList(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        write(repoDir, "a.txt", "a");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("initial").call();

        List<String> result = new LocateCommand(repo).setPattern("*.rs").call();
        assertTrue(result.isEmpty());
    }

    @Test
    public void testWorkingCopyIncludesRemovedFiles(@TempDir Path tempDir) throws Exception {
        // Verified: `hg rm a.txt` then `hg locate` still prints a.txt (dirstate
        // state 'r'), because locate goes through repo.dirstate.matches(m)
        // directly rather than workingctx.matches() (which filters on
        // dirstate.get_entry(f).tracked and would drop it). This is the key
        // divergence from `hg files`, which excludes removed files.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        write(repoDir, "a.txt", "a");
        write(repoDir, "b.txt", "b");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("initial").call();

        new RemoveCommand(repo).setFile("a.txt").call();

        List<String> result = new LocateCommand(repo).call();
        assertEquals(List.of("a.txt", "b.txt"), result);
    }

    @Test
    public void testWorkingCopyIncludesRemovedFilesEvenWithExplicitPattern(@TempDir Path tempDir) throws Exception {
        // Verified: `hg locate 'a.*'` after `hg rm a.txt` still prints a.txt --
        // the removed-file inclusion is unconditional in the Python source,
        // not merely a "no pattern given" special case.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        write(repoDir, "a.txt", "a");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("initial").call();

        new RemoveCommand(repo).setFile("a.txt").call();

        List<String> result = new LocateCommand(repo).setPattern("a.*").call();
        assertEquals(List.of("a.txt"), result);
    }

    @Test
    public void testWorkingCopyIncludesTrackedButMissingFromDisk(@TempDir Path tempDir) throws Exception {
        // Verified: deleting a tracked file from disk without `hg rm` (dirstate
        // stays 'n', `hg status` shows "!") does NOT remove it from `hg locate`
        // output -- neither locate nor files checks physical disk existence,
        // only dirstate/manifest state.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        write(repoDir, "a.txt", "a");
        write(repoDir, "b.txt", "b");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("initial").call();

        assertTrue(new File(repoDir, "a.txt").delete());

        List<String> result = new LocateCommand(repo).call();
        assertEquals(List.of("a.txt", "b.txt"), result);
    }

    @Test
    public void testRevisionSearchesHistoricalManifestNotWorkingCopy(@TempDir Path tempDir) throws Exception {
        // Verified: `hg locate --rev 0` on a repo whose rev 0 had a.txt, b.txt,
        // c.py returns all three even after a.txt was `hg rm`'d in the working
        // copy afterward -- -r searches that revision's manifest, which has no
        // notion of the later working-copy removal.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        write(repoDir, "a.txt", "a");
        write(repoDir, "b.txt", "b");
        write(repoDir, "c.py", "c");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("initial").call();

        new RemoveCommand(repo).setFile("a.txt").call();
        new CommitCommand(repo).setMessage("remove a").call();

        List<String> atRev0 = new LocateCommand(repo).setRevision("0").call();
        assertEquals(List.of("a.txt", "b.txt", "c.py"), atRev0);

        // Sanity check: the working copy (no -r, at rev 1 parent) no longer
        // has a.txt tracked as present-and-not-removed, and current dirstate
        // doesn't even carry the 'r' entry anymore once committed.
        List<String> workingCopy = new LocateCommand(repo).call();
        assertEquals(List.of("b.txt", "c.py"), workingCopy);
    }

    @Test
    public void testRevisionWithPatternFiltersHistoricalManifest(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        write(repoDir, "a.txt", "a");
        write(repoDir, "sub/b.txt", "b");
        write(repoDir, "c.py", "c");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("initial").call();

        List<String> result = new LocateCommand(repo).setPattern("*.txt").setRevision("0").call();
        assertEquals(List.of("a.txt", "sub/b.txt"), result);
    }

    @Test
    public void testRevisionByTipKeyword(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        write(repoDir, "a.txt", "a");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("initial").call();

        write(repoDir, "b.txt", "b");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("second").call();

        List<String> result = new LocateCommand(repo).setRevision("tip").call();
        assertEquals(List.of("a.txt", "b.txt"), result);
    }

    @Test
    public void testUnresolvableRevisionThrows(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        write(repoDir, "a.txt", "a");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("initial").call();

        assertThrows(Exception.class, () -> new LocateCommand(repo).setRevision("42").call());
    }

    @Test
    public void testDoubleStarGlobMatchesAcrossDirectories(@TempDir Path tempDir) throws Exception {
        // Verified against mercurial/match.py's _globre doctest:
        // _globre('**/a') == '(?:.*/)?a', i.e. ** (optionally followed by /)
        // matches zero or more full path segments.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        write(repoDir, "a.txt", "a");
        write(repoDir, "sub/deep/a.txt", "a2");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("initial").call();

        List<String> result = new LocateCommand(repo).setPattern("**/a.txt").call();
        assertEquals(List.of("a.txt", "sub/deep/a.txt"), result);
    }

    @Test
    public void testEmptyRepositoryReturnsEmptyList(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        List<String> result = new LocateCommand(repo).call();
        assertTrue(result.isEmpty());
    }
}
