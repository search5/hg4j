package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.gpg.GpgKeyring;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.testsupport.GpgHome;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code hg sign} as the {@code gpg} extension does it, with a real {@code gpg} key: only the working copy lock is held for the
 * command (the commit takes the store lock itself), a failure after {@code .hgsigs} was written leaves the file as it is, and the
 * commit holds {@code .hgsigs} only.
 */
class SignOriginalBehaviorTest {

    @TempDir
    static Path shared;

    private static GpgHome gpg;
    private static GpgKeyring keyring;

    @TempDir
    Path tmp;
    private HgRepository repo;
    private File dir;
    private byte[] c0;

    @BeforeAll
    static void createKey() throws Exception {
        Assumptions.assumeTrue(GpgHome.isAvailable(), "gpg is not available");
        gpg = GpgHome.create(shared.resolve("gnupg"));
        gpg.generateKey("hg4j signer <signer@example.com>", "ed25519", "never", "");
        keyring = GpgKeyring.fromGnupgHome(gpg.path()).withSecretKeys("export", gpg.exportSecret(""));
    }

    @AfterAll
    static void stopAgent() throws Exception {
        if (gpg != null) {
            gpg.close();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        dir = tmp.resolve("repo").toFile();
        assertTrue(dir.mkdirs());
        repo = Hg.init().setDirectory(dir).call();
        Files.writeString(new File(dir, "a.txt").toPath(), "a\n");
        new AddCommand(repo).call();
        c0 = new CommitCommand(repo).setAuthor("dev").setMessage("c0").call();
    }

    private SignCommand sign() {
        return new SignCommand(repo).setKeyring(keyring).setAuthor("dev");
    }

    private void holding(boolean workingCopy, Runnable body) throws Exception {
        CountDownLatch taken = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread holder = new Thread(() -> {
            try (HgLock ignored = workingCopy ? repo.lockWorkingCopy() : repo.lockStore()) {
                taken.countDown();
                release.await();
            } catch (Throwable t) {
                failure.set(t);
                taken.countDown();
            }
        });
        holder.start();
        assertTrue(taken.await(10, TimeUnit.SECONDS));
        assertNull(failure.get());
        try {
            body.run();
        } finally {
            release.countDown();
            holder.join();
        }
    }

    @Test
    void theCommitHoldsHgsigsOnlyAndOtherChangesStayUncommitted() throws Exception {
        Files.writeString(new File(dir, "a.txt").toPath(), "changed\n");
        sign().setNodeId(c0).call();
        Status status = new StatusCommand(repo).call();
        assertTrue(status.getModified().contains("a.txt"), "the other change was not committed: " + status.getModified());
        assertTrue(new LogCommand(repo).call().size() == 2);
    }

    @Test
    void aSigningWithTheStoreLockHeldElsewhereLeavesHgsigsAsItIs() throws Exception {
        holding(false, () -> assertThrows(HgLockException.class, () -> sign().setNodeId(c0).call()));
        // wlock only for the command: the signature was written and added, the commit could not take the store lock; nothing is put back
        File sigs = new File(dir, ".hgsigs");
        assertTrue(sigs.isFile() && sigs.length() > 0);
        assertTrue(new StatusCommand(repo).call().getAdded().contains(".hgsigs"));
        assertEquals(1, new LogCommand(repo).call().size());
    }

    @Test
    void theWorkingCopyLockHeldElsewhereStopsTheSigningBeforeAnythingIsWritten() throws Exception {
        holding(true, () -> assertThrows(HgLockException.class, () -> sign().setNodeId(c0).call()));
        assertFalse(new File(dir, ".hgsigs").exists());
    }

    @Test
    void aChangedOrUntrackedHgsigsIsRefusedWithMercurialsWords() throws Exception {
        Files.writeString(new File(dir, ".hgsigs").toPath(), "stray\n");
        HgValidationException e = assertThrows(HgValidationException.class, () -> sign().setNodeId(c0).call());
        assertEquals("working copy of .hgsigs is changed \n(please commit .hgsigs manually)", e.getMessage());
    }
}
