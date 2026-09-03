package io.github.search5.hg4j.transport;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;

/**
 * An independent SSH session abstraction interface that does not depend on a specific SSH library (such as JSch or Apache MINA SSHD).
 */
public interface SshSession extends AutoCloseable {
    /**
     * Establishes a connection to the SSH server.
     *
     * @param timeoutMs Connection timeout in milliseconds
     * @throws Exception If a connection error occurs
     */
    void connect(int timeoutMs) throws Exception;

    /**
     * Requests an execution channel command to be run on the remote server.
     *
     * @param command The shell command to execute
     * @param timeoutMs Channel open timeout in milliseconds
     * @throws Exception If an error occurs during command execution
     */
    void executeCommand(String command, int timeoutMs) throws Exception;

    /**
     * Obtains the server output stream (standard output) of the running command.
     *
     * @return InputStream to receive standard output
     * @throws IOException If an error occurs while obtaining the stream
     */
    InputStream getInputStream() throws IOException;

    /**
     * Obtains the server input stream (standard input) of the running command.
     *
     * @return OutputStream to send standard input
     * @throws IOException If an error occurs while obtaining the stream
     */
    OutputStream getOutputStream() throws IOException;

    /**
     * Closes the SSH session and execution channel.
     *
     * @throws IOException If an I/O error occurs during closure
     */
    @Override
    void close() throws IOException;
}
