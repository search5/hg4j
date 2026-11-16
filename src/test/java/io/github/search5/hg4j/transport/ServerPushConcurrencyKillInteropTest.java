package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.RecoverCommand;
import io.github.search5.hg4j.lib.HgRepository;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenarios D and E of Phase 1 in {@code llm-wiki/decisions/server-push-concurrency-roadmap.md}.
 *
 * <ul>
 * <li>D: a server JVM is killed with {@code SIGKILL} while {@code pretxnchangegroup} runs (changesets appended, journal and locks
 * held). hg4j refuses further pushes as the original does, the real {@code hg recover} rolls the push back, and the push can be
 * repeated.</li>
 * <li>E: a lock left by a dead owner. On the same host hg4j breaks it (the owner pid is dead), like the original. On another host
 * neither can tell whether the owner lives, so both wait for {@code ui.timeout} and give up, leaving the repository untouched.</li>
 * </ul>
 */
@Tag("interop")
class ServerPushConcurrencyKillInteropTest {

    @TempDir
    Path tempDir;

    private File serverDir;
    private Server server;
    private Process helper;

    @BeforeEach
    void prepare() throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "real hg required");
        serverDir = tempDir.resolve("server").toFile();
        HgRepository repo = Hg.init().setDirectory(serverDir).call();
        Files.writeString(new File(serverDir, "base.txt").toPath(), "base\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("base").setAuthor("dev").call();
        HgTestUtils.hg(serverDir, "phase", "--public", "-r", "0");
        Files.writeString(new File(serverDir, ".hg/hgrc").toPath(), "[web]\nallow_push = *\npush_ssl = false\n");
    }

    @AfterEach
    void cleanUp() {
        if (server != null) {
            HgTestUtils.stop(server);
        }
        if (helper != null) {
            helper.destroyForcibly();
        }
    }

    private static final class Result {
        final int code;
        final String output;
        Result(int code, String output) {
            this.code = code;
            this.output = output;
        }
    }

    private static Result run(File dir, String... args) throws Exception {
        return runWith("hg", dir, args);
    }

    private static Result runWith(String hgBinary, File dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(hgBinary, "--config", "format.usezstd=false", "--config", "format.revlog-compression=zlib"));
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out;
        try (InputStream is = p.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
        return new Result(p.waitFor(), out);
    }

    private File client(String name, String file) throws Exception {
        File dir = tempDir.resolve(name).toFile();
        HgTestUtils.hg(tempDir.toFile(), "clone", serverDir.getAbsolutePath(), dir.getAbsolutePath());
        Files.writeString(new File(dir, file).toPath(), name + "\n");
        HgTestUtils.hg(dir, "add", file);
        HgTestUtils.hg(dir, "commit", "-m", name, "-u", "dev");
        return dir;
    }

    private int changesets() throws Exception {
        return HgTestUtils.hg(serverDir, "log", "--template", "{rev}\\n").split("\n").length;
    }

    private String inProcessUrl() throws Exception {
        server = HgTestUtils.startServlet(new HgHttpWireServer(new HgRepository(serverDir)));
        return "http://127.0.0.1:" + HgTestUtils.port(server) + "/";
    }

    private static boolean isLink(File f) {
        return Files.isSymbolicLink(f.toPath());
    }

    // ---------------------------------------------------------------------------------------------- D

    /** The pushing client of {@link #killTheServerInTheMiddleOfAPush()}: its commit is on the server's way in when the kill comes. */
    private File pusher;

    /** Starts a server in its own JVM, lets a real {@code hg push} reach {@code pretxnchangegroup} and kills the JVM with SIGKILL there. */
    private void killTheServerInTheMiddleOfAPush() throws Exception {
        File marker = tempDir.resolve("marker").toFile();
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
                ServerKillHelperMain.class.getName(), serverDir.getAbsolutePath(), marker.getAbsolutePath());
        pb.redirectErrorStream(true);
        helper = pb.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(helper.getInputStream(), StandardCharsets.UTF_8));
        String portLine = reader.readLine();
        while (portLine != null && !portLine.startsWith("PORT ")) {
            portLine = reader.readLine();          // logging noise of the helper JVM comes first
        }
        assertTrue(portLine != null, "helper did not start");
        String url = "http://127.0.0.1:" + portLine.substring(5).trim() + "/";

        pusher = client("pusher", "p.txt");
        ProcessBuilder push = new ProcessBuilder("hg", "push", url);
        push.directory(pusher);
        push.redirectErrorStream(true);
        Process pushProcess = push.start();

        long deadline = System.currentTimeMillis() + 60_000;
        while (!marker.exists() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(marker.exists(), "the push never reached pretxnchangegroup");

        helper.destroyForcibly();                      // SIGKILL on Linux: no finally block, no lock release
        assertTrue(helper.waitFor(30, TimeUnit.SECONDS));
        pushProcess.destroyForcibly();
        pushProcess.waitFor(30, TimeUnit.SECONDS);

        // what the kill left behind: the journal of the open transaction (the hook ran inside it) and the locks of the dead owner
        File storeLock = new File(serverDir, ".hg/store/lock");
        assertTrue(new File(serverDir, ".hg/store/journal").exists(), "the journal of the interrupted transaction has to be on disk");
        assertTrue(isLink(storeLock), "the store lock of the dead server is expected to be left behind");
        String owner = Files.readSymbolicLink(storeLock.toPath()).toString();
        assertTrue(owner.startsWith(originalLockPrefix() + ":"), "lock owner not in the original's format: " + owner);
    }

    private void assertRolledBackAndPushable() throws Exception {
        File storeLock = new File(serverDir, ".hg/store/lock");
        File journal = new File(serverDir, ".hg/store/journal");
        String verifyAfter = HgTestUtils.hg(serverDir, "verify");
        assertTrue(verifyAfter.contains("checked 1 changesets"), "the interrupted push must be rolled back: " + verifyAfter);
        assertEquals(1, changesets());
        assertFalse(storeLock.exists() || isLink(storeLock), "store lock left behind after recover");
        assertFalse(journal.exists(), "journal left behind after recover");
        assertFalse(new File(serverDir, ".hg/store/journal.backupfiles").exists(), "journal.backupfiles left behind after recover");

        // and the push that was interrupted can be repeated
        Result again = run(pusher, "push", inProcessUrl());
        assertEquals(0, again.code, again.output);
        assertEquals(2, changesets());
        assertTrue(HgTestUtils.hg(serverDir, "verify").contains("checked 2 changesets"));
        assertFalse(isLink(storeLock) || storeLock.exists(), "store lock left behind after the repeated push");
        assertFalse(journal.exists(), "journal left behind after the repeated push");
    }

    @Test
    void theRealHgRecoverRollsBackAPushThatKilledHg4jInterrupted() throws Exception {
        killTheServerInTheMiddleOfAPush();

        // a push into the interrupted repository is refused until `hg recover` ran, as the original refuses it
        File probe = client("probe", "s.txt");
        Result refused = run(probe, "push", inProcessUrl());
        System.out.println("KILL-RESULT push before recover: exit " + refused.code + " :: " + refused.output.replace('\n', '|'));
        assertNotEquals(0, refused.code, "a push into an interrupted repository must not succeed");
        assertTrue(refused.output.contains("abandoned transaction found"), refused.output);
        assertTrue(new File(serverDir, ".hg/store/journal").exists(), "the refused push must leave the journal for recover");

        // the real hg breaks the dead owner's lock (its own prefix) and plays the journal back
        Result recover = run(serverDir, "--config", "ui.timeout=2", "recover");
        System.out.println("KILL-RESULT real hg recover: exit " + recover.code + " :: " + recover.output.replace('\n', '|'));
        assertEquals(0, recover.code, recover.output);
        assertTrue(recover.output.contains("rolling back interrupted transaction"), recover.output);
        assertFalse(recover.output.contains("couldn't read journal entry"), recover.output);
        assertRolledBackAndPushable();
    }

    @Test
    void hg4jRecoverRollsBackTheSameInterruptedPush() throws Exception {
        killTheServerInTheMiddleOfAPush();
        RecoverCommand.RecoverResult recovered = new RecoverCommand(new HgRepository(serverDir)).call();
        System.out.println("KILL-RESULT hg4j recover: interrupted=" + recovered.wasInterrupted() + " success=" + recovered.isSuccess());
        assertTrue(recovered.wasInterrupted(), "recover found no interrupted transaction");
        assertTrue(recovered.isSuccess());
        assertRolledBackAndPushable();
    }

    /** The other direction: a real {@code hg} killed inside its transaction, and hg4j's recover reads the journal the original wrote. */
    @Test
    void hg4jRecoverRollsBackATransactionThatARealHgLeftBehind() throws Exception {
        File work = tempDir.resolve("work").toFile();
        HgTestUtils.hg(tempDir.toFile(), "clone", serverDir.getAbsolutePath(), work.getAbsolutePath());
        Files.writeString(new File(work, "w.txt").toPath(), "w\n");
        HgTestUtils.hg(work, "add", "w.txt");
        // the hook runs in a child of hg: kill hg itself with SIGKILL while its transaction is open
        Result killed = run(work, "--config", "hooks.pretxncommit=kill -9 $PPID", "commit", "-m", "w", "-u", "dev");
        System.out.println("KILL-RESULT real hg killed in its commit: exit " + killed.code);
        assertNotEquals(0, killed.code);
        assertTrue(new File(work, ".hg/store/journal").exists(), "a real hg killed in the transaction leaves its journal");

        RecoverCommand.RecoverResult recovered = new RecoverCommand(new HgRepository(work)).call();
        System.out.println("KILL-RESULT hg4j recover of a real hg journal: interrupted=" + recovered.wasInterrupted() + " success=" + recovered.isSuccess());
        assertTrue(recovered.wasInterrupted());
        assertTrue(recovered.isSuccess());
        assertFalse(new File(work, ".hg/store/journal").exists());
        String verify = HgTestUtils.hg(work, "verify");
        assertTrue(verify.contains("checked 1 changesets"), "the interrupted commit must be rolled back: " + verify);
    }

    // ---------------------------------------------------------------------------------------------- E

    /** The lock owner string the original writes: {@code host/<pid namespace inode in hex>:pid} on Linux ({@code lock._getlockprefix}). */
    private static String originalLockPrefix() throws Exception {
        Process p = new ProcessBuilder("hostname").redirectErrorStream(true).start();
        String name = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        p.waitFor();
        String ns = "";
        Path nsLink = Path.of("/proc/self/ns/pid");
        if (Files.exists(nsLink)) {
            ns = "/" + Long.toHexString((Long) Files.getAttribute(nsLink, "unix:ino"));
        }
        return name + ns;
    }

    private static long deadPid() throws Exception {
        Process p = new ProcessBuilder("true").start();
        long pid = p.pid();
        p.waitFor();
        return pid;
    }

    @Test
    void aLockOfADeadOwnerOnAnotherHostIsWaitedForUntilUiTimeoutOnBothSides() throws Exception {
        Files.writeString(new File(serverDir, ".hg/hgrc").toPath(), "[web]\nallow_push = *\npush_ssl = false\n[ui]\ntimeout = 2\n");
        File storeLock = new File(serverDir, ".hg/store/lock");
        Files.createSymbolicLink(storeLock.toPath(), Path.of("otherhost.invalid:99999"));

        // the original: waits for ui.timeout, then aborts naming the owner
        long t0 = System.nanoTime();
        Result real = run(serverDir, "verify");
        long realMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("LOCK-RESULT real hg verify with a foreign lock: exit " + real.code + " after " + realMs + " ms :: " + real.output.replace('\n', '|'));
        assertNotEquals(0, real.code);
        assertTrue(real.output.contains("timed out waiting for lock"), real.output);

        // hg4j as the server: the push waits for ui.timeout, is refused, and nothing is written
        File pusher = client("pusher", "p.txt");
        String url = inProcessUrl();
        long t1 = System.nanoTime();
        Result push = run(pusher, "push", url);
        long pushMs = (System.nanoTime() - t1) / 1_000_000;
        System.out.println("LOCK-RESULT real hg push to hg4j with a foreign lock: exit " + push.code + " after " + pushMs + " ms :: " + push.output.replace('\n', '|'));
        assertNotEquals(0, push.code, push.output);
        assertTrue(pushMs >= 1500, "the push gave up after " + pushMs + " ms, before ui.timeout");
        assertTrue(isLink(storeLock), "a lock of another host must not be broken");
        assertEquals("otherhost.invalid:99999", Files.readSymbolicLink(storeLock.toPath()).toString());
        Files.delete(storeLock.toPath());
        assertEquals(1, changesets());
        assertTrue(HgTestUtils.hg(serverDir, "verify").contains("checked 1 changesets"));
    }

    /** The original breaks a lock of a dead pid on the same host, when the owner string has the form it writes itself. */
    @Test
    void theOriginalBreaksADeadSameHostLockInItsOwnFormat() throws Exception {
        Files.writeString(new File(serverDir, ".hg/hgrc").toPath(), "[ui]\ntimeout = 2\n");
        File storeLock = new File(serverDir, ".hg/store/lock");
        String owner = originalLockPrefix() + ":" + deadPid();
        Files.createSymbolicLink(storeLock.toPath(), Path.of(owner));
        Result real = run(serverDir, "verify");
        System.out.println("LOCK-RESULT real hg verify with a same-host dead owner in its own format (" + owner + "): exit " + real.code + " :: " + real.output.replace('\n', '|'));
        assertEquals(0, real.code, real.output);
        assertFalse(isLink(storeLock) || storeLock.exists(), "the original left the dead lock behind");
    }

    /**
     * hg4j writes the owner prefix of the original ({@code host/<pid namespace>:pid}), so a real {@code hg} breaks the dead lock of an
     * hg4j process (see the kill scenario above), and hg4j breaks both that form and the plain {@code host:pid} that it wrote before.
     */
    @Test
    void hg4jBreaksDeadSameHostLocksInBothFormatsAndWritesTheOriginalsFormat() throws Exception {
        Files.writeString(new File(serverDir, ".hg/hgrc").toPath(), "[web]\nallow_push = *\npush_ssl = false\n[ui]\ntimeout = 2\n");
        File storeLock = new File(serverDir, ".hg/store/lock");
        File first = client("first", "f.txt");
        String url = inProcessUrl();

        // (1) a dead owner in the original's format
        Files.createSymbolicLink(storeLock.toPath(), Path.of(originalLockPrefix() + ":" + deadPid()));
        Result push1 = run(first, "push", url);
        System.out.println("LOCK-RESULT hg4j server, dead owner in the original's format: exit " + push1.code + " :: " + push1.output.replace('\n', '|'));
        assertEquals(0, push1.code, push1.output);
        assertFalse(isLink(storeLock) || storeLock.exists(), "store lock left behind");

        // (2) a dead owner in the plain host:pid form that older hg4j versions wrote (the clone comes first: it takes the lock itself)
        File second = client("second", "s.txt");
        String host = originalLockPrefix().split("/")[0];
        Files.createSymbolicLink(storeLock.toPath(), Path.of(host + ":" + deadPid()));
        Result push2 = run(second, "push", url);
        System.out.println("LOCK-RESULT hg4j server, dead owner in the older hg4j format: exit " + push2.code + " :: " + push2.output.replace('\n', '|'));
        assertEquals(0, push2.code, push2.output);
        assertEquals(3, changesets());
        assertTrue(HgTestUtils.hg(serverDir, "verify").contains("checked 3 changesets"));
    }
}
