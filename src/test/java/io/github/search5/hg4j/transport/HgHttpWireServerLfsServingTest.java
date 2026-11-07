package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.lib.HgRepository;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link HgHttpWireServer#setLfsServing(Boolean)}: an instance-level switch that acts like {@code [experimental] lfs.serve} of the
 * repository's hgrc, so a host can turn LFS serving of every repository on or off at once without editing each hgrc.
 */
class HgHttpWireServerLfsServingTest {

    private static final String OID = "1234567890abcdef".repeat(4);
    private static final String BATCH = "/.git/info/lfs/objects/batch";
    private static final String BATCH_BODY = "{\"operation\":\"download\",\"objects\":[{\"oid\":\"" + OID + "\",\"size\":5}]}";

    @TempDir
    Path tempDir;

    private Server server;

    @AfterEach
    void stop() {
        HgTestUtils.stop(server);
    }

    private HgHttpWireServer start(String hgrcExtra, java.util.function.Consumer<HgHttpWireServer> setup) throws Exception {
        File dir = tempDir.resolve("repo" + System.nanoTime()).toFile();
        Hg.init().setDirectory(dir).call();
        Files.writeString(new File(dir, ".hg/hgrc").toPath(), "[extensions]\nlfs =\n" + hgrcExtra);
        HgHttpWireServer wire = new HgHttpWireServer(new HgRepository(dir));
        setup.accept(wire);
        if (server != null) {
            HgTestUtils.stop(server);
        }
        server = HgTestUtils.startServlet(wire);
        return wire;
    }

    private int batch() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + HgTestUtils.port(server) + BATCH))
                .header("Content-Type", "application/vnd.git-lfs+json").header("Accept", "application/vnd.git-lfs+json")
                .POST(HttpRequest.BodyPublishers.ofString(BATCH_BODY)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void byDefaultTheRepositoryHgrcDecides() throws Exception {
        start("", w -> { });
        assertEquals(200, batch());
        start("[experimental]\nlfs.serve = false\n", w -> { });
        assertNotEquals(200, batch());
    }

    @Test
    void switchedOffTheLfsPathsAreNotHandledJustLikeLfsServeFalse() throws Exception {
        start("[experimental]\nlfs.serve = false\n", w -> { });
        int expected = batch();
        start("", w -> w.setLfsServing(false));
        assertEquals(expected, batch());
        assertNotEquals(200, batch());
    }

    @Test
    void switchedOnOverridesTheHgrcAndNullGoesBackToIt() throws Exception {
        start("[experimental]\nlfs.serve = false\n", w -> w.setLfsServing(true));
        assertEquals(200, batch());
        start("[experimental]\nlfs.serve = false\n", w -> w.setLfsServing(true).setLfsServing(null));
        assertNotEquals(200, batch());
    }

    @Test
    void theSwitchTakesEffectWithoutRestartingTheServer() throws Exception {
        HgHttpWireServer wire = start("", w -> { });
        assertEquals(200, batch());
        wire.setLfsServing(false);
        assertNotEquals(200, batch());
        wire.setLfsServing(null);
        assertEquals(200, batch());
    }
}
