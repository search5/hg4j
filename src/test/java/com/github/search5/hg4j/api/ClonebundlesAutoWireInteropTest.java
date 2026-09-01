package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;

/**
 * End-to-end verification that {@link CloneCommand}/{@link FetchCommand} automatically detect
 * and use a real Mercurial server's Clonebundles offer — the "automatic wiring" half of the
 * feature (item 6/7 in the Clonebundles plan), as opposed to the manually-invoked primitives
 * already covered by {@link ClonebundlesCommandTest}/{@link ClonebundlesRealHgInteropTest}.
 *
 * <p>Runs a fresh, disposable Mercurial 6.0 container per test (started/torn down here, not a
 * pre-existing manually-started one like {@link ClonebundlesRealHgInteropTest}) with the {@code
 * clonebundles} extension enabled and a manifest pointing back at a local {@link HttpServer} this
 * test controls, so it can assert the download actually happened.</p>
 */
@Tag("interop")
public class ClonebundlesAutoWireInteropTest {

    private static final String IMAGE = "localhost/hg4j-test-mercurial-6.0";

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(dockerAvailable(), "Docker is not available or the " + IMAGE + " image is missing. Skipping.");
    }

    private static boolean dockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "image", "inspect", IMAGE).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void runDocker(String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "docker";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        int code = p.waitFor();
        if (code != 0) {
            throw new AssertionError("docker " + String.join(" ", args) + " failed (" + code + "): "
                    + new String(out, StandardCharsets.UTF_8));
        }
    }

    @Test
    public void cloneAutomaticallyBypassesTheWireProtocolViaAClonebundleWhenTheServerAdvertisesOne(
            @TempDir Path tempDir) throws Exception {
        AtomicInteger downloadHits = new AtomicInteger();
        byte[][] bundleBytesHolder = new byte[1][];

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

        String containerName = "hg4j-clonebundle-autowire-" + System.nanoTime();
        int serverPort = 18098;
        try {
            // The manifest URL is resolved by hg4j (the clone client), which runs as this test's
            // own host JVM -- not by the container -- so it must point straight at 127.0.0.1,
            // not at a docker-internal DNS alias meant for containers reaching back out to the host.
            String manifestUrl = "http://127.0.0.1:" + bundlePort + "/full.hg";
            runDocker("run", "-d", "--name", containerName, "-p", serverPort + ":8099", IMAGE, "sh", "-c",
                    "cd /repo && hg bundle --all --type none-v2 /tmp/full.hg && "
                            + "echo '" + manifestUrl + " BUNDLESPEC=none-v2' > .hg/clonebundles.manifest && "
                            + "hg serve -p 8099 --address 0.0.0.0 --config web.allow-push=* "
                            + "--config web.push_ssl=false --config extensions.clonebundles=");
            Thread.sleep(2000);

            File tmpBundle = File.createTempFile("hg4j-real-bundle-", ".hg");
            try {
                runDocker("cp", containerName + ":/tmp/full.hg", tmpBundle.getAbsolutePath());
                bundleBytesHolder[0] = Files.readAllBytes(tmpBundle.toPath());
            } finally {
                tmpBundle.delete();
            }
            assertEquals("HG20", new String(bundleBytesHolder[0], 0, 4, StandardCharsets.US_ASCII));

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
            assertEquals(2, cl.getRevisionCount(), "Both real-hg commits from the container repo must be present");
            assertTrue(new File(destDir, "hello.txt").exists(), "Working copy must be checked out after the clone");
        } finally {
            try {
                runDocker("rm", "-f", containerName);
            } catch (Exception ignored) {
            }
            bundleServer.stop(0);
        }
    }
}
