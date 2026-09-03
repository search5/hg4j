package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.ProgressMonitor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage-focused tests for {@link CloneCommand}: exercises the branches left uncovered by the
 * existing suite (empty-source-url validation, pre-existing empty/occupied destination handling,
 * a bare/empty-source clone, symlink and executable-flag checkout, the progress monitor wiring,
 * and the defensive error paths inside the private {@code checkoutLatest} helper). The
 * store-corruption scenarios invoke {@code checkoutLatest} directly via reflection since it is
 * otherwise unreachable in isolation from {@link CloneCommand#call()}.
 */
public class CloneCommandCoverageTest {

    // ─────────────────────────────────────────────
    // Source/destination validation branches
    // ─────────────────────────────────────────────

    @Test
    public void testCloneCommandEmptySourceUrl(@TempDir Path tempDir) {
        // sourceUrl != null but isEmpty() == true is a distinct branch from sourceUrl == null.
        CloneCommand clone = new CloneCommand().setSource("").setDirectory(tempDir.toFile());
        assertThrows(IllegalStateException.class, clone::call);
    }

    @Test
    public void testCloneCommandIntoPreexistingEmptyDirectory(@TempDir Path tempDir) throws Exception {
        // Real `hg clone src emptydir` succeeds when the destination already exists but is empty
        // (verified against real hg 7.2). Exercises directory.exists()==true && list().length==0.
        File sourceDir = new File(tempDir.toFile(), "source");
        HgRepository sourceRepo = Hg.init().setDirectory(sourceDir).call();
        Files.writeString(new File(sourceDir, "a.txt").toPath(), "hello");
        new AddCommand(sourceRepo).call();
        new CommitCommand(sourceRepo).setMessage("init").call();

        File destDir = new File(tempDir.toFile(), "dest");
        assertTrue(destDir.mkdirs());
        assertEquals(0, destDir.list().length);

        HgRepository cloned = Hg.cloneRepository()
                .setSource(sourceDir.getAbsolutePath())
                .setDirectory(destDir)
                .call();

        assertEquals(destDir.getCanonicalFile(), cloned.getDirectory().getCanonicalFile());
        assertTrue(new File(destDir, "a.txt").exists());
        assertEquals("hello", Files.readString(new File(destDir, "a.txt").toPath()));
    }

    @Test
    public void testCloneCommandDestinationIsExistingFile(@TempDir Path tempDir) throws Exception {
        // directory.exists()==true but directory.list()==null (it's a plain file, not a
        // directory) short-circuits the "not empty" validation in CloneCommand, but InitCommand
        // then correctly refuses to turn a file into a repository. Real hg aborts with
        // "destination already exists" for this case; hg4j surfaces a different exception type
        // (HgRepositoryNotFoundException) but likewise refuses to proceed, so this is a
        // pre-existing minor divergence in message/type only, not a functional bug.
        File destFile = new File(tempDir.toFile(), "not-a-dir");
        assertTrue(destFile.createNewFile());

        CloneCommand clone = new CloneCommand()
                .setSource("http://some.server/repo")
                .setDirectory(destFile);
        Exception ex = assertThrows(HgRepositoryNotFoundException.class, clone::call);
        assertTrue(ex.getMessage().contains("not a directory"));
    }

    // ─────────────────────────────────────────────
    // Empty-source clone (pull returns zero commits)
    // ─────────────────────────────────────────────

    @Test
    public void testCloneCommandFromEmptySourceRepository(@TempDir Path tempDir) throws Exception {
        File sourceDir = new File(tempDir.toFile(), "empty-source");
        Hg.init().setDirectory(sourceDir).call(); // no commits at all

        File destDir = new File(tempDir.toFile(), "dest");

        HgRepository cloned = Hg.cloneRepository()
                .setSource(sourceDir.getAbsolutePath())
                .setDirectory(destDir)
                .call();

        assertTrue(destDir.exists());
        File clIdx = new File(cloned.getStoreDir(), "00changelog.i");
        assertFalse(clIdx.exists() && clIdx.length() > 0, "expected an empty changelog for an empty source clone");
        // No working-copy files should have been checked out.
        String[] entries = destDir.list((dir, name) -> !name.equals(".hg"));
        assertEquals(0, entries.length);
    }

    // ─────────────────────────────────────────────
    // Progress monitor wiring
    // ─────────────────────────────────────────────

    @Test
    public void testSetProgressMonitorIgnoresNullAndInvokesRealMonitor(@TempDir Path tempDir) throws Exception {
        File sourceDir = new File(tempDir.toFile(), "source");
        HgRepository sourceRepo = Hg.init().setDirectory(sourceDir).call();
        Files.writeString(new File(sourceDir, "a.txt").toPath(), "hello");
        new AddCommand(sourceRepo).call();
        new CommitCommand(sourceRepo).setMessage("init").call();

        RecordingProgressMonitor recorder = new RecordingProgressMonitor();
        File destDir = new File(tempDir.toFile(), "dest");

        CloneCommand clone = new CloneCommand()
                .setSource(sourceDir.getAbsolutePath())
                .setDirectory(destDir);

        // Passing null must be a no-op (must not replace/clear the previously set monitor,
        // and must not throw).
        assertEquals(clone, clone.setProgressMonitor(null));
        clone.setProgressMonitor(recorder);
        clone.setProgressMonitor(null);

        clone.call();

        assertTrue(recorder.started, "expected monitor.start(...) to be invoked");
        assertTrue(recorder.ended, "expected monitor.end() to be invoked");
        assertTrue(recorder.updateCount >= 1, "expected at least one monitor.update(...) call");
    }

    private static final class RecordingProgressMonitor implements ProgressMonitor {
        boolean started;
        boolean ended;
        int updateCount;

        @Override
        public void start(String title, int totalWork) {
            started = true;
        }

        @Override
        public void update(int completed) {
            updateCount++;
        }

        @Override
        public void end() {
            ended = true;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    }

    // ─────────────────────────────────────────────
    // Checkout of special file modes (symlink / executable)
    // ─────────────────────────────────────────────

    @Test
    public void testCloneChecksOutSymlink(@TempDir Path tempDir) throws Exception {
        File sourceDir = new File(tempDir.toFile(), "source");
        HgRepository sourceRepo = Hg.init().setDirectory(sourceDir).call();

        Files.writeString(new File(sourceDir, "target.txt").toPath(), "target content");
        File linkFile = new File(sourceDir, "link.txt");
        Files.createSymbolicLink(linkFile.toPath(), Path.of("target.txt"));

        new AddCommand(sourceRepo).call();
        new CommitCommand(sourceRepo).setMessage("add symlink").call();

        File destDir = new File(tempDir.toFile(), "dest");
        Hg.cloneRepository().setSource(sourceDir.getAbsolutePath()).setDirectory(destDir).call();

        File clonedLink = new File(destDir, "link.txt");
        assertTrue(Files.isSymbolicLink(clonedLink.toPath()), "cloned link.txt should be a symlink");
        assertEquals("target.txt", Files.readSymbolicLink(clonedLink.toPath()).toString());
    }

    @Test
    public void testCloneChecksOutExecutableFile(@TempDir Path tempDir) throws Exception {
        File sourceDir = new File(tempDir.toFile(), "source");
        HgRepository sourceRepo = Hg.init().setDirectory(sourceDir).call();

        File execFile = new File(sourceDir, "run.sh");
        Files.writeString(execFile.toPath(), "#!/bin/sh\necho hi\n");
        assertTrue(execFile.setExecutable(true, false));

        new AddCommand(sourceRepo).call();
        new CommitCommand(sourceRepo).setMessage("add executable").call();

        File destDir = new File(tempDir.toFile(), "dest");
        Hg.cloneRepository().setSource(sourceDir.getAbsolutePath()).setDirectory(destDir).call();

        File clonedExec = new File(destDir, "run.sh");
        assertTrue(clonedExec.exists());
        assertTrue(clonedExec.canExecute(), "cloned executable flag should be preserved");
    }

    // ─────────────────────────────────────────────
    // checkoutLatest() defensive/error branches, exercised directly via reflection since
    // CloneCommand.call() never reaches them through the public API alone.
    // ─────────────────────────────────────────────

    private static void invokeCheckoutLatest(HgRepository repo) throws Throwable {
        Method m = CloneCommand.class.getDeclaredMethod("checkoutLatest", HgRepository.class);
        m.setAccessible(true);
        try {
            m.invoke(new CloneCommand(), repo);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    public void testCheckoutLatestNoOpOnEmptyChangelog(@TempDir Path tempDir) throws Throwable {
        // A freshly-initialized repository has zero changelog revisions; checkoutLatest must
        // simply return without doing anything (guards future reuse of this helper, e.g. by an
        // "update" style command, against being called before any commit exists).
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        invokeCheckoutLatest(repo);

        // No working copy files created beyond .hg itself.
        String[] entries = repoDir.list((dir, name) -> !name.equals(".hg"));
        assertEquals(0, entries.length);
    }

    @Test
    public void testCheckoutLatestIsIdempotentWhenFileAlreadyExists(@TempDir Path tempDir) throws Throwable {
        // Re-running checkoutLatest over an already-checked-out working copy must delete and
        // rewrite the existing file rather than fail (diskFile.exists() == true branch).
        File sourceDir = new File(tempDir.toFile(), "source");
        HgRepository sourceRepo = Hg.init().setDirectory(sourceDir).call();
        Files.writeString(new File(sourceDir, "a.txt").toPath(), "hello");
        new AddCommand(sourceRepo).call();
        new CommitCommand(sourceRepo).setMessage("init").call();

        File destDir = new File(tempDir.toFile(), "dest");
        HgRepository dest = Hg.cloneRepository().setSource(sourceDir.getAbsolutePath()).setDirectory(destDir).call();

        assertTrue(new File(destDir, "a.txt").exists());

        // Re-run directly: file already exists on disk this time.
        invokeCheckoutLatest(dest);

        assertEquals("hello", Files.readString(new File(destDir, "a.txt").toPath()));
    }

    @Test
    public void testCheckoutLatestThrowsWhenManifestRevisionMissing(@TempDir Path tempDir) throws Throwable {
        File sourceDir = new File(tempDir.toFile(), "source");
        HgRepository sourceRepo = Hg.init().setDirectory(sourceDir).call();
        Files.writeString(new File(sourceDir, "a.txt").toPath(), "hello");
        new AddCommand(sourceRepo).call();
        new CommitCommand(sourceRepo).setMessage("init").call();

        File destDir = new File(tempDir.toFile(), "dest");
        HgRepository dest = Hg.cloneRepository().setSource(sourceDir.getAbsolutePath()).setDirectory(destDir).call();

        // Simulate store corruption: the changelog still references a manifest node that no
        // longer exists in the manifest revlog (e.g. truncated/missing 00manifest.i/.d).
        File mfIdx = new File(dest.getStoreDir(), "00manifest.i");
        File mfDat = new File(dest.getStoreDir(), "00manifest.d");
        truncate(mfIdx);
        truncate(mfDat);

        HgRepository freshDest = new HgRepository(destDir);
        HgRevisionNotFoundException t = assertThrows(HgRevisionNotFoundException.class,
                () -> invokeCheckoutLatest(freshDest));
        assertTrue(t.getMessage().contains("Manifest revision not found"));
    }

    @Test
    public void testCheckoutLatestThrowsWhenFilelogMissing(@TempDir Path tempDir) throws Throwable {
        File sourceDir = new File(tempDir.toFile(), "source");
        HgRepository sourceRepo = Hg.init().setDirectory(sourceDir).call();
        Files.writeString(new File(sourceDir, "a.txt").toPath(), "hello");
        new AddCommand(sourceRepo).call();
        new CommitCommand(sourceRepo).setMessage("init").call();

        File destDir = new File(tempDir.toFile(), "dest");
        HgRepository dest = Hg.cloneRepository().setSource(sourceDir.getAbsolutePath()).setDirectory(destDir).call();

        // Simulate store corruption: the manifest references "a.txt" but its filelog is gone.
        File flIdx = CommitCommand.getFilelogIndex(dest.getStoreDir(), "a.txt");
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        assertTrue(flIdx.delete());
        if (flDat.exists()) {
            assertTrue(flDat.delete());
        }

        HgRepository freshDest = new HgRepository(destDir);
        HgRepositoryNotFoundException t = assertThrows(HgRepositoryNotFoundException.class,
                () -> invokeCheckoutLatest(freshDest));
        assertTrue(t.getMessage().contains("Filelog index not found"));
    }

    @Test
    public void testCheckoutLatestThrowsWhenFileRevisionMissingFromFilelog(@TempDir Path tempDir) throws Throwable {
        File sourceDir = new File(tempDir.toFile(), "source");
        HgRepository sourceRepo = Hg.init().setDirectory(sourceDir).call();
        Files.writeString(new File(sourceDir, "a.txt").toPath(), "hello");
        new AddCommand(sourceRepo).call();
        new CommitCommand(sourceRepo).setMessage("init").call();

        File destDir = new File(tempDir.toFile(), "dest");
        HgRepository dest = Hg.cloneRepository().setSource(sourceDir.getAbsolutePath()).setDirectory(destDir).call();

        // Simulate store corruption: the filelog exists but has zero revisions, so the manifest's
        // referenced node hash can never be found in it.
        File flIdx = CommitCommand.getFilelogIndex(dest.getStoreDir(), "a.txt");
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        truncate(flIdx);
        truncate(flDat);

        HgRepository freshDest = new HgRepository(destDir);
        HgRevisionNotFoundException t = assertThrows(HgRevisionNotFoundException.class,
                () -> invokeCheckoutLatest(freshDest));
        assertTrue(t.getMessage().contains("File version not found in filelog"));
    }

    @Test
    public void testCloneOfTipWithEmptyManifestSkipsBlankManifestLine(@TempDir Path tempDir) throws Exception {
        // The tip commit's manifest can legitimately be empty (every previously-tracked file was
        // removed), in which case `manifest.getRevisionContent(...)` is empty bytes and
        // `"".split("\n")` yields a single blank-string element -- checkoutLatest's manifest-line
        // parser must skip that blank element rather than choking on it.
        File sourceDir = new File(tempDir.toFile(), "source");
        HgRepository sourceRepo = Hg.init().setDirectory(sourceDir).call();
        Files.writeString(new File(sourceDir, "a.txt").toPath(), "hello");
        new AddCommand(sourceRepo).call();
        new CommitCommand(sourceRepo).setMessage("add a.txt").call();

        new RemoveCommand(sourceRepo).setFile("a.txt").call();
        new CommitCommand(sourceRepo).setMessage("remove a.txt").call();

        File destDir = new File(tempDir.toFile(), "dest");
        HgRepository cloned = Hg.cloneRepository()
                .setSource(sourceDir.getAbsolutePath())
                .setDirectory(destDir)
                .call();

        assertTrue(destDir.exists());
        String[] entries = destDir.list((dir, name) -> !name.equals(".hg"));
        assertEquals(0, entries.length, "tip has no tracked files, so nothing should be checked out");
        assertTrue(cloned.getDirstate().getEntries().isEmpty());
    }

    @Test
    public void testCheckoutLatestReplacesPreexistingDanglingSymlinkAtCheckoutPath(@TempDir Path tempDir) throws Throwable {
        // checkoutLatest's "clear whatever is at this path before writing the checked-out file"
        // guard (`diskFile.exists() || Files.isSymbolicLink(...)`) must also catch a dangling
        // symlink squatting on the target path -- File#exists() alone follows the link and
        // reports false for it, same lstat-aware pattern already established elsewhere
        // (AddCommand/CopyCommand/RevertCommand). CloneCommand.call() itself refuses to clone
        // into a non-empty destination directory, so (mirroring
        // testCheckoutLatestIsIdempotentWhenFileAlreadyExists just above) this re-invokes
        // checkoutLatest directly on an already-checked-out repo after swapping the checked-out
        // file for a dangling symlink.
        File sourceDir = new File(tempDir.toFile(), "source");
        HgRepository sourceRepo = Hg.init().setDirectory(sourceDir).call();
        Files.writeString(new File(sourceDir, "a.txt").toPath(), "real content");
        new AddCommand(sourceRepo).call();
        new CommitCommand(sourceRepo).setMessage("add a.txt").call();

        File destDir = new File(tempDir.toFile(), "dest");
        HgRepository dest = Hg.cloneRepository().setSource(sourceDir.getAbsolutePath()).setDirectory(destDir).call();

        File checkedOut = new File(destDir, "a.txt");
        assertTrue(checkedOut.exists());
        Files.delete(checkedOut.toPath());
        Files.createSymbolicLink(checkedOut.toPath(), Path.of("does-not-exist.txt"));
        assertFalse(checkedOut.exists(), "a dangling symlink must not report as existing");

        invokeCheckoutLatest(dest);

        assertFalse(Files.isSymbolicLink(checkedOut.toPath()), "checkout must replace the dangling symlink");
        assertEquals("real content", Files.readString(checkedOut.toPath()));
    }

    private static void truncate(File f) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            // truncate to zero length
        }
    }
}
