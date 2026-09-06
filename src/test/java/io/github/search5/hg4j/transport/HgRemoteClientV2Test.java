package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.bundle.ChangegroupParser;
import io.github.search5.hg4j.errors.HgAuthException;
import io.github.search5.hg4j.errors.HgProtocolException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.transport.wireprotov2.Cbor;
import io.github.search5.hg4j.transport.wireprotov2.Wire2Commands;
import io.github.search5.hg4j.transport.wireprotov2.Wire2Transport;
import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.util.NodeIdUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.function.BiFunction;

/**
 * Targeted coverage for {@link HgRemoteClientV2}'s error/exception paths, {@code known()},
 * multi-command {@code getBundle} branch combinations, and the delta-vs-full-text handling in
 * {@code resolveFullText} — none of which {@link HgHttpTransportV2RoundtripTest} exercises,
 * because a real {@link HgHttpWireServer} never emits the malformed/edge-case wire shapes these
 * branches guard against ({@code Wire2Commands} always sends full revision text and a
 * well-formed discovery descriptor). These tests therefore hand-roll {@link HttpHandler}s that
 * emit exactly the wire bytes each branch needs.
 */
@DisplayName("HgRemoteClientV2 error paths, known(), and getBundle edge cases")
public class HgRemoteClientV2Test {

    @TempDir
    Path tempDir;

    private HttpServer server;
    private Server jettyServer;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (jettyServer != null) {
            HgTestUtils.stop(jettyServer);
        }
    }

    private int startServer(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", handler);
        server.start();
        return server.getAddress().getPort();
    }

    /** Overload for {@link HgHttpWireServer} itself (now a servlet) -- hosted in an embedded
     * Jetty container rather than the JDK's {@code HttpServer}, unlike the hand-rolled {@link
     * HttpHandler} overload above. */
    private int startServer(HttpServlet servlet) throws Exception {
        jettyServer = HgTestUtils.startServlet(servlet);
        return HgTestUtils.port(jettyServer);
    }

    private HgRepository initRepo(String name) throws Exception {
        File dir = tempDir.resolve(name).toFile();
        return Hg.init().setDirectory(dir).call();
    }

    private static byte[] validCapabilitiesDiscoveryBody() {
        Map<String, Object> apis = new LinkedHashMap<>();
        apis.put(Wire2Commands.NAMESPACE, Wire2Commands.namespaceDescriptor());
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("apibase", "api/");
        descriptor.put("apis", apis);
        descriptor.put("v1capabilities", "");
        return Cbor.encode(descriptor);
    }

    // ==================== setCredentials / setCredentialsProvider / setForceTls ====================

    @Test
    @DisplayName("setCredentials가 실제 요청에 Basic 인증 헤더를 실어 보낸다")
    void testSetCredentialsAddsBasicAuthHeader() throws Exception {
        HgRepository repo = initRepo("auth_repo");
        HgHttpWireServer real = new HgHttpWireServer(repo);
        List<String> capturedAuth = new ArrayList<>();
        int port = startServer(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
                capturedAuth.add(request.getHeader("Authorization"));
                real.service(request, response);
            }
        });

        HgRemoteClientV2 client = new HgRemoteClientV2("http://127.0.0.1:" + port);
        client.setCredentials("alice", "s3cret");
        List<String> heads = client.getHeads();
        assertNotNull(heads);

        assertTrue(capturedAuth.stream().anyMatch(h -> h != null), "적어도 한 요청에 Authorization 헤더가 있어야 함");
        String expected = "Basic " + Base64.getEncoder().encodeToString("alice:s3cret".getBytes(StandardCharsets.UTF_8));
        assertTrue(capturedAuth.contains(expected), "Basic 인증 헤더 값이 user:pass의 Base64 인코딩과 일치해야 함");

        client.close();
    }

    @Test
    @DisplayName("setCredentialsProvider로 채운 자격증명도 Basic 인증 헤더로 전송된다")
    void testSetCredentialsProviderPopulatesAuthHeader() throws Exception {
        HgRepository repo = initRepo("provider_repo");
        HgHttpWireServer real = new HgHttpWireServer(repo);
        List<String> capturedAuth = new ArrayList<>();
        int port = startServer(new HttpServlet() {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
                capturedAuth.add(request.getHeader("Authorization"));
                real.service(request, response);
            }
        });

        HgRemoteClientV2 client = new HgRemoteClientV2("http://127.0.0.1:" + port);
        CredentialsProvider provider = (uri, items) -> {
            for (CredentialItem item : items) {
                if (item instanceof CredentialItem.Username u) {
                    u.setValue("bob");
                } else if (item instanceof CredentialItem.Password p) {
                    p.setValue("hunter2".toCharArray());
                }
            }
            return true;
        };
        client.setCredentialsProvider(provider);
        client.getHeads();

        String expected = "Basic " + Base64.getEncoder().encodeToString("bob:hunter2".getBytes(StandardCharsets.UTF_8));
        assertTrue(capturedAuth.contains(expected), "CredentialsProvider가 채운 값이 Basic 헤더로 전송돼야 함");
    }

    @Test
    @DisplayName("forceTls가 설정되면 평문 http URL 요청은 접속 없이 SecurityException으로 즉시 실패한다")
    void testForceTlsRejectsPlainHttp() {
        HgRemoteClientV2 client = new HgRemoteClientV2("http://127.0.0.1:1");
        client.setForceTls(true);
        assertThrows(SecurityException.class, client::getCapabilities);
    }

    // ==================== ensureDiscovered malformed responses ====================

    @Test
    @DisplayName("capabilities 발견 응답이 비어있으면 HgProtocolException이 발생한다")
    void testEmptyCapabilitiesDiscoveryResponseThrows() throws Exception {
        int port = startServer(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });

        HgRemoteClientV2 client = new HgRemoteClientV2("http://127.0.0.1:" + port);
        HgProtocolException e = assertThrows(HgProtocolException.class, client::getCapabilities);
        assertTrue(e.getMessage().contains("Empty capabilities discovery response"), e.getMessage());
    }

    @Test
    @DisplayName("서버가 wireprotocol v2 namespace를 광고하지 않으면 HgProtocolException이 발생한다")
    void testCapabilitiesDiscoveryMissingNamespaceThrows() throws Exception {
        int port = startServer(exchange -> {
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("apibase", "api/");
            descriptor.put("apis", Map.of());
            byte[] body = Cbor.encode(descriptor);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });

        HgRemoteClientV2 client = new HgRemoteClientV2("http://127.0.0.1:" + port);
        HgProtocolException e = assertThrows(HgProtocolException.class, client::getCapabilities);
        assertTrue(e.getMessage().contains("does not advertise wireprotocol v2"), e.getMessage());
    }

    // ==================== non-2xx HTTP status ====================

    @Test
    @DisplayName("명령 실행 중 서버가 200이 아닌 상태코드를 반환하면 HgProtocolException이 발생한다")
    void testNonOkHttpStatusOnCommandThrows() throws Exception {
        int port = startServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            if ("/".equals(path) && query != null && query.contains("cmd=capabilities")) {
                byte[] body = validCapabilitiesDiscoveryBody();
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
                return;
            }
            exchange.sendResponseHeaders(500, -1);
        });

        HgRemoteClientV2 client = new HgRemoteClientV2("http://127.0.0.1:" + port);
        HgProtocolException e = assertThrows(HgProtocolException.class, client::getHeads);
        assertTrue(e.getMessage().contains("HTTP 500"), e.getMessage());
    }

    // ==================== known() ====================

    @Test
    @DisplayName("known()이 알려진/모르는 노드를 순서대로 1/0 문자열로 정확히 보고한다")
    void testKnownReportsKnownAndUnknownNodesInOrder() throws Exception {
        HgRepository repo = initRepo("known_repo");
        Files.writeString(new File(repo.getDirectory(), "a.txt").toPath(), "content");
        Hg hg = Hg.wrap(repo);
        hg.add().addFile("a.txt").call();
        byte[] commitNode = hg.commit().setMessage("known test").call();
        String knownHex = NodeIdUtil.toHex(commitNode).substring(0, 40);
        String unknownHex = "f".repeat(40);

        HgHttpWireServer real = new HgHttpWireServer(repo);
        int port = startServer(real);
        HgRemoteClientV2 client = new HgRemoteClientV2("http://127.0.0.1:" + port);

        String result = client.known(List.of(knownHex, unknownHex));
        assertEquals("10", result);

        assertEquals("", client.known(List.of()), "빈 노드 목록에는 빈 결과 문자열이 반환돼야 함");
    }

    @Test
    @DisplayName("known 응답이 비어 있으면 빈 문자열을 반환한다")
    void testKnownEmptyServerResponseReturnsEmptyString() throws Exception {
        int port = startServer(new OverridingWire2Handler(null, (name, args) ->
                "known".equals(name) ? List.of() : null));

        HgRemoteClientV2 client = new HgRemoteClientV2("http://127.0.0.1:" + port);
        assertEquals("", client.known(List.of("a".repeat(40))));
    }

    // ==================== getBundle branch combinations ====================

    @Test
    @DisplayName("head가 없는 빈 저장소에 대한 getBundle은 빈 바이트 배열을 반환한다")
    void testGetBundleEmptyRepoReturnsEmptyByteArray() throws Exception {
        HgRepository repo = initRepo("empty_repo");
        HgHttpWireServer real = new HgHttpWireServer(repo);
        int port = startServer(real);
        HgRemoteClientV2 client = new HgRemoteClientV2("http://127.0.0.1:" + port);

        byte[] bundle = client.getBundle(List.of(), List.of(), List.of());
        assertEquals(0, bundle.length);
    }

    @Test
    @DisplayName("common 인자로 넘긴 실제 부모 노드가 결과 changegroup에서 제외된다")
    void testGetBundleWithRealCommonRootExcludesAncestor() throws Exception {
        HgRepository repo = initRepo("common_root_repo");
        File repoDir = repo.getDirectory();
        Hg hg = Hg.wrap(repo);
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v1");
        hg.add().addFile("a.txt").call();
        byte[] c1 = hg.commit().setMessage("c1").call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v2");
        byte[] c2 = hg.commit().setMessage("c2").call();

        HgHttpWireServer real = new HgHttpWireServer(repo);
        int port = startServer(real);
        HgRemoteClientV2 client = new HgRemoteClientV2("http://127.0.0.1:" + port);

        String c1Hex = NodeIdUtil.toHex(c1).substring(0, 40);
        String c2Hex = NodeIdUtil.toHex(c2).substring(0, 40);
        byte[] bundleBytes = client.getBundle(List.of(c1Hex), List.of(c2Hex), List.of());
        assertTrue(bundleBytes.length > 0);

        ChangegroupParser.ChangegroupBundle bundle = ChangegroupParser.parseBundle(
                new ByteArrayInputStream(bundleBytes, 6, bundleBytes.length - 6), "01");
        assertEquals(1, bundle.changelogEntries.size(), "root로 지정된 c1은 결과에서 빠지고 c2만 포함돼야 함");
        assertArrayEquals(Arrays.copyOf(c2, 20), bundle.changelogEntries.get(0).node);
    }

    @Test
    @DisplayName("common과 heads가 동일한 노드면 changeset이 없어 빈 바이트 배열을 반환한다")
    void testGetBundleCommonEqualsHeadReturnsEmptyByteArray() throws Exception {
        HgRepository repo = initRepo("common_eq_head_repo");
        File repoDir = repo.getDirectory();
        Hg hg = Hg.wrap(repo);
        Files.writeString(new File(repoDir, "a.txt").toPath(), "content");
        hg.add().addFile("a.txt").call();
        byte[] head = hg.commit().setMessage("only commit").call();

        HgHttpWireServer real = new HgHttpWireServer(repo);
        int port = startServer(real);
        HgRemoteClientV2 client = new HgRemoteClientV2("http://127.0.0.1:" + port);

        String headHex = NodeIdUtil.toHex(head).substring(0, 40);
        byte[] bundleBytes = client.getBundle(List.of(headHex), List.of(headHex), List.of());
        assertEquals(0, bundleBytes.length);
    }

    @Test
    @DisplayName("한 커밋에서 여러 파일을 건드리면 changegroup에 파일별로 별도 그룹이 생긴다")
    void testGetBundleMultipleFilesProduceMultipleFileGroups() throws Exception {
        HgRepository repo = initRepo("multi_file_repo");
        File repoDir = repo.getDirectory();
        Hg hg = Hg.wrap(repo);
        Files.writeString(new File(repoDir, "a.txt").toPath(), "content a");
        Files.writeString(new File(repoDir, "b.txt").toPath(), "content b");
        hg.add().addFile("a.txt").call();
        hg.add().addFile("b.txt").call();
        hg.commit().setMessage("two files").call();

        HgHttpWireServer real = new HgHttpWireServer(repo);
        int port = startServer(real);
        HgRemoteClientV2 client = new HgRemoteClientV2("http://127.0.0.1:" + port);

        byte[] bundleBytes = client.getBundle(List.of(), client.getHeads(), List.of());
        ChangegroupParser.ChangegroupBundle bundle = ChangegroupParser.parseBundle(
                new ByteArrayInputStream(bundleBytes, 6, bundleBytes.length - 6), "01");
        assertEquals(2, bundle.fileGroups.size());
        List<String> paths = bundle.fileGroups.stream().map(fg -> fg.path).sorted().toList();
        assertEquals(List.of("a.txt", "b.txt"), paths);
    }

    @Test
    @DisplayName("changesetdata 응답이 완전히 비어 있으면 (status만 있고 레코드 없음) 빈 바이트 배열을 반환한다")
    void testGetBundleChangesetdataFullyEmptyResponseReturnsEmptyByteArray() throws Exception {
        int port = startServer(new OverridingWire2Handler(null, (name, args) ->
                "changesetdata".equals(name) ? List.of() : null));

        HgRemoteClientV2 client = new HgRemoteClientV2("http://127.0.0.1:" + port);
        String fakeHeadHex = "a".repeat(40);
        byte[] bundleBytes = client.getBundle(List.of(), List.of(fakeHeadHex), List.of());
        assertEquals(0, bundleBytes.length);
    }

    @Test
    @DisplayName("filesdata 응답에 path 헤더 없이 데이터 레코드가 먼저 오면 해당 레코드는 무시된다")
    void testGetBundleFilesdataRecordWithoutPathHeaderIsSkipped() throws Exception {
        HgRepository repo = initRepo("filesdata_malformed_repo");
        File repoDir = repo.getDirectory();
        Hg hg = Hg.wrap(repo);
        Files.writeString(new File(repoDir, "a.txt").toPath(), "content");
        hg.add().addFile("a.txt").call();
        hg.commit().setMessage("one file").call();

        int port = startServer(new OverridingWire2Handler(repo, (name, args) -> {
            if (!"filesdata".equals(name)) {
                return null;
            }
            List<Object> result = new ArrayList<>();
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("totalpaths", 1L);
            header.put("totalitems", 1L);
            result.add(header);
            // Malformed: a data record ("node" present, no leading {path,...} banner) with no
            // prior path header — HgRemoteClientV2's currentGroup is still null at this point.
            Map<String, Object> dataRecord = new LinkedHashMap<>();
            dataRecord.put("node", NodeIdUtil.fromHex("b".repeat(40)));
            dataRecord.put("parents", List.of(new byte[20], new byte[20]));
            dataRecord.put("linknode", NodeIdUtil.fromHex("c".repeat(40)));
            result.add(dataRecord);
            return result;
        }));

        HgRemoteClientV2 client = new HgRemoteClientV2("http://127.0.0.1:" + port);
        byte[] bundleBytes = client.getBundle(List.of(), client.getHeads(), List.of());
        assertNotNull(bundleBytes);

        ChangegroupParser.ChangegroupBundle bundle = ChangegroupParser.parseBundle(
                new ByteArrayInputStream(bundleBytes, 6, bundleBytes.length - 6), "01");
        assertTrue(bundle.fileGroups.isEmpty(), "path 헤더 없는 레코드는 어떤 fileGroup에도 들어가지 않아야 함");
    }

    // ==================== resolveFullText: delta vs full-text records ====================

    @Test
    @DisplayName("manifestdata 레코드가 revision 대신 delta+deltabasenode를 보내면 정상적으로 재구성된다")
    void testResolveFullTextAppliesDeltaAgainstEarlierRecordInBatch() throws Exception {
        HgRepository repo = initRepo("delta_repo");
        File repoDir = repo.getDirectory();
        Hg hg = Hg.wrap(repo);
        Files.writeString(new File(repoDir, "a.txt").toPath(), "version one");
        hg.add().addFile("a.txt").call();
        hg.commit().setMessage("c1").call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "version two, longer content");
        hg.commit().setMessage("c2").call();

        int port = startServer(new OverridingWire2Handler(repo, (name, args) -> {
            if (!"manifestdata".equals(name)) {
                return null;
            }
            try {
                List<Object> nodesArg = Cbor.asList(args.get("nodes"));
                Revlog manifest = repo.getManifestRevlog();
                List<Object> result = new ArrayList<>();
                Map<String, Object> header = new LinkedHashMap<>();
                header.put("totalitems", (long) nodesArg.size());
                result.add(header);

                byte[] firstFullText = null;
                byte[] firstNode = null;
                boolean first = true;
                for (Object n : nodesArg) {
                    byte[] node = Cbor.asBytes(n);
                    int rev = manifest.findRevision(node);
                    byte[] fullText = manifest.getRevisionContent(rev);
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("node", node);
                    d.put("parents", List.of(new byte[20], new byte[20]));

                    List<Object> following = new ArrayList<>();
                    List<String> followingNames = new ArrayList<>();
                    if (first) {
                        following.add(fullText);
                        followingNames.add("revision");
                        firstFullText = fullText;
                        firstNode = node;
                        first = false;
                    } else {
                        d.put("delta", Revlog.createDelta(firstFullText, fullText));
                        d.put("deltabasenode", firstNode);
                    }
                    if (!following.isEmpty()) {
                        List<Object> fieldsFollowing = new ArrayList<>();
                        for (int i = 0; i < following.size(); i++) {
                            fieldsFollowing.add(List.of(followingNames.get(i), (long) ((byte[]) following.get(i)).length));
                        }
                        d.put("fieldsfollowing", fieldsFollowing);
                    }
                    result.add(d);
                    result.addAll(following);
                }
                return result;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }));

        HgRemoteClientV2 client = new HgRemoteClientV2("http://127.0.0.1:" + port);
        byte[] bundleBytes = client.getBundle(List.of(), client.getHeads(), List.of());
        assertNotNull(bundleBytes);

        ChangegroupParser.ChangegroupBundle bundle = ChangegroupParser.parseBundle(
                new ByteArrayInputStream(bundleBytes, 6, bundleBytes.length - 6), "01");
        assertEquals(2, bundle.changelogEntries.size());
        assertEquals(2, bundle.manifestEntries.size(), "delta로 보내진 두 번째 manifest 레코드도 정상적으로 복원돼야 함");
    }

    @Test
    @DisplayName("manifestdata 레코드에 revision도 delta도 없으면 HgProtocolException이 발생한다")
    void testResolveFullTextThrowsWhenNeitherRevisionNorDeltaPresent() throws Exception {
        HgRepository repo = initRepo("delta_missing_repo");
        File repoDir = repo.getDirectory();
        Hg hg = Hg.wrap(repo);
        Files.writeString(new File(repoDir, "a.txt").toPath(), "content");
        hg.add().addFile("a.txt").call();
        hg.commit().setMessage("c1").call();

        int port = startServer(new OverridingWire2Handler(repo, (name, args) -> {
            if (!"manifestdata".equals(name)) {
                return null;
            }
            List<Object> nodesArg = Cbor.asList(args.get("nodes"));
            List<Object> result = new ArrayList<>();
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("totalitems", (long) nodesArg.size());
            result.add(header);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("node", Cbor.asBytes(nodesArg.get(0)));
            d.put("parents", List.of(new byte[20], new byte[20]));
            result.add(d); // no "revision", no "delta"/"deltabasenode"
            return result;
        }));

        HgRemoteClientV2 client = new HgRemoteClientV2("http://127.0.0.1:" + port);
        HgProtocolException e = assertThrows(HgProtocolException.class,
                () -> client.getBundle(List.of(), client.getHeads(), List.of()));
        assertTrue(e.getMessage().contains("neither 'revision' nor 'delta'+'deltabasenode'"), e.getMessage());
    }

    @Test
    @DisplayName("delta의 deltabasenode가 이번 배치에서 찾을 수 없으면 HgProtocolException이 발생한다")
    void testResolveFullTextThrowsWhenDeltaBaseNotFoundInBatch() throws Exception {
        HgRepository repo = initRepo("delta_base_missing_repo");
        File repoDir = repo.getDirectory();
        Hg hg = Hg.wrap(repo);
        Files.writeString(new File(repoDir, "a.txt").toPath(), "content");
        hg.add().addFile("a.txt").call();
        hg.commit().setMessage("c1").call();

        int port = startServer(new OverridingWire2Handler(repo, (name, args) -> {
            if (!"manifestdata".equals(name)) {
                return null;
            }
            List<Object> nodesArg = Cbor.asList(args.get("nodes"));
            List<Object> result = new ArrayList<>();
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("totalitems", (long) nodesArg.size());
            result.add(header);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("node", Cbor.asBytes(nodesArg.get(0)));
            d.put("parents", List.of(new byte[20], new byte[20]));
            d.put("delta", new byte[]{1, 2, 3});
            d.put("deltabasenode", NodeIdUtil.fromHex("d".repeat(40)));
            result.add(d);
            return result;
        }));

        HgRemoteClientV2 client = new HgRemoteClientV2("http://127.0.0.1:" + port);
        HgProtocolException e = assertThrows(HgProtocolException.class,
                () -> client.getBundle(List.of(), client.getHeads(), List.of()));
        assertTrue(e.getMessage().contains("not found among already-fetched revisions"), e.getMessage());
    }

    // ==================== HTTP auth failure / URL normalization ====================

    @Test
    @DisplayName("401 응답에 자격증명이 설정돼 있으면 HgAuthException 메시지에 사용자명이 포함된다 (trailing slash URL도 정상 정규화)")
    void testUnauthorizedWithCredentialsIncludesUsernameAndStripsTrailingSlash() throws Exception {
        int port = startServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            if ("/".equals(path) && query != null && query.contains("cmd=capabilities")) {
                byte[] body = validCapabilitiesDiscoveryBody();
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
                return;
            }
            exchange.sendResponseHeaders(401, -1);
        });

        // Trailing slash exercises the constructor's URL-normalization ternary.
        HgRemoteClientV2 client = new HgRemoteClientV2("http://127.0.0.1:" + port + "/");
        client.setCredentials("dave", "pw");
        HgAuthException e = assertThrows(HgAuthException.class, client::getHeads);
        assertTrue(e.getMessage().contains("dave"), e.getMessage());
    }

    @Test
    @DisplayName("403 응답에 자격증명이 없으면 HgAuthException 메시지에 anonymous가 포함된다")
    void testForbiddenWithoutCredentialsReportsAnonymous() throws Exception {
        int port = startServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            if ("/".equals(path) && query != null && query.contains("cmd=capabilities")) {
                byte[] body = validCapabilitiesDiscoveryBody();
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
                return;
            }
            exchange.sendResponseHeaders(403, -1);
        });

        HgRemoteClientV2 client = new HgRemoteClientV2("http://127.0.0.1:" + port);
        HgAuthException e = assertThrows(HgAuthException.class, client::getHeads);
        assertTrue(e.getMessage().contains("anonymous"), e.getMessage());
    }

    // ==================== pushkey null-defaulting and false-result branches ====================

    @Test
    @DisplayName("pushkey에 old/new로 null을 넘기면 빈 문자열로 취급된다")
    void testPushkeyNullOldAndNewValuesDefaultToEmptyString() throws Exception {
        HgRepository repo = initRepo("pushkey_null_repo");
        HgHttpWireServer real = new HgHttpWireServer(repo);
        int port = startServer(real);
        HgRemoteClientV2 client = new HgRemoteClientV2("http://127.0.0.1:" + port);

        boolean ok = client.pushkey("bookmarks", "newbook", null, null);
        assertTrue(ok, "존재하지 않던 키에 old=null(빈 문자열로 취급)이면 성공해야 함");
    }

    @Test
    @DisplayName("pushkey의 old 값이 실제 현재값과 다르면 false를 반환한다")
    void testPushkeyWrongOldValueReturnsFalse() throws Exception {
        HgRepository repo = initRepo("pushkey_false_repo");
        File repoDir = repo.getDirectory();
        Hg hg = Hg.wrap(repo);
        Files.writeString(new File(repoDir, "a.txt").toPath(), "content");
        hg.add().addFile("a.txt").call();
        byte[] commitNode = hg.commit().setMessage("c1").call();
        String hex = NodeIdUtil.toHex(commitNode).substring(0, 40);

        HgHttpWireServer real = new HgHttpWireServer(repo);
        int port = startServer(real);
        HgRemoteClientV2 client = new HgRemoteClientV2("http://127.0.0.1:" + port);
        assertTrue(client.pushkey("bookmarks", "mybook", "", hex));

        boolean ok = client.pushkey("bookmarks", "mybook", "wrong-old-value", hex);
        assertTrue(!ok, "실제 현재값과 다른 old 값을 넘기면 false가 반환돼야 함");
    }

    // ==================== malformed responses missing the optional parents/linknode fields ====================

    @Test
    @DisplayName("changesetdata 레코드에 parents가 비어있으면 p1/p2가 null-node로 대체된다")
    void testChangesetdataMissingParentsFallsBackToNullNode() throws Exception {
        HgRepository repo = initRepo("cs_missing_parents_repo");
        File repoDir = repo.getDirectory();
        Hg hg = Hg.wrap(repo);
        Files.writeString(new File(repoDir, "a.txt").toPath(), "content");
        hg.add().addFile("a.txt").call();
        hg.commit().setMessage("c1").call();

        int port = startServer(new OverridingWire2Handler(repo, (name, args) -> {
            if (!"changesetdata".equals(name)) {
                return null;
            }
            try {
                Revlog changelog = Wire2Commands.changelog(repo);
                byte[] node = changelog.getIndexRecord(0).getNodeId();
                byte[] revisionText = changelog.getRevisionContent(0);
                List<Object> result = new ArrayList<>();
                Map<String, Object> header = new LinkedHashMap<>();
                header.put("totalitems", 1L);
                result.add(header);
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("node", node);
                d.put("parents", List.of()); // malformed: real Wire2Commands always sends 2 entries
                d.put("fieldsfollowing", List.of(List.of("revision", (long) revisionText.length)));
                result.add(d);
                result.add(revisionText);
                return result;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }));

        HgRemoteClientV2 client = new HgRemoteClientV2("http://127.0.0.1:" + port);
        byte[] bundleBytes = client.getBundle(List.of(), client.getHeads(), List.of());
        ChangegroupParser.ChangegroupBundle bundle = ChangegroupParser.parseBundle(
                new ByteArrayInputStream(bundleBytes, 6, bundleBytes.length - 6), "01");
        assertEquals(1, bundle.changelogEntries.size());
        assertArrayEquals(new byte[20], bundle.changelogEntries.get(0).p1);
        assertArrayEquals(new byte[20], bundle.changelogEntries.get(0).p2);
    }

    @Test
    @DisplayName("manifestdata 레코드에 parents가 비어있으면 p1/p2가 null-node로 대체된다")
    void testManifestdataMissingParentsFallsBackToNullNode() throws Exception {
        HgRepository repo = initRepo("mf_missing_parents_repo");
        File repoDir = repo.getDirectory();
        Hg hg = Hg.wrap(repo);
        Files.writeString(new File(repoDir, "a.txt").toPath(), "content");
        hg.add().addFile("a.txt").call();
        hg.commit().setMessage("c1").call();

        int port = startServer(new OverridingWire2Handler(repo, (name, args) -> {
            if (!"manifestdata".equals(name)) {
                return null;
            }
            try {
                List<Object> nodesArg = Cbor.asList(args.get("nodes"));
                Revlog manifest = repo.getManifestRevlog();
                List<Object> result = new ArrayList<>();
                Map<String, Object> header = new LinkedHashMap<>();
                header.put("totalitems", (long) nodesArg.size());
                result.add(header);
                for (Object n : nodesArg) {
                    byte[] node = Cbor.asBytes(n);
                    int rev = manifest.findRevision(node);
                    byte[] fullText = manifest.getRevisionContent(rev);
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("node", node);
                    d.put("parents", List.of()); // malformed: real Wire2Commands always sends 2 entries
                    d.put("fieldsfollowing", List.of(List.of("revision", (long) fullText.length)));
                    result.add(d);
                    result.add(fullText);
                }
                return result;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }));

        HgRemoteClientV2 client = new HgRemoteClientV2("http://127.0.0.1:" + port);
        byte[] bundleBytes = client.getBundle(List.of(), client.getHeads(), List.of());
        ChangegroupParser.ChangegroupBundle bundle = ChangegroupParser.parseBundle(
                new ByteArrayInputStream(bundleBytes, 6, bundleBytes.length - 6), "01");
        assertEquals(1, bundle.manifestEntries.size());
        assertArrayEquals(new byte[20], bundle.manifestEntries.get(0).p1);
        assertArrayEquals(new byte[20], bundle.manifestEntries.get(0).p2);
    }

    @Test
    @DisplayName("filesdata 레코드에 parents/linknode가 없으면 각각 null-node와 자기 자신의 node로 대체된다")
    void testFilesdataMissingParentsAndLinknodeFallsBack() throws Exception {
        HgRepository repo = initRepo("fl_missing_parents_repo");
        File repoDir = repo.getDirectory();
        Hg hg = Hg.wrap(repo);
        Files.writeString(new File(repoDir, "a.txt").toPath(), "content");
        hg.add().addFile("a.txt").call();
        hg.commit().setMessage("c1").call();

        int port = startServer(new OverridingWire2Handler(repo, (name, args) -> {
            if (!"filesdata".equals(name)) {
                return null;
            }
            try {
                File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "a.txt");
                File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
                Revlog filelog = repo.getRevlog(flIdx, flDat);
                byte[] fnode = filelog.getIndexRecord(0).getNodeId();
                byte[] content = filelog.getRevisionContent(0);

                List<Object> result = new ArrayList<>();
                Map<String, Object> header = new LinkedHashMap<>();
                header.put("totalpaths", 1L);
                header.put("totalitems", 1L);
                result.add(header);
                Map<String, Object> pathHeader = new LinkedHashMap<>();
                pathHeader.put("path", "a.txt");
                pathHeader.put("totalitems", 1L);
                result.add(pathHeader);
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("node", fnode);
                d.put("parents", List.of()); // malformed: no parents
                // "linknode" deliberately omitted: real hg always sends it when the field is requested
                d.put("fieldsfollowing", List.of(List.of("revision", (long) content.length)));
                result.add(d);
                result.add(content);
                return result;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }));

        HgRemoteClientV2 client = new HgRemoteClientV2("http://127.0.0.1:" + port);
        byte[] bundleBytes = client.getBundle(List.of(), client.getHeads(), List.of());
        ChangegroupParser.ChangegroupBundle bundle = ChangegroupParser.parseBundle(
                new ByteArrayInputStream(bundleBytes, 6, bundleBytes.length - 6), "01");
        assertEquals(1, bundle.fileGroups.size());
        ChangegroupParser.ChangeGroupEntry entry = bundle.fileGroups.get(0).entries.get(0);
        assertArrayEquals(new byte[20], entry.p1);
        assertArrayEquals(new byte[20], entry.p2);
        assertArrayEquals(entry.node, entry.cs, "linknode가 없으면 자기 자신의 node가 cs로 사용돼야 함");
    }

    /**
     * A wireprotocol v2 HTTP handler that serves a real, valid capabilities-discovery handshake
     * and delegates every command to real {@link Wire2Commands} dispatch against {@code repo} —
     * except when {@code override} returns a non-null response list for a given command name, in
     * which case that crafted response is sent verbatim instead. Lets a test keep the rest of a
     * real pull working end-to-end while injecting exactly one malformed/edge-case command
     * response. {@code repo} may be {@code null} when every dispatched command is overridden
     * (protocol-level-only tests that never touch a real repository).
     */
    private static final class OverridingWire2Handler implements HttpHandler {
        private final HgRepository repo;
        private final BiFunction<String, Map<String, Object>, List<Object>> override;

        OverridingWire2Handler(HgRepository repo, BiFunction<String, Map<String, Object>, List<Object>> override) {
            this.repo = repo;
            this.override = override;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            if ("/".equals(path) && query != null && query.contains("cmd=capabilities")) {
                byte[] body = validCapabilitiesDiscoveryBody();
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
                return;
            }
            if (path.startsWith("/api/")) {
                try {
                    List<Wire2Transport.ParsedCommandRequest> commands;
                    try (InputStream in = exchange.getRequestBody()) {
                        commands = Wire2Transport.readAllCommandRequests(in);
                    }
                    ByteArrayOutputStream combined = new ByteArrayOutputStream();
                    if (!commands.isEmpty()) {
                        combined.write(Wire2Transport.buildStreamSettingsFrame(commands.get(0).requestId));
                    }
                    for (Wire2Transport.ParsedCommandRequest cmd : commands) {
                        List<Object> resp = override.apply(cmd.name, cmd.args);
                        if (resp == null) {
                            resp = dispatch(cmd.name, cmd.args);
                        }
                        combined.write(Wire2Transport.buildCommandResponseFrames(cmd.requestId, resp));
                    }
                    byte[] bytes = combined.toByteArray();
                    exchange.sendResponseHeaders(200, bytes.length == 0 ? -1 : bytes.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                } catch (Exception e) {
                    exchange.sendResponseHeaders(500, -1);
                }
                return;
            }
            exchange.sendResponseHeaders(404, -1);
        }

        private List<Object> dispatch(String command, Map<String, Object> args) throws IOException {
            switch (command) {
                case "heads":
                    return Wire2Commands.heads(repo);
                case "known":
                    return Wire2Commands.known(repo, args);
                case "changesetdata":
                    return Wire2Commands.changesetdata(repo, args);
                case "manifestdata":
                    return Wire2Commands.manifestdata(repo, args);
                case "filesdata":
                    return Wire2Commands.filesdata(repo, args);
                default:
                    throw new HgProtocolException("wireprotov2", "unhandled test command: " + command);
            }
        }
    }
}
