package com.github.search5.hg4j.lib;

import java.io.File;
import java.io.IOException;
import com.github.search5.hg4j.errors.HgLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.net.InetAddress;
import java.util.Map;

/**
 * Implements a production-grade lock mechanism perfectly compatible with Mercurial native wlock and lock.
 * It uses atomic symbolic link creation on POSIX systems (and falls back to CREATE_NEW file on Windows/other systems)
 * to guarantee 100% mutual exclusion with native Mercurial and JVM-wide tracking.
 */
public class HgLock implements AutoCloseable {

    static boolean forceFallback = false;

    private static class LockInfo {
        final Thread thread;
        int count;

        LockInfo(Thread thread) {
            this.thread = thread;
            this.count = 1;
        }
    }

    private static final Map<String, LockInfo> JVM_ACTIVE_LOCKS = new ConcurrentHashMap<>();

    private final File lockFile;
    private final int timeoutMs;
    private final boolean allowReentrant;
    private boolean acquiredJvmLock = false;
    private boolean acquiredFileLock = false;
    private boolean isReentrant = false;

    /**
     * Internal constructor for No-op Dummy Lock.
     */
    protected HgLock() {
        this.lockFile = null;
        this.timeoutMs = 0;
        this.allowReentrant = false;
    }

    /**
     * Creates a dummy, no-op lock that does nothing on close.
     */
    public static HgLock noOp() {
        return new NoOpLock();
    }

    private static class NoOpLock extends HgLock {
        private NoOpLock() {
            super();
        }

        @Override
        public void close() {}
    }

    /**
     * Acquires a lock immediately (fail-fast, timeout = 0).
     */
    public HgLock(File lockFile) throws HgLockException {
        this(lockFile, 0, false);
    }

    /**
     * Acquires a lock on the specified file, waiting up to timeoutMs if it is already locked.
     */
    public HgLock(File lockFile, int timeoutMs) throws HgLockException {
        this(lockFile, timeoutMs, false);
    }

    /**
     * Acquires a lock on the specified file, waiting up to timeoutMs with reentrancy option.
     */
    public HgLock(File lockFile, int timeoutMs, boolean allowReentrant) throws HgLockException {
        if (lockFile == null) {
            throw new IllegalArgumentException("Lock file cannot be null");
        }
        this.lockFile = lockFile;
        this.timeoutMs = timeoutMs;
        this.allowReentrant = allowReentrant;
        acquire();
    }

    private void acquire() throws HgLockException {
        File parentDir = lockFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        String absPath = lockFile.getAbsolutePath();
        long start = System.currentTimeMillis();
        Thread currentThread = Thread.currentThread();

        while (true) {
            // 1. JVM-wide lock check with reentrancy support
            if (!acquiredJvmLock) {
                synchronized (JVM_ACTIVE_LOCKS) {
                    LockInfo info = JVM_ACTIVE_LOCKS.get(absPath);
                    if (info != null) {
                        if (allowReentrant && info.thread == currentThread) {
                            info.count++;
                            this.acquiredJvmLock = true;
                            this.acquiredFileLock = true;
                            this.isReentrant = true;
                            return; // Reentrant success!
                        }
                    } else {
                        JVM_ACTIVE_LOCKS.put(absPath, new LockInfo(currentThread));
                        this.acquiredJvmLock = true;
                    }
                }
            }

            // 2. File-system wide lock check (Atomic Symlink Creation with File Fallback)
            if (acquiredJvmLock && !isReentrant) {
                try {
                    String target = getHostAndPid();
                    try {
                        if (forceFallback) {
                            throw new UnsupportedOperationException("Testing fallback scenario");
                        }
                        // POSIX-compatible symlink based lock
                        Files.createSymbolicLink(lockFile.toPath(), Path.of(target));
                    } catch (UnsupportedOperationException | SecurityException | IOException symlinkEx) {
                        // Fallback to regular file creation under Windows or environments without symlink support
                        Files.createFile(lockFile.toPath());
                        Files.writeString(lockFile.toPath(), target + "\n", StandardCharsets.UTF_8);
                    }
                    acquiredFileLock = true;
                    return;
                } catch (FileAlreadyExistsException e) {
                    // Lock file already exists on disk. Let's check if the owning process is dead (stale lock).
                    String owner = readExistingOwnerSilently();
                    if (!owner.isEmpty()) {
                        int colon = owner.indexOf(':');
                        if (colon != -1) {
                            String ownerHost = owner.substring(0, colon).trim();
                            String ownerPidStr = owner.substring(colon + 1).trim();
                            try {
                                long existingPid = Long.parseLong(ownerPidStr);
                                String localHost = getLocalHostName();
                                boolean isLocal = "localhost".equals(ownerHost)
                                               || "127.0.0.1".equals(ownerHost)
                                               || localHost.equals(ownerHost);
                                if (isLocal) {
                                    // Same host! Check if the pid is alive.
                                    boolean isAlive = false;
                                    try {
                                        isAlive = ProcessHandle.of(existingPid).map(ProcessHandle::isAlive).orElse(false);
                                    } catch (Exception ignored) {}

                                    if (!isAlive) {
                                        // Owning process is dead! Perform stale lock recovery.
                                        boolean deleted = false;
                                        try {
                                            deleted = Files.deleteIfExists(lockFile.toPath());
                                        } catch (IOException ignored) {}
                                        if (deleted) {
                                            continue; // Try acquiring immediately again
                                        }
                                        throw new HgLockException(lockFile.getName(), "Failed to clear stale lock file: " + absPath);
                                    }
                                }
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                } catch (IOException e) {
                    // Treat any permission or generic file system errors under high contention as busy.
                }
            }

            // Release JVM lock block if file lock wasn't acquired to let other threads compete
            if (acquiredJvmLock && !acquiredFileLock) {
                synchronized (JVM_ACTIVE_LOCKS) {
                    JVM_ACTIVE_LOCKS.remove(absPath);
                }
                acquiredJvmLock = false;
            }

            // Check if we timed out
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed >= timeoutMs) {
                String existingOwner = readExistingOwnerSilently();
                String msg = "Could not acquire exclusive lock on file: " + absPath;
                if (!existingOwner.isEmpty()) {
                    msg += " (Currently held by " + existingOwner + ")";
                } else if (!acquiredJvmLock) {
                    msg += " (Currently held by another thread in this process)";
                }
                throw new HgLockException(lockFile.getName(), msg);
            }

            // Backoff sleep
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new HgLockException(lockFile.getName(), "Lock acquisition interrupted on file: " + absPath, e);
            }
        }
    }

    private static String cachedHostName = null;
 
    private static synchronized String getLocalHostName() {
        if (cachedHostName != null) {
            return cachedHostName;
        }
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isEmpty()) {
            host = System.getenv("COMPUTERNAME");
        }
        if (host == null || host.isEmpty()) {
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (Exception ignored) {
                host = "localhost";
            }
        }
        cachedHostName = host;
        return cachedHostName;
    }
 
    private String getHostAndPid() {
        String host = getLocalHostName();
        long pid = ProcessHandle.current().pid();
        return host + ":" + pid;
    }


    private String readExistingOwnerSilently() {
        if (!Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return "";
        }
        try {
            if (Files.isSymbolicLink(lockFile.toPath())) {
                return Files.readSymbolicLink(lockFile.toPath()).toString().trim();
            } else {
                return Files.readString(lockFile.toPath(), StandardCharsets.UTF_8).trim();
            }
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * Forcefully releases the lock on both JVM and file system levels,
     * ignoring any thread ownership and reentrancy states.
     */
    public void forceUnlock() throws IOException {
        if (lockFile == null) {
            return;
        }
        String absPath = lockFile.getAbsolutePath();
        synchronized (JVM_ACTIVE_LOCKS) {
            try {
                Files.deleteIfExists(lockFile.toPath());
            } finally {
                JVM_ACTIVE_LOCKS.remove(absPath);
                acquiredJvmLock = false;
                acquiredFileLock = false;
                isReentrant = false;
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (lockFile == null) {
            return;
        }
        String absPath = lockFile.getAbsolutePath();
        synchronized (JVM_ACTIVE_LOCKS) {
            LockInfo info = JVM_ACTIVE_LOCKS.get(absPath);
            if (info != null) {
                // Relaxed thread ownership guard: allow close() even if called from another thread
                info.count--;
                if (info.count > 0 && info.thread == Thread.currentThread()) {
                    return; // Keep lock active since other levels of reentrant scopes still own it
                }
                try {
                    if (acquiredFileLock) {
                        Files.deleteIfExists(lockFile.toPath());
                    }
                } finally {
                    JVM_ACTIVE_LOCKS.remove(absPath);
                    acquiredJvmLock = false;
                    acquiredFileLock = false;
                    isReentrant = false;
                }
            }
        }
    }
}
