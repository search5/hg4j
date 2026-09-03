package io.github.search5.hg4j.transport;

/**
 * An abstract factory interface for creating SshSession instances independent of a specific SSH library.
 * Supports dynamic plugin substitution for various SSH libraries, such as Apache MINA SSHD.
 */
public interface SshSessionFactory {
    /**
     * Creates an abstracted SshSession based on the specified connection details.
     *
     * @param host The target host name
     * @param port The port number
     * @param username The user name
     * @param password The password (null if none)
     * @param privateKeyPath The private key file path (null if none)
     * @param passphrase The passphrase for the private key (null if none)
     * @return The created SshSession instance
     * @throws Exception If an error occurs during session creation
     */
    SshSession createSession(String host, int port, String username, String password, String privateKeyPath, String passphrase) throws Exception;
}
