package io.github.search5.hg4j.transport;

/**
 * Common interface for credentials provider to abstract authentication mechanisms (HTTP Basic, SSH Keys, etc.)
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
