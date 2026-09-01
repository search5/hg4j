package com.github.search5.hg4j.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CredentialItemTest {

    @Test
    public void usernameIsNotSecureAndStoresValue() {
        CredentialItem.Username item = new CredentialItem.Username();
        assertEquals("Username", item.getPrompt());
        assertFalse(item.isSecure());
        item.setValue("alice");
        assertEquals("alice", item.getValue());
    }

    @Test
    public void passwordIsSecureAndStoresCharArray() {
        CredentialItem.Password item = new CredentialItem.Password();
        assertEquals("Password", item.getPrompt());
        assertTrue(item.isSecure());
        item.setValue("s3cret".toCharArray());
        assertArrayEquals("s3cret".toCharArray(), item.getValue());
    }

    @Test
    public void sshKeyPathIsNotSecureAndStoresValue() {
        CredentialItem.SshKeyPath item = new CredentialItem.SshKeyPath();
        assertEquals("SSH Private Key Path", item.getPrompt());
        assertFalse(item.isSecure());
        item.setValue("/home/user/.ssh/id_rsa");
        assertEquals("/home/user/.ssh/id_rsa", item.getValue());
    }

    @Test
    public void sshPassphraseIsSecureAndStoresCharArray() {
        CredentialItem.SshPassphrase item = new CredentialItem.SshPassphrase();
        assertEquals("SSH Passphrase", item.getPrompt());
        assertTrue(item.isSecure());
        item.setValue("phrase".toCharArray());
        assertArrayEquals("phrase".toCharArray(), item.getValue());
    }
}
