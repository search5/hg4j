package com.github.search5.hg4j.transport;

import com.github.search5.hg4j.util.NodeIdUtil;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real hg's {@code unbundlehash} optimization (confirmed against Mercurial 7.2.4 source,
 * 2026-09-03): {@code mercurial/wireprotov1peer.py}'s {@code unbundle()} peer method --
 * <pre>
 * if heads != [b'force'] and self.capable(b'unbundlehash'):
 *     heads = wireprototypes.encodelist(
 *         [b'hashed', hashutil.sha1(b''.join(sorted(heads))).digest()])
 * else:
 *     heads = wireprototypes.encodelist(heads)
 * </pre>
 * and the server-side check that recognizes it, {@code mercurial/exchange.py}'s {@code
 * check_heads()}: accepts the push if the incoming heads value equals either the server's actual
 * current heads verbatim, or {@code [b'hashed', sha1(b''.join(sorted(repo.heads()))).digest()]}
 * -- i.e. a client that supports this optimization may send a 20-byte SHA1 digest of its believed
 * remote heads instead of the (potentially much longer) literal list, and the server accepts it
 * as equivalent as long as the digest actually matches its own current heads.
 *
 * <p>This is a wire-encoding optimization only when the server advertises the {@code
 * unbundlehash} capability -- otherwise the literal heads list is sent, unchanged from before.
 * Verified end-to-end against a real hg SSH server (which does advertise this capability) in
 * {@code HgSshClientRealHgInteropTest#pushEndToEndToARealHgSshServer}: if the digest were
 * computed incorrectly, that real server's own {@code check_heads()} would reject the push with
 * {@code PushRaced} even though nothing actually raced.</p>
 */
public class HgSshUnbundleHashTest {

    private SshServer sshServer;
    private int port;

    @BeforeEach
    public void startSshServer(@TempDir Path tempDir) throws Exception {
        sshServer = SshServer.setUpDefaultServer();
        sshServer.setPort(0);
        Path tempKey = Files.createTempFile("ssh_host_unbundlehash_", ".key");
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempKey));
        Files.deleteIfExists(tempKey);
        sshServer.setPasswordAuthenticator((username, password, session) -> true);
    }

    @AfterEach
    public void stopSshServer() throws Exception {
        if (sshServer != null) {
            sshServer.stop(true);
        }
    }

    private static byte[] minimalEmptyBundle1() {
        return new byte[]{
                'H', 'G', '1', '0', 'U', 'N',
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
        };
    }

    private static String sha1HexOfSortedConcat(List<String> hexHeads) throws Exception {
        List<byte[]> raw = new ArrayList<>();
        for (String h : hexHeads) {
            raw.add(NodeIdUtil.fromHex(h));
        }
        raw.sort((a, b) -> {
            int len = Math.min(a.length, b.length);
            for (int i = 0; i < len; i++) {
                int ai = a[i] & 0xFF, bi = b[i] & 0xFF;
                if (ai != bi) return ai - bi;
            }
            return a.length - b.length;
        });
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        for (byte[] n : raw) {
            sha1.update(n);
        }
        return NodeIdUtil.toHex(sha1.digest());
    }

    @Test
    public void pushSendsHashedSentinelWhenServerAdvertisesUnbundlehash(@TempDir Path tempDir) throws Exception {
        List<String> heads = List.of(
                "aaaa000000000000000000000000000000000a",
                "bbbb000000000000000000000000000000000b");
        String expectedHashHex = sha1HexOfSortedConcat(heads);

        CapturingUnbundleCommand.CapturedRequest[] capturedHolder = new CapturingUnbundleCommand.CapturedRequest[1];
        sshServer.setCommandFactory((channel, command) -> new CapturingUnbundleCommand(command,
                "lookup pushkey unbundlehash unbundle=HG10UN", capturedHolder));
        sshServer.start();
        port = sshServer.getPort();

        HgSshClient client = new HgSshClient("ssh://testuser@127.0.0.1:" + port + "/test/repo");
        client.setPassword("testpass");
        try {
            client.getCapabilities(); // negotiates unbundlehash
            client.push(minimalEmptyBundle1(), heads);
        } finally {
            client.close();
        }

        assertNotNull(capturedHolder[0], "unbundle command must have reached the server");
        String heads0 = capturedHolder[0].args.get("heads");
        assertNotNull(heads0);
        String[] tokens = heads0.trim().isEmpty() ? new String[0] : heads0.trim().split("\\s+");
        assertEquals(2, tokens.length, "expected [hashed, <digest>], got: " + heads0);
        assertEquals("hashed", hexDecodeAscii(tokens[0]), "first token must decode to the literal ASCII 'hashed' sentinel");
        assertEquals(expectedHashHex, tokens[1], "second token must be the sha1 digest of the sorted, concatenated raw head node ids");
    }

    @Test
    public void pushSendsLiteralHeadsWhenServerDoesNotAdvertiseUnbundlehash(@TempDir Path tempDir) throws Exception {
        List<String> heads = List.of(
                "aaaa000000000000000000000000000000000a",
                "bbbb000000000000000000000000000000000b");

        CapturingUnbundleCommand.CapturedRequest[] capturedHolder = new CapturingUnbundleCommand.CapturedRequest[1];
        sshServer.setCommandFactory((channel, command) -> new CapturingUnbundleCommand(command,
                "lookup pushkey unbundle=HG10UN", capturedHolder));
        sshServer.start();
        port = sshServer.getPort();

        HgSshClient client = new HgSshClient("ssh://testuser@127.0.0.1:" + port + "/test/repo");
        client.setPassword("testpass");
        try {
            client.getCapabilities();
            client.push(minimalEmptyBundle1(), heads);
        } finally {
            client.close();
        }

        assertNotNull(capturedHolder[0]);
        String heads0 = capturedHolder[0].args.get("heads");
        assertEquals(String.join(" ", heads), heads0, "without the unbundlehash capability, the literal heads list must be sent unchanged");
    }

    /** Real hg's {@code encodelist}/{@code decodelist} are just hex-encode/decode of arbitrary
     * bytes (not specifically node ids) -- "hashed" travels as the hex encoding of its own ASCII
     * bytes, e.g. "686173686564". */
    private static String hexDecodeAscii(String hex) {
        return new String(NodeIdUtil.fromHex(hex), StandardCharsets.US_ASCII);
    }

    /** Minimal hand-rolled SSH v1 server: negotiates capabilities, then handles exactly one
     * {@code unbundle} command by capturing its {@code heads} arg and responding success. */
    private static class CapturingUnbundleCommand implements Command {
        static class CapturedRequest {
            Map<String, String> args;
        }

        private final String command;
        private final String capabilities;
        private final CapturedRequest[] holder;
        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback callback;
        private Thread thread;

        CapturingUnbundleCommand(String command, String capabilities, CapturedRequest[] holder) {
            this.command = command;
            this.capabilities = capabilities;
            this.holder = holder;
        }

        @Override public void setInputStream(InputStream in) { this.in = in; }
        @Override public void setOutputStream(OutputStream out) { this.out = out; }
        @Override public void setErrorStream(OutputStream err) { this.err = err; }
        @Override public void setExitCallback(ExitCallback callback) { this.callback = callback; }

        @Override
        public void start(ChannelSession session, Environment env) {
            thread = new Thread(this::run, "capturing-unbundle-test");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void destroy(ChannelSession session) {
            if (thread != null) thread.interrupt();
        }

        private void run() {
            try {
                // 1. Real hg handshake: client sends "hello\nbetween\npairs 81\n<81 bytes>".
                readLine(); // "hello"
                writeFramed(("capabilities: " + capabilities + "\n").getBytes(StandardCharsets.UTF_8));
                readLine(); // "between"
                String pairsArgLine = readLine(); // "pairs 81"
                int pairsLen = Integer.parseInt(pairsArgLine.substring(pairsArgLine.indexOf(' ') + 1));
                readExactly(pairsLen);
                writeFramed("\n".getBytes(StandardCharsets.UTF_8));

                // 2. "unbundle" command: one fixed "heads" arg.
                String cmd = readLine();
                if (!"unbundle".equals(cmd)) {
                    callback.onExit(1);
                    return;
                }
                String headsArgLine = readLine(); // "heads <len>"
                int headsLen = Integer.parseInt(headsArgLine.substring(headsArgLine.indexOf(' ') + 1));
                String headsVal = new String(readExactly(headsLen), StandardCharsets.UTF_8);
                Map<String, String> args = new TreeMap<>();
                args.put("heads", headsVal);
                CapturedRequest captured = new CapturedRequest();
                captured.args = args;
                holder[0] = captured;

                // 3. Pre-payload "OK to continue" empty frame.
                writeFramed(new byte[0]);

                // 4. Payload: "<len>\n<bytes>" chunks terminated by "0\n".
                ByteArrayOutputStream payload = new ByteArrayOutputStream();
                while (true) {
                    int chunkLen = Integer.parseInt(readLine());
                    if (chunkLen == 0) break;
                    payload.write(readExactly(chunkLen));
                }

                // 5. Success: empty error frame, then result "1".
                writeFramed(new byte[0]);
                writeFramed("1".getBytes(StandardCharsets.US_ASCII));
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

        private String readLine() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int b;
            while ((b = in.read()) != -1) {
                if (b == '\n') break;
                line.write(b);
            }
            return line.toString(StandardCharsets.UTF_8);
        }

        private byte[] readExactly(int len) throws IOException {
            byte[] buf = new byte[len];
            int read = 0;
            while (read < len) {
                int got = in.read(buf, read, len - read);
                if (got == -1) throw new IOException("unexpected EOF");
                read += got;
            }
            return buf;
        }

        private void writeFramed(byte[] payload) throws IOException {
            out.write((payload.length + "\n").getBytes(StandardCharsets.US_ASCII));
            out.write(payload);
            out.flush();
        }
    }
}
