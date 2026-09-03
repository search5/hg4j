package io.github.search5.hg4j.transport;

/**
 * SSH Private key and optional passphrase based credentials provider for SSH key authentication.
 */
public class SshKeyCredentialsProvider implements CredentialsProvider {
    private final String privateKeyPath;
    private final String passphrase;

    public SshKeyCredentialsProvider(String privateKeyPath) {
        this(privateKeyPath, null);
    }

    public SshKeyCredentialsProvider(String privateKeyPath, String passphrase) {
        this.privateKeyPath = privateKeyPath;
        this.passphrase = passphrase;
    }

    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

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
