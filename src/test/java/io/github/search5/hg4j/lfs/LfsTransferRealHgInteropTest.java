package io.github.search5.hg4j.lfs;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.CloneCommand;
import io.github.search5.hg4j.lfs.errors.HgLfsException;
import io.github.search5.hg4j.lfs.errors.HgLfsObjectNotFoundException;
import io.github.search5.hg4j.lfs.errors.HgLfsUnauthorizedException;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LFS plan Phase 6: how the client behaves on the wire. Messages, hints, the user agent, authentication, retries and parallel
 * transfers are checked against a real {@code hg serve} (with a counting proxy in front of it where the traffic matters), and
 * where real hg can be asked the same question its answer is the expectation.
 */
@Tag("interop")
class LfsTransferRealHgInteropTest {

    @TempDir
    Path tempDir;

    private Process serve;
    private int hgPort;
    private LfsTestProxy proxy;
    private final List<byte[]> contents = new ArrayList<>();
    private final List<HgLfsPointer> pointers = new ArrayList<>();
    private static final String[] LFS = {"--config", "extensions.lfs=", "--config", "lfs.track=size(\">100\")",
            "--config", "experimental.lfs.disableusercache=true"};

    private static String[] concat(String[] head, String... tail) {
        String[] all = new String[head.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }

    /** A server repository made by real hg holding {@code count} LFS files; the blobs reach the server only if {@code upload}. */
    private File serverRepo(String name, int count, boolean upload) throws Exception {
        File src = tempDir.resolve(name + "-src").toFile();
        src.mkdirs();
        HgTestUtils.hg(src, concat(LFS, "init"));
        for (int i = 0; i < count; i++) {
            byte[] data = new byte[400 + i];
            new Random(System.nanoTime() + i).nextBytes(data);
            contents.add(data);
            pointers.add(new HgLfsPointer("https://git-lfs.github.com/spec/v1", HgLfsPointer.sha256Hex(data), data.length));
            Files.write(new File(src, "f" + i + ".bin").toPath(), data);
        }
        HgTestUtils.hg(src, concat(LFS, "add"));
        HgTestUtils.hg(src, concat(LFS, "commit", "-m", "files", "-u", "t"));
        File server = tempDir.resolve(name).toFile();
        server.mkdirs();
        HgTestUtils.hg(server, concat(LFS, "init"));
        Files.writeString(new File(server, ".hg/hgrc").toPath(),
                "[extensions]\nlfs =\n[web]\npush_ssl = false\nallow-push = *\n[experimental]\nlfs.disableusercache = true\n");
        // without upload only the pointers travel (lfs.url=null: is real hg's "no store")
        // a local push needs an LFS store address: "file:" copies the blobs straight into the server's store, "null:" sends none
        String store = upload ? "file://" + new File(server, ".hg/store/lfs/objects").getAbsolutePath() : "null:";
        HgTestUtils.hg(src, concat(LFS, "--config", "lfs.url=" + store, "push", server.getAbsolutePath()));
        return server;
    }

    private void startServer(File server) throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            hgPort = socket.getLocalPort();
        }
        serve = new ProcessBuilder("hg", "serve", "-p", String.valueOf(hgPort), "--config", "web.push_ssl=false")
                .directory(server).redirectErrorStream(true).start();
        waitForPort(hgPort);
        proxy = new LfsTestProxy(hgPort);
    }

    @BeforeEach
    void requireHg() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled());
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

    private HgLfsManager freshClient(String name, String hgrc) throws Exception {
        File dir = tempDir.resolve(name).toFile();
        new io.github.search5.hg4j.api.InitCommand().setDirectory(dir).call();
        Files.writeString(new File(dir, ".hg/hgrc").toPath(), "[experimental]\nlfs.disableusercache = true\n" + hgrc);
        HgRepository repo = new HgRepository(dir);
        return new HgLfsManager(repo.getHgDir(), repo.getConfig());
    }

    /** The message real hg aborts with, without the leading "abort: ". */
    private String realAbort(AssertionError error) {
        String text = error.getMessage();
        int at = text.indexOf("abort: ");
        assertTrue(at >= 0, text);
        return text.substring(at + "abort: ".length()).trim();
    }

    @Test
    void aMissingBlobIsReportedWithTheFileNameLikeRealHg() throws Exception {
        startServer(serverRepo("srv", 1, false));
        File realClone = tempDir.resolve("real-clone").toFile();
        AssertionError real = assertThrows(AssertionError.class, () -> HgTestUtils.hg(tempDir.toFile(),
                concat(LFS, "clone", "http://127.0.0.1:" + hgPort, realClone.getAbsolutePath())));
        String expected = realAbort(real);
        assertEquals("LFS server error for \"f0.bin\": The object does not exist", expected.lines().findFirst().orElse(""));

        File ours = tempDir.resolve("our-clone").toFile();
        HgLfsException error = assertThrows(HgLfsObjectNotFoundException.class,
                () -> new CloneCommand().setSource("http://127.0.0.1:" + hgPort).setDirectory(ours).call());
        assertEquals(expected, error.getMessage());
    }

    @Test
    void anUnreachableServerIsReportedWithTheOverrideHint() throws Exception {
        startServer(serverRepo("srv", 1, true));
        AssertionError real = assertThrows(AssertionError.class, () -> HgTestUtils.hg(tempDir.toFile(),
                concat(concat(LFS, "--config", "lfs.url=http://127.0.0.1:1"), "clone", "http://127.0.0.1:" + hgPort,
                        tempDir.resolve("real-clone").toString())));
        String expected = realAbort(real);

        HgLfsManager client = freshClient("ours", "");
        HgLfsException error = assertThrows(HgLfsException.class, () -> client.fetchObjects(pointers, "http://127.0.0.1:1"));
        assertEquals(expected, error.getMessage());
    }

    @Test
    void aBatchEndpointThatRefusesIsReportedWithTheHintForItsStatus() throws Exception {
        startServer(serverRepo("srv", 1, true));
        String url = "http://127.0.0.1:" + hgPort + "/nowhere";
        AssertionError real = assertThrows(AssertionError.class, () -> HgTestUtils.hg(tempDir.toFile(),
                concat(concat(LFS, "--config", "lfs.url=" + url), "clone", "http://127.0.0.1:" + hgPort,
                        tempDir.resolve("real-clone").toString())));
        List<String> expected = realAbort(real).lines().toList();
        assertTrue(expected.get(0).startsWith("LFS HTTP error: HTTP Error 400:"), expected.toString());

        HgLfsManager client = freshClient("ours", "");
        HgLfsException error = assertThrows(HgLfsException.class, () -> client.fetchObjects(pointers, url));
        List<String> actual = error.getMessage().lines().toList();
        // The status line's reason phrase ("method not found" from hgweb) is not available through java.net.http, so ours is the
        // standard phrase of the code; everything else, the code and the hint, is the original's.
        assertEquals(400, error.getStatus());
        assertTrue(actual.get(0).startsWith("LFS HTTP error: HTTP Error 400:"), actual.toString());
        assertEquals(expected.get(1), actual.get(1));
    }

    @Test
    void theUserAgentIsGitLfsStyleAndConfigurable() throws Exception {
        startServer(serverRepo("srv", 1, true));
        HgTestUtils.hg(tempDir.toFile(), concat(LFS, "clone", proxy.url(), tempDir.resolve("real-clone").toString()));
        String realAgent = proxy.userAgents.stream().filter(a -> a.startsWith("git-lfs/")).findFirst().orElseThrow();
        assertTrue(realAgent.startsWith("git-lfs/2.3.4 ("), realAgent);

        proxy.userAgents.clear();
        freshClient("ours", "").fetchObjects(pointers, proxy.url() + "/.git/info/lfs");
        assertTrue(proxy.userAgents.stream().allMatch(a -> a.startsWith("git-lfs/2.3.4 (")), proxy.userAgents.toString());
        assertFalse(proxy.userAgents.isEmpty());

        proxy.userAgents.clear();
        freshClient("ours-custom", "[experimental]\nlfs.user-agent = my-agent/1.0\n").fetchObjects(pointers, proxy.url() + "/.git/info/lfs");
        assertTrue(proxy.userAgents.stream().allMatch("my-agent/1.0"::equals), proxy.userAgents.toString());
    }

    @Test
    void basicCredentialsInTheUrlAreSentAndOtherSchemesAreRefused() throws Exception {
        startServer(serverRepo("srv", 1, true));
        proxy.authChallenge = "Basic realm=\"lfs\"";
        String withCredentials = "http://user:secret@127.0.0.1:" + proxy.port() + "/.git/info/lfs";
        freshClient("ours", "").fetchObjects(pointers, withCredentials);

        HgLfsUnauthorizedException none = assertThrows(HgLfsUnauthorizedException.class,
                () -> freshClient("ours-none", "").fetchObjects(pointers, proxy.url() + "/.git/info/lfs"));
        assertTrue(none.getMessage().startsWith("LFS HTTP error: HTTP Error 401: Unauthorized"), none.getMessage());

        proxy.authChallenge = "Digest realm=\"lfs\", nonce=\"abc\"";
        HgLfsUnauthorizedException digest = assertThrows(HgLfsUnauthorizedException.class,
                () -> freshClient("ours-digest", "").fetchObjects(pointers, proxy.url() + "/.git/info/lfs"));
        assertTrue(digest.getMessage().startsWith("LFS HTTP error: HTTP Error 401: the server must support Basic Authentication"),
                digest.getMessage());
    }

    @Test
    void severalObjectsAreTransferredInParallelUnlessSwitchedOff() throws Exception {
        startServer(serverRepo("srv", 6, true));
        proxy.objectDelayMillis = 300;
        freshClient("parallel", "").fetchObjects(pointers, proxy.url() + "/.git/info/lfs");
        assertTrue(proxy.maxConcurrentObjects.get() >= 2, "default is parallel, saw " + proxy.maxConcurrentObjects.get());

        proxy.maxConcurrentObjects.set(0);
        freshClient("serial", "[experimental]\nlfs.worker-enable = false\n").fetchObjects(pointers, proxy.url() + "/.git/info/lfs");
        assertEquals(1, proxy.maxConcurrentObjects.get(), "lfs.worker-enable = false transfers one at a time");
    }

    /** What a client did when the first {@code drops} blob requests were cut off: whether it got through and how often it asked. */
    private record Outcome(boolean succeeded, int blobRequests, String firstLine, String hint) {
    }

    private Outcome realHgWithDrops(int drops) throws Exception {
        proxy.objectRequests.set(0);
        proxy.dropObjectRequests.set(drops);
        try {
            HgTestUtils.hg(tempDir.toFile(), concat(concat(LFS, "--config", "lfs.retry=5"), "clone", proxy.url(),
                    tempDir.resolve("real-" + drops + "-" + System.nanoTime()).toString()));
            return new Outcome(true, proxy.objectRequests.get(), "", "");
        } catch (AssertionError failure) {
            List<String> lines = realAbort(failure).lines().toList();
            return new Outcome(false, proxy.objectRequests.get(), lines.get(0).split(":")[0], lines.size() > 1 ? lines.get(1).replaceAll("/objects/[0-9a-f]+", "/objects/OID") : "");
        }
    }

    private Outcome hg4jWithDrops(int drops) throws Exception {
        proxy.objectRequests.set(0);
        proxy.dropObjectRequests.set(drops);
        HgLfsManager client = freshClient("ours-" + drops + "-" + System.nanoTime(), "[lfs]\nretry = 5\n");
        try {
            client.fetchObjects(pointers, proxy.url() + "/.git/info/lfs");
            return new Outcome(true, proxy.objectRequests.get(), "", "");
        } catch (HgLfsException failure) {
            List<String> lines = failure.getMessage().lines().toList();
            return new Outcome(false, proxy.objectRequests.get(), lines.get(0).split(":")[0], lines.size() > 1 ? lines.get(1).replaceAll("/objects/[0-9a-f]+", "/objects/OID") : "");
        }
    }

    @Test
    void aDroppedConnectionIsRetriedOnceTransparentlyAndNotByLfsRetryLikeRealHg() throws Exception {
        startServer(serverRepo("srv", 1, true));
        for (int drops : new int[] {1, 2}) {
            Outcome expected = realHgWithDrops(drops);
            Outcome actual = hg4jWithDrops(drops);
            assertEquals(expected, actual, drops + " dropped blob request(s)");
        }
    }

    private static void waitForPort(int port) throws Exception {
        Exception last = null;
        for (int i = 0; i < 100; i++) {
            try (java.net.Socket s = new java.net.Socket("127.0.0.1", port)) {
                return;
            } catch (IOException e) {
                last = e;
                Thread.sleep(100);
            }
        }
        throw new AssertionError("hg serve never opened port " + port, last);
    }
}
