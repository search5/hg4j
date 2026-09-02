package com.github.search5.hg4j.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UsernamePasswordCredentialsProviderTest {

    @Test
    public void constructorStoresUsernameAndPassword() {
        UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider("alice", "s3cret");

        assertEquals("alice", provider.getUsername());
        assertEquals("s3cret", provider.getPassword());
    }

    @Test
    public void getFillsUsernameItemAndReturnsTrue() {
        UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider("alice", "s3cret");
        CredentialItem.Username usernameItem = new CredentialItem.Username();

        boolean ok = provider.get("https://example.com/repo", usernameItem);

        assertTrue(ok);
        assertEquals("alice", usernameItem.getValue());
    }

    @Test
    public void getFillsPasswordItemWithCharArrayWhenPasswordPresent() {
        UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider("alice", "s3cret");
        CredentialItem.Password passwordItem = new CredentialItem.Password();

        boolean ok = provider.get("https://example.com/repo", passwordItem);

        assertTrue(ok);
        assertArrayEquals("s3cret".toCharArray(), passwordItem.getValue());
    }

    @Test
    public void getFillsPasswordItemWithNullWhenPasswordAbsent() {
        UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider("alice", null);
        CredentialItem.Password passwordItem = new CredentialItem.Password();

        boolean ok = provider.get("https://example.com/repo", passwordItem);

        assertTrue(ok);
        assertNull(passwordItem.getValue());
        assertNull(provider.getPassword());
    }

    @Test
    public void getHandlesBothItemsTogether() {
        UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider("alice", "s3cret");
        CredentialItem.Username usernameItem = new CredentialItem.Username();
        CredentialItem.Password passwordItem = new CredentialItem.Password();

        boolean ok = provider.get("https://example.com/repo", usernameItem, passwordItem);

        assertTrue(ok);
        assertEquals("alice", usernameItem.getValue());
        assertArrayEquals("s3cret".toCharArray(), passwordItem.getValue());
    }

    @Test
    public void getReturnsFalseForUnsupportedItemTypes() {
        UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider("alice", "s3cret");
        CredentialItem.SshKeyPath keyPathItem = new CredentialItem.SshKeyPath();
        CredentialItem.SshPassphrase passphraseItem = new CredentialItem.SshPassphrase();

        boolean ok = provider.get("https://example.com/repo", keyPathItem, passphraseItem);

        assertFalse(ok);
        assertNull(keyPathItem.getValue());
        assertNull(passphraseItem.getValue());
    }

    @Test
    public void getReturnsFalseForEmptyItemsArray() {
        UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider("alice", "s3cret");

        boolean ok = provider.get("https://example.com/repo");

        assertFalse(ok);
    }
}
