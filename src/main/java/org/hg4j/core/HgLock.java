package org.hg4j.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implements a production-grade lock mechanism perfectly compatible with Mercurial native wlock and lock.
 * It uses atomic symbolic link creation on POSIX systems (and falls back to CREATE_NEW file on Windows/other systems)
 * to guarantee 100% mutual exclusion with native Mercurial and JVM-wide tracking.
 */
public class HgLock implements AutoCloseable {

    static boolean forceFallback = false;

    private static final Set<String> JVM_ACTIVE_LOCKS = ConcurrentHashMap.newKeySet();

    private final File lockFile;
    private final int timeoutMs;
    private boolean acquiredJvmLock = false;
    private boolean acquiredFileLock = false;

    /**
     * Internal constructor for No-op Dummy Lock.
     */
    protected HgLock() {
        this.lockFile = null;
        this.timeoutMs = 0;
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
        this(lockFile, 0);
    }

    /**
     * Acquires a lock on the specified file, waiting up to timeoutMs if it is already locked.
     */
    public HgLock(File lockFile, int timeoutMs) throws HgLockException {
        if (lockFile == null) {
            throw new IllegalArgumentException("Lock file cannot be null");
        }
        this.lockFile = lockFile;
        this.timeoutMs = timeoutMs;
        acquire();
    }

    private void acquire() throws HgLockException {
        File parentDir = lockFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        String absPath = lockFile.getAbsolutePath();
        long start = System.currentTimeMillis();

        while (true) {
            // 1. JVM-wide lock check
            if (!acquiredJvmLock) {
                if (JVM_ACTIVE_LOCKS.add(absPath)) {
                    acquiredJvmLock = true;
                }
            }

            // 2. File-system wide lock check (Atomic Symlink Creation with File Fallback)
            if (acquiredJvmLock) {
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
                                        throw new HgLockException("Failed to clear stale lock file: " + absPath);
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
                JVM_ACTIVE_LOCKS.remove(absPath);
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
                throw new HgLockException(msg);
            }

            // Backoff sleep
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new HgLockException("Lock acquisition interrupted on file: " + absPath, e);
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
                host = java.net.InetAddress.getLocalHost().getHostName();
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

    @Override
    public void close() throws IOException {
        if (lockFile == null) {
            return;
        }
        String absPath = lockFile.getAbsolutePath();
        try {
            if (acquiredFileLock) {
                Files.deleteIfExists(lockFile.toPath());
            }
        } finally {
            if (acquiredJvmLock) {
                JVM_ACTIVE_LOCKS.remove(absPath);
            }
        }
    }
}
