package io.github.search5.hg4j.transport;

import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Embeds an Apache MINA SSHD server whose command factory spawns the real, host-installed
 * {@code hg} CLI's own {@code serve --stdio} as a subprocess -- identical setup to
 * {@link HgWireProtocolMatrixTest#sshMatrixPullAndPushRoundTrip}, extracted here so the wave-5
 * sibling matrix tests ({@code Fetch}/{@code Incoming}/{@code Outgoing}/{@code Clonebundles}/
 * {@code NarrowClone}) can reuse it instead of reimplementing the SSHD wiring five more times.
 *
 * <p>Real hg's own {@code serve --stdio} refuses any extra CLI args spliced in around
 * {@code -R <path>} ("abort: potentially unsafe serve --stdio invocation") -- a security guard
 * against argument injection through restricted SSH {@code command=} invocations (confirmed
 * 2026-09-04 by reproducing it manually). So per-combo settings (e.g. the compression engine) must
 * be baked into the target repo's own {@code .hg/hgrc} before this server ever runs, not passed as
 * a spliced-in {@code --config} flag.
 */
final class SshMatrixServer implements AutoCloseable {

    private final SshServer sshServer;
    private final int port;
    private final Path tempDir;

    private SshMatrixServer(SshServer sshServer, int port, Path tempDir) {
        this.sshServer = sshServer;
        this.port = port;
        this.tempDir = tempDir;
    }

    static SshMatrixServer start(Path tempDir) throws IOException {
        SshServer sshServer = SshServer.setUpDefaultServer();
        sshServer.setPort(0);
        Path hostKey = tempDir.resolve("host_key_" + System.nanoTime());
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
        sshServer.setPublickeyAuthenticator(AcceptAllPublickeyAuthenticator.INSTANCE);
        sshServer.setPasswordAuthenticator((username, password, session) -> true);
        sshServer.setCommandFactory((channel, command) -> new RealHgServeCommand(command));
        sshServer.start();
        return new SshMatrixServer(sshServer, sshServer.getPort(), tempDir);
    }

    String url(File repoDir) {
        return "ssh://testuser@127.0.0.1:" + port + repoDir.getAbsolutePath();
    }

    /**
     * Same target as {@link #url}, but in the URL form the REAL {@code hg} CLI's own {@code
     * sshpeer} needs: real hg's ssh:// convention treats a <em>single</em> leading slash as
     * relative to the remote login user's home directory and a <em>double</em> leading slash as
     * absolute ({@code mercurial/sshpeer.py}, also documented on {@link
     * HgSshWireServerRealHgInteropTest#sshUrl}) -- {@link #url} deliberately doesn't follow that
     * convention (hg4j's own {@code HgSshClient} just uses the path after the first slash
     * literally, so a single slash already round-trips correctly for it), which breaks when the
     * REAL {@code hg} CLI is the one parsing the URL: it would `cd` to a nonexistent path
     * relative to the SSH login user's home directory instead of this absolute temp-dir path,
     * failing the handshake with "no suitable response from remote hg" (confirmed 2026-09-05
     * while building {@code HgWireProtocolMatrixIncomingOutgoingTest}'s ground-truth checks --
     * pure test-harness URL-construction bug, not an hg4j production bug, since hg4j was never
     * the one parsing this form).
     */
    String realHgUrl(File repoDir) {
        return "ssh://testuser@127.0.0.1:" + port + "/" + repoDir.getAbsolutePath();
    }

    /**
     * Real hg's SSH client shells out to the actual, host-installed {@code ssh} binary (unlike
     * hg4j's own embedded-JSch SSH client, the only thing every OTHER caller of {@link #url} needs
     * to talk to). Any caller that additionally needs the REAL {@code hg} CLI itself to act as the
     * SSH client against this server (e.g. to compute ground truth for a comparison test) needs a
     * genuine keypair the embedded SSHD's {@code AcceptAllPublickeyAuthenticator} will accept, plus
     * host-key-checking disabled (this server's host key is a fresh ephemeral one every run, never
     * in anyone's {@code known_hosts}) -- exactly {@link HgSshWireServerRealHgInteropTest}'s
     * established setup. Pass the returned value as {@code --config ui.ssh=<value>} on every real
     * hg CLI invocation that connects to {@link #url}.
     */
    String realHgUiSshOverride() throws Exception {
        Path sshKeyFile = tempDir.resolve("id_test_" + System.nanoTime());
        runProcess("ssh-keygen", "-t", "rsa", "-b", "2048", "-f", sshKeyFile.toString(), "-N", "");
        return "ssh -i " + sshKeyFile
                + " -o StrictHostKeyChecking=no -o UserKnownHostsFile=" + tempDir.resolve("known_hosts_" + System.nanoTime())
                + " -o IdentitiesOnly=yes -p " + port;
    }

    static boolean isSshKeygenAvailable() {
        try {
            return new ProcessBuilder("ssh-keygen", "-?").start().waitFor() >= 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void runProcess(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        int code = p.waitFor();
        if (code != 0) {
            throw new AssertionError(String.join(" ", cmd) + " failed (" + code + "): " + new String(out));
        }
    }

    @Override
    public void close() {
        try {
            sshServer.stop(true);
        } catch (IOException ignored) {
        }
    }

    /** Identical to {@link HgWireProtocolMatrixTest}'s inner class of the same name. */
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
