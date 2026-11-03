package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lfs.HgLfsPointer;
import io.github.search5.hg4j.lib.HgRepository;
import org.eclipse.jetty.server.Server;
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
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 의도적 차이 포팅 3번: with {@code setEnforceWebPermissions(true)} the {@code [web]} access rules decide every request of
 * hg4j's HTTP server exactly like they do on a real {@code hg serve}: the same raw wire and LFS requests get the same status and
 * the same body ("0\npush not authorized\n" and friends) under each {@code [web]} configuration.
 */
@Tag("interop")
class WebPermissionsParityInteropTest {

    @TempDir
    Path tempDir;

    private Path base;
    private byte[] payload;
    private String oid;
    private final HttpClient client = HttpClient.newHttpClient();
    private int round;

    @BeforeEach
    void baseRepository() throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled());
        base = tempDir.resolve("base");
        Files.createDirectories(base);
        File dir = base.toFile();
        HgTestUtils.hg(dir, "--config", "extensions.lfs=", "init");
        payload = new byte[300];
        new Random(System.nanoTime()).nextBytes(payload);
        oid = HgLfsPointer.sha256Hex(payload);
    }

    private record Answer(int status, String body) {
    }

    private Answer send(int port, String method, String path, Map<String, String> headers, byte[] body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(request::header);
        HttpResponse<byte[]> answer = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Answer(answer.statusCode(), new String(answer.body(), StandardCharsets.ISO_8859_1));
    }

    /** Both servers get the same [web] section; every request must be answered alike when it is refused. */
    private void compare(String web, boolean pushAllowed, boolean readAllowed) throws Exception {
        String hgrc = "[extensions]\nlfs =\n[experimental]\nlfs.disableusercache = true\n[web]\n" + web;
        Path realRepo = tempDir.resolve("real" + round);
        Path ownRepo = tempDir.resolve("own" + round);
        round++;
        Files.createDirectories(realRepo);
        Files.createDirectories(ownRepo);
        ParitySupport.copyTree(base, realRepo);
        ParitySupport.copyTree(base, ownRepo);
        Files.writeString(realRepo.resolve(".hg/hgrc"), hgrc);
        Files.writeString(ownRepo.resolve(".hg/hgrc"), hgrc);
        Map<String, Map<String, String>> none = new LinkedHashMap<>();
        try (ParitySupport.RealServe real = new ParitySupport.RealServe(realRepo)) {
            Server own = HgTestUtils.startServlet(new HgHttpWireServer(new HgRepository(ownRepo.toFile()))
                    .setEnforceWebPermissions(true));
            try {
                int ownPort = HgTestUtils.port(own);
                String[][] requests = {
                        {"GET", "/?cmd=heads", ""},
                        {"POST", "/?cmd=pushkey&namespace=bookmarks&key=b&old=&new=" + "0".repeat(40), ""},
                        {"GET", "/?cmd=pushkey&namespace=bookmarks&key=b&old=&new=" + "0".repeat(40), ""},
                        {"POST", "/.git/info/lfs/objects/batch", "batch"},
                        {"PUT", "/.hg/lfs/objects/" + oid, "put"},
                        {"GET", "/.hg/lfs/objects/" + oid, ""},
                };
                for (String[] r : requests) {
                    Map<String, String> headers = new LinkedHashMap<>();
                    byte[] body = null;
                    if (r[2].equals("batch")) {
                        headers.put("Content-Type", "application/vnd.git-lfs+json");
                        headers.put("Accept", "application/vnd.git-lfs+json");
                        body = ("{\"objects\":[{\"oid\":\"" + oid + "\",\"size\":" + payload.length + "}],\"operation\":\"upload\"}")
                                .getBytes(StandardCharsets.UTF_8);
                    } else if (r[2].equals("put")) {
                        body = payload;
                    }
                    Answer expected = send(real.port, r[0], r[1], headers, body);
                    Answer actual = send(ownPort, r[0], r[1], headers, body);
                    String what = r[0] + " " + r[1].replace(oid, "<oid>") + " under [web]\n" + web;
                    if (expected.status() == 401 || expected.status() == 403 || expected.status() == 405) {
                        assertEquals(expected, actual, what);
                    } else {
                        assertTrue(actual.status() != 401 && actual.status() != 403 && actual.status() != 405,
                                "real hg lets it through (" + expected.status() + ") but hg4j refused: " + actual + "\n" + what);
                    }
                }
            } finally {
                HgTestUtils.stop(own);
            }
        }
    }

    @Test
    void pushIsRefusedByDefaultBecauseOfPushSsl() throws Exception {
        compare("", false, true);
    }

    @Test
    void withoutAllowPushNobodyMayPush() throws Exception {
        compare("push_ssl = false\n", false, true);
    }

    @Test
    void allowPushForEveryoneLetsEverythingThrough() throws Exception {
        compare("push_ssl = false\nallow-push = *\n", true, true);
    }

    @Test
    void theUnderscoreSpellingOfAllowPushIsAccepted() throws Exception {
        compare("push_ssl = false\nallow_push = *\n", true, true);
    }

    @Test
    void anAllowedNamedUserDoesNotCoverAnAnonymousRequest() throws Exception {
        compare("push_ssl = false\nallow-push = alice\n", false, true);
    }

    @Test
    void denyPushBeatsAllowPush() throws Exception {
        compare("push_ssl = false\nallow-push = *\ndeny_push = *\n", false, true);
    }

    @Test
    void denyReadRefusesEverythingToAnAnonymousRequest() throws Exception {
        compare("push_ssl = false\nallow-push = *\ndeny_read = *\n", false, false);
    }

    @Test
    void allowReadForAnotherUserRefusesAnAnonymousRequest() throws Exception {
        compare("push_ssl = false\nallow-push = *\nallow_read = bob\n", false, false);
    }

    @Test
    void allowPullOffRefusesReading() throws Exception {
        compare("push_ssl = false\nallow-push = *\nallowpull = false\n", true, false);
    }
}
