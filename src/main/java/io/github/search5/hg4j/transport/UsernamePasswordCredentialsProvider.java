package io.github.search5.hg4j.transport;

/**
 * Username and password based credentials provider for HTTP basic or SSH password authentication.
 */
public class UsernamePasswordCredentialsProvider implements CredentialsProvider {
    private final String username;
    private final String password;

    /**
     * Creates a provider for a user name and password.
     * @param username the user name, or null
     * @param password the password, or null (a null password is supplied as null to the requesting item)
     */
    public UsernamePasswordCredentialsProvider(String username, String password) {
        this.username = username;
        this.password = password;
    }

    /**
     * Returns the user name given at construction, or null.
     */
    public String getUsername() {
        return username;
    }

    /**
     * Returns the password given at construction, or null.
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
