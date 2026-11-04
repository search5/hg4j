package io.github.search5.hg4j.errors;

/**
 * Base Exception class representing Mercurial domain and operational exceptions in hg4j (Checked Exception).
 */
public class HgException extends Exception {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a message.
     *
     * @param message the detail message
     */
    public HgException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a message and an underlying cause.
     *
     * @param message the detail message
     * @param cause the underlying failure; may be {@code null}
     */
    public HgException(String message, Throwable cause) {
        super(message, cause);
    }
}
