package org.hg4j.errors;

/**
 * 저장소 락(Lock) 획득 실패 또는 락 경합이 발생할 때 던져지는 예외.
 * <p>
 * 기존 {@link org.hg4j.core.HgLockException}의 도메인 예외 계층 대응 클래스.
 */
public class HgLockException extends HgException {
    private static final long serialVersionUID = 1L;

    private final String lockName;

    /**
     * @param lockName 획득에 실패한 락 이름 (예: "store.lock", "wlock")
     * @param message  실패 원인 설명
     */
    public HgLockException(String lockName, String message) {
        super("Failed to acquire lock '" + lockName + "': " + message);
        this.lockName = lockName;
    }

    /**
     * @param lockName 획득에 실패한 락 이름
     * @param message  실패 원인 설명
     * @param cause    원인 예외
     */
    public HgLockException(String lockName, String message, Throwable cause) {
        super("Failed to acquire lock '" + lockName + "': " + message, cause);
        this.lockName = lockName;
    }

    /** 락 이름을 반환합니다. */
    public String getLockName() {
        return lockName;
    }
}
