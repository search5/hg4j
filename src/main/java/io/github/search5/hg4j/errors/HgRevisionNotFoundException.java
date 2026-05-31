package io.github.search5.hg4j.errors;

/**
 * Exception thrown when a revision is not found by hash or revision number.
 */
public class HgRevisionNotFoundException extends java.io.IOException {
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

    public HgRevisionNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
