package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.IncomingCommand;
import io.github.search5.hg4j.api.OutgoingCommand;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.PullCommand;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.transport.WireMatrixCombos.HttpCombo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Backlog item 39, wave 5 (wire matrix track): {@link IncomingCommand}/{@link OutgoingCommand}
 * across the same 21-combo matrix (HTTP 18 + SSH 3) {@link HgWireProtocolMatrixTest} established
 * for {@code Clone}/{@code Pull}/{@code Push} -- <b>and</b>, per explicit scope for these two
 * commands, verified <em>bidirectionally</em>: hg4j client against a real {@code hg} server (the
 * full 21 combos, {@link #httpMatrixIncoming}/{@link #httpMatrixOutgoing}/{@link
 * #sshMatrixIncoming}/{@link #sshMatrixOutgoing}), and a real {@code hg} client against an
 * hg4j-served server ({@link #httpReverseDirectionIncomingOutgoing}/{@link
 * #sshReverseDirectionIncomingOutgoing}).
 *
 * <p>The ground truth in every case is real hg's own {@code hg incoming}/{@code hg outgoing}
 * {@code --template "{node|short}\n"} output -- the exact short node hash set is compared, not the
 * free-form display text (hg4j's {@link IncomingCommand}/{@link OutgoingCommand} render a
 * simplified, not byte-for-byte-identical, summary format; the node identity is what actually
 * matters for "did it find the right changesets").
 *
 * <p><b>Reverse-direction scope note</b>: unlike real hg's {@code hg serve}, hg4j's {@link
 * HgHttpWireServer}/SSH serving path advertises a single fixed capability set (arg tier,
 * compression engine, bundle2) with no config knob to vary it the way {@code server.
 * compressionengines=}/{@code experimental.httppostargs=} do for real hg -- so the reverse
 * direction cannot literally reproduce all 18 HTTP sub-combos server-side. What CAN be forced from
 * the outside (the same {@link CapabilityStrippingHttpProxy} technique used everywhere else in
 * this matrix) is the arg tier and bundle2 axes, giving 3 x 2 = 6 meaningfully distinct HTTP
 * reverse-direction combos plus 1 SSH default -- still strictly more coverage than existed before
 * (zero) for these two commands' server-side correctness.
 */
@Tag("interop")
public class HgWireProtocolMatrixIncomingOutgoingTest {

    private static final Pattern NODE_PATTERN = Pattern.compile("changeset:\\s+(?:\\d+:)?([0-9a-fA-F]{12})");

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    private static Set<String> extractHashes(List<String> lines) {
        Set<String> hashes = new HashSet<>();
        for (String line : lines) {
            Matcher m = NODE_PATTERN.matcher(line);
            if (m.find()) {
                hashes.add(m.group(1).toLowerCase());
            }
        }
        return hashes;
    }

    private static final Pattern SHORT_HASH_LINE = Pattern.compile("^[0-9a-fA-F]{12}$");

    /** {@code HgTestUtils.hg()} merges stderr into stdout, and real hg's {@code incoming}/{@code
     * outgoing} print progress lines ("comparing with ...", "searching for changes") to stderr
     * even with an explicit {@code --template} -- so this must only keep lines that actually look
     * like a short node hash, not just any non-blank line. */
    private static final Pattern FULL_HASH_LINE = Pattern.compile("^[0-9a-fA-F]{40}$");

    /** Same stderr-merged-into-stdout noise problem as {@link #realHgShortHashes}, but for a
     * single full 40-hex-char {@code {node}} template line instead of a set of short ones. */
    private static String singleFullNodeLine(String output) {
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (FULL_HASH_LINE.matcher(trimmed).matches()) {
                return trimmed;
            }
        }
        throw new AssertionError("no line matching a full 40-hex-char node hash found in: " + output);
    }

    private static Set<String> realHgShortHashes(String output) {
        return java.util.Arrays.stream(output.split("\n"))
                .map(String::trim)
                .filter(s -> SHORT_HASH_LINE.matcher(s).matches())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    // ==================================================================
    // Direction A: hg4j client against a real hg server (full 21 combos)
    // ==================================================================

    private File seedServerRepo(Path tempDir, String name) throws Exception {
        File repoDir = tempDir.resolve(name).toFile();
        repoDir.mkdirs();
        HgTestUtils.hg(repoDir, "init");
        Files.writeString(repoDir.toPath().resolve("a.txt"), "first content");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-m", "first commit", "-u", "dev");
        return repoDir;
    }

    private void runIncomingScenario(File repoDir, String url, Path tempDir, String label, String... extraRealHgArgs) throws Exception {
        runIncomingScenario(repoDir, url, url, tempDir, label, extraRealHgArgs);
    }

    private void runIncomingScenario(File repoDir, String url, String groundTruthUrl, Path tempDir, String label, String... extraRealHgArgs) throws Exception {
        HgRepository local = Hg.init().setDirectory(tempDir.resolve("in-" + label).toFile()).call();
        new PullCommand(local).setSource(url).call();

        // Two more commits land on the server -- both must show up as incoming.
        Files.writeString(repoDir.toPath().resolve("b.txt"), "second content " + label);
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-m", "second commit " + label, "-u", "dev");
        Files.writeString(repoDir.toPath().resolve("c.txt"), "third content " + label);
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-m", "third commit " + label, "-u", "dev");

        List<String> incoming = new IncomingCommand(local).setSource(url).call();
        Set<String> hg4jHashes = extractHashes(incoming);

        List<String> realArgs = new java.util.ArrayList<>(List.of(extraRealHgArgs));
        realArgs.addAll(List.of("incoming", "--template", "{node|short}\n", groundTruthUrl));
        String realOut = HgTestUtils.hg(local.getDirectory(), realArgs.toArray(new String[0]));
        Set<String> realHashes = realHgShortHashes(realOut);

        assertEquals(2, realHashes.size(), "sanity: real hg itself must see exactly 2 incoming changesets, combo=" + label);
        assertEquals(realHashes, hg4jHashes,
                "IncomingCommand's reported changeset set must exactly match real hg's own \"hg incoming\", combo=" + label
                        + "\nhg4j reported: " + incoming);
    }

    private void runOutgoingScenario(File repoDir, String url, Path tempDir, String label, String... extraRealHgArgs) throws Exception {
        runOutgoingScenario(repoDir, url, url, tempDir, label, extraRealHgArgs);
    }

    private void runOutgoingScenario(File repoDir, String url, String groundTruthUrl, Path tempDir, String label, String... extraRealHgArgs) throws Exception {
        HgRepository local = Hg.init().setDirectory(tempDir.resolve("out-" + label).toFile()).call();
        new PullCommand(local).setSource(url).call();

        // Two local-only commits, never pushed -- both must show up as outgoing.
        Files.writeString(new File(local.getDirectory(), "d.txt").toPath(), "local-only 1 " + label);
        new AddCommand(local).call();
        new CommitCommand(local).setAuthor("hg4j <hg4j@example.com>").setMessage("outgoing commit 1 " + label).call();
        Files.writeString(new File(local.getDirectory(), "e.txt").toPath(), "local-only 2 " + label);
        new AddCommand(local).call();
        new CommitCommand(local).setAuthor("hg4j <hg4j@example.com>").setMessage("outgoing commit 2 " + label).call();

        List<String> outgoing = new OutgoingCommand(local).setDestination(url).call();
        Set<String> hg4jHashes = extractHashes(outgoing);

        List<String> realArgs = new java.util.ArrayList<>(List.of(extraRealHgArgs));
        realArgs.addAll(List.of("outgoing", "--template", "{node|short}\n", groundTruthUrl));
        String realOut = HgTestUtils.hg(local.getDirectory(), realArgs.toArray(new String[0]));
        Set<String> realHashes = realHgShortHashes(realOut);

        assertEquals(2, realHashes.size(), "sanity: real hg itself must see exactly 2 outgoing changesets, combo=" + label);
        assertEquals(realHashes, hg4jHashes,
                "OutgoingCommand's reported changeset set must exactly match real hg's own \"hg outgoing\", combo=" + label
                        + "\nhg4j reported: " + outgoing);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.github.search5.hg4j.transport.WireMatrixCombos#httpCombos")
    public void httpMatrixIncoming(HttpCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = seedServerRepo(tempDir, "in-matrix-" + combo.label());
        try (HttpMatrixServer server = HttpMatrixServer.start(repoDir, combo)) {
            server.verifySanity(combo);
            runIncomingScenario(repoDir, server.url, tempDir, combo.label());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.github.search5.hg4j.transport.WireMatrixCombos#httpCombos")
    public void httpMatrixOutgoing(HttpCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = seedServerRepo(tempDir, "out-matrix-" + combo.label());
        try (HttpMatrixServer server = HttpMatrixServer.start(repoDir, combo)) {
            server.verifySanity(combo);
            runOutgoingScenario(repoDir, server.url, tempDir, combo.label());
        }
    }

    @ParameterizedTest(name = "ssh-{0}")
    @ValueSource(strings = {"zlib", "zstd", "none"})
    public void sshMatrixIncoming(String compression, @TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(SshMatrixServer.isSshKeygenAvailable(), "ssh-keygen is not available. Skipping.");
        File repoDir = tempDir.resolve("ssh-in-matrix-" + compression).toFile();
        HgTestUtils.hg(tempDir.toFile(), "init", repoDir.getAbsolutePath());
        Files.writeString(new File(repoDir, ".hg/hgrc").toPath(),
                "[server]\ncompressionengines = " + compression + "\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        Files.writeString(repoDir.toPath().resolve("a.txt"), "first content ssh " + compression);
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-m", "first commit", "-u", "dev");

        try (SshMatrixServer ssh = SshMatrixServer.start(tempDir)) {
            // Real hg's own "hg incoming" ground-truth check below shells out to the real ssh
            // binary (unlike hg4j's client, embedded JSch), so it needs a real keypair + host-key
            // checking disabled against this ephemeral test server -- see
            // SshMatrixServer#realHgUiSshOverride's javadoc.
            String uiSsh = ssh.realHgUiSshOverride();
            runIncomingScenario(repoDir, ssh.url(repoDir), ssh.realHgUrl(repoDir), tempDir, "ssh-" + compression,
                    "--config", "ui.ssh=" + uiSsh);
        }
    }

    @ParameterizedTest(name = "ssh-{0}")
    @ValueSource(strings = {"zlib", "zstd", "none"})
    public void sshMatrixOutgoing(String compression, @TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(SshMatrixServer.isSshKeygenAvailable(), "ssh-keygen is not available. Skipping.");
        File repoDir = tempDir.resolve("ssh-out-matrix-" + compression).toFile();
        HgTestUtils.hg(tempDir.toFile(), "init", repoDir.getAbsolutePath());
        Files.writeString(new File(repoDir, ".hg/hgrc").toPath(),
                "[server]\ncompressionengines = " + compression + "\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        Files.writeString(repoDir.toPath().resolve("a.txt"), "first content ssh " + compression);
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-m", "first commit", "-u", "dev");

        try (SshMatrixServer ssh = SshMatrixServer.start(tempDir)) {
            String uiSsh = ssh.realHgUiSshOverride();
            runOutgoingScenario(repoDir, ssh.url(repoDir), ssh.realHgUrl(repoDir), tempDir, "ssh-" + compression,
                    "--config", "ui.ssh=" + uiSsh);
        }
    }

    // ==================================================================
    // Direction B: real hg client against an hg4j-served server (6 HTTP combos: tier x bundle2 --
    // compression cannot be forced on hg4j's fixed-capability server, see class javadoc -- + 1 SSH
    // default). Ground truth here is what real hg reports vs what actually landed in the hg4j
    // store, since IncomingCommand/OutgoingCommand are hg4j CLIENT classes not exercised by this
    // direction at all -- this direction instead validates hg4j's SERVER-side discovery responses
    // (heads/between/known/changegroup) are correct enough for real hg's own incoming/outgoing
    // algorithm to reach the right answer.
    // ==================================================================

    enum ReverseTier { HTTPPOSTARGS, HTTPHEADER, LEGACY_GET }

    record ReverseCombo(ReverseTier tier, boolean bundle2On) {
        @Override
        public String toString() {
            return tier + "-bundle2" + (bundle2On ? "on" : "off");
        }
    }

    static List<ReverseCombo> reverseCombos() {
        List<ReverseCombo> out = new java.util.ArrayList<>();
        for (ReverseTier tier : ReverseTier.values()) {
            for (boolean b2 : List.of(true, false)) {
                out.add(new ReverseCombo(tier, b2));
            }
        }
        return out;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("reverseCombos")
    public void httpReverseDirectionIncomingOutgoing(ReverseCombo combo, @TempDir Path tempDir) throws Exception {
        File serverRepoDir = tempDir.resolve("rev-server-" + combo).toFile();
        HgRepository serverRepo = Hg.init().setDirectory(serverRepoDir).call();
        Files.writeString(new File(serverRepoDir, "a.txt").toPath(), "server seed");
        new AddCommand(serverRepo).call();
        new CommitCommand(serverRepo).setAuthor("hg4j <hg4j@example.com>").setMessage("server seed commit").call();

        com.sun.net.httpserver.HttpServer httpServer = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        httpServer.createContext("/", new HgHttpWireServer(serverRepo));
        httpServer.start();
        try {
            String backendUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/";

            Set<String> strip = new HashSet<>();
            if (combo.tier() == ReverseTier.LEGACY_GET) {
                strip.add("httpheader=");
            }
            if (!combo.bundle2On()) {
                strip.add("bundle2=");
            }
            CapabilityStrippingHttpProxy proxy = strip.isEmpty() ? null : new CapabilityStrippingHttpProxy(backendUrl, strip);
            String url = proxy != null ? proxy.url : backendUrl;
            try {
                // Incoming half: real hg client clones the seed, then hg4j's server gets a new
                // commit; real hg's own "hg incoming" must see exactly that one new commit.
                File clientDir = tempDir.resolve("rev-client-" + combo).toFile();
                HgTestUtils.hg(tempDir.toFile(), "clone", url, clientDir.getAbsolutePath());

                Files.writeString(new File(serverRepoDir, "b.txt").toPath(), "server second");
                new AddCommand(serverRepo).call();
                byte[] secondNode = new CommitCommand(serverRepo).setAuthor("hg4j <hg4j@example.com>").setMessage("server second commit").call();

                String incomingOut = HgTestUtils.hg(clientDir, "incoming", "--template", "{node}\n", url);
                assertEquals(io.github.search5.hg4j.util.NodeIdUtil.toHex(secondNode), singleFullNodeLine(incomingOut),
                        "real hg's own incoming against the hg4j-served repo must see exactly the new server commit, combo=" + combo);

                // Outgoing half: the client makes a local commit of its own; real hg's "hg
                // outgoing" against the (still not pushed to) hg4j server must report it.
                Files.writeString(new File(clientDir, "c.txt").toPath(), "client local");
                HgTestUtils.hg(clientDir, "add", "c.txt");
                HgTestUtils.hg(clientDir, "commit", "-m", "client-only commit", "-u", "dev");
                String clientTip = HgTestUtils.hg(clientDir, "log", "-T", "{node}\n", "-r", "tip").trim();

                String outgoingOut = HgTestUtils.hg(clientDir, "outgoing", "--template", "{node}\n", url);
                assertEquals(clientTip, singleFullNodeLine(outgoingOut),
                        "real hg's own outgoing against the hg4j-served repo must report exactly the client-only commit, combo=" + combo);
            } finally {
                if (proxy != null) {
                    proxy.close();
                }
            }
        } finally {
            httpServer.stop(0);
        }
    }

    /** Real hg's SSH client shells out to the actual {@code ssh} binary (unlike hg4j's own
     * embedded-JSch SSH client used elsewhere in this matrix), so the reverse direction needs a
     * genuine keypair + {@code ui.ssh=} override -- exactly {@link
     * HgSshWireServerRealHgInteropTest}'s established setup, inlined here since that class's
     * fields/helpers are private to it. */
    @ParameterizedTest(name = "ssh-default")
    @ValueSource(strings = {"default"})
    public void sshReverseDirectionIncomingOutgoing(String unused, @TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(isSshKeygenAvailable(), "ssh-keygen is not available. Skipping.");

        File sshKeyFile = tempDir.resolve("id_test").toFile();
        runProcess("ssh-keygen", "-t", "rsa", "-b", "2048", "-f", sshKeyFile.getAbsolutePath(), "-N", "");

        org.apache.sshd.server.SshServer sshServer = org.apache.sshd.server.SshServer.setUpDefaultServer();
        sshServer.setPort(0);
        Path hostKey = tempDir.resolve("host_key");
        sshServer.setKeyPairProvider(new org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider(hostKey));
        sshServer.setPublickeyAuthenticator(org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator.INSTANCE);
        sshServer.setCommandFactory((channel, command) -> new Hg4jWireCommand(command));
        sshServer.start();
        int port = sshServer.getPort();
        try {
            File serverRepoDir = tempDir.resolve("rev-ssh-server").toFile();
            HgRepository serverRepo = Hg.init().setDirectory(serverRepoDir).call();
            Files.writeString(new File(serverRepoDir, "a.txt").toPath(), "server seed");
            new AddCommand(serverRepo).call();
            new CommitCommand(serverRepo).setAuthor("hg4j <hg4j@example.com>").setMessage("server seed commit").call();

            String url = "ssh://127.0.0.1:" + port + "/" + serverRepoDir.getAbsolutePath();
            String uiSsh = "ssh -i " + sshKeyFile.getAbsolutePath()
                    + " -o StrictHostKeyChecking=no -o UserKnownHostsFile=" + tempDir.resolve("known_hosts")
                    + " -o IdentitiesOnly=yes -p " + port;

            File clientDir = tempDir.resolve("rev-ssh-client").toFile();
            HgTestUtils.hg(tempDir.toFile(), "--config", "ui.ssh=" + uiSsh,
                    "clone", url, clientDir.getAbsolutePath());

            Files.writeString(new File(serverRepoDir, "b.txt").toPath(), "server second");
            new AddCommand(serverRepo).call();
            byte[] secondNode = new CommitCommand(serverRepo).setAuthor("hg4j <hg4j@example.com>").setMessage("server second commit").call();

            String incomingOut = HgTestUtils.hg(clientDir, "--config", "ui.ssh=" + uiSsh,
                    "incoming", "--template", "{node}\n", url);
            assertEquals(io.github.search5.hg4j.util.NodeIdUtil.toHex(secondNode), singleFullNodeLine(incomingOut),
                    "real hg's own incoming over SSH against the hg4j-served repo must see exactly the new server commit");

            Files.writeString(new File(clientDir, "c.txt").toPath(), "client local");
            HgTestUtils.hg(clientDir, "add", "c.txt");
            HgTestUtils.hg(clientDir, "commit", "-m", "client-only commit", "-u", "dev");
            String clientTip = HgTestUtils.hg(clientDir, "log", "-T", "{node}\n", "-r", "tip").trim();

            String outgoingOut = HgTestUtils.hg(clientDir, "--config", "ui.ssh=" + uiSsh,
                    "outgoing", "--template", "{node}\n", url);
            assertEquals(clientTip, singleFullNodeLine(outgoingOut),
                    "real hg's own outgoing over SSH against the hg4j-served repo must report exactly the client-only commit");
        } finally {
            sshServer.stop(true);
        }
    }

    private static boolean isSshKeygenAvailable() {
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

    /** Server-side {@code Command} adapter attaching {@link HgSshWireServer} to a fresh
     * {@link HgRepository} instance per connection -- same shape as {@code
     * HgSshWireServerRealHgInteropTest.HgWireCommand}. */
    private static class Hg4jWireCommand implements org.apache.sshd.server.command.Command, Runnable {
        private static final Pattern REPO_PATH = Pattern.compile("-R\\s+'?([^'\\s]+)'?");

        private final String command;
        private java.io.InputStream in;
        private java.io.OutputStream out;
        private java.io.OutputStream err;
        private org.apache.sshd.server.ExitCallback callback;
        private Thread thread;

        Hg4jWireCommand(String command) {
            this.command = command;
        }

        @Override public void setInputStream(java.io.InputStream in) { this.in = in; }
        @Override public void setOutputStream(java.io.OutputStream out) { this.out = out; }
        @Override public void setErrorStream(java.io.OutputStream err) { this.err = err; }
        @Override public void setExitCallback(org.apache.sshd.server.ExitCallback callback) { this.callback = callback; }

        @Override
        public void start(org.apache.sshd.server.channel.ChannelSession session, org.apache.sshd.server.Environment env) {
            thread = new Thread(this, "hg4j-ssh-wire-matrix-test");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void destroy(org.apache.sshd.server.channel.ChannelSession session) {
            if (thread != null) {
                thread.interrupt();
            }
        }

        @Override
        public void run() {
            try {
                Matcher m = REPO_PATH.matcher(command == null ? "" : command);
                if (!m.find()) {
                    err.write(("bad command: " + command + "\n").getBytes());
                    err.flush();
                    callback.onExit(1);
                    return;
                }
                HgRepository repo = new HgRepository(new File(m.group(1)));
                new HgSshWireServer(repo).handleConnection(in, out);
                callback.onExit(0);
            } catch (Exception e) {
                try {
                    err.write((e + "\n").getBytes());
                    err.flush();
                } catch (java.io.IOException ignored) {
                }
                callback.onExit(1);
            }
        }
    }
}
