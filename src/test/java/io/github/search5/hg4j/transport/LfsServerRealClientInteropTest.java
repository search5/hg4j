package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LFS plan Phase 5: REAL hg clients use hg4j's HTTP server with LFS. One pushes an LFS file (the blob goes to the server's LFS
 * endpoint, the changegroup after it), another clones the result and reads the file back.
 */
@Tag("interop")
class LfsServerRealClientInteropTest {

    @TempDir
    Path tempDir;

    private Server own;
    private int port;
    private File serverRepo;
    private byte[] payload;
    private static final String[] LFS = {"--config", "extensions.lfs=", "--config", "lfs.track=size(\">100\")",
            "--config", "experimental.lfs.disableusercache=true"};

    @BeforeEach
    void ownServer() throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled());
        serverRepo = tempDir.resolve("server").toFile();
        serverRepo.mkdirs();
        HgTestUtils.hg(serverRepo, "init");
        Files.writeString(new File(serverRepo, ".hg/hgrc").toPath(),
                "[extensions]\nlfs =\n[experimental]\nlfs.disableusercache = true\n");
        own = HgTestUtils.startServlet(new HgHttpWireServer(new HgRepository(serverRepo)));
        port = HgTestUtils.port(own);
        payload = new byte[2500];
        new Random(System.nanoTime()).nextBytes(payload);
    }

    @AfterEach
    void stop() {
        if (own != null) {
            HgTestUtils.stop(own);
        }
    }

    private static String[] concat(String[] head, String... tail) {
        String[] all = new String[head.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }

    private File realClientWithALargeFile() throws Exception {
        File client = tempDir.resolve("client").toFile();
        HgTestUtils.hg(tempDir.toFile(), concat(LFS, "clone", "http://127.0.0.1:" + port, client.getAbsolutePath()));
        Files.write(new File(client, "big.bin").toPath(), payload);
        HgTestUtils.hg(client, concat(LFS, "add"));
        HgTestUtils.hg(client, concat(LFS, "commit", "-m", "big", "-u", "t"));
        return client;
    }

    @Test
    void theServerAdvertisesLfsAndTakesTheBlobAndTheChangegroup() throws Exception {
        File client = realClientWithALargeFile();
        String output = HgTestUtils.hg(client, concat(LFS, "push"));

        String oid = HgLfsPointer.sha256Hex(payload);
        File blob = new File(serverRepo, ".hg/store/lfs/objects/" + oid.substring(0, 2) + "/" + oid.substring(2));
        assertTrue(blob.isFile(), "the blob is in the server's LFS store after: " + output);
        assertArrayEquals(payload, Files.readAllBytes(blob.toPath()));
        assertTrue(Files.readAllLines(new File(serverRepo, ".hg/requires").toPath()).contains("lfs"),
                "like real hg's server, the repository now requires lfs");
        assertTrue(HgTestUtils.hg(serverRepo, concat(LFS, "verify")).contains("checked 1 changesets"));
    }

    @Test
    void anotherRealClientClonesWhatWasPushed() throws Exception {
        HgTestUtils.hg(realClientWithALargeFile(), concat(LFS, "push"));

        File second = tempDir.resolve("second").toFile();
        HgTestUtils.hg(tempDir.toFile(), concat(LFS, "clone", "http://127.0.0.1:" + port, second.getAbsolutePath()));
        assertArrayEquals(payload, Files.readAllBytes(new File(second, "big.bin").toPath()),
                "the clone has the real content: pointer, flag and blob all came through hg4j's server");
        assertTrue(HgTestUtils.hg(second, concat(LFS, "verify")).contains("checked 1 changesets"));
    }
}
