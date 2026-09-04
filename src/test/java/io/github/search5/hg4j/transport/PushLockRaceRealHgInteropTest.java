package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import com.sun.net.httpserver.HttpServer;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.Environment;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-hg-CLI verification for backlog item 38 ("동시 push 레이스 컨디션"): does hg4j's SERVER
 * direction (the {@code unbundle}/push-apply path {@link io.github.search5.hg4j.transport.wireprotov1.Wire1Commands}
 * hands off to {@link HgLocalClient#pushWithHooks}) actually serialize concurrent pushes through
 * {@link HgRepository#lockStore()}, and does the losing side's wait/timeout behavior match real
 * hg's own default ({@code repo.lock()}/{@code wlock()} with {@code wait=True}, timing out after
 * {@code ui.timeout} -- 600s default -- rather than failing on the very first contended attempt)?
 *
 * <p>Ground truth for real hg's own behavior was established live against real hg 7.2
 * (2026-09-04, outside this test, not reproduced here to keep the suite fast): with a real {@code
 * hg serve}'s target repository store lock artificially held by a foreign symlink and {@code
 * ui.timeout=2} configured, a real {@code hg push} over HTTP waited ~2s before failing with
 * {@code abort: HTTP Error 500: Internal Server Error} (real hg's own {@code
 * wireprotov1server.unbundle()} does not specially catch {@code error.LockHeld}/{@code
 * LockUnavailable} -- it's an unhandled exception the WSGI/CGI layer turns into a 500), and {@code
 * hg verify} afterward showed the repository untouched (the never-applied push simply never
 * landed). {@code mercurial/lock.py}'s own message shape, confirmed via a bare {@code hg verify}
 * against the same held lock, is {@code "abort: repository <path>: timed out waiting for lock
 * held by '<host>:<pid>'"} -- the SHAPE (wait, then abort on timeout) is what this test class
 * holds hg4j's server to, not the exact wording (hg4j's own wording, via {@link
 * io.github.search5.hg4j.lib.HgLock}, is deliberately different and arguably friendlier: it
 * surfaces as a real {@code abort: <message>} to the pushing real-hg client via a bundle2
 * {@code error:abort} part instead of a bare, unexplained HTTP 500).
 */
@Tag("interop")
public class PushLockRaceRealHgInteropTest {

    @BeforeEach
    public void setUp() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping.");
    }

    // ---------------------------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------------------------

    /** Same shape as {@link HgTestUtils#hg}, but reports the exit code/output instead of
     * throwing -- needed for the genuine two-real-process race test, where BOTH outcomes
     * (success and a legitimate rejection) are acceptable and must be inspected, not just
     * whichever happens to run first. */
    private static HgResult hgNoThrow(File repoDir, String... args) throws Exception {
        String[] cmd = new String[args.length + 5];
        cmd[0] = "hg";
        cmd[1] = "--config";
        cmd[2] = "format.usezstd=false";
        cmd[3] = "--config";
        cmd[4] = "format.revlog-compression=zlib";
        System.arraycopy(args, 0, cmd, 5, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(repoDir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out;
        try (InputStream is = p.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        int code = p.waitFor();
        return new HgResult(code, out);
    }

    private static final class HgResult {
        final int exitCode;
        final String output;
        HgResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
        boolean success() {
            return exitCode == 0;
        }
    }

    /** Configures the repository's own {@code ui.timeout} (real hg's config key for how long a
     * waiting lock acquisition should wait before aborting -- see {@link
     * HgRepository#resolvePushLockTimeoutMs()}) plus {@code [web] allow_push}, then re-opens a
     * fresh {@link HgRepository} so the new hgrc is actually loaded (the constructor reads {@code
     * .hg/hgrc} once, at construction time). */
    private static HgRepository reopenWithTimeoutSeconds(File repoDir, int timeoutSeconds) throws Exception {
        Files.writeString(new File(repoDir, ".hg/hgrc").toPath(),
                "[web]\nallow_push = *\npush_ssl = false\n[ui]\ntimeout = " + timeoutSeconds + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return new HgRepository(repoDir);
    }

    /** Holds the store lock on a background thread for {@code holdMs}, signalling {@code
     * acquired} the moment it actually has the lock so the caller can start racing against it
     * with guaranteed overlap instead of hoping for lucky scheduling. */
    private static Thread holdStoreLockInBackground(HgRepository repo, long holdMs, CountDownLatch acquired,
                                                      AtomicReference<Exception> holderError) {
        Thread holder = new Thread(() -> {
            try (HgLock lock = repo.lockStore(0)) {
                acquired.countDown();
                Thread.sleep(holdMs);
            } catch (Exception e) {
                holderError.set(e);
                acquired.countDown();
            }
        }, "push-lock-race-holder");
        holder.setDaemon(true);
        holder.start();
        return holder;
    }

    // ---------------------------------------------------------------------------------------
    // HTTP: HgHttpWireServer as the server, real hg CLI as the pushing client
    // ---------------------------------------------------------------------------------------

    @Test
    public void httpServerWaitsOutContendedStoreLockThenAcceptsRealHgPush(@TempDir Path tempDir) throws Exception {
        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgRepository bootstrap = Hg.init().setDirectory(serverRepoDir).call();
        Files.writeString(new File(serverRepoDir, "a.txt").toPath(), "base");
        new AddCommand(bootstrap).call();
        new CommitCommand(bootstrap).setMessage("c0").setAuthor("dev").call();

        // Generous 30s server-side ui.timeout -- the lock will only be held for ~1.5s, so a
        // correct wait-then-retry implementation finishes long before this ever matters; it only
        // guards against the test itself hanging forever if the fix regresses to "never retry".
        HgRepository serverRepo = reopenWithTimeoutSeconds(serverRepoDir, 30);

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", new HgHttpWireServer(serverRepo));
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";

            File clientDir = tempDir.resolve("client").toFile();
            HgTestUtils.hg(tempDir.toFile(), "clone", baseUrl, clientDir.getAbsolutePath());
            Files.writeString(new File(clientDir, "b.txt").toPath(), "pushed under lock contention");
            HgTestUtils.hg(clientDir, "add", "b.txt");
            HgTestUtils.hg(clientDir, "commit", "-u", "T", "-m", "c1");

            CountDownLatch lockAcquired = new CountDownLatch(1);
            AtomicReference<Exception> holderError = new AtomicReference<>();
            long holdMs = 1500;
            holdStoreLockInBackground(serverRepo, holdMs, lockAcquired, holderError);
            assertTrue(lockAcquired.await(5, TimeUnit.SECONDS), "background holder must acquire the store lock");
            assertNull(holderError.get(), "background holder must successfully hold the store lock");

            long start = System.nanoTime();
            HgTestUtils.hg(clientDir, "push", baseUrl);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertTrue(elapsedMs >= holdMs - 200,
                    "push must have actually WAITED for the lock (matching real hg's own wait-then-retry "
                            + "shape) rather than winning by luck -- elapsed=" + elapsedMs + "ms, held for " + holdMs + "ms");

            serverRepo.clearRevlogCache();
            File clIdx = new File(serverRepo.getStoreDir(), "00changelog.i");
            File clDat = new File(serverRepo.getStoreDir(), "00changelog.d");
            var cl = serverRepo.getRevlog(clIdx, clDat);
            assertEquals(2, cl.getRevisionCount(), "the pushed commit must land once the contended lock clears");

            String verify = HgTestUtils.hg(serverRepoDir, "verify");
            assertFalse(verify.toLowerCase().contains("error"), "server repository must remain valid: " + verify);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void httpServerAbortsRealHgPushAfterConfiguredTimeoutWhenStoreLockHeldTooLong(@TempDir Path tempDir) throws Exception {
        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgRepository bootstrap = Hg.init().setDirectory(serverRepoDir).call();
        Files.writeString(new File(serverRepoDir, "a.txt").toPath(), "base");
        new AddCommand(bootstrap).call();
        new CommitCommand(bootstrap).setMessage("c0").setAuthor("dev").call();

        // Short 1s server-side ui.timeout -- the lock is held for far longer (5s) than that, so
        // the push MUST fail via the timeout path, not merely by waiting for the whole hold.
        int timeoutSeconds = 1;
        HgRepository serverRepo = reopenWithTimeoutSeconds(serverRepoDir, timeoutSeconds);

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", new HgHttpWireServer(serverRepo));
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";

            File clientDir = tempDir.resolve("client").toFile();
            HgTestUtils.hg(tempDir.toFile(), "clone", baseUrl, clientDir.getAbsolutePath());
            Files.writeString(new File(clientDir, "b.txt").toPath(), "should never land");
            HgTestUtils.hg(clientDir, "add", "b.txt");
            HgTestUtils.hg(clientDir, "commit", "-u", "T", "-m", "c1");

            CountDownLatch lockAcquired = new CountDownLatch(1);
            AtomicReference<Exception> holderError = new AtomicReference<>();
            long holdMs = 5000;
            Thread holder = holdStoreLockInBackground(serverRepo, holdMs, lockAcquired, holderError);
            assertTrue(lockAcquired.await(5, TimeUnit.SECONDS), "background holder must acquire the store lock");
            assertNull(holderError.get(), "background holder must successfully hold the store lock");

            long start = System.nanoTime();
            HgResult result = hgNoThrow(clientDir, "push", baseUrl);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertFalse(result.success(), "push against a lock held well past ui.timeout must fail: " + result.output);
            assertTrue(elapsedMs >= (timeoutSeconds * 1000L) - 300,
                    "push must have WAITED roughly the configured ui.timeout before giving up, not failed "
                            + "immediately -- elapsed=" + elapsedMs + "ms, configured timeout=" + (timeoutSeconds * 1000) + "ms");
            assertTrue(elapsedMs < holdMs - 500,
                    "push must have timed out via ui.timeout, not merely by outlasting the whole "
                            + holdMs + "ms hold -- elapsed=" + elapsedMs + "ms");

            holder.join(TimeUnit.SECONDS.toMillis(10));

            // The rejected push must never have landed, and the repository must remain intact.
            serverRepo.clearRevlogCache();
            File clIdx = new File(serverRepo.getStoreDir(), "00changelog.i");
            File clDat = new File(serverRepo.getStoreDir(), "00changelog.d");
            var cl = serverRepo.getRevlog(clIdx, clDat);
            assertEquals(1, cl.getRevisionCount(), "a push that timed out on the lock must not have landed anything");

            String verify = HgTestUtils.hg(serverRepoDir, "verify");
            assertFalse(verify.toLowerCase().contains("error"), "server repository must remain valid after the timeout: " + verify);

            // And the repository must not be left wedged: a subsequent, uncontended push succeeds.
            HgTestUtils.hg(clientDir, "push", baseUrl);
            serverRepo.clearRevlogCache();
            var cl2 = serverRepo.getRevlog(clIdx, clDat);
            assertEquals(2, cl2.getRevisionCount(), "a later uncontended push must still succeed normally");
        } finally {
            server.stop(0);
        }
    }

    /**
     * The genuine two-real-hg-process race, HTTP transport: two independent real {@code hg push}
     * processes are started as close to simultaneously as the JVM can arrange (both threads
     * block on a shared {@link CountDownLatch} until released together), racing for the SAME
     * hg4j-served repository's store lock. Per the task's own acceptance criteria this allows
     * either outcome (both land sequentially, or one lands and the other is legitimately
     * rejected) -- what must NEVER happen is a corrupted store, which real host-native {@code hg
     * verify} is used to confirm.
     */
    @Test
    public void twoRealHgClientsRacingOverHttpNeverCorruptTheHg4jServedRepository(@TempDir Path tempDir) throws Exception {
        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgRepository bootstrap = Hg.init().setDirectory(serverRepoDir).call();
        Files.writeString(new File(serverRepoDir, "a.txt").toPath(), "base");
        new AddCommand(bootstrap).call();
        new CommitCommand(bootstrap).setMessage("c0").setAuthor("dev").call();
        HgRepository serverRepo = reopenWithTimeoutSeconds(serverRepoDir, 30);

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/", new HgHttpWireServer(serverRepo));
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";

            File clientA = tempDir.resolve("client_a").toFile();
            File clientB = tempDir.resolve("client_b").toFile();
            HgTestUtils.hg(tempDir.toFile(), "clone", baseUrl, clientA.getAbsolutePath());
            HgTestUtils.hg(tempDir.toFile(), "clone", baseUrl, clientB.getAbsolutePath());

            Files.writeString(new File(clientA, "from-a.txt").toPath(), "client A's commit");
            HgTestUtils.hg(clientA, "add", "from-a.txt");
            HgTestUtils.hg(clientA, "commit", "-u", "A", "-m", "cA");

            Files.writeString(new File(clientB, "from-b.txt").toPath(), "client B's commit");
            HgTestUtils.hg(clientB, "add", "from-b.txt");
            HgTestUtils.hg(clientB, "commit", "-u", "B", "-m", "cB");

            CountDownLatch go = new CountDownLatch(1);
            AtomicReference<HgResult> resultA = new AtomicReference<>();
            AtomicReference<HgResult> resultB = new AtomicReference<>();
            AtomicReference<Exception> errorA = new AtomicReference<>();
            AtomicReference<Exception> errorB = new AtomicReference<>();

            Thread threadA = new Thread(() -> {
                try {
                    go.await();
                    resultA.set(hgNoThrow(clientA, "push", baseUrl));
                } catch (Exception e) {
                    errorA.set(e);
                }
            }, "race-client-a");
            Thread threadB = new Thread(() -> {
                try {
                    go.await();
                    resultB.set(hgNoThrow(clientB, "push", baseUrl));
                } catch (Exception e) {
                    errorB.set(e);
                }
            }, "race-client-b");
            threadA.start();
            threadB.start();
            go.countDown();
            threadA.join(TimeUnit.SECONDS.toMillis(30));
            threadB.join(TimeUnit.SECONDS.toMillis(30));

            assertNull(errorA.get(), "client A's push thread must not itself throw: " + errorA.get());
            assertNull(errorB.get(), "client B's push thread must not itself throw: " + errorB.get());
            assertNotNull(resultA.get(), "client A's push must complete");
            assertNotNull(resultB.get(), "client B's push must complete");

            // The store must never be corrupted, whichever way the race went -- real hg's own
            // verify is the oracle here, exactly as the task requires.
            String verify = HgTestUtils.hg(serverRepoDir, "verify");
            assertFalse(verify.toLowerCase().contains("error"),
                    "server repository must remain valid after a genuine concurrent push race: " + verify
                            + "\nclientA: exit=" + resultA.get().exitCode + " out=" + resultA.get().output
                            + "\nclientB: exit=" + resultB.get().exitCode + " out=" + resultB.get().output);

            // Both outcomes are acceptable per the task's own acceptance criteria: either both
            // land (sequential, non-conflicting apply) or exactly one lands (the other legitimately
            // rejected, e.g. by checkheads on stale data) -- but at least one MUST land, since
            // neither push should be starved outright by lock contention alone under a 30s timeout.
            // Empirically (2026-09-04) BOTH land here: hg4j's server-side unbundle apply path
            // acquires the store lock and serializes correctly (that's what this whole class
            // verifies), but -- unlike real hg's own exchange.unbundle(), which re-runs
            // check_heads() AFTER acquiring the lock and raises error.PushRaced if the target
            // moved out from under a stale client -- it never independently re-validates heads
            // against what each client actually saw. Real hg's client-side checkheads (which IS
            // ported, in PushCommand) only sees the SERVER's heads as of just before the race
            // started, so it can't catch this either. Net effect: hg4j ends up with 2 heads here
            // where a real-hg-served equivalent would have rejected the loser as raced. This is a
            // real, distinct gap from the lock-wait/timeout behavior this class is about --
            // reported explicitly rather than silently masked, see backlog item 38's completion
            // note. It does not violate this test's own acceptance bar (repo integrity + no
            // partial/duplicate apply), which is all backlog item 38 itself asks for.
            int successes = (resultA.get().success() ? 1 : 0) + (resultB.get().success() ? 1 : 0);
            assertTrue(successes >= 1, "at least one of the two racing pushes must succeed: "
                    + "clientA exit=" + resultA.get().exitCode + " clientB exit=" + resultB.get().exitCode);

            serverRepo.clearRevlogCache();
            File clIdx = new File(serverRepo.getStoreDir(), "00changelog.i");
            File clDat = new File(serverRepo.getStoreDir(), "00changelog.d");
            var cl = serverRepo.getRevlog(clIdx, clDat);
            int expectedRevisions = 1 + successes;
            assertEquals(expectedRevisions, cl.getRevisionCount(),
                    "revision count must exactly match the number of pushes real hg itself reported as successful "
                            + "(no silent partial/duplicate apply): successes=" + successes);
        } finally {
            server.stop(0);
        }
    }

    // ---------------------------------------------------------------------------------------
    // SSH: HgSshWireServer as the server, real hg CLI as the pushing client
    // ---------------------------------------------------------------------------------------

    private static boolean isSshKeygenAvailable() {
        try {
            return new ProcessBuilder("ssh-keygen", "-?").start().waitFor() >= 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void runProcess(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = p.getInputStream().readAllBytes();
        int code = p.waitFor();
        if (code != 0) {
            throw new AssertionError(String.join(" ", cmd) + " failed (" + code + "): " + new String(out));
        }
    }

    /** Test-local SSH fixture bundle -- mirrors {@link HgSshWireServerRealHgInteropTest}'s setup,
     * duplicated here (rather than shared) so this class's lifecycle (one server per test, not
     * one per class) can host a DIFFERENT repository per test method without interference. */
    private static final class SshFixture implements AutoCloseable {
        final SshServer sshServer;
        final int port;
        final File sshKeyFile;
        final Path tempDir;

        SshFixture(Path tempDir) throws Exception {
            this.tempDir = tempDir;
            sshKeyFile = tempDir.resolve("id_test").toFile();
            runProcess("ssh-keygen", "-t", "rsa", "-b", "2048", "-f", sshKeyFile.getAbsolutePath(), "-N", "");

            sshServer = SshServer.setUpDefaultServer();
            sshServer.setPort(0);
            Path hostKey = tempDir.resolve("host_key");
            sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
            sshServer.setPublickeyAuthenticator(AcceptAllPublickeyAuthenticator.INSTANCE);
            sshServer.start();
            port = sshServer.getPort();
        }

        void serve(HgRepository repo) {
            sshServer.setCommandFactory((channel, command) -> new SharedRepoHgWireCommand(command, repo));
        }

        String url(File repoDir) {
            return "ssh://127.0.0.1:" + port + "/" + repoDir.getAbsolutePath();
        }

        String[] withSshConfig(String... args) {
            String[] withConfig = new String[args.length + 2];
            withConfig[0] = "--config";
            withConfig[1] = "ui.ssh=ssh -i " + sshKeyFile.getAbsolutePath()
                    + " -o StrictHostKeyChecking=no -o UserKnownHostsFile=" + tempDir.resolve("known_hosts")
                    + " -o IdentitiesOnly=yes -p " + port;
            System.arraycopy(args, 0, withConfig, 2, args.length);
            return withConfig;
        }

        @Override
        public void close() throws IOException {
            sshServer.stop(true);
        }
    }

    @Test
    public void sshServerWaitsOutContendedStoreLockThenAcceptsRealHgPush(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(isSshKeygenAvailable(), "ssh-keygen is not available. Skipping.");

        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgRepository bootstrap = Hg.init().setDirectory(serverRepoDir).call();
        Files.writeString(new File(serverRepoDir, "a.txt").toPath(), "base");
        new AddCommand(bootstrap).call();
        new CommitCommand(bootstrap).setMessage("c0").setAuthor("dev").call();
        HgRepository serverRepo = reopenWithTimeoutSeconds(serverRepoDir, 30);

        try (SshFixture ssh = new SshFixture(tempDir)) {
            ssh.serve(serverRepo);
            String url = ssh.url(serverRepoDir);

            File clientDir = tempDir.resolve("client").toFile();
            HgTestUtils.hg(tempDir.toFile(), ssh.withSshConfig("clone", url, clientDir.getAbsolutePath()));
            Files.writeString(new File(clientDir, "b.txt").toPath(), "pushed under lock contention over ssh");
            HgTestUtils.hg(clientDir, "add", "b.txt");
            HgTestUtils.hg(clientDir, "commit", "-u", "T", "-m", "c1");

            CountDownLatch lockAcquired = new CountDownLatch(1);
            AtomicReference<Exception> holderError = new AtomicReference<>();
            long holdMs = 1500;
            holdStoreLockInBackground(serverRepo, holdMs, lockAcquired, holderError);
            assertTrue(lockAcquired.await(5, TimeUnit.SECONDS), "background holder must acquire the store lock");
            assertNull(holderError.get(), "background holder must successfully hold the store lock");

            long start = System.nanoTime();
            HgTestUtils.hg(clientDir, ssh.withSshConfig("push", url));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertTrue(elapsedMs >= holdMs - 200,
                    "SSH push must have actually WAITED for the lock -- elapsed=" + elapsedMs + "ms, held for " + holdMs + "ms");

            serverRepo.clearRevlogCache();
            File clIdx = new File(serverRepo.getStoreDir(), "00changelog.i");
            File clDat = new File(serverRepo.getStoreDir(), "00changelog.d");
            var cl = serverRepo.getRevlog(clIdx, clDat);
            assertEquals(2, cl.getRevisionCount(), "the pushed commit must land once the contended lock clears");

            String verify = HgTestUtils.hg(serverRepoDir, "verify");
            assertFalse(verify.toLowerCase().contains("error"), "server repository must remain valid: " + verify);
        }
    }

    @Test
    public void sshServerAbortsRealHgPushAfterConfiguredTimeoutWhenStoreLockHeldTooLong(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(isSshKeygenAvailable(), "ssh-keygen is not available. Skipping.");

        File serverRepoDir = tempDir.resolve("server_repo").toFile();
        HgRepository bootstrap = Hg.init().setDirectory(serverRepoDir).call();
        Files.writeString(new File(serverRepoDir, "a.txt").toPath(), "base");
        new AddCommand(bootstrap).call();
        new CommitCommand(bootstrap).setMessage("c0").setAuthor("dev").call();
        int timeoutSeconds = 1;
        HgRepository serverRepo = reopenWithTimeoutSeconds(serverRepoDir, timeoutSeconds);

        try (SshFixture ssh = new SshFixture(tempDir)) {
            ssh.serve(serverRepo);
            String url = ssh.url(serverRepoDir);

            File clientDir = tempDir.resolve("client").toFile();
            HgTestUtils.hg(tempDir.toFile(), ssh.withSshConfig("clone", url, clientDir.getAbsolutePath()));
            Files.writeString(new File(clientDir, "b.txt").toPath(), "should never land");
            HgTestUtils.hg(clientDir, "add", "b.txt");
            HgTestUtils.hg(clientDir, "commit", "-u", "T", "-m", "c1");

            CountDownLatch lockAcquired = new CountDownLatch(1);
            AtomicReference<Exception> holderError = new AtomicReference<>();
            long holdMs = 5000;
            Thread holder = holdStoreLockInBackground(serverRepo, holdMs, lockAcquired, holderError);
            assertTrue(lockAcquired.await(5, TimeUnit.SECONDS), "background holder must acquire the store lock");
            assertNull(holderError.get(), "background holder must successfully hold the store lock");

            long start = System.nanoTime();
            String[] pushCmd = ssh.withSshConfig("push", url);
            String[] full = new String[pushCmd.length + 4];
            full[0] = "--config";
            full[1] = "format.usezstd=false";
            full[2] = "--config";
            full[3] = "format.revlog-compression=zlib";
            System.arraycopy(pushCmd, 0, full, 4, pushCmd.length);
            HgResult result = hgNoThrow(clientDir, full);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertFalse(result.success(), "SSH push against a lock held well past ui.timeout must fail: " + result.output);
            assertTrue(elapsedMs >= (timeoutSeconds * 1000L) - 300,
                    "SSH push must have WAITED roughly the configured ui.timeout before giving up -- elapsed="
                            + elapsedMs + "ms, configured timeout=" + (timeoutSeconds * 1000) + "ms");
            assertTrue(elapsedMs < holdMs - 500,
                    "SSH push must have timed out via ui.timeout, not merely by outlasting the whole "
                            + holdMs + "ms hold -- elapsed=" + elapsedMs + "ms");

            holder.join(TimeUnit.SECONDS.toMillis(10));

            serverRepo.clearRevlogCache();
            File clIdx = new File(serverRepo.getStoreDir(), "00changelog.i");
            File clDat = new File(serverRepo.getStoreDir(), "00changelog.d");
            var cl = serverRepo.getRevlog(clIdx, clDat);
            assertEquals(1, cl.getRevisionCount(), "a push that timed out on the lock must not have landed anything");

            String verify = HgTestUtils.hg(serverRepoDir, "verify");
            assertFalse(verify.toLowerCase().contains("error"), "server repository must remain valid after the timeout: " + verify);

            HgTestUtils.hg(clientDir, ssh.withSshConfig("push", url));
            serverRepo.clearRevlogCache();
            var cl2 = serverRepo.getRevlog(clIdx, clDat);
            assertEquals(2, cl2.getRevisionCount(), "a later uncontended SSH push must still succeed normally");
        }
    }

    /** Mirrors {@link HgSshWireServerRealHgInteropTest}'s {@code SharedRepoHgWireCommand}: one
     * {@link HgRepository} instance shared across every SSH connection the factory creates, so
     * this test's own store-lock manipulation (via that same instance) is actually visible to
     * the server-side unbundle apply path. */
    private static class SharedRepoHgWireCommand implements Command, Runnable {
        private final String command;
        private final HgRepository repo;
        private InputStream in;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback callback;
        private Thread thread;

        SharedRepoHgWireCommand(String command, HgRepository repo) {
            this.command = command;
            this.repo = repo;
        }

        @Override public void setInputStream(InputStream in) { this.in = in; }
        @Override public void setOutputStream(OutputStream out) { this.out = out; }
        @Override public void setErrorStream(OutputStream err) { this.err = err; }
        @Override public void setExitCallback(ExitCallback callback) { this.callback = callback; }

        @Override
        public void start(ChannelSession session, Environment env) {
            thread = new Thread(this, "hg-ssh-wire-push-race-test");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void destroy(ChannelSession session) {
            if (thread != null) {
                thread.interrupt();
            }
        }

        @Override
        public void run() {
            try {
                new HgSshWireServer(repo).handleConnection(in, out);
                callback.onExit(0);
            } catch (Exception e) {
                try {
                    err.write((e + "\n").getBytes());
                    err.flush();
                } catch (IOException ignored) {
                }
                callback.onExit(1);
            }
        }
    }
}
