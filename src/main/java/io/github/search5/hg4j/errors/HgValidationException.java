package io.github.search5.hg4j.errors;

import java.io.IOException;

/**
 * Checked exception thrown on failures in repository state, working directory integrity, or validation checks.
 * Extends java.io.IOException to maintain API compatibility with existing I/O exceptions.
 */
public class HgValidationException extends IOException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a message.
     *
     * @param message description of the failed check
     */
    public HgValidationException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a message and an underlying cause.
     *
     * @param message description of the failed check
     * @param cause the underlying failure; may be {@code null}
     */
    public HgValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
