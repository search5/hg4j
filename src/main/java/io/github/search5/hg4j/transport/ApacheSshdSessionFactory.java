package io.github.search5.hg4j.transport;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.config.hosts.ConfigFileHostEntryResolver;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.keyverifier.DefaultKnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Default {@link SshSessionFactory}, implemented with Apache MINA SSHD.
 *
 * <p>Behaviour that depends on the user's home directory is evaluated on every call to
 * {@link #createSession}, so a changed {@code user.home} takes effect immediately.</p>
 *
 * <h2>Host key verification</h2>
 * <ul>
 *   <li>If {@code ~/.ssh/known_hosts} exists and the host is not {@code localhost}/{@code 127.0.0.1},
 *       the server key must match an entry of that file. Unknown hosts and changed keys are
 *       rejected (there is no interactive prompt that could accept them).</li>
 *   <li>Otherwise the server key is accepted without verification. This is the behaviour of the
 *       JSch based factory this class replaced and is kept unchanged on purpose; tightening it is a
 *       separate security decision.</li>
 * </ul>
 *
 * <h2>Authentication</h2>
 * <p>Besides an explicit password or private key, keys of a running {@code ssh-agent}
 * ({@code SSH_AUTH_SOCK}, Unix only) are tried, and so are the default identities
 * ({@code ~/.ssh/id_*}) that Apache SSHD loads on its own, as {@code ssh(1)} does. The JSch based factory
 * this class replaced did neither.</p>
 *
 * <h2>Client configuration</h2>
 * <p>If {@code ~/.ssh/config} exists it is applied ({@code HostName}, {@code User}, {@code Port},
 * {@code IdentityFile}, ...) before connecting.</p>
 */
public class ApacheSshdSessionFactory implements SshSessionFactory {

    private final Path agentSocket;

    /** Uses the {@code ssh-agent} named by the {@code SSH_AUTH_SOCK} environment variable, if any. */
    public ApacheSshdSessionFactory() {
        this(agentSocketFromEnvironment());
    }

    /**
     * @param agentSocket the {@code ssh-agent} socket to authenticate with, or null to use no agent
     */
    public ApacheSshdSessionFactory(Path agentSocket) {
        this.agentSocket = agentSocket;
    }

    private static Path agentSocketFromEnvironment() {
        String env = System.getenv("SSH_AUTH_SOCK");
        return env == null || env.isBlank() ? null : Paths.get(env);
    }

    @Override
    public SshSession createSession(String host, int port, String username, String password,
                                    String privateKeyPath, String passphrase) throws Exception {
        SshClient client = SshClient.setUpDefaultClient();

        Path sshDir = Paths.get(System.getProperty("user.home"), ".ssh");
        Path knownHosts = sshDir.resolve("known_hosts");
        boolean isLocal = "localhost".equals(host) || "127.0.0.1".equals(host);
        if (Files.exists(knownHosts) && !isLocal) {
            // strict=false: do not refuse a known_hosts file merely because of its permission bits.
            // With strict=true SSHD fails to load a group-writable file (mode 664, the default under
            // umask 002) and then rejects every server key. The JSch based factory never checked the
            // permissions either. A file that cannot be read still rejects the connection.
            client.setServerKeyVerifier(
                    new DefaultKnownHostsServerKeyVerifier(RejectAllServerKeyVerifier.INSTANCE, false, knownHosts));
        } else {
            client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        }

        if (agentSocket != null) {
            client.setAgentFactory(new UnixSocketSshAgentFactory(agentSocket));
        }

        Path sshConfig = sshDir.resolve("config");
        if (Files.exists(sshConfig)) {
            client.setHostConfigEntryResolver(new ConfigFileHostEntryResolver(sshConfig));
        }

        return new ApacheSshdSession(client, username, host, port, password, privateKeyPath, passphrase);
    }
}
