package io.github.search5.hg4j.errors;

import java.util.List;

/**
 * Exception thrown when unresolved conflicts occur during a merge3 operation (e.g. a real 3-way
 * merge attempted by {@link io.github.search5.hg4j.api.MergeCommand} or, since 2026-09-04, by
 * {@link io.github.search5.hg4j.api.RebaseCommand}'s cherry-pick path).
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
