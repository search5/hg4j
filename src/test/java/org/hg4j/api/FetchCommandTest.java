package org.hg4j.api;

import org.hg4j.core.ChangegroupParser;
import org.hg4j.core.Dirstate;
import org.hg4j.core.HgRepository;
import org.hg4j.core.NodeIdUtil;
import org.hg4j.core.Revlog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.hg4j.transport.UsernamePasswordCredentialsProvider;
import org.hg4j.transport.SshKeyCredentialsProvider;
import org.hg4j.transport.HgRemoteClient;
import org.hg4j.transport.HgSshClient;
import java.lang.reflect.Field;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FetchCommandTest {

    @Test
    public void testFetchCommandValidationAndEdgeCases(@TempDir Path tempDir) throws Exception {
        File destDir = tempDir.resolve("dest_validation").toFile();
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        
        FetchCommand fetchCmd = new FetchCommand(destRepo);
        
        // 1. URL이 null일 때 예외 검증
        assertThrows(IllegalStateException.class, () -> fetchCmd.call());
        
        // 2. URL이 empty일 때 예외 검증
        fetchCmd.setSource("");
        assertThrows(IllegalStateException.class, () -> fetchCmd.call());
    }

    @Test
    public void testFetchDoesNotAdvanceDirstateParent(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();

        // 1. 원본 레포지토리 및 커밋 생성
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();

        File f1 = new File(srcDir, "README.MD");
        Files.writeString(f1.toPath(), "Hello remote sync");

        new AddCommand(srcRepo).call();
        byte[] commitNode1 = new CommitCommand(srcRepo)
                .setAuthor("Alice <alice@example.com>")
                .setMessage("First commit in source")
                .call();

        // 2. 원본 레포지토리 리비전 기반 ChangegroupBundle 모킹
        ChangegroupParser.ChangegroupBundle bundle = org.hg4j.HgTestUtils.createMockBundleFromRepo(srcRepo);

        // 3. 목적지 레포지토리에 FetchCommand로 번들 반영
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        FetchCommand fetchCmd = new FetchCommand(destRepo);
        List<byte[]> imported = fetchCmd.applyBundle(bundle);

        assertEquals(1, imported.size());
        assertArrayEquals(commitNode1, imported.get(0));

        // 4. changelog가 동기화되었는지 확인
        Revlog cl = new Revlog(new File(destRepo.getStoreDir(), "00changelog.i"), new File(destRepo.getStoreDir(), "00changelog.d"));
        assertEquals(1, cl.getRevisionCount());

        // 5. 핵심: Fetch는 작업 사본 dirstate parent를 갱신하지 않고 All Zero로 두어야 함
        Dirstate dirstate = destRepo.getDirstate();
        assertTrue(NodeIdUtil.isAllZero(dirstate.getParent1()), "Fetch 후에는 Dirstate parent1이 All Zero여야 합니다.");
        assertTrue(NodeIdUtil.isAllZero(dirstate.getParent2()), "Fetch 후에는 Dirstate parent2가 All Zero여야 합니다.");
    }

    @Test
    public void testPullAdvancesDirstateParent(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();

        // 1. 원본 레포지토리 및 커밋 생성
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();

        File f1 = new File(srcDir, "README.MD");
        Files.writeString(f1.toPath(), "Hello remote sync");

        new AddCommand(srcRepo).call();
        byte[] commitNode1 = new CommitCommand(srcRepo)
                .setAuthor("Alice <alice@example.com>")
                .setMessage("First commit in source")
                .call();

        ChangegroupParser.ChangegroupBundle bundle = org.hg4j.HgTestUtils.createMockBundleFromRepo(srcRepo);

        // 2. 목적지 레포지토리에 PullCommand로 번들 반영
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        PullCommand pullCmd = new PullCommand(destRepo);
        List<byte[]> imported = pullCmd.applyBundle(bundle);

        assertEquals(1, imported.size());
        assertArrayEquals(commitNode1, imported.get(0));

        // 3. 핵심: Pull은 dirstate parent가 비어있었다면 자동으로 최신 Head로 전진시켜야 함
        Dirstate dirstate = destRepo.getDirstate();
        assertArrayEquals(commitNode1, dirstate.getParent1(), "Pull 후에는 Dirstate parent1이 최신 커밋으로 갱신되어야 합니다.");
        assertTrue(NodeIdUtil.isAllZero(dirstate.getParent2()), "Pull 후에도 Dirstate parent2는 All Zero여야 합니다.");
    }

    @Test
    public void testCredentialsProviderPropagation() throws Exception {
        // 1. HTTP Client 검증
        HgRemoteClient httpClient = new HgRemoteClient("http://example.com/repo");
        UsernamePasswordCredentialsProvider httpProvider = new UsernamePasswordCredentialsProvider("user1", "pass1");
        httpClient.setCredentialsProvider(httpProvider);

        Field userField = HgRemoteClient.class.getDeclaredField("username");
        userField.setAccessible(true);
        Field passField = HgRemoteClient.class.getDeclaredField("password");
        passField.setAccessible(true);

        assertEquals("user1", userField.get(httpClient));
        assertEquals("pass1", passField.get(httpClient));

        // 2. SSH Client 검증 - Username / Password
        HgSshClient sshClient1 = new HgSshClient("ssh://example.com/repo");
        sshClient1.setCredentialsProvider(httpProvider);

        Field sshUserField = HgSshClient.class.getDeclaredField("username");
        sshUserField.setAccessible(true);
        Field sshPassField = HgSshClient.class.getDeclaredField("password");
        sshPassField.setAccessible(true);

        assertEquals("user1", sshUserField.get(sshClient1));
        assertEquals("pass1", sshPassField.get(sshClient1));

        // 3. SSH Client 검증 - SSH Key
        HgSshClient sshClient2 = new HgSshClient("ssh://example.com/repo");
        SshKeyCredentialsProvider sshKeyProvider = new SshKeyCredentialsProvider("/path/to/key", "keypass");
        sshClient2.setCredentialsProvider(sshKeyProvider);

        Field sshKeyPathField = HgSshClient.class.getDeclaredField("privateKeyPath");
        sshKeyPathField.setAccessible(true);
        Field sshPassphraseField = HgSshClient.class.getDeclaredField("passphrase");
        sshPassphraseField.setAccessible(true);

        assertEquals("/path/to/key", sshKeyPathField.get(sshClient2));
        assertEquals("keypass", sshPassphraseField.get(sshClient2));
    }

    @Test
    public void testHgFacadeFetch(@TempDir Path tempDir) throws Exception {
        File destDir = tempDir.resolve("dest_facade").toFile();
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        
        // Hg 퍼사드 객체를 경유해 FetchCommand가 정상 생성되는지 검증
        FetchCommand fetchCmd = Hg.wrap(destRepo).fetch();
        assertNotNull(fetchCmd, "Hg.wrap(repo).fetch()로 FetchCommand를 직접 획득할 수 있어야 합니다.");
    }
}
