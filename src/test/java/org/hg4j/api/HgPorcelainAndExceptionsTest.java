package org.hg4j.api;

import org.hg4j.core.Dirstate;
import org.hg4j.core.HgRepository;
import org.hg4j.core.Revlog;
import org.hg4j.errors.HgCorruptDataException;
import org.hg4j.errors.HgRepositoryNotFoundException;
import org.hg4j.errors.HgRevisionNotFoundException;
import org.hg4j.lib.NodeId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD 검증: JGit 스타일의 open, NodeId 기반 API, 그리고 정교한 예외 흐름 종합 테스트 스위트
 */
public class HgPorcelainAndExceptionsTest {

    @TempDir
    File tempDir;

    @Test
    public void testHgOpenRepositoryNotFound() {
        File fakeDir = new File(tempDir, "fake_repo");
        assertThrows(HgRepositoryNotFoundException.class, () -> {
            Hg.open(fakeDir);
        });
    }

    @Test
    public void testHgOpenSuccess() throws IOException {
        File repoDir = new File(tempDir, "real_repo");
        assertTrue(repoDir.mkdir());
        File hgDir = new File(repoDir, ".hg");
        assertTrue(hgDir.mkdir());

        Hg hg = Hg.open(repoDir);
        assertNotNull(hg);
        assertNotNull(hg.getRepository());
        assertEquals(repoDir.getAbsolutePath(), hg.getRepository().getDirectory().getAbsolutePath());

        // Porcelain 인스턴스 메서드 체이닝 검증
        assertNotNull(hg.commit());
        assertNotNull(hg.add());
        assertNotNull(hg.status());
        assertNotNull(hg.log());
    }

    @Test
    public void testNodeIdPublicApi() {
        byte[] rawNode = new byte[20];
        byte[] rawManifest = new byte[20];
        rawNode[0] = 1;
        rawManifest[19] = 9;

        NodeId nodeId = new NodeId(rawNode);
        NodeId manifestId = new NodeId(rawManifest);

        List<String> files = new ArrayList<>();
        files.add("a.txt");

        HgCommit commit = new HgCommit(0, nodeId, manifestId, "tester", 
                123456789L, 3600, files, "Test Message", "default");

        assertEquals(0, commit.getRevision());
        assertEquals(nodeId, commit.getNodeId());
        assertEquals(manifestId, commit.getManifestNodeId());
        assertEquals("tester", commit.getAuthor());
        assertEquals(123456789L, commit.getTimestamp());
        assertEquals(3600, commit.getTimezoneOffset());
        assertEquals(files, commit.getFiles());
        assertEquals("Test Message", commit.getMessage());
        assertEquals("default", commit.getBranch());
    }

    @Test
    public void testDirstateCorruptDataException() {
        Dirstate dirstate = new Dirstate();
        
        // Null content 전달 시 HgCorruptDataException 검증
        assertThrows(HgCorruptDataException.class, () -> {
            dirstate.read((byte[]) null);
        });

        // 40바이트 미만 전달 시 HgCorruptDataException 검증
        byte[] smallBytes = new byte[10];
        assertThrows(HgCorruptDataException.class, () -> {
            dirstate.read(smallBytes);
        });
    }

    @Test
    public void testRevlogRevisionNotFoundException() throws IOException {
        File idxFile = new File(tempDir, "test.i");
        File datFile = new File(tempDir, "test.d");
        
        // 빈 revlog 생성
        Files.write(idxFile.toPath(), new byte[0]);
        Files.write(datFile.toPath(), new byte[0]);

        Revlog revlog = new Revlog(idxFile, datFile);
        
        // 리비전 카운트가 0일 때 존재하지 않는 리비전 요청 시 HgRevisionNotFoundException 검증
        assertThrows(HgRevisionNotFoundException.class, () -> {
            revlog.getRevisionContent(0);
        });

        assertThrows(HgRevisionNotFoundException.class, () -> {
            revlog.getRawRevisionContent(5);
        });
    }
}
