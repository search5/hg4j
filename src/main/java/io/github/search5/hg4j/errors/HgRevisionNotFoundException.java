package io.github.search5.hg4j.errors;

import java.io.IOException;

/**
 * Exception thrown when a revision is not found by hash or revision number.
 *
 * @apiNote Thrown by {@code io.github.search5.hg4j.storage.Revlog} when a node ID or revision
 *     number does not resolve to an entry in the revlog index, and propagated by any porcelain
 *     command that resolves a user-supplied revision reference (e.g. {@code CatCommand}, {@code
 *     UpdateCommand}, {@code CommitCommand}, {@code MergeCommand}, {@code GraftCommand}, {@code
 *     RebaseCommand}, {@code ShelveCommand}) as well as {@code
 *     io.github.search5.hg4j.treewalk.ManifestTreeIterator}. Use the {@link
 *     #HgRevisionNotFoundException(String)} constructor for a hex node ID lookup failure and
 *     {@link #HgRevisionNotFoundException(int)} for a revision-number lookup failure — the
 *     message wording differs so callers can tell which form of reference the user supplied.
 */
public class HgRevisionNotFoundException extends IOException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an instance with a node ID (hex string).
     *
     * @param nodeId 40-character hexadecimal node ID string
     */
    public HgRevisionNotFoundException(String nodeId) {
        super("Revision not found for node ID: " + nodeId);
    }

    /**
     * Creates an instance with a revision number.
     *
     * @param revNumber The revision number that failed to look up
     */
    public HgRevisionNotFoundException(int revNumber) {
        super("Revision not found for revision number: " + revNumber);
    }

    /**
     * Creates an instance with a custom message and an underlying cause.
     *
     * @param message detail message describing the lookup failure
     * @param cause the underlying exception that caused the revision lookup to fail
     */
    public HgRevisionNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
