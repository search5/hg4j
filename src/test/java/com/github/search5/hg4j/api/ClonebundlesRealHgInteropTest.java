package com.github.search5.hg4j.api;

import com.github.search5.hg4j.HgTestUtils;
import com.github.search5.hg4j.bundle.ClonebundlesManifest;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.transport.HgRemoteClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;

/**
 * Real-Mercurial verification of the Clonebundles mechanism, run against a live Mercurial 6.0
 * server (Docker container {@code hg4j-hg60-server}, port 18099) with the {@code clonebundles}
 * extension explicitly enabled and a manifest present.
 *
 * <p>The {@code clonebundles} capability turned out to be gated behind a server-side extension
 * (like {@code censor}) rather than a core capability: {@code hgext/clonebundles.py} wraps
 * {@code wireprotov1server._capabilities} and only appends the token when
 * {@code .hg/clonebundles.manifest} exists <em>and</em> {@code extensions.clonebundles=} is
 * loaded — a stock {@code hg serve} with just the manifest file present never advertises it. This
 * matters for hg4j's capability check: it must not assume clonebundles support just because a
 * manifest might exist, only because the server actually said so.</p>
 *
 * <p>This test requires manually starting the container beforehand (same convention as the
 * existing {@code HgHttpV1LiveServerInteropTest}):
 * <pre>
 * docker run -d --name hg4j-hg60-server -p 18099:8099 localhost/hg4j-test-mercurial-6.0 sh -c "
 *   cd /repo
 *   hg bundle --all --type none-v2 /tmp/full.hg
 *   echo 'http://placeholder/full.hg BUNDLESPEC=none-v2' &gt; .hg/clonebundles.manifest
 *   hg serve -p 8099 --address 0.0.0.0 --config extensions.clonebundles="
 * </pre>
 * If the container isn't reachable, the test skips via {@code Assumptions}.</p>
 */
@Tag("interop")
public class ClonebundlesRealHgInteropTest {

    private static final String SERVER_URL = "http://127.0.0.1:18099";

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(isServerReachable(), "hg4j-hg60-server container is not reachable on port 18099. Skipping.");
    }

    private static boolean isServerReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 18099), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    public void realHgAdvertisesClonebundlesOnlyWithTheExtensionEnabledAndAManifestPresent() throws Exception {
        HgRemoteClient client = new HgRemoteClient(SERVER_URL);
        client.getCapabilities();

        assertTrue(client.supportsClonebundles(),
                "Real hg with extensions.clonebundles= enabled and a manifest present must advertise the capability");
    }

    @Test
    public void realHgManifestParsesIntoAUsableEntry() throws Exception {
        HgRemoteClient client = new HgRemoteClient(SERVER_URL);
        client.getCapabilities();
        String manifestText = client.fetchClonebundlesManifest();

        List<ClonebundlesManifest.Entry> entries = ClonebundlesManifest.parse(manifestText);
        assertFalse(entries.isEmpty(), "Real hg's manifest response must parse into at least one entry: " + manifestText);
        assertEquals("none-v2", entries.get(0).getBundlespec());

        List<ClonebundlesManifest.Entry> supported = ClonebundlesManifest.filterSupported(entries);
        assertFalse(supported.isEmpty(), "none-v2 must survive hg4j's own bundlespec filter");
    }

    @Test
    public void hg4jAppliesARealHgGeneratedBundle2PayloadViaTheClonebundlesDownloadPath(@TempDir Path tempDir) throws Exception {
        // The manifest's own placeholder URL isn't reachable from the test JVM; this exercises
        // the download+apply half of the flow against the actual bundle bytes `hg bundle --all
        // --type none-v2` produced inside the container (extracted via `docker cp` ahead of time
        // into the scratchpad and copied alongside this test run) -- proving hg4j's
        // UnbundleCommand/Bundle2Parser can consume a genuine real-hg-generated HG20 payload
        // through this specific code path, not just hg4j's own synthetic bundles.
        File realBundleFile = new File(System.getProperty("hg4j.clonebundle.fixture",
                "/private/tmp/claude-501/-Users-mzc01-search5-yona-convert-hg4j/25423f6f-246e-4eb5-b818-6c34e183a904/scratchpad/full.hg"));
        Assumptions.assumeTrue(realBundleFile.exists(), "Real-hg-generated bundle fixture not found: " + realBundleFile);
        byte[] realBundleBytes = Files.readAllBytes(realBundleFile.toPath());
        assertEquals("HG20", new String(realBundleBytes, 0, 4, StandardCharsets.US_ASCII));

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/full.hg", exchange -> {
                exchange.sendResponseHeaders(200, realBundleBytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(realBundleBytes);
                }
            });
            server.start();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/full.hg";

            File destDir = tempDir.resolve("dest").toFile();
            HgRepository destRepo = Hg.init().setDirectory(destDir).call();

            ClonebundlesCommand.downloadAndApply(destRepo, url);

            File clIdx = new File(destRepo.getStoreDir(), "00changelog.i");
            File clDat = new File(destRepo.getStoreDir(), "00changelog.d");
            Revlog cl = destRepo.getRevlog(clIdx, clDat);
            assertEquals(2, cl.getRevisionCount(), "Both real-hg commits from the bundle must be applied");
        } finally {
            server.stop(0);
        }
    }
}
