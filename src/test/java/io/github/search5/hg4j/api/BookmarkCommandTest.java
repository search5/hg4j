package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.StandardCopyOption;

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
                new BookmarkCommand(repo).setBookmarkName("").setDelete(true).call()); // empty (non-null) name for delete

        assertThrows(IllegalArgumentException.class, () ->
                new BookmarkCommand(repo).setBookmarkName("nonexistent").setActive(true).call()); // activate nonexistent
    }

    @Test
    public void testEmptyBookmarkNameIsNoOp(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File file = new File(repoDir, "a.txt");
        Files.writeString(file.toPath(), "content");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("c1").call();
        new BookmarkCommand(repo).setBookmarkName("existing").call();

        Map<String, String> result = new BookmarkCommand(repo).setBookmarkName("").call();

        assertEquals(1, result.size(), "빈 문자열 bookmark 이름은 생성/수정으로 취급되면 안 됨");
        assertFalse(result.containsKey(""), "빈 이름의 bookmark가 생성돼선 안 됨");
    }

    @Test
    public void testSetRevisionIgnoresNullOrEmptyRevision(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File file = new File(repoDir, "a.txt");
        Files.writeString(file.toPath(), "content");
        new AddCommand(repo).call();
        byte[] commitNode = new CommitCommand(repo).setMessage("c1").call();
        String hex = toHex(commitNode).substring(0, 40);

        Map<String, String> viaNull = new BookmarkCommand(repo).setBookmarkName("b-null").setRevision(null).call();
        assertEquals(hex, viaNull.get("b-null"), "revision(null)은 무시되고 현재 부모로 폴백해야 함");

        Map<String, String> viaEmpty = new BookmarkCommand(repo).setBookmarkName("b-empty").setRevision("").call();
        assertEquals(hex, viaEmpty.get("b-empty"), "revision(\"\")도 무시되고 현재 부모로 폴백해야 함");
    }

    @Test
    public void testDeleteNonexistentBookmarkNameOnEmptyRepoIsNoOp(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File bkFile = new File(repo.getHgDir(), "bookmarks");
        assertFalse(bkFile.exists(), "테스트 전제: bookmark가 하나도 생성된 적 없어 bookmarks 파일이 없어야 함");

        Map<String, String> result = new BookmarkCommand(repo).setBookmarkName("never-existed").setDelete(true).call();

        assertTrue(result.isEmpty());
        assertFalse(bkFile.exists(), "존재한 적 없는 파일을 지우려 하면 안 됨(writeBookmarks의 file.exists()==false 분기)");
    }

    @Test
    public void testDeleteInactiveBookmarkWithNoCurrentFileLeavesNoTrace(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File file = new File(repoDir, "a.txt");
        Files.writeString(file.toPath(), "content");
        new AddCommand(repo).call();
        byte[] commitNode = new CommitCommand(repo).setMessage("c1").call();
        String hex = toHex(commitNode).substring(0, 40);

        // setRevision을 명시하면 auto-activate 되지 않으므로 bookmarks.current가 아예 생기지 않는다
        new BookmarkCommand(repo).setBookmarkName("never-active").setRevision(hex).call();
        File curBkFile = new File(repo.getHgDir(), "bookmarks.current");
        assertFalse(curBkFile.exists(), "테스트 전제: 명시적 revision으로 만든 bookmark는 active가 되면 안 됨");

        Map<String, String> result = new BookmarkCommand(repo).setBookmarkName("never-active").setDelete(true).call();

        assertTrue(result.isEmpty());
        assertFalse(curBkFile.exists(), "애초에 없던 bookmarks.current가 삭제 시도로 새로 생기면 안 됨");
    }

    @Test
    public void testExplicitDeactivateWithEmptyNameClearsCurrentFile(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File file = new File(repoDir, "a.txt");
        Files.writeString(file.toPath(), "content");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("c1").call();
        new BookmarkCommand(repo).setBookmarkName("b1").call();
        new BookmarkCommand(repo).setBookmarkName("b1").setActive(true).call();
        assertEquals("b1", new BookmarkCommand(repo).getActiveBookmark());

        new BookmarkCommand(repo).setBookmarkName("").setActive(true).call();

        assertNull(new BookmarkCommand(repo).getActiveBookmark(),
                "빈 문자열 이름으로 setActive(true)를 호출해도 이름 없음과 동일하게 비활성화돼야 함");
    }

    @Test
    public void testBookmarksFileParsingSkipsBlankAndMalformedLines(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File bkFile = new File(repo.getHgDir(), "bookmarks");
        String hex = "6666666666666666666666666666666666666666";
        Files.writeString(bkFile.toPath(),
                "\n" +
                "   \n" +
                "malformed-line-without-space\n" +
                hex + " good-bookmark\n",
                StandardCharsets.UTF_8);

        Map<String, String> result = new BookmarkCommand(repo).call();

        assertEquals(1, result.size(), "빈 줄과 공백이 없는 손상된 줄은 무시되고 정상 항목만 파싱돼야 함");
        assertEquals(hex, result.get("good-bookmark"));
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

    @Test
    public void testDeleteActiveBookmarkClearsCurrentFile(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File file = new File(repoDir, "a.txt");
        Files.writeString(file.toPath(), "content");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("c1").call();

        new BookmarkCommand(repo).setBookmarkName("to-delete").call();
        new BookmarkCommand(repo).setBookmarkName("to-delete").setActive(true).call();
        assertEquals("to-delete", new BookmarkCommand(repo).getActiveBookmark());

        new BookmarkCommand(repo).setBookmarkName("to-delete").setDelete(true).call();

        assertNull(new BookmarkCommand(repo).getActiveBookmark(),
                "삭제한 bookmark가 active였다면 bookmarks.current도 함께 제거되어야 함");
        assertFalse(new BookmarkCommand(repo).call().containsKey("to-delete"));
    }

    @Test
    public void testExplicitDeactivateWithoutName(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // active bookmark가 전혀 없는 상태에서 이름 없이 비활성화를 호출해도 예외 없이 무시돼야 함
        new BookmarkCommand(repo).setActive(true).call();
        assertNull(new BookmarkCommand(repo).getActiveBookmark());

        File file = new File(repoDir, "a.txt");
        Files.writeString(file.toPath(), "content");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("c1").call();
        new BookmarkCommand(repo).setBookmarkName("b1").call();
        new BookmarkCommand(repo).setBookmarkName("b1").setActive(true).call();
        assertEquals("b1", new BookmarkCommand(repo).getActiveBookmark());

        // 이름 없이 setActive(true)를 호출하면 명시적으로 현재 active bookmark를 해제한다
        Map<String, String> result = new BookmarkCommand(repo).setActive(true).call();
        assertNull(new BookmarkCommand(repo).getActiveBookmark());
        assertTrue(result.containsKey("b1"), "비활성화는 bookmark 자체를 지우지 않고 active 표시만 지워야 함");
    }

    @Test
    public void testGetActiveBookmarkIgnoresUnreadableCurrentFile(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File curBkFile = new File(repo.getHgDir(), "bookmarks.current");
        assertTrue(curBkFile.mkdirs(), "테스트 준비: bookmarks.current 자리에 디렉터리를 만들어 읽기 실패를 유발");

        assertNull(new BookmarkCommand(repo).getActiveBookmark(),
                "bookmarks.current를 읽는 도중 IOException이 나면 조용히 null을 반환해야 함");
    }

    @Test
    public void testMergeFromRemoteNoOpOnNullOrEmpty(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        BookmarkCommand.mergeFromRemote(repo, null, null);
        BookmarkCommand.mergeFromRemote(repo, Collections.emptyMap(), null);

        assertTrue(new BookmarkCommand(repo).call().isEmpty());
    }

    @Test
    public void testMergeFromRemoteSkipsWhenValuesAlreadyEqual(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        String sameHash = "4444444444444444444444444444444444444444";
        new BookmarkCommand(repo).setBookmarkName("same").setRevision(sameHash).call();

        Map<String, String> remote = new HashMap<>();
        remote.put("same", sameHash);
        BookmarkCommand.mergeFromRemote(repo, remote, null);

        assertEquals(sameHash, new BookmarkCommand(repo).call().get("same"));
    }

    @Test
    public void testMergeFromRemoteAdoptsRemoteWhenLocalRevisionMissing(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File file = new File(repoDir, "a.txt");
        Files.writeString(file.toPath(), "content");
        new AddCommand(repo).call();
        byte[] commitNode = new CommitCommand(repo).setMessage("c1").call();
        String hex = toHex(commitNode).substring(0, 40);

        // 로컬 bookmark가 로컬 저장소에 없는(strip된 것과 같은) 리비전을 가리키는 상태를 흉내낸다
        String goneHash = "5555555555555555555555555555555555555555";
        new BookmarkCommand(repo).setBookmarkName("stale").setRevision(goneHash).call();

        Map<String, String> remote = new HashMap<>();
        remote.put("stale", hex);
        BookmarkCommand.mergeFromRemote(repo, remote, null);

        assertEquals(hex, new BookmarkCommand(repo).call().get("stale"),
                "로컬 bookmark가 존재하지 않는 리비전을 가리키면 원격 값을 그대로 채택해야 함");
    }

    @Test
    public void testMergeFromRemoteKeepsLocalWhenLocalIsAheadOfRemote(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);

        File file = new File(repoDir, "a.txt");
        Files.writeString(file.toPath(), "one");
        hg.add().addFile("a.txt").call();
        byte[] c1 = hg.commit().setMessage("c1").call();
        String hex1 = toHex(c1).substring(0, 40);

        Files.writeString(file.toPath(), "two");
        byte[] c2 = hg.commit().setMessage("c2").call();
        String hex2 = toHex(c2).substring(0, 40);

        new BookmarkCommand(repo).setBookmarkName("ahead").setRevision(hex2).call();

        Map<String, String> remote = new HashMap<>();
        remote.put("ahead", hex1);
        BookmarkCommand.mergeFromRemote(repo, remote, null);

        Map<String, String> result = new BookmarkCommand(repo).call();
        assertEquals(hex2, result.get("ahead"), "로컬이 원격보다 앞서 있으면 그대로 유지해야 함");
        assertFalse(result.containsKey("ahead@1"), "로컬이 앞서 있을 뿐 진짜 divergence가 아니므로 분기 bookmark를 만들면 안 됨");
    }

    @Test
    public void testMergeFromRemoteCreatesDivergentBookmarkOnTrueDivergence(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);

        File file = new File(repoDir, "a.txt");
        Files.writeString(file.toPath(), "base");
        hg.add().addFile("a.txt").call();
        byte[] base = hg.commit().setMessage("base").call();
        String baseHex = toHex(base).substring(0, 40);

        Files.writeString(file.toPath(), "head-a");
        byte[] headA = hg.commit().setMessage("head-a").call();
        String headAHex = toHex(headA).substring(0, 40);

        hg.update().setRevision(baseHex).call();
        Files.writeString(file.toPath(), "head-b");
        byte[] headB = hg.commit().setMessage("head-b").call();
        String headBHex = toHex(headB).substring(0, 40);

        new BookmarkCommand(repo).setBookmarkName("shared").setRevision(headBHex).call();

        Map<String, String> remote = new HashMap<>();
        remote.put("shared", headAHex);
        BookmarkCommand.mergeFromRemote(repo, remote, "myremote");

        Map<String, String> result = new BookmarkCommand(repo).call();
        assertEquals(headBHex, result.get("shared"), "진짜 divergence에서는 로컬 값이 보존돼야 함");
        assertEquals(headAHex, result.get("shared@myremote"),
                "remotePathName이 주어지면 그 이름을 접미사로 분기 bookmark가 생성돼야 함");
    }

    @Test
    public void testMergeFromRemoteDivergentSuffixDefaultsToOne(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);

        File file = new File(repoDir, "a.txt");
        Files.writeString(file.toPath(), "base");
        hg.add().addFile("a.txt").call();
        byte[] base = hg.commit().setMessage("base").call();
        String baseHex = toHex(base).substring(0, 40);

        Files.writeString(file.toPath(), "head-a");
        byte[] headA = hg.commit().setMessage("head-a").call();
        String headAHex = toHex(headA).substring(0, 40);

        hg.update().setRevision(baseHex).call();
        Files.writeString(file.toPath(), "head-b");
        byte[] headB = hg.commit().setMessage("head-b").call();
        String headBHex = toHex(headB).substring(0, 40);

        new BookmarkCommand(repo).setBookmarkName("shared").setRevision(headBHex).call();

        Map<String, String> remote = new HashMap<>();
        remote.put("shared", headAHex);
        BookmarkCommand.mergeFromRemote(repo, remote, null);

        Map<String, String> result = new BookmarkCommand(repo).call();
        assertEquals(headAHex, result.get("shared@1"), "remotePathName이 null이면 접미사는 기본값 \"1\"이어야 함");
    }

    @Test
    public void testMergeFromRemoteDivergentSuffixWithEmptyRemotePathNameFallsBackToOne(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);

        File file = new File(repoDir, "a.txt");
        Files.writeString(file.toPath(), "base");
        hg.add().addFile("a.txt").call();
        byte[] base = hg.commit().setMessage("base").call();
        String baseHex = toHex(base).substring(0, 40);

        Files.writeString(file.toPath(), "head-a");
        byte[] headA = hg.commit().setMessage("head-a").call();
        String headAHex = toHex(headA).substring(0, 40);

        hg.update().setRevision(baseHex).call();
        Files.writeString(file.toPath(), "head-b");
        byte[] headB = hg.commit().setMessage("head-b").call();
        String headBHex = toHex(headB).substring(0, 40);

        new BookmarkCommand(repo).setBookmarkName("shared").setRevision(headBHex).call();

        Map<String, String> remote = new HashMap<>();
        remote.put("shared", headAHex);
        BookmarkCommand.mergeFromRemote(repo, remote, "");

        Map<String, String> result = new BookmarkCommand(repo).call();
        assertEquals(headAHex, result.get("shared@1"), "remotePathName이 빈 문자열이어도 접미사는 기본값 \"1\"이어야 함");
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
            Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
