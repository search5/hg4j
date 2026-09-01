package com.github.search5.hg4j.api;

import com.github.search5.hg4j.HgTestUtils;
import com.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import com.github.search5.hg4j.lib.NodeId;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * Bookmark(Track B-3) 실제 hg CLI 상호운용 검증. {@link HgTestUtils#hg}로 실제 native
 * Mercurial과 대조한다.
 */
@Tag("interop")
public class BookmarkRealHgInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    @Test
    public void testCommitAdvancesActiveBookmark(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);

        Files.writeString(new File(repoDir, "a.txt").toPath(), "one");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("T").setMessage("c1").call();

        new BookmarkCommand(repo).setBookmarkName("feature").call();
        new BookmarkCommand(repo).setBookmarkName("feature").setActive(true).call();

        Files.writeString(new File(repoDir, "a.txt").toPath(), "two");
        byte[] c2 = new CommitCommand(repo).setAuthor("T").setMessage("c2").call();

        String c2Hex = new NodeId(c2).toHex();
        assertEquals(c2Hex, new BookmarkCommand(repo).call().get("feature"),
                "활성 bookmark는 커밋 시 자동으로 새 리비전을 가리켜야 함");

        // 실제 hg가 동일하게 인식하는지 대조
        String nativeBookmarks = HgTestUtils.hg(repoDir, "bookmarks");
        assertTrue(nativeBookmarks.contains("feature") && nativeBookmarks.contains(c2Hex.substring(0, 12)),
                "실제 hg bookmarks 출력: " + nativeBookmarks);
        assertTrue(nativeBookmarks.contains("*"), "활성 bookmark는 '*'로 표시되어야 함: " + nativeBookmarks);
    }

    @Test
    public void testUpdateActivatesAndDeactivatesBookmark(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);

        Files.writeString(new File(repoDir, "a.txt").toPath(), "one");
        new AddCommand(repo).call();
        byte[] c1 = new CommitCommand(repo).setAuthor("T").setMessage("c1").call();
        new BookmarkCommand(repo).setBookmarkName("stable").setRevision(new NodeId(c1).toHex()).call();

        Files.writeString(new File(repoDir, "a.txt").toPath(), "two");
        new CommitCommand(repo).setAuthor("T").setMessage("c2").call();
        assertNull(new BookmarkCommand(repo).getActiveBookmark(), "c2 커밋 시점에는 활성 bookmark가 없어야 함");

        new UpdateCommand(repo).setRevision(new NodeId(c1).toHex()).call();
        assertEquals("stable", new BookmarkCommand(repo).getActiveBookmark(),
                "stable이 가리키는 리비전으로 update하면 자동 활성화되어야 함");

        String nativeAfterActivate = HgTestUtils.hg(repoDir, "bookmarks");
        assertTrue(nativeAfterActivate.contains("* stable"), "실제 hg도 stable을 활성으로 봐야 함: " + nativeAfterActivate);
    }

    @Test
    public void testPushSyncsBookmarkToRemote(@TempDir Path tempDir) throws Exception {
        File remoteDir = tempDir.resolve("remote").toFile();
        File localDir = tempDir.resolve("local").toFile();

        HgTestUtils.nativeRepo(remoteDir, dir -> {});
        Files.writeString(new File(remoteDir, ".hg/hgrc").toPath(),
                "[web]\nallow_push = *\npush_ssl = false\n", StandardOpenOption.APPEND);

        new InitCommand().setDirectory(localDir).call();
        HgRepository local = new HgRepository(localDir);
        Files.writeString(new File(localDir, "a.txt").toPath(), "content");
        new AddCommand(local).call();
        byte[] node = new CommitCommand(local).setAuthor("T").setMessage("c1").call();
        new BookmarkCommand(local).setBookmarkName("mybook").setRevision(new NodeId(node).toHex()).call();

        new PushCommand(local).setDestination(remoteDir.getAbsolutePath()).call();

        String remoteBookmarks = HgTestUtils.hg(remoteDir, "bookmarks");
        assertTrue(remoteBookmarks.contains("mybook"), "push 후 실제 hg 원격에 bookmark가 반영돼야 함: " + remoteBookmarks);
    }

    @Test
    public void testPullFastForwardDoesNotCreateDivergentBookmark(@TempDir Path tempDir) throws Exception {
        File remoteDir = tempDir.resolve("remote").toFile();

        // 1. 실제 hg로 원격 저장소 생성, bookmark를 rev0에 배치
        HgTestUtils.nativeRepo(remoteDir, dir -> {
            try {
                Files.writeString(new File(dir, "a.txt").toPath(), "one");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        HgTestUtils.hg(remoteDir, "add");
        HgTestUtils.hg(remoteDir, "commit", "-u", "T", "-m", "c1");
        HgTestUtils.hg(remoteDir, "bookmark", "shared");

        // 2. hg4j로 clone (pull) — bookmark도 함께 들어와야 함
        File localDir = tempDir.resolve("local").toFile();
        new InitCommand().setDirectory(localDir).call();
        HgRepository local = new HgRepository(localDir);
        new PullCommand(local).setSource(remoteDir.getAbsolutePath()).call();

        assertEquals(1, new BookmarkCommand(local).call().size());
        assertTrue(new BookmarkCommand(local).call().containsKey("shared"));

        // 3. 원격에서 실제 hg로 커밋을 추가해 bookmark를 전진(fast-forward)
        Files.writeString(new File(remoteDir, "a.txt").toPath(), "two");
        HgTestUtils.hg(remoteDir, "commit", "-u", "T", "-m", "c2");
        HgTestUtils.hg(remoteDir, "bookmark", "-f", "shared");
        String remoteHex = HgTestUtils.hg(remoteDir, "log", "-r", "shared", "--template", "{node}");

        // 4. hg4j로 다시 pull — fast-forward이므로 divergent bookmark(shared@...)가 생기면 안 되고
        //    local의 "shared"가 그냥 remoteHex로 전진해야 한다.
        new PullCommand(local).setSource(remoteDir.getAbsolutePath()).call();

        Map<String, String> localBks = new BookmarkCommand(local).call();
        assertEquals(remoteHex, localBks.get("shared"),
                "fast-forward pull이면 로컬 bookmark가 그냥 전진해야 함: " + localBks);
        assertEquals(1, localBks.size(),
                "fast-forward pull에서는 divergent bookmark(shared@...)가 생기면 안 됨: " + localBks);
    }

    /**
     * 진짜 divergence(로컬과 원격이 같은 bookmark 이름을 서로 조상 관계가 아닌 리비전으로
     * 각각 이동시킨 경우) 시나리오. 로컬 값은 보존되고 divergent 사본이 별도로 생겨야 하며,
     * 2026-09-01 이전 버그처럼 로컬 값이 조용히 원격 값으로 덮어써지면 안 된다.
     *
     * <p>changeset 자체는 pull 이전에 이미 양쪽 다 갖고 있는 상태로 맞춰서(두 갈래 모두
     * 커밋 후 한 번 pull) 두 번째 pull에서는 새로 받아올 changegroup이 없게 만들었다 —
     * 그래야 이 테스트가 오로지 bookmark 병합 로직만 검증하고, 별도로 발견된 cg1
     * 다중 head 델타베이스 버그(Track C, [[mercurial-spec-compliance-requirement]]의
     * Changegroup 항목)에 걸리지 않는다.</p>
     */
    @Test
    public void testPullTrueDivergenceKeepsLocalAndCreatesDivergentCopy(@TempDir Path tempDir) throws Exception {
        File remoteDir = tempDir.resolve("remote").toFile();
        HgTestUtils.nativeRepo(remoteDir, dir -> {
            try {
                Files.writeString(new File(dir, "a.txt").toPath(), "base");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        HgTestUtils.hg(remoteDir, "add");
        HgTestUtils.hg(remoteDir, "commit", "-u", "T", "-m", "base");
        String baseHex = HgTestUtils.hg(remoteDir, "log", "-r", "0", "--template", "{node}");

        // base에서 서로 조상 관계가 아닌 두 개의 head를 만든다 (head-A, head-B).
        Files.writeString(new File(remoteDir, "a.txt").toPath(), "head-a");
        HgTestUtils.hg(remoteDir, "commit", "-u", "T", "-m", "head-a");
        String headAHex = HgTestUtils.hg(remoteDir, "log", "-r", "tip", "--template", "{node}");

        HgTestUtils.hg(remoteDir, "update", "-r", baseHex);
        Files.writeString(new File(remoteDir, "a.txt").toPath(), "head-b");
        HgTestUtils.hg(remoteDir, "commit", "-u", "T", "-m", "head-b");
        String headBHex = HgTestUtils.hg(remoteDir, "log", "-r", "tip", "--template", "{node}");
        String remoteHex = headAHex;

        // 로컬은 pull로 base+head-a+head-b를 전부 받아온다 — 두 번째 pull에서는 새 changeset이 없다.
        File localDir = tempDir.resolve("local").toFile();
        new InitCommand().setDirectory(localDir).call();
        HgRepository local = new HgRepository(localDir);
        new PullCommand(local).setSource(remoteDir.getAbsolutePath()).call();

        // 원격에서 bookmark를 head-a로 지정.
        HgTestUtils.hg(remoteDir, "bookmark", "-r", headAHex, "shared");

        // 로컬에서는 독자적으로 같은 이름 bookmark를 head-b로 지정 — head-a/head-b는
        // 서로 조상 관계가 아니므로 진짜 divergence.
        new BookmarkCommand(local).setBookmarkName("shared").setRevision(headBHex).call();

        // pull: 새로 받아올 changeset은 없고(이미 다 있음), bookmark만 동기화된다.
        new PullCommand(local).setSource(remoteDir.getAbsolutePath()).call();

        Map<String, String> localBks = new BookmarkCommand(local).call();
        assertEquals(headBHex, localBks.get("shared"),
                "진짜 divergence에서는 로컬 bookmark 값이 보존돼야 함(조용히 덮어쓰기 금지): " + localBks);
        boolean hasDivergentCopy = localBks.entrySet().stream()
                .anyMatch(e -> e.getKey().startsWith("shared@") && e.getValue().equals(remoteHex));
        assertTrue(hasDivergentCopy, "원격 값을 담은 divergent 사본(shared@...)이 생겨야 함: " + localBks);
    }
}
