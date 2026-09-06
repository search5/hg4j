package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.FetchCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD for {@link HgRemoteClientV2#getBundle}'s recursive {@code tree=<dir>} fetch (backlog item
 * 20 in {@code mercurial-spec-compliance-requirement.md} -- previously the client always
 * requested only the root manifest (@code tree=""}, silently dropping every subdirectory of a
 * treemanifest source). Companion fix bundled in: {@code Wire2Commands.manifestdata} previously
 * threw for any non-empty {@code tree} on the <em>server</em> side too -- without that, this
 * client-side fix would have nothing capable of testing it against (real hg's own wireprotocol v2
 * server, the intended interop target, is not available in this environment -- it only ever
 * existed in Mercurial 6.0, superseded and removed by 6.1 before this task's Docker images were
 * built) -- so this is an hg4j &lt;-&gt; hg4j self-consistency round-trip: a treemanifest server
 * repo (real per-directory {@code meta/<dir>/00manifest.i} revlogs written via {@code
 * CommitCommand}'s own treemanifest write support, backlog item 18) served over real HTTP via
 * {@link HgHttpWireServer}, pulled by a plain {@link FetchCommand} through the real v1-&gt;v2
 * auto-upgrade handshake, into a brand-new client repository.
 */
@DisplayName("HgRemoteClientV2 recursive treemanifest tree=<dir> fetch (backlog 20)")
class Wire2TreeManifestFetchTest {

    @TempDir
    Path tempDir;

    private Server server;
    private int port;

    @AfterEach
    void tearDown() {
        if (server != null) {
            HgTestUtils.stop(server);
        }
    }

    private HgRepository initTreemanifestRepo(File repoDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File storeDir = new File(repoDir, ".hg/store");
        Files.createDirectories(storeDir.toPath());
        List<String> baseLines = Files.readAllLines(new File(repoDir, ".hg/requires").toPath());
        Files.write(new File(storeDir, "requires").toPath(), baseLines);
        List<String> lines = new ArrayList<>(baseLines);
        lines.add("treemanifest");
        Files.write(new File(repoDir, ".hg/requires").toPath(), lines);
        return new HgRepository(repoDir);
    }

    private void write(File repoDir, String relPath, String content) throws Exception {
        File f = new File(repoDir, relPath);
        Files.createDirectories(f.getParentFile().toPath());
        Files.writeString(f.toPath(), content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    @Test
    @DisplayName("클라이언트가 서버의 서브디렉터리 submanifest까지 재귀적으로 fetch해 로컬 저장소를 정확히 재구성한다")
    void recursivelyFetchesNestedTreemanifestSubdirectories() throws Exception {
        File serverDir = tempDir.resolve("server_repo").toFile();
        HgRepository serverRepo = initTreemanifestRepo(serverDir);
        assertTrue(serverRepo.isTreemanifest());

        write(serverDir, "a.txt", "root file\n");
        write(serverDir, "sub/b.txt", "sub file\n");
        write(serverDir, "sub/deep/c.txt", "deep file\n");
        write(serverDir, "sub2/d.txt", "sub2 file\n");
        new AddCommand(serverRepo).call();
        new CommitCommand(serverRepo).setMessage("nested tree").call();

        server = HgTestUtils.startServlet(new HgHttpWireServer(serverRepo));
        port = HgTestUtils.port(server);

        File clientDir = tempDir.resolve("client_repo").toFile();
        HgRepository clientRepo = Hg.init().setDirectory(clientDir).call();
        new FetchCommand(clientRepo).setSource("http://127.0.0.1:" + port).call();

        // The pulled revision must be reachable and its manifest must expand to the same 4 files
        // with matching content -- proof the client correctly wrote real per-directory
        // meta/sub/00manifest.i / meta/sub/deep/00manifest.i / meta/sub2/00manifest.i revisions
        // locally (not just a root manifest with dangling 't' pointers nobody ever fetched).
        assertTrue(new File(clientDir, ".hg/store/meta/sub/00manifest.i").isFile());
        assertTrue(new File(clientDir, ".hg/store/meta/sub/deep/00manifest.i").isFile());
        assertTrue(new File(clientDir, ".hg/store/meta/sub2/00manifest.i").isFile());

        Map<String, String> mf = clientRepo.getManifestAtCommit(headNode(clientRepo));
        assertEquals(4, mf.size());
        assertTrue(mf.containsKey("a.txt"));
        assertTrue(mf.containsKey("sub/b.txt"));
        assertTrue(mf.containsKey("sub/deep/c.txt"));
        assertTrue(mf.containsKey("sub2/d.txt"));

        assertEquals("deep file\n", new String(getFileContent(clientRepo, "sub/deep/c.txt", mf.get("sub/deep/c.txt"))));
        assertEquals("sub2 file\n", new String(getFileContent(clientRepo, "sub2/d.txt", mf.get("sub2/d.txt"))));
    }

    private static byte[] headNode(HgRepository repo) throws Exception {
        io.github.search5.hg4j.storage.Revlog changelog = repo.getRevlog(
                new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"));
        return changelog.getIndexRecord(changelog.getRevisionCount() - 1).getNodeId();
    }

    private static byte[] getFileContent(HgRepository repo, String path, String hexNodeAndFlag) throws Exception {
        String hex = hexNodeAndFlag.length() >= 40 ? hexNodeAndFlag.substring(0, 40) : hexNodeAndFlag;
        byte[] node = io.github.search5.hg4j.util.NodeIdUtil.fromHex(hex);
        String encoded = io.github.search5.hg4j.util.NodeIdUtil.encodeFname("data/" + path);
        File flIdx = new File(repo.getStoreDir(), encoded + ".i");
        File flDat = new File(repo.getStoreDir(), encoded + ".d");
        io.github.search5.hg4j.storage.Revlog filelog = repo.getRevlog(flIdx, flDat);
        int rev = io.github.search5.hg4j.util.NodeIdUtil.findRevisionByNodeId(filelog, node);
        assertTrue(rev >= 0, "filelog revision not found for " + path);
        return filelog.getRevisionContent(rev);
    }
}
