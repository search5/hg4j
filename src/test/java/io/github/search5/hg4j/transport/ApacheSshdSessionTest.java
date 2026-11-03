package io.github.search5.hg4j.transport;

import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.config.keys.AuthorizedKeysAuthenticator;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link ApacheSshdSession} and {@link ApacheSshdSessionFactory} against a real embedded
 * Apache SSHD server over a real TCP socket: authentication (password, key, key with passphrase),
 * failure reporting, bidirectional exec-channel streaming, stderr capture, host key verification
 * through {@code ~/.ssh/known_hosts} and host resolution through {@code ~/.ssh/config}.
 *
 * <p>Nothing here is mocked: every assertion is about what actually crossed the socket.</p>
 */
public class ApacheSshdSessionTest {

    private static final int TIMEOUT_MS = 10_000;

    @TempDir
    Path tempDir;

    private SshServer server;
    private int port;
    private String originalUserHome;

    @BeforeEach
    void startServer() throws Exception {
        originalUserHome = System.getProperty("user.home");
        // An empty fake home keeps the developer's real ~/.ssh out of every test.
        Path home = tempDir.resolve("home");
        Files.createDirectories(home.resolve(".ssh"));
        System.setProperty("user.home", home.toString());

        server = SshServer.setUpDefaultServer();
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempDir.resolve("host_key")));
        server.setPasswordAuthenticator((user, pass, s) -> "hg4juser".equals(user) && "hg4jpass".equals(pass));
        server.setCommandFactory((channel, command) -> new ScriptedCommand(command));
        server.start();
        port = server.getPort();
    }

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) {
            server.stop(true);
        }
        System.setProperty("user.home", originalUserHome);
    }

    private SshSession session(String host, String user, String password, String key, String passphrase)
            throws Exception {
        return new ApacheSshdSessionFactory().createSession(host, port, user, password, key, passphrase);
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1 && b != '\n') {
            sb.append((char) b);
        }
        return sb.toString();
    }

    // ───────────────────────── password authentication / streaming ─────────────────────────

    @Test
    @DisplayName("비밀번호 인증 후 exec 채널로 stdin/stdout 양방향 왕복이 된다")
    void passwordAuth_roundTripsBytesThroughExecChannel() throws Exception {
        try (SshSession s = session("127.0.0.1", "hg4juser", "hg4jpass", null, null)) {
            s.connect(TIMEOUT_MS);
            s.executeCommand("echo", TIMEOUT_MS);

            s.getOutputStream().write("ping-1\nping-2\n".getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();

            assertEquals("ping-1", readLine(s.getInputStream()));
            assertEquals("ping-2", readLine(s.getInputStream()));
        }
    }

    @Test
    @DisplayName("원격 stderr 가 getCapturedStderr 로 노출된다")
    void remoteStderr_isCaptured() throws Exception {
        try (ApacheSshdSession s = (ApacheSshdSession) session("127.0.0.1", "hg4juser", "hg4jpass", null, null)) {
            s.connect(TIMEOUT_MS);
            s.executeCommand("stderr:remote complaint", TIMEOUT_MS);

            assertEquals(-1, s.getInputStream().read(), "command writes nothing to stdout, then exits");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!s.getCapturedStderr().contains("remote complaint") && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(s.getCapturedStderr().contains("remote complaint"), s.getCapturedStderr());
        }
    }

    @Test
    @DisplayName("두 번째 connect 호출은 기존 연결을 재사용한다")
    void connectTwice_isIdempotent() throws Exception {
        try (SshSession s = session("127.0.0.1", "hg4juser", "hg4jpass", null, null)) {
            s.connect(TIMEOUT_MS);
            assertDoesNotThrow(() -> s.connect(TIMEOUT_MS));
            s.executeCommand("echo", TIMEOUT_MS);
            s.getOutputStream().write("x\n".getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            assertEquals("x", readLine(s.getInputStream()));
        }
    }

    // ───────────────────────── failures must propagate ─────────────────────────

    @Test
    @DisplayName("잘못된 비밀번호는 SshAuthenticationException 으로 전파된다")
    void wrongPassword_throwsSshAuthenticationException() throws Exception {
        try (SshSession s = session("127.0.0.1", "hg4juser", "not-the-password", null, null)) {
            SshAuthenticationException ex =
                    assertThrows(SshAuthenticationException.class, () -> s.connect(TIMEOUT_MS));
            assertTrue(ex.getMessage().contains("hg4juser@127.0.0.1:" + port), ex.getMessage());
        }
    }

    @Test
    @DisplayName("연결 전 executeCommand 는 IllegalStateException")
    void executeBeforeConnect_throwsIllegalState() throws Exception {
        try (SshSession s = session("127.0.0.1", "hg4juser", "hg4jpass", null, null)) {
            IllegalStateException ex =
                    assertThrows(IllegalStateException.class, () -> s.executeCommand("echo", TIMEOUT_MS));
            assertEquals("SSH Session is not connected", ex.getMessage());
        }
    }

    @Test
    @DisplayName("인증에 실패한 세션에서도 executeCommand 는 IllegalStateException")
    void executeAfterFailedAuth_throwsIllegalState() throws Exception {
        try (SshSession s = session("127.0.0.1", "hg4juser", "bad", null, null)) {
            assertThrows(SshAuthenticationException.class, () -> s.connect(TIMEOUT_MS));
            assertThrows(IllegalStateException.class, () -> s.executeCommand("echo", TIMEOUT_MS));
        }
    }

    @Test
    @DisplayName("close 는 연결 전에 호출해도 예외가 없다")
    void closeWithoutConnect_isNoOp() throws Exception {
        SshSession s = session("127.0.0.1", "hg4juser", "hg4jpass", null, null);
        assertDoesNotThrow(s::close);
    }

    @Test
    @DisplayName("close 는 두 번 호출해도 예외가 없다")
    void closeTwice_isNoOp() throws Exception {
        SshSession s = session("127.0.0.1", "hg4juser", "hg4jpass", null, null);
        s.connect(TIMEOUT_MS);
        s.close();
        assertDoesNotThrow(s::close);
    }

    @Test
    @DisplayName("존재하지 않는 개인키 경로는 IOException 으로 전파된다")
    void missingKeyFile_throwsIoException() throws Exception {
        try (SshSession s = session("127.0.0.1", "hg4juser", null, tempDir.resolve("nope").toString(), null)) {
            IOException ex = assertThrows(IOException.class, () -> s.connect(TIMEOUT_MS));
            assertTrue(ex.getMessage().contains("not found"), ex.getMessage());
        }
    }

    @Test
    @DisplayName("연결할 수 없는 포트는 인증 실패가 아닌 일반 예외로 전파된다")
    void unreachablePort_isNotReportedAsAuthenticationFailure() throws Exception {
        int closedPort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }
        SshSession s = new ApacheSshdSessionFactory()
                .createSession("127.0.0.1", closedPort, "hg4juser", "hg4jpass", null, null);
        try {
            Exception ex = assertThrows(Exception.class, () -> s.connect(2_000));
            assertFalse(ex instanceof SshAuthenticationException, ex.toString());
        } finally {
            s.close();
        }
    }

    // ───────────────────────── key authentication ─────────────────────────

    private Path generateKey(String name, String passphrase) throws Exception {
        Assumptions.assumeTrue(isSshKeygenAvailable(), "ssh-keygen is not available. Skipping.");
        Path key = tempDir.resolve(name);
        Process p = new ProcessBuilder("ssh-keygen", "-q", "-t", "ed25519", "-f", key.toString(),
                "-N", passphrase == null ? "" : passphrase).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor(), new String(out));
        return key;
    }

    private static boolean isSshKeygenAvailable() {
        try {
            return new ProcessBuilder("ssh-keygen", "-?").start().waitFor() >= 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void acceptOnlyKey(Path privateKey) {
        server.setPasswordAuthenticator((u, p, s) -> false);
        server.setPublickeyAuthenticator(new AuthorizedKeysAuthenticator(Path.of(privateKey + ".pub")));
    }

    @Test
    @DisplayName("암호문 없는 개인키로 공개키 인증이 된다")
    void keyAuth_withoutPassphrase() throws Exception {
        Path key = generateKey("id_plain", null);
        acceptOnlyKey(key);
        try (SshSession s = session("127.0.0.1", "hg4juser", null, key.toString(), null)) {
            s.connect(TIMEOUT_MS);
            s.executeCommand("echo", TIMEOUT_MS);
            s.getOutputStream().write("key-ok\n".getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            assertEquals("key-ok", readLine(s.getInputStream()));
        }
    }

    @Test
    @DisplayName("암호문이 걸린 개인키는 올바른 암호문으로 인증된다")
    void keyAuth_withCorrectPassphrase() throws Exception {
        Path key = generateKey("id_pass", "s3cr3tPassphrase");
        acceptOnlyKey(key);
        try (SshSession s = session("127.0.0.1", "hg4juser", null, key.toString(), "s3cr3tPassphrase")) {
            assertDoesNotThrow(() -> s.connect(TIMEOUT_MS));
        }
    }

    @Test
    @DisplayName("암호문이 틀리면 예외가 전파되고 연결이 성립하지 않는다")
    void keyAuth_withWrongPassphrase_fails() throws Exception {
        Path key = generateKey("id_pass2", "right-one");
        acceptOnlyKey(key);
        try (SshSession s = session("127.0.0.1", "hg4juser", null, key.toString(), "wrong-one")) {
            assertThrows(Exception.class, () -> s.connect(TIMEOUT_MS));
            assertThrows(IllegalStateException.class, () -> s.executeCommand("echo", TIMEOUT_MS));
        }
    }

    @Test
    @DisplayName("서버가 허용하지 않는 개인키는 SshAuthenticationException")
    void keyAuth_unauthorizedKey_fails() throws Exception {
        Path authorized = generateKey("id_authorized", null);
        Path stranger = generateKey("id_stranger", null);
        acceptOnlyKey(authorized);
        try (SshSession s = session("127.0.0.1", "hg4juser", null, stranger.toString(), null)) {
            assertThrows(SshAuthenticationException.class, () -> s.connect(TIMEOUT_MS));
        }
    }

    // ───────────────────────── known_hosts ─────────────────────────

    /** Linux routes all of 127.0.0.0/8 to loopback, so 127.0.0.2 is a "non local" name for hg4j's policy. */
    private static final String NON_LOCAL_LOOPBACK = "127.0.0.2";

    private String serverKeyLine(String hostSpec) throws Exception {
        KeyPair hostKey = server.getKeyPairProvider().loadKeys(null).iterator().next();
        return hostSpec + " " + PublicKeyEntry.toString(hostKey.getPublic()) + "\n";
    }

    @Test
    @DisplayName("known_hosts 가 있고 호스트가 미등록이면 로컬이 아닌 호스트는 거부된다")
    void knownHosts_unknownNonLocalHost_isRejected() throws Exception {
        Path knownHosts = Path.of(System.getProperty("user.home"), ".ssh", "known_hosts");
        Files.writeString(knownHosts, "");
        try (SshSession s = session(NON_LOCAL_LOOPBACK, "hg4juser", "hg4jpass", null, null)) {
            Exception ex = assertThrows(Exception.class, () -> s.connect(TIMEOUT_MS));
            assertFalse(ex instanceof SshAuthenticationException, ex.toString());
        }
    }

    @Test
    @DisplayName("known_hosts 에 서버 키가 등록되어 있으면 연결된다")
    void knownHosts_registeredKey_isAccepted() throws Exception {
        Path knownHosts = Path.of(System.getProperty("user.home"), ".ssh", "known_hosts");
        Files.writeString(knownHosts, serverKeyLine("[" + NON_LOCAL_LOOPBACK + "]:" + port));
        try (SshSession s = session(NON_LOCAL_LOOPBACK, "hg4juser", "hg4jpass", null, null)) {
            assertDoesNotThrow(() -> s.connect(TIMEOUT_MS));
        }
    }

    @Test
    @DisplayName("그룹 쓰기 권한(664)이 있는 known_hosts 도 읽어서 검증한다")
    void knownHosts_groupWritableFile_isStillRead() throws Exception {
        Path knownHosts = Path.of(System.getProperty("user.home"), ".ssh", "known_hosts");
        Files.writeString(knownHosts, serverKeyLine("[" + NON_LOCAL_LOOPBACK + "]:" + port));
        Files.setPosixFilePermissions(knownHosts, java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-r--"));
        try (SshSession s = session(NON_LOCAL_LOOPBACK, "hg4juser", "hg4jpass", null, null)) {
            assertDoesNotThrow(() -> s.connect(TIMEOUT_MS));
        }
    }

    @Test
    @DisplayName("known_hosts 에 다른 키가 등록되어 있으면(키 변경) 거부된다")
    void knownHosts_changedKey_isRejected() throws Exception {
        SimpleGeneratorHostKeyProvider other = new SimpleGeneratorHostKeyProvider(tempDir.resolve("other_host_key"));
        KeyPair otherKey = other.loadKeys(null).iterator().next();
        Path knownHosts = Path.of(System.getProperty("user.home"), ".ssh", "known_hosts");
        Files.writeString(knownHosts, "[" + NON_LOCAL_LOOPBACK + "]:" + port + " "
                + PublicKeyEntry.toString(otherKey.getPublic()) + "\n");
        try (SshSession s = session(NON_LOCAL_LOOPBACK, "hg4juser", "hg4jpass", null, null)) {
            assertThrows(Exception.class, () -> s.connect(TIMEOUT_MS));
        }
    }

    @Test
    @DisplayName("known_hosts 가 있어도 127.0.0.1 은 검증하지 않는다(기존 동작 유지)")
    void knownHosts_loopback_isNotVerified() throws Exception {
        Files.writeString(Path.of(System.getProperty("user.home"), ".ssh", "known_hosts"), "");
        try (SshSession s = session("127.0.0.1", "hg4juser", "hg4jpass", null, null)) {
            assertDoesNotThrow(() -> s.connect(TIMEOUT_MS));
        }
    }

    @Test
    @DisplayName("known_hosts 가 없으면 로컬이 아닌 호스트도 검증하지 않는다(기존 동작 유지)")
    void noKnownHosts_nonLocalHost_isNotVerified() throws Exception {
        try (SshSession s = session(NON_LOCAL_LOOPBACK, "hg4juser", "hg4jpass", null, null)) {
            assertDoesNotThrow(() -> s.connect(TIMEOUT_MS));
        }
    }

    // ───────────────────────── ~/.ssh/config ─────────────────────────

    @Test
    @DisplayName("~/.ssh/config 의 HostName/User 가 별칭 호스트에 적용된다")
    void sshConfig_hostNameAndUser_areApplied() throws Exception {
        Files.writeString(Path.of(System.getProperty("user.home"), ".ssh", "config"),
                "Host hgalias\n  HostName 127.0.0.1\n  User hg4juser\n");
        // user name is deliberately null: it can only come from the config file.
        try (SshSession s = session("hgalias", null, "hg4jpass", null, null)) {
            s.connect(TIMEOUT_MS);
            s.executeCommand("echo", TIMEOUT_MS);
            s.getOutputStream().write("alias-ok\n".getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            assertEquals("alias-ok", readLine(s.getInputStream()));
        }
    }

    @Test
    @DisplayName("~/.ssh/config 의 IdentityFile 이 인증에 쓰인다")
    void sshConfig_identityFile_isUsed() throws Exception {
        Path key = generateKey("id_cfg", null);
        acceptOnlyKey(key);
        Files.writeString(Path.of(System.getProperty("user.home"), ".ssh", "config"),
                "Host hgkey\n  HostName 127.0.0.1\n  User hg4juser\n  IdentityFile " + key + "\n");
        try (SshSession s = session("hgkey", null, null, null, null)) {
            assertDoesNotThrow(() -> s.connect(TIMEOUT_MS));
        }
    }

    @Test
    @DisplayName("포트가 미지정(0)이면 ~/.ssh/config 의 Port 가 쓰인다")
    void sshConfig_unspecifiedPort_usesConfigPort() throws Exception {
        Files.writeString(Path.of(System.getProperty("user.home"), ".ssh", "config"),
                "Host hgport\n  HostName 127.0.0.1\n  Port " + port + "\n  User hg4juser\n");
        try (SshSession s = new ApacheSshdSessionFactory().createSession("hgport", 0, null, "hg4jpass", null, null)) {
            assertDoesNotThrow(() -> s.connect(TIMEOUT_MS));
        }
    }

    @Test
    @DisplayName("호출자가 명시한 포트는 ~/.ssh/config 의 Port 보다 우선한다(ssh 와 같은 규칙)")
    void sshConfig_explicitPort_winsOverConfigPort() throws Exception {
        int closedPort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }
        Files.writeString(Path.of(System.getProperty("user.home"), ".ssh", "config"),
                "Host hgport\n  HostName 127.0.0.1\n  Port " + port + "\n  User hg4juser\n");
        SshSession s = new ApacheSshdSessionFactory()
                .createSession("hgport", closedPort, null, "hg4jpass", null, null);
        try {
            Exception ex = assertThrows(Exception.class, () -> s.connect(2_000));
            assertFalse(ex instanceof SshAuthenticationException, ex.toString());
        } finally {
            s.close();
        }
    }

    @Test
    @DisplayName("호출자가 명시한 사용자는 ~/.ssh/config 의 User 보다 우선한다(ssh 와 같은 규칙)")
    void sshConfig_explicitUser_winsOverConfigUser() throws Exception {
        Files.writeString(Path.of(System.getProperty("user.home"), ".ssh", "config"),
                "Host hguser\n  HostName 127.0.0.1\n  User someone-else\n");
        try (SshSession s = session("hguser", "hg4juser", "hg4jpass", null, null)) {
            assertDoesNotThrow(() -> s.connect(TIMEOUT_MS));
        }
    }

    @Test
    @DisplayName("사용자가 미지정(null)이고 설정 파일에도 없으면 로컬 사용자로 로그인을 시도한다")
    void unspecifiedUser_fallsBackToLocalUser() throws Exception {
        String localUser = System.getProperty("user.name");
        java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>();
        server.setPasswordAuthenticator((u, p, sess) -> {
            seen.set(u);
            return true;
        });
        try (SshSession s = session("127.0.0.1", null, "any", null, null)) {
            s.connect(TIMEOUT_MS);
        }
        assertEquals(localUser, seen.get());
    }

    /**
     * Scripted server-side command. {@code echo} copies stdin to stdout until EOF;
     * {@code stderr:<text>} writes the text to stderr and exits.
     */
    private static final class ScriptedCommand implements Command {
        private final String command;
        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback callback;
        private Thread thread;

        ScriptedCommand(String command) {
            this.command = command;
        }

        @Override public void setInputStream(InputStream in) { this.in = in; }
        @Override public void setOutputStream(OutputStream out) { this.out = out; }
        @Override public void setErrorStream(OutputStream err) { this.err = err; }
        @Override public void setExitCallback(ExitCallback callback) { this.callback = callback; }

        @Override
        public void start(ChannelSession channel, Environment env) {
            thread = new Thread(() -> {
                int code = 0;
                try {
                    if (command.startsWith("stderr:")) {
                        err.write(command.substring("stderr:".length()).getBytes(StandardCharsets.UTF_8));
                        err.flush();
                    } else {
                        byte[] buf = new byte[1024];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            out.write(buf, 0, n);
                            out.flush();
                        }
                    }
                } catch (IOException e) {
                    code = 1;
                } finally {
                    callback.onExit(code);
                }
            }, "scripted-ssh-command");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void destroy(ChannelSession channel) {
            if (thread != null) {
                thread.interrupt();
            }
        }
    }
}
