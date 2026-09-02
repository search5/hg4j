package com.github.search5.hg4j.api;

import com.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import com.github.search5.hg4j.errors.HgRevisionNotFoundException;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage-focused tests for {@link ArchiveCommand}, targeting branches the base
 * {@code PorcelainExtraCommandsTest}/{@code HgRemainingPorcelainCoverageTest} smoke tests don't
 * reach: the missing-destination and unresolvable-revision validation paths, and the
 * getManifestForCommit/getFileRevisionContent private-helper defensive branches (corrupted or
 * out-of-sync store data) that call()'s own always-consistent call sites can never trigger
 * organically.
 */
public class ArchiveCommandCoverageTest {

    private static Object invokePrivate(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = ArchiveCommand.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception) {
                throw (Exception) e.getCause();
            }
            throw e;
        }
    }

    @Test
    public void callThrowsIllegalArgumentExceptionWhenDestinationNotSet(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        ArchiveCommand cmd = new ArchiveCommand(repo).setRevision("tip");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, cmd::call);
        assertTrue(ex.getMessage().contains("Destination target must be specified"));
    }

    @Test
    public void callThrowsIOExceptionWhenRevisionCannotBeResolved(@TempDir Path tempDir) throws Exception {
        // An empty repository (no commits yet) has no "tip" to resolve to a node --
        // NodeIdUtil.resolveRevision returns null, which call() must surface as IOException.
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File dest = new File(tempDir.toFile(), "out");
        ArchiveCommand cmd = new ArchiveCommand(repo).setRevision("tip").setDestination(dest);
        IOException ex = assertThrows(IOException.class, cmd::call);
        assertTrue(ex.getMessage().contains("Archive target revision not found: tip"));
    }

    @Test
    public void callThrowsIOExceptionForUnknownNamedRevision(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "a.txt").toPath(), "content");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit 1").call();

        File dest = new File(tempDir.toFile(), "out.zip");
        ArchiveCommand cmd = new ArchiveCommand(repo).setRevision("deadbeef").setDestination(dest);
        IOException ex = assertThrows(IOException.class, cmd::call);
        assertTrue(ex.getMessage().contains("Archive target revision not found: deadbeef"));
    }

    @Test
    public void callWritesRepositoryFilesystemContentToZipEntries(@TempDir Path tempDir) throws Exception {
        File repoDir = new File(tempDir.toFile(), "repo");
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        Files.writeString(new File(repoDir, "a.txt").toPath(), "Hello A");
        File subDir = new File(repoDir, "sub");
        subDir.mkdirs();
        Files.writeString(new File(subDir, "b.txt").toPath(), "Hello B in subdir");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit 1").call();

        File zipFile = new File(tempDir.toFile(), "snapshot.zip");
        new ArchiveCommand(repo).setRevision("tip").setDestination(zipFile).call();

        assertTrue(zipFile.exists() && zipFile.length() > 0);
        try (ZipFile zf = new ZipFile(zipFile)) {
            ZipEntry entryA = zf.getEntry("a.txt");
            assertNotNull(entryA);
            assertEquals("Hello A", new String(zf.getInputStream(entryA).readAllBytes(), StandardCharsets.UTF_8));

            ZipEntry entryB = zf.getEntry("sub/b.txt");
            assertNotNull(entryB);
            assertEquals("Hello B in subdir", new String(zf.getInputStream(entryB).readAllBytes(), StandardCharsets.UTF_8));

            assertEquals(2, zf.size(), "Zip must contain exactly the tracked files, nothing else");
        }
    }

    @Test
    public void callCreatesEmptyZipForCommitWithNoTrackedFiles(@TempDir Path tempDir) throws Exception {
        // A commit with zero tracked files produces an empty manifest revision (mContent == "").
        // getManifestForCommit's line-parsing loop must handle the resulting single empty
        // split() element without adding a spurious entry (line.isEmpty() -> continue).
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        new CommitCommand(repo).setMessage("Empty initial commit").call();

        File zipFile = new File(tempDir.toFile(), "empty.zip");
        new ArchiveCommand(repo).setRevision("tip").setDestination(zipFile).call();

        assertTrue(zipFile.exists());
        try (ZipFile zf = new ZipFile(zipFile)) {
            assertEquals(0, zf.size(), "An empty-manifest commit must archive to an empty zip");
        }
    }

    @Test
    public void callThrowsHgRepositoryNotFoundExceptionWhenFilelogMissingFromStore(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File tracked = new File(tempDir.toFile(), "a.txt");
        Files.writeString(tracked.toPath(), "content");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit 1").call();

        // Simulate store corruption: the filelog backing "a.txt" (which the manifest still
        // references) disappears from disk after the commit.
        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "a.txt");
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        assertTrue(flIdx.exists(), "precondition: filelog index must exist before deleting it");
        Files.delete(flIdx.toPath());
        if (flDat.exists()) {
            Files.delete(flDat.toPath());
        }

        File dest = new File(tempDir.toFile(), "out.zip");
        ArchiveCommand cmd = new ArchiveCommand(repo).setRevision("tip").setDestination(dest);
        HgRepositoryNotFoundException ex = assertThrows(HgRepositoryNotFoundException.class, cmd::call);
        assertTrue(ex.getMessage().contains("Filelog index does not exist for: a.txt"));
    }

    @Test
    public void getFileRevisionContentThrowsHgRevisionNotFoundExceptionForUnknownHex(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "a.txt").toPath(), "content");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit 1").call();

        ArchiveCommand cmd = new ArchiveCommand(repo);
        String bogusHex = "f".repeat(40);
        HgRevisionNotFoundException ex = assertThrows(HgRevisionNotFoundException.class, () ->
                invokePrivate(cmd, "getFileRevisionContent",
                        new Class<?>[]{HgRepository.class, String.class, String.class},
                        repo, "a.txt", bogusHex));
        assertTrue(ex.getMessage().contains("File revision not found: a.txt @ " + bogusHex));
    }

    @Test
    public void getManifestForCommitReturnsEmptyMapForNullOrAllZeroOrUnknownCommitNode(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        Files.writeString(new File(tempDir.toFile(), "a.txt").toPath(), "content");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit 1").call();

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog changelog = repo.getRevlog(clIdx, clDat);
        File mfIdx = new File(repo.getStoreDir(), "00manifest.i");
        File mfDat = new File(repo.getStoreDir(), "00manifest.d");
        Revlog manifestRevlog = repo.getRevlog(mfIdx, mfDat);

        ArchiveCommand cmd = new ArchiveCommand(repo);
        Class<?>[] types = {Revlog.class, Revlog.class, byte[].class};

        @SuppressWarnings("unchecked")
        Map<String, String> forNull = (Map<String, String>) invokePrivate(cmd, "getManifestForCommit", types,
                changelog, manifestRevlog, null);
        assertTrue(forNull.isEmpty());

        @SuppressWarnings("unchecked")
        Map<String, String> forAllZero = (Map<String, String>) invokePrivate(cmd, "getManifestForCommit", types,
                changelog, manifestRevlog, new byte[20]);
        assertTrue(forAllZero.isEmpty());

        byte[] unknownNode = new byte[20];
        unknownNode[0] = 0x7f;
        @SuppressWarnings("unchecked")
        Map<String, String> forUnknown = (Map<String, String>) invokePrivate(cmd, "getManifestForCommit", types,
                changelog, manifestRevlog, unknownNode);
        assertTrue(forUnknown.isEmpty(), "A commit node absent from the changelog must yield an empty manifest");
    }

    @Test
    public void getManifestForCommitReturnsEmptyWhenChangelogContentSplitsToZeroLines(@TempDir Path tempDir) throws Exception {
        // String.split("\n") on content that is a single bare newline (all resulting pieces are
        // trailing-empty) yields a zero-length array, unlike split() on truly empty content
        // (which yields one empty element) -- this is only reachable via a changelog revision
        // whose stored content is exactly "\n", e.g. store corruption that truncated the commit
        // metadata away. getManifestForCommit's lines.length guard must handle that without an
        // ArrayIndexOutOfBoundsException on lines[0].
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog changelog = repo.getRevlog(clIdx, clDat);
        File mfIdx = new File(repo.getStoreDir(), "00manifest.i");
        File mfDat = new File(repo.getStoreDir(), "00manifest.d");
        Revlog manifestRevlog = repo.getRevlog(mfIdx, mfDat);

        byte[] zeroParent = new byte[20];
        assertEquals(0, "\n".split("\n").length, "precondition: split() on a bare newline must yield zero elements");
        byte[] commitNode = changelog.appendRevision(
                "\n".getBytes(StandardCharsets.UTF_8), -1, -1, zeroParent, zeroParent, 0);

        ArchiveCommand cmd = new ArchiveCommand(repo);
        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) invokePrivate(cmd, "getManifestForCommit",
                new Class<?>[]{Revlog.class, Revlog.class, byte[].class},
                changelog, manifestRevlog, commitNode);

        assertTrue(result.isEmpty(), "Zero-line changelog content must yield an empty manifest, not throw");
    }

    @Test
    public void getManifestForCommitReturnsEmptyWhenManifestRevlogNeverRecordedTheNode(
            @TempDir Path repoDirPath, @TempDir Path otherRepoDirPath) throws Exception {
        HgRepository repo = Hg.init().setDirectory(repoDirPath.toFile()).call();
        Files.writeString(new File(repoDirPath.toFile(), "a.txt").toPath(), "content");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit 1").call();
        byte[] commitNode = repo.getDirstate().getParent1();

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog changelog = repo.getRevlog(clIdx, clDat);

        // A manifest revlog from an unrelated, empty repository -- pairing it with the real
        // changelog above simulates a manifest store restored from an older backup than its
        // changelog, which never recorded the commit's manifest node.
        HgRepository otherRepo = Hg.init().setDirectory(otherRepoDirPath.toFile()).call();
        File otherMfIdx = new File(otherRepo.getStoreDir(), "00manifest.i");
        File otherMfDat = new File(otherRepo.getStoreDir(), "00manifest.d");
        Revlog neverWrittenManifestRevlog = otherRepo.getRevlog(otherMfIdx, otherMfDat);

        ArchiveCommand cmd = new ArchiveCommand(repo);
        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) invokePrivate(cmd, "getManifestForCommit",
                new Class<?>[]{Revlog.class, Revlog.class, byte[].class},
                changelog, neverWrittenManifestRevlog, commitNode);

        assertTrue(result.isEmpty(),
                "An unresolvable manifest node must yield an empty manifest, not throw or return stale data");
    }

    @Test
    public void getManifestForCommitSkipsManifestLinesWithoutNullSeparator(@TempDir Path tempDir) throws Exception {
        // Every manifest line hg4j itself ever writes is "path\0hexflags", so a line lacking the
        // null separator can only arise from external store corruption. Exercise that defensive
        // skip directly by hand-crafting a manifest revision (via Revlog.appendRevision, the same
        // low-level primitive CommitCommand uses) containing one well-formed entry and one
        // malformed line, then a changelog revision pointing at it.
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();

        File mfIdx = new File(repo.getStoreDir(), "00manifest.i");
        File mfDat = new File(repo.getStoreDir(), "00manifest.d");
        Revlog manifestRevlog = repo.getRevlog(mfIdx, mfDat);
        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog changelog = repo.getRevlog(clIdx, clDat);

        byte[] zeroParent = new byte[20];
        String goodHex = "a".repeat(40);
        String malformedManifestText = "good.txt\0" + goodHex + "\n" + "malformed-line-without-null-separator\n";
        byte[] manifestNode = manifestRevlog.appendRevision(
                malformedManifestText.getBytes(StandardCharsets.UTF_8), -1, -1, zeroParent, zeroParent, 0);

        String changelogText = NodeIdUtil.toHex(manifestNode) + "\nTester <t@example.com>\n0 0\n\ngoodtxt only\n";
        byte[] commitNode = changelog.appendRevision(
                changelogText.getBytes(StandardCharsets.UTF_8), -1, -1, zeroParent, zeroParent, 0);

        ArchiveCommand cmd = new ArchiveCommand(repo);
        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) invokePrivate(cmd, "getManifestForCommit",
                new Class<?>[]{Revlog.class, Revlog.class, byte[].class},
                changelog, manifestRevlog, commitNode);

        assertEquals(1, result.size(), "The malformed line must be skipped, not throw or produce a bogus entry");
        assertEquals(goodHex, result.get("good.txt"));
        assertFalse(result.containsKey("malformed-line-without-null-separator"));
    }
}
