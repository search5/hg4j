package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code hg copy} and {@code hg rename} with patterns and all options. Every expected line and state below was observed with real hg
 * 7.2 (cmdutil.copy) on the same repository: a.txt, b.txt, dir/x.txt, dir/y.txt committed, u.txt not tracked.
 */
class CopyRenamePatternsTest {

    @TempDir
    Path tempDir;

    private File dir;

    @BeforeEach
    void repository() throws Exception {
        dir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(dir).call();
        write("a.txt", "A\n");
        write("b.txt", "B\n");
        write("dir/x.txt", "X\n");
        write("dir/y.txt", "Y\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("base").setAuthor("t").call();
        write("u.txt", "U\n");
        repo.close();
    }

    private void write(String name, String content) throws Exception {
        File f = new File(dir, name);
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), content);
    }

    private CopyCommand.PatternResult copy(String cwd, boolean after, boolean dry, boolean force, String... patterns) throws Exception {
        try (HgRepository repo = new HgRepository(dir)) {
            return new CopyCommand(repo).setPatterns(patterns).setCwd(cwd).setAfter(after).setDryRun(dry).setForce(force).callPatterns();
        }
    }

    private CopyCommand.PatternResult copy(String... patterns) throws Exception {
        return copy("", false, false, false, patterns);
    }

    private RenameCommand.PatternResult rename(boolean after, String... patterns) throws Exception {
        try (HgRepository repo = new HgRepository(dir)) {
            return new RenameCommand(repo).setPatterns(patterns).setAfter(after).callPatterns();
        }
    }

    /** path to state, plus "path <- source" for every recorded copy */
    private Map<String, String> state() throws Exception {
        try (HgRepository repo = new HgRepository(dir)) {
            Dirstate ds = repo.getDirstate();
            Map<String, String> result = new TreeMap<>();
            for (Map.Entry<String, Dirstate.Entry> e : ds.getEntries().entrySet()) {
                if (e.getValue().getState() != 'n' || ds.getCopyMap().containsKey(e.getKey())) {
                    result.put(e.getKey(), String.valueOf(e.getValue().getState()));
                }
            }
            ds.getCopyMap().forEach((dst, src) -> result.put(dst + " <-", src));
            return result;
        }
    }

    private static HgValidationException refused(org.junit.jupiter.api.function.Executable action) {
        return assertThrows(HgValidationException.class, action);
    }

    @Test
    void aSimpleCopyIsAddedWithItsSource() throws Exception {
        CopyCommand.PatternResult r = copy("a.txt", "c.txt");
        assertFalse(r.failed());
        assertEquals(List.of(), r.warnings());
        assertEquals(List.of(), r.status(), "a name given exactly is not reported");
        assertEquals(Map.of("c.txt", "a", "c.txt <-", "a.txt"), state());
        assertEquals("A\n", Files.readString(new File(dir, "c.txt").toPath()));
    }

    @Test
    void aDestinationThatExistsAndIsCommittedIsNotOverwritten() throws Exception {
        CopyCommand.PatternResult r = copy("a.txt", "b.txt");
        assertTrue(r.failed());
        assertEquals(List.of("b.txt: not overwriting - file already committed",
                "('hg copy --force' to replace the file by recording a copy)"), r.warnings());
        assertEquals(Map.of(), state());
        assertEquals("B\n", Files.readString(new File(dir, "b.txt").toPath()));
    }

    @Test
    void forceReplacesItAndRecordsTheCopy() throws Exception {
        CopyCommand.PatternResult r = copy("", false, false, true, "a.txt", "b.txt");
        assertFalse(r.failed());
        assertEquals(Map.of("b.txt", "n", "b.txt <-", "a.txt"), state(), "b.txt stays tracked (modified) and is now a copy of a.txt");
        assertEquals("A\n", Files.readString(new File(dir, "b.txt").toPath()));
    }

    @Test
    void aDirectoryDestinationReceivesTheFileWithItsName() throws Exception {
        copy("a.txt", "dir");
        assertEquals(Map.of("dir/a.txt", "a", "dir/a.txt <-", "a.txt"), state());
    }

    @Test
    void severalSourcesNeedADirectoryDestination() {
        assertEquals("with multiple sources, destination must be an existing directory",
                refused(() -> copy("a.txt", "b.txt", "dir2")).getMessage());
        assertEquals("destination newfile/ is not a directory", refused(() -> copy("a.txt", "newfile/")).getMessage());
        assertEquals("no destination specified", refused(() -> copy("a.txt")).getMessage());
        assertEquals("no source or destination specified", refused(() -> copy()).getMessage());
    }

    @Test
    void aDirectorySourceIsCopiedFileByFileAndReported() throws Exception {
        CopyCommand.PatternResult r = copy("dir", "newdir");
        assertEquals(List.of("copying dir/x.txt to newdir/x.txt", "copying dir/y.txt to newdir/y.txt"), r.status());
        assertEquals(Map.of("newdir/x.txt", "a", "newdir/x.txt <-", "dir/x.txt", "newdir/y.txt", "a", "newdir/y.txt <-", "dir/y.txt"),
                state());
    }

    @Test
    void aFileThatIsNotTrackedIsNotCopied() {
        assertEquals("no files to copy", refused(() -> copy("u.txt", "v.txt")).getMessage());
        assertFalse(new File(dir, "v.txt").exists());
    }

    @Test
    void afterRecordsACopyThatAlreadyHappened() throws Exception {
        Files.copy(new File(dir, "a.txt").toPath(), new File(dir, "c.txt").toPath());
        CopyCommand.PatternResult r = copy("", true, false, false, "a.txt", "c.txt");
        assertFalse(r.failed());
        assertEquals(List.of(), r.warnings());
        assertEquals(Map.of("c.txt", "a", "c.txt <-", "a.txt"), state());
    }

    @Test
    void afterNeedsTheDestinationToExist() throws Exception {
        CopyCommand.PatternResult r = copy("", true, false, false, "a.txt", "zz.txt");
        assertTrue(r.failed());
        assertEquals(List.of("a.txt: not recording copy - zz.txt does not exist"), r.warnings());
    }

    @Test
    void dryRunChangesNothing() throws Exception {
        CopyCommand.PatternResult r = copy("", false, true, false, "a.txt", "c.txt");
        assertFalse(r.failed());
        assertEquals(Map.of(), state());
        assertFalse(new File(dir, "c.txt").exists());
    }

    @Test
    void renameMovesTheFileAndRemovesTheSource() throws Exception {
        RenameCommand.PatternResult r = rename(false, "a.txt", "m.txt");
        assertFalse(r.failed());
        assertEquals(Map.of("m.txt", "a", "m.txt <-", "a.txt", "a.txt", "r"), state());
        assertFalse(new File(dir, "a.txt").exists());
        assertEquals("A\n", Files.readString(new File(dir, "m.txt").toPath()));
    }

    @Test
    void renamingADirectoryReportsEveryFile() throws Exception {
        RenameCommand.PatternResult r = rename(false, "dir", "newdir");
        assertEquals(List.of("moving dir/x.txt to newdir/x.txt", "moving dir/y.txt to newdir/y.txt"), r.status());
        assertEquals("r", state().get("dir/x.txt"));
        assertEquals("a", state().get("newdir/x.txt"));
        assertFalse(new File(dir, "dir/x.txt").exists());
    }

    @Test
    void renameAfterRecordsAMoveWithoutTouchingTheSource() throws Exception {
        Files.copy(new File(dir, "a.txt").toPath(), new File(dir, "c.txt").toPath());
        rename(true, "a.txt", "c.txt");
        assertEquals(Map.of("c.txt", "a", "c.txt <-", "a.txt", "a.txt", "r"), state());
        assertTrue(new File(dir, "a.txt").exists(), "--after leaves the source alone");
    }

    @Test
    void globsAndExclusion() throws Exception {
        CopyCommand.PatternResult all = copy("glob:*.txt", "dir");
        assertEquals(List.of("copying a.txt to dir/a.txt", "copying b.txt to dir/b.txt"), all.status());
        assertFalse(new File(dir, "dir/u.txt").exists(), "u.txt is not tracked");
    }

    @Test
    void includeAndExcludeNarrowTheSources() throws Exception {
        try (HgRepository repo = new HgRepository(dir)) {
            CopyCommand.PatternResult r = new CopyCommand(repo).setPatterns(".", "dir").setInclude("glob:*.txt").setExclude("b.txt")
                    .callPatterns();
            assertEquals(List.of("copying a.txt to dir/a.txt"), r.status());
        }
        assertEquals(Map.of("dir/a.txt", "a", "dir/a.txt <-", "a.txt"), state());
    }

    @Test
    void aFileAddedButNotCommittedHasNoCopyDataToStore() throws Exception {
        write("n.txt", "N\n");
        try (HgRepository repo = new HgRepository(dir)) {
            new AddCommand(repo).addFile("n.txt").call();
        }
        CopyCommand.PatternResult r = copy("n.txt", "n2.txt");
        assertEquals(List.of("n.txt has not been committed yet, so no copy data will be stored for n2.txt."), r.warnings());
        assertEquals(Map.of("n.txt", "a", "n2.txt", "a"), state(), "both are added, and no copy is recorded");
    }

    @Test
    void forgetUnmarksACopy() throws Exception {
        copy("a.txt", "c.txt");
        try (HgRepository repo = new HgRepository(dir)) {
            new CopyCommand(repo).setPatterns("c.txt").setForget(true).callPatterns();
        }
        assertEquals(Map.of("c.txt", "a"), state(), "c.txt stays added, its copy source is forgotten");
        try (HgRepository repo = new HgRepository(dir)) {
            CopyCommand.PatternResult r = new CopyCommand(repo).setPatterns("b.txt").setForget(true).callPatterns();
            assertEquals(List.of("b.txt: not unmarking as copy - file is not marked as copied"), r.warnings());
        }
    }

    @Test
    void forgetAndDryRunExcludeEachOther() throws Exception {
        try (HgRepository repo = new HgRepository(dir)) {
            assertEquals("cannot specify both --forget and --dry-run", refused(
                    () -> new CopyCommand(repo).setPatterns("a.txt", "b.txt").setForget(true).setDryRun(true).callPatterns()).getMessage());
        }
    }

    @Test
    void namesAreReadFromTheCurrentDirectoryAndReportedFromThere() throws Exception {
        copy("", false, false, false, "dir/x.txt", "dir/z.txt");
        CopyCommand.PatternResult r = copy("dir", false, false, false, ".", "../d2");
        assertEquals(List.of("copying x.txt to ../d2/x.txt", "copying y.txt to ../d2/y.txt", "copying z.txt to ../d2/z.txt"),
                r.status());
        Map<String, String> state = state();
        assertEquals("dir/x.txt", state.get("d2/z.txt <-"), "a copy of a copy points at the original");
    }

    @Test
    void aDestinationThatExistsButIsNotCommittedSuggestsAfter() throws Exception {
        copy("a.txt", "dir/q.txt");
        CopyCommand.PatternResult r = copy("b.txt", "dir/q.txt");
        assertTrue(r.failed());
        assertEquals(List.of("dir/q.txt: not overwriting - file exists", "('hg copy --after' to record the copy)"), r.warnings());
    }

    @Test
    void aSourceMarkedForRemovalIsNotCopied() throws Exception {
        try (HgRepository repo = new HgRepository(dir)) {
            new RemoveCommand(repo).setFile("a.txt").call();
        }
        assertEquals("no files to copy", refused(() -> copy("a.txt", "zz.txt")).getMessage());
        CopyCommand.PatternResult after = copy("", true, false, false, "a.txt", "zz.txt");
        assertEquals(List.of("a.txt: not recording copy - zz.txt does not exist"), after.warnings());
    }
}
