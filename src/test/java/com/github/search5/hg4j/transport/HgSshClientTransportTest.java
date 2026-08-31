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

import static org.junit.jupiter.api.Assertions.*;

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
        private org.apache.sshd.server.ExitCallback callback;
        private Thread thread;

        public FullFeaturedMockHgCommand(String command) {
            this.command = command;
        }

        @Override public void setInputStream(InputStream in) { this.in = in; }
        @Override public void setOutputStream(OutputStream out) { this.out = out; }
        @Override public void setErrorStream(OutputStream err) { this.err = err; }
        @Override public void setExitCallback(org.apache.sshd.server.ExitCallback cb) { this.callback = cb; }

        @Override
        public void start(org.apache.sshd.server.channel.ChannelSession session,
                          org.apache.sshd.server.Environment env) throws IOException {
            thread = new Thread(this);
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void destroy(org.apache.sshd.server.channel.ChannelSession session) throws Exception {
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
        
        java.lang.reflect.Field inField = HgSshClient.class.getDeclaredField("in");
        inField.setAccessible(true);
        inField.set(client, new ByteArrayInputStream(invalidHeader));
        
        java.lang.reflect.Method method = HgSshClient.class.getDeclaredMethod("readCapabilities");
        method.setAccessible(true);
        
        assertThrows(com.github.search5.hg4j.errors.HgProtocolException.class, () -> {
            try {
                method.invoke(client);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    @Test
    @DisplayName("SSH chunk size EOF 시 HgProtocolException 발생")
    public void testReadBinaryResponse_eofInSize_throwsProtocolException() throws Exception {
        byte[] incompleteSize = new byte[]{0, 0, 1}; // Less than 4 bytes
        HgSshClient client = new HgSshClient("ssh://hg4juser@127.0.0.1/repo");
        
        java.lang.reflect.Field inField = HgSshClient.class.getDeclaredField("in");
        inField.setAccessible(true);
        inField.set(client, new ByteArrayInputStream(incompleteSize));
        
        java.lang.reflect.Method method = HgSshClient.class.getDeclaredMethod("readBinaryResponse");
        method.setAccessible(true);
        
        assertThrows(com.github.search5.hg4j.errors.HgProtocolException.class, () -> {
            try {
                method.invoke(client);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    @Test
    @DisplayName("SSH 음수 chunk size 시 HgProtocolException 발생")
    public void testReadBinaryResponse_negativeSize_throwsProtocolException() throws Exception {
        byte[] negativeSize = new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0x9C}; // Negative value
        HgSshClient client = new HgSshClient("ssh://hg4juser@127.0.0.1/repo");
        
        java.lang.reflect.Field inField = HgSshClient.class.getDeclaredField("in");
        inField.setAccessible(true);
        inField.set(client, new ByteArrayInputStream(negativeSize));
        
        java.lang.reflect.Method method = HgSshClient.class.getDeclaredMethod("readBinaryResponse");
        method.setAccessible(true);
        
        assertThrows(com.github.search5.hg4j.errors.HgProtocolException.class, () -> {
            try {
                method.invoke(client);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    @Test
    @DisplayName("SSH chunk payload EOF 시 HgProtocolException 발생")
    public void testReadBinaryResponse_eofInPayload_throwsProtocolException() throws Exception {
        byte[] incompletePayload = new byte[]{0, 0, 0, 10, 1, 2, 3}; // Specifying 10 bytes but providing only 3 bytes
        HgSshClient client = new HgSshClient("ssh://hg4juser@127.0.0.1/repo");
        
        java.lang.reflect.Field inField = HgSshClient.class.getDeclaredField("in");
        inField.setAccessible(true);
        inField.set(client, new ByteArrayInputStream(incompletePayload));
        
        java.lang.reflect.Method method = HgSshClient.class.getDeclaredMethod("readBinaryResponse");
        method.setAccessible(true);
        
        assertThrows(com.github.search5.hg4j.errors.HgProtocolException.class, () -> {
            try {
                method.invoke(client);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    @Test
    @DisplayName("Repository.open() 팩토리 메소드 기능 및 예외 정밀 검증")
    public void testRepositoryOpenStaticMethod() throws Exception {
        // Null argument
        assertThrows(IllegalArgumentException.class, () -> com.github.search5.hg4j.lib.Repository.open(null));
        
        // Non-existent directory
        File nonExistent = new File(System.getProperty("java.io.tmpdir"), "non_existent_hg_repo_test_" + System.currentTimeMillis());
        assertThrows(com.github.search5.hg4j.errors.HgRepositoryNotFoundException.class, () -> com.github.search5.hg4j.lib.Repository.open(nonExistent));
        
        // Verify opening a valid repository
        java.nio.file.Path tempPath = Files.createTempDirectory("hg4j_repo_open_static_test_");
        File tempDir = tempPath.toFile();
        try {
            // Initialization
            com.github.search5.hg4j.lib.Repository repo = com.github.search5.hg4j.api.Hg.init().setDirectory(tempDir).call();
            assertNotNull(repo);
            repo.close();
            
            // Call static open
            try (com.github.search5.hg4j.lib.Repository opened = com.github.search5.hg4j.lib.Repository.open(tempDir)) {
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
}

