package org.hg4j.errors;

/**
 * revlog 체크섬 불일치, 손상된 델타 복원 등 데이터 무결성 오류 시 발생하는 예외.
 */
public class HgCorruptDataException extends java.io.IOException {
    private static final long serialVersionUID = 1L;

    public HgCorruptDataException(String message) {
        super(message);
    }

    public HgCorruptDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
