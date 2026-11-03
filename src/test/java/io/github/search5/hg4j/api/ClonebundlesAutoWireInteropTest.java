package io.github.search5.hg4j.api;

import com.sun.net.httpserver.HttpServer;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.testsupport.LocalHg;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end verification that {@link CloneCommand}/{@link FetchCommand} automatically detect
 * and use a real Mercurial server's Clonebundles offer -- the "automatic wiring" half of the
 * feature (item 6/7 in the Clonebundles plan), as opposed to the manually-invoked primitives
 * already covered by {@link ClonebundlesCommandTest}/{@link ClonebundlesRealHgInteropTest}.
 *
 * <p>Runs a fresh, disposable real {@code hg serve} process per test (built by
 * {@code scripts/build-local-hg.sh}) with the {@code clonebundles} extension enabled and a manifest
 * pointing back at a local {@link HttpServer} this test controls, so it can assert the download
 * actually happened.</p>
 */
@Tag("interop")
public class ClonebundlesAutoWireInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(LocalHg.isAvailable(), LocalHg::unavailableReason);
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void awaitListening(int port, Process server) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (!server.isAlive()) {
                throw new AssertionError("hg serve exited early with status " + server.exitValue());
            }
            try (Socket ignored = new Socket("127.0.0.1", port)) {
                return;
            } catch (Exception notYet) {
                Thread.sleep(100);
            }
        }
        throw new AssertionError("hg serve did not start listening on port " + port);
    }

    @Test
    public void cloneAutomaticallyBypassesTheWireProtocolViaAClonebundleWhenTheServerAdvertisesOne(
            @TempDir Path tempDir) throws Exception {
        AtomicInteger downloadHits = new AtomicInteger();
        byte[][] bundleBytesHolder = new byte[1][];

        // The source repository the real server serves: two commits, as the old container's /repo had.
        Path serverRepo = Files.createDirectories(tempDir.resolve("server-repo"));
        LocalHg.hgIn(tempDir, "server-repo", "init", ".");
        Files.writeString(serverRepo.resolve("hello.txt"), "hello\n");
        LocalHg.hgIn(tempDir, "server-repo", "add", "hello.txt");
        LocalHg.hgIn(tempDir, "server-repo", "commit", "-u", "dev", "-m", "first");
        Files.writeString(serverRepo.resolve("world.txt"), "world\n");
        LocalHg.hgIn(tempDir, "server-repo", "add", "world.txt");
        LocalHg.hgIn(tempDir, "server-repo", "commit", "-u", "dev", "-m", "second");

        Path bundleFile = tempDir.resolve("full.hg");
        LocalHg.hgIn(tempDir, "server-repo", "bundle", "--all", "--type", "none-v2", bundleFile.toString());
        bundleBytesHolder[0] = Files.readAllBytes(bundleFile);
        assertEquals("HG20", new String(bundleBytesHolder[0], 0, 4, StandardCharsets.US_ASCII));

        HttpServer bundleServer = HttpServer.create(new InetSocketAddress(0), 0);
        bundleServer.createContext("/full.hg", exchange -> {
            downloadHits.incrementAndGet();
            byte[] body = bundleBytesHolder[0];
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        bundleServer.start();
        int bundlePort = bundleServer.getAddress().getPort();

        Process hgServe = null;
        try {
            // The manifest URL is resolved by hg4j (the clone client), which runs as this test's own
            // JVM, so it points straight at 127.0.0.1.
            String manifestUrl = "http://127.0.0.1:" + bundlePort + "/full.hg";
            Files.writeString(serverRepo.resolve(".hg/clonebundles.manifest"),
                    manifestUrl + " BUNDLESPEC=none-v2\n");

            int serverPort = freePort();
            ProcessBuilder pb = LocalHg.processBuilder(serverRepo, "serve", "-p", String.valueOf(serverPort),
                    "--address", "127.0.0.1", "--config", "web.allow-push=*", "--config", "web.push_ssl=false",
                    "--config", "extensions.clonebundles=");
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            hgServe = pb.start();
            awaitListening(serverPort, hgServe);

            File destDir = tempDir.resolve("dest").toFile();
            HgRepository destRepo = new CloneCommand()
                    .setSource("http://127.0.0.1:" + serverPort)
                    .setDirectory(destDir)
                    .call();

            assertTrue(downloadHits.get() > 0,
                    "CloneCommand must have automatically downloaded the clonebundle from the manifest URL");

            File clIdx = new File(destRepo.getStoreDir(), "00changelog.i");
            File clDat = new File(destRepo.getStoreDir(), "00changelog.d");
            Revlog cl = destRepo.getRevlog(clIdx, clDat);
            assertEquals(2, cl.getRevisionCount(), "Both real-hg commits from the served repo must be present");
            assertTrue(new File(destDir, "hello.txt").exists(), "Working copy must be checked out after the clone");
        } finally {
            if (hgServe != null) {
                hgServe.destroy();
                hgServe.waitFor();
            }
            bundleServer.stop(0);
        }
    }
}
