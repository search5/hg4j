package io.github.search5.hg4j.errors;

/**
 * Base Exception class representing Mercurial domain and operational exceptions in hg4j (Checked Exception).
 *
 * @apiNote Not thrown directly; it is the common supertype for domain-level failures that are
 *     not I/O in nature, such as {@link HgLockException} and {@link HgMergeConflictException}.
 *     A caller that wants a single catch clause for "something went wrong in hg4j's domain
 *     logic, as opposed to the underlying transport or file system" can catch this type.
 */
public class HgException extends Exception {
    private static final long serialVersionUID = 1L;

    public HgException(String message) {
        super(message);
    }

    public HgException(String message, Throwable cause) {
        super(message, cause);
    }
}
