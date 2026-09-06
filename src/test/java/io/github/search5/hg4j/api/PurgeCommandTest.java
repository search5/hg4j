package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.nio.file.LinkOption;

import static org.junit.jupiter.api.Assertions.*;

public class PurgeCommandTest {

    @Test
    public void testPurgeDeletesUntrackedFileButKeepsTrackedFile(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File tracked = new File(repoDir, "tracked.txt");
        Files.writeString(tracked.toPath(), "tracked");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("commit tracked").call();

        File untracked = new File(repoDir, "untracked.txt");
        Files.writeString(untracked.toPath(), "junk");

        new PurgeCommand(repo).call();

        assertFalse(untracked.exists(), "Untracked file must be deleted by purge");
        assertTrue(tracked.exists(), "Tracked file must survive purge");
    }

    @Test
    public void testPurgeSkipsIgnoredFile(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        Files.writeString(new File(repoDir, ".hgignore").toPath(), "^ignored\\.log$\n");

        File ignored = new File(repoDir, "ignored.log");
        Files.writeString(ignored.toPath(), "ignore me");

        File untracked = new File(repoDir, "untracked.txt");
        Files.writeString(untracked.toPath(), "junk");

        new PurgeCommand(repo).call();

        assertTrue(ignored.exists(), "Ignored file must survive purge");
        assertFalse(untracked.exists(), "Untracked non-ignored file must be deleted");
    }

    @Test
    public void testPurgeDeletesEmptyUntrackedDirByDefault(@TempDir Path tempDir) throws Exception {
        // Backlog #39: real hg's own `hg purge` deletes untracked empty directories with ZERO
        // flags needed (verified live, 2026-09-05, `hg help purge`: listed under "by default") --
        // `setPurgeDirectories(false)` is the opt-OUT, matching real hg's `--files`-only mode.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File dir = new File(repoDir, "emptydir");
        assertTrue(dir.mkdir());

        new PurgeCommand(repo).call();

        assertFalse(dir.exists(), "Untracked empty directory must be purged by default, matching real hg");
    }

    @Test
    public void testPurgeDirectoriesFalseLeavesEmptyUntrackedDir(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File dir = new File(repoDir, "emptydir");
        assertTrue(dir.mkdir());

        new PurgeCommand(repo).setPurgeDirectories(false).call();

        assertTrue(dir.exists(), "Directory must survive purge when purgeDirectories is explicitly disabled");
    }

    @Test
    public void testPurgeDirectoriesRemovesEmptyUntrackedDirAfterFilePurge(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File dir = new File(repoDir, "junkdir");
        assertTrue(dir.mkdir());
        File fileInDir = new File(dir, "junk.txt");
        Files.writeString(fileInDir.toPath(), "junk");

        PurgeCommand cmd = new PurgeCommand(repo).setPurgeDirectories(true);
        assertSame(cmd, cmd.setPurgeDirectories(true), "setPurgeDirectories must return this for chaining");
        cmd.call();

        assertFalse(fileInDir.exists(), "File inside untracked directory must be purged");
        assertFalse(dir.exists(), "Directory left empty by purge must itself be purged");
    }

    @Test
    public void testPurgeDirectoriesKeepsDirWithIgnoredFile(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        Files.writeString(new File(repoDir, ".hgignore").toPath(), "^keepdir/kept\\.log$\n");

        File dir = new File(repoDir, "keepdir");
        assertTrue(dir.mkdir());
        File ignoredFile = new File(dir, "kept.log");
        Files.writeString(ignoredFile.toPath(), "keep me");

        new PurgeCommand(repo).setPurgeDirectories(true).call();

        assertTrue(ignoredFile.exists(), "Ignored file inside directory must survive purge");
        assertTrue(dir.exists(), "Directory not left empty (ignored file remains) must survive purge");
    }

    @Test
    public void testPurgeDirectoriesSkipsIgnoredDirectory(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        Files.writeString(new File(repoDir, ".hgignore").toPath(), "^ignoreddir$\n");

        File dir = new File(repoDir, "ignoreddir");
        assertTrue(dir.mkdir());

        new PurgeCommand(repo).setPurgeDirectories(true).call();

        assertTrue(dir.exists(), "Directory matched by ignore pattern must survive purge even when empty");
    }

    @Test
    public void testPurgeDirectoriesRemovesNestedEmptyDirs(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File outer = new File(repoDir, "outer");
        File inner = new File(outer, "inner");
        assertTrue(inner.mkdirs());
        File deepFile = new File(inner, "deep.txt");
        Files.writeString(deepFile.toPath(), "junk");

        new PurgeCommand(repo).setPurgeDirectories(true).call();

        assertFalse(deepFile.exists());
        assertFalse(inner.exists(), "Nested inner directory must be purged bottom-up");
        assertFalse(outer.exists(), "Outer directory must be purged after inner is emptied");
    }

    @Test
    public void testPurgeDirectoriesKeepsDirWhoseNameMatchesRemovedTrackedFile(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File asFile = new File(repoDir, "was_a_file");
        Files.writeString(asFile.toPath(), "content");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("add was_a_file").call();
        new RemoveCommand(repo).setFile("was_a_file").call();
        assertFalse(asFile.exists());
        assertEquals('r', repo.getDirstate().getEntries().get("was_a_file").getState());

        File asDir = new File(repoDir, "was_a_file");
        assertTrue(asDir.mkdir());

        new PurgeCommand(repo).setPurgeDirectories(true).call();

        assertTrue(asDir.exists(),
                "Directory whose relative path still keys a dirstate entry must be treated as tracked and survive purge");
    }

    @Test
    public void testPurgeDeletesBrokenSymlink(@TempDir Path tempDir) throws Exception {
        // Backlog #39: real hg 7.2's own `hg purge` DOES delete a broken (dangling-target)
        // symlink -- verified live, 2026-09-05 (`hg purge` logs "removing file <broken-link>").
        // The old hg4j behavior (silently skipping it) came from Files.exists() following the
        // link and seeing "no target" as "path doesn't exist at all".
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        Path brokenLink = repoDir.toPath().resolve("broken-link");
        try {
            Files.createSymbolicLink(brokenLink, repoDir.toPath().resolve("does-not-exist"));
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }
        assertFalse(Files.exists(brokenLink), "Symlink target must not exist for this test");

        new PurgeCommand(repo).call();

        assertFalse(Files.exists(brokenLink, LinkOption.NOFOLLOW_LINKS),
                "A broken symlink must be deleted by purge, matching real hg");
    }

    @Test
    public void testPurgeNeverTraversesIntoSymlinkedDirectory(@TempDir Path tempDir) throws Exception {
        // Backlog #39 (real, severe data-loss bug found while extending requirement-matrix
        // coverage): the old implementation used Files.isDirectory(path) WITHOUT
        // LinkOption.NOFOLLOW_LINKS, so a symlink pointing at a directory was walked THROUGH --
        // any file on the far side of it (entirely outside the repository) looked "untracked" to
        // the parent's dirstate and got deleted. Verified live against real hg 7.2: `hg purge`
        // deletes only the symlink itself, an external target directory's contents are completely
        // untouched (confirmed via `hg purge -p` listing just the link's own path, and an actual
        // `hg purge` run that left the external directory's file in place).
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File externalDir = new File(tempDir.toFile().getParentFile(), "external-target-" + System.nanoTime());
        assertTrue(externalDir.mkdirs());
        File precious = new File(externalDir, "precious.txt");
        Files.writeString(precious.toPath(), "do not delete me");

        Path linkDir = repoDir.toPath().resolve("linkdir");
        try {
            Files.createSymbolicLink(linkDir, externalDir.toPath());
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }

        new PurgeCommand(repo).call();

        assertFalse(Files.exists(linkDir, LinkOption.NOFOLLOW_LINKS),
                "The symlink itself must be purged, matching real hg");
        assertTrue(precious.exists(), "A file reachable only through a directory symlink must NEVER be deleted by purge");
    }

}
