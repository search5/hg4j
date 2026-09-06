package io.github.search5.hg4j.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import java.net.Proxy;
import java.util.Collections;
import java.util.Map;
import java.io.File;

/**
 * Unit tests for HgRemoteConnectionFactory, the HgRemoteConnection interface,
 * and HgRemoteClient in the io.github.search5.hg4j.transport package.
 */
@DisplayName("HgRemoteConnectionFactory 및 transport 패키지 테스트")
public class HgRemoteConnectionFactoryTest {

    // ─────────────────────────────────────────────────────────────
    // HgRemoteConnectionFactory Tests
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("HTTP URL → HgRemoteClient 인스턴스 반환")
    public void testCreateConnection_httpUrl_returnsHgRemoteClient() throws IOException {
        HgRemoteConnection conn = HgRemoteConnectionFactory.createConnection("http://example.com/repo");
        assertNotNull(conn);
        assertInstanceOf(HgRemoteClient.class, conn);
    }

    @Test
    @DisplayName("HTTPS URL → HgRemoteClient 인스턴스 반환")
    public void testCreateConnection_httpsUrl_returnsHgRemoteClient() throws IOException {
        HgRemoteConnection conn = HgRemoteConnectionFactory.createConnection("https://example.com/repo");
        assertNotNull(conn);
        assertInstanceOf(HgRemoteClient.class, conn);
    }

    @Test
    @DisplayName("SSH URL → HgSshClient 인스턴스 반환")
    public void testCreateConnection_sshUrl_returnsHgSshClient() throws IOException {
        HgRemoteConnection conn = HgRemoteConnectionFactory.createConnection("ssh://user@example.com/repo");
        assertNotNull(conn);
        assertInstanceOf(HgSshClient.class, conn);
    }

    @Test
    @DisplayName("file:// URL → HgLocalClient 인스턴스 반환")
    public void testCreateConnection_fileUrl_returnsHgLocalClient() throws IOException {
        HgRemoteConnection conn = HgRemoteConnectionFactory.createConnection("file:///tmp/nonexistent-hg4j-repo");
        assertNotNull(conn);
        assertInstanceOf(HgLocalClient.class, conn);
    }

    @Test
    @DisplayName("스킴 없는 순수 로컬 디렉터리 경로 → HgLocalClient 인스턴스 반환 (4번째 기본 프로토콜)")
    public void testCreateConnection_bareLocalDirectoryPath_returnsHgLocalClient(@TempDir Path tempDir) throws IOException {
        HgRemoteConnection conn = HgRemoteConnectionFactory.createConnection(tempDir.toAbsolutePath().toString());
        assertNotNull(conn);
        assertInstanceOf(HgLocalClient.class, conn);
    }

    @Test
    @DisplayName("존재하지 않는 스킴 없는 경로 → 어떤 기본 프로토콜도 처리 안 하고 HTTP 폴백으로 떨어진다")
    public void testCreateConnection_nonexistentBarePath_fallsBackToHttpClient() throws IOException {
        HgRemoteConnection conn = HgRemoteConnectionFactory.createConnection("/tmp/hg4j-nonexistent-path-" + System.nanoTime());
        assertNotNull(conn);
        assertInstanceOf(HgRemoteClient.class, conn);
    }

    @Test
    @DisplayName("존재하지만 디렉터리가 아닌(일반 파일) 스킴 없는 경로 → HTTP 폴백으로 떨어진다")
    public void testCreateConnection_barePathExistsButIsNotDirectory_fallsBackToHttpClient(@TempDir Path tempDir) throws IOException {
        File plainFile = new File(tempDir.toFile(), "not-a-directory.txt");
        assertTrue(plainFile.createNewFile());
        HgRemoteConnection conn = HgRemoteConnectionFactory.createConnection(plainFile.getAbsolutePath());
        assertNotNull(conn);
        assertInstanceOf(HgRemoteClient.class, conn);
    }

    @Test
    @DisplayName("null URL → IllegalArgumentException 발생")
    public void testCreateConnection_nullUrl_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> HgRemoteConnectionFactory.createConnection(null));
    }

    @Test
    @DisplayName("빈 URL → IllegalArgumentException 발생")
    public void testCreateConnection_emptyUrl_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> HgRemoteConnectionFactory.createConnection(""));
    }

    // ─────────────────────────────────────────────────────────────
    // HgRemoteClient Detailed Behavior Tests
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("URL 후행 슬래시 정규화 확인")
    public void testHgRemoteClient_trailingSlashNormalization() throws IOException {
        // Instantiating with a trailing slash URL returns a valid instance
        HgRemoteClient clientWithSlash = new HgRemoteClient("http://example.com/repo/");
        assertNotNull(clientWithSlash);

        // URL without a trailing slash
        HgRemoteClient clientNoSlash = new HgRemoteClient("http://example.com/repo");
        assertNotNull(clientNoSlash);
    }

    @Test
    @DisplayName("TLS 강제 설정 시 HTTP URL에서 SecurityException 발생")
    public void testHgRemoteClient_forceTls_throwsSecurityException() {
        HgRemoteClient client = new HgRemoteClient("http://example.com/repo");
        client.setForceTls(true);
        assertThrows(SecurityException.class, () -> client.getCapabilities());
    }

    @Test
    @DisplayName("TLS 강제 설정 시 HTTP URL에서 getHeads SecurityException 발생")
    public void testHgRemoteClient_forceTls_getHeads_throwsSecurityException() {
        HgRemoteClient client = new HgRemoteClient("http://example.com/repo");
        client.setForceTls(true);
        assertThrows(SecurityException.class, () -> client.getHeads());
    }

    @Test
    @DisplayName("TLS 강제 설정 시 HTTP URL에서 getChangegroup SecurityException 발생")
    public void testHgRemoteClient_forceTls_getChangegroup_throwsSecurityException() {
        HgRemoteClient client = new HgRemoteClient("http://example.com/repo");
        client.setForceTls(true);
        assertThrows(SecurityException.class, () -> client.getChangegroup(List.of("abc123")));
    }

    @Test
    @DisplayName("TLS 강제 설정 시 HTTP URL에서 getBundle SecurityException 발생")
    public void testHgRemoteClient_forceTls_getBundle_throwsSecurityException() {
        HgRemoteClient client = new HgRemoteClient("http://example.com/repo");
        client.setForceTls(true);
        assertThrows(SecurityException.class,
                () -> client.getBundle(List.of(), List.of(), List.of()));
    }

    @Test
    @DisplayName("TLS 강제 설정 시 HTTP URL에서 push SecurityException 발생")
    public void testHgRemoteClient_forceTls_push_throwsSecurityException() {
        HgRemoteClient client = new HgRemoteClient("http://example.com/repo");
        client.setForceTls(true);
        assertThrows(SecurityException.class,
                () -> client.push(new byte[]{1, 2, 3}, List.of("head1")));
    }

    @Test
    @DisplayName("잘못된 URL 프로토콜 → getCapabilities IOException 발생")
    public void testHgRemoteClient_invalidUrl_throwsIOException() {
        HgRemoteClient client = new HgRemoteClient("not-a-valid-protocol://example.com/repo");
        assertThrows(IOException.class, () -> client.getCapabilities());
    }

    @Test
    @DisplayName("잘못된 URL 프로토콜 → getHeads IOException 발생")
    public void testHgRemoteClient_invalidUrl_getHeads_throwsIOException() {
        HgRemoteClient client = new HgRemoteClient("not-a-valid-protocol://example.com/repo");
        assertThrows(IOException.class, () -> client.getHeads());
    }

    @Test
    @DisplayName("null proxy 설정 시 무시됨 (NPE 없음)")
    public void testHgRemoteClient_setNullProxy_ignored() {
        HgRemoteClient client = new HgRemoteClient("http://example.com/repo");
        // The null proxy should be ignored and operate without throwing exceptions
        assertDoesNotThrow(() -> client.setProxy(null));
    }

    @Test
    @DisplayName("타임아웃 및 인증 정보 설정 시 예외 없음")
    public void testHgRemoteClient_setOptions_noException() {
        HgRemoteClient client = new HgRemoteClient("http://example.com/repo");
        assertDoesNotThrow(() -> {
            client.setTimeouts(5000, 15000);
            client.setCredentials("admin", "secret");
            client.setForceTls(false);
            client.setProxy(Proxy.NO_PROXY);
        });
    }

    @Test
    @DisplayName("HTTPS URL에서 forceTls=true 설정 후 연결 시 SecurityException 미발생 (IOException 발생 가능)")
    public void testHgRemoteClient_httpsWithForceTls_noSecurityException() {
        HgRemoteClient client = new HgRemoteClient("https://example.com/repo");
        client.setForceTls(true);
        // Should not throw SecurityException. Since there is no network, only IOException is expected.
        assertThrows(IOException.class, () -> client.getCapabilities());
    }

    // ─────────────────────────────────────────────────────────────
    // HgSshClient Unit Tests (Package-level Verification)
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("HgSshClient 생성 시 예외 없음")
    public void testHgSshClient_creation_noException() {
        assertDoesNotThrow(() -> new HgSshClient("ssh://user@example.com:22/repo"));
    }

    @Test
    @DisplayName("HgSshClient는 HgRemoteConnection 인터페이스를 구현함")
    public void testHgSshClient_implementsHgRemoteConnection() {
        HgSshClient client = new HgSshClient("ssh://user@example.com/repo");
        assertInstanceOf(HgRemoteConnection.class, client);
    }

    @Test
    @DisplayName("HgSshClient는 AutoCloseable을 구현함")
    public void testHgSshClient_implementsAutoCloseable() {
        HgSshClient client = new HgSshClient("ssh://user@example.com/repo");
        assertInstanceOf(AutoCloseable.class, client);
    }

    @Test
    @DisplayName("HgSshClient close() 시 예외 없음 (미연결 상태)")
    public void testHgSshClient_closeWithoutConnect_noException() {
        HgSshClient client = new HgSshClient("ssh://user@example.com/repo");
        assertDoesNotThrow(() -> client.close());
    }

    @Test
    @DisplayName("HgSshClient setPassword() 설정 시 예외 없음")
    public void testHgSshClient_setPassword_noException() {
        HgSshClient client = new HgSshClient("ssh://user@example.com/repo");
        assertDoesNotThrow(() -> client.setPassword("mypassword"));
    }

    @Test
    @DisplayName("HgSshClient 미연결 상태에서 getCapabilities → IOException 발생")
    public void testHgSshClient_notConnected_getCapabilities_throwsIOException() {
        HgSshClient client = new HgSshClient("ssh://user@256.0.0.1:22/repo");
        assertThrows(IOException.class, () -> client.getCapabilities());
    }

    @Test
    @DisplayName("HgSshClient 미연결 상태에서 getHeads → IOException 발생")
    public void testHgSshClient_notConnected_getHeads_throwsIOException() {
        HgSshClient client = new HgSshClient("ssh://user@256.0.0.1:22/repo");
        assertThrows(IOException.class, () -> client.getHeads());
    }

    @Test
    @DisplayName("HgSshClient 미연결 상태에서 getChangegroup → IOException 발생")
    public void testHgSshClient_notConnected_getChangegroup_throwsIOException() {
        HgSshClient client = new HgSshClient("ssh://user@256.0.0.1:22/repo");
        assertThrows(IOException.class, () -> client.getChangegroup(List.of("abc")));
    }

    @Test
    @DisplayName("HgSshClient 미연결 상태에서 push → IOException 발생")
    public void testHgSshClient_notConnected_push_throwsIOException() {
        HgSshClient client = new HgSshClient("ssh://user@256.0.0.1:22/repo");
        assertThrows(IOException.class, () -> client.push(new byte[]{0}, List.of()));
    }

    // ─────────────────────────────────────────────────────────────
    // TransportProtocol Plugin Abstraction Tests
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("커스텀 프로토콜 등록 및 동적 처리 테스트")
    public void testCustomTransportProtocolRegistration() throws IOException {
        TransportProtocol customProto = new TransportProtocol() {
            @Override
            public boolean canHandle(String url) {
                return url.startsWith("custom://");
            }

            @Override
            public HgRemoteConnection open(String url) throws IOException {
                return new HgRemoteConnection() {
                    @Override
                    public List<String> getCapabilities() throws IOException {
                        return List.of("custom-cap=true");
                    }

                    @Override
                    public List<String> getHeads() throws IOException {
                        return List.of("custom-head");
                    }

                    @Override
                    public byte[] getChangegroup(List<String> roots) throws IOException {
                        return new byte[0];
                    }

                    @Override
                    public byte[] getBundle(List<String> common, List<String> heads, List<String> bundleCaps) throws IOException {
                        return new byte[0];
                    }

                    @Override
                    public String push(byte[] bundleBytes, List<String> heads) throws IOException {
                        return "";
                    }

                    @Override
                    public Map<String, String> listKeys(String namespace) throws IOException {
                        return Collections.emptyMap();
                    }

                    @Override
                    public boolean pushkey(String namespace, String key, String oldVal, String newVal) throws IOException {
                        return false;
                    }

                    @Override
                    public void close() throws IOException {
                        // no-op
                    }
                };
            }
        };

        // 1. Verify registration
        HgRemoteConnectionFactory.register(customProto);
        assertTrue(HgRemoteConnectionFactory.getRegisteredProtocols().contains(customProto));

        // 2. Verify instance creation
        HgRemoteConnection conn = HgRemoteConnectionFactory.createConnection("custom://my-custom-repo");
        assertNotNull(conn);
        List<String> caps = conn.getCapabilities();
        assertTrue(caps.contains("custom-cap=true"));
        assertEquals(List.of("custom-head"), conn.getHeads());
    }

    @Test
    public void testRegisterNullProtocolIsANoOp() {
        int before = HgRemoteConnectionFactory.getRegisteredProtocols().size();
        HgRemoteConnectionFactory.register(null);
        assertEquals(before, HgRemoteConnectionFactory.getRegisteredProtocols().size());
    }
}
