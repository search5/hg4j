package io.github.search5.hg4j.transport;

/**
 * SSH Private key and optional passphrase based credentials provider for SSH key authentication.
 */
public class SshKeyCredentialsProvider implements CredentialsProvider {
    private final String privateKeyPath;
    private final String passphrase;

    /**
     * Creates a provider for a private key without a passphrase.
     * @param privateKeyPath the private key file path
     */
    public SshKeyCredentialsProvider(String privateKeyPath) {
        this(privateKeyPath, null);
    }

    /**
     * Creates a provider for a private key.
     * @param privateKeyPath the private key file path
     * @param passphrase the key passphrase, or null if the key is not protected
     */
    public SshKeyCredentialsProvider(String privateKeyPath, String passphrase) {
        this.privateKeyPath = privateKeyPath;
        this.passphrase = passphrase;
    }

    /**
     * Returns the private key file path given at construction.
     */
    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

    /**
     * Returns the passphrase given at construction, or null if there is none.
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
