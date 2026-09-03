package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.PullCommand;
import io.github.search5.hg4j.api.PushCommand;
import io.github.search5.hg4j.lib.HgRepository;
import org.apache.sshd.server.Environment;
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backlog 22, "범위(포함)" group 2 (SSH half), the "off" side specifically. The "on" side (real
 * hg's SSH server always advertises {@code unbundlehash} unconditionally -- confirmed by reading
 * {@code mercurial/wireprotov1server.py}'s {@code wireprotocaps} list directly, 2026-09-03 -- so
 * there is no config that turns it off) is already exercised end to end by
 * {@link HgSshClientRealHgInteropTest#pushEndToEndToARealHgSshServer} against a genuinely
 * unmodified real hg server, and the hashed wire-encoding itself is unit-verified against a mock
 * server in {@code HgSshUnbundleHashTest}.
 *
 * <p>What was NOT verified before this test: that when the capability is absent, hg4j's
 * {@link HgSshClient} correctly falls back to sending the LITERAL (un-hashed) heads list, and that
 * a real hg server's own {@code exchange.check_heads()} still accepts that literal form -- i.e.
 * the fallback path isn't just "doesn't crash" but actually round-trips against genuine hg.
 * Since real hg has no config toggle for this (it's unconditional), this test uses the same
 * "man-in-the-middle" technique as {@link CapabilityStrippingHttpProxy} on the HTTP side, adapted
 * to SSH's raw framed-response byte protocol: a {@link Command} adapter that pipes a real
 * {@code hg serve --stdio} subprocess through unmodified EXCEPT for the very first framed response
 * (the {@code hello} command's {@code capabilities: ...} line), from which it strips the
 * {@code unbundlehash} token before forwarding. Real hg itself is never touched.</p>
 */
@Tag("interop")
public class HgSshUnbundleHashOffInteropTest {

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
        sshServer.setCommandFactory((channel, command) -> new UnbundlehashStrippingHgServeCommand(command));
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
    public void unbundlehashOffForcedRealSshPushStillSucceeds(@TempDir Path tempDir) throws Exception {
        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgTestUtils.hg(tempDir.toFile(), "init", serverRepoDir.getAbsolutePath());
        Files.writeString(new File(serverRepoDir, "seed.txt").toPath(), "seed");
        HgTestUtils.hg(serverRepoDir, "add");
        HgTestUtils.hg(serverRepoDir, "commit", "-m", "seed", "-u", "dev");

        HgSshClient probe = new HgSshClient(sshUrl(serverRepoDir));
        probe.setPassword("testpass");
        try {
            List<String> caps = probe.getCapabilities();
            assertFalse(caps.contains("unbundlehash"),
                    "sanity: the filtering SSH command must actually have stripped unbundlehash, got: " + caps);
        } finally {
            probe.close();
        }

        File clientDir = tempDir.resolve("client_repo").toFile();
        HgRepository client = Hg.init().setDirectory(clientDir).call();
        new PullCommand(client).setSource(sshUrl(serverRepoDir)).call();

        String marker = "unbundlehash-off-ssh-" + System.nanoTime();
        Files.writeString(new File(clientDir, marker + ".txt").toPath(), "hg4j real ssh push, unbundlehash off");
        new AddCommand(client).addFile(marker + ".txt").call();
        new CommitCommand(client).setAuthor("hg4j <hg4j@example.com>").setMessage(marker).call();

        // With unbundlehash hidden, HgSshClient#push (via NodeIdUtil#computeUnbundleHeadsWireValue)
        // must send the plain literal heads list instead of the sha1-hashed sentinel -- and the
        // real server must still accept it as a normal, non-degraded push.
        new PushCommand(client).setDestination(sshUrl(serverRepoDir)).call();

        String log = HgTestUtils.hg(serverRepoDir, "log", "-T", "{desc}\n");
        assertTrue(log.contains(marker), "real hg server must see the pushed commit, log was: " + log);
    }

    /**
     * Server-side {@code Command} adapter that execs a real {@code hg serve --stdio} subprocess
     * (same as {@link HgSshClientRealHgInteropTest}'s {@code RealHgServeCommand}) and pipes it
     * through byte-for-byte, EXCEPT the very first framed response off its stdout (real hg's
     * {@code hello} reply, containing the {@code capabilities: ...} line), which is decoded,
     * stripped of the {@code unbundlehash} token, and re-framed before forwarding.
     */
    private static class UnbundlehashStrippingHgServeCommand implements Command {
        private static final Pattern REPO_PATH = Pattern.compile("-R\\s+'?([^'\\s]+)'?");

        private final String command;
        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback callback;
        private Process proc;

        UnbundlehashStrippingHgServeCommand(String command) {
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

            Thread pumpIn = new Thread(() -> pump(in, proc.getOutputStream()), "unbundlehash-filter-stdin");
            Thread pumpOut = new Thread(() -> filterHelloThenPump(proc.getInputStream(), out), "unbundlehash-filter-stdout");
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
            }, "unbundlehash-filter-wait");
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

        /**
         * Reads and rewrites ONLY the first framed response (real hg's {@code hello} reply, per
         * the same {@code "<len>\n<bytes>"} framing {@link HgSshClient#readFramedResponse()}
         * implements on the client side), then falls back to a plain byte-for-byte pump for
         * everything else (the {@code between} response that immediately follows, and the whole
         * rest of the session).
         */
        private static void filterHelloThenPump(InputStream src, OutputStream dst) {
            try {
                String lenLine = readLine(src);
                if (lenLine == null) {
                    return;
                }
                int len = Integer.parseInt(lenLine.trim());
                byte[] helloBytes = readExactly(src, len);
                String helloText = new String(helloBytes, StandardCharsets.UTF_8);
                String rewritten = stripUnbundlehash(helloText);
                byte[] rewrittenBytes = rewritten.getBytes(StandardCharsets.UTF_8);

                dst.write((rewrittenBytes.length + "\n").getBytes(StandardCharsets.US_ASCII));
                dst.write(rewrittenBytes);
                dst.flush();

                pump(src, dst);
            } catch (Exception ignored) {
                // channel/process torn down mid-handshake -- nothing more to pump
            }
        }

        private static String stripUnbundlehash(String helloText) {
            String[] lines = helloText.split("\n", -1);
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (line.startsWith("capabilities:")) {
                    String capString = line.substring("capabilities:".length()).trim();
                    List<String> kept = new ArrayList<>();
                    if (!capString.isEmpty()) {
                        for (String tok : capString.split("\\s+")) {
                            if (!"unbundlehash".equals(tok)) {
                                kept.add(tok);
                            }
                        }
                    }
                    result.append("capabilities: ").append(String.join(" ", kept));
                } else {
                    result.append(line);
                }
                if (i < lines.length - 1) {
                    result.append("\n");
                }
            }
            return result.toString();
        }

        private static String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int b;
            boolean any = false;
            while ((b = in.read()) != -1) {
                any = true;
                if (b == '\n') {
                    break;
                }
                baos.write(b);
            }
            if (!any) {
                return null;
            }
            return baos.toString(StandardCharsets.US_ASCII);
        }

        private static byte[] readExactly(InputStream in, int len) throws IOException {
            byte[] buf = new byte[len];
            int total = 0;
            while (total < len) {
                int got = in.read(buf, total, len - total);
                if (got == -1) {
                    throw new IOException("Unexpected EOF while reading the hello framed response");
                }
                total += got;
            }
            return buf;
        }

        @Override
        public void destroy(ChannelSession session) {
            if (proc != null) {
                proc.destroy();
            }
        }
    }
}
