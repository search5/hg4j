package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.lib.HgLock;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.errors.HgLockException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import com.github.search5.hg4j.errors.HgValidationException;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.util.SafeFileIO;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Porcelain command to untrack and remove files from the working directory and staging area.
 */
public class RemoveCommand {

    private final HgRepository repository;
    private String file;
    private boolean force = false;

    public RemoveCommand(HgRepository repository) {
        this.repository = repository;
    }

    public RemoveCommand setFile(String file) {
        this.file = file;
        return this;
    }

    public RemoveCommand setForce(boolean force) {
        this.force = force;
        return this;
    }

    private void appendToJournal(File journal, String entry) throws IOException {
        Files.write(journal.toPath(), (entry + "\n").getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public boolean call() throws IOException, HgLockException {
        if (file == null || file.isEmpty()) {
            throw new IllegalStateException("File path must be specified.");
        }

        File dirstateFile = new File(repository.getDirectory(), ".hg/dirstate");
        byte[] dirstateBackup = dirstateFile.exists() ? Files.readAllBytes(dirstateFile.toPath()) : null;
        File journalFile = new File(repository.getStoreDir(), "journal");

        try (HgLock wlock = repository.lockWorkingCopy();
             HgLock storeLock = repository.lockStore()) {

            // Create physical journal and backups for Crash Resilience
            Files.deleteIfExists(journalFile.toPath());
            if (dirstateFile.exists()) {
                File dirstateBackupFile = new File(repository.getDirectory(), ".hg/dirstate.backup");
                Files.copy(dirstateFile.toPath(), dirstateBackupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                appendToJournal(journalFile, "dirstate");
            }

            try {
                Dirstate dirstate = repository.getDirstate();
                Dirstate.Entry entry = dirstate.getEntries().get(file);

                if (entry == null) {
                    throw new HgValidationException("File is not tracked: " + file);
                }

                if (!force) {
                    char state = entry.getState();
                    if (state == 'a') {
                        throw new HgValidationException("File has uncommitted changes (added): " + file + ". Use force to remove.");
                    } else if (state == 'n' || state == 'm') {
                        File diskFile = new File(repository.getDirectory(), file);
                        // A symlink's own lstat size/mtime/content, not whatever it points at --
                        // File#isFile()/length()/lastModified() all follow the link, which
                        // compared the TARGET's size against the symlink's own tracked size and
                        // spuriously flagged an untouched symlink as modified whenever its target
                        // happened to be a different length (matches AddCommand/StatusCommand's
                        // already-established lstat-aware convention).
                        boolean isSymlink = Files.isSymbolicLink(diskFile.toPath());
                        if (isSymlink || (diskFile.exists() && diskFile.isFile())) {
                            long diskSize = isSymlink
                                    ? Files.readSymbolicLink(diskFile.toPath()).toString().getBytes(StandardCharsets.UTF_8).length
                                    : diskFile.length();
                            long diskTime = SafeFileIO.lastModifiedSeconds(diskFile);
                            boolean isDirty = entry.getSize() != diskSize || entry.getTime() != diskTime;
                            if (!isDirty) {
                                // Racy-hg check: content level comparison
                                File flIdx = CommitCommand.getFilelogIndex(repository.getStoreDir(), file);
                                File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
                                if (flIdx.exists()) {
                                    try {
                                        Revlog filelog = repository.getRevlog(flIdx, flDat);
                                        if (filelog.getRevisionCount() > 0) {
                                            byte[] fileContent = isSymlink
                                                    ? Files.readSymbolicLink(diskFile.toPath()).toString().getBytes(StandardCharsets.UTF_8)
                                                    : Files.readAllBytes(diskFile.toPath());
                                            byte[] lastContent = filelog.getRevisionContent(filelog.getRevisionCount() - 1);
                                            if (!Arrays.equals(fileContent, lastContent)) {
                                                isDirty = true;
                                            }
                                        }
                                    } catch (Exception ignored) {}
                                }
                            }
                            if (isDirty) {
                                throw new HgValidationException("File has uncommitted changes (modified): " + file + ". Use force to remove.");
                            }
                        }
                    }
                }

                File diskFile = new File(repository.getDirectory(), file);
                // File#exists() follows a symlink and returns false for a dangling target, which
                // silently skipped deleting the link itself -- it was marked removed in the
                // dirstate but physically left on disk.
                if (Files.isSymbolicLink(diskFile.toPath()) || (diskFile.exists() && diskFile.isFile())) {
                    Files.delete(diskFile.toPath());
                }

                if (entry.getState() == 'a') {
                    // If it was newly added, we just untrack it completely
                    dirstate.removeEntry(file);
                } else {
                    // Mark as removed for the next commit
                    dirstate.addEntry(file, new Dirstate.Entry('r', 0, 0, 0));
                }

                repository.writeDirstate(dirstate);

                // Clean up crash backups on success
                Files.deleteIfExists(journalFile.toPath());
                File dirstateBackupFile = new File(repository.getDirectory(), ".hg/dirstate.backup");
                Files.deleteIfExists(dirstateBackupFile.toPath());

                return true;
            } catch (Exception e) {
                // Restore dirstate on failure
                if (dirstateBackup != null) {
                    try {
                        SafeFileIO.writeAtomic(dirstateFile, dirstateBackup);
                    } catch (Exception ignored) {}
                }
                try {
                    Files.deleteIfExists(journalFile.toPath());
                } catch (Exception ignored) {}
                throw e;
            }
        }
    }
}
