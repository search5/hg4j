package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The hint `hg revert` gives when no file is named, and the refusals before it touches anything. */
class RevertCommandHintsTest {

    @TempDir
    Path tempDir;

    private HgRepository twoCommits(String[] firstHex) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File f = new File(tempDir.toFile(), "a.txt");
        Files.writeString(f.toPath(), "one\n");
        new AddCommand(repo).call();
        byte[] first = new CommitCommand(repo).setMessage("one").setAuthor("t").call();
        firstHex[0] = NodeIdUtil.toHex(first);
        Files.writeString(f.toPath(), "two\n");
        new CommitCommand(repo).setMessage("two").setAuthor("t").call();
        return repo;
    }

    private static String hintOf(RevertCommand command) {
        return assertThrows(HgValidationException.class, command::callSelected).getMessage();
    }

    @Test
    void cleanWorkingCopyWithNoFilesSuggestsAll() throws Exception {
        HgRepository repo = twoCommits(new String[1]);
        assertEquals("no files or directories specified\n(use --all to revert all files)", hintOf(new RevertCommand(repo)));
    }

    @Test
    void dirtyWorkingCopyWithNoFilesSuggestsDiscardingChanges() throws Exception {
        HgRepository repo = twoCommits(new String[1]);
        Files.writeString(new File(tempDir.toFile(), "a.txt").toPath(), "dirty\n");
        assertEquals("no files or directories specified\n(uncommitted changes, use --all to discard all changes)",
                hintOf(new RevertCommand(repo)));
    }

    @Test
    void anotherRevisionAddsTheUpdateSuggestion() throws Exception {
        String[] first = new String[1];
        HgRepository repo = twoCommits(first);
        String clean = hintOf(new RevertCommand(repo).setRevision(first[0]));
        assertTrue(clean.contains("use --all to revert all files, or 'hg update 0' to update"), clean);
        Files.writeString(new File(tempDir.toFile(), "a.txt").toPath(), "dirty\n");
        String dirty = hintOf(new RevertCommand(repo).setRevision(first[0]));
        assertTrue(dirty.contains("uncommitted changes, use --all to discard all changes, or 'hg update 0' to update"), dirty);
    }

    @Test
    void aMergeInProgressSuggestsAbortingIt() throws Exception {
        String[] first = new String[1];
        HgRepository repo = twoCommits(first);
        // a second head on top of the first commit, then merged with the tip: the merge is left uncommitted
        new UpdateCommand(repo).setRevision(first[0]).call();
        Files.writeString(new File(tempDir.toFile(), "side.txt").toPath(), "side\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("side").setAuthor("t").call();
        new MergeCommand(repo).setRevision(1).call();
        String hint = hintOf(new RevertCommand(repo));
        assertTrue(hint.contains("uncommitted merge, use --all to discard all changes, or 'hg update -C .' to abort the merge"), hint);
        // with files named but no revision, the merge is refused outright
        String refused = assertThrows(HgValidationException.class,
                () -> new RevertCommand(repo).setPatterns("a.txt").callSelected()).getMessage();
        assertTrue(refused.contains("uncommitted merge with no revision specified"), refused);
    }

    @Test
    void anUnknownRevisionIsRefused() throws Exception {
        HgRepository repo = twoCommits(new String[1]);
        assertThrows(HgRevisionNotFoundException.class,
                () -> new RevertCommand(repo).setPatterns("a.txt").setRevision("ffffffffffff").callSelected());
    }
}
