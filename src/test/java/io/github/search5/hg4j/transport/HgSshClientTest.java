package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;

/**
 * hg4j self-consistency check for {@link HgSshClient}: drives it against {@link
 * HgSshWireServer} (already independently verified to speak real hg's actual v1 SSH wire
 * protocol against a real {@code hg} client, see {@link HgSshWireServerRealHgInteropTest}) over
 * a genuine embedded SSH session, exactly the same shape as {@link
 * HgHttpWireServerTest}/{@link HgArgProtocolTest} do for the HTTP transport. Real-hg-as-server
 * verification lives in {@code HgSshClientRealHgInteropTest}.
 *
 * <p>An earlier version of this file hand-rolled a fake "hg serve --stdio" protocol
 * implementation directly in the test (a simple line-based text format, with the server
 * proactively writing {@code "capabilities: ...\n"} before the client sent anything) that
 * matched {@link HgSshClient}'s own then-incorrect assumptions rather than real hg's actual
 * length-prefixed argument framing and {@code hello}/{@code between}-driven handshake — so it
 * never would have caught the framing bug fixed on 2026-09-03. Driving the real, independently
 * verified {@link HgSshWireServer} instead closes that gap.</p>
 */
public class HgSshClientTest {

    private SshServer sshServer;
    private int port;
    private HgRepository serverRepo;
    private byte[] headCommit;

    @BeforeEach
    public void startSshServer(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("server_repo").toFile();
        serverRepo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "hello ssh");
        new AddCommand(serverRepo).call();
        headCommit = new CommitCommand(serverRepo).setMessage("v1").setAuthor("dev").call();

        sshServer = SshServer.setUpDefaultServer();
        sshServer.setPort(0); // auto-assign ephemeral port

        Path tempKey = Files.createTempFile("ssh_host_", ".key");
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempKey));
        Files.deleteIfExists(tempKey);

        sshServer.setPasswordAuthenticator((username, password, session) ->
                "testuser".equals(username) && "testpass".equals(password)
        );

        sshServer.setCommandFactory((CommandFactory) (channel, command) -> new HgWireServerCommand(command, repoDir));

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
        String sshUrl = "ssh://testuser@127.0.0.1:" + port + "/test/repo";

        HgSshClient client = new HgSshClient(sshUrl);
        client.setPassword("testpass");

        try {
            List<String> caps = client.getCapabilities();
            assertNotNull(caps);
            // Wire1Commands.capabilitiesString() doesn't advertise "heads" as an explicit
            // capability token (real hg's own convention: it's an always-available baseline v1
            // command, not an optional capability) -- assert against what's actually there.
            assertTrue(caps.contains("getbundle"));
            assertTrue(caps.contains("lookup"));

            List<String> heads = client.getHeads();
            assertNotNull(heads);
            assertEquals(1, heads.size());
            assertEquals(NodeIdUtil.toHex(headCommit), heads.get(0));
        } finally {
            client.close();
        }
    }

    @Test
    public void testCustomSshSessionFactoryPluggability() throws Exception {
        SshSessionFactory originalFactory = HgSshClient.getSshSessionFactory();
        assertNotNull(originalFactory);
        assertInstanceOf(JschSessionFactory.class, originalFactory);

        final int customPort = 22222;
        final boolean[] openSessionCalled = {false};
        SshSessionFactory mockFactory = new SshSessionFactory() {
            @Override
            public SshSession createSession(String host, int port, String username, String password, String privateKeyPath, String passphrase) throws Exception {
                openSessionCalled[0] = true;
                assertEquals("127.0.0.1", host);
                assertEquals(customPort, port); // custom port verify
                assertEquals("mockuser", username);
                assertEquals("mockpass", password);

                return new SshSession() {
                    @Override
                    public void connect(int timeoutMs) throws Exception {}

                    @Override
                    public void executeCommand(String command, int timeoutMs) throws Exception {}

                    @Override
                    public InputStream getInputStream() throws IOException {
                        // Real hg's v1 SSH handshake response shape (see HgSshClient#performHandshake):
                        // a framed "hello" response containing the capabilities line, followed by a
                        // framed "between" response (a single "\n" byte for the null-range query).
                        String helloBody = "capabilities: heads\n";
                        byte[] helloBytes = helloBody.getBytes(StandardCharsets.UTF_8);
                        String betweenBody = "\n";
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        out.write((helloBytes.length + "\n").getBytes(StandardCharsets.US_ASCII));
                        out.write(helloBytes);
                        out.write((betweenBody.length() + "\n").getBytes(StandardCharsets.US_ASCII));
                        out.write(betweenBody.getBytes(StandardCharsets.US_ASCII));
                        return new ByteArrayInputStream(out.toByteArray());
                    }

                    @Override
                    public OutputStream getOutputStream() throws IOException {
                        return new ByteArrayOutputStream();
                    }

                    @Override
                    public void close() throws IOException {}
                };
            }
        };

        try {
            HgSshClient.setSshSessionFactory(mockFactory);
            assertEquals(mockFactory, HgSshClient.getSshSessionFactory());

            String sshUrl = "ssh://mockuser:mockpass@127.0.0.1:" + customPort + "/test/repo";
            HgSshClient client = new HgSshClient(sshUrl);

            try {
                List<String> caps = client.getCapabilities(); // triggers ensureConnected()
                assertNotNull(caps);
                assertTrue(caps.contains("heads"));
            } finally {
                client.close();
            }

            assertTrue(openSessionCalled[0], "SshSessionFactory's createSession must be actively invoked");
        } finally {
            HgSshClient.setSshSessionFactory(originalFactory);
        }
    }

    /** Server-side {@code Command} adapter attaching {@link HgSshWireServer} to the embedded SSHD server. */
    private static class HgWireServerCommand implements Command, Runnable {
        private static final Pattern REPO_PATH = Pattern.compile("-R\\s+'?([^'\\s]+)'?");

        private final String command;
        private final File fallbackRepoDir;
        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback callback;
        private Thread thread;

        HgWireServerCommand(String command, File fallbackRepoDir) {
            this.command = command;
            this.fallbackRepoDir = fallbackRepoDir;
        }

        @Override public void setInputStream(InputStream in) { this.in = in; }
        @Override public void setOutputStream(OutputStream out) { this.out = out; }
        @Override public void setErrorStream(OutputStream err) { this.err = err; }
        @Override public void setExitCallback(ExitCallback callback) { this.callback = callback; }

        @Override
        public void start(ChannelSession session, Environment env) {
            thread = new Thread(this, "hg-ssh-wire-test");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void destroy(ChannelSession session) {
            if (thread != null) {
                thread.interrupt();
            }
        }

        @Override
        public void run() {
            try {
                if (command == null || !command.contains("serve --stdio")) {
                    err.write("Invalid mercurial ssh command\n".getBytes(StandardCharsets.UTF_8));
                    err.flush();
                    callback.onExit(1);
                    return;
                }
                // The test URL's repo path ("/test/repo") is a placeholder -- always serve the
                // one real repository this test actually seeded, regardless of what path the
                // client requested (matches this file's prior mock behavior).
                Matcher m = REPO_PATH.matcher(command);
                File repoDir = m.find() ? new File(m.group(1)) : fallbackRepoDir;
                HgRepository repo = repoDir.isDirectory() && new File(repoDir, ".hg").isDirectory()
                        ? new HgRepository(repoDir) : new HgRepository(fallbackRepoDir);
                new HgSshWireServer(repo).handleConnection(in, out);
                callback.onExit(0);
            } catch (Exception e) {
                try {
                    err.write((e + "\n").getBytes(StandardCharsets.UTF_8));
                    err.flush();
                } catch (IOException ignored) {
                }
                callback.onExit(1);
            }
        }
    }
}
