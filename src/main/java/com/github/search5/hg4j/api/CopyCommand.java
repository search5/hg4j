package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.errors.HgLockException;
import com.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import com.github.search5.hg4j.errors.HgValidationException;
import com.github.search5.hg4j.lib.HgLock;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.util.SafeFileIO;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Porcelain command implementing {@code hg copy}: duplicates a tracked file to a new path
 * while leaving the original untouched and still tracked at its original path, and records
 * copy-source metadata in the dirstate copyMap so history/blame (`hg annotate`, `hg log
 * --follow`) on the destination follows through to the source.
 * <p>
 * This is the sibling of {@link RenameCommand} ({@code hg rename}/{@code hg mv}), which
 * additionally deletes the source and marks it removed. Real {@code hg copy} semantics were
 * verified against the live {@code hg} CLI (v7.2) on scratch repositories:
 * <ul>
 *   <li>The source file is left in place, on disk and in the dirstate, completely unchanged.</li>
 *   <li>The destination is added to the dirstate as a new file (state {@code 'a'}) and the
 *       copyMap records {@code destination -> source}.</li>
 *   <li>Copying onto a path that already exists on disk (tracked or not) is refused unless
 *       {@link #setForce(boolean)} is set, matching {@code hg copy}'s
 *       "not overwriting - file exists" refusal.</li>
 *   <li>Copying an untracked source is refused ({@code hg copy}: "not copying - file is not
 *       managed").</li>
 *   <li>Copy-of-a-copy chains resolve to the immediate source's own recorded copy source, but
 *       ONLY while that immediate source is itself still an uncommitted addition (dirstate
 *       state {@code 'a'}). Verified live: {@code a -> commit -> copy a b -> copy b c} records
 *       c's source as {@code b} (the chain "resets" once a step is committed), while
 *       {@code a -> commit -> copy a b (uncommitted) -> copy b c (uncommitted)} records c's
 *       source as {@code a} (the original, since b was never committed in between). This
 *       mirrors {@code copy_dirstate_pending}/{@code copy} in Mercurial's
 *       {@code scmutil.py}, whose dirstate-based chain lookup naturally collapses once a step
 *       is committed there too.</li>
 *   <li>Copying a freshly {@code hg add}-ed file that was never itself a copy stores NO copy
 *       metadata for the destination at all (real hg prints "has not been committed yet, so
 *       no copy data will be stored" and just adds the destination as a brand new file).</li>
 *   <li>Symlinks are copied as symlinks (the link itself, not its target's content) - matching
 *       {@code os.lstat}/{@code os.readlink} semantics used elsewhere in this codebase (see
 *       {@link AddCommand}).</li>
 * </ul>
 */
public final class CopyCommand {
    private final HgRepository repository;
    private String sourcePath;
    private String destinationPath;
    private boolean force;

    public CopyCommand(HgRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("Repository cannot be null");
        }
        this.repository = repository;
    }

    public CopyCommand setSource(String sourcePath) {
        this.sourcePath = sourcePath;
        return this;
    }

    public CopyCommand setDestination(String destinationPath) {
        this.destinationPath = destinationPath;
        return this;
    }

    /**
     * When {@code true}, an existing file at the destination path is overwritten, mirroring
     * {@code hg copy --force}. Defaults to {@code false}, matching {@code hg copy}'s default
     * refusal to clobber an existing file.
     */
    public CopyCommand setForce(boolean force) {
        this.force = force;
        return this;
    }

    /**
     * Executes the file copy and SCM copy history registration.
     *
     * @throws IOException if the physical file copy or dirstate write fails, or if the source
     *                      is missing/untracked, or the destination already exists without
     *                      {@link #setForce(boolean)}
     */
    public void call() throws IOException, HgLockException {
        if (sourcePath == null || destinationPath == null) {
            throw new IllegalStateException("Source and destination paths must be specified.");
        }

        File srcFile = new File(repository.getDirectory(), sourcePath);
        File destFile = new File(repository.getDirectory(), destinationPath);

        if (!existsOnDisk(srcFile)) {
            throw new HgRepositoryNotFoundException("Source file does not exist: " + sourcePath);
        }

        if (existsOnDisk(destFile) && !force) {
            throw new HgValidationException(
                    "Destination file already exists: " + destinationPath + " (use setForce(true) to overwrite)");
        }

        // 1. Lock repository for working directory changes
        File dirstateFile = new File(repository.getDirectory(), ".hg/dirstate");
        byte[] dirstateBackup = dirstateFile.exists() ? Files.readAllBytes(dirstateFile.toPath()) : null;
        File journalFile = new File(repository.getStoreDir(), "journal");

        try (HgLock lock = repository.lockWorkingCopy()) {
            // Create physical journal and backups for Crash Resilience
            Files.deleteIfExists(journalFile.toPath());
            if (dirstateFile.exists()) {
                File dirstateBackupFile = new File(repository.getDirectory(), ".hg/dirstate.backup");
                Files.copy(dirstateFile.toPath(), dirstateBackupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                appendToJournal(journalFile, "dirstate");
            }

            try {
                Dirstate dirstate = repository.getDirstate();

                // 2. Source must be tracked (and not already scheduled for removal) to be copyable.
                Dirstate.Entry sourceEntry = dirstate.getEntries().get(sourcePath);
                if (sourceEntry == null || sourceEntry.getState() == 'r') {
                    throw new HgValidationException("Source file is not tracked: " + sourcePath);
                }

                // 3. Resolve the copy-source chain the way real hg's dirstate does: only chase
                // through to an earlier recorded copy source while the immediate source is
                // itself still an uncommitted addition (state 'a'). Once a step is committed,
                // its state becomes 'n'/'m' and the chain naturally stops there.
                String originalSource = sourcePath;
                if (sourceEntry.getState() == 'a') {
                    String chained = dirstate.getCopyMap().get(sourcePath);
                    if (chained != null) {
                        originalSource = chained;
                    }
                }
                // Real hg stores no copy metadata at all when the source is a freshly added
                // file that was never itself a copy of anything (origsrc == src and src is
                // still 'added').
                boolean storeCopyMetadata = !(originalSource.equals(sourcePath) && sourceEntry.getState() == 'a');

                // 4. Perform physical copy. NOFOLLOW_LINKS ensures a symlink source is copied
                // as a new symlink (the link itself), not dereferenced into its target's content.
                destFile.getParentFile().mkdirs();
                CopyOption[] options = force
                        ? new CopyOption[]{StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS}
                        : new CopyOption[]{LinkOption.NOFOLLOW_LINKS};
                Files.copy(srcFile.toPath(), destFile.toPath(), options);

                // 5. Register destination as newly added, mirroring AddCommand's lstat-aware
                // handling of symlinks (tracked size is the target path string length, not the
                // dereferenced file length).
                boolean isSymlink = Files.isSymbolicLink(destFile.toPath());
                boolean executable = !isSymlink && destFile.canExecute();
                int mode = executable ? 0755 : 0644;
                int size = isSymlink
                        ? Files.readSymbolicLink(destFile.toPath()).toString().getBytes(StandardCharsets.UTF_8).length
                        : (int) destFile.length();
                long time = SafeFileIO.lastModifiedSeconds(destFile);
                dirstate.addEntry(destinationPath, new Dirstate.Entry('a', mode, size, time));

                if (storeCopyMetadata) {
                    dirstate.addCopy(destinationPath, originalSource);
                }

                repository.writeDirstate(dirstate);

                // Clean up crash backups on success
                Files.deleteIfExists(journalFile.toPath());
                File dirstateBackupFile = new File(repository.getDirectory(), ".hg/dirstate.backup");
                Files.deleteIfExists(dirstateBackupFile.toPath());
            } catch (Exception e) {
                // Restore dirstate on failure
                if (dirstateBackup != null) {
                    try {
                        SafeFileIO.writeAtomic(dirstateFile, dirstateBackup);
                    } catch (Exception ignored) {
                    }
                }
                try {
                    Files.deleteIfExists(journalFile.toPath());
                } catch (Exception ignored) {
                }
                throw e;
            }
        }
    }

    /**
     * Whether {@code file} exists on disk, including a dangling symlink (whose target is
     * missing, so plain {@link File#exists()} would return {@code false} for it).
     */
    private static boolean existsOnDisk(File file) {
        return file.exists() || Files.isSymbolicLink(file.toPath());
    }

    private void appendToJournal(File journal, String entry) throws IOException {
        Files.write(journal.toPath(), (entry + "\n").getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
