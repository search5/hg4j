package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.errors.HgValidationException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import io.github.search5.hg4j.util.NodeIdUtil;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 백로그 23번 merge 카테고리: fast-forward/진짜 3-way merge(충돌 없음)/충돌-resolve/서로 다른
 * 브랜치 merge는 이미 {@code CHgMergeInteropTest}/{@code MergeStateInteropTest}에서 실제 hg CLI와
 * 왕복 검증됐다. 이 파일은 남은 두 시나리오를 검증한다: (1) 한쪽에서 rename/copy된 파일이 merge를
 * 거쳐도 copy 추적이 살아남는지(실제 hg {@code hg log --follow}로 확인), (2) merge 중단
 * ({@code hg merge --abort} 대응 명령)이 실제 hg의 그것과 동일하게 동작하는지.
 */
@Tag("interop")
public class MergeRealHgInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    /**
     * 실제 hg로 한쪽 브랜치(dev)에서 a.txt를 b.txt로 rename하고, 다른 브랜치(default)에서는
     * 관계없는 파일만 수정한 뒤, hg4j {@link MergeCommand}+{@link CommitCommand}로 병합 커밋을
     * 만든다. 실제 hg {@code hg log --follow b.txt}가 rename 이전 이력(a.txt 원본 커밋)까지
     * 정상적으로 따라가는지 확인한다 -- 이는 merge 커밋의 manifest가 b.txt에 대해 rename 커밋 때
     * 생성된 filelog 리비전 노드를 그대로 재사용해야만(새 리비전을 만들지 않아야만) 성립한다.
     */
    @Test
    public void copyTrackingSurvivesMergeAndIsFollowableByRealHg(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = HgTestUtils.nativeRepo(repoDir, dir -> {
            try {
                Files.writeString(new File(dir, "a.txt").toPath(), "base\n");
                Files.writeString(new File(dir, "extra.txt").toPath(), "x\n");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        HgTestUtils.hg(repoDir, "add", "a.txt", "extra.txt");
        HgTestUtils.hg(repoDir, "commit", "-m", "c0");

        HgTestUtils.hg(repoDir, "branch", "dev");
        HgTestUtils.hg(repoDir, "rename", "a.txt", "b.txt");
        HgTestUtils.hg(repoDir, "commit", "-m", "c2 rename a to b on dev");
        String devTipNode = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "update", "default");
        Files.writeString(new File(repoDir, "extra.txt").toPath(), "x-modified\n");
        HgTestUtils.hg(repoDir, "commit", "-m", "c1 modify extra on default");
        String defaultTipNode = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        // hg4j로 병합: dev(devTipNode) <- default(defaultTipNode)
        HgTestUtils.hg(repoDir, "update", "dev");
        MergeCommand.MergeResult result = new MergeCommand(repo).setNodeId(NodeIdUtil.fromHex(defaultTipNode)).call();
        assertFalse(result.isConflicted(), "unrelated-file-only merge must not conflict");

        byte[] mergeNode = new CommitCommand(repo)
                .setAuthor("T <t@example.com>")
                .setMessage("merge default into dev using hg4j")
                .call();
        String mergeHex = NodeIdUtil.toHex(mergeNode);

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.contains("integrity error"), "Repository integrity error!\n" + verify);

        // 실제 hg가 merge 커밋에서 b.txt의 rename 이력을 계속 따라갈 수 있어야 한다.
        String follow = HgTestUtils.hg(repoDir, "log", "--follow", "b.txt", "-r", mergeHex,
                "--template", "{rev}:{desc}\n");
        assertTrue(follow.contains("c2 rename a to b on dev"),
                "실제 hg log --follow가 rename 커밋까지 따라가야 함:\n" + follow);
        assertTrue(follow.contains("c0"),
                "실제 hg log --follow가 rename 원본(a.txt) 커밋까지 따라가야 함:\n" + follow);

        // 병합 커밋 자체의 b.txt 파일 내용도 원본 그대로 유지돼야 한다.
        String catB = HgTestUtils.hg(repoDir, "cat", "-r", mergeHex, "b.txt");
        assertEquals("base", catB.trim());
    }

    /**
     * hg4j {@link MergeCommand#abort()}가 실제 hg {@code hg merge --abort}와 동일하게 동작하는지:
     * 충돌 없는 병합을 시작한 뒤(다른 부모에서만 추가된 파일이 작업 디렉터리에 나타난 상태) abort하면
     * 그 파일이 사라지고, 수정된 파일은 p1 내용으로 되돌아가고, dirstate가 단일 부모로 복귀해야 한다.
     * 이 상태를 hg4j가 만든 뒤 실제 hg {@code hg status}/{@code hg parents}/{@code hg resolve --list}로
     * 대조 검증한다.
     */
    @Test
    public void mergeAbortMatchesRealHgMergeAbort(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = HgTestUtils.nativeRepo(repoDir, dir -> {
            try {
                Files.writeString(new File(dir, "f.txt").toPath(), "base\n");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        HgTestUtils.hg(repoDir, "add", "f.txt");
        HgTestUtils.hg(repoDir, "commit", "-m", "c0");

        HgTestUtils.hg(repoDir, "branch", "dev");
        Files.writeString(new File(repoDir, "new.txt").toPath(), "newfile\n");
        HgTestUtils.hg(repoDir, "add", "new.txt");
        HgTestUtils.hg(repoDir, "commit", "-m", "c1 adds new.txt on dev");
        String devTipNode = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "update", "default");
        Files.writeString(new File(repoDir, "f.txt").toPath(), "onA\n");
        HgTestUtils.hg(repoDir, "commit", "-m", "c1B modifies f on default");
        String p1Node = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        MergeCommand mergeCmd = new MergeCommand(repo).setNodeId(NodeIdUtil.fromHex(devTipNode));
        MergeCommand.MergeResult result = mergeCmd.call();
        assertFalse(result.isConflicted());

        // Sanity: merge actually brought new.txt in and dirstate now has two parents.
        assertTrue(new File(repoDir, "new.txt").exists());
        Dirstate midMergeDs = repo.getDirstate();
        assertFalse(midMergeDs.getParent2Node().isNull());

        mergeCmd.abort();

        // 실제 hg 기준: abort 후 new.txt는 사라지고, f.txt는 p1(onA) 내용, 단일 부모로 복귀.
        assertFalse(new File(repoDir, "new.txt").exists(), "merge 중 다른 부모에서만 추가된 파일은 abort 후 사라져야 함");
        assertEquals("onA\n", Files.readString(new File(repoDir, "f.txt").toPath()));

        Dirstate postAbortDs = repo.getDirstate();
        assertTrue(postAbortDs.getParent2Node().isNull(), "abort 후 parent2가 null이어야 함");
        assertEquals(p1Node, postAbortDs.getParent1Node().toHex());

        // 실제 hg CLI로 대조: parents가 단일(p1)이어야 하고, resolve --list가 비어 있어야 하며,
        // status에 추적 파일 변경이 남아 있으면 안 된다(hg4j가 쓴 dirstate를 real hg가 그대로 읽음).
        String realParents = HgTestUtils.hg(repoDir, "parents", "--template", "{node} ");
        assertEquals(p1Node, realParents.trim());

        String realResolveList = HgTestUtils.hg(repoDir, "resolve", "--list");
        assertEquals("", realResolveList.trim());

        String realStatus = HgTestUtils.hg(repoDir, "status");
        assertEquals("", realStatus.trim(), "abort 후 실제 hg status는 추적 파일 변경이 없어야 함:\n" + realStatus);
    }

    /**
     * 병합 중이 아닐 때 abort()를 호출하면 실제 hg의 "abort: no merge in progress"와 같은 취지로
     * 거부돼야 한다.
     */
    @Test
    public void abortWithoutMergeInProgressThrows(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = HgTestUtils.nativeRepo(repoDir, dir -> {
            try {
                Files.writeString(new File(dir, "f.txt").toPath(), "base\n");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        HgTestUtils.hg(repoDir, "add", "f.txt");
        HgTestUtils.hg(repoDir, "commit", "-m", "c0");

        assertThrows(HgValidationException.class, () -> new MergeCommand(repo).abort());
    }
}
