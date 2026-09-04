package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.errors.HgLockException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.obsolete.HgObsMarker;
import io.github.search5.hg4j.util.SafeFileIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strip command for truncating/removing changesets and their descendants
 * completely from repository revlogs, rolling back history securely.
 */
public class StripCommand {
    private final HgRepository repository;
    private String revision;

    public StripCommand(HgRepository repository) {
        this.repository = repository;
    }

    public StripCommand setRevision(String revision) {
        this.revision = revision;
        return this;
    }

    /**
     * Executes SCM strip/rollback operation by physically truncating `.i` and `.d` revlogs
     * at target revision offset and resetting working copy parents.
     *
     * @throws IOException if truncation or workspace restoration fails
     */
    public void call() throws IOException, HgLockException {
        if (revision == null || revision.isEmpty()) {
            throw new IllegalArgumentException("Target revision must be specified for strip rollback");
        }

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        byte[] nodeBytes = NodeIdUtil.resolveRevision(changelog, revision);
        if (nodeBytes == null) {
            throw new IOException("Strip target revision not found: " + revision);
        }

        int targetRev = changelog.findRevision(nodeBytes);
        if (targetRev == -1) {
            throw new IOException("Strip target revision not found in local index: " + revision);
        }

        // We truncate all SCM histories to targetRev - 1
        int keepCount = targetRev;
        byte[] rollbackParent = (keepCount > 0) ? changelog.getIndexRecord(keepCount - 1).getNodeId() : new byte[20];

        File dirstateFile = new File(repository.getDirectory(), ".hg/dirstate");
        byte[] dirstateBackup = dirstateFile.exists() ? Files.readAllBytes(dirstateFile.toPath()) : null;
        File journalFile = new File(repository.getStoreDir(), "journal");

        // Every store/hg-dir file this strip is about to mutate is first copied whole into
        // backupDir and recorded here, so a failure can restore it byte-for-byte. A plain
        // "relpath\tsize" journal entry (as CommitCommand/FetchCommand use for their
        // append-only writes) cannot undo a strip: strip only ever shrinks revlogs, and
        // FileChannel#truncate can never grow a file back up, so restoring via truncate(origSize)
        // to a size larger than the file's current (already-shrunk) size is a silent no-op --
        // confirmed by writing this test in StripCommandCoverageTest and observing the
        // "restored" changelog stay at its truncated size. Physical copy+restore (matching the
        // "backup <orig>\t<backup>" journal format HgRepository.checkAndPerformAutoRollback()
        // and RebaseCommand already use) is the only correct way to undo a shrink.
        File backupDir = new File(repository.getStoreDir(), "strip-journal-backup");
        Map<File, File> backupMapping = new HashMap<>();
        Set<File> touchedFiles = new LinkedHashSet<>();

        try (HgLock storeLock = repository.lockStore();
             HgLock wlock = repository.lockWorkingCopy()) {

            // 0. Create physical journal and file backups
            Files.deleteIfExists(journalFile.toPath());
            deleteDirRecursively(backupDir);
            if (dirstateFile.exists()) {
                File dirstateBackupFile = new File(repository.getDirectory(), ".hg/dirstate.backup");
                Files.copy(dirstateFile.toPath(), dirstateBackupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                appendToJournal(journalFile, "dirstate");
            }

            // Back up core changelog and manifest before they get truncated below
            File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
            File mfDat = new File(repository.getStoreDir(), "00manifest.d");
            // Loaded up-front (before anything below truncates it) purely to read exact
            // per-revision .d byte offsets for truncateRevlog() -- see its javadoc.
            Revlog manifest = repository.getRevlog(mfIdx, mfDat);
            backupBeforeMutate(journalFile, backupDir, backupMapping, touchedFiles, clIdx);
            backupBeforeMutate(journalFile, backupDir, backupMapping, touchedFiles, clDat);
            backupBeforeMutate(journalFile, backupDir, backupMapping, touchedFiles, mfIdx);
            backupBeforeMutate(journalFile, backupDir, backupMapping, touchedFiles, mfDat);

            // 1. Truncate / delete individual file revlogs whose linkRev >= targetRev
            File fncacheFile = new File(repository.getStoreDir(), "fncache");
            List<String> fncachePaths = fncacheFile.exists()
                ? Files.readAllLines(fncacheFile.toPath(), StandardCharsets.UTF_8)
                : new ArrayList<>();

            List<String> updatedFncachePaths = new ArrayList<>();

            for (String storePath : fncachePaths) {
                if (storePath.equals("00changelog.i") || storePath.equals("00manifest.i")) {
                    updatedFncachePaths.add(storePath);
                    continue;
                }

                if (storePath.endsWith(".i")) {
                    File flIdx = new File(repository.getStoreDir(), storePath);
                    String baseName = flIdx.getPath();
                    File flDat = new File(baseName.substring(0, baseName.length() - 2) + ".d");

                    if (flIdx.exists()) {
                        backupBeforeMutate(journalFile, backupDir, backupMapping, touchedFiles, flIdx);
                        backupBeforeMutate(journalFile, backupDir, backupMapping, touchedFiles, flDat);

                        Revlog filelog = repository.getRevlog(flIdx, flDat);
                        int flCount = filelog.getRevisionCount();
                        int flKeepCount = 0;
                        for (int j = 0; j < flCount; j++) {
                            Revlog.IndexRecord rec = filelog.getIndexRecord(j);
                            if (rec.getLinkRev() < targetRev) {
                                flKeepCount++;
                            } else {
                                break;
                            }
                        }

                        if (flKeepCount == 0) {
                            flIdx.delete();
                            if (flDat.exists()) {
                                flDat.delete();
                            }
                        } else {
                            filelog.truncate(flKeepCount);
                            updatedFncachePaths.add(storePath);
                        }
                    }
                }
            }

            // Write back updated fncache atomically (this always (re)writes the file, even
            // creating it from scratch when it did not exist before, so it must be journaled
            // unconditionally too)
            backupBeforeMutate(journalFile, backupDir, backupMapping, touchedFiles, fncacheFile);
            SafeFileIO.writeLinesAtomic(fncacheFile, updatedFncachePaths);

            // 2. Truncate Core Changelog and Manifest
            changelog.truncate(keepCount);
            manifest.truncate(keepCount);

            // 3. Bookmarks pointing at a stripped revision follow it back to the new tip
            // rather than being deleted — real hg's strip.py `strip()` calls
            // `repo._bookmarks.applychanges()` with each such bookmark remapped to the
            // first surviving ancestor (all the way to the null node when everything is
            // stripped). Verified against real hg CLI (2026-09-01): after `hg bookmark -r
            // <rev>` then `hg strip -r <rev>`, `hg bookmarks` still lists the bookmark,
            // now at the parent revision; stripping revision 0 leaves it at -1:000000000000.
            String rollbackParentHex = NodeIdUtil.toHex(rollbackParent);
            File bookmarksFile = new File(repository.getHgDir(), "bookmarks");
            if (bookmarksFile.exists()) {
                backupBeforeMutate(journalFile, backupDir, backupMapping, touchedFiles, bookmarksFile);
                List<String> bLines = Files.readAllLines(bookmarksFile.toPath(), StandardCharsets.UTF_8);
                List<String> updatedBLines = new ArrayList<>();
                for (String line : bLines) {
                    if (line.trim().isEmpty()) continue;
                    String[] parts = line.trim().split("\\s+", 2);
                    if (parts.length == 2) {
                        String hexNode = parts[0];
                        String bookmarkName = parts[1];
                        byte[] node = NodeIdUtil.fromHex(hexNode);
                        int rev = changelog.findRevision(node);
                        if (rev != -1 && rev < targetRev) {
                            updatedBLines.add(line);
                        } else if (rev != -1) {
                            updatedBLines.add(rollbackParentHex + " " + bookmarkName);
                        }
                        // rev == -1: bookmark already refers to an unresolvable/corrupt
                        // node (unrelated to this strip) -- drop it rather than guess.
                    }
                }
                if (updatedBLines.isEmpty()) {
                    bookmarksFile.delete();
                } else {
                    SafeFileIO.writeLinesAtomic(bookmarksFile, updatedBLines);
                }
            }

            // 4. Clean phase roots whose revisions are stripped
            // 실제 hg는 phaseroots를 .hg/store/phaseroots에 저장한다(.hg/phaseroots가 아님 —
            // real hg CLI로 확인, 2026-09-01).
            File phaserootsFile = new File(repository.getStoreDir(), "phaseroots");
            if (phaserootsFile.exists()) {
                backupBeforeMutate(journalFile, backupDir, backupMapping, touchedFiles, phaserootsFile);
                List<String> pLines = Files.readAllLines(phaserootsFile.toPath(), StandardCharsets.UTF_8);
                List<String> updatedPLines = new ArrayList<>();
                for (String line : pLines) {
                    if (line.trim().isEmpty()) continue;
                    String[] parts = line.trim().split("\\s+", 2);
                    if (parts.length == 2) {
                        String hexNode = parts[1];
                        byte[] node = NodeIdUtil.fromHex(hexNode);
                        int rev = changelog.findRevision(node);
                        if (rev != -1 && rev < targetRev) {
                            updatedPLines.add(line);
                        }
                    }
                }
                if (updatedPLines.isEmpty()) {
                    phaserootsFile.delete();
                } else {
                    SafeFileIO.writeLinesAtomic(phaserootsFile, updatedPLines);
                }
            }

            // Restore dirstate parent safely
            Dirstate d = repository.getDirstate();
            byte[] parent20 = new byte[20];
            System.arraycopy(rollbackParent, 0, parent20, 0, 20);
            d.setParents(parent20, new byte[20]);
            repository.writeDirstate(d);

            // The working copy is reset to whatever revision survives as the new tip —
            // its branch may differ from the branch the stripped revision(s) were on
            // (e.g. stripping a feature-branch tip lands back on a default-branch
            // ancestor), so the working branch must follow it, same as UpdateCommand.
            if (keepCount > 0) {
                repository.setBranch(CommitCommand.getBranchOfRevision(changelog, keepCount - 1));
            } else {
                repository.setBranch("default");
            }

            // Register obsolescence marker pruning the stripped commit completely (no successors)
            try {
                HgObsMarker.writeMarker(repository.getStoreDir(), nodeBytes, List.of(), "prune");
            } catch (Exception e) {
                // non-blocking
            }

            repository.clearRevlogCache();

            // 5. Successful strip complete -> clear the journal and physical backups.
            // Real hg's strip does NOT leave `hg rollback` information behind (verified
            // against real hg CLI, 2026-09-01: `hg rollback` right after a successful
            // `hg strip` reports "no rollback information available") -- it relies solely
            // on its `.hg/strip-backup/*.hg` backup bundle for recovery instead, so we don't
            // register anything with CommitCommand.writeUndoInfo here either.
            //
            // What a successful strip DOES do, and what CommitCommand's own undo info leaves
            // behind from an earlier commit, is invalidate any pending `hg rollback` capability:
            // verified against real hg CLI (2026-09-01) -- committing twice, then stripping the
            // tip, leaves no `.hg/store/undo*`/`.hg/undo*` files at all, where before the strip
            // the second commit's undo info was present. History has moved on underneath it, so
            // that stale undo info (which could otherwise "roll back" straight past the strip)
            // must not survive.
            Files.deleteIfExists(new File(repository.getStoreDir(), "undo").toPath());
            Files.deleteIfExists(new File(repository.getStoreDir(), "undo.backup.files").toPath());
            Files.deleteIfExists(new File(repository.getDirectory(), ".hg/undo.backup.dirstate").toPath());
            Files.deleteIfExists(new File(repository.getDirectory(), ".hg/undo.backup.bookmarks").toPath());

            Files.deleteIfExists(journalFile.toPath());
            File dirstateBackupFile = new File(repository.getDirectory(), ".hg/dirstate.backup");
            Files.deleteIfExists(dirstateBackupFile.toPath());
            deleteDirRecursively(backupDir);

        } catch (Exception e) {
            // Transaction rollback: restore every touched file from its physical backup
            // (or delete it, if it was newly created by this failed attempt and never existed
            // before), then discard the backups and journal.
            for (File file : touchedFiles) {
                File backup = backupMapping.get(file);
                if (backup != null && backup.exists()) {
                    Files.copy(backup.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(file.toPath());
                }
            }
            if (dirstateBackup != null) {
                SafeFileIO.writeAtomic(dirstateFile, dirstateBackup);
            }
            Files.deleteIfExists(journalFile.toPath());
            deleteDirRecursively(backupDir);
            repository.clearRevlogCache();
            throw e;
        }
    }

    /**
     * Copies {@code source} into {@code backupDir} (preserving its path relative to the
     * repository's {@code .hg} directory) before it gets truncated/rewritten/deleted, and
     * records a {@code backup <orig>\t<backup>} journal line so a crash between here and the
     * mutation can still be recovered by {@code HgRepository.checkAndPerformAutoRollback()} on
     * the next repository open. A source that does not exist yet is intentionally left
     * unbacked-up (and thus absent from {@code backupMapping}) but still added to {@code
     * touchedFiles}, so rollback knows to delete it if this attempt ends up creating it.
     */
    private void backupBeforeMutate(File journalFile, File backupDir, Map<File, File> backupMapping,
                                     Set<File> touchedFiles, File source) throws IOException {
        touchedFiles.add(source);
        if (!source.exists() || backupMapping.containsKey(source)) {
            return;
        }
        File hgDir = repository.getHgDir();
        String origRel = hgDir.toPath().relativize(source.toPath()).toString().replace('\\', '/');
        File target = new File(backupDir, origRel);
        File targetParent = target.getParentFile();
        if (targetParent != null) {
            targetParent.mkdirs();
        }
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        backupMapping.put(source, target);
        String backupRel = hgDir.toPath().relativize(target.toPath()).toString().replace('\\', '/');
        appendToJournal(journalFile, "backup " + origRel + "\t" + backupRel);
    }

    private void deleteDirRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteDirRecursively(child);
            }
        }
        file.delete();
    }

    // Revlog truncation itself (the exact-offset .d truncate, the inline-vs-non-inline v1
    // branching, and v2/docket-based end-pointer bookkeeping) now lives on the reusable
    // Revlog.truncate(int) -- see its javadoc for the full history of what this used to get
    // wrong (backlog #39, requirement-matrix expansion to StripCommand, 2026-09-05).

    private void appendToJournal(File journalFile, String entry) throws IOException {
        Files.writeString(journalFile.toPath(), entry + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try (FileChannel fc = FileChannel.open(journalFile.toPath(), StandardOpenOption.WRITE)) {
            fc.force(true);
        }
    }
}
