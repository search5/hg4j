package org.hg4j.errors;

/**
 * 네트워크 및 와이어 프로토콜 제어 오류의 기반 예외 클래스.
 * HTTP/SSH transport 계층에서 발생하는 모든 예외의 상위 클래스.
 */
public class HgTransportException extends HgException {
    private static final long serialVersionUID = 1L;

    private final String remoteUrl;

    /**
     * @param remoteUrl 연결 대상 원격 저장소 URL
     * @param message   오류 설명
     */
    public HgTransportException(String remoteUrl, String message) {
        super("Transport error for '" + remoteUrl + "': " + message);
        this.remoteUrl = remoteUrl;
    }

    /**
     * @param remoteUrl 연결 대상 원격 저장소 URL
     * @param message   오류 설명
     * @param cause     원인 예외
     */
    public HgTransportException(String remoteUrl, String message, Throwable cause) {
        super("Transport error for '" + remoteUrl + "': " + message, cause);
        this.remoteUrl = remoteUrl;
    }

    /** 원격 저장소 URL을 반환합니다. */
    public String getRemoteUrl() {
        return remoteUrl;
    }
}
