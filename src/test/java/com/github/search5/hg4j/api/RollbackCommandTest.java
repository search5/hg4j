package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.github.search5.hg4j.storage.Revlog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class RollbackCommandTest {

    @Test
    public void testRollbackCommand(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Hg hg = Hg.wrap(repo);

        // 1. Commit 1
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "content 1");
        hg.add().addFile("a.txt").call();
        byte[] commit1 = hg.commit().setMessage("Commit 1").call();
        String hex1 = toHex(commit1).substring(0, 40);

        // 2. Commit 2
        Files.writeString(f1.toPath(), "content 2");
        byte[] commit2 = hg.commit().setMessage("Commit 2").call();
        String hex2 = toHex(commit2).substring(0, 40);

        // 2차 커밋 완료 후 저장소의 커밋 개수가 2개임을 확인
        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog changelog = repo.getRevlog(clIdx, clDat);
        assertEquals(2, changelog.getRevisionCount());

        // 3. Rollback 실행 (Commit 2를 되돌림)
        hg.rollback();

        // 4. 검증: 저장소의 커밋 개수가 다시 1개로 줄어들고 1차 커밋 노드가 복구되었는지 확인
        repo.clearRevlogCache();
        changelog = repo.getRevlog(clIdx, clDat);
        assertEquals(1, changelog.getRevisionCount());
        assertEquals(hex1, toHex(changelog.getIndexRecord(0).getNodeId()).substring(0, 40));

        // dirstate 부모도 Commit 1로 되돌아갔는지 확인
        byte[] parent1 = repo.getDirstate().getParent1();
        assertEquals(hex1, toHex(parent1).substring(0, 40));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(2 * bytes.length);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
