package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 백로그 23 (shelve 카테고리): shelve -> 다른 작업 -> unshelve 왕복이 실제 hg가 만든
 * shelve와 상호운용되는지 검증한다 (hg4j가 만든 shelve를 real hg가 unshelve할 수 있는지,
 * 그리고 그 반대도) -- 기존 {@code ShelveCommandTest}/{@code ShelveCommandCoverageTest}는
 * hg4j 자체 왕복만 검증했으므로 이 항목 기준으로는 "미검증"이었다.
 *
 * <p>real hg의 {@code hg unshelve}는 hg4j처럼 diff를 그대로 재생하는 방식이 아니라 임시
 * 커밋 + rebase + merge + strip으로 이루어진 완전히 다른(그리고 hg4j가 흉내내지 않는)
 * 알고리즘이라, 여기서 검증하는 것은 딱 "충돌도, 그 사이 다른 커밋도 없는 가장 단순한
 * 왕복"이다 -- 더 복잡한 시나리오(충돌 재해결, 그 사이 커밋된 히스토리 위로의 rebase)는
 * 커버하지 않는다(상세는 백로그 문서/최종 보고 참고).</p>
 */
@Tag("interop")
public class ShelveRealHgInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    private static void configureRepoForRealHg(File repoDir) throws Exception {
        File hgrc = new File(repoDir, ".hg/hgrc");
        Files.writeString(hgrc.toPath(),
                "[format]\nusezstd = false\nrevlog-compression = zlib\n[ui]\nusername = Test User <test@example.com>\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * hg4j가 만든 shelve를 real hg {@code hg unshelve}가 그대로 복원할 수 있는지: 파일 수정+
     * 신규 파일 추가를 hg4j로 shelve한 뒤, real hg CLI로 unshelve해서 워킹 카피 내용이
     * 정확히 복원되는지 확인한다.
     */
    @Test
    public void hg4jShelveCanBeUnshelvedByRealHg(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);
        configureRepoForRealHg(repoDir);

        File a = new File(repoDir, "a.txt");
        Files.writeString(a.toPath(), "line1\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("T").setMessage("c1").call();

        // Dirty the working copy: modify an existing file, add a new one.
        Files.writeString(a.toPath(), "line1\nline2\n");
        File b = new File(repoDir, "b.txt");
        Files.writeString(b.toPath(), "brand new file\n");
        new AddCommand(repo).call();

        new ShelveCommand(repo).setName("default").call();

        // Working copy must be back to the clean c1 state after shelving.
        assertEquals("line1\n", Files.readString(a.toPath()));
        assertFalse(b.exists());

        // "shelve -> other work -> unshelve": leave an unrelated untracked file lying around in
        // between, matching the task's round-trip scenario without moving the working parent
        // (which would additionally require real hg's rebase-based unshelve path -- out of scope
        // here, see the class javadoc).
        Files.writeString(new File(repoDir, "unrelated.txt").toPath(), "other work\n");

        String unshelveOut = HgTestUtils.hg(repoDir, "unshelve");

        assertEquals("line1\nline2\n", Files.readString(a.toPath()),
                "Real hg unshelve must restore a.txt's shelved modification. Output was:\n" + unshelveOut);
        assertTrue(b.exists(), "Real hg unshelve must restore the newly-added b.txt. Output was:\n" + unshelveOut);
        assertEquals("brand new file\n", Files.readString(b.toPath()));

        String status = HgTestUtils.hg(repoDir, "status");
        assertTrue(status.contains("M a.txt"), "a.txt must show as modified after unshelve, got:\n" + status);
        assertTrue(status.contains("A b.txt"), "b.txt must show as added after unshelve, got:\n" + status);

        String verifyOut = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verifyOut.contains("integrity error"), "Repo must remain verify-clean: " + verifyOut);
    }

    /**
     * 그 반대 방향: real hg {@code hg shelve}가 만든 shelve를 hg4j의 {@code
     * ShelveCommand.setUnshelve(true)}가 복원할 수 있는지.
     */
    @Test
    public void realHgShelveCanBeUnshelvedByHg4j(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = HgTestUtils.nativeRepo(repoDir, dir -> {
            try {
                Files.writeString(new File(dir, "a.txt").toPath(), "line1\n");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        HgTestUtils.hg(repoDir, "add");
        HgTestUtils.hg(repoDir, "commit", "-u", "T", "-m", "c1");

        File a = new File(repoDir, "a.txt");
        Files.writeString(a.toPath(), "line1\nline2\n");
        File b = new File(repoDir, "b.txt");
        Files.writeString(b.toPath(), "brand new file\n");
        HgTestUtils.hg(repoDir, "add", "b.txt");

        HgTestUtils.hg(repoDir, "shelve", "-n", "default");

        // Working copy must be back to the clean c1 state after real hg's own shelve.
        assertEquals("line1\n", Files.readString(a.toPath()));
        assertFalse(b.exists());

        // "shelve -> other work -> unshelve" (see the other direction's test for why this stays
        // an untracked, unrelated file rather than moving the working parent).
        Files.writeString(new File(repoDir, "unrelated.txt").toPath(), "other work\n");

        new ShelveCommand(repo).setName("default").setUnshelve(true).call();

        assertEquals("line1\nline2\n", Files.readString(a.toPath()),
                "hg4j unshelve must restore a.txt's real-hg-shelved modification");
        assertTrue(b.exists(), "hg4j unshelve must restore the newly-added b.txt");
        assertEquals("brand new file\n", Files.readString(b.toPath()));

        String status = HgTestUtils.hg(repoDir, "status");
        assertTrue(status.contains("M a.txt"), "a.txt must show as modified after hg4j unshelve, got:\n" + status);
        assertTrue(status.contains("A b.txt"), "b.txt must show as added after hg4j unshelve, got:\n" + status);
    }

    /**
     * A shelved file REMOVAL (as opposed to add/modify) round trip, hg4j-made shelve unshelved by
     * real hg -- removals take a different code path in both {@code performShelve()} (no filelog
     * entry at all, represented purely via the shelved commit's manifest omitting the path) and
     * the manifest-diff-based no-{@code .state} reconstruction real hg's own shelves need.
     */
    @Test
    public void hg4jShelveOfARemovedFileCanBeUnshelvedByRealHg(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);
        configureRepoForRealHg(repoDir);

        File a = new File(repoDir, "a.txt");
        Files.writeString(a.toPath(), "line1\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setAuthor("T").setMessage("c1").call();

        new RemoveCommand(repo).setFile("a.txt").call();
        Files.deleteIfExists(a.toPath());

        new ShelveCommand(repo).setName("default").call();

        // Working copy must be back to the clean c1 state (a.txt restored) after shelving.
        assertTrue(a.exists(), "a.txt must be restored to disk once its removal is shelved away");
        assertEquals("line1\n", Files.readString(a.toPath()));

        HgTestUtils.hg(repoDir, "unshelve");

        assertFalse(a.exists(), "Real hg unshelve must re-apply the shelved removal of a.txt");
        String status = HgTestUtils.hg(repoDir, "status");
        assertTrue(status.contains("R a.txt"), "a.txt must show as removed after unshelve, got:\n" + status);
    }
}
