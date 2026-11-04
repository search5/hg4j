package io.github.search5.hg4j.transport;

import org.apache.sshd.agent.SshAgent;
import org.apache.sshd.agent.SshAgentFactory;
import org.apache.sshd.agent.SshAgentServer;
import org.apache.sshd.common.FactoryManager;
import org.apache.sshd.common.channel.ChannelFactory;
import org.apache.sshd.common.session.ConnectionService;
import org.apache.sshd.common.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * {@link SshAgentFactory} that lets Apache SSHD authenticate with the keys of a running
 * {@code ssh-agent}, reached through {@link UnixSocketSshAgent}. Agent forwarding is not supported.
 *
 * <p>If the agent cannot be reached, a warning is logged and public key authentication simply goes on
 * without the agent's keys (which is what {@code ssh(1)} does). The login then still fails, with
 * {@link SshAuthenticationException}, if no other method succeeds; nothing is reported as a success.</p>
 */
public class UnixSocketSshAgentFactory implements SshAgentFactory {

    private static final Logger LOG = LoggerFactory.getLogger(UnixSocketSshAgentFactory.class);

    private final Path socketPath;

    /**
     * Creates a factory for the agent listening on the given Unix domain socket. The agent is contacted when an
     * SSH session asks for it, not at construction.
     * @param socketPath the path of the agent socket (the value of {@code SSH_AUTH_SOCK}); must not be null
     */
    public UnixSocketSshAgentFactory(Path socketPath) {
        this.socketPath = socketPath;
    }

    @Override
    public List<ChannelFactory> getChannelForwardingFactories(FactoryManager manager) {
        return Collections.emptyList();
    }

    @Override
    public SshAgent createClient(Session session, FactoryManager manager) throws IOException {
        UnixSocketSshAgent agent = null;
        try {
            agent = new UnixSocketSshAgent(socketPath);
            // Ask for the identities now: if the agent is unusable, fall back instead of failing later.
            agent.getIdentities();
            return agent;
        } catch (IOException | RuntimeException e) {
            LOG.warn("ssh-agent at {} is not usable ({}); continuing without it", socketPath, e.toString());
            if (agent != null) {
                try {
                    agent.close();
                } catch (IOException closeFailure) {
                    LOG.debug("closing the unusable ssh-agent connection failed", closeFailure);
                }
            }
            return null;
        }
    }

    @Override
    public SshAgentServer createServer(ConnectionService service) throws IOException {
        throw new UnsupportedOperationException("hg4j does not run an ssh-agent server");
    }
}
