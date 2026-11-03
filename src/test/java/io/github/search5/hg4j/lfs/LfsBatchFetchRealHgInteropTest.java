package io.github.search5.hg4j.lfs;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.CloneCommand;
import io.github.search5.hg4j.api.InitCommand;
import io.github.search5.hg4j.lfs.errors.HgLfsCorruptObjectException;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LFS plan Phase 2: fetching several blobs takes ONE batch request, and a download that does not match its pointer is refused.
 * The server is a real {@code hg serve} (lfs extension); a forwarding proxy in front of it only counts requests and, in one
 * test, flips a byte of a download.
 */
@Tag("interop")
class LfsBatchFetchRealHgInteropTest {

    @TempDir
    Path tempDir;

    private Process serve;
    private LfsTestProxy proxy;
    private int hgPort;
    private final List<byte[]> contents = new ArrayList<>();
    private final List<HgLfsPointer> pointers = new ArrayList<>();

    @BeforeEach
    void realHgServer() throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled());
        File server = tempDir.resolve("server").toFile();
        HgTestUtils.nativeRepo(server, dir -> { });
        Files.writeString(new File(server, ".hg/hgrc").toPath(),
                "[extensions]\nlfs =\n[lfs]\ntrack = all()\n[web]\npush_ssl = false\n[experimental]\nlfs.disableusercache = true\n",
                java.nio.file.StandardOpenOption.APPEND);
        for (int i = 0; i < 3; i++) {
            byte[] data = new byte[2000 + i];
            new Random(System.nanoTime() + i).nextBytes(data); // unique per test: the user cache is shared inside one run
            contents.add(data);
            Files.write(new File(server, "f" + i + ".bin").toPath(), data);
            pointers.add(new HgLfsPointer("https://git-lfs.github.com/spec/v1", HgLfsPointer.sha256Hex(data), data.length));
        }
        HgTestUtils.hg(server, "add");
        HgTestUtils.hg(server, "commit", "-u", "t", "-m", "three lfs files");

        hgPort = freePort();
        serve = new ProcessBuilder("hg", "serve", "-p", String.valueOf(hgPort), "--config", "web.push_ssl=false")
                .directory(server).redirectErrorStream(true).start();
        waitForPort(hgPort);

        proxy = new LfsTestProxy(hgPort);
    }

    @AfterEach
    void stop() throws Exception {
        if (proxy != null) {
            proxy.close();
        }
        if (serve != null) {
            serve.destroy();
            serve.waitFor();
        }
    }

    private HgLfsManager freshClient() throws Exception {
        File client = tempDir.resolve("client").toFile();
        new InitCommand().setDirectory(client).call();
        Files.writeString(new File(client, ".hg/hgrc").toPath(), "[experimental]\nlfs.disableusercache = true\n");
        HgRepository repo = new HgRepository(client);
        return new HgLfsManager(repo.getHgDir(), repo.getConfig());
    }

    private String url() {
        return proxy.url() + "/.git/info/lfs";
    }

    @Test
    void severalObjectsTakeOneBatchRequest() throws Exception {
        HgLfsManager client = freshClient();
        client.fetchObjects(pointers, url());
        assertEquals(1, proxy.batchRequests.get(), "one batch request for all three objects");
        for (int i = 0; i < 3; i++) {
            assertArrayEquals(contents.get(i), client.getCachedObject(pointers.get(i)));
        }
        client.fetchObjects(pointers, url());
        assertEquals(1, proxy.batchRequests.get(), "nothing is missing any more, so no further request");
    }

    @Test
    void aDownloadThatDoesNotMatchItsPointerIsRefused() throws Exception {
        HgLfsManager client = freshClient();
        proxy.corruptDownloads.set(true);
        assertThrows(HgLfsCorruptObjectException.class, () -> client.fetchObjects(pointers.subList(0, 1), url()));
        assertFalse(client.isCached(pointers.get(0)), "a corrupt object must not be cached");
    }

    @Test
    void cloneChecksOutAllLfsFilesWithOneBatchRequest() throws Exception {
        File target = tempDir.resolve("cloned").toFile();
        new CloneCommand().setSource(proxy.url()).setDirectory(target).call();
        for (int i = 0; i < 3; i++) {
            assertArrayEquals(contents.get(i), Files.readAllBytes(new File(target, "f" + i + ".bin").toPath()));
        }
        assertEquals(1, proxy.batchRequests.get(), "the checkout asked the server once for all three blobs");
    }

    @Test
    void cloneStraightFromRealServeWithoutTheProxy() throws Exception {
        File target = tempDir.resolve("cloned-direct").toFile();
        new CloneCommand().setSource("http://127.0.0.1:" + hgPort).setDirectory(target).call();
        for (int i = 0; i < 3; i++) {
            assertArrayEquals(contents.get(i), Files.readAllBytes(new File(target, "f" + i + ".bin").toPath()));
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void waitForPort(int port) throws Exception {
        Exception last = null;
        for (int i = 0; i < 100; i++) {
            try (java.net.Socket s = new java.net.Socket("127.0.0.1", port)) {
                return;
            } catch (Exception e) {
                last = e;
                Thread.sleep(100);
            }
        }
        throw new AssertionError("hg serve never opened port " + port, last);
    }
}
