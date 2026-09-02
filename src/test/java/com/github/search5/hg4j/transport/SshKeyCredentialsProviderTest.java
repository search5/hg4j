package com.github.search5.hg4j.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SshKeyCredentialsProviderTest {

    @Test
    public void singleArgConstructorLeavesPassphraseNull() {
        SshKeyCredentialsProvider provider = new SshKeyCredentialsProvider("/home/user/.ssh/id_rsa");
        assertEquals("/home/user/.ssh/id_rsa", provider.getPrivateKeyPath());
        assertNull(provider.getPassphrase());
    }

    @Test
    public void twoArgConstructorStoresBothFields() {
        SshKeyCredentialsProvider provider = new SshKeyCredentialsProvider("/home/user/.ssh/id_rsa", "s3cret");
        assertEquals("/home/user/.ssh/id_rsa", provider.getPrivateKeyPath());
        assertEquals("s3cret", provider.getPassphrase());
    }

    @Test
    public void getFillsSshKeyPathItemAndReturnsTrue() {
        SshKeyCredentialsProvider provider = new SshKeyCredentialsProvider("/home/user/.ssh/id_rsa");
        CredentialItem.SshKeyPath keyPathItem = new CredentialItem.SshKeyPath();

        boolean ok = provider.get("ssh://example.com/repo", keyPathItem);

        assertTrue(ok);
        assertEquals("/home/user/.ssh/id_rsa", keyPathItem.getValue());
    }

    @Test
    public void getFillsSshPassphraseItemWithCharArrayWhenPassphrasePresent() {
        SshKeyCredentialsProvider provider = new SshKeyCredentialsProvider("/home/user/.ssh/id_rsa", "phrase");
        CredentialItem.SshPassphrase passphraseItem = new CredentialItem.SshPassphrase();

        boolean ok = provider.get("ssh://example.com/repo", passphraseItem);

        assertTrue(ok);
        assertArrayEquals("phrase".toCharArray(), passphraseItem.getValue());
    }

    @Test
    public void getFillsSshPassphraseItemWithNullWhenPassphraseAbsent() {
        SshKeyCredentialsProvider provider = new SshKeyCredentialsProvider("/home/user/.ssh/id_rsa");
        CredentialItem.SshPassphrase passphraseItem = new CredentialItem.SshPassphrase();

        boolean ok = provider.get("ssh://example.com/repo", passphraseItem);

        assertTrue(ok);
        assertNull(passphraseItem.getValue());
    }

    @Test
    public void getHandlesBothItemsTogether() {
        SshKeyCredentialsProvider provider = new SshKeyCredentialsProvider("/home/user/.ssh/id_rsa", "phrase");
        CredentialItem.SshKeyPath keyPathItem = new CredentialItem.SshKeyPath();
        CredentialItem.SshPassphrase passphraseItem = new CredentialItem.SshPassphrase();

        boolean ok = provider.get("ssh://example.com/repo", keyPathItem, passphraseItem);

        assertTrue(ok);
        assertEquals("/home/user/.ssh/id_rsa", keyPathItem.getValue());
        assertArrayEquals("phrase".toCharArray(), passphraseItem.getValue());
    }

    @Test
    public void getReturnsFalseForUnsupportedItemTypes() {
        SshKeyCredentialsProvider provider = new SshKeyCredentialsProvider("/home/user/.ssh/id_rsa", "phrase");
        CredentialItem.Username usernameItem = new CredentialItem.Username();
        CredentialItem.Password passwordItem = new CredentialItem.Password();

        boolean ok = provider.get("ssh://example.com/repo", usernameItem, passwordItem);

        assertFalse(ok);
        assertNull(usernameItem.getValue());
        assertNull(passwordItem.getValue());
    }

    @Test
    public void getReturnsFalseForEmptyItemsArray() {
        SshKeyCredentialsProvider provider = new SshKeyCredentialsProvider("/home/user/.ssh/id_rsa", "phrase");

        boolean ok = provider.get("ssh://example.com/repo");

        assertFalse(ok);
    }
}
