package io.github.search5.hg4j.transport;

/**
 * SSH Private key and optional passphrase based credentials provider for SSH key authentication.
 */
public class SshKeyCredentialsProvider implements CredentialsProvider {
    private final String privateKeyPath;
    private final String passphrase;

    /**
     * Creates a provider for an unencrypted (passphrase-less) private key.
     *
     * @param privateKeyPath filesystem path to the SSH private key file
     */
    public SshKeyCredentialsProvider(String privateKeyPath) {
        this(privateKeyPath, null);
    }

    /**
     * Creates a provider for a private key, optionally passphrase-protected.
     *
     * @param privateKeyPath filesystem path to the SSH private key file
     * @param passphrase the private key's passphrase, or {@code null} if it is not encrypted
     */
    public SshKeyCredentialsProvider(String privateKeyPath, String passphrase) {
        this.privateKeyPath = privateKeyPath;
        this.passphrase = passphrase;
    }

    /**
     * Returns the configured private key file path.
     *
     * @return the filesystem path to the SSH private key file
     */
    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

    /**
     * Returns the configured private key passphrase.
     *
     * @return the private key's passphrase, or {@code null} if it is not encrypted
     */
    public String getPassphrase() {
        return passphrase;
    }

    @Override
    public boolean get(String uri, CredentialItem... items) {
        boolean ok = false;
        for (CredentialItem item : items) {
            if (item instanceof CredentialItem.SshKeyPath) {
                ((CredentialItem.SshKeyPath) item).setValue(privateKeyPath);
                ok = true;
            } else if (item instanceof CredentialItem.SshPassphrase) {
                ((CredentialItem.SshPassphrase) item).setValue(passphrase != null ? passphrase.toCharArray() : null);
                ok = true;
            }
        }
        return ok;
    }
}
