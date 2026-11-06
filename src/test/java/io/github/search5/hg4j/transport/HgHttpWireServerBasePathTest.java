package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.lib.HgRepository;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HgHttpWireServer#setBasePath(String)}: a server that serves one repository under a path of its own
 * ({@code /owner/project}) answers the LFS endpoints and the wire protocol there, and nothing outside of it.
 */
class HgHttpWireServerBasePathTest {

    private static final String OID = "1234567890abcdef".repeat(4);
    private static final String BATCH = ".git/info/lfs/objects/batch";
    private static final String BATCH_BODY = "{\"operation\":\"download\",\"objects\":[{\"oid\":\"" + OID + "\",\"size\":5}]}";

    @TempDir
    Path tempDir;

    private Server server;
    private HgHttpWireServer wire;

    @BeforeEach
    void start() throws Exception {
        File dir = tempDir.resolve("repo").toFile();
        Hg.init().setDirectory(dir).call();
        Files.writeString(new File(dir, ".hg/hgrc").toPath(), "[extensions]\nlfs =\n");
        HgRepository repo = new HgRepository(dir);
        Files.writeString(new File(dir, "a.txt").toPath(), "a\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("a").setAuthor("t").call();
        wire = new HgHttpWireServer(repo);
        server = HgTestUtils.startServlet(wire);
    }

    @AfterEach
    void stop() {
        HgTestUtils.stop(server);
    }

    private int status(String method, String path) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + HgTestUtils.port(server) + path));
        if ("POST".equals(method)) {
            request.header("Content-Type", "application/vnd.git-lfs+json").header("Accept", "application/vnd.git-lfs+json")
                    .POST(HttpRequest.BodyPublishers.ofString(BATCH_BODY));
        } else {
            request.GET();
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private String body(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + HgTestUtils.port(server) + path)).GET().build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void byDefaultTheRepositoryIsServedAtTheRoot() throws Exception {
        assertEquals(200, status("POST", "/" + BATCH));
        assertTrue(body("/?cmd=capabilities").contains("getbundle"));
        assertEquals(404, status("POST", "/owner/project/" + BATCH));
    }

    @Test
    void theLfsEndpointsAreFoundUnderTheBasePath() throws Exception {
        wire.setBasePath("/owner/project");
        assertEquals(200, status("POST", "/owner/project/" + BATCH));
        assertEquals(404, status("POST", "/" + BATCH));
    }

    @Test
    void theWireProtocolIsServedUnderTheBasePathOnly() throws Exception {
        wire.setBasePath("/owner/project");
        assertTrue(body("/owner/project?cmd=capabilities").contains("getbundle"));
        assertTrue(body("/owner/project/?cmd=capabilities").contains("getbundle"));
        assertEquals(404, status("GET", "/?cmd=capabilities"));
        assertEquals(404, status("GET", "/other/project?cmd=capabilities"));
    }

    @Test
    void aPathThatOnlyStartsLikeTheBasePathIsNotUnderIt() throws Exception {
        wire.setBasePath("/owner/project");
        assertEquals(404, status("POST", "/owner/projectx/" + BATCH));
        assertEquals(404, status("GET", "/owner/projectx?cmd=capabilities"));
    }

    @Test
    void aTrailingSlashAndAMissingLeadingSlashAreNormalized() throws Exception {
        wire.setBasePath("owner/project/");
        assertEquals(200, status("POST", "/owner/project/" + BATCH));
        wire.setBasePath("");
        assertEquals(200, status("POST", "/" + BATCH));
        wire.setBasePath("/");
        assertEquals(200, status("POST", "/" + BATCH));
    }

    private String uploadBatch(String path) throws Exception {
        String upload = "{\"operation\":\"upload\",\"objects\":[{\"oid\":\"" + OID + "\",\"size\":5}]}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + HgTestUtils.port(server) + path))
                .header("Content-Type", "application/vnd.git-lfs+json").header("Accept", "application/vnd.git-lfs+json")
                .POST(HttpRequest.BodyPublishers.ofString(upload)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void theBatchHrefsCarryTheBasePath() throws Exception {
        wire.setBasePath("/owner/project");
        String href = "http://127.0.0.1:" + HgTestUtils.port(server) + "/owner/project/.hg/lfs/objects/" + OID;
        assertTrue(uploadBatch("/owner/project/" + BATCH).contains("\"href\": \"" + href + "\""));
    }

    @Test
    void theBatchHrefsAtTheRootStayAsTheyWere() throws Exception {
        String href = "http://127.0.0.1:" + HgTestUtils.port(server) + "/.hg/lfs/objects/" + OID;
        assertTrue(uploadBatch("/" + BATCH).contains("\"href\": \"" + href + "\""));
    }
}
