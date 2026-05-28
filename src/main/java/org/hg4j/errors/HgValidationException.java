package org.hg4j.errors;

/**
 * 저장소 상태, 워킹 디렉터리 정합성, 유효성 검증 실패 시 발생하는Checked Exception.
 * java.io.IOException을 상속하여 기존 I/O 예외와의 API 호환성을 유지합니다.
 */
public class HgValidationException extends java.io.IOException {
    private static final long serialVersionUID = 1L;

    public HgValidationException(String message) {
        super(message);
    }

    public HgValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
