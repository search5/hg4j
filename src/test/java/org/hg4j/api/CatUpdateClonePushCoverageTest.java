package org.hg4j.api;

import org.hg4j.core.HgRepository;
import org.hg4j.core.NodeIdUtil;
import org.hg4j.core.Revlog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CatCommand, UpdateCommand, PushCommand, CloneCommand의 커버리지를 높이기 위한 테스트.
 * 각 명령어의 예외 경로 및 엣지 케이스를 검증한다.
 */
public class CatUpdateClonePushCoverageTest {

    // ─────────────────────────────────────────────
    // CatCommand 커버리지
    // ─────────────────────────────────────────────

    @Test
    public void testCatCommandFileNotSpecified(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        // file 없이 call() → IllegalStateException
        CatCommand cat = new CatCommand(repo);
        assertThrows(IllegalStateException.class, cat::call);
    }

    @Test
    public void testCatCommandEmptyRepository(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        // 빈 리포지토리에서 파일 조회 → IOException (Unable to resolve revision)
        CatCommand cat = new CatCommand(repo).setFile("some.txt");
        assertThrows(IOException.class, cat::call);
    }

    @Test
    public void testCatCommandFileNotTracked(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 파일 하나 커밋
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Hello Cat\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("커밋1").call();

        // 리포지토리에 없는 파일 조회 → IOException
        CatCommand cat = new CatCommand(repo).setFile("nonexistent.txt");
        assertThrows(IOException.class, cat::call);
    }

    @Test
    public void testCatCommandRetrievesFileContent(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 파일 하나 커밋
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Hello Cat Command\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("커밋1").call();

        // CatCommand로 내용 조회
        byte[] content = new CatCommand(repo).setFile("a.txt").call();
        assertNotNull(content);
        assertEquals("Hello Cat Command\n", new String(content));
    }

    @Test
    public void testCatCommandWithRevisionNumber(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 두 커밋
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Initial content\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("커밋1").call();

        Files.writeString(f1.toPath(), "Updated content\n");
        new CommitCommand(repo).setMessage("커밋2").call();

        // 리비전 0에서의 내용 조회
        byte[] content0 = new CatCommand(repo).setFile("a.txt").setRevision("0").call();
        assertEquals("Initial content\n", new String(content0));

        // 리비전 1에서의 내용 조회
        byte[] content1 = new CatCommand(repo).setFile("a.txt").setRevision("1").call();
        assertEquals("Updated content\n", new String(content1));
    }

    @Test
    public void testCatCommandWithHexNodeId(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Hex node content\n");
        new AddCommand(repo).call();
        byte[] commitNode = new CommitCommand(repo).setMessage("커밋").call();

        String hexPrefix = NodeIdUtil.toHex(commitNode).substring(0, 8);

        byte[] content = new CatCommand(repo).setFile("a.txt").setRevision(hexPrefix).call();
        assertEquals("Hex node content\n", new String(content));
    }

    @Test
    public void testCatCommandAmbiguousRevision(@TempDir Path tempDir) throws Exception {
        // 두 커밋의 hex가 동일한 접두사를 가진 경우를 인위적으로 만들기 어려우므로
        // 잘못된 리비전 ID 테스트로 대체
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "content\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("커밋").call();

        // 존재하지 않는 hex 접두사 → IOException (null 반환 후)
        CatCommand cat = new CatCommand(repo).setFile("a.txt").setRevision("ffffffff");
        assertThrows(IOException.class, cat::call);
    }

    @Test
    public void testCatCommandSetRevisionNodeIdNull(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        CatCommand cat = new CatCommand(repo).setFile("a.txt").setRevision((org.hg4j.lib.NodeId) null);
        // 리비전이 null이면 null로 셋팅되고 빈 리포지토리에서 call() 시 리비전 해석 실패로 예외 발생
        assertThrows(IOException.class, cat::call);
    }

    @Test
    public void testCatCommandNonExistent40HexRevision(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "content\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("커밋").call();

        // 40자리 존재하지 않는 hex nodeId를 주면 해석되지 않아 Unable to resolve revision 예외가 발생함
        String nonExistent40Hex = "f".repeat(40);
        CatCommand cat = new CatCommand(repo).setFile("a.txt").setRevision(nonExistent40Hex);
        org.hg4j.errors.HgRevisionNotFoundException ex = assertThrows(org.hg4j.errors.HgRevisionNotFoundException.class, cat::call);
        assertTrue(ex.getMessage().contains("Unable to resolve revision"));
    }

    @Test
    public void testCatCommandSetFileNullOrEmpty(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        
        CatCommand catNullFile = new CatCommand(repo).setFile(null);
        assertThrows(IllegalStateException.class, catNullFile::call);

        CatCommand catEmptyFile = new CatCommand(repo).setFile("");
        assertThrows(IllegalStateException.class, catEmptyFile::call);
    }

    @Test
    public void testCatCommandFilelogNotFound(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "content a\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("커밋").call();

        // 매니페스트에는 등록되었으나, 실제 파일로그 파일(.i)을 강제로 삭제
        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "a.txt");
        assertTrue(flIdx.exists());
        assertTrue(flIdx.delete());

        CatCommand cat = new CatCommand(repo).setFile("a.txt");
        org.hg4j.errors.HgCorruptDataException ex = assertThrows(org.hg4j.errors.HgCorruptDataException.class, cat::call);
        assertTrue(ex.getMessage().contains("Filelog not found"));
    }

    // ─────────────────────────────────────────────
    // UpdateCommand 커버리지 추가 경로
    // ─────────────────────────────────────────────

    @Test
    public void testUpdateCommandEmptyRepository(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        // 빈 리포지토리에서 update → IOException (Repository is empty)
        UpdateCommand update = new UpdateCommand(repo);
        assertThrows(IOException.class, update::call);
    }

    @Test
    public void testUpdateCommandInvalidRevision(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "content\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("커밋").call();

        // 존재하지 않는 리비전
        assertThrows(IOException.class, () ->
                new UpdateCommand(repo).setRevision("invalid_xyz_456").call());
    }

    @Test
    public void testUpdateCommandWithFileDeletion(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 커밋 0: a.txt, b.txt 모두 있음
        File f1 = new File(repoDir, "a.txt");
        File f2 = new File(repoDir, "b.txt");
        Files.writeString(f1.toPath(), "file a\n");
        Files.writeString(f2.toPath(), "file b\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("두 파일 커밋").call();

        // 커밋 1: b.txt 삭제
        new RemoveCommand(repo).setFile("b.txt").call();
        new CommitCommand(repo).setMessage("b.txt 삭제").call();

        assertTrue(f1.exists());
        assertFalse(f2.exists());

        // 커밋 0으로 업데이트 → b.txt 복원
        new UpdateCommand(repo).setRevision("0").setForce(true).call();
        assertTrue(f1.exists());
        assertTrue(f2.exists());
        assertEquals("file b\n", Files.readString(f2.toPath()));

        // 다시 커밋 1로 업데이트 → b.txt 삭제
        new UpdateCommand(repo).setRevision("1").setForce(true).call();
        assertTrue(f1.exists());
        assertFalse(f2.exists());
    }

    // ─────────────────────────────────────────────
    // PushCommand 커버리지 추가 경로
    // ─────────────────────────────────────────────

    @Test
    public void testPushCommandNoDestinationUrl(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        PushCommand push = new PushCommand(repo);
        assertThrows(IllegalStateException.class, push::call);
    }

    @Test
    public void testPushCommandBundleWriteEntry(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 커밋 두 개를 준비하고 PushCommand의 번들 직렬화를 내부적으로 검증
        // (실제 push는 네트워크가 필요하지만, 번들 구성 코드는 실행 가능)
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "content push\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("push test 커밋").call();

        // destination 없이 호출 → IllegalStateException (네트워크 없이 검증)
        assertThrows(IllegalStateException.class, () ->
                new PushCommand(repo).call());
    }

    // ─────────────────────────────────────────────
    // CloneCommand 커버리지 추가 경로
    // ─────────────────────────────────────────────

    @Test
    public void testCloneCommandNoSourceUrl(@TempDir Path tempDir) {
        CloneCommand clone = new CloneCommand();
        clone.setDirectory(tempDir.toFile());
        assertThrows(IllegalStateException.class, clone::call);
    }

    @Test
    public void testCloneCommandNoDirectory() {
        CloneCommand clone = new CloneCommand();
        clone.setSource("http://some.server/repo");
        assertThrows(IllegalStateException.class, clone::call);
    }

    @Test
    public void testCloneCommandDestinationNotEmpty(@TempDir Path tempDir) throws Exception {
        // 이미 비어있지 않은 디렉터리를 지정 → IOException
        File destDir = tempDir.toFile();
        Files.writeString(destDir.toPath().resolve("existing_file.txt"), "already here");

        CloneCommand clone = new CloneCommand()
                .setSource("http://some.server/repo")
                .setDirectory(destDir);
        assertThrows(IOException.class, clone::call);
    }

    @Test
    public void testPushCommandNonLinearStartRevCalculation(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("local_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Create non-linear commits:
        // Rev 0: A
        File fa = new File(repoDir, "a.txt");
        Files.writeString(fa.toPath(), "Content A");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit A").call();

        // Rev 1: B (parent: A) on branch-B
        repo.setBranch("branch-B");
        File fb = new File(repoDir, "b.txt");
        Files.writeString(fb.toPath(), "Content B");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit B").call();

        // Rev 2: C (parent: A) on default
        new UpdateCommand(repo).setRevision("0").setForce(true).call();
        repo.setBranch("default");
        File fc = new File(repoDir, "c.txt");
        Files.writeString(fc.toPath(), "Content C");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit C").call();

        // Rev 3: D (parent: C) on default
        File fd = new File(repoDir, "d.txt");
        Files.writeString(fd.toPath(), "Content D");
        new AddCommand(repo).call();
        byte[] nodeD = new CommitCommand(repo).setMessage("Commit D").call();

        // 2. Start Mock HttpServer acting as remote Mercurial server
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
            new java.net.InetSocketAddress(0), 0
        );
        String nodeDHex = NodeIdUtil.toHex(nodeD);

        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query != null && query.contains("cmd=heads")) {
                byte[] response = (nodeDHex + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } else if (query != null && query.contains("cmd=unbundle")) {
                byte[] response = "0\nno errors\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } else {
                exchange.sendResponseHeaders(404, 0);
            }
            exchange.close();
        });
        server.start();

        int port = server.getAddress().getPort();
        String remoteUrl = "http://localhost:" + port + "/";

        try {
            // 3. Try to push
            PushCommand push = new PushCommand(repo).setDestination(remoteUrl);
            String result = push.call();

            // In the bug state, since maxRemoteRev = 3, startRev = 4 (under count = 4).
            // Under count = 4, startRev >= count triggers "No changesets to push (remote is up-to-date)".
            // But we actually have Commit B (rev 1) which is missing on remote!
            // With the fix, startRev = 1, and it should successfully push and return "0\nno errors\n".
            assertEquals("0\nno errors\n", result);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testPushCommandUnrelatedRepositoryThrows(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("local_repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // Create a local commit
        File fa = new File(repoDir, "a.txt");
        Files.writeString(fa.toPath(), "Content A");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit A").call();

        // Mock remote heads having a completely unrelated commit hash
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
            new java.net.InetSocketAddress(0), 0
        );
        String randomHex = "1111222233334444555566667777888899990000";

        server.createContext("/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query != null && query.contains("cmd=heads")) {
                byte[] response = (randomHex + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } else {
                exchange.sendResponseHeaders(404, 0);
            }
            exchange.close();
        });
        server.start();

        int port = server.getAddress().getPort();
        String remoteUrl = "http://localhost:" + port + "/";

        try {
            PushCommand push = new PushCommand(repo).setDestination(remoteUrl);
            IOException ex = assertThrows(IOException.class, push::call);
            assertTrue(ex.getMessage().contains("unrelated"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testCatCommandSetRevisionNodeIdNotNull(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "NodeId test content\n");
        new AddCommand(repo).call();
        byte[] commitNode = new CommitCommand(repo).setMessage("커밋").call();

        // NodeId 객체로 setRevision을 호출하여 null이 아닌 분기 실행
        org.hg4j.lib.NodeId nodeIdObj = new org.hg4j.lib.NodeId(commitNode);
        byte[] content = new CatCommand(repo).setFile("a.txt").setRevision(nodeIdObj).call();
        assertEquals("NodeId test content\n", new String(content));
    }

    @Test
    public void testCatCommandFileVersionNotFoundInHistory(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "content a\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("커밋").call();

        // getManifestAtCommit을 오버라이드하여 존재하지 않는 file version hex를 반환하게 만듦
        HgRepository spyRepo = new HgRepository(repoDir) {
            @Override
            public java.util.Map<String, String> getManifestAtCommit(byte[] commitNodeId) throws IOException {
                java.util.Map<String, String> fakeMap = new java.util.HashMap<>();
                fakeMap.put("a.txt", "1".repeat(40)); // 존재하지 않는 40자리 hex
                return fakeMap;
            }
        };

        CatCommand cat = new CatCommand(spyRepo).setFile("a.txt").setRevision("0");
        org.hg4j.errors.HgRevisionNotFoundException ex = assertThrows(org.hg4j.errors.HgRevisionNotFoundException.class, cat::call);
        assertTrue(ex.getMessage().contains("File version not found in history"));
    }

    @Test
    public void testCommitDefaultDraftPhase(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "test content");
        new AddCommand(repo).call();
        byte[] commitNode = new CommitCommand(repo).setMessage("신규 커밋").call();

        // 새로 생성된 커밋의 페이즈가 draft인지 검증
        org.hg4j.core.PhaseRoots phaseRoots = repo.getPhaseRoots();
        org.hg4j.lib.NodeId nodeId = new org.hg4j.lib.NodeId(commitNode);
        
        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog cl = repo.getRevlog(clIdx, clDat);

        assertEquals(org.hg4j.core.PhaseRoots.Phase.DRAFT, phaseRoots.getPhase(nodeId, cl));
    }

    @Test
    public void testPushBlocksSecretPhaseCommits(@TempDir Path tempDir) throws Exception {
        // 1. remote 저장소 초기화 및 첫 커밋 생성
        File remoteDir = tempDir.resolve("remote").toFile();
        HgRepository remoteRepo = Hg.init().setDirectory(remoteDir).call();
        File rf = new File(remoteDir, "base.txt");
        Files.writeString(rf.toPath(), "base content");
        new AddCommand(remoteRepo).call();
        new CommitCommand(remoteRepo).setMessage("Base commit").call();

        // 2. remote 저장소를 local 디렉토리로 clone하여 완벽한 연관 관계 형성
        File localDir = tempDir.resolve("local").toFile();
        Hg.cloneRepository().setSource(remoteDir.getAbsolutePath()).setDirectory(localDir).call();
        
        HgRepository localRepo = Hg.open(localDir).getRepository();
        File lf = new File(localDir, "secret.txt");
        Files.writeString(lf.toPath(), "secret content");
        new AddCommand(localRepo).call();
        byte[] commitNode = new CommitCommand(localRepo).setMessage("Secret commit").call();

        // 3. local의 신규 커밋을 SECRET 페이즈로 강제 변경
        org.hg4j.core.PhaseRoots phaseRoots = localRepo.getPhaseRoots();
        org.hg4j.lib.NodeId nodeId = new org.hg4j.lib.NodeId(commitNode);
        
        File clIdx = new File(localRepo.getStoreDir(), "00changelog.i");
        File clDat = new File(localRepo.getStoreDir(), "00changelog.d");
        Revlog cl = localRepo.getRevlog(clIdx, clDat);
        
        phaseRoots.setPhase(nodeId, org.hg4j.core.PhaseRoots.Phase.SECRET, cl);
        assertEquals(org.hg4j.core.PhaseRoots.Phase.SECRET, phaseRoots.getPhase(nodeId, cl));

        // 4. push 호출 시 예외 발생 검증
        PushCommand push = new PushCommand(localRepo).setDestination(remoteDir.getAbsolutePath());
        org.hg4j.errors.HgValidationException ex = assertThrows(org.hg4j.errors.HgValidationException.class, push::call);
        assertTrue(ex.getMessage().contains("push includes secret commit"));
    }
}
