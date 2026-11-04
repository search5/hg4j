package io.github.search5.hg4j.transport;

/**
 * JGit-style CredentialItem abstractions for secure and modular credentials retrieval.
 */
public abstract class CredentialItem {
    private final String prompt;
    private final boolean secure;

    /**
     * Creates an item.
     * @param prompt the text describing the requested value, suitable for showing to a user
     * @param secure true if the value is confidential and must not be echoed or logged
     */
    protected CredentialItem(String prompt, boolean secure) {
        this.prompt = prompt;
        this.secure = secure;
    }

    /**
     * Returns the text describing the requested value.
     */
    public String getPrompt() {
        return prompt;
    }

    /**
     * Returns whether the value is confidential and must not be echoed or logged.
     */
    public boolean isSecure() {
        return secure;
    }

    /**
     * A request for a user name (prompt {@code "Username"}, not secure). The value is null until a
     * provider sets it.
     */
    public static class Username extends CredentialItem {
        private String value;

        /**
         * Creates an empty user name item.
         */
        public Username() {
            super("Username", false);
        }

        /**
         * Returns the user name.
         * @return the value, or null if no provider has filled it
         */
        public String getValue() {
            return value;
        }

        /**
         * Sets the user name.
         * @param value the user name; may be null
         */
        public void setValue(String value) {
            this.value = value;
        }
    }

    /**
     * A request for a password (prompt {@code "Password"}, secure). The value is null until a provider
     * sets it.
     */
    public static class Password extends CredentialItem {
        private char[] value;

        /**
         * Creates an empty password item.
         */
        public Password() {
            super("Password", true);
        }

        /**
         * Returns the password.
         * @return the internal character array, not a copy, or null if no provider has filled it
         */
        public char[] getValue() {
            return value;
        }

        /**
         * Sets the password. The array is stored as given, not copied.
         * @param value the password characters; may be null
         */
        public void setValue(char[] value) {
            this.value = value;
        }
    }

    /**
     * A request for the file path of an SSH private key (prompt {@code "SSH Private Key Path"}, not
     * secure). The value is null until a provider sets it.
     */
    public static class SshKeyPath extends CredentialItem {
        private String value;

        /**
         * Creates an empty key path item.
         */
        public SshKeyPath() {
            super("SSH Private Key Path", false);
        }

        /**
         * Returns the private key file path.
         * @return the value, or null if no provider has filled it
         */
        public String getValue() {
            return value;
        }

        /**
         * Sets the private key file path.
         * @param value the path; may be null
         */
        public void setValue(String value) {
            this.value = value;
        }
    }

    /**
     * A request for the passphrase of an SSH private key (prompt {@code "SSH Passphrase"}, secure).
     * The value is null until a provider sets it.
     */
    public static class SshPassphrase extends CredentialItem {
        private char[] value;

        /**
         * Creates an empty passphrase item.
         */
        public SshPassphrase() {
            super("SSH Passphrase", true);
        }

        /**
         * Returns the passphrase.
         * @return the internal character array, not a copy, or null if no provider has filled it
         */
        public char[] getValue() {
            return value;
        }

        /**
         * Sets the passphrase. The array is stored as given, not copied.
         * @param value the passphrase characters; may be null
         */
        public void setValue(char[] value) {
            this.value = value;
        }
    }
}
