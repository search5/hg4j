package io.github.search5.hg4j.transport;

/**
 * Username and password based credentials provider for HTTP basic or SSH password authentication.
 */
public class UsernamePasswordCredentialsProvider implements CredentialsProvider {
    private final String username;
    private final String password;

    /**
     * Creates a new provider that supplies a fixed username and password.
     *
     * @param username the username to supply on {@link #get(String, CredentialItem...)}
     * @param password the password to supply on {@link #get(String, CredentialItem...)}
     */
    public UsernamePasswordCredentialsProvider(String username, String password) {
        this.username = username;
        this.password = password;
    }

    /**
     * Returns the username held by this provider.
     *
     * @return the configured username
     */
    public String getUsername() {
        return username;
    }

    /**
     * Returns the password held by this provider.
     *
     * @return the configured password
     */
    public String getPassword() {
        return password;
    }

    @Override
    public boolean get(String uri, CredentialItem... items) {
        boolean ok = false;
        for (CredentialItem item : items) {
            if (item instanceof CredentialItem.Username) {
                ((CredentialItem.Username) item).setValue(username);
                ok = true;
            } else if (item instanceof CredentialItem.Password) {
                ((CredentialItem.Password) item).setValue(password != null ? password.toCharArray() : null);
                ok = true;
            }
        }
        return ok;
    }
}
