package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server hooks of a push see the changesets of the same push, like the original's pending view ({@code HG_PENDING}): the
 * {@code prepushkey} hooks run after the changegroup of the bundle was applied (they refuse by undoing it), and the
 * {@code pretxnchangegroup} hooks run over HTTP with a bundle2 push.
 */
@Tag("interop")
class HookPendingVisibilityRealHgInteropTest {

    @TempDir
    Path tempDir;

    private Server server;
    private HgRepository serverRepo;
    private File serverDir;
    private File clientDir;

    private String url() {
        return "http://127.0.0.1:" + HgTestUtils.port(server) + "/";
    }

    @AfterEach
    void stop() {
        if (server != null) {
            HgTestUtils.stop(server);
        }
    }

    private HgHttpWireServer start() throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "hg is not installed");
        serverDir = tempDir.resolve("server").toFile();
        HgTestUtils.hg(tempDir.toFile(), "init", serverDir.getAbsolutePath());
        Files.writeString(new File(serverDir, "a.txt").toPath(), "a\n");
        HgTestUtils.hg(serverDir, "add");
        HgTestUtils.hg(serverDir, "commit", "-m", "base", "-u", "t");
        HgTestUtils.hg(serverDir, "bookmark", "main");
        serverRepo = new HgRepository(serverDir);
        HgHttpWireServer wire = new HgHttpWireServer(serverRepo);
        server = HgTestUtils.startServlet(wire);
        clientDir = tempDir.resolve("client").toFile();
        HgTestUtils.hg(tempDir.toFile(), "clone", url(), clientDir.getAbsolutePath());
        Files.writeString(new File(clientDir, "b.txt").toPath(), "b\n");
        HgTestUtils.hg(clientDir, "add");
        HgTestUtils.hg(clientDir, "commit", "-m", "pushed", "-u", "someone");
        return wire;
    }

    /** What a hook sees: the changelog length and whether it finds the node. */
    private static String look(HgRepository repo, String newNode) throws Exception {
        repo.clearRevlogCache();
        Revlog changelog = repo.getRevlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"));
        int found = newNode == null || newNode.isEmpty() ? -2 : changelog.findRevision(NodeIdUtil.fromHex(newNode));
        return changelog.getRevisionCount() + "/" + found;
    }

    @Test
    void thePrepushkeyHookSeesTheChangesetsOfTheSamePush() throws Exception {
        HgHttpWireServer wire = start();
        List<String> seen = new ArrayList<>();
        wire.registerPrePushkeyHook(ctx -> {
            try {
                seen.add(look((HgRepository) ctx.get("repository"), (String) ctx.get("new")));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return true;
        });
        HgTestUtils.hg(clientDir, "bookmark", "-f", "main");
        HgTestUtils.hg(clientDir, "push", "-B", "main", url());
        String pushed = HgTestUtils.hg(clientDir, "log", "-r", ".", "-T", "{node}").trim();
        assertEquals(1, seen.size(), seen.toString());
        assertEquals("2/1", seen.get(0), "the pushed changeset is revision 1 of two");
        assertEquals(pushed, HgTestUtils.hg(serverDir, "log", "-r", "main", "-T", "{node}").trim());
    }

    @Test
    void aRefusingPrepushkeyHookUndoesTheWholePush() throws Exception {
        HgHttpWireServer wire = start();
        wire.registerPrePushkeyHook(ctx -> false);
        HgTestUtils.hg(clientDir, "bookmark", "-f", "main");
        try {
            HgTestUtils.hg(clientDir, "push", "-B", "main", url());
        } catch (Throwable expected) {
            // the push is refused
        }
        assertEquals(0, Integer.parseInt(HgTestUtils.hg(serverDir, "log", "-r", "tip", "-T", "{rev}").trim()));
    }

    @Test
    void thePretxnchangegroupHookRunsOnABundle2PushAndSeesTheNewChangesets() throws Exception {
        HgHttpWireServer wire = start();
        List<String> seen = new ArrayList<>();
        wire.registerPreTxnChangegroupHook(ctx -> {
            try {
                @SuppressWarnings("unchecked")
                List<String> nodes = (List<String>) ctx.get("nodes");
                seen.add(look((HgRepository) ctx.get("repository"), nodes.get(0)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return true;
        });
        HgTestUtils.hg(clientDir, "push", url());
        assertEquals(List.of("2/1"), seen);
        assertTrue(HgTestUtils.hg(serverDir, "log", "-T", "{desc}\n").contains("pushed"));
    }
}
