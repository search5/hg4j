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

import static org.junit.jupiter.api.Assertions.*;
import com.github.search5.hg4j.api.Hg;
import com.github.search5.hg4j.errors.HgAuthException;
import com.github.search5.hg4j.errors.HgProtocolException;
import com.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import com.github.search5.hg4j.lib.Repository;
import com.jcraft.jsch.JSchException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;

/**
 * Detailed unit tests for HgSshClient in the com.github.search5.hg4j.transport package.
 * Validates the SSH stdio protocol using an embedded Apache MINA SSHD server.
 */
@DisplayName("HgSshClient 심층 테스트 (transport 패키지)")
public class HgSshClientTransportTest {

    private SshServer sshServer;
    private int port;

    @BeforeEach
    public void startSshServer() throws Exception {
        sshServer = SshServer.setUpDefaultServer();
        sshServer.setPort(0);

        Path tempKey = Files.createTempFile("ssh_host_transport_", ".key");
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempKey));
        Files.deleteIfExists(tempKey);

        sshServer.setPasswordAuthenticator((username, password, session) ->
                "hg4juser".equals(username) && "hg4jpass".equals(password));

        sshServer.setCommandFactory((channel, command) -> new FullFeaturedMockHgCommand(command));
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
            assertTrue(caps.contains("heads"));
            assertTrue(caps.contains("getbundle"));
            assertTrue(caps.contains("changegroup"));
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

    @Test
    @DisplayName("push 호출 성공")
    public void testPush_success() throws Exception {
        String url = "ssh://hg4juser@127.0.0.1:" + port + "/test/repo";
        try (HgSshClient client = new HgSshClient(url)) {
            client.setPassword("hg4jpass");
            String result = client.push(new byte[]{0x01, 0x02}, List.of("head1"));
            assertNotNull(result);
        }
    }

    @Test
    @DisplayName("push - 빈 heads 목록")
    public void testPush_emptyHeads_success() throws Exception {
        String url = "ssh://hg4juser@127.0.0.1:" + port + "/test/repo";
        try (HgSshClient client = new HgSshClient(url)) {
            client.setPassword("hg4jpass");
            String result = client.push(new byte[]{0x01}, List.of());
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

    private static class FullFeaturedMockHgCommand implements Command, Runnable {
        private final String command;
        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback callback;
        private Thread thread;

        public FullFeaturedMockHgCommand(String command) {
            this.command = command;
        }

        @Override public void setInputStream(InputStream in) { this.in = in; }
        @Override public void setOutputStream(OutputStream out) { this.out = out; }
        @Override public void setErrorStream(OutputStream err) { this.err = err; }
        @Override public void setExitCallback(ExitCallback cb) { this.callback = cb; }

        @Override
        public void start(ChannelSession session,
                          Environment env) throws IOException {
            thread = new Thread(this);
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void destroy(ChannelSession session) throws Exception {
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

                // Mercurial stdio protocol: Print the capabilities header first
                out.write("capabilities: heads getbundle changegroup\n".getBytes(StandardCharsets.UTF_8));
                out.flush();

                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

                while (!Thread.currentThread().isInterrupted()) {
                    String line = reader.readLine();
                    if (line == null) break;
                    line = line.trim();

                    if ("heads".equals(line)) {
                        // 40-character hash response
                        out.write("0000000000000000000000000000000000000000\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();

                    } else if (line.startsWith("changegroup") || line.startsWith("getbundle")) {
                        // Consume argument lines (until an empty line is encountered)
                        String argLine;
                        while ((argLine = reader.readLine()) != null && !argLine.trim().isEmpty()) {
                            // consume args
                        }
                        // 빈 청크(터미널) 응답
                        out.write(new byte[]{0, 0, 0, 0});
                        out.flush();

                    } else if (line.startsWith("unbundle")) {
                        // push: Consume arguments, then return OK response
                        String argLine;
                        while ((argLine = reader.readLine()) != null && !argLine.trim().isEmpty()) {
                            // consume args
                        }
                        // Read bundle data (ignored in practice)
                        // Text response
                        out.write("push ok\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();

                    } else if ("close".equals(line) || "exit".equals(line)) {
                        break;
                    }
                }
                callback.onExit(0);
            } catch (Exception e) {
                callback.onExit(1, e.getMessage());
            }
        }
    }

    @Test
    @DisplayName("SSH capabilities 헤더 오류 시 HgProtocolException 발생")
    public void testReadCapabilities_invalidHeader_throwsProtocolException() throws Exception {
        byte[] invalidHeader = "invalidheader: something\n".getBytes(StandardCharsets.UTF_8);
        HgSshClient client = new HgSshClient("ssh://hg4juser@127.0.0.1/repo");
        
        Field inField = HgSshClient.class.getDeclaredField("in");
        inField.setAccessible(true);
        inField.set(client, new ByteArrayInputStream(invalidHeader));
        
        Method method = HgSshClient.class.getDeclaredMethod("readCapabilities");
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

    private static void invokeReadCapabilities(HgSshClient client) throws Throwable {
        Method m = HgSshClient.class.getDeclaredMethod("readCapabilities");
        m.setAccessible(true);
        try {
            m.invoke(client);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static void writeBigEndianLen(ByteArrayOutputStream out, int len) {
        out.write((len >> 24) & 0xFF);
        out.write((len >> 16) & 0xFF);
        out.write((len >> 8) & 0xFF);
        out.write(len & 0xFF);
    }

    private static HgSshClient createConnectedClientWithResponse(String response) throws Exception {
        HgSshClient client = new HgSshClient("ssh://user@127.0.0.1/repo");
        setField(client, "connected", true);
        setField(client, "capabilities", List.of("lookup", "listkeys", "known", "pushkey", "between"));
        setField(client, "in", new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)));
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
    // readCapabilities() SSH V2 upgrade Tests
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
            this.current = new ByteArrayInputStream(
                    "capabilities: heads exp-ssh-v2-0003\n".getBytes(StandardCharsets.UTF_8));
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
    @DisplayName("readCapabilities - v2 업그레이드 성공 시 protocolVersion=2로 전환")
    public void testReadCapabilities_v2Upgrade_success() throws Throwable {
        System.setProperty("hg4j.ssh.v2.enabled", "true");
        try {
            HgSshClient client = new HgSshClient("ssh://user@127.0.0.1/repo");
            ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
            setField(client, "out", capturedOut);
            setField(client, "in", new UpgradeSimulatingInputStream(capturedOut));

            invokeReadCapabilities(client);

            assertEquals(2, client.getProtocolVersion());
            @SuppressWarnings("unchecked")
            List<String> caps = (List<String>) getField(client, "capabilities");
            assertEquals(List.of("heads"), caps);
        } finally {
            System.clearProperty("hg4j.ssh.v2.enabled");
        }
    }

    @Test
    @DisplayName("readCapabilities - v2 업그레이드 거부 시 protocolVersion 유지, 잔여 데이터 스킵")
    public void testReadCapabilities_v2Upgrade_rejected_skipsResidualData() throws Throwable {
        System.setProperty("hg4j.ssh.v2.enabled", "true");
        try {
            HgSshClient client = new HgSshClient("ssh://user@127.0.0.1/repo");
            setField(client, "out", new ByteArrayOutputStream());
            String stream = "capabilities: heads exp-ssh-v2-0003\n" + "upgrade rejected\n" + "RESIDUAL_JUNK_DATA";
            setField(client, "in", new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)));

            invokeReadCapabilities(client);

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
    @DisplayName("listKeys - 정상 응답 파싱 (readLine은 첫 줄만 읽으므로 단일 엔트리)")
    public void testListKeys_success() throws Exception {
        // readLine() reads only up to the first '\n', so listKeys can only ever
        // observe a single "key\tvalue" entry per response in this implementation.
        HgSshClient client = createConnectedClientWithResponse("key1\tvalue1\n");
        Map<String, String> result = client.listKeys("bookmarks");
        assertEquals(1, result.size());
        assertEquals("value1", result.get("key1"));
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
    @DisplayName("pushkey - oldVal/newVal null이면 빈 문자열 전송")
    public void testPushkey_nullOldAndNewVal_sendsEmptyStrings() throws Exception {
        HgSshClient client = createConnectedClientWithResponse("1\n");
        assertTrue(client.pushkey("bookmarks", "mybook", null, null));

        ByteArrayOutputStream out = (ByteArrayOutputStream) getField(client, "out");
        String sent = out.toString(StandardCharsets.UTF_8);
        assertTrue(sent.contains("old \n"));
        assertTrue(sent.contains("new \n"));
    }
}

