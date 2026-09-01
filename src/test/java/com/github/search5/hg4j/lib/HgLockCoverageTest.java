package com.github.search5.hg4j.lib;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.github.search5.hg4j.errors.HgLockException;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage for {@link HgLock}, targeting branches and lines not exercised by
 * {@link HgLockTest} or by the rest of the test suite's incidental use of the class:
 * validation, directory auto-creation, no-op/null-lockFile paths, forceUnlock(), stale-lock
 * recovery edge cases (alive owner, unparsable pid, delete failure), generic IO-busy handling,
 * interrupted waits, reentrant multi-level close(), and the documented "relaxed ownership"
 * cross-thread close() behavior.
 */
public class HgLockCoverageTest {

    // ---------------------------------------------------------------
    // Basic validation / setup branches
    // ---------------------------------------------------------------

    @Test
    public void testNullLockFileConstructorThrows() {
        assertThrows(IllegalArgumentException.class, () -> new HgLock(null));
    }

    @Test
    public void testNestedParentDirectoriesAreCreated(@TempDir Path tempDir) throws Exception {
        File lockFile = new File(tempDir.toFile(), "a/b/c/lock");
        assertFalse(lockFile.getParentFile().exists(), "Precondition: parent chain must not exist yet");

        try (HgLock lock = new HgLock(lockFile)) {
            assertTrue(lockFile.getParentFile().isDirectory(), "acquire() should mkdirs() the missing parent chain");
            assertTrue(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS));
        }
    }

    // ---------------------------------------------------------------
    // No-op lock / null-lockFile instance paths
    // ---------------------------------------------------------------

    @Test
    public void testNoOpLockForceUnlockIsNoOp() throws Exception {
        HgLock noOp = HgLock.noOp();
        // Must not throw even though there is no backing lock file at all.
        noOp.forceUnlock();
        noOp.close();
    }

    @Test
    public void testCloseOnInstanceWithNullLockFileIsNoOp() throws Exception {
        // Package-private access to the protected no-arg constructor lets us exercise
        // HgLock.close()'s own null-lockFile guard directly (NoOpLock overrides close(),
        // so it never reaches this branch).
        HgLock nullFileLock = new HgLock() {};
        nullFileLock.close();
    }

    // ---------------------------------------------------------------
    // forceUnlock()
    // ---------------------------------------------------------------

    @Test
    public void testForceUnlockReleasesFileAndJvmStateForReacquisition(@TempDir Path tempDir) throws Exception {
        File lockFile = new File(tempDir.toFile(), "lock");
        HgLock lock = new HgLock(lockFile);
        assertTrue(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS));

        lock.forceUnlock();
        assertFalse(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS), "forceUnlock must delete the lock file");

        // JVM-wide bookkeeping must be cleared too: a brand new acquisition must succeed immediately.
        try (HgLock reacquired = new HgLock(lockFile)) {
            assertTrue(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS));
        }

        // close() on the already force-unlocked instance must not blow up (no JVM map entry left).
        lock.close();
    }

    // ---------------------------------------------------------------
    // Stale-lock recovery edge cases
    // ---------------------------------------------------------------

    @Test
    public void testStaleLockRecoverySkippedWhenOwnerProcessIsAlive(@TempDir Path tempDir) throws Exception {
        File lockFile = new File(tempDir.toFile(), "lock");
        long ownPid = ProcessHandle.current().pid();
        writeFakeOwner(lockFile, "localhost:" + ownPid);

        HgLockException ex = assertThrows(HgLockException.class, () -> {
            try (HgLock lock = new HgLock(lockFile, 150)) {
                fail("Must not acquire: owner pid is this very (alive) process");
            }
        });
        assertTrue(ex.getMessage().contains("Currently held by localhost:" + ownPid),
                "Message should report the live owner: " + ex.getMessage());
        assertTrue(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS),
                "Lock file for a live owner must NOT be removed");
    }

    @Test
    public void testReentrantOptInDeniedToADifferentThread(@TempDir Path tempDir) throws Exception {
        // allowReentrant=true only grants immediate reentrant success to the SAME thread that
        // already owns the lock; a different thread must still contend for it normally.
        File lockFile = new File(tempDir.toFile(), "lock");

        try (HgLock owner = new HgLock(lockFile, 0, true)) {
            AtomicReference<Throwable> caught = new AtomicReference<>();
            Thread other = new Thread(() -> {
                try (HgLock ignored = new HgLock(lockFile, 0, true)) {
                    fail("A different thread must not be granted reentrant success");
                } catch (Throwable t) {
                    caught.set(t);
                }
            });
            other.start();
            other.join(5_000);

            assertFalse(other.isAlive());
            assertInstanceOf(HgLockException.class, caught.get());
        }
    }

    @Test
    public void testTimeoutMessageReportsOwnerVerbatimWhenNoColonSeparator(@TempDir Path tempDir) throws Exception {
        // Malformed owner text (no "host:pid" separator) must not blow up the stale-lock
        // recovery logic; it should just be reported as-is and left untouched.
        File lockFile = new File(tempDir.toFile(), "lock");
        writeFakeOwner(lockFile, "garbage-without-a-colon");

        HgLockException ex = assertThrows(HgLockException.class, () -> {
            try (HgLock lock = new HgLock(lockFile, 150)) {
                fail("Must not acquire: existing lock content is unparsable");
            }
        });
        assertTrue(ex.getMessage().contains("Currently held by garbage-without-a-colon"), ex.getMessage());
        assertTrue(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS),
                "Malformed lock content must be left alone, not deleted");
    }

    @Test
    public void testStaleLockRecoveryTreatsLoopbackAddressAsLocalHost(@TempDir Path tempDir) throws Exception {
        // Exercises the "127.0.0.1".equals(ownerHost) branch of the isLocal OR-chain, distinct
        // from the literal "localhost" and real-hostname branches covered by other tests.
        File lockFile = new File(tempDir.toFile(), "lock");
        writeFakeOwner(lockFile, "127.0.0.1:999999"); // definitely-dead pid

        try (HgLock lock = new HgLock(lockFile, 150)) {
            assertTrue(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS));
            String content = readOwner(lockFile);
            assertTrue(content.contains(String.valueOf(ProcessHandle.current().pid())),
                    "Stale loopback-owned lock should have been recovered and re-owned by us");
        }
    }

    @Test
    public void testStaleLockRecoveryTreatsActualHostnameAsLocalHost(@TempDir Path tempDir) throws Exception {
        // Exercises the localHost.equals(ownerHost) branch of the isLocal OR-chain (as opposed to
        // the literal "localhost"/"127.0.0.1" aliases exercised elsewhere).
        java.lang.reflect.Method m = HgLock.class.getDeclaredMethod("getLocalHostName");
        m.setAccessible(true);
        String realHostName = (String) m.invoke(null);

        File lockFile = new File(tempDir.toFile(), "lock");
        writeFakeOwner(lockFile, realHostName + ":999999"); // definitely-dead pid

        try (HgLock lock = new HgLock(lockFile, 150)) {
            assertTrue(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS));
            String content = readOwner(lockFile);
            assertTrue(content.contains(String.valueOf(ProcessHandle.current().pid())),
                    "Stale lock owned by our own real hostname should have been recovered");
        }
    }

    @Test
    public void testStaleLockRecoveryIgnoresUnparsablePid(@TempDir Path tempDir) throws Exception {
        File lockFile = new File(tempDir.toFile(), "lock");
        writeFakeOwner(lockFile, "localhost:not-a-pid");

        HgLockException ex = assertThrows(HgLockException.class, () -> {
            try (HgLock lock = new HgLock(lockFile, 150)) {
                fail("Must not acquire: owner pid string is not numeric");
            }
        });
        assertTrue(ex.getMessage().contains("Currently held by localhost:not-a-pid"),
                "Message should report the unparsable owner verbatim: " + ex.getMessage());
        assertTrue(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS),
                "Lock file must be left alone when the pid cannot be parsed");
    }

    @Test
    public void testStaleLockRecoveryDeleteFailureReportsClearError(@TempDir Path tempDir) throws Exception {
        Path dir = tempDir.resolve("locked-dir");
        Files.createDirectory(dir);
        File lockFile = dir.resolve("lock").toFile();
        // A definitely-dead pid so the code decides to attempt stale-lock recovery.
        writeFakeOwner(lockFile, "localhost:999999");

        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"));
        try {
            HgLockException ex = assertThrows(HgLockException.class, () -> {
                try (HgLock lock = new HgLock(lockFile, 150)) {
                    fail("Must not acquire: stale-lock deletion is blocked by directory permissions");
                }
            });
            assertTrue(ex.getMessage().contains("Failed to clear stale lock file"),
                    "Message should explain the deletion failure: " + ex.getMessage());
        } finally {
            // Restore write permission so @TempDir cleanup (and the manual delete below) can proceed.
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            Files.deleteIfExists(lockFile.toPath());
        }
    }

    @Test
    public void testAcquireTreatsGenericIOExceptionAsBusyThenTimesOut(@TempDir Path tempDir) throws Exception {
        Path dir = tempDir.resolve("readonly-dir");
        Files.createDirectory(dir);
        File lockFile = dir.resolve("lock").toFile();
        // No lock file exists yet; removing write permission on the parent makes lock
        // creation fail with a plain IOException (not FileAlreadyExistsException).
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"));
        try {
            HgLockException ex = assertThrows(HgLockException.class, () -> {
                try (HgLock lock = new HgLock(lockFile, 150)) {
                    fail("Must not acquire: directory forbids creating the lock file");
                }
            });
            assertTrue(ex.getMessage().contains("Could not acquire exclusive lock"), ex.getMessage());
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
    }

    // ---------------------------------------------------------------
    // Timeout-message branches: unresolvable owner ("another thread in this process")
    // ---------------------------------------------------------------

    @Test
    public void testTimeoutMessageBlamesAnotherThreadWhenNoLockFileExistsYet(@TempDir Path tempDir) throws Exception {
        File lockFile = new File(tempDir.toFile(), "lock");
        String absPath = lockFile.getAbsolutePath();

        // Deterministically simulate "another thread in this JVM already owns this lock,
        // but hasn't created the physical file on disk yet" without relying on a real race.
        Field mapField = HgLock.class.getDeclaredField("JVM_ACTIVE_LOCKS");
        mapField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> jvmLocks = (Map<String, Object>) mapField.get(null);

        Class<?> lockInfoClass = Class.forName("com.github.search5.hg4j.lib.HgLock$LockInfo");
        Constructor<?> ctor = lockInfoClass.getDeclaredConstructor(Thread.class);
        ctor.setAccessible(true);
        Thread otherThread = new Thread(() -> {}); // never started; only used as a distinct identity
        Object fakeLockInfo = ctor.newInstance(otherThread);

        jvmLocks.put(absPath, fakeLockInfo);
        try {
            HgLockException ex = assertThrows(HgLockException.class, () -> {
                try (HgLock lock = new HgLock(lockFile, 0)) {
                    fail("Must not acquire: JVM-wide map says another thread already owns it");
                }
            });
            assertTrue(ex.getMessage().contains("another thread in this process"), ex.getMessage());
        } finally {
            jvmLocks.remove(absPath);
        }
        assertFalse(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    public void testTimeoutMessageBlamesAnotherThreadWhenOwnerUnreadable(@TempDir Path tempDir) throws Exception {
        // A directory sitting at the lock path collides with lock creation (FileAlreadyExistsException)
        // but its "owner" can never be read as text, so readExistingOwnerSilently() returns "".
        File lockFile = new File(tempDir.toFile(), "lock");
        Files.createDirectory(lockFile.toPath());

        HgLockException ex = assertThrows(HgLockException.class, () -> {
            try (HgLock lock = new HgLock(lockFile, 150)) {
                fail("Must not acquire: a directory occupies the lock path");
            }
        });
        assertTrue(ex.getMessage().contains("another thread in this process"), ex.getMessage());
    }

    // ---------------------------------------------------------------
    // Interrupted wait
    // ---------------------------------------------------------------

    @Test
    public void testInterruptedWhileWaitingThrowsAndPreservesInterruptFlag(@TempDir Path tempDir) throws Exception {
        File lockFile = new File(tempDir.toFile(), "lock");

        try (HgLock holder = new HgLock(lockFile)) {
            AtomicReference<Throwable> caught = new AtomicReference<>();
            AtomicReference<Boolean> interruptedFlagObserved = new AtomicReference<>(false);

            Thread waiter = new Thread(() -> {
                try (HgLock ignored = new HgLock(lockFile, 10_000)) {
                    fail("Must not acquire: holder still owns the lock");
                } catch (Throwable t) {
                    caught.set(t);
                    interruptedFlagObserved.set(Thread.currentThread().isInterrupted());
                }
            });
            waiter.start();
            waiter.interrupt();
            waiter.join(5_000);

            assertFalse(waiter.isAlive(), "Waiter thread should have terminated");
            assertNotNull(caught.get(), "Waiter should have thrown");
            assertInstanceOf(HgLockException.class, caught.get());
            assertTrue(caught.get().getMessage().contains("interrupted"), caught.get().getMessage());
            assertInstanceOf(InterruptedException.class, caught.get().getCause());
            assertTrue(interruptedFlagObserved.get(), "Interrupt status must be restored before the exception propagates");
        }
    }

    // ---------------------------------------------------------------
    // Reentrant acquisition and multi-level close()
    // ---------------------------------------------------------------

    @Test
    public void testReentrantAcquisitionSameThreadNestedClose(@TempDir Path tempDir) throws Exception {
        File lockFile = new File(tempDir.toFile(), "lock");

        HgLock outer = new HgLock(lockFile, 0, true);
        assertTrue(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS));

        HgLock inner = new HgLock(lockFile, 0, true); // reentrant success, same thread
        assertTrue(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS));

        inner.close(); // decrements count 2 -> 1, must NOT release the physical lock
        assertTrue(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS),
                "Inner close() must keep the lock alive while the outer scope still holds it");

        outer.close(); // decrements count 1 -> 0, must fully release
        assertFalse(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS),
                "Outer close() must fully release the lock");
    }

    @Test
    public void testCloseFromDifferentThreadBypassesOutstandingReentrantCount(@TempDir Path tempDir) throws Exception {
        // Documented "relaxed ownership guard": close() called from a thread other than the
        // original owner fully releases the lock, even if the reentrant count is still > 0.
        File lockFile = new File(tempDir.toFile(), "lock");

        HgLock outer = new HgLock(lockFile, 0, true);
        HgLock inner = new HgLock(lockFile, 0, true); // count now 2, both owned by this (main) thread
        assertTrue(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS));

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread closer = new Thread(() -> {
            try {
                inner.close();
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        closer.start();
        closer.join(5_000);

        assertNull(failure.get(), "close() from another thread should not throw");
        assertFalse(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS),
                "A close() from a foreign thread releases the lock outright, ignoring the outstanding count");

        // The remaining handle's close() must now be a harmless no-op (JVM map entry is already gone).
        outer.close();
    }

    @Test
    public void testDoubleCloseIsIdempotentAndDoesNotLeakANewerLock(@TempDir Path tempDir) throws Exception {
        // A stray/duplicate close() call on an already-closed instance must be a safe no-op
        // (matching AutoCloseable's documented idempotency recommendation, and matching real
        // Mercurial's lock.release(), which is a harmless no-op once self.held has reached 0 -
        // see mercurial/lock.py Lock.release()). It must NOT reach back into the shared,
        // path-keyed JVM-wide bookkeeping and tear down a *different*, still-active lock that
        // has since been acquired on the very same path.
        File lockFile = new File(tempDir.toFile(), "lock");

        HgLock first = new HgLock(lockFile);
        first.close();
        assertFalse(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS));

        HgLock second = new HgLock(lockFile); // a brand new, unrelated acquisition of the same path
        assertTrue(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS));

        first.close(); // stray duplicate close of the already-closed instance

        assertTrue(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS),
                "A duplicate close() of an unrelated, already-closed instance must not touch the newer lock");

        second.close();
        assertFalse(Files.exists(lockFile.toPath(), LinkOption.NOFOLLOW_LINKS),
                "The still-legitimate owner's close() must still delete the lock file");
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private static void writeFakeOwner(File lockFile, String ownerText) throws Exception {
        try {
            Files.createSymbolicLink(lockFile.toPath(), Path.of(ownerText));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            Files.writeString(lockFile.toPath(), ownerText + "\n", StandardCharsets.UTF_8);
        }
    }

    private static String readOwner(File lockFile) throws Exception {
        if (Files.isSymbolicLink(lockFile.toPath())) {
            return Files.readSymbolicLink(lockFile.toPath()).toString();
        }
        return Files.readString(lockFile.toPath(), StandardCharsets.UTF_8);
    }
}
