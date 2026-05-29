package org.hg4j.api;

import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.hg4j.errors.HgAuthException;
import org.hg4j.errors.HgProtocolException;
import org.hg4j.transport.HgSshClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class HgSshTransportRoundtripTest {

    private SshServer sshServer;
    private int port;
    private Path tempKey;
    private volatile boolean failAuth = false;
    private volatile boolean sendInvalidHeader = false;
    private volatile boolean abruptEof = false;

    @BeforeEach
    public void setUp() throws Exception {
        failAuth = false;
        sendInvalidHeader = false;
        abruptEof = false;

        sshServer = SshServer.setUpDefaultServer();
        sshServer.setPort(0);
        tempKey = Files.createTempFile("ssh_mock_transport_test_", ".key");
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempKey));
        Files.deleteIfExists(tempKey);

        sshServer.setPasswordAuthenticator((username, password, session) -> !failAuth);
        sshServer.setCommandFactory((channel, command) -> new Command() {
            private InputStream in;
            private OutputStream out;
            private org.apache.sshd.server.ExitCallback callback;

            @Override public void setInputStream(InputStream in) { this.in = in; }
            @Override public void setOutputStream(OutputStream out) { this.out = out; }
            @Override public void setErrorStream(OutputStream err) {}
            @Override public void setExitCallback(org.apache.sshd.server.ExitCallback cb) { this.callback = cb; }

            @Override
            public void start(org.apache.sshd.server.channel.ChannelSession s, org.apache.sshd.server.Environment env) throws IOException {
                new Thread(() -> {
                    try {
                        if (sendInvalidHeader) {
                            out.write("invalid response format\n".getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            callback.onExit(0);
                            return;
                        }

                        // Send standard mock capabilities
                        out.write("capabilities: lookup changegroupsubsets branchmap getbundle\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();

                        if (abruptEof) {
                            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
                            String line = reader.readLine();
                            if (line != null && line.trim().equals("getbundle")) {
                                reader.readLine(); // read extra parameters line
                                out.write(new byte[]{0, 0}); // incomplete chunk length (EOF simulation)
                                out.flush();
                            }
                            callback.onExit(0);
                            return;
                        }

                        // Stdio loop command handling if needed
                        byte[] buffer = new byte[1024];
                        while (in.read(buffer) != -1) {
                            // Dummy echo/acknowledgement
                        }
                        callback.onExit(0);
                    } catch (IOException ignored) {}
                }).start();
            }

            @Override public void destroy(org.apache.sshd.server.channel.ChannelSession s) {}
        });

        sshServer.start();
        port = sshServer.getPort();
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (sshServer != null) {
            sshServer.stop(true);
        }
        if (tempKey != null) {
            Files.deleteIfExists(tempKey);
        }
    }

    @Test
    public void testSshAuthFailure(@TempDir Path tempDir) throws Exception {
        failAuth = true;

        String url = "ssh://hg4juser@127.0.0.1:" + port + "/test/repo";
        try (HgSshClient client = new HgSshClient(url)) {
            client.setPassword("wrongpass");
            assertThrows(HgAuthException.class, client::getCapabilities, 
                    "Authentication failure must throw HgAuthException");
        }
    }

    @Test
    public void testSshInvalidHeaderThrowsHgProtocolException() throws Exception {
        sendInvalidHeader = true;

        String url = "ssh://hg4juser@127.0.0.1:" + port + "/test/repo";
        try (HgSshClient client = new HgSshClient(url)) {
            client.setPassword("any");
            assertThrows(HgProtocolException.class, client::getCapabilities, 
                    "No capabilities/invalid header must throw HgProtocolException");
        }
    }

    @Test
    public void testSshAbruptEofDuringChunkRead() throws Exception {
        abruptEof = true;

        String url = "ssh://hg4juser@127.0.0.1:" + port + "/test/repo";
        try (HgSshClient client = new HgSshClient(url)) {
            client.setPassword("any");
            client.getCapabilities(); // Should succeed to parse capabilities
            
            // Now attempt getBundle which reads chunked stream
            assertThrows(HgProtocolException.class, 
                    () -> client.getBundle(List.of(), List.of("head"), List.of("bundle2")),
                    "Abrupt stream close must throw HgProtocolException");
        }
    }
}
