package io.github.search5.hg4j.lfs.server;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.lfs.HgLfsManager;
import io.github.search5.hg4j.lfs.HgLfsPointer;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.transport.HgHttpWireServer;
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
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Backlog: server-side LFS HTTP endpoints (see {@code
 * llm-wiki/decisions/lfs-server-side-batch-api-plan.md}) -- verifies {@link HgLfsServer} against
 * the real {@code hg} CLI, the actual validation that matters for a production LFS server. Real hg
 * pushing an LFS-tracked file to an hg4j-served repository only carries the pointer text through
 * the ordinary wire protocol (that part is exercised by {@code HgHttpWireServerRealHgInteropTest}
 * already); the LFS blob itself travels over the separate Batch API + Basic Transfer channel this
 * test targets, mounted alongside {@link HgHttpWireServer} in the same repository URL space.
 */
@Tag("interop")
public class HgLfsServerRealHgInteropTest {

    private Server server;
    private HgRepository serverRepo;
    private File serverRepoDir;

    @BeforeEach
    public void setUp(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
        Assumptions.assumeTrue(isLfsExtensionAvailable(), "Native hg's lfs extension is not available. Skipping.");

        serverRepoDir = tempDir.resolve("server_repo").toFile();
        serverRepo = Hg.init().setDirectory(serverRepoDir).call();

        server = HgTestUtils.startServlets(Map.of(
                "/*", new HgHttpWireServer(serverRepo).enableLfsCapability(),
                "/.git/info/lfs/*", new HgLfsServer(serverRepo),
                "/.hg/lfs/*", new HgLfsServer(serverRepo)));
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            HgTestUtils.stop(server);
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + HgTestUtils.port(server) + "/";
    }

    private static boolean isLfsExtensionAvailable() {
        try {
            Process p = new ProcessBuilder("hg", "--config", "extensions.lfs=", "version").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest(data)) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * The core round trip the whole plan exists for: real hg (lfs enabled, low threshold) commits
     * a big file and pushes it to an EMPTY hg4j-served repository; a second, independent real hg
     * client then clones that repository and must receive the exact original bytes -- not the
     * pointer text -- plus a clean {@code hg verify}.
     */
    @Test
    public void realHgPushesAndAnotherRealHgClonesAnLfsFileThroughHg4jServedOverHttp(@TempDir Path tempDir) throws Exception {
        File sourceDir = tempDir.resolve("source_repo").toFile();
        HgTestUtils.hg(tempDir.toFile(), "clone", baseUrl(), sourceDir.getAbsolutePath());
        Files.writeString(new File(sourceDir, ".hg/hgrc").toPath(),
                "[extensions]\nlfs =\n[lfs]\nthreshold = 10\n[experimental]\nlfs.disableusercache = True\n",
                StandardOpenOption.APPEND);

        byte[] originalContent = new byte[4096];
        new Random(42).nextBytes(originalContent);
        Files.write(new File(sourceDir, "big.bin").toPath(), originalContent);
        HgTestUtils.hg(sourceDir, "add", "big.bin");
        HgTestUtils.hg(sourceDir, "commit", "-u", "tester", "-m", "add big lfs file");

        HgTestUtils.hg(sourceDir, "push", "--new-branch", baseUrl());

        File destDir = tempDir.resolve("dest_repo").toFile();
        HgTestUtils.hg(tempDir.toFile(), "clone", "--config", "extensions.lfs=",
                "--config", "experimental.lfs.disableusercache=True", baseUrl(), destDir.getAbsolutePath());

        byte[] restored = Files.readAllBytes(new File(destDir, "big.bin").toPath());
        assertArrayEquals(originalContent, restored,
                "a second real hg client must receive the exact original LFS bytes through hg4j's server, not the pointer text");

        String verifyOutput = HgTestUtils.hg(destDir, "--config", "extensions.lfs=", "verify");
        assertFalse(verifyOutput.toLowerCase().contains("error:"),
                "real hg verify (lfs-aware) must find no errors after cloning the LFS file through hg4j's server: " + verifyOutput);
    }

    /** Batch API scenario: an object already present and hash-verified in the server's local
     * store must be reported WITHOUT an "actions" key on an upload request -- the client's signal
     * to skip re-uploading it (real hg's {@code wireprotolfsserver.py}: {@code elif verifies: yield
     * rsp # Skip 'actions': already uploaded}). */
    @Test
    public void batchApiSkipsReuploadOfAnAlreadyVerifiedBlob() throws Exception {
        byte[] content = "already have this blob".getBytes(StandardCharsets.UTF_8);
        String oid = sha256Hex(content);
        HgLfsManager manager = new HgLfsManager(serverRepo.getHgDir(), serverRepo.getConfig());
        manager.cacheObject(new HgLfsPointer("https://git-lfs.github.com/spec/v1", oid, content.length), content);

        String responseBody = postBatch("upload", oid, content.length);

        assertTrue(responseBody.contains("\"oid\":\"" + oid + "\""), responseBody);
        assertFalse(responseBody.contains("\"actions\""),
                "an already-verified object must be reported with no actions -- the skip-reupload signal: " + responseBody);
    }

    /** Batch API scenario: requesting to download an oid the server has never seen must report a
     * 404 error for that object, not a 500 or a silent success. */
    @Test
    public void batchApiReturns404ErrorForADownloadOfANonexistentOid() throws Exception {
        String unknownOid = "0".repeat(64);

        String responseBody = postBatch("download", unknownOid, 123);

        assertTrue(responseBody.contains("\"code\":404"), responseBody);
    }

    /** Basic Transfer scenario: a local object whose bytes no longer hash to its own oid (bit rot,
     * or a partial write) must be served as 422, not silently handed back corrupted. */
    @Test
    public void basicTransferDownloadReturns422ForACorruptedLocalBlob() throws Exception {
        byte[] wrongContent = "not what the oid says".getBytes(StandardCharsets.UTF_8);
        String oid = sha256Hex("expected content".getBytes(StandardCharsets.UTF_8));
        File corruptFile = new HgLfsManager(serverRepo.getHgDir(), serverRepo.getConfig()).getLocalPath(oid);
        corruptFile.getParentFile().mkdirs();
        Files.write(corruptFile.toPath(), wrongContent);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + ".hg/lfs/objects/" + oid))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(422, response.statusCode(), "a hash-mismatched local blob must be served as 422, not handed back as-is");
    }

    private String postBatch(String operation, String oid, long size) throws Exception {
        String requestBody = "{\"operation\":\"" + operation + "\",\"transfers\":[\"basic\"],"
                + "\"objects\":[{\"oid\":\"" + oid + "\",\"size\":" + size + "}]}";
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + ".git/info/lfs/objects/batch"))
                .header("Content-Type", "application/vnd.git-lfs+json")
                .header("Accept", "application/vnd.git-lfs+json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }
}
