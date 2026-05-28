package org.hg4j.transport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * org.hg4j.transport 패키지의 HgRemoteConnectionFactory, HgRemoteConnection 인터페이스,
 * HgRemoteClient에 대한 단위 테스트.
 */
@DisplayName("HgRemoteConnectionFactory 및 transport 패키지 테스트")
public class HgRemoteConnectionFactoryTest {

    // ─────────────────────────────────────────────────────────────
    // HgRemoteConnectionFactory 테스트
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
    // HgRemoteClient 세부 동작 테스트
    // ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("URL 후행 슬래시 정규화 확인")
    public void testHgRemoteClient_trailingSlashNormalization() throws IOException {
        // 후행 슬래시 있는 URL로 생성 시 정상 객체 반환
        HgRemoteClient clientWithSlash = new HgRemoteClient("http://example.com/repo/");
        assertNotNull(clientWithSlash);

        // 후행 슬래시 없는 URL
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
        // null 프록시는 무시되어야 하며 예외 없이 동작해야 함
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
            client.setProxy(java.net.Proxy.NO_PROXY);
        });
    }

    @Test
    @DisplayName("HTTPS URL에서 forceTls=true 설정 후 연결 시 SecurityException 미발생 (IOException 발생 가능)")
    public void testHgRemoteClient_httpsWithForceTls_noSecurityException() {
        HgRemoteClient client = new HgRemoteClient("https://example.com/repo");
        client.setForceTls(true);
        // SecurityException은 아니어야 함. 네트워크 없으므로 IOException 계열만 허용
        assertThrows(IOException.class, () -> client.getCapabilities());
    }

    // ─────────────────────────────────────────────────────────────
    // HgSshClient 단위 테스트 (패키지 레벨 검증)
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
}
