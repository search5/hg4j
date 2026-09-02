package com.github.search5.hg4j.api;
import com.github.search5.hg4j.bundle.Bundle2Parser;
import com.github.search5.hg4j.bundle.ChangegroupParser;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.github.search5.hg4j.storage.Revlog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.github.search5.hg4j.transport.UsernamePasswordCredentialsProvider;
import com.github.search5.hg4j.transport.SshKeyCredentialsProvider;
import com.github.search5.hg4j.transport.CredentialsProvider;
import com.github.search5.hg4j.transport.HgRemoteClient;
import com.github.search5.hg4j.transport.HgRemoteConnection;
import com.github.search5.hg4j.transport.HgRemoteConnectionFactory;
import com.github.search5.hg4j.transport.HgSshClient;
import com.github.search5.hg4j.transport.TransportProtocol;
import com.github.search5.hg4j.transport.HgHttpWireServer;
import com.github.search5.hg4j.errors.HgCorruptDataException;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Field;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.DeflaterOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import com.github.search5.hg4j.HgTestUtils;

public class FetchCommandTest {

    @Test
    public void testFetchCommandValidationAndEdgeCases(@TempDir Path tempDir) throws Exception {
        File destDir = tempDir.resolve("dest_validation").toFile();
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();

        FetchCommand fetchCmd = new FetchCommand(destRepo);

        // 1. Validate exception when URL is null
        assertThrows(IllegalStateException.class, () -> fetchCmd.call());

        // 2. Validate exception when URL is empty
        fetchCmd.setSource("");
        assertThrows(IllegalStateException.class, () -> fetchCmd.call());
    }

    @Test
    public void testFetchDoesNotAdvanceDirstateParent(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();

        // 1. Create source repository and commit
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();

        File f1 = new File(srcDir, "README.MD");
        Files.writeString(f1.toPath(), "Hello remote sync");

        new AddCommand(srcRepo).call();
        byte[] commitNode1 = new CommitCommand(srcRepo)
                .setAuthor("Alice <alice@example.com>")
                .setMessage("First commit in source")
                .call();

        // 2. Mock ChangegroupBundle based on source repository revision
        ChangegroupParser.ChangegroupBundle bundle = HgTestUtils.createMockBundleFromRepo(srcRepo);

        // 3. Apply bundle to destination repository using FetchCommand
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        FetchCommand fetchCmd = new FetchCommand(destRepo);
        List<byte[]> imported = fetchCmd.applyBundle(bundle);

        assertEquals(1, imported.size());
        assertArrayEquals(commitNode1, imported.get(0));

        // 4. Verify changelog is synchronized
        Revlog cl = new Revlog(new File(destRepo.getStoreDir(), "00changelog.i"), new File(destRepo.getStoreDir(), "00changelog.d"));
        assertEquals(1, cl.getRevisionCount());

        // 5. Fetch must not update the working copy dirstate parent and leave it as All Zero
        Dirstate dirstate = destRepo.getDirstate();
        assertTrue(NodeIdUtil.isAllZero(dirstate.getParent1()), "Fetch 후에는 Dirstate parent1이 All Zero여야 합니다.");
        assertTrue(NodeIdUtil.isAllZero(dirstate.getParent2()), "Fetch 후에는 Dirstate parent2가 All Zero여야 합니다.");
    }

    @Test
    public void testPullAdvancesDirstateParent(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();

        // 1. Create source repository and commit
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();

        File f1 = new File(srcDir, "README.MD");
        Files.writeString(f1.toPath(), "Hello remote sync");

        new AddCommand(srcRepo).call();
        byte[] commitNode1 = new CommitCommand(srcRepo)
                .setAuthor("Alice <alice@example.com>")
                .setMessage("First commit in source")
                .call();

        ChangegroupParser.ChangegroupBundle bundle = HgTestUtils.createMockBundleFromRepo(srcRepo);

        // 2. Apply bundle to destination repository using PullCommand
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        PullCommand pullCmd = new PullCommand(destRepo);
        List<byte[]> imported = pullCmd.applyBundle(bundle);

        assertEquals(1, imported.size());
        assertArrayEquals(commitNode1, imported.get(0));

        // 3. Pull must automatically advance the dirstate parent to the latest Head if it was empty
        Dirstate dirstate = destRepo.getDirstate();
        assertArrayEquals(commitNode1, dirstate.getParent1(), "Pull 후에는 Dirstate parent1이 최신 커밋으로 갱신되어야 합니다.");
        assertTrue(NodeIdUtil.isAllZero(dirstate.getParent2()), "Pull 후에도 Dirstate parent2는 All Zero여야 합니다.");
    }

    @Test
    public void testCredentialsProviderPropagation() throws Exception {
        // 1. Verify HTTP Client
        HgRemoteClient httpClient = new HgRemoteClient("http://example.com/repo");
        UsernamePasswordCredentialsProvider httpProvider = new UsernamePasswordCredentialsProvider("user1", "pass1");
        httpClient.setCredentialsProvider(httpProvider);

        Field userField = HgRemoteClient.class.getDeclaredField("username");
        userField.setAccessible(true);
        Field passField = HgRemoteClient.class.getDeclaredField("password");
        passField.setAccessible(true);

        assertEquals("user1", userField.get(httpClient));
        assertEquals("pass1", passField.get(httpClient));

        // 2. Verify SSH Client - Username / Password
        HgSshClient sshClient1 = new HgSshClient("ssh://example.com/repo");
        sshClient1.setCredentialsProvider(httpProvider);

        Field sshUserField = HgSshClient.class.getDeclaredField("username");
        sshUserField.setAccessible(true);
        Field sshPassField = HgSshClient.class.getDeclaredField("password");
        sshPassField.setAccessible(true);

        assertEquals("user1", sshUserField.get(sshClient1));
        assertEquals("pass1", sshPassField.get(sshClient1));

        // 3. Verify SSH Client - SSH Key
        HgSshClient sshClient2 = new HgSshClient("ssh://example.com/repo");
        SshKeyCredentialsProvider sshKeyProvider = new SshKeyCredentialsProvider("/path/to/key", "keypass");
        sshClient2.setCredentialsProvider(sshKeyProvider);

        Field sshKeyPathField = HgSshClient.class.getDeclaredField("privateKeyPath");
        sshKeyPathField.setAccessible(true);
        Field sshPassphraseField = HgSshClient.class.getDeclaredField("passphrase");
        sshPassphraseField.setAccessible(true);

        assertEquals("/path/to/key", sshKeyPathField.get(sshClient2));
        assertEquals("keypass", sshPassphraseField.get(sshClient2));
    }

    @Test
    public void testHgFacadeFetch(@TempDir Path tempDir) throws Exception {
        File destDir = tempDir.resolve("dest_facade").toFile();
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();

        // Verify that FetchCommand is normally created via Hg facade object
        FetchCommand fetchCmd = Hg.wrap(destRepo).fetch();
        assertNotNull(fetchCmd, "Hg.wrap(repo).fetch()로 FetchCommand를 직접 획득할 수 있어야 합니다.");
    }

    // ------------------------------------------------------------------
    // Scripted transport support: a fully controllable HgRemoteConnection
    // double, registered under a "scripted://<uuid>" scheme, letting the
    // tests below drive every branch of FetchCommand#call() directly
    // (capability advertisement, discovery negotiation, bundle formats,
    // error handling) without needing a real network round trip for each
    // combination.
    // ------------------------------------------------------------------

    private static final Map<String, ScriptedRemoteConnection> SCRIPTED_CONNECTIONS = new ConcurrentHashMap<>();

    static {
        HgRemoteConnectionFactory.register(new TransportProtocol() {
            @Override
            public boolean canHandle(String url) {
                return url != null && url.startsWith("scripted://");
            }
            @Override
            public HgRemoteConnection open(String url) {
                ScriptedRemoteConnection conn = SCRIPTED_CONNECTIONS.get(url);
                assertNotNull(conn, "No scripted connection registered for " + url);
                return conn;
            }
        });
    }

    private static String registerScripted(ScriptedRemoteConnection conn) {
        String url = "scripted://" + UUID.randomUUID();
        SCRIPTED_CONNECTIONS.put(url, conn);
        return url;
    }

    private static final class ScriptedRemoteConnection implements HgRemoteConnection {
        List<String> capabilities = new ArrayList<>();
        List<String> heads = new ArrayList<>();
        byte[] changegroupBytes = new byte[0];
        byte[] bundleBytesToReturn = new byte[0];
        Map<String, String> bookmarks = new LinkedHashMap<>();
        Map<String, String> phases = new LinkedHashMap<>();
        RuntimeException betweenException;
        List<String> betweenResult = new ArrayList<>();
        String knownResult = "";
        RuntimeException listKeysException;
        CredentialsProvider capturedCredentials;
        List<String> lastBundleCapsArg;
        List<String> lastCommonArg;
        List<String> lastGetChangegroupRootsArg;
        boolean closed;

        @Override
        public List<String> getCapabilities() {
            return capabilities;
        }

        @Override
        public List<String> getHeads() {
            return heads;
        }

        @Override
        public byte[] getChangegroup(List<String> roots) {
            lastGetChangegroupRootsArg = roots;
            return changegroupBytes;
        }

        @Override
        public byte[] getBundle(List<String> common, List<String> hds, List<String> bundleCaps) {
            lastCommonArg = common;
            lastBundleCapsArg = bundleCaps;
            return bundleBytesToReturn;
        }

        @Override
        public String push(byte[] bundleBytes, List<String> hds) {
            return "ok";
        }

        @Override
        public Map<String, String> listKeys(String namespace) {
            if (listKeysException != null) {
                throw listKeysException;
            }
            if ("bookmarks".equals(namespace)) {
                return bookmarks;
            }
            if ("phases".equals(namespace)) {
                return phases;
            }
            return Map.of();
        }

        @Override
        public boolean pushkey(String namespace, String key, String oldVal, String newVal) {
            return true;
        }

        @Override
        public List<String> between(List<String> pairs) {
            if (betweenException != null) {
                throw betweenException;
            }
            return betweenResult;
        }

        @Override
        public String known(List<String> nodes) {
            return knownResult;
        }

        @Override
        public void setCredentialsProvider(CredentialsProvider provider) {
            this.capturedCredentials = provider;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static byte[] bundle1Header(String comp) {
        return ("HG10" + comp).getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    public void fetchReturnsEmptyImmediatelyWhenRemoteHasNoHeads(@TempDir Path tempDir) throws Exception {
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();

        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.capabilities = List.of("getbundle", "bundle2");
        conn.heads = List.of();
        String url = registerScripted(conn);

        List<byte[]> imported = new FetchCommand(destRepo).setSource(url).call();
        assertTrue(imported.isEmpty());
        assertTrue(conn.closed, "Connection must be closed via try-with-resources even on the early-exit path");
    }

    @Test
    public void fetchSyncsBookmarksAndPhasesEvenWhenAlreadyUpToDate(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "hello");
        new AddCommand(srcRepo).call();
        byte[] commitNode = new CommitCommand(srcRepo).setAuthor("dev").setMessage("v1").call();
        String commitHex = NodeIdUtil.toHex(commitNode);

        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        ChangegroupParser.ChangegroupBundle seedBundle = HgTestUtils.createMockBundleFromRepo(srcRepo);
        new FetchCommand(destRepo).applyBundle(seedBundle);

        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.capabilities = List.of("getbundle", "bundle2");
        conn.heads = List.of(commitHex);
        conn.bookmarks = Map.of("mybook", commitHex);
        conn.phases = Map.of(commitHex, "1"); // secret phase, well-known and locally present
        String url = registerScripted(conn);

        List<byte[]> imported = new FetchCommand(destRepo).setSource(url).call();
        assertTrue(imported.isEmpty(), "No new changesets exist -- must report an empty import list");

        Map<String, String> localBookmarks = new BookmarkCommand(destRepo).call();
        assertEquals(commitHex, localBookmarks.get("mybook"),
                "Bookmark-only remote movement must still be applied even when there is nothing new to pull");
    }

    @Test
    public void fetchSkipsPhaseUpdateForARemoteNodeNotKnownLocally(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "hello");
        new AddCommand(srcRepo).call();
        byte[] commitNode = new CommitCommand(srcRepo).setAuthor("dev").setMessage("v1").call();
        String commitHex = NodeIdUtil.toHex(commitNode);

        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        new FetchCommand(destRepo).applyBundle(HgTestUtils.createMockBundleFromRepo(srcRepo));

        String unknownHex = "a".repeat(40);
        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.capabilities = List.of("getbundle");
        conn.heads = List.of(commitHex);
        conn.phases = Map.of(unknownHex, "0");
        String url = registerScripted(conn);

        // Must not throw despite the remote naming a node this repository has never heard of.
        assertDoesNotThrow(() -> new FetchCommand(destRepo).setSource(url).call());
    }

    @Test
    public void fetchSwallowsExceptionsFromRemoteBookmarkPhaseSync(@TempDir Path tempDir) throws Exception {
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();

        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.capabilities = List.of("getbundle");
        conn.heads = List.of();
        conn.listKeysException = new RuntimeException("remote listkeys boom");
        String url = registerScripted(conn);

        // Empty-heads path returns before any listKeys call, so route through the
        // bundleBytes-empty early return (count==0, non-empty heads) which does call
        // syncBookmarksAndPhases and must swallow the failure rather than propagate it.
        conn.heads = List.of("b".repeat(40));
        conn.bundleBytesToReturn = new byte[0];

        assertDoesNotThrow(() -> new FetchCommand(destRepo).setSource(url).call(),
                "A failure while syncing remote bookmarks/phases must be logged, not thrown");
    }

    @Test
    public void fetchUsesChangegroupFallbackWhenRemoteLacksGetbundleCapability(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "hello");
        new AddCommand(srcRepo).call();
        byte[] commitNode = new CommitCommand(srcRepo).setAuthor("dev").setMessage("v1").call();

        byte[] changegroupBytes = HgTestUtils.serializeBundleToBytes(HgTestUtils.createMockBundleFromRepo(srcRepo));
        byte[] wireBytes = concat(bundle1Header("UN"), changegroupBytes);

        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.capabilities = List.of("changegroup", "lookup"); // deliberately no getbundle/bundle2
        conn.heads = List.of(NodeIdUtil.toHex(commitNode));
        conn.changegroupBytes = wireBytes;
        String url = registerScripted(conn);

        List<byte[]> imported = new FetchCommand(destRepo).setSource(url).call();
        assertEquals(1, imported.size());
        assertArrayEquals(commitNode, imported.get(0));
        assertNull(conn.lastBundleCapsArg, "getBundle must never be called when getbundle capability is absent");
    }

    @Test
    public void fetchAdvertisesBundle2CapsOnlyWhenRemoteSupportsBundle2(@TempDir Path tempDir) throws Exception {
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();

        ScriptedRemoteConnection withoutBundle2 = new ScriptedRemoteConnection();
        withoutBundle2.capabilities = List.of("getbundle");
        withoutBundle2.heads = List.of("c".repeat(40));
        withoutBundle2.bundleBytesToReturn = new byte[0];
        String urlWithout = registerScripted(withoutBundle2);
        new FetchCommand(destRepo).setSource(urlWithout).call();
        assertTrue(withoutBundle2.lastBundleCapsArg.isEmpty(),
                "No bundle2 caps must be advertised when the remote doesn't support bundle2");

        ScriptedRemoteConnection withBundle2 = new ScriptedRemoteConnection();
        withBundle2.capabilities = List.of("getbundle", "bundle2");
        withBundle2.heads = List.of("d".repeat(40));
        withBundle2.bundleBytesToReturn = new byte[0];
        String urlWith = registerScripted(withBundle2);
        new FetchCommand(destRepo).setSource(urlWith).call();
        // 실제 스펙(wireprototypes.GETBUNDLE_ARGUMENTS의 bundlecaps="scsv" 타입, 실제 hg
        // 클라이언트의 캡처된 요청 실측 2026-09-03): 원래의 bare "bundle2" 토큰은 실제 hg가
        // 전혀 안 쓰는 토큰이라 제거됐다 — changegroup 버전 목록은 이제
        // "bundle2=<blob>"(콤마로 중첩된 blob) 토큰 안에 실려 간다. bare "HG20" 토큰은
        // 여전히 그대로 있다(exchange.bundle2requested()가 확인하는 바로 그 토큰).
        assertTrue(withBundle2.lastBundleCapsArg.contains("HG20"));
        assertTrue(withBundle2.lastBundleCapsArg.stream().anyMatch(c -> c.startsWith("bundle2=")),
                "must carry a bundle2=<blob> token instead of the old flat changegroup=... token");
    }

    @Test
    public void fetchPerformsBetweenKnownDiscoveryAndNarrowsCommonSet(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "hello");
        new AddCommand(srcRepo).call();
        byte[] commitNode = new CommitCommand(srcRepo).setAuthor("dev").setMessage("v1").call();
        String knownHex = NodeIdUtil.toHex(commitNode);

        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        new FetchCommand(destRepo).applyBundle(HgTestUtils.createMockBundleFromRepo(srcRepo));

        String newRemoteHeadHex = "f".repeat(40);
        String notLocallyKnownHex = "e".repeat(40);

        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.capabilities = List.of("getbundle");
        // knownHex is present locally (keeps common non-empty); newRemoteHeadHex is not, so
        // upToDate becomes false and the between/known negotiation block runs.
        conn.heads = List.of(knownHex, newRemoteHeadHex);
        conn.betweenResult = List.of(knownHex, notLocallyKnownHex);
        conn.knownResult = "11";
        conn.bundleBytesToReturn = new byte[0];
        String url = registerScripted(conn);

        assertDoesNotThrow(() -> new FetchCommand(destRepo).setSource(url).call());
        assertNotNull(conn.lastCommonArg);
        assertTrue(conn.lastCommonArg.contains(knownHex));
    }

    @Test
    public void fetchFallsBackToLeafMatchWhenDiscoveryNegotiationThrows(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "hello");
        new AddCommand(srcRepo).call();
        byte[] commitNode = new CommitCommand(srcRepo).setAuthor("dev").setMessage("v1").call();
        String knownHex = NodeIdUtil.toHex(commitNode);

        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        new FetchCommand(destRepo).applyBundle(HgTestUtils.createMockBundleFromRepo(srcRepo));

        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.capabilities = List.of("getbundle");
        conn.heads = List.of(knownHex, "9".repeat(40));
        conn.betweenException = new RuntimeException("simulated wire failure");
        conn.bundleBytesToReturn = new byte[0];
        String url = registerScripted(conn);

        assertDoesNotThrow(() -> new FetchCommand(destRepo).setSource(url).call(),
                "A discovery negotiation failure must be caught and fall back to leaf matching, not propagate");
    }

    @Test
    public void fetchDiscoversIndirectCommonNodeAndMarksMergeParentsAsInteriorRevisions(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();

        File f1 = new File(srcDir, "hello.txt");
        Files.writeString(f1.toPath(), "Line 1\nLine 2\nLine 3\n");
        new AddCommand(srcRepo).call();
        byte[] baseNode = new CommitCommand(srcRepo).setAuthor("dev").setMessage("base").call();

        Files.writeString(f1.toPath(), "Line 1 [MINE]\nLine 2\nLine 3\n");
        byte[] yoursNode = new CommitCommand(srcRepo).setAuthor("dev").setMessage("yours").call();

        Dirstate dirstate = srcRepo.getDirstate();
        dirstate.setParents(baseNode, new byte[20]);
        srcRepo.writeDirstate(dirstate);
        Files.writeString(f1.toPath(), "Line 1\nLine 2\nLine 3 [THEIRS]\n");
        new CommitCommand(srcRepo).setAuthor("dev").setMessage("theirs").call();

        new MergeCommand(srcRepo).setNodeId(yoursNode).call();
        byte[] mergeNode = new CommitCommand(srcRepo).setAuthor("dev").setMessage("merge").call();

        File destDir = tempDir.resolve("dest").toFile();
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        new FetchCommand(destRepo).applyBundle(HgTestUtils.createMockBundleFromRepo(srcRepo));

        Revlog destCl = new Revlog(new File(destRepo.getStoreDir(), "00changelog.i"), new File(destRepo.getStoreDir(), "00changelog.d"));
        assertEquals(4, destCl.getRevisionCount(), "sanity: base+yours+theirs+merge must all be present locally");
        // sanity: the merge commit must really carry two parents, so the discovery loop's
        // parent1 AND parent2 interior-marking branches both get exercised below.
        Revlog.IndexRecord mergeRec = destCl.getIndexRecord(3);
        assertTrue(mergeRec.getParent1() >= 0 && mergeRec.getParent2() >= 0);

        String mergeHex = NodeIdUtil.toHex(mergeNode);
        String baseHex = NodeIdUtil.toHex(baseNode);
        String unknownHeadHex = "7".repeat(40);

        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.capabilities = List.of("getbundle");
        conn.heads = List.of(mergeHex, unknownHeadHex);
        conn.betweenResult = List.of(baseHex); // indirectly discovered node, not already in `common`
        conn.knownResult = "11";
        conn.bundleBytesToReturn = new byte[0];
        String url = registerScripted(conn);

        assertDoesNotThrow(() -> new FetchCommand(destRepo).setSource(url).call());
        assertNotNull(conn.lastCommonArg);
        assertTrue(conn.lastCommonArg.contains(baseHex),
                "The between-discovered base node must be folded into the common set even though it wasn't a remote head");
    }

    @Test
    public void fetchReturnsEmptyWhenBundleResponseHasNoData(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "hello");
        new AddCommand(srcRepo).call();
        byte[] commitNode = new CommitCommand(srcRepo).setAuthor("dev").setMessage("v1").call();
        String knownHex = NodeIdUtil.toHex(commitNode);

        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        new FetchCommand(destRepo).applyBundle(HgTestUtils.createMockBundleFromRepo(srcRepo));

        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.capabilities = List.of("getbundle");
        conn.heads = List.of("1".repeat(40)); // an unknown head so upToDate is false, count>0
        conn.bundleBytesToReturn = null;
        String url = registerScripted(conn);

        List<byte[]> imported = new FetchCommand(destRepo).setSource(url).call();
        assertTrue(imported.isEmpty());
    }

    @Test
    public void fetchDecodesUncompressedBundle1Format(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "hello");
        new AddCommand(srcRepo).call();
        byte[] commitNode = new CommitCommand(srcRepo).setAuthor("dev").setMessage("v1").call();
        byte[] changegroupBytes = HgTestUtils.serializeBundleToBytes(HgTestUtils.createMockBundleFromRepo(srcRepo));
        byte[] wireBytes = concat(bundle1Header("UN"), changegroupBytes);

        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.capabilities = List.of("getbundle");
        conn.heads = List.of(NodeIdUtil.toHex(commitNode));
        conn.bundleBytesToReturn = wireBytes;
        String url = registerScripted(conn);

        List<byte[]> imported = new FetchCommand(destRepo).setSource(url).call();
        assertEquals(1, imported.size());
        assertArrayEquals(commitNode, imported.get(0));
    }

    @Test
    public void fetchDecodesGzipCompressedBundle1Format(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "hello");
        new AddCommand(srcRepo).call();
        byte[] commitNode = new CommitCommand(srcRepo).setAuthor("dev").setMessage("v1").call();
        byte[] changegroupBytes = HgTestUtils.serializeBundleToBytes(HgTestUtils.createMockBundleFromRepo(srcRepo));

        java.io.ByteArrayOutputStream compressed = new java.io.ByteArrayOutputStream();
        try (DeflaterOutputStream dos = new DeflaterOutputStream(compressed)) {
            dos.write(changegroupBytes);
        }
        byte[] wireBytes = concat(bundle1Header("GZ"), compressed.toByteArray());

        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.capabilities = List.of("getbundle");
        conn.heads = List.of(NodeIdUtil.toHex(commitNode));
        conn.bundleBytesToReturn = wireBytes;
        String url = registerScripted(conn);

        List<byte[]> imported = new FetchCommand(destRepo).setSource(url).call();
        assertEquals(1, imported.size());
        assertArrayEquals(commitNode, imported.get(0));
    }

    @Test
    public void fetchDecodesBzip2CompressedBundle1Format(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "hello");
        new AddCommand(srcRepo).call();
        byte[] commitNode = new CommitCommand(srcRepo).setAuthor("dev").setMessage("v1").call();
        byte[] changegroupBytes = HgTestUtils.serializeBundleToBytes(HgTestUtils.createMockBundleFromRepo(srcRepo));

        java.io.ByteArrayOutputStream compressed = new java.io.ByteArrayOutputStream();
        try (BZip2CompressorOutputStream bzos = new BZip2CompressorOutputStream(compressed)) {
            bzos.write(changegroupBytes);
        }
        // FetchCommand re-prepends the "BZ" magic real hg strips off the wire; the compressed
        // payload it expects is therefore the real bzip2 stream minus its own leading "BZ".
        byte[] fullBz = compressed.toByteArray();
        byte[] strippedBz = java.util.Arrays.copyOfRange(fullBz, 2, fullBz.length);
        byte[] wireBytes = concat(bundle1Header("BZ"), strippedBz);

        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.capabilities = List.of("getbundle");
        conn.heads = List.of(NodeIdUtil.toHex(commitNode));
        conn.bundleBytesToReturn = wireBytes;
        String url = registerScripted(conn);

        List<byte[]> imported = new FetchCommand(destRepo).setSource(url).call();
        assertEquals(1, imported.size());
        assertArrayEquals(commitNode, imported.get(0));
    }

    @Test
    public void fetchThrowsOnUnsupportedBundle1CompressionFormat(@TempDir Path tempDir) throws Exception {
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();

        byte[] wireBytes = concat(bundle1Header("XX"), "garbage payload".getBytes(StandardCharsets.UTF_8));

        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.capabilities = List.of("getbundle");
        conn.heads = List.of("2".repeat(40));
        conn.bundleBytesToReturn = wireBytes;
        String url = registerScripted(conn);

        assertThrows(HgCorruptDataException.class, () -> new FetchCommand(destRepo).setSource(url).call());
    }

    @Test
    public void fetchPropagatesCredentialsProviderToTheRemoteConnection(@TempDir Path tempDir) throws Exception {
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();

        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.capabilities = List.of("getbundle");
        conn.heads = List.of();
        String url = registerScripted(conn);

        UsernamePasswordCredentialsProvider provider = new UsernamePasswordCredentialsProvider("u", "p");
        new FetchCommand(destRepo).setSource(url).setCredentialsProvider(provider).call();

        assertSame(provider, conn.capturedCredentials);
    }

    @Test
    public void applyBundleRollsBackStoreFilesOnMissingManifestLinkCommit(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "hello");
        new AddCommand(srcRepo).call();
        new CommitCommand(srcRepo).setAuthor("dev").setMessage("v1").call();

        ChangegroupParser.ChangegroupBundle bundle = HgTestUtils.createMockBundleFromRepo(srcRepo);
        // Corrupt the manifest entry so its changeset link can never be resolved against the
        // changelog entries also present in this bundle -- forces applyBundle's failure path.
        ChangegroupParser.ChangeGroupEntry badManifestEntry = bundle.manifestEntries.get(0);
        badManifestEntry.cs = NodeIdUtil.fromHex("9".repeat(40));

        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        FetchCommand fetchCmd = new FetchCommand(destRepo);

        assertThrows(HgCorruptDataException.class, () -> fetchCmd.applyBundle(bundle));

        Revlog cl = new Revlog(new File(destRepo.getStoreDir(), "00changelog.i"), new File(destRepo.getStoreDir(), "00changelog.d"));
        assertEquals(0, cl.getRevisionCount(), "Changelog must be rolled back to its pre-failure state");
        assertFalse(new File(destRepo.getStoreDir(), "journal").exists(), "Journal must be cleaned up after rollback");
    }

    @Test
    public void fetchAppliesClonebundleThenCatchesUpViaNormalDiscovery(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("server_repo").toFile();
        HgRepository serverRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "hello clonebundles wired via fetch");
        new AddCommand(serverRepo).call();
        byte[] commitNode = new CommitCommand(serverRepo).setMessage("v1").setAuthor("dev").call();

        byte[] changegroupBytes = HgTestUtils.serializeBundleToBytes(HgTestUtils.createMockBundleFromRepo(serverRepo));
        byte[] cloneBundleFile = concat("HG10UN".getBytes(StandardCharsets.US_ASCII), changegroupBytes);

        HttpServer bundleServer = HttpServer.create(new InetSocketAddress(0), 0);
        bundleServer.createContext("/full.hg", exchange -> {
            exchange.sendResponseHeaders(200, cloneBundleFile.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(cloneBundleFile);
            }
        });
        bundleServer.start();

        HttpServer wireServer = HttpServer.create(new InetSocketAddress(0), 0);
        wireServer.createContext("/", new HgHttpWireServer(serverRepo));
        wireServer.start();
        try {
            String manifestUrl = "http://127.0.0.1:" + bundleServer.getAddress().getPort() + "/full.hg";
            Files.writeString(new File(serverRepo.getHgDir(), "clonebundles.manifest").toPath(),
                    manifestUrl + " BUNDLESPEC=none-v1\n");

            File destDir = tempDir.resolve("dest").toFile();
            HgRepository destRepo = Hg.init().setDirectory(destDir).call();
            String wireUrl = "http://127.0.0.1:" + wireServer.getAddress().getPort();

            List<byte[]> imported = new FetchCommand(destRepo).setSource(wireUrl).call();

            assertEquals(1, imported.size(), "The commit imported via the clonebundle must be reported");
            assertArrayEquals(commitNode, imported.get(0));

            Revlog cl = new Revlog(new File(destRepo.getStoreDir(), "00changelog.i"), new File(destRepo.getStoreDir(), "00changelog.d"));
            assertEquals(1, cl.getRevisionCount());
        } finally {
            wireServer.stop(0);
            bundleServer.stop(0);
        }
    }

    @Test
    public void fetchSkipsClonebundleWhenManifestHasNoSupportedEntries(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("server_repo").toFile();
        HgRepository serverRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "hello");
        new AddCommand(serverRepo).call();
        byte[] commitNode = new CommitCommand(serverRepo).setMessage("v1").setAuthor("dev").call();

        HttpServer wireServer = HttpServer.create(new InetSocketAddress(0), 0);
        wireServer.createContext("/", new HgHttpWireServer(serverRepo));
        wireServer.start();
        try {
            Files.writeString(new File(serverRepo.getHgDir(), "clonebundles.manifest").toPath(),
                    "https://example.invalid/bundle.hg BUNDLESPEC=zstd-v3-experimental\n");

            File destDir = tempDir.resolve("dest").toFile();
            HgRepository destRepo = Hg.init().setDirectory(destDir).call();
            String wireUrl = "http://127.0.0.1:" + wireServer.getAddress().getPort();

            List<byte[]> imported = new FetchCommand(destRepo).setSource(wireUrl).call();

            assertEquals(1, imported.size(),
                    "With no usable clonebundle entry, fetch must still complete via a normal pull");
            assertArrayEquals(commitNode, imported.get(0));
        } finally {
            wireServer.stop(0);
        }
    }

    @Test
    public void fetchPropagatesClonebundleDownloadFailureWithoutFallingBack(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("server_repo").toFile();
        HgRepository serverRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "hello");
        new AddCommand(serverRepo).call();
        new CommitCommand(serverRepo).setMessage("v1").setAuthor("dev").call();

        HttpServer wireServer = HttpServer.create(new InetSocketAddress(0), 0);
        wireServer.createContext("/", new HgHttpWireServer(serverRepo));
        wireServer.start();
        try {
            Files.writeString(new File(serverRepo.getHgDir(), "clonebundles.manifest").toPath(),
                    "http://127.0.0.1:1/does-not-exist.hg BUNDLESPEC=none-v1\n");

            File destDir = tempDir.resolve("dest").toFile();
            HgRepository destRepo = Hg.init().setDirectory(destDir).call();
            String wireUrl = "http://127.0.0.1:" + wireServer.getAddress().getPort();

            assertThrows(Exception.class, () -> new FetchCommand(destRepo).setSource(wireUrl).call(),
                    "A clonebundle download failure must fail the whole fetch, never fall back silently");
        } finally {
            wireServer.stop(0);
        }
    }
}
