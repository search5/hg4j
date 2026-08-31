package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import static org.junit.jupiter.api.Assertions.*;

public class BookmarkCommandTest {

    @Test
    public void testBookmarkLifecycle(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Initial bookmarks should be empty
        Map<String, String> initial = new BookmarkCommand(repo).call();
        assertTrue(initial.isEmpty());
        assertNull(new BookmarkCommand(repo).getActiveBookmark());

        // 2. Commit a changeset
        File file = new File(repoDir, "a.txt");
        Files.writeString(file.toPath(), "Content");
        new AddCommand(repo).call();
        byte[] commitNode = new CommitCommand(repo).setMessage("First commit").call();

        // 3. Create bookmark "feature-x" (should default to commitNode since it is parent 1)
        Map<String, String> created = new BookmarkCommand(repo).setBookmarkName("feature-x").call();
        assertEquals(1, created.size());
        
        String hexNode = toHex(commitNode).substring(0, 40);
        assertEquals(hexNode, created.get("feature-x"));

        // 4. Activate bookmark "feature-x"
        new BookmarkCommand(repo).setBookmarkName("feature-x").setActive(true).call();
        assertEquals("feature-x", new BookmarkCommand(repo).getActiveBookmark());

        // 5. Create another bookmark "feature-y"
        new BookmarkCommand(repo).setBookmarkName("feature-y").call();
        Map<String, String> list = new BookmarkCommand(repo).call();
        assertEquals(2, list.size());
        assertEquals(hexNode, list.get("feature-y"));

        // 6. Delete bookmark "feature-x"
        new BookmarkCommand(repo).setBookmarkName("feature-x").setDelete(true).call();
        Map<String, String> postDelete = new BookmarkCommand(repo).call();
        assertEquals(1, postDelete.size());
        assertFalse(postDelete.containsKey("feature-x"));
        assertTrue(postDelete.containsKey("feature-y"));

        // 실제 hg CLI로 확인(2026-09-01): -r 없이(현재 작업 사본 부모를 암묵적으로 대상으로)
        // 새 bookmark를 만들면 자동으로 active가 된다 — 그래서 5단계에서 "feature-y"를 만든
        // 시점에 이미 active가 "feature-x"에서 "feature-y"로 넘어가 있었고, "feature-x"
        // 삭제는(이미 active가 아니었으므로) active 상태에 영향을 주지 않는다.
        assertEquals("feature-y", new BookmarkCommand(repo).getActiveBookmark());
    }

    @Test
    public void testBookmarkCommandExceptions(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        assertThrows(IllegalArgumentException.class, () -> 
                new BookmarkCommand(repo).setDelete(true).call()); // no name for delete

        assertThrows(IllegalArgumentException.class, () -> 
                new BookmarkCommand(repo).setBookmarkName("nonexistent").setActive(true).call()); // activate nonexistent
    }

    @Test
    public void testBookmarkAutoAdvanceAndSwitch(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);

        // 1. Initial Commit
        File file = new File(repoDir, "a.txt");
        Files.writeString(file.toPath(), "initial");
        hg.add().addFile("a.txt").call();
        byte[] commitNode1 = hg.commit().setMessage("Commit 1").call();
        String hex1 = toHex(commitNode1).substring(0, 40);

        // 2. Create and Activate Bookmark "feature-z"
        new BookmarkCommand(repo).setBookmarkName("feature-z").call();
        new BookmarkCommand(repo).setBookmarkName("feature-z").setActive(true).call();
        assertEquals("feature-z", new BookmarkCommand(repo).getActiveBookmark());

        // 3. Commit 2 (should auto-advance "feature-z")
        Files.writeString(file.toPath(), "advanced");
        byte[] commitNode2 = hg.commit().setMessage("Commit 2").call();
        String hex2 = toHex(commitNode2).substring(0, 40);

        // 검증: 활성 북마크 "feature-z"가 2차 커밋의 해시(hex2)로 전진하였는지 검사
        Map<String, String> bookmarks = new BookmarkCommand(repo).call();
        assertEquals(hex2, bookmarks.get("feature-z"), "Active bookmark should auto-advance to the latest commit node");
        assertEquals("feature-z", new BookmarkCommand(repo).getActiveBookmark());

        // 4. Update back to Commit 1 (should deactivate "feature-z" since we moved away)
        hg.update().setRevision(hex1).call();
        assertNull(new BookmarkCommand(repo).getActiveBookmark(), "Active bookmark should be deactivated when updating away to a node without bookmarks");
    }

    @Test
    public void testPullBookmarksMergeAndDivergence(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 0. 1차 커밋을 작성하여 FetchCommand가 up-to-date 조기 종료할 수 있게 준비
        File fInit = new File(repoDir, "a.txt");
        Files.writeString(fInit.toPath(), "init content");
        new AddCommand(repo).addFile("a.txt").call();
        byte[] initCommit = new CommitCommand(repo).setMessage("init").call();
        String headHex = toHex(initCommit).substring(0, 40);
        
        // 1. 로컬에 feature-conflict 북마크 생성 (가짜 해시)
        String localHash = "1111111111111111111111111111111111111111";
        new BookmarkCommand(repo).setBookmarkName("feature-conflict").setRevision(localHash).call();

        // 2. Mock HTTP Server 기동 (원격 북마크 전송 시뮬레이션)
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        try {
        
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                // V1 listkeys?namespace=bookmarks 통신 처리
                String query = exchange.getRequestURI().getRawQuery();
                if (query != null && query.contains("namespace=bookmarks")) {
                    // feature-conflict -> 다른 해시 (충돌), feature-new -> 새 해시 (병합)
                    String response = "feature-conflict-same\t1111111111111111111111111111111111111111\n" +
                                     "feature-conflict\t2222222222222222222222222222222222222222\n" +
                                     "feature-new\t3333333333333333333333333333333333333333\n";
                    exchange.sendResponseHeaders(200, response.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes(StandardCharsets.UTF_8));
                    }
                } else if (query != null && query.contains("cmd=heads")) {
                    // 원격 heads 쿼리에 대해 로컬 최신 커밋 해시를 응답하여 동기화 유도
                    String response = headHex + "\n";
                    exchange.sendResponseHeaders(200, response.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes(StandardCharsets.UTF_8));
                    }
                } else if (query != null && (query.contains("cmd=changegroup") || query.contains("cmd=getbundle"))) {
                    // 빈 changegroup 번들 반환 (HG10UN 헤더 + 0 리비전)
                    byte[] emptyBundle = new byte[]{'H', 'G', '1', '0', 'U', 'N', 0, 0, 0, 0};
                    exchange.sendResponseHeaders(200, emptyBundle.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(emptyBundle);
                    }
                } else {
                    // capabilities
                    String response = "lookup changegroup listkeys\n";
                    exchange.sendResponseHeaders(200, response.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
        });
        server.start();

        PullCommand pull = new PullCommand(repo).setSource("http://127.0.0.1:" + port);
        pull.call();

            Map<String, String> localBks = new BookmarkCommand(repo).call();
            
            // 검증: 로컬에 없던 feature-new 북마크는 원격 해시("3333...")로 정상 유입되었어야 함
            assertTrue(localBks.containsKey("feature-new"));
            assertEquals("3333333333333333333333333333333333333333", localBks.get("feature-new"));

            // 검증: 원격이 보낸 "feature-conflict" 값("2222...")은 로컬 저장소에 실제로 존재하지
            // 않는 리비전이므로(이 테스트는 실제 changegroup을 주고받지 않는 순수 mock) — 실제
            // Mercurial의 comparebookmarks()도 양쪽 다 "in repo"인 경우에만 ancestor 비교/분기
            // 생성을 시도한다 — 존재하지 않는 대상에 대해서는 로컬 값을 그대로 보존하고 유령
            // divergent bookmark를 만들지 않아야 한다(2026-09-01 정정 — 예전엔 존재 여부를
            // 확인하지 않고 무조건 name@default를 만들었음). 실제 ancestor 기반 divergence
            // 판정은 진짜 hg 데이터로 BookmarkRealHgInteropTest에서 검증한다.
            assertEquals(localHash, localBks.get("feature-conflict"), "존재하지 않는 원격 리비전에 대해 로컬 값이 보존돼야 함");
            assertFalse(localBks.containsKey("feature-conflict@default"),
                    "원격이 가리키는 리비전이 로컬에 없으면 유령 divergent bookmark를 만들면 안 됨");
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testPushBookmarksSync(@TempDir Path tempDir) throws Exception {
        File repoDir1 = tempDir.resolve("local").toFile();
        File repoDir2 = tempDir.resolve("remote").toFile();

        // 두 로컬 저장소 생성
        HgRepository localRepo = Hg.init().setDirectory(repoDir1).call();
        HgRepository remoteRepo = Hg.init().setDirectory(repoDir2).call();

        // 1. remote에 커밋 추가
        File f2 = new File(repoDir2, "b.txt");
        Files.writeString(f2.toPath(), "remote content");
        Hg hgRemote = Hg.wrap(remoteRepo);
        hgRemote.add().addFile("b.txt").call();
        byte[] headBytes = hgRemote.commit().setMessage("remote init").call();
        String headHex = toHex(headBytes).substring(0, 40);

        // 2. remote 디렉토리를 local 디렉토리로 물리 복사하여 완벽하게 동일한 복제본 수립
        repoDir1.mkdirs();
        copyFolder(repoDir2, repoDir1);
        
        localRepo = new HgRepository(repoDir1);
        Hg hgLocal = Hg.wrap(localRepo);

        // 3. local 저장소에 추가 2차 커밋 작성하여 전진
        File f1 = new File(repoDir1, "local.txt");
        Files.writeString(f1.toPath(), "local progress");
        hgLocal.add().addFile("local.txt").call();
        byte[] localHeadBytes = hgLocal.commit().setMessage("local progress commit").call();
        String localHeadHex = toHex(localHeadBytes).substring(0, 40);

        new BookmarkCommand(localRepo).setBookmarkName("feature-push").setRevision(localHeadHex).call();

        // 4. pushkey 통신이 local push 시 기동되어 remote 북마크에 feature-push가 수립되는지 검증
        try {
            PushCommand push = new PushCommand(localRepo).setDestination(repoDir2.getAbsolutePath());
            push.call();
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }

        // 원격에 북마크 feature-push가 올바르게 주입(pushkey 완료)되었는지 검사
        Map<String, String> remoteBks = new BookmarkCommand(remoteRepo).call();
        assertTrue(remoteBks.containsKey("feature-push"));
        assertEquals(localHeadHex, remoteBks.get("feature-push"));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(2 * bytes.length);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
    private static void copyFolder(File source, File destination) throws IOException {
        if (source.isDirectory()) {
            if (!destination.exists()) {
                destination.mkdirs();
            }
            String[] files = source.list();
            if (files != null) {
                for (String file : files) {
                    File srcFile = new File(source, file);
                    File destFile = new File(destination, file);
                    copyFolder(srcFile, destFile);
                }
            }
        } else {
            Files.copy(source.toPath(), destination.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
