package org.hg4j.transport;

import com.jcraft.jsch.Session;

/**
 * SSH 세션을 생성하기 위한 팩토리 인터페이스입니다.
 * Apache MINA SSHD 등 다른 SSH 라이브러리로의 대체를 가능하게 하는 아키텍처적 유연성을 보증합니다.
 */
public interface SshSessionFactory {
    /**
     * 지정된 접속 정보를 바탕으로 JSch Session을 생성합니다.
     *
     * @param host 대상 호스트명
     * @param port 포트 번호
     * @param username 사용자명
     * @param password 패스워드 (없을 시 null)
     * @param privateKeyPath 개인키 파일 경로 (없을 시 null)
     * @param passphrase 개인키 암호 (없을 시 null)
     * @return 생성된 JSch Session 인스턴스
     * @throws Exception 세션 생성 중 오류 발생 시
     */
    Session openSession(String host, int port, String username, String password, String privateKeyPath, String passphrase) throws Exception;
}
