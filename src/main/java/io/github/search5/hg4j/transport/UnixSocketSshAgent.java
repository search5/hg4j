package io.github.search5.hg4j.transport;

import org.apache.sshd.agent.common.AbstractAgentProxy;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.buffer.ByteArrayBuffer;
import org.apache.sshd.common.util.threads.ThreadUtils;

import java.io.EOFException;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;

/**
 * Client of an OpenSSH-compatible {@code ssh-agent}, talking over a Unix domain socket
 * ({@code SSH_AUTH_SOCK}) with the JDK's own socket support (Java 16+), so no native library is needed.
 *
 * <p>Apache SSHD's own Unix agent client requires the {@code tomcat-apr} native library, which is why
 * this class exists. The wire format is the standard agent protocol: every message is a 4-byte
 * big-endian length followed by that many bytes. {@link AbstractAgentProxy} builds the requests and
 * parses the answers; this class only moves the framed bytes.</p>
 *
 * <p>Requests are serialized (the agent protocol is strictly request/response per connection).</p>
 */
public class UnixSocketSshAgent extends AbstractAgentProxy {

    /** Upper bound for one agent answer; a larger length prefix is treated as a corrupted stream. */
    static final int MAX_MESSAGE_BYTES = 256 * 1024;

    private final Path socketPath;
    private final SocketChannel channel;

    /**
     * @param socketPath the agent socket
     * @throws IOException if the socket cannot be connected
     */
    public UnixSocketSshAgent(Path socketPath) throws IOException {
        super(ThreadUtils.newSingleThreadExecutor("hg4j-ssh-agent"));
        this.socketPath = socketPath;
        SocketChannel opened = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            opened.connect(UnixDomainSocketAddress.of(socketPath));
        } catch (IOException | RuntimeException e) {
            try {
                opened.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
        this.channel = opened;
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    protected synchronized Buffer request(Buffer buffer) throws IOException {
        ByteBuffer out = ByteBuffer.wrap(buffer.array(), buffer.rpos(), buffer.available());
        while (out.hasRemaining()) {
            channel.write(out);
        }

        ByteBuffer lengthBytes = ByteBuffer.allocate(Integer.BYTES);
        readFully(lengthBytes);
        lengthBytes.flip();
        int length = lengthBytes.getInt();
        if (length < 0 || length > MAX_MESSAGE_BYTES) {
            throw new IOException("Illogical ssh-agent message length " + length + " from " + socketPath);
        }
        ByteBuffer body = ByteBuffer.allocate(length);
        readFully(body);
        return new ByteArrayBuffer(body.array(), 0, length);
    }

    private void readFully(ByteBuffer into) throws IOException {
        while (into.hasRemaining()) {
            if (channel.read(into) < 0) {
                throw new EOFException("ssh-agent " + socketPath + " closed the connection");
            }
        }
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            channel.close();
        } catch (IOException e) {
            failure = e;
        }
        try {
            super.close();
        } catch (IOException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[socket=" + socketPath + "]";
    }
}
