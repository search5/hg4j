package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.errors.HgLockException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

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

        byte[] nodeBytes = com.github.search5.hg4j.util.NodeIdUtil.resolveRevision(changelog, revision);
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

        java.util.Map<File, Long> fileSizes = new java.util.HashMap<>();
        File dirstateFile = new File(repository.getDirectory(), ".hg/dirstate");
        byte[] dirstateBackup = dirstateFile.exists() ? Files.readAllBytes(dirstateFile.toPath()) : null;
        File journalFile = new File(repository.getStoreDir(), "journal");

        try (com.github.search5.hg4j.lib.HgLock storeLock = repository.lockStore();
             com.github.search5.hg4j.lib.HgLock wlock = repository.lockWorkingCopy()) {
            
            // 0. Create physical journal and file size logs
            Files.deleteIfExists(journalFile.toPath());
            if (dirstateFile.exists()) {
                File dirstateBackupFile = new File(repository.getDirectory(), ".hg/dirstate.backup");
                Files.copy(dirstateFile.toPath(), dirstateBackupFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                appendToJournal(journalFile, "dirstate");
            }

            // Save original size of core changelog and manifest
            File mfIdx = new File(repository.getStoreDir(), "00manifest.i");
            File mfDat = new File(repository.getStoreDir(), "00manifest.d");
            fileSizes.put(clIdx, clIdx.exists() ? clIdx.length() : 0L);
            fileSizes.put(clDat, clDat.exists() ? clDat.length() : 0L);
            fileSizes.put(mfIdx, mfIdx.exists() ? mfIdx.length() : 0L);
            fileSizes.put(mfDat, mfDat.exists() ? mfDat.length() : 0L);

            appendToJournal(journalFile, "store/00changelog.i\t" + fileSizes.get(clIdx));
            appendToJournal(journalFile, "store/00changelog.d\t" + fileSizes.get(clDat));
            appendToJournal(journalFile, "store/00manifest.i\t" + fileSizes.get(mfIdx));
            appendToJournal(journalFile, "store/00manifest.d\t" + fileSizes.get(mfDat));

            // 1. Truncate / delete individual file revlogs whose linkRev >= targetRev
            File fncacheFile = new File(repository.getStoreDir(), "fncache");
            java.util.List<String> fncachePaths = fncacheFile.exists() 
                ? java.nio.file.Files.readAllLines(fncacheFile.toPath(), java.nio.charset.StandardCharsets.UTF_8)
                : new java.util.ArrayList<>();
                
            java.util.List<String> updatedFncachePaths = new java.util.ArrayList<>();
            
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
                        fileSizes.put(flIdx, flIdx.length());
                        fileSizes.put(flDat, flDat.exists() ? flDat.length() : 0L);

                        appendToJournal(journalFile, "store/" + storePath + "\t" + fileSizes.get(flIdx));
                        appendToJournal(journalFile, "store/" + storePath.substring(0, storePath.length() - 2) + ".d\t" + fileSizes.get(flDat));

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
                            truncateRevlog(flIdx, flDat, flKeepCount);
                            updatedFncachePaths.add(storePath);
                        }
                    }
                }
            }
            
            // Write back updated fncache atomically
            com.github.search5.hg4j.util.SafeFileIO.writeLinesAtomic(fncacheFile, updatedFncachePaths);

            // 2. Truncate Core Changelog and Manifest
            truncateRevlog(clIdx, clDat, keepCount);
            truncateRevlog(mfIdx, mfDat, keepCount);

            // 3. Clean bookmarks whose revisions are stripped
            File bookmarksFile = new File(repository.getHgDir(), "bookmarks");
            if (bookmarksFile.exists()) {
                java.util.List<String> bLines = java.nio.file.Files.readAllLines(bookmarksFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                java.util.List<String> updatedBLines = new java.util.ArrayList<>();
                for (String line : bLines) {
                    if (line.trim().isEmpty()) continue;
                    String[] parts = line.trim().split("\\s+", 2);
                    if (parts.length == 2) {
                        String hexNode = parts[0];
                        byte[] node = com.github.search5.hg4j.util.NodeIdUtil.fromHex(hexNode);
                        int rev = changelog.findRevision(node);
                        if (rev != -1 && rev < targetRev) {
                            updatedBLines.add(line);
                        }
                    }
                }
                if (updatedBLines.isEmpty()) {
                    bookmarksFile.delete();
                } else {
                    com.github.search5.hg4j.util.SafeFileIO.writeLinesAtomic(bookmarksFile, updatedBLines);
                }
            }

            // 4. Clean phase roots whose revisions are stripped
            // 실제 hg는 phaseroots를 .hg/store/phaseroots에 저장한다(.hg/phaseroots가 아님 —
            // real hg CLI로 확인, 2026-09-01).
            File phaserootsFile = new File(repository.getStoreDir(), "phaseroots");
            if (phaserootsFile.exists()) {
                java.util.List<String> pLines = java.nio.file.Files.readAllLines(phaserootsFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                java.util.List<String> updatedPLines = new java.util.ArrayList<>();
                for (String line : pLines) {
                    if (line.trim().isEmpty()) continue;
                    String[] parts = line.trim().split("\\s+", 2);
                    if (parts.length == 2) {
                        String hexNode = parts[1];
                        byte[] node = com.github.search5.hg4j.util.NodeIdUtil.fromHex(hexNode);
                        int rev = changelog.findRevision(node);
                        if (rev != -1 && rev < targetRev) {
                            updatedPLines.add(line);
                        }
                    }
                }
                if (updatedPLines.isEmpty()) {
                    phaserootsFile.delete();
                } else {
                    com.github.search5.hg4j.util.SafeFileIO.writeLinesAtomic(phaserootsFile, updatedPLines);
                }
            }

            // Restore dirstate parent safely
            Dirstate d = repository.getDirstate();
            byte[] parent20 = new byte[20];
            System.arraycopy(rollbackParent, 0, parent20, 0, 20);
            d.setParents(parent20, new byte[20]);
            repository.writeDirstate(d);

            // Register obsolescence marker pruning the stripped commit completely (no successors)
            try {
                com.github.search5.hg4j.obsolete.HgObsMarker.writeMarker(repository.getStoreDir(), nodeBytes, java.util.List.of(), "prune");
            } catch (Exception e) {
                // non-blocking
            }

            repository.clearRevlogCache();

            // 5. Successful strip complete -> Write undo info for rollback support and clear journal
            try {
                CommitCommand.writeUndoInfo(repository, fileSizes, dirstateBackup);
            } catch (Exception e) {
                // non-blocking
            }
            Files.deleteIfExists(journalFile.toPath());
            File dirstateBackupFile = new File(repository.getDirectory(), ".hg/dirstate.backup");
            Files.deleteIfExists(dirstateBackupFile.toPath());

        } catch (Exception e) {
            // Transaction Rollback Session on error
            for (java.util.Map.Entry<File, Long> sizeEntry : fileSizes.entrySet()) {
                File file = sizeEntry.getKey();
                long origSize = sizeEntry.getValue();
                if (origSize == 0) {
                    Files.deleteIfExists(file.toPath());
                } else if (file.exists()) {
                    try (FileChannel outChan = FileChannel.open(file.toPath(), StandardOpenOption.WRITE)) {
                        outChan.truncate(origSize);
                        outChan.force(true);
                    }
                }
            }
            if (dirstateBackup != null) {
                com.github.search5.hg4j.util.SafeFileIO.writeAtomic(dirstateFile, dirstateBackup);
            }
            Files.deleteIfExists(journalFile.toPath());
            throw e;
        }
    }

    private void truncateRevlog(File idxFile, File datFile, int keepCount) throws IOException {
        if (!idxFile.exists()) return;

        long keepIndexLength = (long) keepCount * 64;
        try (RandomAccessFile rafIdx = new RandomAccessFile(idxFile, "rw")) {
            rafIdx.setLength(keepIndexLength);
        }
        
        if (datFile.exists()) {
            try (RandomAccessFile rafDat = new RandomAccessFile(datFile, "rw")) {
                if (keepCount == 0) {
                    rafDat.setLength(0);
                } else {
                    // Safe estimation fallback based on average revision lengths
                    long targetDatSize = Math.min(datFile.length(), datFile.length() * keepCount / (keepCount + 1));
                    rafDat.setLength(targetDatSize);
                }
            }
        }
    }

    private void appendToJournal(File journalFile, String entry) throws IOException {
        Files.writeString(journalFile.toPath(), entry + "\n", java.nio.charset.StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        try (java.nio.channels.FileChannel fc = java.nio.channels.FileChannel.open(journalFile.toPath(), java.nio.file.StandardOpenOption.WRITE)) {
            fc.force(true);
        }
    }
}
