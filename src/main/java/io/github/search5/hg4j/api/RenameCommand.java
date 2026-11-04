package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.errors.HgLockException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import io.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import io.github.search5.hg4j.util.SafeFileIO;
import java.nio.file.StandardOpenOption;

/**
 * Porcelain command to move or rename a file or directory inside the repository.
 * Correctly registers SCM copy metadata in the dirstate copyMap.
 */
public final class RenameCommand {
    private final HgRepository repository;
    private String sourcePath;
    private String targetPath;

    /**
     * Creates the command.
     * @param repository the repository to rename in; must not be {@code null}
     * @throws IllegalArgumentException if {@code repository} is {@code null}
     */
    public RenameCommand(HgRepository repository) {
        if (repository == null) {
            throw new IllegalArgumentException("Repository cannot be null");
        }
        this.repository = repository;
    }

    /**
     * Sets the source path for {@link #call()}.
     * @param sourcePath the repository-relative path with {@code /} separators of an existing file or symbolic link
     *         (a dangling symbolic link is allowed); must be set before {@link #call()}
     * @return {@code this}
     */
    public RenameCommand setSource(String sourcePath) {
        this.sourcePath = sourcePath;
        return this;
    }

    /**
     * Sets the destination path for {@link #call()}.
     * @param targetPath the repository-relative path with {@code /} separators; must be set before {@link #call()}
     * @return {@code this}
     */
    public RenameCommand setTarget(String targetPath) {
        this.targetPath = targetPath;
        return this;
    }

    private final java.util.List<String> patterns = new java.util.ArrayList<>();
    private boolean force;
    private boolean after;
    private boolean dryRun;
    private boolean forget;
    private final java.util.List<String> include = new java.util.ArrayList<>();
    private final java.util.List<String> exclude = new java.util.ArrayList<>();
    private String cwd = "";
    private String atRev;

    /**
     * The sources followed by the destination, as on the command line ({@code hg rename SOURCE... DEST}): each is a pattern
     * ({@code kind:pattern}, plain names are relative to {@link #setCwd}), the destination must be an existing directory when there
     * are several sources. Used by {@link #callPatterns()}.
     */
    public RenameCommand setPatterns(String... sourcesAndDestination) {
        patterns.clear();
        patterns.addAll(java.util.Arrays.asList(sourcesAndDestination));
        return this;
    }

    /** {@code -f}/{@code --force}: overwrite a destination that exists (only for {@link #callPatterns()}). */
    public RenameCommand setForce(boolean force) {
        this.force = force;
        return this;
    }

    /** {@code --after}: record a rename that already happened on disk. */
    public RenameCommand setAfter(boolean after) {
        this.after = after;
        return this;
    }

    /** {@code --dry-run}: only report what would be done. */
    public RenameCommand setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
        return this;
    }

    /** {@code --forget}: unmark the destinations as copies (the files stay). */
    public RenameCommand setForget(boolean forget) {
        this.forget = forget;
        return this;
    }

    /**
     * {@code --at-rev REV}: the rename is recorded in (or, with {@link #setForget}, removed from) the changeset REV, which is replaced
     * by a changed copy of itself. Needs {@link #setAfter} or {@link #setForget}; REV must be a draft without children.
     */
    public RenameCommand setAtRev(String revision) {
        this.atRev = revision;
        return this;
    }

    /** {@code -I}. */
    public RenameCommand setInclude(String... patterns) {
        include.clear();
        include.addAll(java.util.Arrays.asList(patterns));
        return this;
    }

    /** {@code -X}. */
    public RenameCommand setExclude(String... patterns) {
        exclude.clear();
        exclude.addAll(java.util.Arrays.asList(patterns));
        return this;
    }

    /** The current directory relative to the repository root, against which relative patterns are read. */
    public RenameCommand setCwd(String cwd) {
        this.cwd = cwd == null ? "" : cwd;
        return this;
    }

    /** What {@code hg rename} reports: the warnings and status lines real hg prints, and whether a file was not handled (exit status 1). */
    public record PatternResult(boolean failed, java.util.List<String> warnings, java.util.List<String> status) {
    }

    /**
     * {@code hg rename [OPTION]... SOURCE... DEST} with all of its options. Real hg does not fail on a file it cannot handle: it
     * prints a warning and goes on, and the result says so ({@link PatternResult#failed()}).
     */
    public PatternResult callPatterns() throws IOException, HgLockException {
        CopyEngine.Result result = CopyEngine.execute(repository, true, patterns, after, force, dryRun, forget, include, exclude, cwd, atRev);
        return new PatternResult(result.failed(), result.warnings(), result.status());
    }

    /**
     * Renames the single file set by {@link #setSource} to {@link #setTarget}.
     * <p>
     * Under the working copy lock, moves the file on disk (replacing an existing target and creating parent
     * directories), marks the source removed ({@code r}) and the target added ({@code a}) in the dirstate and records
     * the copy source. The dirstate is changed inside a {@code changing_files} context: it is written once at the end and not at all if anything fails (the moved file is not moved back).
     * @throws IllegalStateException if the source or target was not set
     * @throws io.github.search5.hg4j.errors.HgRepositoryNotFoundException (an {@link IOException}) if the source
     *         does not exist
     * @throws IOException if moving the file or writing the dirstate fails
     * @throws HgLockException if the working copy lock cannot be acquired
     */
    public void call() throws IOException, HgLockException {
        if (sourcePath == null || targetPath == null) {
            throw new IllegalStateException("Source and target paths must be specified.");
        }

        // Like real hg: the locks are taken right after the arguments are checked, before any repository state is read, and released when the command ends.
        try (HgLock lock = repository.lockWorkingCopy()) {
            File srcFile = new File(repository.getDirectory(), sourcePath);
            File destFile = new File(repository.getDirectory(), targetPath);

            // A symlink is valid to rename even when its target is missing (dangling) -- File#exists()
            // follows the link and returns false in that case, matching the same lstat-aware
            // convention AddCommand/CommitCommand already use elsewhere.
            if (!Files.isSymbolicLink(srcFile.toPath()) && !srcFile.exists()) {
                throw new HgRepositoryNotFoundException("Source file does not exist: " + sourcePath);
            }

            // `dirstate.changing_files`: the dirstate is written once, when the context ends normally; a failure writes nothing
            repository.changingFiles(() -> {
                // 1. Perform physical move
                destFile.getParentFile().mkdirs();
                Files.move(srcFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

                // 2. Update dirstate copy map and state entries
                Dirstate dirstate = repository.getDirstate();

                // Source: marked as removed ('r')
                dirstate.addEntry(sourcePath, new Dirstate.Entry('r', 0, 0, 0));

                // Target: marked as added ('a'). A symlink's dirstate mode/size must reflect the
                // link itself (lstat semantics) -- File#canExecute()/length() both follow the
                // link to whatever it points at, matching what AddCommand/CopyCommand already do.
                boolean destIsSymlink = Files.isSymbolicLink(destFile.toPath());
                int mode = destIsSymlink ? 0120000 : (destFile.canExecute() ? 0755 : 0644);
                int size = destIsSymlink
                        ? Files.readSymbolicLink(destFile.toPath()).toString().getBytes(StandardCharsets.UTF_8).length
                        : (int) destFile.length();
                long time = SafeFileIO.lastModifiedSeconds(destFile);
                dirstate.addEntry(targetPath, new Dirstate.Entry('a', mode, size, time));

                // Register Copy-rename linkage in copyMap
                dirstate.addCopy(targetPath, sourcePath);

                repository.writeDirstate(dirstate);
                return null;
            });
        }
    }

}
