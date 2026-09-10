package io.github.search5.hg4j.errors;

import java.util.List;

/**
 * Exception thrown when unresolved conflicts occur during a merge3 operation.
 *
 * @apiNote Thrown by {@link io.github.search5.hg4j.api.GraftCommand}, {@link
 *     io.github.search5.hg4j.api.BackoutCommand}, and {@link io.github.search5.hg4j.api.RebaseCommand}
 *     when their internal cherry-pick/replay merge leaves one or more files unresolved; {@link
 *     io.github.search5.hg4j.api.ShelveCommand}'s unshelve path also declares and catches it
 *     internally to convert an unshelve conflict into paused state. None of these commands
 *     commit when this is thrown — callers should resolve the reported paths (see {@link
 *     #getConflictPaths()}) with {@code ResolveCommand} and then call the command's
 *     {@code continue}-style method, or abort.
 */
public class HgMergeConflictException extends HgException {
    private static final long serialVersionUID = 1L;

    /** File path where the conflict occurred (the first one, if there were several). */
    private final String conflictPath;
    /** Every file path left unresolved by the operation that raised this exception. */
    private final List<String> conflictPaths;

    /**
     * Creates a new exception for a single conflicting file.
     *
     * @param conflictPath File path where the conflict occurred
     * @param message      Description of the conflict
     */
    public HgMergeConflictException(String conflictPath, String message) {
        super("Merge conflict in '" + conflictPath + "': " + message);
        this.conflictPath = conflictPath;
        this.conflictPaths = List.of(conflictPath);
    }

    /**
     * Creates a new exception for a single conflicting file, wrapping an underlying cause.
     *
     * @param conflictPath File path where the conflict occurred
     * @param message      Description of the conflict
     * @param cause        the underlying exception that caused the conflict to be raised
     */
    public HgMergeConflictException(String conflictPath, String message, Throwable cause) {
        super("Merge conflict in '" + conflictPath + "': " + message, cause);
        this.conflictPath = conflictPath;
        this.conflictPaths = List.of(conflictPath);
    }

    /**
     * Creates a new exception for one or more conflicting files.
     *
     * @param conflictPaths One or more file paths where conflicts occurred (e.g. every file left
     *                      unresolved by a single paused {@code hg rebase} revision)
     * @param message       Description of the conflict
     */
    public HgMergeConflictException(List<String> conflictPaths, String message) {
        super("Merge conflict in " + conflictPaths + ": " + message);
        this.conflictPaths = List.copyOf(conflictPaths);
        this.conflictPath = conflictPaths.isEmpty() ? null : conflictPaths.get(0);
    }

    /**
     * Returns the file path where the conflict occurred (the first one, if there were several).
     *
     * @return the conflicting file path, or {@code null} if this exception was created with an empty path list
     */
    public String getConflictPath() {
        return conflictPath;
    }

    /**
     * Returns every file path left unresolved by the operation that raised this exception.
     *
     * @return an immutable list of the conflicting file paths
     */
    public List<String> getConflictPaths() {
        return conflictPaths;
    }
}
