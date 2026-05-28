package org.hg4j.core;

import org.apache.sshd.server.SshServer;
import org.hg4j.transport.HgSshClient;
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
