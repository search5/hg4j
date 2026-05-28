package org.hg4j.errors;

/**
 * 잘못된 경로이거나 손상된 Mercurial 저장소를 찾을 수 없을 때 발생하는 예외.
 */
public class HgRepositoryNotFoundException extends java.io.IOException {
    private static final long serialVersionUID = 1L;

    private final String path;

    /**
     * @param path 존재하지 않거나 손상된 저장소 경로
     */
    public HgRepositoryNotFoundException(String path) {
        super("Mercurial repository not found at path: " + path);
        this.path = path;
    }

    /**
     * @param path  존재하지 않거나 손상된 저장소 경로
     * @param cause 원인 예외
     */
    public HgRepositoryNotFoundException(String path, Throwable cause) {
        super("Mercurial repository not found at path: " + path, cause);
        this.path = path;
    }

    /** 저장소 경로를 반환합니다. */
    public String getPath() {
        return path;
    }
}
