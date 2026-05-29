package org.hg4j.transport;

/**
 * JGit-style CredentialItem abstractions for secure and modular credentials retrieval.
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
