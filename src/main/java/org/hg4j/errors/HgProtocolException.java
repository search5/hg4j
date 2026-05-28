package org.hg4j.errors;

/**
 * Capabilities 협상 실패, 와이어 프로토콜 포맷 위배 등 프로토콜 오류 시 발생하는 예외.
 * {@link HgTransportException}의 서브클래스.
 */
public class HgProtocolException extends HgTransportException {
    private static final long serialVersionUID = 1L;

    /**
     * @param remoteUrl      프로토콜 오류가 발생한 원격 저장소 URL
     * @param protocolDetail 프로토콜 오류 상세 내용
     */
    public HgProtocolException(String remoteUrl, String protocolDetail) {
        super(remoteUrl, protocolDetail);
    }

    /**
     * @param remoteUrl      프로토콜 오류가 발생한 원격 저장소 URL
     * @param protocolDetail 프로토콜 오류 상세 내용
     * @param cause          원인 예외
     */
    public HgProtocolException(String remoteUrl, String protocolDetail, Throwable cause) {
        super(remoteUrl, protocolDetail, cause);
    }
}
