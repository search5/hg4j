package io.github.search5.hg4j.transport;

import com.sun.net.httpserver.HttpServer;
import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.ClonebundlesCommand;
import io.github.search5.hg4j.api.FetchCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.HgCommit;
import io.github.search5.hg4j.api.LogCommand;
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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.file.StandardOpenOption;

/**
 * Backlog item 39, wave 5 (wire matrix track): the Clonebundles "bypass" mechanism (see {@link
 * ClonebundlesCommand}/{@link io.github.search5.hg4j.bundle.ClonebundlesManifest}) across the same
 * 21-combo matrix (HTTP 18 + SSH 3) {@link HgWireProtocolMatrixTest} established for {@code
 * Clone}/{@code Pull}/{@code Push}. The manifest lookup itself ({@code ?cmd=clonebundles}) still
 * goes over the negotiated wire protocol, so this proves the tier/compression/bundle2 axes don't
 * interfere with capability detection or the manifest fetch; the actual bundle payload transfer is
 * a plain HTTP GET to a self-hosted static file server, matching real hg's own design (offloading
 * the big transfer away from the wire-protocol server entirely).
 *
 * <p>Backlog item 39 (2026-09-05) also found and fixed a real hg4j production bug while building
 * this matrix: {@link FetchCommand}'s clonebundles bypass gate used to be {@code instanceof
 * HgRemoteClient} (HTTP only) even though real hg's own client attempts the bypass over any
 * transport ({@code remote.capable(b'clonebundles')} in {@code mercurial/exchange.py} is
 * transport-agnostic) -- fixed by moving {@code supportsClonebundles()}/{@code
 * fetchClonebundlesManifest()} onto the shared {@link HgRemoteConnection} interface and
 * implementing them in {@link HgSshClient} too, verified by the SSH half of this matrix.
 */
@Tag("interop")
public class HgWireProtocolMatrixClonebundlesTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    private File seedRepoWithBundle(Path tempDir, String name, byte[][] bundleBytesOut) throws Exception {
        File repoDir = tempDir.resolve(name).toFile();
        repoDir.mkdirs();
        HgTestUtils.hg(repoDir, "init");
        Files.writeString(repoDir.toPath().resolve("a.txt"), "first content");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-m", "first commit", "-u", "dev");
        Files.writeString(repoDir.toPath().resolve("b.txt"), "second content");
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-m", "second commit", "-u", "dev");

        File bundleFile = tempDir.resolve(name + "-full.hg").toFile();
        HgTestUtils.hg(repoDir, "bundle", "--all", "--type", "none-v2", bundleFile.getAbsolutePath());
        bundleBytesOut[0] = Files.readAllBytes(bundleFile.toPath());
        return repoDir;
    }

    /** Starts a tiny embedded HTTP file server that serves {@code bundleBytes} at {@code /full.hg}
     * and counts how many times it was hit -- used to positively confirm the bypass actually fired
     * (as opposed to silently falling through to a normal pull and still happening to succeed). */
    private static final class BundleFileServer implements AutoCloseable {
        final HttpServer server;
        final String url;
        final AtomicInteger hits = new AtomicInteger();

        BundleFileServer(byte[] bundleBytes) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/full.hg", exchange -> {
                hits.incrementAndGet();
                exchange.sendResponseHeaders(200, bundleBytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bundleBytes);
                }
                exchange.close();
            });
            server.start();
            url = "http://127.0.0.1:" + server.getAddress().getPort() + "/full.hg";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private void writeManifest(File repoDir, String bundleUrl) throws Exception {
        Files.writeString(repoDir.toPath().resolve(".hg/clonebundles.manifest"),
                bundleUrl + " BUNDLESPEC=none-v2\n", StandardCharsets.UTF_8);
    }

    private void assertBypassApplied(HgRepository local, BundleFileServer bundleServer, String label) throws Exception {
        assertEquals(1, bundleServer.hits.get(),
                "the clonebundle download server must have been hit exactly once (bypass must actually fire), combo=" + label);
        List<HgCommit> log = new LogCommand(local).call();
        assertEquals(2, log.size(), "both bundled commits must be applied, combo=" + label);
        assertTrue(log.stream().anyMatch(c -> "first commit".equals(c.getMessage())));
        assertTrue(log.stream().anyMatch(c -> "second commit".equals(c.getMessage())));
    }

    // ------------------------------------------------------------------
    // HTTP matrix: 3 tiers x 3 compression x 2 bundle2 = 18
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("io.github.search5.hg4j.transport.WireMatrixCombos#httpCombos")
    public void httpMatrixClonebundlesBypass(HttpCombo combo, @TempDir Path tempDir) throws Exception {
        byte[][] bundleBytesHolder = new byte[1][];
        File repoDir = seedRepoWithBundle(tempDir, "cb-matrix-" + combo.label(), bundleBytesHolder);

        try (BundleFileServer bundleServer = new BundleFileServer(bundleBytesHolder[0])) {
            writeManifest(repoDir, bundleServer.url);

            try (HttpMatrixServer server = HttpMatrixServer.start(repoDir, combo, "--config", "extensions.clonebundles=")) {
                HgRemoteClient probe = new HgRemoteClient(server.url);
                List<String> caps = probe.getCapabilities();
                assertTrue(caps.contains("clonebundles"),
                        "server must advertise clonebundles for combo=" + combo + ", got: " + caps);

                HgRepository local = Hg.init().setDirectory(tempDir.resolve("cb-dest-" + combo.label()).toFile()).call();
                List<byte[]> imported = new FetchCommand(local).setSource(server.url).call();
                assertEquals(2, imported.size(), "combo=" + combo);

                assertBypassApplied(local, bundleServer, combo.label());
            }
        }
    }

    // ------------------------------------------------------------------
    // SSH matrix: compression only (3 combinations) -- also proves the backlog-39 fix (SSH
    // clonebundles support) actually works end to end against a real hg SSH server.
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "ssh-{0}")
    @ValueSource(strings = {"zlib", "zstd", "none"})
    public void sshMatrixClonebundlesBypass(String compression, @TempDir Path tempDir) throws Exception {
        byte[][] bundleBytesHolder = new byte[1][];
        File repoDir = tempDir.resolve("ssh-cb-matrix-" + compression).toFile();
        repoDir.mkdirs();
        HgTestUtils.hg(tempDir.toFile(), "init", repoDir.getAbsolutePath());
        Files.writeString(new File(repoDir, ".hg/hgrc").toPath(),
                "[server]\ncompressionengines = " + compression + "\n[extensions]\nclonebundles =\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        Files.writeString(repoDir.toPath().resolve("a.txt"), "first content ssh " + compression);
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-m", "first commit", "-u", "dev");
        Files.writeString(repoDir.toPath().resolve("b.txt"), "second content ssh " + compression);
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-m", "second commit", "-u", "dev");

        File bundleFile = tempDir.resolve("ssh-cb-" + compression + "-full.hg").toFile();
        HgTestUtils.hg(repoDir, "bundle", "--all", "--type", "none-v2", bundleFile.getAbsolutePath());
        bundleBytesHolder[0] = Files.readAllBytes(bundleFile.toPath());

        try (BundleFileServer bundleServer = new BundleFileServer(bundleBytesHolder[0])) {
            writeManifest(repoDir, bundleServer.url);

            try (SshMatrixServer ssh = SshMatrixServer.start(tempDir)) {
                String url = ssh.url(repoDir);
                HgSshClient probe = new HgSshClient(url);
                List<String> caps = probe.getCapabilities();
                assertTrue(caps.contains("clonebundles"),
                        "SSH server must advertise clonebundles for compression=" + compression + ", got: " + caps);
                assertTrue(probe.supportsClonebundles());
                probe.close();

                HgRepository local = Hg.init().setDirectory(tempDir.resolve("ssh-cb-dest-" + compression).toFile()).call();
                List<byte[]> imported = new FetchCommand(local).setSource(url).call();
                assertEquals(2, imported.size(), "compression=" + compression);

                assertBypassApplied(local, bundleServer, "ssh-" + compression);
            }
        }
    }
}
