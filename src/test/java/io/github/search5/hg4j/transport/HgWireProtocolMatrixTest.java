package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.HgCommit;
import io.github.search5.hg4j.api.LogCommand;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.StandardOpenOption;

/**
 * Wire protocol <b>matrix</b>: cross-multiplies the axes that
 * {@link HgHttpV1NegotiationForcingInteropTest} already forces <em>individually</em> (arg tier,
 * compression) with a new axis ({@code bundle2} on/off) to check combinations no existing test
 * exercises -- e.g. legacy-GET tier together with bundle2 forced off, which no single-axis test
 * can ever produce since each of those holds every other axis at the server's own default.
 *
 * <p>HTTP: 3 tiers ({@code httppostargs}/{@code httpheader=N}/legacy GET) x 3 compression engines
 * (zlib/zstd/none) x 2 bundle2 states (on/off) = 18 combinations. SSH: 3 compression engines only
 * (SSH has no arg-tier concept, and bundle2-off forcing over SSH is not yet wired up -- tracked
 * separately in the matrix plan doc, not in scope here). Every combination round-trips a pull
 * <b>and</b> a push (the write path), matching the project rule that read-only verification is not
 * suficient.
 *
 * <p>See {@code llm-wiki/decisions/exhaustive-interop-matrix-plan.md} §2 for the design.
 */
@Tag("interop")
public class HgWireProtocolMatrixTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    // ------------------------------------------------------------------
    // Shared HTTP seed/assert helpers (same pattern as HgHttpV1NegotiationForcingInteropTest)
    // ------------------------------------------------------------------

    private File seedRepo(Path tempDir, String name) throws Exception {
        File repoDir = tempDir.resolve(name).toFile();
        repoDir.mkdirs();
        HgTestUtils.hg(repoDir, "init");
        Files.writeString(repoDir.toPath().resolve(".hg/hgrc"),
                "[web]\nallow-push = *\npush_ssl = false\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        Files.writeString(repoDir.toPath().resolve("a.txt"), "first content");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-m", "first commit", "-u", "dev");
        Files.writeString(repoDir.toPath().resolve("b.txt"), "second content");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-m", "second commit", "-u", "dev");
        return repoDir;
    }

    private void assertPulledBothSeedCommits(HgRepository local) throws Exception {
        List<HgCommit> log = new LogCommand(local).call();
        assertTrue(log.size() >= 2, "expected at least the 2 seeded commits, got: " + log.size());
        assertTrue(log.stream().anyMatch(c -> "first commit".equals(c.getMessage())));
        assertTrue(log.stream().anyMatch(c -> "second commit".equals(c.getMessage())));
    }

    private void pushAndVerifyOnServer(File repoDir, String url, Path tempDir, String label) throws Exception {
        File pushSideDir = tempDir.resolve("push-side-" + label).toFile();
        HgRepository pushSide = Hg.init().setDirectory(pushSideDir).call();
        new PullCommand(pushSide).setSource(url).call();

        String marker = "matrix-" + label + "-" + System.nanoTime();
        Files.writeString(pushSideDir.toPath().resolve(marker + ".txt"), "pushed via matrix combo " + label);
        new AddCommand(pushSide).addFile(marker + ".txt").call();
        new CommitCommand(pushSide).setAuthor("hg4j <hg4j@example.com>").setMessage(marker).call();

        new PushCommand(pushSide).setDestination(url).call();

        String serverLog = HgTestUtils.hg(repoDir, "log", "-T", "{desc}\n");
        assertTrue(serverLog.contains(marker),
                "real hg server (label=" + label + ") must see the pushed commit, log was: " + serverLog);
    }

    // ------------------------------------------------------------------
    // HTTP matrix: 3 tiers x 3 compression x 2 bundle2 = 18
    // ------------------------------------------------------------------

    enum Tier { HTTPPOSTARGS, HTTPHEADER, LEGACY_GET }

    record HttpCombo(Tier tier, String compression, boolean bundle2On) {
        String label() {
            return tier + "-" + compression + "-bundle2" + (bundle2On ? "on" : "off");
        }

        @Override
        public String toString() {
            return label();
        }
    }

    static Stream<HttpCombo> httpCombos() {
        List<HttpCombo> out = new ArrayList<>();
        for (Tier tier : Tier.values()) {
            for (String compression : List.of("zlib", "zstd", "none")) {
                for (boolean bundle2On : List.of(true, false)) {
                    out.add(new HttpCombo(tier, compression, bundle2On));
                }
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("httpCombos")
    public void httpMatrixPullAndPushRoundTrip(HttpCombo combo, @TempDir Path tempDir) throws Exception {
        File repoDir = seedRepo(tempDir, "matrix-" + combo.label());

        List<String> serverArgs = new ArrayList<>(List.of("--config", "server.compressionengines=" + combo.compression()));
        if (combo.tier() == Tier.HTTPPOSTARGS) {
            serverArgs.add("--config");
            serverArgs.add("experimental.httppostargs=True");
        }

        try (RealHgServeSupport.ServeHandle serve = RealHgServeSupport.start(repoDir, serverArgs.toArray(new String[0]))) {
            Set<String> strip = new HashSet<>();
            if (combo.tier() == Tier.LEGACY_GET) {
                strip.add("httpheader=");
            }
            if (!combo.bundle2On()) {
                strip.add("bundle2=");
            }

            CapabilityStrippingHttpProxy proxy = strip.isEmpty() ? null : new CapabilityStrippingHttpProxy(serve.url, strip);
            String effectiveUrl = proxy != null ? proxy.url : serve.url;
            try {
                HgRemoteClient probe = new HgRemoteClient(effectiveUrl);
                List<String> caps = probe.getCapabilities();

                if (combo.tier() == Tier.LEGACY_GET) {
                    assertFalse(caps.stream().anyMatch(c -> c.startsWith("httpheader=")),
                            "sanity: proxy must have stripped httpheader= for " + combo + ", got: " + caps);
                    assertFalse(caps.contains("httppostargs"),
                            "sanity: real hg default server must not advertise httppostargs for " + combo + ", got: " + caps);
                } else if (combo.tier() == Tier.HTTPPOSTARGS) {
                    assertTrue(caps.contains("httppostargs"),
                            "sanity: server must advertise httppostargs for " + combo + ", got: " + caps);
                }

                if (!combo.bundle2On()) {
                    assertFalse(caps.stream().anyMatch(c -> c.startsWith("bundle2=")),
                            "sanity: proxy must have stripped bundle2= for " + combo + ", got: " + caps);
                } else {
                    assertTrue(caps.stream().anyMatch(c -> c.startsWith("bundle2=")),
                            "sanity: real hg default server must advertise bundle2= for " + combo + ", got: " + caps);
                }

                String compressionToken = caps.stream().filter(c -> c.startsWith("compression=")).findFirst()
                        .orElseThrow(() -> new AssertionError("server must advertise a compression= token for " + combo + ", got: " + caps));
                assertEquals("compression=" + combo.compression(), compressionToken,
                        "sanity: server.compressionengines=" + combo.compression() + " must make the server advertise ONLY that engine, combo=" + combo);

                HgRepository local = Hg.init().setDirectory(tempDir.resolve("pull-side-" + combo.label()).toFile()).call();
                new PullCommand(local).setSource(effectiveUrl).call();
                assertPulledBothSeedCommits(local);

                pushAndVerifyOnServer(repoDir, effectiveUrl, tempDir, combo.label());
            } finally {
                if (proxy != null) {
                    proxy.close();
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // SSH matrix: compression only (3 combinations) -- hg4j client against a REAL hg CLI SSH
    // server (`hg -R <path> --config server.compressionengines=<engine> serve --stdio`), following
    // the exact pattern HgSshClientRealHgInteropTest already established (embedded Apache MINA
    // SSHD channel piping straight through to a real hg subprocess).
    // ------------------------------------------------------------------

    private SshServer sshServer;
    private int sshPort;

    @AfterEach
    public void tearDownSsh() throws IOException {
        if (sshServer != null) {
            sshServer.stop(true);
            sshServer = null;
        }
    }

    private String sshUrl(File repoDir) {
        return "ssh://testuser@127.0.0.1:" + sshPort + repoDir.getAbsolutePath();
    }

    @ParameterizedTest(name = "ssh-{0}")
    @ValueSource(strings = {"zlib", "zstd", "none"})
    public void sshMatrixPullAndPushRoundTrip(String compression, @TempDir Path tempDir) throws Exception {
        sshServer = SshServer.setUpDefaultServer();
        sshServer.setPort(0);
        Path hostKey = tempDir.resolve("host_key");
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
        sshServer.setPublickeyAuthenticator(AcceptAllPublickeyAuthenticator.INSTANCE);
        sshServer.setPasswordAuthenticator((username, password, session) -> true);
        // Real hg's own `serve --stdio` refuses any extra CLI args spliced in around `-R <path>`
        // ("abort: potentially unsafe serve --stdio invocation") -- a security guard against
        // argument injection through restricted SSH `command=` invocations, confirmed 2026-09-04
        // by reproducing it manually. So the compression engine cannot be passed as a `--config`
        // flag on the spawned process the way the HTTP half of this matrix does; it must instead
        // be written into the target repo's own `.hg/hgrc`, which `serve --stdio` reads normally.
        sshServer.setCommandFactory((channel, command) -> new RealHgServeCommand(command));
        sshServer.start();
        sshPort = sshServer.getPort();

        File repoDir = tempDir.resolve("ssh-matrix-" + compression).toFile();
        HgTestUtils.hg(tempDir.toFile(), "init", repoDir.getAbsolutePath());
        Files.writeString(new File(repoDir, ".hg/hgrc").toPath(),
                "[server]\ncompressionengines = " + compression + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        Files.writeString(repoDir.toPath().resolve("seed.txt"), "seed for ssh " + compression);
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-m", "seed", "-u", "dev");

        String url = sshUrl(repoDir);

        HgRepository pullSide = Hg.init().setDirectory(tempDir.resolve("ssh-pull-" + compression).toFile()).call();
        new PullCommand(pullSide).setSource(url).call();
        List<HgCommit> log = new LogCommand(pullSide).call();
        assertEquals(1, log.size(), "hg4j must pull the seed commit over SSH with compression=" + compression);
        assertEquals("seed", log.get(0).getMessage());

        String marker = "ssh-matrix-" + compression + "-" + System.nanoTime();
        Files.writeString(new File(tempDir.resolve("ssh-pull-" + compression).toFile(), marker + ".txt").toPath(), "pushed over ssh");
        new AddCommand(pullSide).addFile(marker + ".txt").call();
        new CommitCommand(pullSide).setAuthor("hg4j <hg4j@example.com>").setMessage(marker).call();
        new PushCommand(pullSide).setDestination(url).call();

        String serverLog = HgTestUtils.hg(repoDir, "log", "-T", "{desc}\n");
        assertTrue(serverLog.contains(marker),
                "real hg SSH server (compression=" + compression + ") must see the pushed commit, log was: " + serverLog);
    }

    /** Identical to {@link HgSshClientRealHgInteropTest}'s inner class of the same name: spawns
     * the real {@code hg} CLI's own {@code serve --stdio} as a subprocess with NO extra CLI args
     * (real hg's own security guard rejects a spliced-in {@code --config}, see the comment at the
     * call site above) -- the per-combo compression engine is instead baked into the target repo's
     * {@code .hg/hgrc} before this command ever runs. */
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
