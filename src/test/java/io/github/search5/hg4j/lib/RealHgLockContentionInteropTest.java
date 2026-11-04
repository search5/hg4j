package io.github.search5.hg4j.lib;

import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.HgHook;
import io.github.search5.hg4j.testsupport.LocalHg;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * hg4j and the real {@code hg} write to the same repository at the same time. The locks are what keeps them apart, so every scenario is
 * run twice: with the locks (nothing may be damaged) and with hg4j's locks switched off while the real hg still takes its own (the damage
 * must show). The second run proves that the scenario really depends on the locks; a scenario that passes without them tests nothing.
 */
@Tag("interop")
class RealHgLockContentionInteropTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void requireHg() {
        Assumptions.assumeTrue(LocalHg.isAvailable(), LocalHg::unavailableReason);
    }

    @AfterEach
    void locksOn() {
        HgLock.disabledForTests = false;
    }

    // ---------------------------------------------------------------- helpers

    private record Outcome(int exit, String output) {
    }

    private Outcome realHg(File dir, String... args) throws Exception {
        ProcessBuilder pb = LocalHg.processBuilder(dir.toPath(), args);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return new Outcome(-1, "timed out\n" + out);
        }
        return new Outcome(p.exitValue(), out);
    }

    /** A repository with one commit, made by hg4j, whose own commands wait up to {@code seconds} for a lock. */
    private File repositoryWithOneCommit(String name, int seconds) throws Exception {
        File dir = tempDir.resolve(name).toFile();
        Hg.init().setDirectory(dir).call().close();
        try (HgRepository repo = new HgRepository(dir)) {
            Files.writeString(new File(dir, "base.txt").toPath(), "base\n");
            new AddCommand(repo).call();
            new CommitCommand(repo).setMessage("base").setAuthor("t").call();
        }
        Files.writeString(new File(dir, ".hg/hgrc").toPath(), "[ui]\ntimeout = " + seconds + "\nusername = t\n");
        return dir;
    }

    private void hg4jCommit(File dir, String file, String message, HgHook inTransaction) throws Exception {
        try (HgRepository repo = new HgRepository(dir)) {
            Files.writeString(new File(dir, file).toPath(), file + "\n");
            new AddCommand(repo).call();
            CommitCommand commit = new CommitCommand(repo).setMessage(message).setAuthor("t");
            if (inTransaction != null) {
                commit.registerPreCommitHook(inTransaction);
            }
            commit.call();
        }
    }

    /** What is wrong with the repository after both writers are done; {@code null} when nothing is. */
    private String damage(File dir, int expectedChangesets) throws Exception {
        Outcome verify = realHg(dir, "verify");
        if (verify.exit() != 0) {
            return "hg verify failed:\n" + verify.output();
        }
        Outcome count = realHg(dir, "log", "--template", "{rev} ");
        int revisions = count.output().trim().isEmpty() ? 0 : count.output().trim().split("\\s+").length;
        if (revisions != expectedChangesets) {
            return "expected " + expectedChangesets + " changesets but the log shows: " + count.output();
        }
        Outcome status = realHg(dir, "status");
        if (!status.output().trim().isEmpty()) {
            return "the working copy is not clean afterwards:\n" + status.output();
        }
        return null;
    }

    private static HgHook sleepingHook(CountDownLatch inside, long millis) {
        return ctx -> {
            inside.countDown();
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return true;
        };
    }

    // ---------------------------------------------------------------- scenario 1: hg4j holds the locks, the real hg commits meanwhile

    private String realCommitWhileHg4jCommits() throws Exception {
        File dir = repositoryWithOneCommit("hg4j-first", 60);
        CountDownLatch inside = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> mine = pool.submit(() -> {
                hg4jCommit(dir, "from-hg4j.txt", "from hg4j", sleepingHook(inside, 3000));
                return null;
            });
            assertNotNull(inside.await(30, TimeUnit.SECONDS) ? "in" : null, "hg4j never reached its transaction");
            Files.writeString(new File(dir, "from-hg.txt").toPath(), "from-hg.txt\n");
            Outcome theirs = realHg(dir, "commit", "-A", "-m", "from hg", "-u", "t", "from-hg.txt");
            mine.get(60, TimeUnit.SECONDS);
            if (theirs.exit() != 0) {
                return "the real hg commit failed: " + theirs.output();
            }
            return damage(dir, 3);
        } catch (Exception e) {
            return "hg4j commit failed: " + e;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void withTheLocksARealCommitWaitsForTheHg4jCommitInProgress() throws Exception {
        assertNull(realCommitWhileHg4jCommits());
    }

    @Test
    void withoutTheLocksARealCommitDuringAnHg4jCommitDamagesTheRepository() throws Exception {
        HgLock.disabledForTests = true;
        String damage = realCommitWhileHg4jCommits();
        System.out.println("DAMAGE: " + damage);
        assertNotNull(damage, "the scenario passed without the locks, so it does not need them");
    }

    // ---------------------------------------------------------------- scenario 2: the real hg holds the locks, hg4j commits meanwhile

    private String hg4jCommitWhileRealHgCommits() throws Exception {
        File dir = repositoryWithOneCommit("hg-first", 60);
        Files.writeString(new File(dir, "from-hg.txt").toPath(), "from-hg.txt\n");
        ProcessBuilder pb = LocalHg.processBuilder(dir.toPath(), "commit", "-A", "-m", "from hg", "-u", "t", "from-hg.txt",
                "--config", "hooks.pretxncommit.slow=sleep 3");
        pb.redirectErrorStream(true);
        Process real = pb.start();
        try {
            // hg takes both locks before it runs the hook; give it time to get there
            File wlock = new File(dir, ".hg/wlock");
            long deadline = System.currentTimeMillis() + 30_000;
            while (!Files.isSymbolicLink(wlock.toPath()) && !wlock.exists() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            Thread.sleep(700);
            hg4jCommit(dir, "from-hg4j.txt", "from hg4j", null);
            String out = new String(real.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!real.waitFor(60, TimeUnit.SECONDS) || real.exitValue() != 0) {
                return "the real hg commit failed: " + out;
            }
            return damage(dir, 3);
        } catch (Exception e) {
            return "hg4j commit failed: " + e;
        } finally {
            real.destroyForcibly();
        }
    }

    @Test
    void withTheLocksAnHg4jCommitWaitsForTheRealCommitInProgress() throws Exception {
        assertNull(hg4jCommitWhileRealHgCommits());
    }

    @Test
    void withoutTheLocksAnHg4jCommitDuringARealCommitDamagesTheRepository() throws Exception {
        HgLock.disabledForTests = true;
        String damage = hg4jCommitWhileRealHgCommits();
        System.out.println("DAMAGE: " + damage);
        assertNotNull(damage, "the scenario passed without the locks, so it does not need them");
    }

    // ---------------------------------------------------------------- scenario 3: the real hg is refused while hg4j holds the locks

    private String realCommitWhileHg4jHoldsTheLocks() throws Exception {
        File dir = repositoryWithOneCommit("held", 1);
        Files.writeString(new File(dir, "from-hg.txt").toPath(), "from-hg.txt\n");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (HgRepository repo = new HgRepository(dir); HgLock wlock = repo.lockWorkingCopy(); HgLock lock = repo.lockStore()) {
                held.countDown();
                release.await();
            } catch (Exception ignored) {
                held.countDown();
            }
        });
        holder.start();
        try {
            held.await(30, TimeUnit.SECONDS);
            Outcome theirs = realHg(dir, "commit", "-A", "-m", "from hg", "-u", "t", "from-hg.txt", "--config", "ui.timeout=1");
            if (theirs.exit() == 0) {
                return "the real hg committed while hg4j held both locks";
            }
            if (!theirs.output().contains("lock")) {
                return "the real hg failed, but not over the lock: " + theirs.output();
            }
            return null;
        } finally {
            release.countDown();
            holder.join(30_000);
        }
    }

    @Test
    void withTheLocksARealCommitIsRefusedWhileHg4jHoldsThem() throws Exception {
        assertNull(realCommitWhileHg4jHoldsTheLocks());
    }

    @Test
    void withoutTheLocksARealCommitIsNotRefused() throws Exception {
        HgLock.disabledForTests = true;
        String damage = realCommitWhileHg4jHoldsTheLocks();
        System.out.println("DAMAGE: " + damage);
        assertNotNull(damage, "the scenario passed without the locks, so it does not need them");
    }
}
