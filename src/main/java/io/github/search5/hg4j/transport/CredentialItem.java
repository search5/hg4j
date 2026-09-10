package io.github.search5.hg4j.transport;

/**
 * JGit-style CredentialItem abstractions for secure and modular credentials retrieval.
 *
 * @apiNote Instances are passed to {@link CredentialsProvider#get} to request specific pieces of
 *     credential data; {@link #isSecure()} lets a provider implementation decide whether to mask
 *     input (e.g. when prompting interactively) for {@link Password}/{@link SshPassphrase} versus
 *     {@link Username}/{@link SshKeyPath}.
 */
public abstract class CredentialItem {
    private final String prompt;
    private final boolean secure;

    /**
     * Creates a credential item.
     *
     * @param prompt the human-readable label to display when requesting this credential
     * @param secure {@code true} if the value should be treated as sensitive (masked when
     *               prompted interactively), {@code false} otherwise
     */
    protected CredentialItem(String prompt, boolean secure) {
        this.prompt = prompt;
        this.secure = secure;
    }

    /**
     * Returns the human-readable label to display when requesting this credential.
     *
     * @return the prompt label
     */
    public String getPrompt() {
        return prompt;
    }

    /**
     * Whether this credential's value should be treated as sensitive.
     *
     * @return {@code true} if the value should be masked when prompted interactively, {@code false} otherwise
     */
    public boolean isSecure() {
        return secure;
    }

    /** A username credential item. */
    public static class Username extends CredentialItem {
        private String value;

        /** Creates a username credential item with the default "Username" prompt. */
        public Username() {
            super("Username", false);
        }

        /**
         * Returns the supplied username value.
         *
         * @return the username, or {@code null} if not yet supplied
         */
        public String getValue() {
            return value;
        }

        /**
         * Sets the username value.
         *
         * @param value the username to store
         */
        public void setValue(String value) {
            this.value = value;
        }
    }

    /** A password credential item. */
    public static class Password extends CredentialItem {
        private char[] value;

        /** Creates a password credential item with the default "Password" prompt, marked secure. */
        public Password() {
            super("Password", true);
        }

        /**
         * Returns the supplied password value.
         *
         * @return the password characters, or {@code null} if not yet supplied
         */
        public char[] getValue() {
            return value;
        }

        /**
         * Sets the password value.
         *
         * @param value the password characters to store
         */
        public void setValue(char[] value) {
            this.value = value;
        }
    }

    /** An SSH private key file path credential item. */
    public static class SshKeyPath extends CredentialItem {
        private String value;

        /** Creates an SSH private key path credential item with the default "SSH Private Key Path" prompt. */
        public SshKeyPath() {
            super("SSH Private Key Path", false);
        }

        /**
         * Returns the supplied SSH private key file path.
         *
         * @return the key file path, or {@code null} if not yet supplied
         */
        public String getValue() {
            return value;
        }

        /**
         * Sets the SSH private key file path.
         *
         * @param value the key file path to store
         */
        public void setValue(String value) {
            this.value = value;
        }
    }

    /** An SSH private key passphrase credential item. */
    public static class SshPassphrase extends CredentialItem {
        private char[] value;

        /** Creates an SSH passphrase credential item with the default "SSH Passphrase" prompt, marked secure. */
        public SshPassphrase() {
            super("SSH Passphrase", true);
        }

        /**
         * Returns the supplied SSH key passphrase.
         *
         * @return the passphrase characters, or {@code null} if not yet supplied
         */
        public char[] getValue() {
            return value;
        }

        /**
         * Sets the SSH key passphrase.
         *
         * @param value the passphrase characters to store
         */
        public void setValue(char[] value) {
            this.value = value;
        }
    }
}
