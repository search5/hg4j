package org.hg4j.errors;

/**
 * 해시 또는 리비전 번호로 조회 시 해당 리비전을 찾지 못할 때 발생하는 예외.
 */
public class HgRevisionNotFoundException extends HgException {
    private static final long serialVersionUID = 1L;

    /**
     * 노드 ID(hex 문자열)로 생성합니다.
     *
     * @param nodeId 40자 16진수 노드 ID 문자열
     */
    public HgRevisionNotFoundException(String nodeId) {
        super("Revision not found for node ID: " + nodeId);
    }

    /**
     * 리비전 번호로 생성합니다.
     *
     * @param revNumber 조회에 실패한 리비전 번호
     */
    public HgRevisionNotFoundException(int revNumber) {
        super("Revision not found for revision number: " + revNumber);
    }

    public HgRevisionNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
