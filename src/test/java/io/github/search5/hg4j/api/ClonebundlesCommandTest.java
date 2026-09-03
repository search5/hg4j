package io.github.search5.hg4j.api;

import io.github.search5.hg4j.bundle.ChangegroupParser;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.errors.HgProtocolException;
import io.github.search5.hg4j.storage.Revlog;
import java.nio.charset.StandardCharsets;

/**
 * Verifies the "bypass" half of Clonebundles: downloading a bundle from an arbitrary externally
 * hosted URL (a plain HTTP GET with no wire-protocol framing at all — the whole point of the
 * feature is to route large-clone traffic away from the wire protocol server) and applying it to
 * a fresh local repository via the existing {@link UnbundleCommand} pipeline.
 */
public class ClonebundlesCommandTest {

    @Test
    public void downloadsAndAppliesABundleFromAPlainHttpUrl(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        File f = new File(srcDir, "a.txt");
        Files.writeString(f.toPath(), "hello clonebundles");
        new AddCommand(srcRepo).call();
        byte[] commitNode = new CommitCommand(srcRepo).setMessage("v1").setAuthor("dev").call();

        ChangegroupParser.ChangegroupBundle bundle =
                HgTestUtils.createMockBundleFromRepo(srcRepo);
        byte[] changegroupBytes = HgTestUtils.serializeBundleToBytes(bundle);
        // HgTestUtils.serializeBundleToBytes() produces the raw changegroup body only (meant for
        // direct ChangegroupParser.parseBundle() consumption); a real .hg bundle FILE -- which is
        // exactly what a clonebundle download is -- needs the "HG10UN" container header UnbundleCommand expects.
        byte[] bundleBytes = new byte[6 + changegroupBytes.length];
        System.arraycopy("HG10UN".getBytes(StandardCharsets.US_ASCII), 0, bundleBytes, 0, 6);
        System.arraycopy(changegroupBytes, 0, bundleBytes, 6, changegroupBytes.length);

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/bundles/full.hg", exchange -> {
                exchange.sendResponseHeaders(200, bundleBytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bundleBytes);
                }
            });
            server.start();
            String bundleUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/bundles/full.hg";

            File destDir = tempDir.resolve("dest").toFile();
            HgRepository destRepo = Hg.init().setDirectory(destDir).call();

            ClonebundlesCommand.downloadAndApply(destRepo, bundleUrl);

            File clIdx = new File(destRepo.getStoreDir(), "00changelog.i");
            File clDat = new File(destRepo.getStoreDir(), "00changelog.d");
            Revlog cl = destRepo.getRevlog(clIdx, clDat);
            assertEquals(1, cl.getRevisionCount());
            assertEquals(NodeIdUtil.toHex(commitNode), NodeIdUtil.toHex(cl.getIndexRecord(0).getNodeId()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void propagatesTheDownloadFailureRatherThanFallingBackSilently(@TempDir Path tempDir) throws Exception {
        // Real hg's own design: a clonebundle download failure must fail the whole clone, never
        // fall back to a normal pull transparently -- see the plan doc for why (falling back
        // silently defeats the point of offloading clone load in the first place).
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/missing.hg", exchange -> exchange.sendResponseHeaders(404, -1));
            server.start();
            String bundleUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/missing.hg";

            assertThrows(HgProtocolException.class, () ->
                    ClonebundlesCommand.downloadAndApply(destRepo, bundleUrl));
        } finally {
            server.stop(0);
        }
    }
}
