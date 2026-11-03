package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lfs.HgLfsManager;
import io.github.search5.hg4j.lfs.HgLfsPointer;
import io.github.search5.hg4j.lib.HgRepository;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LFS plan Phase 5: the LFS endpoints of {@code hg4j}'s HTTP server answer exactly like the ones of a real {@code hg serve} (lfs
 * extension). The same raw requests go to both servers, each over its own copy of one repository made by real hg, and status
 * code, content type and body must be the same (host names and expiry times aside).
 */
@Tag("interop")
class LfsServerParityInteropTest {

    private static final String MEDIA_TYPE = "application/vnd.git-lfs+json";

    @TempDir
    Path tempDir;

    private ParitySupport.RealServe real;
    private Server own;
    private int realPort;
    private int ownPort;
    private Path realRepo;
    private Path ownRepo;
    private byte[] payload;
    private String oid;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void bothServers() throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled());
        Path base = tempDir.resolve("base");
        Files.createDirectories(base);
        File dir = base.toFile();
        HgTestUtils.hg(dir, "--config", "extensions.lfs=", "init");
        Files.writeString(new File(dir, ".hg/hgrc").toPath(),
                "[extensions]\nlfs =\n[lfs]\ntrack = size(\">100\")\n[web]\npush_ssl = false\nallow-push = *\n"
                        + "[experimental]\nlfs.disableusercache = true\n");
        payload = new byte[700];
        new Random(System.nanoTime()).nextBytes(payload);
        Files.write(new File(dir, "big.bin").toPath(), payload);
        HgTestUtils.hg(dir, "add");
        HgTestUtils.hg(dir, "commit", "-m", "one", "-u", "t");
        oid = HgLfsPointer.sha256Hex(payload);

        realRepo = tempDir.resolve("real");
        ownRepo = tempDir.resolve("own");
        Files.createDirectories(realRepo);
        Files.createDirectories(ownRepo);
        ParitySupport.copyTree(base, realRepo);
        ParitySupport.copyTree(base, ownRepo);

        real = new ParitySupport.RealServe(realRepo);
        realPort = real.port;
        own = HgTestUtils.startServlet(new HgHttpWireServer(new HgRepository(ownRepo.toFile())));
        ownPort = HgTestUtils.port(own);
    }

    @AfterEach
    void stop() {
        if (real != null) {
            real.close();
        }
        if (own != null) {
            HgTestUtils.stop(own);
        }
    }

    /** What a server answered, reduced to what must be the same: status, content type and a normalized body. */
    private record Answer(int status, String contentType, Object body) {
    }

    private Answer send(int port, String method, String path, Map<String, String> headers, byte[] body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(request::header);
        HttpResponse<byte[]> answer = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        // Jetty writes "text/plain;charset=utf-8" where hg writes "text/plain; charset=utf-8": the same value, spaced differently
        String contentType = answer.headers().firstValue("Content-Type").orElse("").replace("; ", ";").toLowerCase(java.util.Locale.ROOT);
        Object normalized;
        if (contentType.startsWith(MEDIA_TYPE)) {
            normalized = normalize(new String(answer.body(), StandardCharsets.UTF_8), port);
        } else {
            normalized = new String(answer.body(), StandardCharsets.ISO_8859_1);
        }
        return new Answer(answer.statusCode(), contentType, normalized);
    }

    /** The batch answer as a map, with the host name and the expiry time (which differ per run and server) replaced. */
    @SuppressWarnings("unchecked")
    private static Object normalize(String json, int port) throws Exception {
        Object parsed = new HgLfsManager.MapJsonParser(json).parse();
        scrub(parsed, port);
        return parsed;
    }

    @SuppressWarnings("unchecked")
    private static void scrub(Object node, int port) {
        if (node instanceof Map<?, ?> raw) {
            Map<String, Object> map = (Map<String, Object>) raw;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                if (entry.getKey().equals("href") && entry.getValue() instanceof String href) {
                    entry.setValue(href.replace("127.0.0.1:" + port, "HOST"));
                } else if (entry.getKey().equals("expires_at")) {
                    assertTrue(String.valueOf(entry.getValue()).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ"),
                            "expires_at has the original's layout: " + entry.getValue());
                    entry.setValue("EXPIRY");
                } else {
                    scrub(entry.getValue(), port);
                }
            }
        } else if (node instanceof List<?> list) {
            list.forEach(element -> scrub(element, port));
        }
    }

    private void assertSame(String what, String method, String path, Map<String, String> headers, byte[] body) throws Exception {
        Answer expected = send(realPort, method, path, headers, body);
        Answer actual = send(ownPort, method, path, headers, body);
        assertEquals(expected, actual, what);
    }

    private static Map<String, String> lfsHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", MEDIA_TYPE);
        headers.put("Accept", MEDIA_TYPE);
        return headers;
    }

    private static byte[] json(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private String batch(String operation, String... oidAndSize) {
        StringBuilder objects = new StringBuilder();
        for (int i = 0; i < oidAndSize.length; i += 2) {
            if (i > 0) {
                objects.append(',');
            }
            objects.append("{\"oid\":\"").append(oidAndSize[i]).append("\",\"size\":").append(oidAndSize[i + 1]).append('}');
        }
        return "{\"operation\":\"" + operation + "\",\"transfers\":[\"basic\"],\"objects\":[" + objects + "]}";
    }

    private static final String BATCH = "/.git/info/lfs/objects/batch";
    private static final String MISSING = "ab".repeat(32);

    @Test
    void batchDownloadOfStoredAndMissingObjects() throws Exception {
        assertSame("a stored object", "POST", BATCH, lfsHeaders(), json(batch("download", oid, "700")));
        assertSame("a missing object", "POST", BATCH, lfsHeaders(), json(batch("download", MISSING, "5")));
        assertSame("both at once", "POST", BATCH, lfsHeaders(), json(batch("download", oid, "700", MISSING, "5")));
    }

    @Test
    void batchUploadSkipsWhatTheServerHasAndOffersTheRest() throws Exception {
        assertSame("a stored object needs no upload", "POST", BATCH, lfsHeaders(), json(batch("upload", oid, "700")));
        assertSame("a new object gets an upload action", "POST", BATCH, lfsHeaders(), json(batch("upload", MISSING, "5")));
    }

    @Test
    void batchAnswerCarriesTheBasicAuthorizationBack() throws Exception {
        Map<String, String> headers = lfsHeaders();
        headers.put("Authorization", "Basic dXNlcjpwYXNz");
        assertSame("basic credentials are echoed in the action header", "POST", BATCH, headers, json(batch("download", oid, "700")));
    }

    @Test
    void batchRequestsThatAreRefused() throws Exception {
        assertSame("GET", "GET", BATCH, lfsHeaders(), null);
        Map<String, String> wrongType = lfsHeaders();
        wrongType.put("Content-Type", "application/json");
        assertSame("wrong Content-Type", "POST", BATCH, wrongType, json(batch("download", oid, "700")));
        Map<String, String> wrongAccept = lfsHeaders();
        wrongAccept.put("Accept", "application/json");
        assertSame("wrong Accept", "POST", BATCH, wrongAccept, json(batch("download", oid, "700")));
        assertSame("no basic transfer", "POST", BATCH, lfsHeaders(),
                json("{\"operation\":\"download\",\"transfers\":[\"tus\"],\"objects\":[]}"));
        assertSame("unknown operation", "POST", BATCH, lfsHeaders(), json(batch("delete", oid, "700")));
    }

    @Test
    void basicTransferDownloadUploadAndErrors() throws Exception {
        String stored = "/.hg/lfs/objects/" + oid;
        assertSame("GET a stored blob", "GET", stored, Map.of(), null);
        assertSame("wrong number of path parts", "GET", "/.hg/lfs/objects/" + oid + "/extra", Map.of(), null);
        assertSame("unsupported LFS transfer method (405)", "POST", stored, Map.of(), new byte[0]);

        byte[] fresh = new byte[50];
        new Random(7).nextBytes(fresh);
        String freshOid = HgLfsPointer.sha256Hex(fresh);
        Map<String, String> octet = Map.of("Content-Type", "application/octet-stream");
        assertSame("PUT a new blob (201)", "PUT", "/.hg/lfs/objects/" + freshOid, octet, fresh);
        assertSame("PUT the same blob again (200)", "PUT", "/.hg/lfs/objects/" + freshOid, octet, fresh);
        assertSame("GET what was just uploaded", "GET", "/.hg/lfs/objects/" + freshOid, Map.of(), null);
        byte[] wrong = fresh.clone();
        wrong[0] ^= 1;
        assertSame("PUT content that does not match its id (422)", "PUT", "/.hg/lfs/objects/" + MISSING, octet, wrong);
    }

    @Test
    void aCorruptStoredBlobIsReported() throws Exception {
        for (Path repo : List.of(realRepo, ownRepo)) {
            File blob = new File(repo.toFile(), ".hg/store/lfs/objects/" + oid.substring(0, 2) + "/" + oid.substring(2));
            assertTrue(blob.isFile(), "the blob exists in " + repo);
            byte[] bad = payload.clone();
            bad[3] ^= 1;
            Files.write(blob.toPath(), bad);
        }
        assertSame("batch download of a corrupt blob (422)", "POST", BATCH, lfsHeaders(), json(batch("download", oid, "700")));
        assertSame("batch upload asks for a new copy of a corrupt blob", "POST", BATCH, lfsHeaders(), json(batch("upload", oid, "700")));
        assertSame("GET a corrupt blob (422)", "GET", "/.hg/lfs/objects/" + oid, Map.of(), null);
    }

    @Test
    void theLfsEndpointsAreOffWithoutTheExtensionOrWhenSwitchedOff() throws Exception {
        for (Path repo : List.of(realRepo, ownRepo)) {
            Files.writeString(repo.resolve(".hg/hgrc"), "[extensions]\nlfs =\n[experimental]\nlfs.serve = false\n"
                    + "[web]\npush_ssl = false\nallow-push = *\n");
        }
        real.close();
        own.stop();
        // restart both over the changed configuration
        real = new ParitySupport.RealServe(realRepo);
        realPort = real.port;
        own = HgTestUtils.startServlet(new HgHttpWireServer(new HgRepository(ownRepo.toFile())));
        ownPort = HgTestUtils.port(own);
        // what a server does with an unknown path is its own business (hgweb answers with an HTML error page); the LFS answer
        // must not come
        for (int port : new int[] {realPort, ownPort}) {
            Answer answer = send(port, "POST", BATCH, lfsHeaders(), json(batch("download", oid, "700")));
            assertTrue(answer.status() >= 400, "port " + port + " must not answer the batch request: " + answer.status());
            assertFalse(String.valueOf(answer.body()).contains("transfer"), "no LFS body on port " + port);
        }
    }

    private List<String> capabilityTokens(int port) throws Exception {
        HttpResponse<String> answer = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/?cmd=capabilities")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, answer.statusCode());
        return List.of(answer.body().trim().split(" "));
    }

    @Test
    void capabilitiesAreTheOriginalsWithTheLfsTokens() throws Exception {
        List<String> expected = capabilityTokens(realPort);
        assertTrue(expected.contains("lfs") && expected.contains("lfs-serve"),
                "the oracle advertises both for a repository that requires lfs: " + expected);
        assertEquals(expected, capabilityTokens(ownPort));
    }

    @Test
    void withoutTheLfsRequirementOnlyTheLfsTokenIsAdded() throws Exception {
        // a repository with the extension on but no LFS revision yet
        Path plain = tempDir.resolve("plain-real");
        Path plainOwn = tempDir.resolve("plain-own");
        for (Path dir : List.of(plain, plainOwn)) {
            Files.createDirectories(dir);
            HgTestUtils.hg(dir.toFile(), "init");
            Files.writeString(dir.resolve(".hg/hgrc"), "[extensions]\nlfs =\n");
        }
        try (ParitySupport.RealServe realPlain = new ParitySupport.RealServe(plain)) {
            Server ownPlain = HgTestUtils.startServlet(new HgHttpWireServer(new HgRepository(plainOwn.toFile())));
            try {
                List<String> expected = capabilityTokens(realPlain.port);
                assertTrue(expected.contains("lfs") && !expected.contains("lfs-serve"), expected.toString());
                assertEquals(expected, capabilityTokens(HgTestUtils.port(ownPlain)));
            } finally {
                HgTestUtils.stop(ownPlain);
            }
        }
    }
}
