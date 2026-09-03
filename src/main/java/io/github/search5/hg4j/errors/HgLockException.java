package io.github.search5.hg4j.errors;

/**
 * Exception thrown when repository lock acquisition fails or lock contention occurs.
 * <p>
 * Domain exception layer wrapper corresponding to the existing {@link io.github.search5.hg4j.lib.HgLockException}.
 */
public class HgLockException extends HgException {
    private static final long serialVersionUID = 1L;

    private final String lockName;

    /**
     * @param lockName Name of the lock that failed to acquire (e.g., "store.lock", "wlock")
     * @param message  Description of the failure cause
     */
    public HgLockException(String lockName, String message) {
        super("Failed to acquire lock '" + lockName + "': " + message);
        this.lockName = lockName;
    }

    /**
     * @param lockName Name of the lock that failed to acquire
     * @param message  Description of the failure cause
     * @param cause    The causing exception
     */
    public HgLockException(String lockName, String message, Throwable cause) {
        super("Failed to acquire lock '" + lockName + "': " + message, cause);
        this.lockName = lockName;
    }

    /** Returns the name of the lock. */
    public String getLockName() {
        return lockName;
    }
}
