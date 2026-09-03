package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;

/**
 * 실제 hg는 default 브랜치 커밋의 changelog "extra" 줄에 "branch:default"를 전혀 쓰지
 * 않는다(mercurial/changelog.py의 add()에서 branch=='default'/''이면 extra에서 제거).
 * 항상 "branch:default"를 쓰면 동일한 내용이라도 원문 바이트, 나아가 커밋 노드 해시가
 * 실제 hg와 달라진다 — 이를 hg4j의 CommitCommand가 올바르게 재현하는지 검증한다.
 */
@Tag("interop")
public class ChangelogExtraFieldInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    @Test
    public void defaultBranchCommitOmitsBranchExtraField(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);
        Files.writeString(new File(repoDir, "a.txt").toPath(), "one");
        hg.add().addFile("a.txt").call();
        byte[] node = hg.commit().setAuthor("T").setMessage("c1").call();

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog changelog = repo.getRevlog(clIdx, clDat);
        int rev = changelog.findRevision(node);
        String rawText = new String(changelog.getRevisionContent(rev), StandardCharsets.UTF_8);

        String[] lines = rawText.split("\n", -1);
        assertTrue(lines.length >= 3);
        String dateLine = lines[2];
        assertFalse(dateLine.contains("branch:"),
                "실제 hg는 default 브랜치 커밋에 branch: extra 항목을 남기지 않는다: " + dateLine);
    }

    @Test
    public void nonDefaultBranchCommitIncludesBranchExtraField(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);
        repo.setBranch("feature");
        Files.writeString(new File(repoDir, "a.txt").toPath(), "one");
        hg.add().addFile("a.txt").call();
        byte[] node = hg.commit().setAuthor("T").setMessage("c1").call();

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog changelog = repo.getRevlog(clIdx, clDat);
        int rev = changelog.findRevision(node);
        String rawText = new String(changelog.getRevisionContent(rev), StandardCharsets.UTF_8);
        String dateLine = rawText.split("\n", -1)[2];
        assertTrue(dateLine.contains("branch:feature"), "dateLine=" + dateLine);
    }

    @Test
    public void hg4jDefaultBranchChangelogBytesMatchRealHg(@TempDir Path tempDir) throws Exception {
        // 실제 hg가 만드는 changelog 원문(사용자/날짜 제외 나머지)과 hg4j가 만드는 것을
        // 형태적으로 대조: 둘 다 "manifest\nuser\nsecs tz\nfiles...\n\nmsg" 형태이며
        // default 브랜치에서는 3번째 줄에 "secs tz" 뿐이어야 한다.
        File nativeDir = tempDir.resolve("native").toFile();
        HgTestUtils.nativeRepo(nativeDir, dir -> {
            try {
                Files.writeString(new File(dir, "a.txt").toPath(), "one");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        HgTestUtils.hg(nativeDir, "add");
        HgTestUtils.hg(nativeDir, "commit", "-u", "T", "-m", "c1", "--config", "devel.default-date=1000000 0");
        String nativeNode = HgTestUtils.hg(nativeDir, "log", "-r", "0", "--template", "{node}");
        HgRepository nativeRepo = new HgRepository(nativeDir);
        Revlog nativeCl = nativeRepo.getRevlog(new File(nativeRepo.getStoreDir(), "00changelog.i"), new File(nativeRepo.getStoreDir(), "00changelog.d"));
        String nativeRaw = new String(nativeCl.getRevisionContent(0), StandardCharsets.UTF_8);
        String nativeDateLine = nativeRaw.split("\n", -1)[2];
        assertEquals("1000000 0", nativeDateLine, "sanity check on native fixture");

        File hg4jDir = tempDir.resolve("hg4j").toFile();
        HgRepository hg4jRepo = Hg.init().setDirectory(hg4jDir).call();
        Hg hg = Hg.wrap(hg4jRepo);
        Files.writeString(new File(hg4jDir, "a.txt").toPath(), "one");
        hg.add().addFile("a.txt").call();
        byte[] node = hg.commit().setAuthor("T").setMessage("c1").setDate(1000000L, 0).call();

        Revlog hg4jCl = hg4jRepo.getRevlog(new File(hg4jRepo.getStoreDir(), "00changelog.i"), new File(hg4jRepo.getStoreDir(), "00changelog.d"));
        int rev = hg4jCl.findRevision(node);
        String hg4jRaw = new String(hg4jCl.getRevisionContent(rev), StandardCharsets.UTF_8);
        String hg4jDateLine = hg4jRaw.split("\n", -1)[2];
        assertEquals(nativeDateLine, hg4jDateLine);
        assertEquals(nativeNode, NodeIdUtil.toHex(node),
                "동일한 부모/작성자/날짜/메시지/파일 내용이면 노드 해시도 실제 hg와 같아야 함");
    }
}
