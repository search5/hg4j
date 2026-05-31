package io.github.search5.hg4j.core;

import org.apache.sshd.server.SshServer;
import io.github.search5.hg4j.transport.HgSshClient;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class HgSshClientTest {

    private SshServer sshServer;
    private int port;

    @BeforeEach
    public void startSshServer() throws Exception {
        sshServer = SshServer.setUpDefaultServer();
        sshServer.setPort(0); // auto-assign ephemeral port
        
        // Simple host key provider
        Path tempKey = Files.createTempFile("ssh_host_", ".key");
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempKey));
        Files.deleteIfExists(tempKey);

        // Simple auth: testuser / testpass
        sshServer.setPasswordAuthenticator((username, password, session) -> 
            "testuser".equals(username) && "testpass".equals(password)
        );

        // CommandFactory to mock 'hg -R /test/repo serve --stdio'
        sshServer.setCommandFactory(new CommandFactory() {
            @Override
            public Command createCommand(org.apache.sshd.server.channel.ChannelSession channel, String command) throws IOException {
                return new MockHgStdioCommand(command);
            }
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

    @Test
    public void testSshClientConnectAndGetHeads() throws Exception {
        // Given: SSH URL
        String sshUrl = "ssh://testuser@127.0.0.1:" + port + "/test/repo";
        
        // When: Initialize HgSshClient and set password
        HgSshClient client = new HgSshClient(sshUrl);
        client.setPassword("testpass");
        
        try {
            // Then: verify capabilities are parsed on connection
            List<String> caps = client.getCapabilities();
            assertNotNull(caps);
            assertTrue(caps.contains("heads"));
            assertTrue(caps.contains("getbundle"));

            // Then: verify getHeads requests are multiplexed correctly over SSH stdio
            List<String> heads = client.getHeads();
            assertNotNull(heads);
            assertEquals(1, heads.size());
            assertEquals("0000000000000000000000000000000000000000", heads.get(0));
        } finally {
            client.close();
        }
    }

    @Test
    public void testCustomSshSessionFactoryPluggability() throws Exception {
        io.github.search5.hg4j.transport.SshSessionFactory originalFactory = HgSshClient.getSshSessionFactory();
        assertNotNull(originalFactory);
        assertInstanceOf(io.github.search5.hg4j.transport.JschSessionFactory.class, originalFactory);

        final int customPort = 22222;
        final boolean[] openSessionCalled = {false};
        io.github.search5.hg4j.transport.SshSessionFactory mockFactory = new io.github.search5.hg4j.transport.SshSessionFactory() {
            @Override
            public io.github.search5.hg4j.transport.SshSession createSession(String host, int port, String username, String password, String privateKeyPath, String passphrase) throws Exception {
                openSessionCalled[0] = true;
                assertEquals("127.0.0.1", host);
                assertEquals(customPort, port); // custom port verify
                assertEquals("mockuser", username);
                assertEquals("mockpass", password);
                
                // Return mock SshSession
                return new io.github.search5.hg4j.transport.SshSession() {
                    @Override
                    public void connect(int timeoutMs) throws Exception {}

                    @Override
                    public void executeCommand(String command, int timeoutMs) throws Exception {}

                    @Override
                    public java.io.InputStream getInputStream() throws java.io.IOException {
                        return new java.io.ByteArrayInputStream("capabilities: heads\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }

                    @Override
                    public java.io.OutputStream getOutputStream() throws java.io.IOException {
                        return new java.io.ByteArrayOutputStream();
                    }

                    @Override
                    public void close() throws java.io.IOException {}
                };
            }
        };

        try {
            // 1. Verify injection
            HgSshClient.setSshSessionFactory(mockFactory);
            assertEquals(mockFactory, HgSshClient.getSshSessionFactory());

            // 2. Verify execution (check if createSession is invoked with correct parameters)
            String sshUrl = "ssh://mockuser:mockpass@127.0.0.1:" + customPort + "/test/repo";
            HgSshClient client = new HgSshClient(sshUrl);
            
            try {
                java.util.List<String> caps = client.getCapabilities(); // 트리거 ensureConnected()
                assertNotNull(caps);
                assertTrue(caps.contains("heads"));
            } finally {
                client.close();
            }

            assertTrue(openSessionCalled[0], "SshSessionFactory's createSession must be actively invoked");
        } finally {
            // 3. Restore
            HgSshClient.setSshSessionFactory(originalFactory);
        }
    }

    /**
     * Mock Command simulating 'hg serve --stdio' protocol behaviour.
     */
    private static class MockHgStdioCommand implements Command, Runnable {
        private final String command;
        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private org.apache.sshd.server.ExitCallback callback;
        private Thread thread;

        public MockHgStdioCommand(String command) {
            this.command = command;
        }

        @Override
        public void setInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public void setOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void setErrorStream(OutputStream err) {
            this.err = err;
        }

        @Override
        public void setExitCallback(org.apache.sshd.server.ExitCallback callback) {
            this.callback = callback;
        }

        @Override
        public void start(org.apache.sshd.server.channel.ChannelSession session, org.apache.sshd.server.Environment env) throws IOException {
            thread = new Thread(this);
            thread.start();
        }

        @Override
        public void destroy(org.apache.sshd.server.channel.ChannelSession session) throws Exception {
            if (thread != null) {
                thread.interrupt();
            }
        }

        @Override
        public void run() {
            try {
                // Mercurial stdio protocol starts by writing capabilities: to stdout
                // Let's check command matching to simulate real hg serve --stdio
                if (command == null || !command.contains("serve --stdio")) {
                    err.write("Invalid mercurial ssh command\n".getBytes(StandardCharsets.UTF_8));
                    err.flush();
                    callback.onExit(1);
                    return;
                }

                // Write capabilities header
                out.write("capabilities: heads getbundle changegroup\n".getBytes(StandardCharsets.UTF_8));
                out.flush();

                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                while (true) {
                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    line = line.trim();
                    if ("heads".equals(line)) {
                        // Mock heads response: 40-char hash + newline
                        out.write("0000000000000000000000000000000000000000\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } else if (line.startsWith("changegroup")) {
                        // Mock empty changegroup chunking
                        // Standard chunk format (4-byte length + payload)
                        // Write empty payload terminal chunk (0000)
                        out.write(new byte[]{0, 0, 0, 0});
                        out.flush();
                    } else if ("close".equals(line) || "exit".equals(line)) {
                        break;
                    }
                }
                callback.onExit(0);
            } catch (Exception e) {
                callback.onExit(1, e.getMessage());
            }
        }
    }
}
