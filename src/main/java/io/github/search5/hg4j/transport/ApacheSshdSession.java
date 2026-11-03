package io.github.search5.hg4j.transport;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.config.hosts.HostConfigEntry;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.SshException;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.util.io.resource.PathResource;
import org.apache.sshd.common.util.security.SecurityUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link SshSession} implementation backed by Apache MINA SSHD.
 *
 * <p>Life cycle: {@link #connect(int)} starts the client, opens the transport and authenticates;
 * {@link #executeCommand(String, int)} opens one exec channel whose stdin/stdout are exposed through
 * {@link #getOutputStream()} / {@link #getInputStream()}; {@link #close()} releases the channel,
 * the session and the client, and reports the first failure after it has attempted all three.</p>
 *
 * <p>Authentication failures are reported as {@link SshAuthenticationException}; every other
 * failure propagates as the underlying exception and is never swallowed.</p>
 */
public class ApacheSshdSession implements SshSession {

    /** Upper bound of remote stderr kept for error messages. */
    private static final int MAX_CAPTURED_STDERR = 64 * 1024;

    private final SshClient client;
    private final String username;
    private final String host;
    private final int port;
    private final String password;
    private final String privateKeyPath;
    private final String passphrase;

    private ClientSession session;
    private boolean authenticated;
    private ChannelExec channel;
    private InputStream in;
    private OutputStream out;
    private final BoundedBuffer stderr = new BoundedBuffer(MAX_CAPTURED_STDERR);

    /**
     * @param client         a configured but not yet started client; this session takes ownership
     *                       and stops it in {@link #close()}
     * @param username       user name; null or empty means "unspecified": {@code ~/.ssh/config} decides,
     *                       else the local user
     * @param host           host name or alias
     * @param port           port; 0 or less means "unspecified": {@code ~/.ssh/config} decides, else 22
     * @param password       password or null
     * @param privateKeyPath path of an explicit private key or null
     * @param passphrase     passphrase of the private key or null
     */
    public ApacheSshdSession(SshClient client, String username, String host, int port,
                             String password, String privateKeyPath, String passphrase) {
        this.client = client;
        this.username = username;
        this.host = host;
        this.port = port;
        this.password = password;
        this.privateKeyPath = privateKeyPath;
        this.passphrase = passphrase;
    }

    @Override
    public void connect(int timeoutMs) throws Exception {
        if (session != null && session.isOpen() && authenticated) {
            return;
        }
        client.start();
        session = client.connect(effectiveUsername(), host, port).verify(timeoutMs).getSession();

        if (privateKeyPath != null) {
            for (KeyPair keyPair : loadKeyPairs(privateKeyPath, passphrase)) {
                session.addPublicKeyIdentity(keyPair);
            }
        }
        if (password != null) {
            session.addPasswordIdentity(password);
        }

        try {
            session.auth().verify(timeoutMs);
            authenticated = true;
        } catch (SshException e) {
            if (isAuthenticationFailure(e)) {
                throw new SshAuthenticationException(
                        "SSH authentication failed for " + describeTarget() + ": " + e.getMessage(), e);
            }
            throw e;
        }
    }

    @Override
    public void executeCommand(String command, int timeoutMs) throws Exception {
        if (session == null || !session.isOpen() || !authenticated) {
            throw new IllegalStateException("SSH Session is not connected");
        }
        channel = session.createExecChannel(command);
        channel.setErr(stderr);
        channel.open().verify(timeoutMs);
        this.in = channel.getInvertedOut();
        this.out = channel.getInvertedIn();
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return this.in;
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return this.out;
    }

    /**
     * @return remote stderr captured so far (at most {@value #MAX_CAPTURED_STDERR} bytes), useful
     *         for diagnosing a command that failed to start
     */
    public String getCapturedStderr() {
        return stderr.asString();
    }

    @Override
    public void close() throws IOException {
        IOException first = null;
        if (channel != null) {
            try {
                channel.close(false).await();
            } catch (IOException e) {
                first = e;
            } finally {
                channel = null;
            }
        }
        if (session != null) {
            try {
                session.close(false).await();
            } catch (IOException e) {
                first = first == null ? e : first;
            } finally {
                session = null;
                authenticated = false;
            }
        }
        try {
            client.stop();
        } catch (RuntimeException e) {
            if (first == null) {
                first = new IOException("Failed to stop SSH client", e);
            }
        }
        if (first != null) {
            throw first;
        }
    }

    /**
     * The user to log in as: the explicit one, else the {@code User} of a matching {@code ~/.ssh/config}
     * entry, else the local user (the order {@code ssh(1)} uses).
     */
    private String effectiveUsername() throws IOException {
        if (username != null && !username.isEmpty()) {
            return username;
        }
        HostConfigEntry entry = client.getHostConfigEntryResolver()
                .resolveEffectiveHost(host, port, null, null, null, null);
        if (entry != null && entry.getUsername() != null && !entry.getUsername().isEmpty()) {
            return entry.getUsername();
        }
        return System.getProperty("user.name");
    }

    private String describeTarget() {
        return (username != null ? username + "@" : "") + host + (port > 0 ? ":" + port : "");
    }

    /** SSHD reports "no authentication method left" with this disconnect code. */
    private static boolean isAuthenticationFailure(SshException e) {
        return e.getDisconnectCode() == SshConstants.SSH2_DISCONNECT_NO_MORE_AUTH_METHODS_AVAILABLE
                || (e.getMessage() != null && e.getMessage().startsWith("Authentication failed"));
    }

    private static List<KeyPair> loadKeyPairs(String path, String passphrase)
            throws IOException, GeneralSecurityException {
        Path keyPath = Paths.get(path);
        if (!Files.isRegularFile(keyPath)) {
            throw new IOException("SSH private key not found: " + path);
        }
        FilePasswordProvider provider = passphrase == null
                ? FilePasswordProvider.EMPTY
                : FilePasswordProvider.of(passphrase);
        Iterable<KeyPair> loaded;
        try (InputStream keyStream = Files.newInputStream(keyPath)) {
            loaded = SecurityUtils.loadKeyPairIdentities(null, new PathResource(keyPath), keyStream, provider);
        }
        if (loaded == null) {
            throw new IOException("No usable SSH private key found in: " + path);
        }
        List<KeyPair> keyPairs = new ArrayList<>();
        loaded.forEach(keyPairs::add);
        return keyPairs;
    }

    /** Keeps the first {@code limit} bytes written and silently drops the rest. */
    private static final class BoundedBuffer extends OutputStream {
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        private final int limit;

        BoundedBuffer(int limit) {
            this.limit = limit;
        }

        @Override
        public synchronized void write(int b) {
            if (delegate.size() < limit) {
                delegate.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            int room = limit - delegate.size();
            if (room > 0) {
                delegate.write(b, off, Math.min(room, len));
            }
        }

        synchronized String asString() {
            return delegate.toString(StandardCharsets.UTF_8);
        }
    }
}
