package com.github.search5.hg4j.errors;

import java.io.IOException;

/**
 * Checked exception thrown on failures in repository state, working directory integrity, or validation checks.
 * Extends java.io.IOException to maintain API compatibility with existing I/O exceptions.
 */
public class HgValidationException extends IOException {
    private static final long serialVersionUID = 1L;

    public HgValidationException(String message) {
        super(message);
    }

    public HgValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
