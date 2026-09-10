package io.github.search5.hg4j.transport;

/**
 * Common interface for credentials provider to abstract authentication mechanisms (HTTP Basic, SSH Keys, etc.)
 *
 * @apiNote Set on a connection via {@link HgRemoteConnection#setCredentialsProvider}, implemented
 *     by {@code HgRemoteClient}/{@code HgRemoteClientV2} (HTTP Basic) and {@code HgSshClient}
 *     (SSH key/passphrase); callers of networked commands (e.g. {@code PullCommand}, {@code
 *     FetchCommand}) supply an implementation such as {@link UsernamePasswordCredentialsProvider}
 *     or {@link SshKeyCredentialsProvider} to avoid embedding credentials in the remote URL.
 */
public interface CredentialsProvider {
    /**
     * Fills the requested CredentialItems with actual credential values for the target URI.
     *
     * @param uri target remote repository URI
     * @param items requested CredentialItems
     * @return true if successfully filled, false otherwise
     */
    boolean get(String uri, CredentialItem... items);
}
