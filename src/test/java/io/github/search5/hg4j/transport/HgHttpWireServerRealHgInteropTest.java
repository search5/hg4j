package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link HgHttpWireServer} against the real {@code hg} CLI as the client — the actual
 * validation that matters for a production server. Real hg 7.2.2 no longer implements
 * wireprotocol v2 at all (removed in 6.1), so this necessarily exercises the v1 path
 * ({@code Wire1Commands}) end to end, unlike {@link HgHttpWireServerTest} (which uses hg4j's own
 * client and auto-upgrades to v2).
 */
@Tag("interop")
public class HgHttpWireServerRealHgInteropTest {

    private HttpServer server;
    private HgRepository serverRepo;
    private byte[] commitNode;
    private File serverRepoDir;

    @BeforeEach
    public void setUp(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");

        serverRepoDir = tempDir.resolve("server_repo").toFile();
        serverRepo = Hg.init().setDirectory(serverRepoDir).call();
        File f = new File(serverRepoDir, "a.txt");
        Files.writeString(f.toPath(), "hello real hg interop");
        new AddCommand(serverRepo).call();
        commitNode = new CommitCommand(serverRepo).setMessage("v1").setAuthor("dev").call();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", new HgHttpWireServer(serverRepo));
        server.start();
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @Test
    public void realHgClonesFromHg4jServedOverHttp(@TempDir Path tempDir) throws Exception {
        File destDir = tempDir.resolve("client_repo").toFile();
        HgTestUtils.hg(tempDir.toFile(), "clone", baseUrl(), destDir.getAbsolutePath());

        String log = HgTestUtils.hg(destDir, "log", "-T", "{node}\n");
        assertEquals(NodeIdUtil.toHex(commitNode), log.trim());
        assertEquals("hello real hg interop", Files.readString(new File(destDir, "a.txt").toPath()));
    }

    @Test
    public void realHgPullsIncrementalChangesFromHg4jServedOverHttp(@TempDir Path tempDir) throws Exception {
        File destDir = tempDir.resolve("client_repo").toFile();
        HgTestUtils.hg(tempDir.toFile(), "clone", baseUrl(), destDir.getAbsolutePath());

        Files.writeString(new File(serverRepoDir, "b.txt").toPath(), "second file");
        new AddCommand(serverRepo).call();
        byte[] secondCommit = new CommitCommand(serverRepo).setMessage("v2").setAuthor("dev").call();

        HgTestUtils.hg(destDir, "pull");
        HgTestUtils.hg(destDir, "update");

        String log = HgTestUtils.hg(destDir, "log", "-T", "{node}\n", "-r", "tip");
        assertEquals(NodeIdUtil.toHex(secondCommit), log.trim());
        assertTrue(new File(destDir, "b.txt").exists());
    }

    @Test
    public void realHgPushesToHg4jServedOverHttp(@TempDir Path tempDir) throws Exception {
        File destDir = tempDir.resolve("client_repo").toFile();
        HgTestUtils.hg(tempDir.toFile(), "clone", baseUrl(), destDir.getAbsolutePath());

        Files.writeString(new File(destDir, "c.txt").toPath(), "pushed file");
        HgTestUtils.hg(destDir, "add", "c.txt");
        HgTestUtils.hg(destDir, "commit", "-m", "pushed commit");

        HgTestUtils.hg(destDir, "push", baseUrl());

        serverRepo.clearRevlogCache();
        File clIdx = new File(serverRepo.getStoreDir(), "00changelog.i");
        File clDat = new File(serverRepo.getStoreDir(), "00changelog.d");
        var cl = serverRepo.getRevlog(clIdx, clDat);
        assertEquals(2, cl.getRevisionCount(), "The pushed commit must be applied to the hg4j server repository");
    }

    @Test
    public void realHgManagesABookmarkOnHg4jServedOverHttp(@TempDir Path tempDir) throws Exception {
        File destDir = tempDir.resolve("client_repo").toFile();
        HgTestUtils.hg(tempDir.toFile(), "clone", baseUrl(), destDir.getAbsolutePath());

        HgTestUtils.hg(destDir, "bookmark", "mybook");
        try {
            HgTestUtils.hg(destDir, "push", "-B", "mybook", baseUrl());
        } catch (AssertionError e) {
            // Real hg's own documented convention: `hg push` exits 1 whenever no new revisions
            // were transferred, even if the push otherwise succeeded at moving a bookmark (see
            // Wire1Commands.unbundle's doc comment) -- this is expected here since there are no
            // new commits, only a bookmark move. Confirmed against real hg 7.2.2 as the client.
            assertTrue(e.getMessage().contains("no changes found") && e.getMessage().contains("exporting bookmark"),
                    "Expected only the known 'no changes found' exit code, got: " + e.getMessage());
        }

        String remoteBookmarks = HgTestUtils.hg(tempDir.toFile(), "bookmarks", "-R", serverRepoDir.getAbsolutePath());
        assertTrue(remoteBookmarks.contains("mybook"), "The pushed bookmark must show up server-side: " + remoteBookmarks);
    }
}
