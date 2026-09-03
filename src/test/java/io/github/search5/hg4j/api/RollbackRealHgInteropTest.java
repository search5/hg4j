package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import io.github.search5.hg4j.lib.NodeId;

/**
 * 트랜잭션 저널링/rollback(Track B-4) 실제 hg CLI 상호운용 검증.
 */
@Tag("interop")
public class RollbackRealHgInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    @Test
    public void testRollbackAfterCommitMatchesRealHgState(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        new InitCommand().setDirectory(repoDir).call();
        HgRepository repo = new HgRepository(repoDir);

        Files.writeString(new File(repoDir, "a.txt").toPath(), "one");
        new AddCommand(repo).call();
        byte[] c1 = new CommitCommand(repo).setAuthor("T").setMessage("c1").call();
        String c1Hex = new NodeId(c1).toHex();

        Files.writeString(new File(repoDir, "a.txt").toPath(), "two");
        new CommitCommand(repo).setAuthor("T").setMessage("c2").call();

        new RollbackCommand(repo).call();

        String nativeLog = HgTestUtils.hg(repoDir, "log", "--template", "{node}\n");
        assertEquals(c1Hex, nativeLog.trim(), "rollback 후 실제 hg가 봐도 c2가 사라지고 c1만 남아야 함");

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog changelog = repo.getRevlog(clIdx, clDat);
        assertEquals(1, changelog.getRevisionCount());
    }

    /**
     * 2026-09-01 이전에는 CommitCommand만 undo 정보를 기록해서 pull 직후에는 rollback이
     * 아예 동작하지 않았다(가장 흔한 실사용 시나리오인데도). FetchCommand도 undo 정보를
     * 남기도록 고친 것을 검증한다.
     */
    @Test
    public void testRollbackAfterPullRevertsToPrePullState(@TempDir Path tempDir) throws Exception {
        File remoteDir = tempDir.resolve("remote").toFile();
        HgTestUtils.nativeRepo(remoteDir, dir -> {
            try {
                Files.writeString(new File(dir, "a.txt").toPath(), "one");
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        HgTestUtils.hg(remoteDir, "add");
        HgTestUtils.hg(remoteDir, "commit", "-u", "T", "-m", "c1");

        File localDir = tempDir.resolve("local").toFile();
        new InitCommand().setDirectory(localDir).call();
        HgRepository local = new HgRepository(localDir);
        new PullCommand(local).setSource(remoteDir.getAbsolutePath()).call();

        File clIdx = new File(local.getStoreDir(), "00changelog.i");
        File clDat = new File(local.getStoreDir(), "00changelog.d");
        assertEquals(1, local.getRevlog(clIdx, clDat).getRevisionCount());

        new RollbackCommand(local).call();

        local.clearRevlogCache();
        assertEquals(0, local.getRevlog(clIdx, clDat).getRevisionCount(),
                "pull로 받아온 리비전이 rollback으로 전부 되돌아가야 함");
    }
}
