package org.hg4j.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class HgLockTest {

    @Test
    public void testSequentialLockAcquisition(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = new HgRepository(repoDir);
        repo.getStoreDir().mkdirs();

        File lockFile = new File(repo.getStoreDir(), "lock");

        // 1. First lock acquisition should succeed
        try (HgLock lock = new HgLock(lockFile)) {
            assertTrue(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS), "Lock file should be created on disk");
            
            // 2. Trying to lock the same file simultaneously must fail
            assertThrows(HgLockException.class, () -> {
                try (HgLock secondLock = new HgLock(lockFile)) {
                    fail("Should not acquire the lock concurrently");
                }
            });
        }

        // 3. After closing, the lock file on disk is deleted, and lock is free
        assertFalse(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS), "Lock file should be removed after release");

        // 4. Sequential lock acquisition must now succeed
        try (HgLock subsequentLock = new HgLock(lockFile)) {
            assertTrue(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS));
        }
        assertFalse(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    public void testConcurrentLockContention(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = new HgRepository(repoDir);
        repo.getStoreDir().mkdirs();
        File lockFile = new File(repo.getStoreDir(), "lock");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);
        AtomicBoolean secondThreadFailed = new AtomicBoolean(false);

        executor.submit(() -> {
            try (HgLock firstLock = new HgLock(lockFile)) {
                latch1.countDown(); // Signals thread 2 that thread 1 has acquired the lock
                latch2.await(5, TimeUnit.SECONDS); // Hold the lock for a while
            } catch (Exception e) {
                fail("Thread 1 should have successfully acquired the lock: " + e.getMessage());
            }
            return null;
        });

        executor.submit(() -> {
            try {
                latch1.await(5, TimeUnit.SECONDS); // Wait until thread 1 has the lock
                // Attempting to lock should fail fast
                try (HgLock secondLock = new HgLock(lockFile)) {
                    fail("Thread 2 should have failed to acquire the lock");
                }
            } catch (HgLockException e) {
                secondThreadFailed.set(true);
            } catch (Exception e) {
                fail("Thread 2 caught unexpected exception: " + e.getMessage());
            } finally {
                latch2.countDown(); // Release thread 1
            }
            return null;
        });

        executor.shutdown();
        boolean finished = executor.awaitTermination(10, TimeUnit.SECONDS);

        assertTrue(finished, "Threads should terminate inside timeout window");
        assertTrue(secondThreadFailed.get(), "Thread 2 must fail to acquire lock concurrently");
        assertFalse(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS), "Lock file must be deleted upon release");
    }

    @Test
    public void testLockTimeoutAndRetry(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = new HgRepository(repoDir);
        repo.getStoreDir().mkdirs();
        File lockFile = new File(repo.getStoreDir(), "lock");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch1 = new CountDownLatch(1);
        CountDownLatch latch2 = new CountDownLatch(1);
        AtomicBoolean secondThreadAcquired = new AtomicBoolean(false);

        // Thread 1 acquires lock and holds it
        executor.submit(() -> {
            try (HgLock firstLock = new HgLock(lockFile)) {
                latch1.countDown();
                // Sleep for 300ms, then close the lock
                Thread.sleep(300);
            } catch (Exception e) {
                fail(e.getMessage());
            } finally {
                latch2.countDown();
            }
            return null;
        });

        // Thread 2 waits, then tries to lock with a 1000ms timeout
        executor.submit(() -> {
            try {
                latch1.await(5, TimeUnit.SECONDS);
                // Attempt to acquire lock with 1000ms timeout.
                // This should succeed because thread 1 releases it after 300ms.
                try (HgLock secondLock = new HgLock(lockFile, 1000)) {
                    secondThreadAcquired.set(true);
                }
            } catch (Exception e) {
                fail("Thread 2 should have successfully waited and acquired lock: " + e.getMessage());
            }
            return null;
        });

        executor.shutdown();
        boolean finished = executor.awaitTermination(5, TimeUnit.SECONDS);
        assertTrue(finished);
        assertTrue(secondThreadAcquired.get(), "Thread 2 should succeed to acquire lock after Thread 1 released it within timeout");
    }

    @Test
    public void testLockMetadataAndOwnerReporting(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = new HgRepository(repoDir);
        repo.getStoreDir().mkdirs();
        File lockFile = new File(repo.getStoreDir(), "lock");

        try (HgLock firstLock = new HgLock(lockFile)) {
            assertTrue(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS));
            
            // Verify file metadata is written (should contain pid)
            String content;
            if (Files.isSymbolicLink(lockFile.toPath())) {
                content = Files.readSymbolicLink(lockFile.toPath()).toString();
            } else {
                content = Files.readString(lockFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            }
            assertTrue(content.contains(":"), "Lock file should contain ':' separator");
            assertTrue(content.contains(String.valueOf(ProcessHandle.current().pid())), "Lock file should contain current PID");

            // Attempting to lock concurrently should fail with timeout, reporting the existing owner in the exception message
            HgLockException exception = assertThrows(HgLockException.class, () -> {
                new HgLock(lockFile, 100);
            });

            assertTrue(exception.getMessage().contains("Currently held by"), "Exception message should contain lock owner details");
            assertTrue(exception.getMessage().contains(String.valueOf(ProcessHandle.current().pid())), "Exception message should contain PID");
        }
        assertFalse(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    public void testStaleLockRecovery(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        File lockFile = new File(repoDir, "lock");
        
        // Write a fake lock pointing to a dead pid (e.g. 999999)
        String fakeLockMetadata = "localhost:999999\n";
        try {
            Files.createSymbolicLink(lockFile.toPath(), java.nio.file.Path.of("localhost:999999"));
        } catch (Exception e) {
            Files.writeString(lockFile.toPath(), fakeLockMetadata, java.nio.charset.StandardCharsets.UTF_8);
        }
        
        // Attempting to acquire should delete the stale lock and succeed
        try (HgLock lock = new HgLock(lockFile, 100)) {
            assertTrue(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS));
            String content;
            if (Files.isSymbolicLink(lockFile.toPath())) {
                content = Files.readSymbolicLink(lockFile.toPath()).toString();
            } else {
                content = Files.readString(lockFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            }
            assertTrue(content.contains(String.valueOf(ProcessHandle.current().pid())), "Should now own the lock");
        }
        assertFalse(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    public void testFallbackLockCreation(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        File lockFile = new File(repoDir, "fallback_lock");

        try {
            HgLock.forceFallback = true;
            try (HgLock lock = new HgLock(lockFile, 100)) {
                assertTrue(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS));
                assertFalse(Files.isSymbolicLink(lockFile.toPath()));
                
                String content = Files.readString(lockFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(content.contains(String.valueOf(ProcessHandle.current().pid())));
                
                // Concurrent acquisition check in fallback mode
                assertThrows(HgLockException.class, () -> {
                    new HgLock(lockFile, 50);
                });
            }
            assertFalse(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS));
        } finally {
            HgLock.forceFallback = false;
        }
    }

    @Test
    public void testDifferentHostCaseInsensitiveIgnored(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        File lockFile = new File(repoDir, "lock");
        
        String localHost = getLocalHostName();
        String differentCaseHost = localHost.equalsIgnoreCase("localhost") ? "LocalHost" : localHost.toUpperCase();
        
        if (differentCaseHost.equals(localHost)) {
            differentCaseHost = localHost + "-different";
        }
        
        String fakeLockMetadata = differentCaseHost + ":999999\n";
        try {
            Files.createSymbolicLink(lockFile.toPath(), java.nio.file.Path.of(differentCaseHost + ":999999"));
        } catch (Exception e) {
            Files.writeString(lockFile.toPath(), fakeLockMetadata, java.nio.charset.StandardCharsets.UTF_8);
        }
        
        assertThrows(HgLockException.class, () -> {
            new HgLock(lockFile, 100);
        });
    }

    private String getLocalHostName() {
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
        return host;
    }
}
