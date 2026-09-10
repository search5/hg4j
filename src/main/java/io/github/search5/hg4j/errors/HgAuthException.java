package io.github.search5.hg4j.errors;

/**
 * Exception thrown on SSH/HTTP credential authentication failure.
 * Subclass of {@link HgTransportException}.
 *
 * @apiNote Thrown by the client-side transport implementations ({@code HgRemoteClient},
 *     {@code HgRemoteClientV2}, {@code HgSshClient} in {@code io.github.search5.hg4j.transport})
 *     when the remote rejects the credentials supplied via a {@link
 *     io.github.search5.hg4j.transport.CredentialsProvider}. Callers of porcelain commands that
 *     talk to a remote (e.g. {@code PullCommand}, {@code PushCommand}, {@code CloneCommand})
 *     should catch this to distinguish "bad credentials" from other {@link
 *     HgTransportException} failures and prompt for new credentials or fail fast.
 */
public class HgAuthException extends HgTransportException {
    private static final long serialVersionUID = 1L;

    /** The username whose credentials were rejected by the remote. */
    private final String username;

    /**
     * Creates an authentication-failure exception for the given remote and username.
     *
     * @param remoteUrl Target remote repository URL for authentication
     * @param username  Username that failed authentication
     */
    public HgAuthException(String remoteUrl, String username) {
        super(remoteUrl, "Authentication failed for user '" + username + "'");
        this.username = username;
    }

    /**
     * Creates an authentication-failure exception for the given remote and username, wrapping an
     * underlying cause.
     *
     * @param remoteUrl Target remote repository URL for authentication
     * @param username  Username that failed authentication
     * @param cause     The causing exception
     */
    public HgAuthException(String remoteUrl, String username, Throwable cause) {
        super(remoteUrl, "Authentication failed for user '" + username + "'", cause);
        this.username = username;
    }

    /**
     * Returns the username that failed authentication.
     *
     * @return the username whose credentials were rejected
     */
    public String getUsername() {
        return username;
    }
}
