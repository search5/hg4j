package io.github.search5.hg4j.lfs;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CloneCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.PushCommand;
import io.github.search5.hg4j.errors.HgValidationException;
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
import java.nio.file.StandardOpenOption;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LFS plan Phase 4: hg4j pushes LFS revisions to a REAL {@code hg serve} (lfs extension). The blobs go to the server's LFS
 * endpoint first, then the changegroup; real hg then clones and reads the result. A proxy in front of the server counts the
 * LFS requests.
 */
@Tag("interop")
class LfsPushRealHgInteropTest {

    @TempDir
    Path tempDir;

    private File server;
    private Process serve;
    private LfsTestProxy proxy;
    private int hgPort;
    private byte[] payload;

    @BeforeEach
    void realHgServer() throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled());
        server = tempDir.resolve("server").toFile();
        HgTestUtils.nativeRepo(server, dir -> { });
        Files.writeString(new File(server, ".hg/hgrc").toPath(),
                "[extensions]\nlfs =\n[web]\npush_ssl = false\nallow-push = *\n"
                        // The server must not share the client's user cache: with one shared cache directory the server would
                        // see the client's blob there, believe it already has it, and ask for no upload.
                        + "[experimental]\nlfs.disableusercache = true\n", StandardOpenOption.APPEND);
        Files.writeString(new File(server, "readme.txt").toPath(), "base\n");
        HgTestUtils.hg(server, "add");
        HgTestUtils.hg(server, "commit", "-u", "t", "-m", "base");

        try (ServerSocket socket = new ServerSocket(0)) {
            hgPort = socket.getLocalPort();
        }
        serve = new ProcessBuilder("hg", "serve", "-p", String.valueOf(hgPort), "--config", "web.push_ssl=false",
                "--config", "web.allow-push=*", "--errorlog", tempDir.resolve("serve-error.log").toString()).directory(server).redirectErrorStream(true).start();
        waitForPort(hgPort);
        proxy = new LfsTestProxy(hgPort);
        payload = new byte[3000];
        new Random(System.nanoTime()).nextBytes(payload); // unique per test: the user cache is shared inside one run
    }

    @AfterEach
    void stop() throws Exception {
        if (proxy != null) {
            proxy.close();
        }
        if (serve != null) {
            serve.destroy();
            serve.waitFor();
            // the server's own log: when a push fails, the reason is here, not in the client's HTTP 500
            File errorLog = tempDir.resolve("serve-error.log").toFile();
            if (errorLog.isFile() && errorLog.length() > 0) {
                System.err.println("[hg serve] " + Files.readString(errorLog.toPath()));
            }
        }
    }

    /** Clones the server through the proxy and adds a commit holding {@code payload} as an LFS file. */
    private HgRepository cloneAndCommitLargeFile(String name) throws Exception {
        File client = tempDir.resolve(name).toFile();
        new CloneCommand().setSource(proxy.url()).setDirectory(client).call();
        Files.writeString(new File(client, ".hg/hgrc").toPath(), "[lfs]\ntrack = size(\">100\")\n", StandardOpenOption.APPEND);
        HgRepository repo = new HgRepository(client);
        Files.write(new File(client, "big.bin").toPath(), payload);
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("add big").setAuthor("t").call();
        HgRepository committed = new HgRepository(client);
        File index = CommitCommand.getFilelogIndex(committed.getStoreDir(), "big.bin");
        assertTrue(committed.getRevlog(index, new File(index.getPath().substring(0, index.getPath().length() - 2) + ".d"))
                .isExtStored(0), "the commit must have stored big.bin as an LFS pointer, or the push has nothing to upload");
        return committed;
    }

    @Test
    void theBlobReachesTheServerBeforeThePointer() throws Exception {
        HgRepository repo = cloneAndCommitLargeFile("client");
        new PushCommand(repo).call();

        assertTrue(proxy.batchRequests.get() >= 1, "the push asked the LFS server first (batch requests: " + proxy.batchRequests.get() + ")");
        assertEquals(1, proxy.uploads.get(), "one blob was uploaded; LFS requests seen: " + proxy.seen);
        String oid = HgLfsPointer.sha256Hex(payload);
        File stored = new File(server, ".hg/store/lfs/objects/" + oid.substring(0, 2) + "/" + oid.substring(2));
        assertTrue(stored.isFile(), "the server's LFS store holds the blob: " + stored);
        assertArrayEquals(payload, Files.readAllBytes(stored.toPath()));
        assertTrue(HgTestUtils.hg(server, "--config", "extensions.lfs=", "verify").contains("checked 2 changesets"));
    }

    @Test
    void realHgCanCloneWhatHg4jPushed() throws Exception {
        new PushCommand(cloneAndCommitLargeFile("client")).call();

        File real = tempDir.resolve("real-clone").toFile();
        HgTestUtils.hg(tempDir.toFile(), "--config", "extensions.lfs=", "--config", "experimental.lfs.disableusercache=true",
                "clone", "http://127.0.0.1:" + hgPort, real.getAbsolutePath());
        byte[] content = Files.readAllBytes(new File(real, "big.bin").toPath());
        assertArrayEquals(payload, content, "real hg read the pushed LFS file back with its real content");
    }

    @Test
    void aBlobTheServerAlreadyHasIsNotUploadedAgain() throws Exception {
        HgRepository repo = cloneAndCommitLargeFile("client");
        new PushCommand(repo).call();
        assertEquals(1, proxy.uploads.get());

        // a second commit that touches big.bin again with the SAME content cannot exist, so add a copy: same blob, new file
        Files.write(new File(repo.getDirectory(), "copy.bin").toPath(), payload);
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("copy of the same blob").setAuthor("t").call();
        new PushCommand(new HgRepository(repo.getDirectory())).call();

        assertEquals(1, proxy.uploads.get(), "the server answered with no upload action, so nothing was sent again");
        assertTrue(proxy.batchRequests.get() >= 2, "the second push still asked the server");
    }

    @Test
    void aCorruptLocalBlobStopsThePushBeforeAnythingIsSent() throws Exception {
        HgRepository repo = cloneAndCommitLargeFile("client");
        String oid = HgLfsPointer.sha256Hex(payload);
        File blob = new File(repo.getHgDir(), "store/lfs/objects/" + oid.substring(0, 2) + "/" + oid.substring(2));
        assertTrue(blob.isFile());
        byte[] bad = payload.clone();
        bad[10] ^= 1;
        Files.write(blob.toPath(), bad);
        // the user cache would otherwise heal the local store from its own copy
        String before = HgTestUtils.hg(server, "--config", "extensions.lfs=", "log", "--template", "{node}");

        HgLfsCorruptObjectException error = assertThrows(HgLfsCorruptObjectException.class,
                () -> new PushCommand(new HgRepository(repo.getDirectory())).call());
        assertTrue(error.getMessage().contains("detected corrupt lfs object: " + oid), error.getMessage());
        assertEquals(0, proxy.uploads.get());
        assertEquals(before, HgTestUtils.hg(server, "--config", "extensions.lfs=", "log", "--template", "{node}"),
                "the server received no changeset");
    }

    @Test
    void aRemoteWithoutTheLfsExtensionIsRefusedLikeRealHg() throws Exception {
        // stop the lfs-enabled server and serve the same repository without the extension
        serve.destroy();
        serve.waitFor();
        Files.writeString(new File(server, ".hg/hgrc").toPath(), "[web]\npush_ssl = false\nallow-push = *\n");
        int plainPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            plainPort = socket.getLocalPort();
        }
        serve = new ProcessBuilder("hg", "serve", "-p", String.valueOf(plainPort), "--config", "web.push_ssl=false",
                "--config", "web.allow-push=*").directory(server).redirectErrorStream(true).start();
        waitForPort(plainPort);

        File client = tempDir.resolve("client-plain").toFile();
        new CloneCommand().setSource("http://127.0.0.1:" + plainPort).setDirectory(client).call();
        Files.writeString(new File(client, ".hg/hgrc").toPath(), "[lfs]\ntrack = size(\">100\")\n", StandardOpenOption.APPEND);
        HgRepository repo = new HgRepository(client);
        Files.write(new File(client, "big.bin").toPath(), payload);
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("add big").setAuthor("t").call();

        HgValidationException ours = assertThrows(HgValidationException.class, () -> new PushCommand(new HgRepository(client)).call());
        assertTrue(ours.getMessage().contains("required features are not supported in the destination: lfs"), ours.getMessage());

        // real hg refuses the same push with the same words
        File realClient = tempDir.resolve("real-client").toFile();
        HgTestUtils.hg(tempDir.toFile(), "clone", "http://127.0.0.1:" + plainPort, realClient.getAbsolutePath());
        Files.writeString(new File(realClient, "big.bin").toPath(), new String(payload, StandardCharsets.ISO_8859_1), StandardCharsets.ISO_8859_1);
        String[] lfs = {"--config", "extensions.lfs=", "--config", "lfs.track=size(\">100\")", "--config", "experimental.lfs.disableusercache=true"};
        HgTestUtils.hg(realClient, concat(lfs, "add"));
        HgTestUtils.hg(realClient, concat(lfs, "commit", "-m", "big", "-u", "t"));
        AssertionError real = assertThrows(AssertionError.class, () -> HgTestUtils.hg(realClient, concat(lfs, "push")));
        assertTrue(real.getMessage().contains("required features are not supported in the destination: lfs"), real.getMessage());
    }

    private static String[] concat(String[] head, String... tail) {
        String[] all = new String[head.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
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
