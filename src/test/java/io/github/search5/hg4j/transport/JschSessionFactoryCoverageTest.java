package io.github.search5.hg4j.transport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targeted coverage tests for {@link JschSessionFactory}. Exercises every branch: known_hosts
 * file presence, localhost/127.0.0.1 detection (StrictHostKeyChecking "ask" vs "no"), private key
 * loading with and without a passphrase, and password presence. {@code createSession} never
 * actually connects over the network (it only builds a {@link com.jcraft.jsch.Session} object and
 * configures it), so these tests run fully offline.
 */
public class JschSessionFactoryCoverageTest {

    @TempDir
    Path tempDir;

    private String originalUserHome;

    @AfterEach
    public void restoreUserHome() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
            originalUserHome = null;
        }
    }

    /** Points system property "user.home" at the isolated temp dir used by this test. */
    private void setFakeHome(boolean withKnownHosts) throws Exception {
        originalUserHome = System.getProperty("user.home");
        File sshDir = tempDir.resolve(".ssh").toFile();
        assertTrue(sshDir.mkdirs());
        if (withKnownHosts) {
            assertTrue(new File(sshDir, "known_hosts").createNewFile());
        }
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());
    }

    private static boolean isSshKeygenAvailable() {
        try {
            return new ProcessBuilder("ssh-keygen", "-?").start().waitFor() >= 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void runProcess(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        int code = p.waitFor();
        if (code != 0) {
            throw new RuntimeException("Command failed: " + String.join(" ", cmd) + "\n" + new String(out));
        }
    }

    @Test
    public void testKnownHostsPresent_nonLocalHost_usesAskStrictHostKeyChecking() throws Exception {
        // knownHostsFile.exists() == true, isLocal == false -> "ask" branch
        setFakeHome(true);
        JschSessionFactory factory = new JschSessionFactory();
        SshSession session = factory.createSession("example.com", 22, "user", null, null, null);
        assertNotNull(session);
        assertInstanceOf(JschSshSession.class, session);
    }

    @Test
    public void testKnownHostsPresent_localhost_usesNoStrictHostKeyChecking() throws Exception {
        // knownHostsFile.exists() == true, isLocal == true (via "localhost") -> "no" branch
        setFakeHome(true);
        JschSessionFactory factory = new JschSessionFactory();
        SshSession session = factory.createSession("localhost", 22, "user", null, null, null);
        assertNotNull(session);
    }

    @Test
    public void testKnownHostsPresent_loopbackIp_usesNoStrictHostKeyChecking() throws Exception {
        // knownHostsFile.exists() == true, isLocal == true (via "127.0.0.1") -> "no" branch
        setFakeHome(true);
        JschSessionFactory factory = new JschSessionFactory();
        SshSession session = factory.createSession("127.0.0.1", 22, "user", null, null, null);
        assertNotNull(session);
    }

    @Test
    public void testNoKnownHostsFile_nonLocalHost_usesNoStrictHostKeyChecking() throws Exception {
        // knownHostsFile.exists() == false -> "no" branch, regardless of isLocal
        setFakeHome(false);
        JschSessionFactory factory = new JschSessionFactory();
        SshSession session = factory.createSession("example.com", 22, "user", null, null, null);
        assertNotNull(session);
    }

    @Test
    public void testPasswordProvided_setsSessionPassword() throws Exception {
        // password != null -> session.setPassword branch
        setFakeHome(true);
        JschSessionFactory factory = new JschSessionFactory();
        SshSession session = factory.createSession("example.com", 22, "user", "s3cret", null, null);
        assertNotNull(session);
    }

    @Test
    public void testPrivateKeyWithoutPassphrase_addsIdentity() throws Exception {
        // privateKeyPath != null, passphrase == null -> addIdentity(String) branch
        Assumptions.assumeTrue(isSshKeygenAvailable(), "ssh-keygen is not available. Skipping.");
        setFakeHome(true);
        File keyFile = tempDir.resolve("id_nopass").toFile();
        runProcess("ssh-keygen", "-t", "rsa", "-b", "2048", "-f", keyFile.getAbsolutePath(), "-N", "");

        JschSessionFactory factory = new JschSessionFactory();
        SshSession session = factory.createSession(
                "example.com", 22, "user", null, keyFile.getAbsolutePath(), null);
        assertNotNull(session);
    }

    @Test
    public void testPrivateKeyWithPassphrase_addsIdentityWithPassphrase() throws Exception {
        // privateKeyPath != null, passphrase != null -> addIdentity(String, String) branch
        Assumptions.assumeTrue(isSshKeygenAvailable(), "ssh-keygen is not available. Skipping.");
        setFakeHome(true);
        File keyFile = tempDir.resolve("id_pass").toFile();
        runProcess("ssh-keygen", "-t", "rsa", "-b", "2048", "-f", keyFile.getAbsolutePath(), "-N", "s3cr3tPassphrase");

        JschSessionFactory factory = new JschSessionFactory();
        SshSession session = factory.createSession(
                "example.com", 22, "user", null, keyFile.getAbsolutePath(), "s3cr3tPassphrase");
        assertNotNull(session);
    }
}
