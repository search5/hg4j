package org.hg4j.errors;

/**
 * Exception thrown when unresolved conflicts occur during a merge3 operation.
 */
public class HgMergeConflictException extends HgException {
    private static final long serialVersionUID = 1L;

    private final String conflictPath;

    /**
     * @param conflictPath File path where the conflict occurred
     * @param message      Description of the conflict
     */
    public HgMergeConflictException(String conflictPath, String message) {
        super("Merge conflict in '" + conflictPath + "': " + message);
        this.conflictPath = conflictPath;
    }

    public HgMergeConflictException(String conflictPath, String message, Throwable cause) {
        super("Merge conflict in '" + conflictPath + "': " + message, cause);
        this.conflictPath = conflictPath;
    }

    /** Returns the file path where the conflict occurred. */
    public String getConflictPath() {
        return conflictPath;
    }
}
