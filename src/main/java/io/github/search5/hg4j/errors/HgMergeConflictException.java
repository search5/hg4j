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

    private final String conflictPath;
    private final List<String> conflictPaths;

    /**
     * @param conflictPath File path where the conflict occurred
     * @param message      Description of the conflict
     */
    public HgMergeConflictException(String conflictPath, String message) {
        super("Merge conflict in '" + conflictPath + "': " + message);
        this.conflictPath = conflictPath;
        this.conflictPaths = List.of(conflictPath);
    }

    public HgMergeConflictException(String conflictPath, String message, Throwable cause) {
        super("Merge conflict in '" + conflictPath + "': " + message, cause);
        this.conflictPath = conflictPath;
        this.conflictPaths = List.of(conflictPath);
    }

    /**
     * @param conflictPaths One or more file paths where conflicts occurred (e.g. every file left
     *                      unresolved by a single paused {@code hg rebase} revision)
     * @param message       Description of the conflict
     */
    public HgMergeConflictException(List<String> conflictPaths, String message) {
        super("Merge conflict in " + conflictPaths + ": " + message);
        this.conflictPaths = List.copyOf(conflictPaths);
        this.conflictPath = conflictPaths.isEmpty() ? null : conflictPaths.get(0);
    }

    /** Returns the file path where the conflict occurred (the first one, if there were several). */
    public String getConflictPath() {
        return conflictPath;
    }

    /** Returns every file path left unresolved by the operation that raised this exception. */
    public List<String> getConflictPaths() {
        return conflictPaths;
    }
}
