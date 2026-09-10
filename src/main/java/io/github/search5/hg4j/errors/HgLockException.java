package io.github.search5.hg4j.errors;

/**
 * Exception thrown when repository lock acquisition fails or lock contention occurs.
 *
 * @apiNote Thrown by {@link io.github.search5.hg4j.lib.HgLock} (acquiring the OS-level file
 *     lock) and by {@link io.github.search5.hg4j.lib.HgRepository#lockWorkingCopy()} / {@link
 *     io.github.search5.hg4j.lib.HgRepository#lockStore()} (acquiring {@code .hg/wlock} or
 *     {@code .hg/store/lock}). Any porcelain command that mutates the working copy or store
 *     (e.g. {@code CommitCommand}, {@code UpdateCommand}) can surface this when another process
 *     already holds the lock; callers typically retry after a delay or surface it to the user as
 *     "repository is locked by another process".
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
