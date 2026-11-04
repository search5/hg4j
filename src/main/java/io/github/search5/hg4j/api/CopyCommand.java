package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.SafeFileIO;

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
    private final java.util.List<String> patterns = new java.util.ArrayList<>();
    private boolean after;
    private boolean dryRun;
    private boolean forget;
    private final java.util.List<String> include = new java.util.ArrayList<>();
    private final java.util.List<String> exclude = new java.util.ArrayList<>();
    private String cwd = "";
    private String atRev;

    /**
     * The sources followed by the destination, as on the command line ({@code hg copy SOURCE... DEST}): each is a pattern
     * ({@code kind:pattern}, plain names are relative to {@link #setCwd}), the destination must be an existing directory when there
     * are several sources. Used by {@link #callPatterns()}.
     */
    public CopyCommand setPatterns(String... sourcesAndDestination) {
        patterns.clear();
        patterns.addAll(java.util.Arrays.asList(sourcesAndDestination));
        return this;
    }

    /** {@code --after}: record a copy that already happened on disk. */
    public CopyCommand setAfter(boolean after) {
        this.after = after;
        return this;
    }

    /** {@code --dry-run}: only report what would be done. */
    public CopyCommand setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
        return this;
    }

    /** {@code --forget}: unmark the destinations as copies (the files stay). */
    public CopyCommand setForget(boolean forget) {
        this.forget = forget;
        return this;
    }

    /**
     * {@code --at-rev REV}: the copy is recorded in (or, with {@link #setForget}, removed from) the changeset REV, which is replaced
     * by a changed copy of itself. Needs {@link #setAfter} or {@link #setForget}; REV must be a draft without children.
     */
    public CopyCommand setAtRev(String revision) {
        this.atRev = revision;
        return this;
    }

    /** {@code -I}. */
    public CopyCommand setInclude(String... patterns) {
        include.clear();
        include.addAll(java.util.Arrays.asList(patterns));
        return this;
    }

    /** {@code -X}. */
    public CopyCommand setExclude(String... patterns) {
        exclude.clear();
        exclude.addAll(java.util.Arrays.asList(patterns));
        return this;
    }

    /** The current directory relative to the repository root, against which relative patterns are read. */
    public CopyCommand setCwd(String cwd) {
        this.cwd = cwd == null ? "" : cwd;
        return this;
    }

    /** What {@code hg copy} reports: the warnings and status lines real hg prints, and whether a file was not handled (exit status 1). */
    public record PatternResult(boolean failed, java.util.List<String> warnings, java.util.List<String> status) {
    }

    /**
     * {@code hg copy [OPTION]... SOURCE... DEST} with all of its options. Real hg does not fail on a file it cannot handle: it
     * prints a warning and goes on, and the result says so ({@link PatternResult#failed()}).
     */
    public PatternResult callPatterns() throws IOException, HgLockException {
        CopyEngine.Result result = CopyEngine.execute(repository, false, patterns, after, this.force, dryRun, forget, include, exclude, cwd, atRev);
        return new PatternResult(result.failed(), result.warnings(), result.status());
    }

    public void call() throws IOException, HgLockException {
        if (sourcePath == null || destinationPath == null) {
            throw new IllegalStateException("Source and destination paths must be specified.");
        }

        // Like real hg: the locks are taken right after the arguments are checked, before any repository state is read, and released when the command ends.
        try (HgLock lock = repository.lockWorkingCopy()) {
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
            byte[] dirstateBackup = null; // read once the lock is held

            {
                dirstateBackup = dirstateFile.exists() ? Files.readAllBytes(dirstateFile.toPath()) : null;

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
                } catch (Exception e) {
                    // Restore dirstate on failure
                    if (dirstateBackup != null) {
                        try {
                            SafeFileIO.writeAtomic(dirstateFile, dirstateBackup);
                        } catch (Exception ignored) {
                        }
                    }
                    try {
                    } catch (Exception ignored) {
                    }
                    throw e;
                }
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

}
