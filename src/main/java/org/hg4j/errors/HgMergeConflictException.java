package org.hg4j.errors;

/**
 * merge3 수행 시 자동으로 해결할 수 없는 충돌이 발생할 때 던져지는 예외.
 */
public class HgMergeConflictException extends HgException {
    private static final long serialVersionUID = 1L;

    private final String conflictPath;

    /**
     * @param conflictPath 충돌이 발생한 파일 경로
     * @param message      충돌 내용 설명
     */
    public HgMergeConflictException(String conflictPath, String message) {
        super("Merge conflict in '" + conflictPath + "': " + message);
        this.conflictPath = conflictPath;
    }

    public HgMergeConflictException(String conflictPath, String message, Throwable cause) {
        super("Merge conflict in '" + conflictPath + "': " + message, cause);
        this.conflictPath = conflictPath;
    }

    /** 충돌이 발생한 파일 경로를 반환합니다. */
    public String getConflictPath() {
        return conflictPath;
    }
}
