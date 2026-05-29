package org.hg4j.transport;

/**
 * 특정 SSH 라이브러리에 독립적인 SshSession 인스턴스를 생성하는 추상 팩토리 인터페이스입니다.
 * Apache MINA SSHD 등 다양한 SSH 라이브러리로의 다이나믹 플러그인 교체를 완벽 지원합니다.
 */
public interface SshSessionFactory {
    /**
     * 지정된 접속 정보를 바탕으로 추상화된 SshSession을 생성합니다.
     *
     * @param host 대상 호스트명
     * @param port 포트 번호
     * @param username 사용자명
     * @param password 패스워드 (없을 시 null)
     * @param privateKeyPath 개인키 파일 경로 (없을 시 null)
     * @param passphrase 개인키 암호 (없을 시 null)
     * @return 생성된 SshSession 추상 인터페이스 인스턴스
     * @throws Exception 세션 생성 중 오류 발생 시
     */
    SshSession createSession(String host, int port, String username, String password, String privateKeyPath, String passphrase) throws Exception;
}
