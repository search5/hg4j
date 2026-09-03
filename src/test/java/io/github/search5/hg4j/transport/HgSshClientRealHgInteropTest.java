package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.PullCommand;
import io.github.search5.hg4j.api.PushCommand;
import io.github.search5.hg4j.bundle.Bundle2Parser;
import io.github.search5.hg4j.api.HgCommit;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.api.LogCommand;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import org.apache.sshd.server.Environment;

/**
 * Verifies {@link HgSshClient} against the real {@code hg} CLI as an SSH server (spawned as a
 * genuine subprocess -- {@code hg -R <path> serve --stdio} -- behind an embedded Apache MINA SSHD
 * channel), the inverse of {@link HgSshWireServerRealHgInteropTest} (which drives real hg as the
 * client). Closes the specific gap this session's SSH work set out to close: does changegroup
 * negotiation over SSH actually converge on a real server's advertised version, verified the same
 * way the HTTP transport bug was found and fixed -- against a real server, not just against
 * hg4j's own (however carefully spec-derived) implementation of the other end.
 *
 * <p>{@link HgSshClient} previously spoke an entirely invented line-based wire protocol (fixed
 * 2026-09-03, see {@link HgSshWireServer}'s and this class's own javadoc history) that would have
 * deadlocked immediately against a real hg SSH server -- this test is the first time that fix has
 * been checked against the genuine article rather than against hg4j's own (already corrected, but
 * still homegrown) server implementation.</p>
 */
@Tag("interop")
public class HgSshClientRealHgInteropTest {

    private SshServer sshServer;
    private int port;

    @BeforeEach
    public void setUp(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");

        sshServer = SshServer.setUpDefaultServer();
        sshServer.setPort(0);
        Path hostKey = tempDir.resolve("host_key");
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
        sshServer.setPublickeyAuthenticator(AcceptAllPublickeyAuthenticator.INSTANCE);
        sshServer.setPasswordAuthenticator((username, password, session) -> true);
        sshServer.setCommandFactory((channel, command) -> new RealHgServeCommand(command));
        sshServer.start();
        port = sshServer.getPort();
    }

    @AfterEach
    public void tearDown() throws IOException {
        if (sshServer != null) {
            sshServer.stop(true);
        }
    }

    private String sshUrl(File repoDir) {
        return "ssh://testuser@127.0.0.1:" + port + repoDir.getAbsolutePath();
    }

    @Test
    public void getBundleActuallyNegotiatesAgainstARealHgSshServer(@TempDir Path tempDir) throws Exception {
        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgTestUtils.hg(tempDir.toFile(), "init", serverRepoDir.getAbsolutePath());
        Files.writeString(new File(serverRepoDir, "a.txt").toPath(), "hello real ssh");
        HgTestUtils.hg(serverRepoDir, "add");
        HgTestUtils.hg(serverRepoDir, "commit", "-m", "v1", "-u", "dev");

        HgSshClient client = new HgSshClient(sshUrl(serverRepoDir));
        client.setPassword("testpass");
        try {
            List<String> caps = client.getCapabilities();
            assertNotNull(caps);
            assertTrue(caps.contains("getbundle"), "real hg server must advertise getbundle: " + caps);

            List<String> heads = client.getHeads();
            assertEquals(1, heads.size());

            byte[] bundleBytes = client.getBundle(List.of(), heads,
                    List.of("HG20", Bundle2Parser.buildChangegroupBundleCaps("01,02,03,04,05")));

            assertTrue(bundleBytes.length >= 4 && bundleBytes[0] == 'H' && bundleBytes[1] == 'G'
                            && bundleBytes[2] == '2' && bundleBytes[3] == '0',
                    "expected a bundle2-framed response (HG20 magic)");
            Bundle2Parser.ExtractedBundle2 ext = Bundle2Parser.extractChangegroupDetailed(
                    new java.io.ByteArrayInputStream(bundleBytes));
            assertNotEquals("01", ext.cgVersion,
                    "negotiation must not silently degrade to legacy bundle1/cg1 against a real SSH server");
        } finally {
            client.close();
        }
    }

    @Test
    public void pullEndToEndFromARealHgSshServer(@TempDir Path tempDir) throws Exception {
        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgTestUtils.hg(tempDir.toFile(), "init", serverRepoDir.getAbsolutePath());
        Files.writeString(new File(serverRepoDir, "a.txt").toPath(), "hello real ssh pull");
        HgTestUtils.hg(serverRepoDir, "add");
        HgTestUtils.hg(serverRepoDir, "commit", "-m", "v1", "-u", "dev");

        File clientDir = tempDir.resolve("client_repo").toFile();
        HgRepository client = Hg.init().setDirectory(clientDir).call();
        new PullCommand(client).setSource(sshUrl(serverRepoDir)).call();

        // Plain pull (no -u) never touches the working directory, matching real hg's own default
        // -- history transfer is what's under test here, not checkout.
        List<HgCommit> log = new LogCommand(client).call();
        assertEquals(1, log.size());
        assertEquals("v1", log.get(0).getMessage());
    }

    @Test
    public void pushEndToEndToARealHgSshServer(@TempDir Path tempDir) throws Exception {
        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgTestUtils.hg(tempDir.toFile(), "init", serverRepoDir.getAbsolutePath());
        Files.writeString(new File(serverRepoDir, "seed.txt").toPath(), "seed");
        HgTestUtils.hg(serverRepoDir, "add");
        HgTestUtils.hg(serverRepoDir, "commit", "-m", "seed", "-u", "dev");

        File clientDir = tempDir.resolve("client_repo").toFile();
        HgRepository client = Hg.init().setDirectory(clientDir).call();
        new PullCommand(client).setSource(sshUrl(serverRepoDir)).call();

        String marker = "pushed-from-hg4j-" + System.nanoTime();
        Files.writeString(new File(clientDir, marker + ".txt").toPath(), "hg4j real ssh push");
        new AddCommand(client).addFile(marker + ".txt").call();
        new CommitCommand(client).setAuthor("hg4j <hg4j@example.com>").setMessage(marker).call();

        new PushCommand(client).setDestination(sshUrl(serverRepoDir)).call();

        String log = HgTestUtils.hg(serverRepoDir, "log", "-T", "{desc}\n");
        assertTrue(log.contains(marker), "real hg server must see the pushed commit, log was: " + log);
    }

    /** Server-side {@code Command} adapter that execs the REAL {@code hg} CLI's own {@code serve
     * --stdio} as a subprocess and pipes the SSH channel's stdin/stdout straight through to it --
     * as opposed to {@link HgSshWireServer}, this is genuinely real hg on the other end. */
    private static class RealHgServeCommand implements Command {
        private static final Pattern REPO_PATH = Pattern.compile("-R\\s+'?([^'\\s]+)'?");

        private final String command;
        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback callback;
        private Thread pumpIn;
        private Thread pumpOut;
        private Process proc;

        RealHgServeCommand(String command) {
            this.command = command;
        }

        @Override public void setInputStream(InputStream in) { this.in = in; }
        @Override public void setOutputStream(OutputStream out) { this.out = out; }
        @Override public void setErrorStream(OutputStream err) { this.err = err; }
        @Override public void setExitCallback(ExitCallback callback) { this.callback = callback; }

        @Override
        public void start(ChannelSession session, Environment env) throws IOException {
            Matcher m = REPO_PATH.matcher(command == null ? "" : command);
            if (!m.find()) {
                try {
                    err.write(("bad command: " + command + "\n").getBytes());
                    err.flush();
                } catch (IOException ignored) {
                }
                callback.onExit(1);
                return;
            }
            String repoPath = m.group(1);
            ProcessBuilder pb = new ProcessBuilder("hg", "-R", repoPath, "serve", "--stdio");
            pb.redirectErrorStream(false);
            proc = pb.start();

            pumpIn = new Thread(() -> pump(in, proc.getOutputStream()), "real-hg-serve-stdin");
            pumpOut = new Thread(() -> pump(proc.getInputStream(), out), "real-hg-serve-stdout");
            pumpIn.setDaemon(true);
            pumpOut.setDaemon(true);
            pumpIn.start();
            pumpOut.start();

            Thread waiter = new Thread(() -> {
                try {
                    int code = proc.waitFor();
                    pumpOut.join(2000);
                    callback.onExit(code);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }, "real-hg-serve-wait");
            waiter.setDaemon(true);
            waiter.start();
        }

        private static void pump(InputStream src, OutputStream dst) {
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = src.read(buf)) != -1) {
                    dst.write(buf, 0, n);
                    dst.flush();
                }
            } catch (IOException ignored) {
                // channel/process torn down -- nothing more to pump
            }
        }

        @Override
        public void destroy(ChannelSession session) {
            if (proc != null) {
                proc.destroy();
            }
        }
    }
}
