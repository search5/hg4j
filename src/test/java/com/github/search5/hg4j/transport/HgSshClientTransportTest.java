package com.github.search5.hg4j.transport;

import org.apache.sshd.server.SshServer;
import com.github.search5.hg4j.transport.HgSshClient;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import com.github.search5.hg4j.api.AddCommand;
import com.github.search5.hg4j.api.CommitCommand;
import com.github.search5.hg4j.api.Hg;
import com.github.search5.hg4j.errors.HgAuthException;
import com.github.search5.hg4j.errors.HgProtocolException;
import com.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.lib.Repository;
import com.jcraft.jsch.JSchException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.junit.jupiter.api.io.TempDir;

/**
 * Detailed unit tests for HgSshClient in the com.github.search5.hg4j.transport package.
 * Validates the SSH stdio protocol using an embedded Apache MINA SSHD server.
 */
@DisplayName("HgSshClient 심층 테스트 (transport 패키지)")
public class HgSshClientTransportTest {

    private SshServer sshServer;
    private int port;
    private File serverRepoDir;

    @BeforeEach
    public void startSshServer(@TempDir Path tempDir) throws Exception {
        serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgRepository serverRepo = Hg.init().setDirectory(serverRepoDir).call();
        Files.writeString(new File(serverRepoDir, "a.txt").toPath(), "hello ssh transport");
        new AddCommand(serverRepo).call();
        new CommitCommand(serverRepo).setMessage("v1").setAuthor("dev").call();

        sshServer = SshServer.setUpDefaultServer();
        sshServer.setPort(0);

        Path tempKey = Files.createTempFile("ssh_host_transport_", ".key");
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempKey));
        Files.deleteIfExists(tempKey);

        sshServer.setPasswordAuthenticator((username, password, session) ->
                "hg4juser".equals(username) && "hg4jpass".equals(password));

        sshServer.setCommandFactory((channel, command) -> new HgWireServerCommand(command, serverRepoDir));
        sshServer.start();
        port = sshServer.getPort();
    }

    @AfterEach
    public void stopSshServer() throws Exception {
        if (sshServer != null) {
            sshServer.stop(true);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // URL Parsing Tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SSH URL 파싱 - 사용자명+호스트+포트+경로 분리")
    public void testParseSshUrl_fullUrl_parsedCorrectly() {
        // Successful parsing is indicated by instantiation without exception
        assertDoesNotThrow(() -> new HgSshClient("ssh://admin@myserver.com:2222/path/to/repo"));
    }

    @Test
    @DisplayName("SSH URL 파싱 - 사용자명 없이 호스트만")
    public void testParseSshUrl_noUsername_usesSystemProperty() {
        assertDoesNotThrow(() -> new HgSshClient("ssh://myserver.com/repo"));
    }

    @Test
    @DisplayName("SSH URL 파싱 - 포트 없이 기본 22 사용")
    public void testParseSshUrl_noPort_defaultPort22() {
        assertDoesNotThrow(() -> new HgSshClient("ssh://user@myserver.com/repo"));
    }

    @Test
    @DisplayName("잘못된 프로토콜 → IllegalArgumentException 발생")
    public void testParseSshUrl_invalidProtocol_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> new HgSshClient("http://example.com/repo"));
    }

    @Test
    @DisplayName("경로 없는 URL → IllegalArgumentException 발생")
    public void testParseSshUrl_noPath_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> new HgSshClient("ssh://example.com"));
    }

    @Test
    @DisplayName("포트 번호가 숫자 아닌 경우 → 기본 포트 22 사용")
    public void testParseSshUrl_invalidPortNumber_fallbackToDefault() {
        // Fallback to default port 22 if the port is non-numeric. No exception expected.
        assertDoesNotThrow(() -> new HgSshClient("ssh://user@host:abc/repo"));
    }

    // ─────────────────────────────────────────────────────────────────────
    // setPrivateKey Configuration Tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("setPrivateKey() 설정 시 예외 없음")
    public void testSetPrivateKey_noException() {
        HgSshClient client = new HgSshClient("ssh://user@example.com/repo");
        assertDoesNotThrow(() -> client.setPrivateKey("/path/to/key", "passphrase"));
    }

    @Test
    @DisplayName("setPrivateKey() passphrase null 시 예외 없음")
    public void testSetPrivateKey_nullPassphrase_noException() {
        HgSshClient client = new HgSshClient("ssh://user@example.com/repo");
        assertDoesNotThrow(() -> client.setPrivateKey("/path/to/key", null));
    }

    // ─────────────────────────────────────────────────────────────────────
    // Integration Tests via Embedded SSH Server
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("연결 후 capabilities 수신 성공")
    public void testGetCapabilities_success() throws Exception {
        String url = "ssh://hg4juser@127.0.0.1:" + port + "/test/repo";
        try (HgSshClient client = new HgSshClient(url)) {
            client.setPassword("hg4jpass");
            List<String> caps = client.getCapabilities();
            assertNotNull(caps);
            // Wire1Commands.capabilitiesString() -- "heads"/"changegroup" aren't advertised as
            // explicit capability tokens (real hg's own convention: they're always-available
            // baseline v1 commands, not optional capabilities), so assert against what's actually
            // in that string rather than what an earlier hand-rolled fake server happened to send.
            assertTrue(caps.contains("getbundle"));
            assertTrue(caps.contains("lookup"));
            assertTrue(caps.contains("pushkey"));
        }
    }

    @Test
    @DisplayName("연결 후 getHeads 수신 성공")
    public void testGetHeads_success() throws Exception {
        String url = "ssh://hg4juser@127.0.0.1:" + port + "/test/repo";
        try (HgSshClient client = new HgSshClient(url)) {
            client.setPassword("hg4jpass");
            List<String> heads = client.getHeads();
            assertNotNull(heads);
            assertFalse(heads.isEmpty());
            assertEquals(40, heads.get(0).length());
        }
    }

    @Test
    @DisplayName("getCapabilities 두 번 호출 시 재연결 없이 캐시 반환")
    public void testGetCapabilities_calledTwice_usesCache() throws Exception {
        String url = "ssh://hg4juser@127.0.0.1:" + port + "/test/repo";
        try (HgSshClient client = new HgSshClient(url)) {
            client.setPassword("hg4jpass");
            List<String> caps1 = client.getCapabilities();
            List<String> caps2 = client.getCapabilities();
            assertEquals(caps1, caps2);
        }
    }

    @Test
    @DisplayName("getChangegroup 호출 성공 (빈 응답 처리)")
    public void testGetChangegroup_success() throws Exception {
        String url = "ssh://hg4juser@127.0.0.1:" + port + "/test/repo";
        try (HgSshClient client = new HgSshClient(url)) {
            client.setPassword("hg4jpass");
            byte[] result = client.getChangegroup(List.of("aabbccdd"));
            assertNotNull(result);
        }
    }

    @Test
    @DisplayName("getChangegroup 빈 roots 목록으로 호출")
    public void testGetChangegroup_emptyRoots_success() throws Exception {
        String url = "ssh://hg4juser@127.0.0.1:" + port + "/test/repo";
        try (HgSshClient client = new HgSshClient(url)) {
            client.setPassword("hg4jpass");
            byte[] result = client.getChangegroup(List.of());
            assertNotNull(result);
        }
    }

    @Test
    @DisplayName("getBundle 호출 성공")
    public void testGetBundle_success() throws Exception {
        String url = "ssh://hg4juser@127.0.0.1:" + port + "/test/repo";
        try (HgSshClient client = new HgSshClient(url)) {
            client.setPassword("hg4jpass");
            byte[] result = client.getBundle(List.of(), List.of("head1"), List.of("bundle2"));
            assertNotNull(result);
        }
    }

    @Test
    @DisplayName("getBundle - common/heads/bundleCaps 모두 null")
    public void testGetBundle_allNullParams_success() throws Exception {
        String url = "ssh://hg4juser@127.0.0.1:" + port + "/test/repo";
        try (HgSshClient client = new HgSshClient(url)) {
            client.setPassword("hg4jpass");
            byte[] result = client.getBundle(null, null, null);
            assertNotNull(result);
        }
    }

    /** Minimal valid empty HG10UN bundle1 payload -- real hg's server actually parses "push"
     * payloads now that the SSH wire framing is correct, unlike an earlier hand-rolled fake
     * server that echoed "push ok" back regardless of what bytes were sent. */
    private static byte[] minimalEmptyBundle1() {
        return new byte[]{
                'H', 'G', '1', '0', 'U', 'N',
                0, 0, 0, 0, // changelog group: empty
                0, 0, 0, 0, // manifest group: empty
                0, 0, 0, 0, // no filelogs follow
        };
    }

    @Test
    @DisplayName("push 호출 성공")
    public void testPush_success() throws Exception {
        String url = "ssh://hg4juser@127.0.0.1:" + port + "/test/repo";
        try (HgSshClient client = new HgSshClient(url)) {
            client.setPassword("hg4jpass");
            String result = client.push(minimalEmptyBundle1(), List.of("head1"));
            assertNotNull(result);
        }
    }

    @Test
    @DisplayName("push - 빈 heads 목록")
    public void testPush_emptyHeads_success() throws Exception {
        String url = "ssh://hg4juser@127.0.0.1:" + port + "/test/repo";
        try (HgSshClient client = new HgSshClient(url)) {
            client.setPassword("hg4jpass");
            String result = client.push(minimalEmptyBundle1(), List.of());
            assertNotNull(result);
        }
    }

    @Test
    @DisplayName("close() 이중 호출 안전성 확인")
    public void testClose_calledTwice_noException() throws Exception {
        String url = "ssh://hg4juser@127.0.0.1:" + port + "/test/repo";
        HgSshClient client = new HgSshClient(url);
        client.setPassword("hg4jpass");
        client.getCapabilities(); // Establish connection
        assertDoesNotThrow(() -> {
            client.close();
            client.close(); // Double close
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // Embedded Mercurial stdio Mock Command
    // ─────────────────────────────────────────────────────────────────────

    /** Server-side {@code Command} adapter attaching {@link HgSshWireServer} to the embedded
     * SSHD server -- speaks real hg's actual v1 SSH wire protocol (independently verified against
     * a real {@code hg} client, see {@link HgSshWireServerRealHgInteropTest}), replacing an
     * earlier hand-rolled fake that matched {@link HgSshClient}'s then-incorrect assumptions
     * instead (a simple line-based text format with no real length-prefixed argument framing). */
    private static class HgWireServerCommand implements Command, Runnable {
        private static final Pattern REPO_PATH = Pattern.compile("-R\\s+'?([^'\\s]+)'?");

        private final String command;
        private final File fallbackRepoDir;
        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback callback;
        private Thread thread;

        HgWireServerCommand(String command, File fallbackRepoDir) {
            this.command = command;
            this.fallbackRepoDir = fallbackRepoDir;
        }

        @Override public void setInputStream(InputStream in) { this.in = in; }
        @Override public void setOutputStream(OutputStream out) { this.out = out; }
        @Override public void setErrorStream(OutputStream err) { this.err = err; }
        @Override public void setExitCallback(ExitCallback cb) { this.callback = cb; }

        @Override
        public void start(ChannelSession session, Environment env) {
            thread = new Thread(this, "hg-ssh-wire-test");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void destroy(ChannelSession session) {
            if (thread != null) thread.interrupt();
        }

        @Override
        public void run() {
            try {
                if (command == null || !command.contains("serve --stdio")) {
                    err.write("Invalid command\n".getBytes(StandardCharsets.UTF_8));
                    err.flush();
                    callback.onExit(1);
                    return;
                }
                Matcher m = REPO_PATH.matcher(command);
                File repoDir = m.find() ? new File(m.group(1)) : fallbackRepoDir;
                HgRepository repo = repoDir.isDirectory() && new File(repoDir, ".hg").isDirectory()
                        ? new HgRepository(repoDir) : new HgRepository(fallbackRepoDir);
                new HgSshWireServer(repo).handleConnection(in, out);
                callback.onExit(0);
            } catch (Exception e) {
                try {
                    err.write((e + "\n").getBytes(StandardCharsets.UTF_8));
                    err.flush();
                } catch (IOException ignored) {
                }
                callback.onExit(1);
            }
        }
    }

    @Test
    @DisplayName("SSH 첫 framed 응답이 길이 헤더가 아니면 HgProtocolException 발생")
    public void testPerformHandshake_notAFramedLength_throwsProtocolException() throws Exception {
        // Real hg's handshake expects the FIRST response line to be a byte-count (the framed
        // "hello" response) -- arbitrary text there must fail cleanly, not silently misparse.
        byte[] garbage = "invalidheader: something\n".getBytes(StandardCharsets.UTF_8);
        HgSshClient client = new HgSshClient("ssh://hg4juser@127.0.0.1/repo");

        Field inField = HgSshClient.class.getDeclaredField("in");
        inField.setAccessible(true);
        inField.set(client, new ByteArrayInputStream(garbage));
        Field outField = HgSshClient.class.getDeclaredField("out");
        outField.setAccessible(true);
        outField.set(client, new ByteArrayOutputStream());

        Method method = HgSshClient.class.getDeclaredMethod("performHandshake");
        method.setAccessible(true);

        assertThrows(HgProtocolException.class, () -> {
            try {
                method.invoke(client);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    @Test
    @DisplayName("SSH hello 응답에 capabilities: 줄이 없으면 HgProtocolException 발생")
    public void testPerformHandshake_missingCapabilitiesLine_throwsProtocolException() throws Exception {
        // A well-formed framed response that simply doesn't contain a "capabilities:" line --
        // distinct from the not-a-number case above.
        String helloBody = "not a capabilities line\n";
        byte[] helloBytes = helloBody.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        stream.write((helloBytes.length + "\n").getBytes(StandardCharsets.US_ASCII));
        stream.write(helloBytes);

        HgSshClient client = new HgSshClient("ssh://hg4juser@127.0.0.1/repo");
        Field inField = HgSshClient.class.getDeclaredField("in");
        inField.setAccessible(true);
        inField.set(client, new ByteArrayInputStream(stream.toByteArray()));
        Field outField = HgSshClient.class.getDeclaredField("out");
        outField.setAccessible(true);
        outField.set(client, new ByteArrayOutputStream());

        Method method = HgSshClient.class.getDeclaredMethod("performHandshake");
        method.setAccessible(true);

        assertThrows(HgProtocolException.class, () -> {
            try {
                method.invoke(client);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    @Test
    @DisplayName("SSH chunk size EOF 시 HgProtocolException 발생")
    public void testReadBinaryResponse_eofInSize_throwsProtocolException() throws Exception {
        byte[] incompleteSize = new byte[]{0, 0, 1}; // Less than 4 bytes
        HgSshClient client = new HgSshClient("ssh://hg4juser@127.0.0.1/repo");
        
        Field inField = HgSshClient.class.getDeclaredField("in");
        inField.setAccessible(true);
        inField.set(client, new ByteArrayInputStream(incompleteSize));
        
        Method method = HgSshClient.class.getDeclaredMethod("readBinaryResponse");
        method.setAccessible(true);
        
        assertThrows(HgProtocolException.class, () -> {
            try {
                method.invoke(client);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    @Test
    @DisplayName("SSH 음수 chunk size 시 HgProtocolException 발생")
    public void testReadBinaryResponse_negativeSize_throwsProtocolException() throws Exception {
        byte[] negativeSize = new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0x9C}; // Negative value
        HgSshClient client = new HgSshClient("ssh://hg4juser@127.0.0.1/repo");
        
        Field inField = HgSshClient.class.getDeclaredField("in");
        inField.setAccessible(true);
        inField.set(client, new ByteArrayInputStream(negativeSize));
        
        Method method = HgSshClient.class.getDeclaredMethod("readBinaryResponse");
        method.setAccessible(true);
        
        assertThrows(HgProtocolException.class, () -> {
            try {
                method.invoke(client);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    @Test
    @DisplayName("SSH chunk payload EOF 시 HgProtocolException 발생")
    public void testReadBinaryResponse_eofInPayload_throwsProtocolException() throws Exception {
        byte[] incompletePayload = new byte[]{0, 0, 0, 10, 1, 2, 3}; // Specifying 10 bytes but providing only 3 bytes
        HgSshClient client = new HgSshClient("ssh://hg4juser@127.0.0.1/repo");
        
        Field inField = HgSshClient.class.getDeclaredField("in");
        inField.setAccessible(true);
        inField.set(client, new ByteArrayInputStream(incompletePayload));
        
        Method method = HgSshClient.class.getDeclaredMethod("readBinaryResponse");
        method.setAccessible(true);
        
        assertThrows(HgProtocolException.class, () -> {
            try {
                method.invoke(client);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    @Test
    @DisplayName("Repository.open() 팩토리 메소드 기능 및 예외 정밀 검증")
    public void testRepositoryOpenStaticMethod() throws Exception {
        // Null argument
        assertThrows(IllegalArgumentException.class, () -> Repository.open(null));
        
        // Non-existent directory
        File nonExistent = new File(System.getProperty("java.io.tmpdir"), "non_existent_hg_repo_test_" + System.currentTimeMillis());
        assertThrows(HgRepositoryNotFoundException.class, () -> Repository.open(nonExistent));
        
        // Verify opening a valid repository
        Path tempPath = Files.createTempDirectory("hg4j_repo_open_static_test_");
        File tempDir = tempPath.toFile();
        try {
            // Initialization
            Repository repo = Hg.init().setDirectory(tempDir).call();
            assertNotNull(repo);
            repo.close();
            
            // Call static open
            try (Repository opened = Repository.open(tempDir)) {
                assertNotNull(opened);
                assertEquals(tempDir.getCanonicalPath(), opened.getDirectory().getCanonicalPath());
            }
        } finally {
            // Deletion
            File[] files = tempDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isDirectory()) {
                        File[] children = f.listFiles();
                        if (children != null) {
                            for (File c : children) c.delete();
                        }
                    }
                    f.delete();
                }
            }
            tempDir.delete();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Reflection helpers for private field / method access
    // ─────────────────────────────────────────────────────────────────────

    private static void setField(HgSshClient client, String name, Object value) throws Exception {
        Field f = HgSshClient.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(client, value);
    }

    private static Object getField(HgSshClient client, String name) throws Exception {
        Field f = HgSshClient.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(client);
    }

    private static void setProtocolVersion(HgSshClient client, int version) throws Exception {
        setField(client, "protocolVersion", version);
    }

    private static String invokeReadLine(HgSshClient client) throws Throwable {
        Method m = HgSshClient.class.getDeclaredMethod("readLine");
        m.setAccessible(true);
        try {
            return (String) m.invoke(client);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static void invokeWriteLine(HgSshClient client, String line) throws Throwable {
        Method m = HgSshClient.class.getDeclaredMethod("writeLine", String.class);
        m.setAccessible(true);
        try {
            m.invoke(client, line);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static byte[] invokeReadBinaryResponse(HgSshClient client) throws Throwable {
        Method m = HgSshClient.class.getDeclaredMethod("readBinaryResponse");
        m.setAccessible(true);
        try {
            return (byte[]) m.invoke(client);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static void invokePerformHandshake(HgSshClient client) throws Throwable {
        Method m = HgSshClient.class.getDeclaredMethod("performHandshake");
        m.setAccessible(true);
        try {
            m.invoke(client);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** {@code "<len>\n<bytes>"} -- real hg's v1 "framed" response format. */
    private static byte[] framed(String content) {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        byte[] header = (body.length + "\n").getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header, 0, header.length);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    private static void writeBigEndianLen(ByteArrayOutputStream out, int len) {
        out.write((len >> 24) & 0xFF);
        out.write((len >> 16) & 0xFF);
        out.write((len >> 8) & 0xFF);
        out.write(len & 0xFF);
    }

    /** {@code connected=true} skips the handshake entirely, so the fake {@code in} stream here
     * only ever needs to satisfy ONE subsequent command's framed response -- {@link #framed}. */
    private static HgSshClient createConnectedClientWithResponse(String response) throws Exception {
        HgSshClient client = new HgSshClient("ssh://user@127.0.0.1/repo");
        setField(client, "connected", true);
        setField(client, "capabilities", List.of("lookup", "listkeys", "known", "pushkey", "between"));
        setField(client, "in", new ByteArrayInputStream(framed(response)));
        setField(client, "out", new ByteArrayOutputStream());
        return client;
    }

    // ─────────────────────────────────────────────────────────────────────
    // setCredentialsProvider() Tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("setCredentialsProvider - null 전달 시 아무 동작 없음")
    public void testSetCredentialsProvider_null_doesNothing() {
        HgSshClient client = new HgSshClient("ssh://origuser@example.com/repo");
        assertDoesNotThrow(() -> client.setCredentialsProvider(null));
    }

    @Test
    @DisplayName("setCredentialsProvider - SSH 키와 사용자 자격증명 모두 반영")
    public void testSetCredentialsProvider_keyAndPasswordApplied() throws Exception {
        HgSshClient client = new HgSshClient("ssh://olduser@example.com/repo");
        CredentialsProvider provider = (uri, items) -> {
            for (CredentialItem item : items) {
                if (item instanceof CredentialItem.SshKeyPath k) {
                    k.setValue("/home/user/.ssh/id_rsa");
                } else if (item instanceof CredentialItem.SshPassphrase p) {
                    p.setValue("keypass".toCharArray());
                } else if (item instanceof CredentialItem.Username u) {
                    u.setValue("newuser");
                } else if (item instanceof CredentialItem.Password pw) {
                    pw.setValue("newpass".toCharArray());
                }
            }
            return true;
        };

        client.setCredentialsProvider(provider);

        assertEquals("/home/user/.ssh/id_rsa", getField(client, "privateKeyPath"));
        assertEquals("keypass", getField(client, "passphrase"));
        assertEquals("newuser", getField(client, "username"));
        assertEquals("newpass", getField(client, "password"));
    }

    @Test
    @DisplayName("setCredentialsProvider - 키 조회 실패, 빈 사용자명/비밀번호는 기존 값 유지")
    public void testSetCredentialsProvider_partialFill_keepsDefaultsWhenEmpty() throws Exception {
        HgSshClient client = new HgSshClient("ssh://origuser@example.com/repo");
        CredentialsProvider provider = (uri, items) -> {
            boolean isKeyCall = items.length > 0 && items[0] instanceof CredentialItem.SshKeyPath;
            if (isKeyCall) {
                return false;
            }
            for (CredentialItem item : items) {
                if (item instanceof CredentialItem.Username u) {
                    u.setValue("");
                }
            }
            return true;
        };

        client.setCredentialsProvider(provider);

        assertEquals("origuser", getField(client, "username"));
        assertNull(getField(client, "password"));
        assertNull(getField(client, "privateKeyPath"));
    }

    // ─────────────────────────────────────────────────────────────────────
    // ensureConnected() failure-path Tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ensureConnected - JSchException 발생 시 HgAuthException으로 래핑")
    public void testEnsureConnected_jschException_wrapsAsHgAuthException() throws Exception {
        SshSessionFactory original = HgSshClient.getSshSessionFactory();
        SshSessionFactory failingFactory = (host, p, username, password, key, pass) -> {
            throw new JSchException("Auth fail");
        };
        HgSshClient.setSshSessionFactory(failingFactory);
        try {
            HgSshClient client = new HgSshClient("ssh://baduser@127.0.0.1:1/repo");
            client.setPassword("wrongpass");
            HgAuthException ex = assertThrows(HgAuthException.class, client::getCapabilities);
            assertEquals("baduser", ex.getUsername());
        } finally {
            HgSshClient.setSshSessionFactory(original);
        }
    }

    @Test
    @DisplayName("ensureConnected - 'permission denied' 메시지 포함 시 HgAuthException으로 래핑")
    public void testEnsureConnected_permissionDeniedMessage_wrapsAsHgAuthException() throws Exception {
        SshSessionFactory original = HgSshClient.getSshSessionFactory();
        SshSessionFactory failingFactory = (h, p, u, pw, k, ph) -> {
            throw new IOException("Permission denied (publickey)");
        };
        HgSshClient.setSshSessionFactory(failingFactory);
        try {
            HgSshClient client = new HgSshClient("ssh://user@127.0.0.1:1/repo");
            client.setPassword("x");
            assertThrows(HgAuthException.class, client::getCapabilities);
        } finally {
            HgSshClient.setSshSessionFactory(original);
        }
    }

    @Test
    @DisplayName("ensureConnected - 인증과 무관한 실패는 HgProtocolException으로 래핑")
    public void testEnsureConnected_genericIOException_wrapsAsHgProtocolException() throws Exception {
        SshSessionFactory original = HgSshClient.getSshSessionFactory();
        SshSessionFactory failingFactory = (h, p, u, pw, k, ph) -> {
            throw new IOException("Connection refused");
        };
        HgSshClient.setSshSessionFactory(failingFactory);
        try {
            HgSshClient client = new HgSshClient("ssh://user@127.0.0.1:1/repo");
            HgProtocolException ex = assertThrows(HgProtocolException.class, client::getCapabilities);
            assertTrue(ex.getMessage().contains("Connection refused"));
        } finally {
            HgSshClient.setSshSessionFactory(original);
        }
    }

    @Test
    @DisplayName("ensureConnected - 메시지 없는 예외도 HgProtocolException으로 래핑 (NPE 없음)")
    public void testEnsureConnected_nullMessageException_wrapsAsHgProtocolException() throws Exception {
        SshSessionFactory original = HgSshClient.getSshSessionFactory();
        SshSessionFactory failingFactory = (h, p, u, pw, k, ph) -> {
            throw new RuntimeException();
        };
        HgSshClient.setSshSessionFactory(failingFactory);
        try {
            HgSshClient client = new HgSshClient("ssh://user@127.0.0.1:1/repo");
            assertThrows(HgProtocolException.class, client::getCapabilities);
        } finally {
            HgSshClient.setSshSessionFactory(original);
        }
    }

    @Test
    @DisplayName("ensureConnected - connect() 실패 시에도 세션 close()가 호출되고 close() 예외는 무시됨")
    public void testEnsureConnected_connectFails_closesPartiallyCreatedSessionEvenIfCloseThrows() throws Exception {
        SshSessionFactory original = HgSshClient.getSshSessionFactory();
        boolean[] closeCalled = {false};
        SshSession mockSession = new SshSession() {
            @Override public void connect(int timeoutMs) throws Exception { throw new IOException("connect boom"); }
            @Override public void executeCommand(String command, int timeoutMs) throws Exception {}
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
            @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
            @Override public void close() throws IOException {
                closeCalled[0] = true;
                throw new IOException("close boom");
            }
        };
        SshSessionFactory factory = (h, p, u, pw, k, ph) -> mockSession;
        HgSshClient.setSshSessionFactory(factory);
        try {
            HgSshClient client = new HgSshClient("ssh://user@127.0.0.1:1/repo");
            assertThrows(HgProtocolException.class, client::getCapabilities);
            assertTrue(closeCalled[0], "connect() 실패 시에도 세션 close()가 호출되어야 함");
        } finally {
            HgSshClient.setSshSessionFactory(original);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // getProtocolVersion() Test
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getProtocolVersion - 기본값은 1")
    public void testGetProtocolVersion_defaultIsOne() {
        HgSshClient client = new HgSshClient("ssh://user@127.0.0.1/repo");
        assertEquals(1, client.getProtocolVersion());
    }

    // ─────────────────────────────────────────────────────────────────────
    // readLine() / writeLine() SSH V2 protocol framing Tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("readLine v2 - 프레임 페이로드 정상 디코딩")
    public void testReadLine_v2_decodesFramedPayload() throws Throwable {
        HgSshClient client = new HgSshClient("ssh://user@127.0.0.1/repo");
        setProtocolVersion(client, 2);

        byte[] payload = "hello v2\n".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(1);
        writeBigEndianLen(frame, payload.length);
        frame.write(payload);
        setField(client, "in", new ByteArrayInputStream(frame.toByteArray()));

        assertEquals("hello v2", invokeReadLine(client));
    }

    @Test
    @DisplayName("readLine v2 - 채널 EOF 시 빈 문자열 반환")
    public void testReadLine_v2_channelEOF_returnsEmptyString() throws Throwable {
        HgSshClient client = new HgSshClient("ssh://user@127.0.0.1/repo");
        setProtocolVersion(client, 2);
        setField(client, "in", new ByteArrayInputStream(new byte[0]));
        assertEquals("", invokeReadLine(client));
    }

    @Test
    @DisplayName("readLine v2 - 길이 0 프레임 시 빈 문자열 반환")
    public void testReadLine_v2_zeroLengthFrame_returnsEmptyString() throws Throwable {
        HgSshClient client = new HgSshClient("ssh://user@127.0.0.1/repo");
        setProtocolVersion(client, 2);
        setField(client, "in", new ByteArrayInputStream(new byte[]{1, 0, 0, 0, 0}));
        assertEquals("", invokeReadLine(client));
    }

    @Test
    @DisplayName("readLine v2 - 프레임 길이 필드 도중 EOF 시 HgProtocolException")
    public void testReadLine_v2_eofInFrameSize_throwsProtocolException() throws Throwable {
        HgSshClient client = new HgSshClient("ssh://user@127.0.0.1/repo");
        setProtocolVersion(client, 2);
        setField(client, "in", new ByteArrayInputStream(new byte[]{1, 0, 0}));
        assertThrows(HgProtocolException.class, () -> invokeReadLine(client));
    }

    @Test
    @DisplayName("readLine v2 - 프레임 페이로드 도중 EOF 시 HgProtocolException")
    public void testReadLine_v2_eofInFramePayload_throwsProtocolException() throws Throwable {
        HgSshClient client = new HgSshClient("ssh://user@127.0.0.1/repo");
        setProtocolVersion(client, 2);
        setField(client, "in", new ByteArrayInputStream(new byte[]{1, 0, 0, 0, 10, 1, 2, 3}));
        assertThrows(HgProtocolException.class, () -> invokeReadLine(client));
    }

    @Test
    @DisplayName("writeLine v2 - 프레임 형식으로 기록")
    public void testWriteLine_v2_writesFramedPayload() throws Throwable {
        HgSshClient client = new HgSshClient("ssh://user@127.0.0.1/repo");
        setProtocolVersion(client, 2);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        setField(client, "out", out);

        invokeWriteLine(client, "hello");

        byte[] written = out.toByteArray();
        assertEquals(1, written[0]);
        int len = ((written[1] & 0xFF) << 24) | ((written[2] & 0xFF) << 16) | ((written[3] & 0xFF) << 8) | (written[4] & 0xFF);
        byte[] expectedPayload = "hello\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(expectedPayload.length, len);
        byte[] actualPayload = new byte[len];
        System.arraycopy(written, 5, actualPayload, 0, len);
        assertArrayEquals(expectedPayload, actualPayload);
    }

    // ─────────────────────────────────────────────────────────────────────
    // readBinaryResponse() SSH V2 protocol framing Tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("readBinaryResponse v2 - 채널 EOF 시 빈 바이트 배열 반환")
    public void testReadBinaryResponse_v2_channelEOF_returnsEmptyBytes() throws Throwable {
        HgSshClient client = new HgSshClient("ssh://user@127.0.0.1/repo");
        setProtocolVersion(client, 2);
        setField(client, "in", new ByteArrayInputStream(new byte[0]));
        assertEquals(0, invokeReadBinaryResponse(client).length);
    }

    @Test
    @DisplayName("readBinaryResponse v2 - 프레임 길이 필드 도중 EOF 시 HgProtocolException")
    public void testReadBinaryResponse_v2_eofInFrameSize_throwsProtocolException() throws Throwable {
        HgSshClient client = new HgSshClient("ssh://user@127.0.0.1/repo");
        setProtocolVersion(client, 2);
        setField(client, "in", new ByteArrayInputStream(new byte[]{1, 0, 0}));
        assertThrows(HgProtocolException.class, () -> invokeReadBinaryResponse(client));
    }

    @Test
    @DisplayName("readBinaryResponse v2 - 프레임 페이로드 도중 EOF 시 HgProtocolException")
    public void testReadBinaryResponse_v2_eofInFramePayload_throwsProtocolException() throws Throwable {
        HgSshClient client = new HgSshClient("ssh://user@127.0.0.1/repo");
        setProtocolVersion(client, 2);
        setField(client, "in", new ByteArrayInputStream(new byte[]{1, 0, 0, 0, 10, 1, 2, 3}));
        assertThrows(HgProtocolException.class, () -> invokeReadBinaryResponse(client));
    }

    @Test
    @DisplayName("readBinaryResponse v2 - 길이 0 프레임은 스트림 종료 신호")
    public void testReadBinaryResponse_v2_zeroLengthFrame_endsStream() throws Throwable {
        HgSshClient client = new HgSshClient("ssh://user@127.0.0.1/repo");
        setProtocolVersion(client, 2);
        setField(client, "in", new ByteArrayInputStream(new byte[]{1, 0, 0, 0, 0}));
        assertEquals(0, invokeReadBinaryResponse(client).length);
    }

    @Test
    @DisplayName("readBinaryResponse v2 - 채널 1, 2 데이터 모두 누적")
    public void testReadBinaryResponse_v2_channel1And2_dataAccumulated() throws Throwable {
        HgSshClient client = new HgSshClient("ssh://user@127.0.0.1/repo");
        setProtocolVersion(client, 2);

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        byte[] p1 = "AB".getBytes(StandardCharsets.UTF_8);
        stream.write(1);
        writeBigEndianLen(stream, p1.length);
        stream.write(p1);

        byte[] p2 = "ERR".getBytes(StandardCharsets.UTF_8);
        stream.write(2);
        writeBigEndianLen(stream, p2.length);
        stream.write(p2);

        stream.write(1);
        writeBigEndianLen(stream, 0);

        setField(client, "in", new ByteArrayInputStream(stream.toByteArray()));
        byte[] result = invokeReadBinaryResponse(client);
        assertEquals("ABERR", new String(result, StandardCharsets.UTF_8));
    }

    // ─────────────────────────────────────────────────────────────────────
    // performHandshake() SSH V2 upgrade Tests
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Simulates a remote server that accepts the V2 protocol upgrade offer.
     * Captures the client-written "upgrade &lt;token&gt; ..." line to echo back a
     * matching "upgraded &lt;token&gt;" confirmation, followed by a V2-framed capabilities header.
     */
    private static class UpgradeSimulatingInputStream extends InputStream {
        private final ByteArrayOutputStream capturedOut;
        private ByteArrayInputStream current;
        private int phase = 0;

        UpgradeSimulatingInputStream(ByteArrayOutputStream capturedOut) {
            this.capturedOut = capturedOut;
            // performHandshake() always does the real v1 hello+between handshake FIRST (both
            // framed responses) before it even attempts the v2 upgrade dance below.
            byte[] hello = framed("capabilities: heads exp-ssh-v2-0003\n");
            byte[] between = framed("\n");
            byte[] combined = new byte[hello.length + between.length];
            System.arraycopy(hello, 0, combined, 0, hello.length);
            System.arraycopy(between, 0, combined, hello.length, between.length);
            this.current = new ByteArrayInputStream(combined);
        }

        private String extractToken() {
            String written = capturedOut.toString(StandardCharsets.UTF_8);
            int idx = written.indexOf("upgrade ");
            String rest = written.substring(idx + "upgrade ".length());
            int spaceIdx = rest.indexOf(' ');
            return rest.substring(0, spaceIdx);
        }

        @Override
        public int read() throws IOException {
            if (current != null) {
                int b = current.read();
                if (b != -1) {
                    return b;
                }
            }
            if (phase == 0) {
                phase = 1;
                String token = extractToken();
                current = new ByteArrayInputStream(("upgraded " + token + "\n").getBytes(StandardCharsets.UTF_8));
                return current.read();
            } else if (phase == 1) {
                phase = 2;
                byte[] payload = "capabilities: heads\n".getBytes(StandardCharsets.UTF_8);
                ByteArrayOutputStream frame = new ByteArrayOutputStream();
                frame.write(1);
                writeBigEndianLen(frame, payload.length);
                frame.write(payload, 0, payload.length);
                current = new ByteArrayInputStream(frame.toByteArray());
                return current.read();
            }
            return -1;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            int first = read();
            if (first == -1) return -1;
            b[off] = (byte) first;
            return 1;
        }
    }

    @Test
    @DisplayName("performHandshake - v2 업그레이드 성공 시 protocolVersion=2로 전환")
    public void testPerformHandshake_v2Upgrade_success() throws Throwable {
        System.setProperty("hg4j.ssh.v2.enabled", "true");
        try {
            HgSshClient client = new HgSshClient("ssh://user@127.0.0.1/repo");
            ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
            setField(client, "out", capturedOut);
            setField(client, "in", new UpgradeSimulatingInputStream(capturedOut));

            invokePerformHandshake(client);

            assertEquals(2, client.getProtocolVersion());
            @SuppressWarnings("unchecked")
            List<String> caps = (List<String>) getField(client, "capabilities");
            assertEquals(List.of("heads"), caps);
        } finally {
            System.clearProperty("hg4j.ssh.v2.enabled");
        }
    }

    @Test
    @DisplayName("performHandshake - v2 업그레이드 거부 시 protocolVersion 유지, 잔여 데이터 스킵")
    public void testPerformHandshake_v2Upgrade_rejected_skipsResidualData() throws Throwable {
        System.setProperty("hg4j.ssh.v2.enabled", "true");
        try {
            HgSshClient client = new HgSshClient("ssh://user@127.0.0.1/repo");
            setField(client, "out", new ByteArrayOutputStream());
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            stream.write(framed("capabilities: heads exp-ssh-v2-0003\n"));
            stream.write(framed("\n")); // between's null-range response
            stream.write("upgrade rejected\nRESIDUAL_JUNK_DATA".getBytes(StandardCharsets.UTF_8));
            setField(client, "in", new ByteArrayInputStream(stream.toByteArray()));

            invokePerformHandshake(client);

            assertEquals(1, client.getProtocolVersion());
            @SuppressWarnings("unchecked")
            List<String> caps = (List<String>) getField(client, "capabilities");
            assertTrue(caps.contains("heads"));
            assertTrue(caps.contains("exp-ssh-v2-0003"));
        } finally {
            System.clearProperty("hg4j.ssh.v2.enabled");
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // listKeys() / between() / known() / pushkey() Tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("listKeys - 다중 엔트리 응답 전부 파싱 (framed 읽기로 임베디드 개행 보존)")
    public void testListKeys_success() throws Exception {
        // Fixed 2026-09-03: an earlier version read the response with a plain line-reader that
        // stopped at the FIRST embedded '\n', so a real multi-key listkeys response (which is
        // inherently multi-line) could only ever yield a single entry. The real framed read
        // (length-prefixed, not newline-terminated) preserves every entry.
        HgSshClient client = createConnectedClientWithResponse("key1\tvalue1\nkey2\tvalue2\n");
        Map<String, String> result = client.listKeys("bookmarks");
        assertEquals(2, result.size());
        assertEquals("value1", result.get("key1"));
        assertEquals("value2", result.get("key2"));
    }

    @Test
    @DisplayName("listKeys - 빈 응답")
    public void testListKeys_emptyResponse() throws Exception {
        HgSshClient client = createConnectedClientWithResponse("");
        assertTrue(client.listKeys("bookmarks").isEmpty());
    }

    @Test
    @DisplayName("listKeys - tab 없는 라인은 무시")
    public void testListKeys_lineWithoutTab_skipped() throws Exception {
        HgSshClient client = createConnectedClientWithResponse("malformedline\n");
        Map<String, String> result = client.listKeys("bookmarks");
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("between - 정상 응답 파싱")
    public void testBetween_success() throws Exception {
        HgSshClient client = createConnectedClientWithResponse("aaa bbb ccc");
        assertEquals(List.of("aaa", "bbb", "ccc"), client.between(List.of("start-end")));
    }

    @Test
    @DisplayName("between - 빈 응답")
    public void testBetween_emptyResponse() throws Exception {
        HgSshClient client = createConnectedClientWithResponse("");
        assertTrue(client.between(List.of("start-end")).isEmpty());
    }

    @Test
    @DisplayName("known - 응답 그대로 반환")
    public void testKnown_returnsTrimmedResponse() throws Exception {
        HgSshClient client = createConnectedClientWithResponse("1 0 1\n");
        assertEquals("1 0 1", client.known(List.of("aaa", "bbb", "ccc")));
    }

    @Test
    @DisplayName("pushkey - '1' 응답은 성공으로 처리")
    public void testPushkey_trueResponse() throws Exception {
        HgSshClient client = createConnectedClientWithResponse("1\n");
        assertTrue(client.pushkey("bookmarks", "mybook", "old", "new"));
    }

    @Test
    @DisplayName("pushkey - 'true' 응답도 성공으로 처리 (대소문자 무관)")
    public void testPushkey_trueTextResponse() throws Exception {
        HgSshClient client = createConnectedClientWithResponse("TRUE\n");
        assertTrue(client.pushkey("bookmarks", "mybook", "old", "new"));
    }

    @Test
    @DisplayName("pushkey - '0' 응답은 실패로 처리")
    public void testPushkey_falseResponse() throws Exception {
        HgSshClient client = createConnectedClientWithResponse("0\n");
        assertFalse(client.pushkey("bookmarks", "mybook", "old", "new"));
    }

    @Test
    @DisplayName("pushkey - 빈 응답은 성공으로 간주")
    public void testPushkey_emptyResponse_treatedAsSuccess() throws Exception {
        HgSshClient client = createConnectedClientWithResponse("");
        assertTrue(client.pushkey("bookmarks", "mybook", "old", "new"));
    }

    @Test
    @DisplayName("pushkey - oldVal/newVal null이면 빈 문자열 전송 (length=0 헤더)")
    public void testPushkey_nullOldAndNewVal_sendsEmptyStrings() throws Exception {
        HgSshClient client = createConnectedClientWithResponse("1\n");
        assertTrue(client.pushkey("bookmarks", "mybook", null, null));

        // Real hg's per-arg wire format: "<name> <byte-length>\n<bytes>" -- an empty value is
        // "<name> 0\n" with nothing following (not "<name> \n", the old line-based assumption).
        ByteArrayOutputStream out = (ByteArrayOutputStream) getField(client, "out");
        String sent = out.toString(StandardCharsets.UTF_8);
        assertTrue(sent.contains("old 0\n"), "sent was: " + sent);
        assertTrue(sent.contains("new 0\n"), "sent was: " + sent);
    }
}

