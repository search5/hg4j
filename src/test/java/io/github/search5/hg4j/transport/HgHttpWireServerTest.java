package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.FetchCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.PushCommand;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * hg4j-self-consistency check for {@link HgHttpWireServer}: hg4j's own {@link HgRemoteClient}
 * against the new production HTTP server. Real-hg-as-client interop is covered separately by
 * {@code HgHttpWireServerRealHgInteropTest}.
 */
public class HgHttpWireServerTest {

    private HttpServer server;
    private HgRepository serverRepo;
    private byte[] commitNode;

    @BeforeEach
    public void setUp(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("server_repo").toFile();
        serverRepo = Hg.init().setDirectory(repoDir).call();
        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "hello wire server");
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
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    public void capabilitiesAndHeadsRoundTrip() throws Exception {
        // HgRemoteClient auto-upgrades to wireprotocol v2 on its first getCapabilities() call
        // whenever the server advertises it (see HgRemoteClient#tryEstablishV2FromDiscoveryResponse)
        // -- and HgHttpWireServer legitimately supports both v1 and v2, so this is the real,
        // correct interaction here, not a v1-specific check. v1's own capability string/framing is
        // covered directly by Wire1CommandsTest and by real-hg-as-client interop (real hg no
        // longer implements v2 at all, so it always exercises v1 through this same server).
        HgRemoteClient client = new HgRemoteClient(baseUrl());
        List<String> caps = client.getCapabilities();
        assertTrue(caps.contains("heads"), "Expected the v2 command set once auto-upgraded: " + caps);
        assertTrue(client.negotiateV2(List.of()), "Client must have recorded the v2 upgrade");

        List<String> heads = client.getHeads();
        assertEquals(1, heads.size());
        assertEquals(NodeIdUtil.toHex(commitNode), heads.get(0));
    }

    @Test
    public void fetchCommandClonesTheRepositoryOverRealHttpWireFraming(@TempDir Path tempDir) throws Exception {
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();

        FetchCommand fetch = new FetchCommand(destRepo);
        List<byte[]> imported = fetch.setSource(baseUrl()).call();

        assertEquals(1, imported.size());
        File clIdx = new File(destRepo.getStoreDir(), "00changelog.i");
        File clDat = new File(destRepo.getStoreDir(), "00changelog.d");
        var cl = destRepo.getRevlog(clIdx, clDat);
        assertEquals(1, cl.getRevisionCount());
        assertEquals(NodeIdUtil.toHex(commitNode), NodeIdUtil.toHex(cl.getIndexRecord(0).getNodeId()));
    }

    @Test
    public void postChangegroupHookFiresWithTheImportedNodeHexAfterARealPushOverHttp(@TempDir Path tempDir) throws Exception {
        File emptyServerRepoDir = tempDir.resolve("push_target_repo").toFile();
        HgRepository emptyServerRepo = Hg.init().setDirectory(emptyServerRepoDir).call();
        HgHttpWireServer pushTargetHandler = new HgHttpWireServer(emptyServerRepo);

        List<Map<String, Object>> observedContexts = new ArrayList<>();
        pushTargetHandler.registerPostChangegroupHook(ctx -> {
            observedContexts.add(ctx);
            return true;
        });

        HttpServer pushServer = HttpServer.create(new InetSocketAddress(0), 0);
        pushServer.createContext("/", pushTargetHandler);
        pushServer.start();
        try {
            File clientRepoDir = tempDir.resolve("push_client_repo").toFile();
            HgRepository clientRepo = Hg.init().setDirectory(clientRepoDir).call();
            Files.writeString(new File(clientRepoDir, "pushed.txt").toPath(), "pushed content");
            new AddCommand(clientRepo).call();
            byte[] pushedCommit = new CommitCommand(clientRepo).setMessage("pushed").setAuthor("dev").call();

            String pushUrl = "http://127.0.0.1:" + pushServer.getAddress().getPort();
            String result = new PushCommand(clientRepo).setDestination(pushUrl).call();
            assertNotNull(result);

            assertEquals(1, observedContexts.size(), "The post-changegroup hook must fire exactly once for the real push");
            @SuppressWarnings("unchecked")
            List<String> nodes = (List<String>) observedContexts.get(0).get("nodes");
            assertEquals(List.of(NodeIdUtil.toHex(pushedCommit)), nodes);
        } finally {
            pushServer.stop(0);
        }
    }

    @Test
    public void listkeysAndPushkeyManageARealBookmarkOverHttp() throws Exception {
        HgRemoteClient client = new HgRemoteClient(baseUrl());
        assertTrue(client.listKeys("bookmarks").isEmpty());

        boolean ok = client.pushkey("bookmarks", "mybook", "", NodeIdUtil.toHex(commitNode));
        assertTrue(ok);

        var keys = client.listKeys("bookmarks");
        assertEquals(NodeIdUtil.toHex(commitNode), keys.get("mybook"));
    }

    @Test
    public void serverAdvertisesAndServesClonebundlesOnceTheManifestFileExists() throws Exception {
        HgRemoteClient before = new HgRemoteClient(baseUrl());
        before.getCapabilities();
        assertFalse(before.supportsClonebundles(), "No manifest yet -- must not be advertised");

        String manifestBody = "https://example.com/bundle.hg BUNDLESPEC=none-v2\n";
        Files.writeString(new File(serverRepo.getHgDir(), "clonebundles.manifest").toPath(), manifestBody);

        HgRemoteClient after = new HgRemoteClient(baseUrl());
        after.getCapabilities();
        assertTrue(after.supportsClonebundles(), "Once the manifest exists, the capability must be advertised");
        assertEquals(manifestBody, after.fetchClonebundlesManifest());
    }
}
