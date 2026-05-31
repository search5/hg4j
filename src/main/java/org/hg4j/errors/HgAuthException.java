package org.hg4j.errors;

/**
 * Exception thrown on SSH/HTTP credential authentication failure.
 * Subclass of {@link HgTransportException}.
 */
public class HgAuthException extends HgTransportException {
    private static final long serialVersionUID = 1L;

    private final String username;

    /**
     * @param remoteUrl Target remote repository URL for authentication
     * @param username  Username that failed authentication
     */
    public HgAuthException(String remoteUrl, String username) {
        super(remoteUrl, "Authentication failed for user '" + username + "'");
        this.username = username;
    }

    /**
     * @param remoteUrl Target remote repository URL for authentication
     * @param username  Username that failed authentication
     * @param cause     The causing exception
     */
    public HgAuthException(String remoteUrl, String username, Throwable cause) {
        super(remoteUrl, "Authentication failed for user '" + username + "'", cause);
        this.username = username;
    }

    /** Returns the username that failed authentication. */
    public String getUsername() {
        return username;
    }
}
