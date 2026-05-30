package org.hg4j.errors;

/**
 * Base Exception class representing Mercurial domain and operational exceptions in hg4j (Checked Exception).
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
