package io.github.search5.hg4j.transport;

import com.jcraft.jsch.*;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;

/**
 * Adapter implementation wrapping JSch library's Session and ChannelExec with the SshSession abstract interface.
 */
public class JschSshSession implements SshSession {
    private final Session session;
    private ChannelExec channel;
    private InputStream in;
    private OutputStream out;

    public JschSshSession(Session session) {
        this.session = session;
    }

    @Override
    public void connect(int timeoutMs) throws Exception {
        if (session != null && !session.isConnected()) {
            session.connect(timeoutMs);
        }
    }

    @Override
    public void executeCommand(String command, int timeoutMs) throws Exception {
        if (session == null || !session.isConnected()) {
            throw new IllegalStateException("SSH Session is not connected");
        }
        channel = (ChannelExec) session.openChannel("exec");
        channel.setAgentForwarding(true); // Enable SSH Agent forwarding
        channel.setCommand(command);
        this.in = channel.getInputStream();
        this.out = channel.getOutputStream();
        channel.connect(timeoutMs);
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return this.in;
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return this.out;
    }

    @Override
    public void close() throws IOException {
        if (channel != null) {
            try {
                channel.disconnect();
            } catch (Exception ignored) {}
            channel = null;
        }
        if (session != null) {
            try {
                session.disconnect();
            } catch (Exception ignored) {}
        }
    }
}
