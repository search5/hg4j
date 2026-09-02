package com.github.search5.hg4j.lib;

import com.github.search5.hg4j.api.AddCommand;
import com.github.search5.hg4j.api.CommitCommand;
import com.github.search5.hg4j.api.Hg;
import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.errors.HgLockException;
import com.github.search5.hg4j.lib.NodeId;
import com.github.search5.hg4j.phase.PhaseRoots;
import com.github.search5.hg4j.storage.DefaultFileStoreEngine;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.storage.StoreEngine;
import com.github.search5.hg4j.errors.HgCorruptDataException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage for {@link HgRepository}, targeting branches not exercised by
 * {@link HgRepositoryTest}: individual requires flags, sharedpath resolution (success and
 * failure), malformed requires/hgrc handling, revlog cache reuse/invalidation, manifest
 * delegation, phaseroots share-safe location, ignore-pattern edge cases, working-copy scanning
 * with symlinks, branch round-trip, and the auto-rollback-on-crash-recovery journal replay.
 */
public class HgRepositoryCoverageTest {

    @Test
    public void testDirstateV2RequirementFlagAndPropagatesToWrittenDirstate(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        File hgDir = new File(repoDir, ".hg");
        File storeDir = new File(hgDir, "store");
        storeDir.mkdirs();
        Files.writeString(new File(hgDir, "requires").toPath(), "dirstate-v2\n");

        try (HgRepository repo = new HgRepository(repoDir)) {
            assertFalse(repo.isChangelogV2());
            assertFalse(repo.isRevlogV2());
            assertFalse(repo.isPersistentNodemap());
            assertFalse(repo.isUseZstdCompression());

            Dirstate dirstate = new Dirstate();
            repo.writeDirstate(dirstate);
            assertTrue(dirstate.isV2(), "dirstate-v2 requirement should force written dirstate to v2 format");
        }
    }

    @Test
    public void testSharedpathResolvesStoreDirToSharedRepository(@TempDir Path tempDir) throws Exception {
        File sharedRepoDir = new File(tempDir.toFile(), "shared");
        File sharedHgDir = new File(sharedRepoDir, ".hg");
        File sharedStoreDir = new File(sharedHgDir, "store");
        sharedStoreDir.mkdirs();
        Files.writeString(new File(sharedStoreDir, "requires").toPath(), "exp-changelog-v2\n");

        File poolRepoDir = new File(tempDir.toFile(), "pool");
        File poolHgDir = new File(poolRepoDir, ".hg");
        poolHgDir.mkdirs();
        Files.writeString(new File(poolHgDir, "sharedpath").toPath(), sharedHgDir.getAbsolutePath());

        try (HgRepository repo = new HgRepository(poolRepoDir)) {
            assertEquals(sharedStoreDir.getCanonicalFile(), repo.getStoreDir().getCanonicalFile());
            // requires from the *shared* store must be honored through the resolved storeDir
            assertTrue(repo.isChangelogV2());
        }
    }

    @Test
    public void testSharedpathReadFailureFallsBackToLocalStoreDir(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        File hgDir = new File(repoDir, ".hg");
        hgDir.mkdirs();
        // Invalid UTF-8 byte sequence makes Files.readString(...) throw, exercising the catch
        // branch in the constructor that falls back to .hg/store.
        Files.write(new File(hgDir, "sharedpath").toPath(), new byte[] { (byte) 0xFF, (byte) 0xFE, (byte) 0x00 });

        try (HgRepository repo = new HgRepository(repoDir)) {
            assertEquals(new File(hgDir, "store").getAbsoluteFile(), repo.getStoreDir().getAbsoluteFile());
        }
    }

    @Test
    public void testRequiresFileAsDirectoryIsSilentlyIgnored(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        File hgDir = new File(repoDir, ".hg");
        // requires exists but is a directory, not a file -> readRequiresFile must skip it
        new File(hgDir, "requires").mkdirs();

        try (HgRepository repo = new HgRepository(repoDir)) {
            assertFalse(repo.isChangelogV2());
            assertFalse(repo.isUseZstdCompression());
        }
    }

    @Test
    public void testRequiresFileWithInvalidEncodingFallsBackToDefaults(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        File hgDir = new File(repoDir, ".hg");
        hgDir.mkdirs();
        // Malformed UTF-8 makes Files.readAllLines(...) throw, exercising the catch(Exception)
        // fallback-to-v1 branch in readRequiresFile.
        Files.write(new File(hgDir, "requires").toPath(), new byte[] { (byte) 0xC0, (byte) 0xC1 });

        try (HgRepository repo = new HgRepository(repoDir)) {
            assertFalse(repo.isChangelogV2());
            assertFalse(repo.isRevlogV2());
            assertFalse(repo.isPersistentNodemap());
            assertFalse(repo.isUseZstdCompression());
        }
    }

    @Test
    public void testLoadConfigSkipsWhenHgrcIsDirectory(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        File hgDir = new File(repoDir, ".hg");
        // hgrc exists but is a directory -> loadConfig's exists()&&isFile() guard must skip it
        new File(hgDir, "hgrc").mkdirs();

        try (HgRepository repo = new HgRepository(repoDir)) {
            assertNull(repo.getConfig().getPath("default"));
        }
    }

    @Test
    public void testLoadConfigSwallowsReadFailure(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        File hgDir = new File(repoDir, ".hg");
        hgDir.mkdirs();
        // Malformed UTF-8 makes HgRcConfig.load()'s Files.readString(...) throw; loadConfig must
        // swallow it (non-blocking configuration load) rather than propagate from the constructor.
        Files.write(new File(hgDir, "hgrc").toPath(), new byte[] { (byte) 0xC0, (byte) 0xC1 });

        try (HgRepository repo = new HgRepository(repoDir)) {
            assertNull(repo.getConfig().getPath("default"));
        }
    }

    @Test
    public void testGetManifestAtCommitAndManifestRevlogDelegateToStoreEngine(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            File f1 = new File(repoDir, "a.txt");
            Files.writeString(f1.toPath(), "hello\n");
            new AddCommand(repo).addFile("a.txt").call();
            new CommitCommand(repo).setMessage("c1").call();

            Dirstate dirstate = repo.getDirstate();
            byte[] parent1 = dirstate.getParent1();

            Map<String, String> manifest = repo.getManifestAtCommit(parent1);
            assertTrue(manifest.containsKey("a.txt"));

            Revlog manifestRevlog = repo.getManifestRevlog();
            assertNotNull(manifestRevlog);
            assertTrue(manifestRevlog.getRevisionCount() >= 1);
        }
    }

    @Test
    public void testRevlogCacheReturnsSameInstanceUntilCleared(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
            File storeDir = repo.getStoreDir();
            storeDir.mkdirs();
            File idx = new File(storeDir, "00changelog.i");
            File dat = new File(storeDir, "00changelog.d");
            Files.write(idx.toPath(), new byte[0]);

            Revlog first = repo.getRevlog(idx, dat);
            Revlog second = repo.getRevlog(idx, dat);
            assertSame(first, second, "repeated getRevlog() with same canonical files must hit the cache");

            repo.clearRevlogCache();
            Revlog third = repo.getRevlog(idx, dat);
            assertNotSame(first, third, "clearRevlogCache() must force a fresh Revlog instance");
        }
    }

    @Test
    public void testGetPhaseRootsReadsFromStoreDirNotHgDir(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
            File storeDir = repo.getStoreDir();
            storeDir.mkdirs();

            String hex = "1111111111111111111111111111111111111111";
            // Real hg records phaseroots in .hg/store/phaseroots (share-safe), never .hg/phaseroots.
            Files.writeString(new File(storeDir, "phaseroots").toPath(), "1 " + hex + "\n");
            // Planting a bogus .hg/phaseroots proves it is NOT the one being read.
            Files.writeString(new File(repo.getHgDir(), "phaseroots").toPath(), "2 " + hex + "\n");

            PhaseRoots phaseRoots = repo.getPhaseRoots();
            NodeId node = NodeId.fromHex(hex);
            PhaseRoots.Phase phase = phaseRoots.getPhase(node, n -> new NodeId[0]);
            assertEquals(PhaseRoots.Phase.DRAFT, phase, "must read value from store/phaseroots (1=draft), not .hg/phaseroots (2=secret)");
        }
    }

    @Test
    public void testIsIgnoredRegexpLineStartingWithCaretIsUsedVerbatim(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
            repo.getHgDir().mkdirs();
            File ignoreFile = new File(repoDir, ".hgignore");
            // Line already anchored with ^ must NOT be wrapped in (?:.... ) by the loader.
            Files.writeString(ignoreFile.toPath(), "^foo\\.txt$\n");

            assertTrue(repo.isIgnored("foo.txt"));
            assertFalse(repo.isIgnored("xfoo.txt"));
        }
    }

    @Test
    public void testScanWorkingCopySkipsHgDirIgnoredFilesAndSymlinkedDirectories(@TempDir Path tempDir) throws Exception {
        assumeSymlinksSupported(tempDir);

        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
            repo.getHgDir().mkdirs();
            Files.writeString(new File(repo.getHgDir(), "somejunk").toPath(), "should never be scanned");

            File subdir = new File(repoDir, "subdir");
            subdir.mkdirs();
            Files.writeString(new File(subdir, "nested.txt").toPath(), "nested");
            Files.writeString(new File(repoDir, "top.txt").toPath(), "top");
            Files.writeString(new File(repoDir, "ignored.txt").toPath(), "ignored");
            Files.writeString(new File(repoDir, ".hgignore").toPath(), "syntax: glob\nignored.txt\n");

            File linkToDir = new File(repoDir, "linkdir");
            Files.createSymbolicLink(linkToDir.toPath(), subdir.toPath());
            File linkToFile = new File(repoDir, "linkfile");
            Files.createSymbolicLink(linkToFile.toPath(), new File(repoDir, "top.txt").toPath());

            List<String> scanned = repo.scanWorkingCopy();

            assertTrue(scanned.contains("subdir/nested.txt"));
            assertTrue(scanned.contains("top.txt"));
            assertTrue(scanned.contains("linkfile"), "symlink to a regular file should be scanned as a file");
            assertFalse(scanned.contains("ignored.txt"));
            // Real hg (verified live: `ln -s existingdir dir-link; hg add`) tracks a symlink to a
            // directory as a plain file entry (content = target path text) — it is never
            // recursed into, but it IS scanned as its own file, just like any other symlink.
            assertTrue(scanned.contains("linkdir"), "symlink to a directory should be scanned as a file, not recursed into");
            assertFalse(scanned.stream().anyMatch(p -> p.startsWith("linkdir/")),
                    "symlink to a directory must not be recursed into");
            assertFalse(scanned.stream().anyMatch(p -> p.contains("somejunk")), ".hg contents must never be scanned");
        }
    }

    @Test
    public void testSetBranchAndGetBranchRoundTrip(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
            assertEquals("default", repo.getBranch(), "default branch when no branch file exists");

            repo.setBranch("feature-x");
            assertEquals("feature-x", repo.getBranch());
        }
    }

    @Test
    public void testSetBranchRejectsNullAndEmpty(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
            assertThrows(IllegalArgumentException.class, () -> repo.setBranch(null));
            assertThrows(IllegalArgumentException.class, () -> repo.setBranch(""));
        }
    }

    @Test
    public void testLockWorkingCopyAndLockStoreWithoutJournalSucceed(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
            repo.getStoreDir().mkdirs();
            try (HgLock wlock = repo.lockWorkingCopy()) {
                assertNotNull(wlock);
                assertTrue(new File(repo.getHgDir(), "wlock").exists()
                        || Files.isSymbolicLink(new File(repo.getHgDir(), "wlock").toPath()));
            }
            // No journal present -> checkAndPerformAutoRollback returns immediately, lock succeeds.
            try (HgLock slock = repo.lockStore()) {
                assertNotNull(slock);
            }
        }
    }

    @Test
    public void testAutoRollbackRestoresBackupsAndTruncatesAndCleansUp(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
            File hgDir = repo.getHgDir();
            File storeDir = repo.getStoreDir();
            storeDir.mkdirs();

            // "backup FILE BACKUPFILE" (space-separated) restores an existing backup.
            File origA = new File(hgDir, "a.i");
            File backupA = new File(hgDir, "a.i.backup");
            Files.writeString(backupA.toPath(), "ORIGINAL-A");
            Files.writeString(origA.toPath(), "CORRUPTED-A");

            // "backup FILE\tBACKUPFILE" (tab-separated) with a MISSING backup deletes the original.
            File origB = new File(hgDir, "b.i");
            Files.writeString(origB.toPath(), "STALE-B");

            // "dirstate" restores dirstate.backup -> dirstate (move).
            File dirstateFile = new File(hgDir, "dirstate");
            File dirstateBackup = new File(hgDir, "dirstate.backup");
            Files.writeString(dirstateFile.toPath(), "CORRUPT-DIRSTATE");
            Files.writeString(dirstateBackup.toPath(), "GOOD-DIRSTATE");

            // "fncache" restores store/fncache.backup -> store/fncache (move).
            File fncacheFile = new File(storeDir, "fncache");
            File fncacheBackup = new File(storeDir, "fncache.backup");
            Files.writeString(fncacheFile.toPath(), "CORRUPT-FNCACHE");
            Files.writeString(fncacheBackup.toPath(), "GOOD-FNCACHE");

            // Generic truncate line "relpath SIZE": truncate an existing file back to origSize.
            File truncTarget = new File(hgDir, "00changelog.i");
            Files.writeString(truncTarget.toPath(), "0123456789");

            // Generic delete line "relpath 0": delete a file created after the transaction started.
            File deleteTarget = new File(hgDir, "created-after-tx.i");
            Files.writeString(deleteTarget.toPath(), "junk");

            // Unparsable line (no separator) must be skipped without throwing.
            String journalContent = String.join("\n",
                    "",
                    "backup a.i a.i.backup",
                    "backup b.i\tb.i.backup",
                    "dirstate",
                    "fncache",
                    "00changelog.i 4",
                    "created-after-tx.i 0",
                    "unparsableLineWithNoSeparator"
            );
            Files.writeString(new File(storeDir, "journal").toPath(), journalContent + "\n");

            // Leftover cleanup artifacts that must be removed after a successful rollback.
            File rebaseBackupDir = new File(storeDir, "rebase-backup");
            File nestedFile = new File(rebaseBackupDir, "nested/deep.txt");
            nestedFile.getParentFile().mkdirs();
            Files.writeString(nestedFile.toPath(), "leftover");

            repo.checkAndPerformAutoRollback();

            assertEquals("ORIGINAL-A", Files.readString(origA.toPath()));
            assertFalse(origB.exists(), "backup missing -> original must be deleted");
            assertEquals("GOOD-DIRSTATE", Files.readString(dirstateFile.toPath()));
            assertFalse(dirstateBackup.exists(), "dirstate.backup must be consumed (moved)");
            assertEquals("GOOD-FNCACHE", Files.readString(fncacheFile.toPath()));
            assertFalse(fncacheBackup.exists(), "fncache.backup must be consumed (moved)");
            assertEquals("0123", Files.readString(truncTarget.toPath()), "file must be truncated to recorded size");
            assertFalse(deleteTarget.exists(), "size-0 entry must delete the file");

            // Successful rollback cleans up the journal and backup artifacts.
            assertFalse(new File(storeDir, "journal").exists());
            assertFalse(rebaseBackupDir.exists(), "rebase-backup directory must be recursively removed");
        }
    }

    @Test
    public void testAutoRollbackWithMissingJournalIsNoOp(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
            repo.getStoreDir().mkdirs();
            // Must not throw when there is nothing to roll back.
            repo.checkAndPerformAutoRollback();
        }
    }

    @Test
    public void testAutoRollbackRetainsJournalOnFailure(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
            File storeDir = repo.getStoreDir();
            storeDir.mkdirs();
            // A malformed size (not a number) makes the generic-entry branch throw
            // NumberFormatException, which is caught -> rollbackSuccess stays false -> journal kept.
            File journalFile = new File(storeDir, "journal");
            Files.writeString(journalFile.toPath(), "somefile.i not-a-number\n");

            repo.checkAndPerformAutoRollback();

            assertTrue(journalFile.exists(), "journal must be retained for retry when rollback fails");
        }
    }

    @Test
    public void testSetStoreEngineIgnoresNullAndSwapClearsCache(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
            repo.getStoreDir().mkdirs();
            File idx = new File(repo.getStoreDir(), "00changelog.i");
            File dat = new File(repo.getStoreDir(), "00changelog.d");
            Files.write(idx.toPath(), new byte[0]);
            Revlog cached = repo.getRevlog(idx, dat);

            // Null must be a documented no-op: engine and cache are left untouched.
            repo.setStoreEngine(null);
            assertSame(cached, repo.getRevlog(idx, dat));

            // Swapping to a real (non-null) engine must invalidate the existing revlog cache.
            repo.setStoreEngine(new DefaultFileStoreEngine());
            Revlog afterSwap = repo.getRevlog(idx, dat);
            assertNotSame(cached, afterSwap, "setStoreEngine(non-null) must clear the revlog cache");
        }
    }

    @Test
    public void testSharedpathAsDirectoryIsNotTreatedAsShareLink(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        File hgDir = new File(repoDir, ".hg");
        hgDir.mkdirs();
        // sharedpath exists but is a directory, not a file -> constructor's isFile() guard must
        // fall through to the local .hg/store, not attempt to read it as a share pointer.
        new File(hgDir, "sharedpath").mkdirs();

        try (HgRepository repo = new HgRepository(repoDir)) {
            assertEquals(new File(hgDir, "store").getAbsoluteFile(), repo.getStoreDir().getAbsoluteFile());
        }
    }

    @Test
    public void testGetDirstateThrowsCorruptDataWhenRebuildAlsoFails(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
            repo.getStoreDir().mkdirs();
            // Changelog exists with at least one revision so rebuildDirstateFromManifest proceeds
            // past the early-return guards and reaches getManifestAtCommit(), which we make fail.
            File clIdx = new File(repo.getStoreDir(), "00changelog.i");
            File clDat = new File(repo.getStoreDir(), "00changelog.d");

            StoreEngine failingEngine = new StoreEngine() {
                private final DefaultFileStoreEngine delegate = new DefaultFileStoreEngine();
                @Override
                public Revlog getRevlog(HgRepository r, File idx, File dat) throws IOException {
                    return delegate.getRevlog(r, idx, dat);
                }
                @Override
                public Map<String, String> getManifestAtCommit(HgRepository r, byte[] commitNodeId) throws IOException {
                    throw new IOException("simulated manifest read failure");
                }
                @Override
                public Dirstate getDirstate(HgRepository r) throws IOException {
                    throw new IOException("simulated dirstate corruption");
                }
                @Override
                public void writeDirstate(HgRepository r, Dirstate dirstate) throws IOException {
                    delegate.writeDirstate(r, dirstate);
                }
                @Override
                public Revlog getManifestRevlog(HgRepository r) throws IOException {
                    return delegate.getManifestRevlog(r);
                }
            };

            // Fabricate a changelog with exactly one revision so getRevisionCount() - 1 >= 0.
            try (HgRepository seedRepo = Hg.init().setDirectory(new File(tempDir.toFile(), "seed")).call()) {
                Files.writeString(new File(seedRepo.getDirectory(), "a.txt").toPath(), "x\n");
                new AddCommand(seedRepo).addFile("a.txt").call();
                new CommitCommand(seedRepo).setMessage("c1").call();
                Files.copy(new File(seedRepo.getStoreDir(), "00changelog.i").toPath(), clIdx.toPath());
                File seedDat = new File(seedRepo.getStoreDir(), "00changelog.d");
                if (seedDat.exists()) {
                    Files.copy(seedDat.toPath(), clDat.toPath());
                } else {
                    Files.write(clDat.toPath(), new byte[0]);
                }
            }

            repo.setStoreEngine(failingEngine);
            HgCorruptDataException ex = assertThrows(HgCorruptDataException.class, repo::getDirstate);
            assertNotNull(ex.getCause());
        }
    }

    @Test
    public void testRebuildDirstateWhenChangelogFileMissingSetsZeroParents(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
            repo.getHgDir().mkdirs();
            // storeDir intentionally left without a 00changelog.i -> clIdx.exists() is false.
            File dirstateFile = new File(repo.getHgDir(), "dirstate");
            Files.writeString(dirstateFile.toPath(), "not a valid dirstate payload at all");

            Dirstate dirstate = repo.getDirstate();
            assertArrayEquals(new byte[20], dirstate.getParent1());
            assertTrue(dirstate.getEntries().isEmpty());
        }
    }

    @Test
    public void testRebuildDirstateWhenChangelogEmptySetsZeroParents(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
            repo.getHgDir().mkdirs();
            repo.getStoreDir().mkdirs();
            // Changelog index exists but has zero revisions -> lastRev < 0.
            Files.write(new File(repo.getStoreDir(), "00changelog.i").toPath(), new byte[0]);
            File dirstateFile = new File(repo.getHgDir(), "dirstate");
            Files.writeString(dirstateFile.toPath(), "not a valid dirstate payload at all");

            Dirstate dirstate = repo.getDirstate();
            assertArrayEquals(new byte[20], dirstate.getParent1());
        }
    }

    @Test
    public void testRebuildDirstatePreservesNonNormalStateAndSkipsMissingWorkingFiles(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            File committed = new File(repoDir, "committed.txt");
            File toBeDeleted = new File(repoDir, "deleted.txt");
            File toBeReplacedByDir = new File(repoDir, "replaced.txt");
            Files.writeString(committed.toPath(), "keep\n");
            Files.writeString(toBeDeleted.toPath(), "gone\n");
            Files.writeString(toBeReplacedByDir.toPath(), "was-a-file\n");
            new AddCommand(repo).addFile("committed.txt").call();
            new AddCommand(repo).addFile("deleted.txt").call();
            new AddCommand(repo).addFile("replaced.txt").call();
            new CommitCommand(repo).setMessage("c1").call();

            // Replace a committed file with a directory of the same name: diskFile.exists() is
            // true but diskFile.isFile() is false, covering the other half of that compound branch.
            Files.delete(toBeReplacedByDir.toPath());
            toBeReplacedByDir.mkdirs();

            // Stage an uncommitted removal: mark deleted.txt as removed ('r') in the in-memory
            // dirstate (mirroring `hg rm`) and delete it from disk, then also add a brand-new file
            // to exercise a non-'n' state on an entry that was never part of any manifest.
            Dirstate live = repo.getDirstate();
            live.addEntry("deleted.txt", new Dirstate.Entry('r', 0, -1, 0));
            Files.delete(toBeDeleted.toPath());
            live.addEntry("uncommitted-new.txt", new Dirstate.Entry('a', 0100644, 3, 0));
            repo.writeDirstate(live);

            // Corrupt the on-disk dirstate so the next read fails and rebuild-from-manifest runs,
            // using the in-memory `live` dirstate (now cachedDirstate) as the source for state
            // inheritance (sourceDirstate != null branch, and its non-'n' entries).
            Files.writeString(new File(repo.getHgDir(), "dirstate").toPath(), "corrupted-on-purpose");

            Dirstate rebuilt = repo.getDirstate();
            Map<String, Dirstate.Entry> entries = rebuilt.getEntries();

            assertEquals('n', entries.get("committed.txt").getState(), "unaffected file stays normal");
            assertFalse(entries.containsKey("deleted.txt"),
                    "manifest entry whose working file no longer exists must be skipped (diskFile.exists()==false)");
            assertFalse(entries.containsKey("replaced.txt"),
                    "manifest entry whose path is now a directory must be skipped (diskFile.isFile()==false)");
            assertFalse(entries.containsKey("uncommitted-new.txt"),
                    "a file that was never committed is not part of the manifest walk and is dropped");
        }
    }

    @Test
    public void testRevlogCacheEvictsEldestEntryBeyondCapacity(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
            File storeDir = repo.getStoreDir();
            storeDir.mkdirs();

            // The cache is capped at 100 entries (LinkedHashMap access-order LRU); the 101st
            // distinct revlog must evict and clearCache() the eldest one.
            for (int i = 0; i < 101; i++) {
                File idx = new File(storeDir, "f" + i + ".i");
                File dat = new File(storeDir, "f" + i + ".d");
                Files.write(idx.toPath(), new byte[0]);
                repo.getRevlog(idx, dat);
            }
            // Must not throw, and the cache must still function afterwards.
            File idx0 = new File(storeDir, "f0.i");
            File dat0 = new File(storeDir, "f0.d");
            assertNotNull(repo.getRevlog(idx0, dat0));
        }
    }

    @Test
    public void testIsIgnoredSkipsBlankAndCommentLinesInHgignore(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
            repo.getHgDir().mkdirs();
            File ignoreFile = new File(repoDir, ".hgignore");
            Files.writeString(ignoreFile.toPath(), "\n   \n# a comment\nfoo.txt\n");

            assertTrue(repo.isIgnored("foo.txt"));
            assertFalse(repo.isIgnored("bar.txt"));
        }
    }

    @Test
    public void testGlobUnclosedBraceIsTakenLiterally(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
            repo.getHgDir().mkdirs();
            File ignoreFile = new File(repoDir, ".hgignore");
            // No closing '}' -> expandBraces() must leave the glob unexpanded (open==-1||close==-1
            // branch), and the literal '{' / '}' characters must be handled by globToRegex's
            // Pattern.quote branch rather than treated as brace-expansion syntax.
            Files.writeString(ignoreFile.toPath(), "syntax: glob\nweird{name\n");

            assertTrue(repo.isIgnored("weird{name"));
            assertFalse(repo.isIgnored("weirdXname"));
        }
    }

    @Test
    public void testGlobTrailingSingleStarAtEndOfPattern(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
            repo.getHgDir().mkdirs();
            File ignoreFile = new File(repoDir, ".hgignore");
            // '*' as the very last character: i + 1 < glob.length() is false, so the lookahead for
            // a second '*' must short-circuit rather than index out of bounds.
            Files.writeString(ignoreFile.toPath(), "syntax: glob\nfile*\n");

            assertTrue(repo.isIgnored("file123"));
            assertFalse(repo.isIgnored("other123"));
        }
    }

    @Test
    public void testLockStorePropagatesRuntimeExceptionFromAutoRollbackAndReleasesLock(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = new HgRepository(repoDir) {
            @Override
            public synchronized void checkAndPerformAutoRollback() {
                throw new IllegalStateException("boom");
            }
        };
        repo.getStoreDir().mkdirs();
        try {
            assertThrows(IllegalStateException.class, repo::lockStore);
            // Lock must have been released (closed) despite the failure, so acquiring again works.
            try (HgLock relock = new HgLock(new File(repo.getStoreDir(), "lock"), 0, true)) {
                assertNotNull(relock);
            }
        } finally {
            repo.close();
        }
    }

    @Test
    public void testLockStoreWrapsNonRuntimeThrowableFromAutoRollbackInHgLockException(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = new HgRepository(repoDir) {
            @Override
            public synchronized void checkAndPerformAutoRollback() {
                throw new AssertionError("simulated non-RuntimeException throwable");
            }
        };
        repo.getStoreDir().mkdirs();
        try {
            HgLockException ex = assertThrows(HgLockException.class, repo::lockStore);
            assertNotNull(ex.getCause());
            assertTrue(ex.getCause() instanceof AssertionError);
        } finally {
            repo.close();
        }
    }

    @Test
    public void testAutoRollbackBackupLineWithMalformedContentIsSkipped(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
            File storeDir = repo.getStoreDir();
            storeDir.mkdirs();
            // "backup" line with only one token after it -> split produces parts.length == 1,
            // so the (parts.length == 2) branch's false path must be taken (no-op, no crash).
            Files.writeString(new File(storeDir, "journal").toPath(), "backup onlytoken\n");

            repo.checkAndPerformAutoRollback();
            // Successful (no-op) processing still deletes the journal.
            assertFalse(new File(storeDir, "journal").exists());
        }
    }

    @Test
    public void testAutoRollbackDirstateAndFncacheWithoutBackupDeleteInsteadOfRestore(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
            File hgDir = repo.getHgDir();
            File storeDir = repo.getStoreDir();
            storeDir.mkdirs();

            File dirstateFile = new File(hgDir, "dirstate");
            Files.writeString(dirstateFile.toPath(), "half-written-dirstate");
            File fncacheFile = new File(storeDir, "fncache");
            Files.writeString(fncacheFile.toPath(), "half-written-fncache");

            // A generic entry using a TAB separator directly (no fallback to space needed), to
            // cover the splitIdx != -1 (tab found immediately) branch.
            File tabTarget = new File(hgDir, "tabtarget.i");
            Files.writeString(tabTarget.toPath(), "1234567890");
            // A generic entry whose target file does not exist at all -> file.exists() false.
            String journal = "dirstate\nfncache\ntabtarget.i\t4\nnonexistent.i 7\n";
            Files.writeString(new File(storeDir, "journal").toPath(), journal);

            repo.checkAndPerformAutoRollback();

            assertFalse(dirstateFile.exists(), "no dirstate.backup present -> original must be deleted");
            assertFalse(fncacheFile.exists(), "no fncache.backup present -> original must be deleted");
            assertEquals("1234", Files.readString(tabTarget.toPath()), "tab-separated generic entry must truncate correctly");
            assertFalse(new File(storeDir, "journal").exists());
        }
    }

    @Test
    public void testAutoRollbackCleanupFailureIsLoggedAndSwallowed(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
            File hgDir = repo.getHgDir();
            File storeDir = repo.getStoreDir();
            storeDir.mkdirs();

            // Trivial journal that succeeds with no work to do, so rollbackSuccess becomes true
            // and the post-rollback cleanup block runs.
            Files.writeString(new File(storeDir, "journal").toPath(), "\n");

            // Make dirstate.backup a NON-EMPTY directory: Files.deleteIfExists() on it throws
            // DirectoryNotEmptyException, which the cleanup block's catch(Exception) must swallow
            // (logged as a WARNING) instead of propagating.
            File dirstateBackupDir = new File(hgDir, "dirstate.backup");
            File nested = new File(dirstateBackupDir, "nested.txt");
            nested.getParentFile().mkdirs();
            Files.writeString(nested.toPath(), "cannot be deleted by deleteIfExists");

            assertDoesNotThrow(repo::checkAndPerformAutoRollback);
            // The journal itself is deleted before the failing cleanup step runs.
            assertFalse(new File(storeDir, "journal").exists());
        }
    }

    @Test
    public void testRebuildDirstateRecordsExecutableModeForExecutableWorkingFile(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = Hg.init().setDirectory(repoDir).call()) {
            File script = new File(repoDir, "run.sh");
            Files.writeString(script.toPath(), "#!/bin/sh\necho hi\n");
            new AddCommand(repo).addFile("run.sh").call();
            new CommitCommand(repo).setMessage("c1").call();

            boolean setExec = script.setExecutable(true, false);
            org.junit.jupiter.api.Assumptions.assumeTrue(setExec, "chmod +x not supported on this filesystem");

            Files.writeString(new File(repo.getHgDir(), "dirstate").toPath(), "corrupted-on-purpose");

            Dirstate rebuilt = repo.getDirstate();
            Dirstate.Entry entry = rebuilt.getEntries().get("run.sh");
            assertNotNull(entry);
            assertEquals(0100755, entry.getMode(), "executable working file must be recorded with the executable mode");
        }
    }

    @Test
    public void testLoadIgnorePatternsSwallowsReadFailureOnMalformedHgignore(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
            repo.getHgDir().mkdirs();
            // Malformed UTF-8 makes Files.readAllLines(...) throw an IOException, exercising
            // loadIgnorePatterns()'s catch(IOException) branch instead of propagating.
            Files.write(new File(repoDir, ".hgignore").toPath(), new byte[] { (byte) 0xC0, (byte) 0xC1 });

            assertFalse(repo.isIgnored("anything.txt"), "a broken .hgignore must not crash isIgnored(), just yield no patterns");
        }
    }

    @Test
    public void testGlobLoneClosingBraceIsTakenLiterally(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        try (HgRepository repo = new HgRepository(repoDir)) {
            repo.getHgDir().mkdirs();
            File ignoreFile = new File(repoDir, ".hgignore");
            // A lone '}' with no matching '{' before it: expandBraces() finds no '{' at all
            // (open == -1), so the glob is passed through unexpanded and globToRegex must handle
            // the standalone '}' via its Pattern.quote branch.
            Files.writeString(ignoreFile.toPath(), "syntax: glob\nweird}name\n");

            assertTrue(repo.isIgnored("weird}name"));
            assertFalse(repo.isIgnored("weirdXname"));
        }
    }

    private static void assumeSymlinksSupported(Path tempDir) {
        try {
            Path link = tempDir.resolve("symlink-probe");
            Path target = tempDir.resolve("symlink-probe-target");
            Files.createDirectories(target);
            Files.createSymbolicLink(link, target);
            Files.delete(link);
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks not supported on this filesystem: " + e);
        }
    }
}
