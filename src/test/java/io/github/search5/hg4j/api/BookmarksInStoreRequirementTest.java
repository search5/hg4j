package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code bookmarksinstore} requirement without a real hg process: what {@link InitCommand} writes, where {@link HgRepository} then
 * looks for the bookmarks and for hg4j's own rollback copy of them, that {@link Hg#open(File)} lets such a repository in, and that an open
 * object follows a requirement that a clone wrote after it was opened. The behavior against real hg is in
 * {@code BookmarksInStoreRealHgInteropTest}.
 */
class BookmarksInStoreRequirementTest {

    @TempDir
    Path tempDir;

    private static File canonical(File f) throws Exception {
        return f.getCanonicalFile();
    }

    @Test
    void initWithTheOptionWritesTheRequirementAndTheBookmarksGoToTheStore() throws Exception {
        File dir = tempDir.resolve("repo").toFile();
        HgRepository repo = new InitCommand().setDirectory(dir).setBookmarksInStore(true).call();

        assertTrue(Files.readAllLines(new File(dir, ".hg/requires").toPath()).contains("bookmarksinstore"));
        assertTrue(repo.isBookmarksInStore());
        assertEquals(canonical(new File(dir, ".hg/store/bookmarks")), canonical(repo.getBookmarksFile()));
        // hg4j's rollback copy sits next to the bookmarks file, so the store lock alone covers it
        assertEquals(canonical(new File(dir, ".hg/store/undo.backup.bookmarks")), canonical(repo.getBookmarksUndoBackupFile()));
    }

    @Test
    void initWithoutTheOptionKeepsTheBookmarksInTheHgDirectory() throws Exception {
        File dir = tempDir.resolve("repo").toFile();
        HgRepository repo = new InitCommand().setDirectory(dir).call();

        assertFalse(Files.readAllLines(new File(dir, ".hg/requires").toPath()).contains("bookmarksinstore"));
        assertFalse(repo.isBookmarksInStore());
        assertEquals(canonical(new File(dir, ".hg/bookmarks")), canonical(repo.getBookmarksFile()));
        assertEquals(canonical(new File(dir, ".hg/undo.backup.bookmarks")), canonical(repo.getBookmarksUndoBackupFile()));
    }

    @Test
    void hgOpenAcceptsTheRequirement() throws Exception {
        File dir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(dir).setBookmarksInStore(true).call();

        // Hg.open has its own allowlist of requirements; it refused this one before
        assertDoesNotThrow(() -> Hg.open(dir).close());
    }

    @Test
    void anOpenObjectFollowsTheRequirementThatAnInProcessCloneWroteLater() throws Exception {
        File dir = tempDir.resolve("repo").toFile();
        HgRepository repo = new InitCommand().setDirectory(dir).call();
        assertFalse(repo.isBookmarksInStore());

        // what a stream clone (StreamCloneClient#writeRequirements) or a local store copy does to an object that is open already
        Files.writeString(new File(dir, ".hg/requires").toPath(), "\nbookmarksinstore\n", StandardOpenOption.APPEND);
        assertFalse(repo.isBookmarksInStore(), "the flag is read when the repository is opened");

        repo.reloadBookmarksInStoreRequirement();
        assertTrue(repo.isBookmarksInStore());
        assertEquals(canonical(new File(dir, ".hg/store/bookmarks")), canonical(repo.getBookmarksFile()));
    }
}
