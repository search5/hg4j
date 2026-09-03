package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.util.NodeIdUtil;

/**
 * Coverage-focused tests for {@link UpdateCommand}, targeting branches and defensive-error
 * paths not exercised by {@link UpdateCommandTest}: null-hook / null-filter guard clauses,
 * the "removed" arm of the dirty-working-copy check, resolution of an ambiguous hex-prefix
 * revision, first-checkout-after-pull (no prior dirstate parent), a fresh (not skip-rewrite)
 * executable-file checkout, an unreadable pre-existing file on disk, a target file whose
 * filelog index is missing entirely, a target file revision missing from an otherwise-present
 * filelog, and the subrepo-checkout branches (git-subrepo skip, empty source URL / empty
 * recorded revision, and a failing subrepo pull/update being logged rather than aborting the
 * overall update).
 */
public class UpdateCommandCoverageTest {

    @Test
    public void registeringNullHooksAndNullTreeFilterIsANoOp(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        // Passing null must not add a hook entry and must not throw, and setTreeFilter(null)
        // must leave the default (accept-everything) filter in place rather than NPE-ing.
        byte[] result = new UpdateCommand(repo)
                .setRevision("0")
                .registerPreUpdateHook(null)
                .registerPostUpdateHook(null)
                .setTreeFilter(null)
                .call();
        assertNotNull(result);
    }

    @Test
    public void callThrowsWithoutForceWhenATrackedFileWasDeletedFromDisk(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        File a = new File(repoDir, "a.txt");
        Files.writeString(a.toPath(), "v0");
        Files.writeString(new File(repoDir, "b.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        // Deleted from disk without `hg remove`: StatusCommand reports it under "removed",
        // which must block a non-forced update just like a modification or addition would.
        Files.delete(a.toPath());
        assertThrows(HgValidationException.class,
                () -> new UpdateCommand(repo).setRevision("0").call());
    }

    @Test
    public void callThrowsWithoutForceWhenAFileWasAddedButNotYetCommitted(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        // Staged with `hg add` but not yet committed: dirstate carries an 'a' entry, which
        // StatusCommand reports under "added". This must independently trigger the same
        // dirty-working-copy guard as a modification or a removal, exercising the first
        // (short-circuiting) arm of the OR check on its own.
        Files.writeString(new File(repoDir, "new.txt").toPath(), "staged");
        new AddCommand(repo).addFile("new.txt").call();
        assertThrows(HgValidationException.class,
                () -> new UpdateCommand(repo).setRevision("0").call());
    }

    @Test
    public void updateTreatsUnresolvableDirstateParentAsEmptyManifest(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        // Force the dirstate's recorded parent to a node id that exists nowhere in the
        // changelog (simulating a stale/corrupted dirstate, e.g. after history was
        // rewritten out from under it). UpdateCommand must not blow up trying to resolve
        // it to a revision number; it must fall back to treating the current tree as an
        // empty manifest, so the checkout below still creates a.txt from scratch.
        Dirstate dirstate = repo.getDirstate();
        byte[] bogusParent = new byte[20];
        Arrays.fill(bogusParent, (byte) 0x42);
        dirstate.setParents(bogusParent, new byte[20]);
        repo.writeDirstate(dirstate);

        new UpdateCommand(repo).setRevision("0").call();
        assertEquals("v0", Files.readString(new File(repoDir, "a.txt").toPath()));
    }

    @Test
    public void deletingATrackedFileDuringCheckoutIsANoOpWhenItsAlreadyGoneFromDisk(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        File gone = new File(repoDir, "gone.txt");
        Files.writeString(gone.toPath(), "will vanish");
        new AddCommand(repo).addFile("gone.txt").call();
        new CommitCommand(repo).setMessage("rev1 adds gone.txt").call();
        assertTrue(gone.exists());

        // Remove it from disk directly (bypassing `hg remove`), then update back to rev0
        // (which never had it): UpdateCommand's own deletion step must tolerate the file
        // already being absent rather than failing when it calls Files.delete.
        Files.delete(gone.toPath());
        new UpdateCommand(repo).setRevision("0").setForce(true).call();
        assertFalse(gone.exists());
        assertFalse(repo.getDirstate().getEntries().containsKey("gone.txt"));
    }

    @Test
    public void checkingOutReplacesASymlinkThatBecameBrokenSinceItWasLastCheckedOut(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();

        Files.writeString(new File(repoDir, "target.txt").toPath(), "target content");
        File link = new File(repoDir, "link.txt");
        Files.createSymbolicLink(link.toPath(), Path.of("target.txt"));
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0: link -> target.txt").call();

        Files.writeString(new File(repoDir, "other.txt").toPath(), "other content");
        Files.delete(link.toPath());
        Files.createSymbolicLink(link.toPath(), Path.of("other.txt"));
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev1: link -> other.txt").call();

        // Delete the symlink's current target directly (bypassing `hg remove`): link.txt
        // is still a valid on-disk symlink object, but following it now fails, so
        // File.exists() reports false while Files.isSymbolicLink() still reports true.
        Files.delete(new File(repoDir, "other.txt").toPath());
        assertFalse(link.exists());
        assertTrue(Files.isSymbolicLink(link.toPath()));

        // Updating back to rev0 (a real content change for link.txt) must detect the
        // pre-existing (now-broken) symlink via the isSymbolicLink() arm of the OR check
        // and replace it with a link to target.txt.
        new UpdateCommand(repo).setRevision("0").setForce(true).call();

        assertTrue(Files.isSymbolicLink(link.toPath()));
        assertEquals("target.txt", Files.readSymbolicLink(link.toPath()).toString());
    }

    @Test
    public void updateSkipsSubrepoCheckoutWhenOnlyHgsubIsPresentWithoutHgsubstate(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        // .hgsub without a matching .hgsubstate: subrepo checkout must be skipped
        // entirely (neither file alone is enough to trigger it).
        Files.writeString(new File(repoDir, ".hgsub").toPath(), "vendor/x = /nonexistent\n");
        new AddCommand(repo).call();
        byte[] committed = new CommitCommand(repo).setMessage("rev0").call();

        byte[] updated = new UpdateCommand(repo).setRevision("0").setForce(true).call();
        assertEquals(NodeIdUtil.toHex(committed), NodeIdUtil.toHex(updated));
        assertFalse(new File(repoDir, "vendor").exists());
    }

    @Test
    public void firstUpdateAfterPullWithoutPriorCheckoutUsesEmptyParentManifest(@TempDir Path tempDir) throws Exception {
        File sourceDir = tempDir.resolve("source").toFile();
        HgRepository sourceRepo = Hg.init().setDirectory(sourceDir).call();
        Files.writeString(new File(sourceDir, "a.txt").toPath(), "v0");
        new AddCommand(sourceRepo).call();
        new CommitCommand(sourceRepo).setMessage("rev0").call();

        File destDir = tempDir.resolve("dest").toFile();
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        new PullCommand(destRepo).setSource(sourceDir.getAbsolutePath()).call();

        // destRepo's changelog now has revision 0, but its dirstate was never checked out:
        // parent1 is still the null revision, which does not resolve to any changelog
        // revision, so UpdateCommand must fall back to treating the current tree as empty.
        new UpdateCommand(destRepo).setRevision("0").call();
        assertEquals("v0", Files.readString(new File(destDir, "a.txt").toPath()));
    }

    @Test
    public void executableFileCreatedFreshDuringCheckoutGetsExecutableBit(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        File script = new File(repoDir, "run.sh");
        Files.writeString(script.toPath(), "#!/bin/sh\necho hi\n");
        assertTrue(script.setExecutable(true, false));
        new AddCommand(repo).addFile("run.sh").call();
        new CommitCommand(repo).setMessage("rev1 adds executable").call();

        // Go back to rev0 (removing run.sh from disk), then forward again so the checkout
        // must freshly *write* the executable file rather than take the no-op skip-rewrite
        // path already covered by UpdateCommandTest.
        new UpdateCommand(repo).setRevision("0").call();
        assertFalse(script.exists());
        new UpdateCommand(repo).setRevision("1").call();

        assertTrue(script.exists());
        assertTrue(script.canExecute(), "Freshly checked-out file must get its executable bit from the manifest");
    }

    @Test
    public void updateOverwritesAnUnreadablePreExistingFileInstead(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        File a = new File(repoDir, "a.txt");
        Files.writeString(a.toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        Files.writeString(a.toPath(), "v1");
        new CommitCommand(repo).setMessage("rev1").call();

        new UpdateCommand(repo).setRevision("0").call();
        assertTrue(a.setReadable(false, false), "Test requires being able to revoke owner read permission");
        try {
            // The pre-existing file on disk can no longer be read to compare its content
            // against the target revision; UpdateCommand must swallow that read failure
            // and simply overwrite the file rather than propagate the exception.
            new UpdateCommand(repo).setRevision("1").call();
            assertEquals("v1", Files.readString(a.toPath()));
        } finally {
            a.setReadable(true, false);
        }
    }

    @Test
    public void updateThrowsWhenTargetFilelogIndexIsMissingFromStore(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "a.txt");
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        Files.delete(flIdx.toPath());
        Files.deleteIfExists(flDat.toPath());

        // Simulates a store missing the filelog entirely for a file the manifest still
        // references (e.g. a corrupted or incomplete repository).
        HgRepositoryNotFoundException ex = assertThrows(HgRepositoryNotFoundException.class,
                () -> new UpdateCommand(repo).setRevision("0").call());
        assertTrue(ex.getMessage().contains("Filelog index not found"));
    }

    @Test
    public void updateThrowsWhenTargetFileRevisionIsMissingFromFilelog(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();
        File a = new File(repoDir, "a.txt");
        Files.writeString(a.toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        Files.writeString(a.toPath(), "v1");
        new CommitCommand(repo).setMessage("rev1").call();

        new UpdateCommand(repo).setRevision("0").call();

        // Truncate the filelog to zero bytes: the file still exists (flIdx.exists() is
        // true) but has lost every recorded revision, so the target file's node id can
        // no longer be located within it.
        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "a.txt");
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        Files.write(flIdx.toPath(), new byte[0]);
        Files.write(flDat.toPath(), new byte[0]);

        // Use a fresh HgRepository handle so no previously cached Revlog masks the
        // on-disk truncation.
        HgRepository fresh = new HgRepository(repoDir);
        HgRevisionNotFoundException ex = assertThrows(HgRevisionNotFoundException.class,
                () -> new UpdateCommand(fresh).setRevision("1").call());
        assertTrue(ex.getMessage().contains("File version not found in filelog"));
    }

    @Test
    public void ambiguousRevisionPrefixIsRejectedWithClearError(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File repoDir = tempDir.toFile();

        List<String> hexes = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            Files.writeString(new File(repoDir, "f" + i + ".txt").toPath(), "content " + i);
            new AddCommand(repo).addFile("f" + i + ".txt").call();
            byte[] node = new CommitCommand(repo).setMessage("rev" + i).call();
            hexes.add(NodeIdUtil.toHex(node));
        }

        // Node ids are effectively random 40-hex-digit strings, so among 60 of them some
        // short hex-letter-containing prefix (a-f; a plain-digit prefix would instead be
        // parsed as an integer revision number) is virtually certain to collide.
        String ambiguousPrefix = findLetterPrefixCollision(hexes);
        assertNotNull(ambiguousPrefix, "Expected a colliding hex-letter prefix among " + hexes.size() + " commits");

        assertThrows(HgRevisionNotFoundException.class,
                () -> new UpdateCommand(repo).setRevision(ambiguousPrefix).call());
    }

    private static String findLetterPrefixCollision(List<String> hexes) {
        for (int len = 1; len <= 4; len++) {
            Map<String, Integer> counts = new HashMap<>();
            for (String h : hexes) {
                String p = h.substring(0, len);
                if (!containsHexLetter(p)) {
                    continue; // a plain-digit prefix is parsed as an integer revision, not a hex prefix
                }
                counts.merge(p, 1, Integer::sum);
            }
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (e.getValue() >= 2) {
                    return e.getKey();
                }
            }
        }
        return null;
    }

    private static boolean containsHexLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'a' && c <= 'f') {
                return true;
            }
        }
        return false;
    }

    @Test
    public void subrepoCheckoutSkipsGitEntriesAndHandlesUnrecordedRevisionAndSource(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("main").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        // "vendor/gitmod" is flagged [git] and must be skipped outright (no directory
        // created for it). "vendor/emptyish" is declared in .hgsub but has no recorded
        // source URL and is absent from .hgsubstate entirely, so it falls back to an
        // entry with both an empty source URL and an empty revision: the local repo
        // must still be initialized for it, but neither pull nor update is attempted.
        Files.writeString(new File(repoDir, ".hgsub").toPath(),
                "vendor/gitmod = [git]https://example.com/whatever.git\n" +
                        "vendor/emptyish =\n");
        Files.writeString(new File(repoDir, ".hgsubstate").toPath(),
                "1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b vendor/gitmod\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev1 with subrepos").call();

        new UpdateCommand(repo).setRevision("tip").setForce(true).call();

        assertFalse(new File(repoDir, "vendor/gitmod").exists(),
                "Git-flagged subrepo entries must be skipped entirely");
        assertTrue(new File(repoDir, "vendor/emptyish/.hg").exists(),
                "Subrepo with no source URL or recorded revision must still be locally initialized");
    }

    @Test
    public void subrepoPullFailureIsLoggedAndDoesNotAbortTheOverallUpdate(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("main").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        File nonExistentSource = tempDir.resolve("no-such-source-repo").toFile();
        Files.writeString(new File(repoDir, ".hgsub").toPath(),
                "vendor/bad = " + nonExistentSource.getAbsolutePath() + "\n");
        Files.writeString(new File(repoDir, ".hgsubstate").toPath(),
                "0000000000000000000000000000000000000000 vendor/bad\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev1 with unreachable subrepo").call();

        // Pulling from a source that does not exist fails and is logged; the subsequent
        // attempt to update the (still-empty) subrepo to a revision it doesn't have also
        // fails, and that failure propagates up to the outer catch-and-log around the
        // whole subrepo-checkout block rather than aborting the main update.
        byte[] result = new UpdateCommand(repo).setRevision("tip").setForce(true).call();
        assertNotNull(result);
        assertEquals("v0", Files.readString(new File(repoDir, "a.txt").toPath()));
    }

    @Test
    public void postUpdateHookExceptionIsLoggedAndDoesNotFailTheUpdate(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("rev0").call();

        byte[] result = new UpdateCommand(repo).setRevision("0")
                .registerPostUpdateHook(ctx -> {
                    throw new IOException("boom");
                })
                .call();
        assertNotNull(result);
    }
}
