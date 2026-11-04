package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.errors.HgLockException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.SafeFileIO;
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


    private final FileSelection selection = new FileSelection();
    private final java.util.List<String> warnings = new java.util.ArrayList<>();

    /** The files to work on, as patterns ({@code kind:pattern}, {@code set:EXPRESSION}); a bare name is relative to the current directory. */
    public RemoveCommand setPatterns(String... patterns) {
        selection.patterns(patterns);
        return this;
    }

    /** {@code -I}: only files matching these patterns (globs by default). */
    public RemoveCommand setInclude(String... patterns) {
        selection.include(patterns);
        return this;
    }

    /** {@code -X}: not the files matching these patterns (globs by default). */
    public RemoveCommand setExclude(String... patterns) {
        selection.exclude(patterns);
        return this;
    }

    /** The current directory relative to the repository root, against which relative patterns are read. */
    public RemoveCommand setCwd(String cwd) {
        selection.cwd(cwd);
        return this;
    }

    /** What the last {@link #callSelected()} left undone, in Mercurial's words. */
    public java.util.List<String> getWarnings() {
        return warnings;
    }

    /**
     * {@code hg remove [FILE]...}: removes the tracked files the patterns select, from the disk and from tracking. A file that
     * cannot be removed safely (marked for add, modified) is left and reported in {@link #getWarnings()} unless forced; the
     * others are removed. Needs at least one pattern.
     *
     * @return the removed files
     */
    public java.util.List<String> callSelected() throws IOException, HgLockException {
        if (selection.patternCount() == 0) {
            throw new HgValidationException("no files specified");
        }
        warnings.clear();
        io.github.search5.hg4j.match.Matcher matcher = selection.matcher(repository, null);
        Dirstate dirstate = repository.getDirstate();
        java.util.List<String> candidates = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, Dirstate.Entry> e : new java.util.TreeMap<>(dirstate.getEntries()).entrySet()) {
            if (e.getValue().getState() != 'r' && matcher.matches(e.getKey())) {
                candidates.add(e.getKey());
            }
        }
        java.util.List<String> removed = new java.util.ArrayList<>();
        for (String path : candidates) {
            try {
                if (new RemoveCommand(repository).setFile(path).setForce(force).call()) {
                    removed.add(path);
                }
            } catch (HgValidationException refused) {
                warnings.add("not removing " + path + ": " + refused.getMessage());
            }
        }
        return removed;
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
                            // An entry whose cached stat is ambiguous (real hg's own "unset"
                            // sentinel -- see Dirstate.Entry#isStatAmbiguous(), most commonly hit
                            // when the file was committed within the same wall-clock second as
                            // the dirstate write) can never be trusted via a raw size/mtime
                            // comparison: its sentinel size (-1) never equals a real on-disk size,
                            // which previously made EVERY such entry look permanently "modified"
                            // and made `remove` (without --force) wrongly refuse a genuinely
                            // untouched file -- confirmed live against a real hg-authored
                            // dirstate produced by an add+commit that landed in the same second.
                            boolean isDirty = !entry.isStatAmbiguous()
                                    && (entry.getSize() != diskSize || entry.getTime() != diskTime);
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
                                            byte[] lastContent = io.github.search5.hg4j.lfs.HgLfsFactory.getInstance().readContent(repository, file, filelog, filelog.getRevisionCount() - 1);
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
                // An added file that is removed with force is only forgotten: real hg leaves the file on the disk (it shows as
                // unknown afterwards); everything else is deleted from the disk too.
                if (entry.getState() != 'a'
                        && (Files.isSymbolicLink(diskFile.toPath()) || (diskFile.exists() && diskFile.isFile()))) {
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
