package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.api.Hg;
import org.eclipse.jetty.server.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import io.github.search5.hg4j.bundle.ChangegroupParser;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

/**
 * hg4j 서버({@link HgHttpWireServer})와 hg4j 클라이언트(HgRemoteClientV2) 양쪽 모두 real hg 6.0
 * 서버로 직접 검증한 실제 wireprotocol v2 스펙(프레임 기반 전송, X-HgUpgrade/X-HgProto
 * 캡ability 발견 핸드셰이크, /api/&lt;namespace&gt;/&lt;ro|rw&gt;/&lt;command&gt; 라우팅)을
 * 그대로 구현하고 있으므로, 이 테스트는 hg4j끼리의 자기 일관성(self-consistency) 회귀 테스트다.
 */
@DisplayName("Wire Protocol v2 real-spec framing HTTP roundtrip tests")
public class HgHttpTransportV2RoundtripTest {

    @TempDir
    Path tempDir;

    private Server server;
    private int port;
    private HgRepository repository;
    private File repoDir;

    @BeforeEach
    void setUp() throws Exception {
        repoDir = tempDir.resolve("server_repo").toFile();
        repository = Hg.init().setDirectory(repoDir).call();

        server = HgTestUtils.startServlet(new HgHttpWireServer(repository));
        port = HgTestUtils.port(server);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            HgTestUtils.stop(server);
        }
    }

    @Test
    @DisplayName("실제 hg 6.0으로 검증한 capabilities 발견 핸드셰이크와 heads가 동작한다")
    void testV2HttpRoundtripCapabilitiesAndHeads() throws Exception {
        File testFile = new File(repoDir, "test.txt");
        Files.writeString(testFile.toPath(), "hello roundtrip");

        Hg hg = Hg.wrap(repository);
        hg.add().addFile("test.txt").call();
        hg.commit().setMessage("v2 integration").call();

        HgRemoteClientV2 client = new HgRemoteClientV2("http://127.0.0.1:" + port);

        // 실제 v2 명령 집합(mercurial/wireprotov2server.py COMMANDS로 확인): changegroup/
        // getbundle/unbundle 같은 번들 기반 명령은 없다 — changesetdata/manifestdata/
        // filesdata 등 객체 단위 스트리밍 명령뿐이다.
        List<String> caps = client.getCapabilities();
        assertNotNull(caps);
        assertTrue(caps.contains("heads"));
        assertTrue(caps.contains("changesetdata"));
        assertTrue(caps.contains("manifestdata"));
        assertTrue(caps.contains("filesdata"));

        List<String> heads = client.getHeads();
        assertNotNull(heads);
        assertEquals(1, heads.size());

        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);
        String expectedHeadHex = NodeIdUtil.toHex(changelog.getIndexRecord(0).getNodeId());

        assertEquals(expectedHeadHex, heads.get(0));
    }

    @Test
    @DisplayName("getBundle이 changesetdata/manifestdata/filesdata로부터 실제 changegroup을 재구성한다")
    void testV2GetBundleTransfersRealChangegroup() throws Exception {
        Files.writeString(new File(repoDir, "a.txt").toPath(), "content");
        Hg hg = Hg.wrap(repository);
        hg.add().addFile("a.txt").call();
        byte[] commitNode = hg.commit().setMessage("v2 getbundle test").call();

        HgRemoteClientV2 client = new HgRemoteClientV2("http://127.0.0.1:" + port);
        byte[] bundleBytes = client.getBundle(List.of(), client.getHeads(), List.of());
        assertNotNull(bundleBytes);
        assertTrue(bundleBytes.length > 0, "실제 changegroup 바이트가 반환돼야 함");

        assertEquals("HG10UN", new String(bundleBytes, 0, 6, StandardCharsets.US_ASCII));
        ChangegroupParser.ChangegroupBundle bundle =
                ChangegroupParser.parseBundle(
                        new ByteArrayInputStream(bundleBytes, 6, bundleBytes.length - 6), "01");
        assertEquals(1, bundle.changelogEntries.size());
        assertArrayEquals(Arrays.copyOf(commitNode, 20), bundle.changelogEntries.get(0).node);
    }

    @Test
    @DisplayName("listkeys/pushkey로 실제 bookmark를 원격에서 조회·갱신할 수 있다")
    void testV2ListkeysAndPushkeyManageRealBookmark() throws Exception {
        Files.writeString(new File(repoDir, "a.txt").toPath(), "content");
        Hg hg = Hg.wrap(repository);
        hg.add().addFile("a.txt").call();
        byte[] commitNode = hg.commit().setMessage("v2 pushkey test").call();
        String hex = NodeIdUtil.toHex(commitNode).substring(0, 40);

        HgRemoteClientV2 client = new HgRemoteClientV2("http://127.0.0.1:" + port);

        assertTrue(client.listKeys("bookmarks").isEmpty());

        boolean ok = client.pushkey("bookmarks", "mybook", "", hex);
        assertTrue(ok, "존재하지 않던 키에 대한 pushkey(oldVal empty)는 성공해야 함");

        Map<String, String> keys = client.listKeys("bookmarks");
        assertEquals(hex, keys.get("mybook"), "listkeys로 방금 push한 bookmark가 조회돼야 함");
    }

    @Test
    @DisplayName("push는 real hg v2에 명령 자체가 없다는 것을 명확히 알리며 실패한다")
    void testV2PushIsExplicitlyUnsupported() {
        HgRemoteClientV2 client = new HgRemoteClientV2("http://127.0.0.1:" + port);
        Exception e = assertThrows(Exception.class, () -> client.push(new byte[]{1, 2, 3}, null));
        assertTrue(e.getMessage().contains("no push/unbundle command"));
    }

    /**
     * Regression test for a getBundle() bug found via code review (not yet caught by any prior
     * test): on a SECOND/incremental {@code hg4j fetch()} against a server that has moved forward
     * since the first pull, {@link HgRemoteClientV2#getBundle} used to delta-encode the very first
     * new changelog/manifest/file entry against an empty byte array instead of the common root's
     * actual fulltext. {@link ChangegroupParser#parseBundle} alone can't catch this (it just
     * stores the raw delta bytes without decoding them), which is why {@code
     * testV2GetBundleTransfersRealChangegroup} above never noticed it — the corruption only
     * surfaces once the bundle is actually *applied*: {@link Revlog#appendChangeGroupEntry}
     * decodes each cg1 entry positionally (against whatever revision already occupies the
     * immediately preceding slot in local storage) and then verifies the decoded content's SHA-1
     * against the transmitted node hash, throwing {@code HgCorruptDataException} on any mismatch.
     * This test therefore drives a real end-to-end fetch()-twice sequence (mirroring an actual
     * `hg4j pull` used a second time) through {@link io.github.search5.hg4j.api.FetchCommand},
     * which is the only path that both builds AND applies the bundle.
     */
    @Test
    @DisplayName("두 번째(incremental) fetch()가 changelog/manifest/filelog를 손상시키지 않고 해시 검증을 통과한다")
    void testV2IncrementalFetchDoesNotCorruptLocalRevlogs() throws Exception {
        Hg serverHg = Hg.wrap(repository);
        Files.writeString(new File(repoDir, "a.txt").toPath(), "first content");
        serverHg.add().addFile("a.txt").call();
        byte[] commit1 = serverHg.commit().setMessage("first commit").call();

        File clientDir = tempDir.resolve("client_repo").toFile();
        HgRepository clientRepo = Hg.init().setDirectory(clientDir).call();
        Hg clientHg = Hg.wrap(clientRepo);

        String url = "http://127.0.0.1:" + port;
        List<byte[]> firstPull = clientHg.fetch().setSource(url).call();
        assertEquals(1, firstPull.size(), "첫 pull은 commit1 하나만 가져와야 함");

        // Server moves forward: modifies the already-tracked file (touches changelog, manifest,
        // AND the a.txt filelog -- all three revlogs already have a prior, common revision
        // locally, exactly the scenario the buggy empty-array seed corrupted).
        Files.writeString(new File(repoDir, "a.txt").toPath(), "second content");
        byte[] commit2 = serverHg.commit().setMessage("second commit").call();

        // This used to throw HgCorruptDataException ("Security Integrity Error: Changegroup
        // entry hash mismatch") from Revlog.appendChangeGroupEntry before the fix.
        List<byte[]> secondPull = clientHg.fetch().setSource(url).call();
        assertEquals(1, secondPull.size(), "두 번째 pull은 commit2 하나만 새로 가져와야 함");

        File clIdx = new File(clientRepo.getStoreDir(), "00changelog.i");
        File clDat = new File(clientRepo.getStoreDir(), "00changelog.d");
        Revlog clientChangelog = clientRepo.getRevlog(clIdx, clDat);
        assertEquals(2, clientChangelog.getRevisionCount(), "로컬 changelog에 두 커밋 모두 있어야 함");
        assertArrayEquals(Arrays.copyOf(commit1, 20), clientChangelog.getIndexRecord(0).getNodeId());
        assertArrayEquals(Arrays.copyOf(commit2, 20), clientChangelog.getIndexRecord(1).getNodeId());

        // getRevisionContent() re-derives and would throw on a hash mismatch -- reaching this
        // point at all (already implied by fetch() not throwing above) plus a correct decoded
        // body confirms the changelog wasn't silently corrupted.
        byte[] rev1Content = clientChangelog.getRevisionContent(1);
        assertTrue(new String(rev1Content, StandardCharsets.UTF_8).contains("second commit"));

        File mfIdx = new File(clientRepo.getStoreDir(), "00manifest.i");
        File mfDat = new File(clientRepo.getStoreDir(), "00manifest.d");
        Revlog clientManifest = clientRepo.getRevlog(mfIdx, mfDat);
        assertEquals(2, clientManifest.getRevisionCount(), "로컬 manifest revlog에도 두 리비전 모두 있어야 함");
        byte[] mfRev1Content = clientManifest.getRevisionContent(1);
        assertTrue(new String(mfRev1Content, StandardCharsets.UTF_8).contains("a.txt"));

        File flIdx = io.github.search5.hg4j.api.CommitCommand.getFilelogIndex(clientRepo.getStoreDir(), "a.txt");
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        Revlog clientFilelog = clientRepo.getRevlog(flIdx, flDat);
        assertEquals(2, clientFilelog.getRevisionCount(), "a.txt filelog에도 두 리비전 모두 있어야 함");
        assertEquals("second content", new String(clientFilelog.getRevisionContent(1), StandardCharsets.UTF_8));
    }
}
