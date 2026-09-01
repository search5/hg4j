package com.github.search5.hg4j.transport;

import com.github.search5.hg4j.errors.HgAuthException;
import com.github.search5.hg4j.errors.HgLockException;
import com.github.search5.hg4j.errors.HgProtocolException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the default methods of {@link HgRemoteConnection} directly, since
 * concrete implementations (HgLocalClient/HgRemoteClient/HgSshClient) all override
 * them and never invoke the interface's own default bodies.
 */
public class HgRemoteConnectionTest {

    private static class MinimalConnection implements HgRemoteConnection {
        @Override
        public List<String> getCapabilities() { return Collections.emptyList(); }
        @Override
        public List<String> getHeads() { return Collections.emptyList(); }
        @Override
        public byte[] getChangegroup(List<String> roots) { return new byte[0]; }
        @Override
        public byte[] getBundle(List<String> common, List<String> heads, List<String> bundleCaps) { return new byte[0]; }
        @Override
        public String push(byte[] bundleBytes, List<String> heads) { return "ok"; }
        @Override
        public Map<String, String> listKeys(String namespace) { return Collections.emptyMap(); }
        @Override
        public boolean pushkey(String namespace, String key, String oldVal, String newVal) { return false; }
        @Override
        public void close() { }
    }

    @Test
    public void betweenDefaultReturnsEmptyList() throws IOException {
        HgRemoteConnection conn = new MinimalConnection();
        assertEquals(Collections.emptyList(), conn.between(List.of("a", "b")));
    }

    @Test
    public void knownDefaultReturnsEmptyString() throws IOException {
        HgRemoteConnection conn = new MinimalConnection();
        assertEquals("", conn.known(List.of("deadbeef")));
    }

    @Test
    public void setCredentialsProviderDefaultIsNoOpAndDoesNotThrow() {
        HgRemoteConnection conn = new MinimalConnection();
        conn.setCredentialsProvider(new UsernamePasswordCredentialsProvider("u", "p"));
        // no observable state change; reaching this line means the default no-op executed cleanly
        assertTrue(true);
    }

    @Test
    public void abstractMethodsAreReachableThroughInterfaceReference() throws IOException, HgAuthException, HgProtocolException, HgLockException {
        HgRemoteConnection conn = new MinimalConnection();
        assertEquals(Collections.emptyList(), conn.getCapabilities());
        assertEquals(Collections.emptyList(), conn.getHeads());
        assertEquals(0, conn.getChangegroup(Collections.emptyList()).length);
        assertEquals(0, conn.getBundle(null, null, null).length);
        assertEquals("ok", conn.push(new byte[0], Collections.emptyList()));
        assertEquals(Collections.emptyMap(), conn.listKeys("bookmarks"));
        assertEquals(false, conn.pushkey("bookmarks", "k", "old", "new"));
        conn.close();
    }
}
