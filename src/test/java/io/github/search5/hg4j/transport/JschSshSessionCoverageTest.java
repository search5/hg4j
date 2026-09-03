package io.github.search5.hg4j.transport;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.ThrowingSession;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targeted coverage tests for {@link JschSshSession}. Exercises every branch not already reached
 * by the broader SSH transport integration tests (e.g. {@link HgSshClientTransportTest}):
 * the null-session short-circuits in {@code connect()}/{@code executeCommand()}, the
 * "already connected" skip branch in {@code connect()}, the unconnected-session failure branch
 * in {@code executeCommand()}, and both defensive {@code catch (Exception ignored)} blocks in
 * {@code close()}.
 */
public class JschSshSessionCoverageTest {

    private SshServer sshServer;
    private int port;

    @BeforeEach
    public void startSshServer() throws Exception {
        sshServer = SshServer.setUpDefaultServer();
        sshServer.setPort(0);

        Path tempKey = Files.createTempFile("ssh_host_jsch_coverage_", ".key");
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempKey));
        Files.deleteIfExists(tempKey);

        sshServer.setPasswordAuthenticator((username, password, session) ->
                "hg4juser".equals(username) && "hg4jpass".equals(password));
        sshServer.setCommandFactory((channel, command) -> new org.apache.sshd.server.command.Command() {
            private org.apache.sshd.server.ExitCallback callback;
            @Override public void setInputStream(java.io.InputStream in) {}
            @Override public void setOutputStream(java.io.OutputStream out) {}
            @Override public void setErrorStream(java.io.OutputStream err) {}
            @Override public void setExitCallback(org.apache.sshd.server.ExitCallback callback) { this.callback = callback; }
            @Override public void start(org.apache.sshd.server.channel.ChannelSession session, org.apache.sshd.server.Environment env) {
                callback.onExit(0);
            }
            @Override public void destroy(org.apache.sshd.server.channel.ChannelSession session) {}
        });
        sshServer.start();
        port = sshServer.getPort();
    }

    @AfterEach
    public void stopSshServer() throws Exception {
        if (sshServer != null) {
            sshServer.stop(true);
        }
    }

    private static Object getField(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    // ─────────────────────────────────────────────────────────────────────
    // connect() branch coverage
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("connect - null session -> no-op, no exception")
    public void testConnect_nullSession_noOp() {
        JschSshSession sut = new JschSshSession(null);
        assertDoesNotThrow(() -> sut.connect(1000));
    }

    @Test
    @DisplayName("connect - already-connected session -> skips session.connect() again")
    public void testConnect_alreadyConnected_skipsReconnect() throws Exception {
        Session session = realConnectedSession();
        try {
            assertTrue(session.isConnected());
            JschSshSession sut = new JschSshSession(session);
            // session != null && !isConnected() == false -> the connect() branch is skipped entirely.
            assertDoesNotThrow(() -> sut.connect(1000));
            assertTrue(session.isConnected());
        } finally {
            session.disconnect();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // executeCommand() branch coverage
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("executeCommand - null session -> IllegalStateException")
    public void testExecuteCommand_nullSession_throwsIllegalStateException() {
        JschSshSession sut = new JschSshSession(null);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> sut.executeCommand("ls", 1000));
        assertEquals("SSH Session is not connected", ex.getMessage());
    }

    @Test
    @DisplayName("executeCommand - non-null but unconnected session -> IllegalStateException")
    public void testExecuteCommand_unconnectedSession_throwsIllegalStateException() throws Exception {
        JSch jsch = new JSch();
        Session session = jsch.getSession("hg4juser", "127.0.0.1", port);
        assertFalse(session.isConnected());

        JschSshSession sut = new JschSshSession(session);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> sut.executeCommand("ls", 1000));
        assertEquals("SSH Session is not connected", ex.getMessage());
    }

    // ─────────────────────────────────────────────────────────────────────
    // close() branch coverage: both catch (Exception ignored) blocks
    // ─────────────────────────────────────────────────────────────────────

    /** Overrides disconnect() to throw, so JschSshSession.close()'s catch block is exercised. */
    private static class ThrowingChannelExec extends ChannelExec {
        @Override
        public void disconnect() {
            throw new RuntimeException("boom - simulated channel.disconnect() failure");
        }
    }

    @Test
    @DisplayName("close - channel.disconnect() throws -> exception swallowed, session still null-safe")
    public void testClose_channelDisconnectThrows_exceptionIsSwallowed() throws Exception {
        // session == null here also exercises the missing "session == null" skip branch
        // of the second `if (session != null)` check in close().
        JschSshSession sut = new JschSshSession(null);
        setField(sut, "channel", new ThrowingChannelExec());

        assertDoesNotThrow(sut::close);
        assertNull(getField(sut, "channel"));
    }

    @Test
    @DisplayName("close - session.disconnect() throws -> exception swallowed")
    public void testClose_sessionDisconnectThrows_exceptionIsSwallowed() throws Exception {
        Session throwingSession = new ThrowingSession();
        JschSshSession sut = new JschSshSession(throwingSession);

        assertDoesNotThrow(sut::close);
    }

    @Test
    @DisplayName("close - no channel, no session -> no-op")
    public void testClose_noChannelNoSession_noOp() {
        JschSshSession sut = new JschSshSession(null);
        assertDoesNotThrow(sut::close);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────

    private Session realConnectedSession() throws Exception {
        JSch jsch = new JSch();
        Session session = jsch.getSession("hg4juser", "127.0.0.1", port);
        session.setPassword("hg4jpass");
        session.setConfig("StrictHostKeyChecking", "no");
        session.connect(5000);
        return session;
    }
}
