package org.hg4j.errors;

/**
 * Base RuntimeException class representing Mercurial domain and operational exceptions in hg4j.
 */
public class HgException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public HgException(String message) {
        super(message);
    }

    public HgException(String message, Throwable cause) {
        super(message, cause);
    }
}
