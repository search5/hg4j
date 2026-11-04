package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.shape.StoreShards;
import io.github.search5.hg4j.transport.HgHttpWireServer;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A shaped clone that takes its data from sharded clone bundles: the server generates one stream bundle per shard, lists them in its
 * clone bundles manifest as {@code peer-bundle-cache://} entries, and the client picks the group of its shape, applies the top level
 * bundle first and then the others, and only then pulls what is newer.
 */
class ShardedCloneBundlesTest {

    private static final String CONFIG = "version = 0\n[[shards]]\nname = \"foo-files\"\npaths = [\"foo\"]\n[[shards]]\nname = \"bar-files\"\npaths = [\"bar\"]\n"
            + "[[profiles]]\nname = \"foo\"\nincludes = [\"foo-files\"]\npublic = true\n[[profiles]]\nname = \"bar\"\nincludes = [\"bar-files\"]\npublic = true\n";

    @TempDir
    Path tempDir;

    private HgRepository serverRepo;
    private File serverDir;
    private Server server;

    private void commit(String message) throws Exception {
        new AddCommand(serverRepo).call();
        new CommitCommand(serverRepo).setMessage(message).setAuthor("t").call();
    }

    @BeforeEach
    void startServer() throws Exception {
        serverDir = tempDir.resolve("server").toFile();
        Hg.init().setDirectory(serverDir).call().close();
        Files.writeString(new File(serverDir, ".hg/hgrc").toPath(), "[experimental]\nnarrow = true\nadvertise-shapes = true\n"
                + "server.stream-narrow-clones = true\n[extensions]\nclonebundles =\n[server]\npeer-bundle-cache-root = shardedcache\n[ui]\ntimeout = 5\n");
        serverRepo = new HgRepository(serverDir);
        for (String sub : List.of("foo", "bar", "other")) {
            new File(serverDir, sub).mkdirs();
            Files.writeString(new File(serverDir, sub + "/f.txt").toPath(), sub + " 1\n");
        }
        Files.writeString(new File(serverDir, "top.txt").toPath(), "top\n");
        commit("first");
        new NarrowServerAdmin(serverRepo).shapeUpdate(CONFIG.getBytes(StandardCharsets.UTF_8), new NarrowServerAdmin.UpdateOptions(true, false, false));

        // the sharded bundles, and the manifest that names them
        List<File> bundles = new ShardedStreamBundlesCommand(serverRepo).call();
        StoreShards shards = StoreShards.fromRepository(serverRepo);
        StringBuilder manifest = new StringBuilder();
        List<StoreShards.ShardShape> infos = shards.shardedBundleInfo();
        for (int i = 0; i < infos.size(); i++) {
            String name = bundles.get(i).getName();
            String group = name.split("-")[2];
            manifest.append("peer-bundle-cache://").append(name).append(" BUNDLESPEC=none-v2;STREAM=v2;SHARD-ID=")
                    .append(infos.get(i).fingerprint().toText()).append(";BUNDLE-GROUP-ID=").append(group)
                    .append(infos.get(i).topLevel() ? ";BUNDLE-GROUP-TOP-LEVEL=1" : "").append('\n');
        }
        Files.writeString(new File(serverDir, ".hg/clonebundles.manifest").toPath(), manifest.toString());
        server = HgTestUtils.startServlet(new HgHttpWireServer(serverRepo));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            HgTestUtils.stop(server);
        }
    }

    private String url() {
        return "http://127.0.0.1:" + HgTestUtils.port(server);
    }

    @Test
    void theClientAppliesTheShardBundlesOfItsShapeAndGetsItsFiles() throws Exception {
        File dest = tempDir.resolve("foo-clone").toFile();
        NarrowCloneCommand clone = new NarrowCloneCommand().setSource(url()).setDirectory(dest).setShape("foo");
        clone.call();
        assertEquals("foo 1\n", Files.readString(new File(dest, "foo/f.txt").toPath()));
        assertFalse(new File(dest, "bar/f.txt").exists());
        // the data came from the bundles: the filelog of the other shard was never stored, the one of the shape was streamed
        assertTrue(new File(dest, ".hg/store/data/foo/f.txt.i").isFile());
        assertFalse(new File(dest, ".hg/store/data/bar/f.txt.i").exists(), "the bar shard is not part of this shape");
        List<String> messages = new ArrayList<>(clone.getMessages());
        assertTrue(messages.stream().anyMatch(m -> m.startsWith("applying 2 clone bundles")), messages.toString());
    }

    @Test
    void theSameCloneAgainstMercurialsOwnServer() throws Exception {
        File rustHg = new File(System.getProperty("user.home"), ".cache/hg4j/hg-rust-dev/bin/hg");
        org.junit.jupiter.api.Assumptions.assumeTrue(rustHg.isFile(), "the Rust build of Mercurial is not installed");
        // the repository of the server stays as built above; Mercurial serves it
        int port;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        ProcessBuilder pb = new ProcessBuilder(rustHg.getPath(), "serve", "--port", String.valueOf(port), "--address", "127.0.0.1",
                "--config", "web.allow-pull=true", "--config", "web.push_ssl=false", "--config", "web.allow-push=*")
                .directory(serverDir).redirectErrorStream(true);
        pb.environment().put("HGPLAIN", "1");
        pb.redirectOutput(tempDir.resolve("serve.log").toFile());
        Process serve = pb.start();
        try {
            long deadline = System.currentTimeMillis() + 15000;
            boolean up = false;
            while (System.currentTimeMillis() < deadline && !up) {
                try (java.net.Socket probe = new java.net.Socket("127.0.0.1", port)) {
                    up = true;
                } catch (java.io.IOException notYet) {
                    Thread.sleep(200);
                }
            }
            assertTrue(up, "hg serve did not start: " + Files.readString(tempDir.resolve("serve.log")));
            File dest = tempDir.resolve("foo-from-hg").toFile();
            NarrowCloneCommand clone = new NarrowCloneCommand().setSource("http://127.0.0.1:" + port).setDirectory(dest).setShape("foo");
            clone.call();
            assertEquals("foo 1\n", Files.readString(new File(dest, "foo/f.txt").toPath()));
            assertFalse(new File(dest, ".hg/store/data/bar/f.txt.i").exists());
            assertTrue(clone.getMessages().stream().anyMatch(m -> m.startsWith("applying 2 clone bundles")), clone.getMessages().toString());
        } finally {
            serve.destroy();
            serve.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
