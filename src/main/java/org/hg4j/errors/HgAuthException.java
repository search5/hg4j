package org.hg4j.errors;

/**
 * SSH/HTTP 인증(Credentials) 실패 예외.
 * {@link HgTransportException}의 서브클래스.
 */
public class HgAuthException extends HgTransportException {
    private static final long serialVersionUID = 1L;

    private final String username;

    /**
     * @param remoteUrl 인증 대상 원격 저장소 URL
     * @param username  인증에 실패한 사용자명
     */
    public HgAuthException(String remoteUrl, String username) {
        super(remoteUrl, "Authentication failed for user '" + username + "'");
        this.username = username;
    }

    /**
     * @param remoteUrl 인증 대상 원격 저장소 URL
     * @param username  인증에 실패한 사용자명
     * @param cause     원인 예외
     */
    public HgAuthException(String remoteUrl, String username, Throwable cause) {
        super(remoteUrl, "Authentication failed for user '" + username + "'", cause);
        this.username = username;
    }

    /** 인증에 실패한 사용자명을 반환합니다. */
    public String getUsername() {
        return username;
    }
}
