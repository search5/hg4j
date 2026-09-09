package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P3-33 실사용 검증(설계 문서 열린 질문 4번 결론): {@link MergeCommitCommand}가 hg4j 자체
 * 라운드트립만으로 검증되지 않도록, 실제 {@code hg} CLI로 만든 두 갈래를 이 클래스로 병합한 뒤
 * 그 결과 changeset을 다시 실제 {@code hg} CLI로 {@code verify}/{@code log}/{@code cat}해서
 * 부모/내용/manifest가 real hg의 기대와 실제로 일치하는지 확인한다. {@code MergeRealHgInteropTest}
 * (기존 워킹카피 기반 {@link MergeCommand}+{@link CommitCommand} 경로)의 자매 스위트 -- 같은
 * 검증 항목(정상 병합/copy 추적/manifest 무결성)을 in-core 경로로 반복 검증한다.
 */
@Tag("interop")
public class MergeCommitCommandRealHgInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    @Test
    public void realHgBuiltBranchesMergeCleanlyAndRealHgAcceptsTheResult(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = HgTestUtils.nativeRepo(repoDir, dir -> {
            try {
                Files.writeString(new File(dir, "a.txt").toPath(), "line1\nline2\nline3\n");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        HgTestUtils.hg(repoDir, "add", "a.txt");
        HgTestUtils.hg(repoDir, "commit", "-m", "c0 base");

        // "ours" bookmark: edits the top of the file.
        HgTestUtils.hg(repoDir, "bookmark", "ours");
        Files.writeString(new File(repoDir, "a.txt").toPath(), "line1 OURS\nline2\nline3\n");
        HgTestUtils.hg(repoDir, "commit", "-m", "c1 ours edits top");
        String oursHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        // "theirs" bookmark, forked from c0: edits the bottom of the file + adds a new file.
        HgTestUtils.hg(repoDir, "update", "-r", "0");
        HgTestUtils.hg(repoDir, "bookmark", "theirs");
        Files.writeString(new File(repoDir, "a.txt").toPath(), "line1\nline2\nline3 THEIRS\n");
        Files.writeString(new File(repoDir, "b.txt").toPath(), "brand new from theirs\n");
        HgTestUtils.hg(repoDir, "add", "b.txt");
        HgTestUtils.hg(repoDir, "commit", "-m", "c2 theirs edits bottom and adds b.txt");
        String theirsHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        TreeMergeCommand.TreeMergeResult mergeResult = new TreeMergeCommand(repo)
                .setOurs(NodeIdUtil.fromHex(oursHex))
                .setTheirs(NodeIdUtil.fromHex(theirsHex))
                .call();
        assertFalse(mergeResult.isConflicted(), "non-overlapping edits must not conflict");

        byte[] mergeNode = new MergeCommitCommand(repo)
                .setParents(NodeIdUtil.fromHex(oursHex), NodeIdUtil.fromHex(theirsHex))
                .setAuthor("T <t@example.com>")
                .setMessage("in-core merge of ours+theirs")
                .setTreeMergeResult(mergeResult)
                .call();
        String mergeHex = NodeIdUtil.toHex(mergeNode);

        // 1) real hg verify -- the store as a whole must be structurally sound.
        String verify = HgTestUtils.hg(repoDir, "verify");
        assertTrue(verify.toLowerCase().contains("0 integrity errors")
                        || !verify.toLowerCase().contains("integrity error"),
                "real hg verify must report no integrity errors:\n" + verify);

        // 2) real hg log -- the new changeset's parents must be exactly ours/theirs.
        String parents = HgTestUtils.hg(repoDir, "log", "-r", mergeHex, "--template", "{p1node} {p2node}");
        assertTrue(parents.contains(oursHex), "parent1 must be 'ours':\n" + parents);
        assertTrue(parents.contains(theirsHex), "parent2 must be 'theirs':\n" + parents);

        // 3) real hg cat -- merged file content must reflect both non-overlapping edits.
        String catA = HgTestUtils.hg(repoDir, "cat", "-r", mergeHex, "a.txt");
        assertEquals("line1 OURS\nline2\nline3 THEIRS", catA.trim());
        String catB = HgTestUtils.hg(repoDir, "cat", "-r", mergeHex, "b.txt");
        assertEquals("brand new from theirs", catB.trim());

        // 4) real hg manifest -- both files must be listed at the merge revision.
        String manifest = HgTestUtils.hg(repoDir, "manifest", "-r", mergeHex);
        assertTrue(manifest.contains("a.txt"));
        assertTrue(manifest.contains("b.txt"));

        // 5) real hg update -- the merge commit must be checkoutable without error.
        HgTestUtils.hg(repoDir, "update", "-r", mergeHex);
        assertEquals("line1 OURS\nline2\nline3 THEIRS\n",
                Files.readString(new File(repoDir, "a.txt").toPath()));
    }

    @Test
    public void copyMetadataFromARealHgCopyIsPreservedThroughAnInCoreMergeAndFollowableByRealHg(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = HgTestUtils.nativeRepo(repoDir, dir -> {
            try {
                Files.writeString(new File(dir, "a.txt").toPath(), "content\n");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        HgTestUtils.hg(repoDir, "add", "a.txt");
        HgTestUtils.hg(repoDir, "commit", "-m", "c0 base");

        HgTestUtils.hg(repoDir, "bookmark", "ours");
        Files.writeString(new File(repoDir, "unrelated.txt").toPath(), "o\n");
        HgTestUtils.hg(repoDir, "add", "unrelated.txt");
        HgTestUtils.hg(repoDir, "commit", "-m", "c1 ours touches something else");
        String oursHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        HgTestUtils.hg(repoDir, "update", "-r", "0");
        HgTestUtils.hg(repoDir, "bookmark", "theirs");
        HgTestUtils.hg(repoDir, "copy", "a.txt", "a-copy.txt");
        HgTestUtils.hg(repoDir, "commit", "-m", "c2 theirs copies a.txt to a-copy.txt");
        String theirsHex = HgTestUtils.hg(repoDir, "log", "-r", ".", "--template", "{node}");

        TreeMergeCommand.TreeMergeResult mergeResult = new TreeMergeCommand(repo)
                .setOurs(NodeIdUtil.fromHex(oursHex))
                .setTheirs(NodeIdUtil.fromHex(theirsHex))
                .call();
        assertFalse(mergeResult.isConflicted());
        assertTrue(mergeResult.getCopiedFiles().containsKey("a-copy.txt"));

        byte[] mergeNode = new MergeCommitCommand(repo)
                .setParents(NodeIdUtil.fromHex(oursHex), NodeIdUtil.fromHex(theirsHex))
                .setAuthor("T <t@example.com>")
                .setMessage("in-core merge preserving copy metadata")
                .setTreeMergeResult(mergeResult)
                .call();
        String mergeHex = NodeIdUtil.toHex(mergeNode);

        String verify = HgTestUtils.hg(repoDir, "verify");
        assertFalse(verify.toLowerCase().contains("integrity error"), "Repository integrity error!\n" + verify);

        // real hg's own rename/copy-follow machinery must recognize the copy hg4j recorded.
        String follow = HgTestUtils.hg(repoDir, "log", "--follow", "a-copy.txt", "-r", mergeHex,
                "--template", "{rev}:{desc}\n");
        assertTrue(follow.contains("c2 theirs copies a.txt to a-copy.txt"),
                "real hg log --follow must reach the copy commit:\n" + follow);
        assertTrue(follow.contains("c0 base"),
                "real hg log --follow must reach the copy source's own origin commit:\n" + follow);

        String catCopy = HgTestUtils.hg(repoDir, "cat", "-r", mergeHex, "a-copy.txt");
        assertEquals("content", catCopy.trim());
    }
}
