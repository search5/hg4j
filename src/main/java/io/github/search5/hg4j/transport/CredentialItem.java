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

    protected CredentialItem(String prompt, boolean secure) {
        this.prompt = prompt;
        this.secure = secure;
    }

    public String getPrompt() {
        return prompt;
    }

    public boolean isSecure() {
        return secure;
    }

    public static class Username extends CredentialItem {
        private String value;

        public Username() {
            super("Username", false);
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    public static class Password extends CredentialItem {
        private char[] value;

        public Password() {
            super("Password", true);
        }

        public char[] getValue() {
            return value;
        }

        public void setValue(char[] value) {
            this.value = value;
        }
    }

    public static class SshKeyPath extends CredentialItem {
        private String value;

        public SshKeyPath() {
            super("SSH Private Key Path", false);
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    public static class SshPassphrase extends CredentialItem {
        private char[] value;

        public SshPassphrase() {
            super("SSH Passphrase", true);
        }

        public char[] getValue() {
            return value;
        }

        public void setValue(char[] value) {
            this.value = value;
        }
    }
}
