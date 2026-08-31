package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.errors.HgCorruptDataException;
import com.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import com.github.search5.hg4j.errors.HgRevisionNotFoundException;
import com.github.search5.hg4j.lib.NodeId;
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
    public void testHgPorcelainAllMethods() throws IOException {
        File repoDir = new File(tempDir, "real_repo_all");
        assertTrue(repoDir.mkdir());
        File hgDir = new File(repoDir, ".hg");
        assertTrue(hgDir.mkdir());

        try (Hg hg = Hg.open(repoDir)) {
            assertNotNull(hg);
            assertThrows(IllegalArgumentException.class, () -> Hg.open((java.io.File) null));

            // 커맨드 빌더 확인
            assertNotNull(hg.branch());
            assertNotNull(hg.tag());
            assertNotNull(hg.bookmark());
            assertNotNull(hg.merge());
            assertNotNull(hg.pull());
            assertNotNull(hg.shelve());
            assertNotNull(hg.rebase());
            assertNotNull(hg.update());
            assertNotNull(hg.push());
            assertNotNull(hg.cat());
            assertNotNull(hg.revert());
            assertNotNull(hg.remove());
            assertNotNull(hg.diff());
            assertNotNull(hg.tree());

            // NodeId diff, tree 헬퍼 메서드
            NodeId node1 = new NodeId(new byte[20]);
            NodeId node2 = new NodeId(new byte[20]);
            
            // 빈 저장소이므로 예외 없이 빈 리스트를 리턴함
            assertTrue(hg.getDiff(node1, node2).isEmpty());
            assertTrue(hg.getTree(node1).isEmpty());
            
            // int 기반 헬퍼 메서드 커버
            assertTrue(hg.getTree(0).isEmpty());
        }
    }

    @Test
    public void testHgValidationException() {
        com.github.search5.hg4j.errors.HgValidationException ex1 = new com.github.search5.hg4j.errors.HgValidationException("val error");
        assertEquals("val error", ex1.getMessage());
        
        Throwable cause = new RuntimeException("cause");
        com.github.search5.hg4j.errors.HgValidationException ex2 = new com.github.search5.hg4j.errors.HgValidationException("val error 2", cause);
        assertEquals("val error 2", ex2.getMessage());
        assertEquals(cause, ex2.getCause());
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

    @Test
    public void testCatCommandEdgeCases() throws Exception {
        File repoDir = new File(tempDir, "cat_edge_repo");
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. File path must be specified
        CatCommand cat = new CatCommand(repo);
        assertThrows(IllegalStateException.class, cat::call);
        cat.setFile("");
        assertThrows(IllegalStateException.class, cat::call);

        cat.setFile("a.txt");

        // 2. Commit a file
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Hello Cat\n");
        new AddCommand(repo).call();
        byte[] revNode = new CommitCommand(repo).setMessage("Base").call();

        // setRevision(NodeId) 테스트
        cat.setRevision(new com.github.search5.hg4j.lib.NodeId(revNode));
        assertArrayEquals("Hello Cat\n".getBytes(), cat.call());

        // setRevision(NodeId null) 테스트
        cat.setRevision((com.github.search5.hg4j.lib.NodeId) null);
        assertArrayEquals("Hello Cat\n".getBytes(), cat.call()); // defaults to tip

        // 3. Revision not found
        cat.setRevision("non_existent_branch_123");
        assertThrows(com.github.search5.hg4j.errors.HgRevisionNotFoundException.class, cat::call);

        // 4. Filelog index not found
        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "a.txt");
        assertTrue(flIdx.exists());
        assertTrue(flIdx.delete());

        cat.setRevision(new com.github.search5.hg4j.lib.NodeId(revNode));
        assertThrows(com.github.search5.hg4j.errors.HgCorruptDataException.class, cat::call);
    }

    @Test
    public void testHgOpenRequiresValidationAndWrap(@TempDir java.nio.file.Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("requires_val_repo").toFile();
        com.github.search5.hg4j.lib.HgRepository repo = Hg.init().setDirectory(repoDir).call();
        
        // 1. wrap() verify
        try (Hg hg = Hg.wrap(repo)) {
            assertNotNull(hg);
            assertEquals(repo, hg.getRepository());
        }

        // 2. open(String) overload verify
        try (Hg hg = Hg.open(repoDir.getAbsolutePath())) {
            assertNotNull(hg);
        }

        // 3. unsupported requirement check in .hg/requires
        File hgDir = new File(repoDir, ".hg");
        File requires = new File(hgDir, "requires");
        Files.writeString(requires.toPath(), "unsupported-ext-requirement-123\n");
        
        assertThrows(com.github.search5.hg4j.errors.HgValidationException.class, () -> {
            Hg.open(repoDir);
        });
    }

    @Test
    public void testJavaScmHookSystemIntegration(@TempDir java.nio.file.Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("hook_test_repo").toFile();
        com.github.search5.hg4j.lib.HgRepository repo = Hg.init().setDirectory(repoDir).call();
        
        try (Hg hg = Hg.wrap(repo)) {
            // 1. PRE_COMMIT hook 등록하여 커밋 트랜잭션을 거부하는지 확인
            hg.registerHook(HgHookType.PRE_COMMIT, ctx -> {
                String message = (String) ctx.get("message");
                // "bad message" 이면 거절
                return !"bad message".equals(message);
            });

            // 2. POST_COMMIT hook 등록하여 성공 시 context 데이터를 수집하는지 검사
            final List<String> hookResults = new java.util.ArrayList<>();
            hg.registerHook(HgHookType.POST_COMMIT, ctx -> {
                hookResults.add((String) ctx.get("message"));
                return true;
            });

            File file1 = new File(repoDir, "a.txt");
            Files.writeString(file1.toPath(), "Some Content");
            hg.add().addFile("a.txt").call();

            // 3. PRE_COMMIT 훅 거부 시 예외가 발생하는지 확인
            CommitCommand rejectCommit = hg.commit().setAuthor("Tester").setMessage("bad message");
            assertThrows(com.github.search5.hg4j.errors.HgValidationException.class, rejectCommit::call);

            // 4. 정상적인 메시지로 커밋 시 성공적으로 수행되고 POST_COMMIT 훅이 구동되는지 확인
            byte[] node = hg.commit().setAuthor("Tester").setMessage("good message").call();
            assertNotNull(node);
            assertEquals(1, hookResults.size());
            assertEquals("good message", hookResults.get(0));
        }
    }

    @Test
    public void testDynamicZstdCompressionIntegration(@TempDir java.nio.file.Path tempDir) throws Exception {
        // 1. Zstd 압축이 활성화된 저장소 초기화
        File zstdRepoDir = tempDir.resolve("zstd_repo").toFile();
        com.github.search5.hg4j.lib.HgRepository zstdRepo = Hg.init()
                .setDirectory(zstdRepoDir)
                .setUseZstd(true)
                .call();
        
        assertTrue(zstdRepo.isUseZstdCompression());
        
        // 2. 텍스트 파일 기입 및 커밋
        try (Hg hg = Hg.wrap(zstdRepo)) {
            File largeFile = new File(zstdRepoDir, "large.txt");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 200; i++) {
                sb.append("This is repeated text that compresses extremely well with Zstandard compression. Line ").append(i).append("\n");
            }
            Files.writeString(largeFile.toPath(), sb.toString());
            
            hg.add().addFile("large.txt").call();
            byte[] node = hg.commit().setAuthor("Tester").setMessage("commit with zstd").call();
            assertNotNull(node);
            
            // 3. 기록된 데이터 읽기 검증 (zstd 압축 해제가 투명하게 진행되는지 확인)
            File flIdx = new File(zstdRepo.getStoreDir(), "data/large.txt.i");
            File flDat = new File(zstdRepo.getStoreDir(), "data/large.txt.d");
            assertTrue(flIdx.exists());
            
            Revlog fl = zstdRepo.getRevlog(flIdx, flDat);
            byte[] content = fl.getRevisionContent(0);
            assertEquals(sb.toString(), new String(content, java.nio.charset.StandardCharsets.UTF_8));
        }

        // 4. 일반 저장소(Zstd 비활성화)와 대조 검증
        File normalRepoDir = tempDir.resolve("normal_repo").toFile();
        com.github.search5.hg4j.lib.HgRepository normalRepo = Hg.init()
                .setDirectory(normalRepoDir)
                .setUseZstd(false)
                .call();
        
        assertFalse(normalRepo.isUseZstdCompression());
        
        try (Hg hg = Hg.wrap(normalRepo)) {
            File file = new File(normalRepoDir, "a.txt");
            Files.writeString(file.toPath(), "Simple text");
            hg.add().addFile("a.txt").call();
            byte[] node = hg.commit().setAuthor("Tester").setMessage("normal commit").call();
            assertNotNull(node);
            
            File flIdx = new File(normalRepo.getStoreDir(), "data/a.txt.i");
            File flDat = new File(normalRepo.getStoreDir(), "data/a.txt.d");
            Revlog fl = normalRepo.getRevlog(flIdx, flDat);
            byte[] content = fl.getRevisionContent(0);
            assertEquals("Simple text", new String(content, java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
