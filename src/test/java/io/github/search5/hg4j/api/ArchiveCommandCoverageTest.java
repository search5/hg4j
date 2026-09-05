package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
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
        // Backlog #39: real hg always prefixes zip members with the destination's basename minus
        // its extension (here "snapshot/") and always adds a .hg_archival.txt metadata member --
        // verified live against real hg 7.2, 2026-09-05 (see ArchiveCommand's own class javadoc).
        try (ZipFile zf = new ZipFile(zipFile)) {
            ZipEntry entryA = zf.getEntry("snapshot/a.txt");
            assertNotNull(entryA);
            assertEquals("Hello A", new String(zf.getInputStream(entryA).readAllBytes(), StandardCharsets.UTF_8));

            ZipEntry entryB = zf.getEntry("snapshot/sub/b.txt");
            assertNotNull(entryB);
            assertEquals("Hello B in subdir", new String(zf.getInputStream(entryB).readAllBytes(), StandardCharsets.UTF_8));

            ZipEntry meta = zf.getEntry("snapshot/.hg_archival.txt");
            assertNotNull(meta);

            assertEquals(3, zf.size(), "Zip must contain exactly the tracked files plus .hg_archival.txt, nothing else");
        }
    }

    @Test
    public void callCreatesArchivalOnlyZipForCommitWithNoTrackedFiles(@TempDir Path tempDir) throws Exception {
        // A commit with zero tracked files produces an empty manifest revision -- the resulting
        // zip must still carry exactly the always-present .hg_archival.txt metadata member.
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        new CommitCommand(repo).setMessage("Empty initial commit").call();

        File zipFile = new File(tempDir.toFile(), "empty.zip");
        new ArchiveCommand(repo).setRevision("tip").setDestination(zipFile).call();

        assertTrue(zipFile.exists());
        try (ZipFile zf = new ZipFile(zipFile)) {
            assertEquals(1, zf.size(), "An empty-manifest commit must still archive .hg_archival.txt alone");
            assertNotNull(zf.getEntry("empty/.hg_archival.txt"));
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

    // The old hand-rolled getManifestForCommit()/flat-manifest-only parser (and its defensive
    // edge-case tests formerly here: null/all-zero/unknown commit node, zero-line changelog
    // content, a manifest revlog that never recorded the node, manifest lines without a null
    // separator) was removed as part of the backlog #39 rewrite -- ArchiveCommand now delegates to
    // HgRepository#getManifestAtCommit(byte[]), the same shared, already treemanifest-aware,
    // already-tested helper TreeCommand/ManifestCommand use (see ArchiveCommand's class javadoc
    // for why the old parser was a real bug: it silently dropped treemanifest subdirectories).
    // Equivalent defensive-branch coverage for that shared helper lives on its own call sites'
    // test classes (e.g. TreeCommandTest, ManifestCommandTest), not duplicated here.
}
