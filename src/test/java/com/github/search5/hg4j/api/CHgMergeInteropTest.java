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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import com.github.search5.hg4j.util.NodeIdUtil;

@Tag("interop")
public class CHgMergeInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping CHgMergeInteropTest.");
    }

    @Test
    public void testNativeCommitAndHg4jMerge(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();

        // 두 브랜치가 독립적 커밋을 가진 시나리오:
        // commit 0 (default): f1.txt 추가 — 공통 조상
        // commit 1 (default): f3.txt 추가 — default만의 변경
        // commit 2 (dev):     f2.txt 추가 — dev만의 변경 (commit 0에서 분기)
        // → hg4j merge: dev(commit 2) ← default(commit 1) → 진정한 3-way merge
        HgRepository repository = HgTestUtils.nativeRepo(repoDir, dir -> {
            try {
                // Commit 0: 공통 베이스 (default branch)
                File f1 = new File(dir, "f1.txt");
                Files.writeString(f1.toPath(), "Base content in default branch\n");
                HgTestUtils.hg(dir, "add", "f1.txt");
                HgTestUtils.hg(dir, "commit", "-m", "Initial default commit");

                // Commit 1: default branch 에 추가 커밋
                File f3 = new File(dir, "f3.txt");
                Files.writeString(f3.toPath(), "Only in default branch\n");
                HgTestUtils.hg(dir, "add", "f3.txt");
                HgTestUtils.hg(dir, "commit", "-m", "Default branch extra commit");

                // commit 0으로 되돌아가서 dev branch 시작
                HgTestUtils.hg(dir, "update", "-r", "0");
                HgTestUtils.hg(dir, "branch", "dev");

                // Commit 2 (dev branch): 독립적인 파일 추가
                File f2 = new File(dir, "f2.txt");
                Files.writeString(f2.toPath(), "Content in dev branch\n");
                HgTestUtils.hg(dir, "add", "f2.txt");
                HgTestUtils.hg(dir, "commit", "-m", "Commit in dev branch");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // default 브랜치 tip (commit 1)의 node ID 확인
        String defaultTipNode = HgTestUtils.hg(repoDir, "log", "-r", "default", "--template", "{node}");

        // hg4j로 merge: dev(commit 2) ← default tip(commit 1)
        // commit 1의 rev 번호는 1
        new MergeCommand(repository).setRevision(1).call();

        // merge commit 작성
        byte[] mergeCommitNode = new CommitCommand(repository)
                .setAuthor("Antigravity <antigravity@google.com>")
                .setMessage("Merge default branch into dev branch using hg4j")
                .call();

        // native hg로 merge commit의 parents 검증
        String mergeNodeHex = NodeIdUtil.toHex(mergeCommitNode);
        String nativeParents = HgTestUtils.hg(repoDir, "log", "-r", mergeNodeHex, "--template", "{p1node}:{p2node}");
        String[] parents = nativeParents.split(":");

        assertEquals(2, parents.length, "Merge commit must have exactly two parents");
        assertNotEquals("0".repeat(40), parents[1], "Parent 2 must not be null node — must be a real commit");
        assertEquals(defaultTipNode, parents[1], "Parent 2 must match default branch tip node ID");

        // 저장소 무결성 검증을 먼저 실행하여 에러 원인 추적
        String nativeVerify = HgTestUtils.hg(repoDir, "verify");
        System.out.println("=== NATIVE VERIFY ===");
        System.out.println(nativeVerify);
        System.out.println("=====================");
        assertFalse(nativeVerify.contains("integrity error"), "Repository integrity error!\n" + nativeVerify);

        // 두 브랜치의 파일이 모두 포함됐는지 확인
        String catF3 = HgTestUtils.hg(repoDir, "cat", "-r", mergeNodeHex, "f3.txt");
        assertEquals("Only in default branch", catF3.trim());
    }
}
