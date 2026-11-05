package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgValidationException;

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
     * Renames the single file set by {@link #setSource} to {@link #setTarget}, with the checks of {@code hg rename}: it runs the same
     * engine as {@link #callPatterns()} on the two paths.
     * <p>The source must be tracked and not marked for removal, and an existing target is refused unless {@link #setForce} is set
     * (real hg: {@code not copying - file is not managed}, {@code not copying - file has been marked for remove},
     * {@code not overwriting - file exists}); a refusal throws {@link HgValidationException} with the warning real hg prints.
     * A source that was only added, never committed, is moved without recording a copy, as {@code hg rename} does. Under the working
     * copy lock the file is moved on disk (creating parent directories), the source is marked removed ({@code r}) and the target added
     * ({@code a}), and the copy source is recorded. The dirstate is changed inside a {@code changing_files} context: it is written
     * once at the end and not at all if anything fails (the moved file is not moved back).
     *
     * @throws IllegalStateException if the source or target was not set
     * @throws io.github.search5.hg4j.errors.HgRepositoryNotFoundException (an {@link IOException}) if the source
     *         does not exist
     * @throws HgValidationException if real hg would refuse the rename (source not tracked or marked for removal, target exists)
     * @throws IOException if moving the file or writing the dirstate fails
     * @throws HgLockException if the working copy lock cannot be acquired
     */
    public void call() throws IOException, HgLockException {
        if (sourcePath == null || targetPath == null) {
            throw new IllegalStateException("Source and target paths must be specified.");
        }
        File srcFile = new File(repository.getDirectory(), sourcePath);
        // A symlink is valid to rename even when its target is missing (dangling) -- File#exists() follows the link.
        if (!Files.isSymbolicLink(srcFile.toPath()) && !srcFile.exists()) {
            throw new HgRepositoryNotFoundException("Source file does not exist: " + sourcePath);
        }
        CopyEngine.Result result;
        try {
            result = CopyEngine.execute(repository, true, java.util.List.of(sourcePath, targetPath), false, force, false,
                    false, java.util.List.of(), java.util.List.of(), "", null);
        } catch (CopyEngine.Refused refused) {
            // the warnings real hg prints for the skipped file come first ("x: not copying - file is not managed")
            java.util.List<String> lines = new java.util.ArrayList<>(refused.warnings());
            lines.add(refused.getMessage());
            throw new HgValidationException(String.join("\n", lines));
        }
        if (result.failed()) {
            throw new HgValidationException(String.join("\n", result.warnings()));
        }
    }

}
