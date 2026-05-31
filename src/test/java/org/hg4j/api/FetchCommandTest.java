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
        
        // 1. Validate exception when URL is null
        assertThrows(IllegalStateException.class, () -> fetchCmd.call());
        
        // 2. Validate exception when URL is empty
        fetchCmd.setSource("");
        assertThrows(IllegalStateException.class, () -> fetchCmd.call());
    }

    @Test
    public void testFetchDoesNotAdvanceDirstateParent(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();

        // 1. Create source repository and commit
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();

        File f1 = new File(srcDir, "README.MD");
        Files.writeString(f1.toPath(), "Hello remote sync");

        new AddCommand(srcRepo).call();
        byte[] commitNode1 = new CommitCommand(srcRepo)
                .setAuthor("Alice <alice@example.com>")
                .setMessage("First commit in source")
                .call();

        // 2. Mock ChangegroupBundle based on source repository revision
        ChangegroupParser.ChangegroupBundle bundle = org.hg4j.HgTestUtils.createMockBundleFromRepo(srcRepo);

        // 3. Apply bundle to destination repository using FetchCommand
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        FetchCommand fetchCmd = new FetchCommand(destRepo);
        List<byte[]> imported = fetchCmd.applyBundle(bundle);

        assertEquals(1, imported.size());
        assertArrayEquals(commitNode1, imported.get(0));

        // 4. Verify changelog is synchronized
        Revlog cl = new Revlog(new File(destRepo.getStoreDir(), "00changelog.i"), new File(destRepo.getStoreDir(), "00changelog.d"));
        assertEquals(1, cl.getRevisionCount());

        // 5. Fetch must not update the working copy dirstate parent and leave it as All Zero
        Dirstate dirstate = destRepo.getDirstate();
        assertTrue(NodeIdUtil.isAllZero(dirstate.getParent1()), "Fetch 후에는 Dirstate parent1이 All Zero여야 합니다.");
        assertTrue(NodeIdUtil.isAllZero(dirstate.getParent2()), "Fetch 후에는 Dirstate parent2가 All Zero여야 합니다.");
    }

    @Test
    public void testPullAdvancesDirstateParent(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();

        // 1. Create source repository and commit
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();

        File f1 = new File(srcDir, "README.MD");
        Files.writeString(f1.toPath(), "Hello remote sync");

        new AddCommand(srcRepo).call();
        byte[] commitNode1 = new CommitCommand(srcRepo)
                .setAuthor("Alice <alice@example.com>")
                .setMessage("First commit in source")
                .call();

        ChangegroupParser.ChangegroupBundle bundle = org.hg4j.HgTestUtils.createMockBundleFromRepo(srcRepo);

        // 2. Apply bundle to destination repository using PullCommand
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        PullCommand pullCmd = new PullCommand(destRepo);
        List<byte[]> imported = pullCmd.applyBundle(bundle);

        assertEquals(1, imported.size());
        assertArrayEquals(commitNode1, imported.get(0));

        // 3. Pull must automatically advance the dirstate parent to the latest Head if it was empty
        Dirstate dirstate = destRepo.getDirstate();
        assertArrayEquals(commitNode1, dirstate.getParent1(), "Pull 후에는 Dirstate parent1이 최신 커밋으로 갱신되어야 합니다.");
        assertTrue(NodeIdUtil.isAllZero(dirstate.getParent2()), "Pull 후에도 Dirstate parent2는 All Zero여야 합니다.");
    }

    @Test
    public void testCredentialsProviderPropagation() throws Exception {
        // 1. Verify HTTP Client
        HgRemoteClient httpClient = new HgRemoteClient("http://example.com/repo");
        UsernamePasswordCredentialsProvider httpProvider = new UsernamePasswordCredentialsProvider("user1", "pass1");
        httpClient.setCredentialsProvider(httpProvider);

        Field userField = HgRemoteClient.class.getDeclaredField("username");
        userField.setAccessible(true);
        Field passField = HgRemoteClient.class.getDeclaredField("password");
        passField.setAccessible(true);

        assertEquals("user1", userField.get(httpClient));
        assertEquals("pass1", passField.get(httpClient));

        // 2. Verify SSH Client - Username / Password
        HgSshClient sshClient1 = new HgSshClient("ssh://example.com/repo");
        sshClient1.setCredentialsProvider(httpProvider);

        Field sshUserField = HgSshClient.class.getDeclaredField("username");
        sshUserField.setAccessible(true);
        Field sshPassField = HgSshClient.class.getDeclaredField("password");
        sshPassField.setAccessible(true);

        assertEquals("user1", sshUserField.get(sshClient1));
        assertEquals("pass1", sshPassField.get(sshClient1));

        // 3. Verify SSH Client - SSH Key
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
        
        // Verify that FetchCommand is normally created via Hg facade object
        FetchCommand fetchCmd = Hg.wrap(destRepo).fetch();
        assertNotNull(fetchCmd, "Hg.wrap(repo).fetch()로 FetchCommand를 직접 획득할 수 있어야 합니다.");
    }
}
