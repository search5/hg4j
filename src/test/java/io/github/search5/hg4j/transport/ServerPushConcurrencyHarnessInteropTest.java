package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.lib.HgRepository;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1 of {@code llm-wiki/decisions/server-push-concurrency-roadmap.md}: a regression harness for the server side of concurrent
 * pushes, with real {@code hg} processes against an embedded {@link HgHttpWireServer}. It pins the behaviour of the current lock
 * scope, so that any later change of that scope can be judged against the same scenarios. After every scenario the repository has to
 * pass the real {@code hg verify}, and no lock file or journal may be left behind.
 *
 * <ul>
 * <li>A: several real {@code hg push} to different new branches at once: all succeed, the union of the changesets is exact.</li>
 * <li>B: several real {@code hg push} to the same branch from the same base: exactly one wins, the others are refused as a lost race.</li>
 * <li>C: pushes to different branches while real {@code hg pull} processes read: every pull passes {@code hg verify} and sees only
 * heads that were pushed (never a half-applied push).</li>
 * </ul>
 */
@Tag("interop")
class ServerPushConcurrencyHarnessInteropTest {

    private static final int PUSHERS = 6;
    private static final int PULLERS = 4;

    @TempDir
    Path tempDir;

    private File serverDir;
    private Server server;
    private String baseNode;

    @BeforeEach
    void startServer() throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "real hg required");
        serverDir = tempDir.resolve("server").toFile();
        HgRepository repo = Hg.init().setDirectory(serverDir).call();
        Files.writeString(new File(serverDir, "base.txt").toPath(), "base\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("base").setAuthor("dev").call();
        // a server repository is filled by pushes, so its changesets are public (the first push to a publishing server turns a draft
        // base public, which a client that saw it as draft then refuses with "is public expected draft")
        HgTestUtils.hg(serverDir, "phase", "--public", "-r", "0");
        Files.writeString(new File(serverDir, ".hg/hgrc").toPath(), "[web]\nallow_push = *\npush_ssl = false\n");
        HgRepository served = new HgRepository(serverDir);
        server = HgTestUtils.startServlet(new HgHttpWireServer(served));
        baseNode = HgTestUtils.hg(serverDir, "log", "-r", "0", "--template", "{node}").trim();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            HgTestUtils.stop(server);
        }
    }

    private String url() {
        return "http://127.0.0.1:" + HgTestUtils.port(server) + "/";
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
        List<String> cmd = new ArrayList<>(List.of("hg", "--config", "format.usezstd=false", "--config", "format.revlog-compression=zlib"));
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

    private static boolean readsAsALostRace(String output) {
        String o = output.toLowerCase();
        return o.contains("changed while pushing") || o.contains("try again") || o.contains("push failed")
                || o.contains("push creates new remote head");
    }

    /** A clone of the server with one new commit on {@code branch} ({@code null} keeps the default branch); returns its directory. */
    private File client(int i, String branch) throws Exception {
        File dir = tempDir.resolve("client" + i).toFile();
        HgTestUtils.hg(tempDir.toFile(), "clone", serverDir.getAbsolutePath(), dir.getAbsolutePath());
        if (branch != null) {
            HgTestUtils.hg(dir, "branch", branch);
        }
        Files.writeString(new File(dir, "c" + i + ".txt").toPath(), "client " + i + "\n");
        HgTestUtils.hg(dir, "add", "c" + i + ".txt");
        HgTestUtils.hg(dir, "commit", "-m", "client " + i, "-u", "dev");
        return dir;
    }

    private static String tip(File dir) throws Exception {
        return HgTestUtils.hg(dir, "log", "-r", ".", "--template", "{node}").trim();
    }

    /** Starts all tasks together and waits for them; the results are in task order. */
    private static <T> List<T> together(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return task.call();
                }));
            }
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(180, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** The server repository has to be consistent and idle: real {@code hg verify}, no lock files, no journal. */
    private void assertServerConsistentAndIdle() throws Exception {
        String verify = HgTestUtils.hg(serverDir, "verify");
        assertTrue(verify.contains("checked "), verify);
        assertFalse(new File(serverDir, ".hg/wlock").exists() || isLink(new File(serverDir, ".hg/wlock")), "wlock left behind");
        assertFalse(new File(serverDir, ".hg/store/lock").exists() || isLink(new File(serverDir, ".hg/store/lock")), "store lock left behind");
        File[] journals = new File(serverDir, ".hg").listFiles((d, n) -> n.startsWith("journal") || n.startsWith("undo.partial"));
        assertEquals(0, journals == null ? 0 : journals.length, "journal left behind: " + Arrays.toString(journals));
        assertFalse(new File(serverDir, ".hg/store/journal").exists(), "store journal left behind");
    }

    private static boolean isLink(File f) {
        return Files.isSymbolicLink(f.toPath());
    }

    @Test
    void pushesToDifferentNewBranchesAllSucceed() throws Exception {
        List<File> clients = new ArrayList<>();
        for (int i = 0; i < PUSHERS; i++) {
            clients.add(client(i, "branch" + i));
        }
        List<Callable<Result>> pushes = new ArrayList<>();
        for (File c : clients) {
            pushes.add(() -> run(c, "push", "--new-branch", url()));
        }
        List<Result> results = together(pushes);
        for (int i = 0; i < results.size(); i++) {
            assertEquals(0, results.get(i).code, "push " + i + " failed: " + results.get(i).output);
        }
        assertServerConsistentAndIdle();
        // base + one changeset per client, and every client's changeset is on the server
        assertEquals(PUSHERS + 1, HgTestUtils.hg(serverDir, "log", "--template", "{rev}\\n").split("\n").length);
        for (File c : clients) {
            HgTestUtils.hg(serverDir, "log", "-r", tip(c), "--template", "{node}");
        }
    }

    @Test
    void pushesToTheSameBranchFromTheSameBaseLetExactlyOneWin() throws Exception {
        List<File> clients = new ArrayList<>();
        for (int i = 0; i < PUSHERS; i++) {
            clients.add(client(i, null));
        }
        List<Callable<Result>> pushes = new ArrayList<>();
        for (File c : clients) {
            pushes.add(() -> run(c, "push", url()));
        }
        List<Result> results = together(pushes);
        int wins = 0;
        for (int i = 0; i < results.size(); i++) {
            Result r = results.get(i);
            if (r.code == 0) {
                wins++;
            } else {
                assertTrue(readsAsALostRace(r.output), "push " + i + " failed for another reason: " + r.output);
            }
        }
        assertEquals(1, wins, "exactly one push to the same branch from the same base may win");
        assertServerConsistentAndIdle();
        assertEquals(2, HgTestUtils.hg(serverDir, "log", "--template", "{rev}\\n").split("\n").length, "base plus the winner only");
        assertEquals(1, HgTestUtils.hg(serverDir, "heads", "--template", "{node}\\n").split("\n").length, "one head");
    }

    @Test
    void pullsDuringPushesNeverSeeAHalfAppliedPush() throws Exception {
        List<File> clients = new ArrayList<>();
        Set<String> pushedNodes = new HashSet<>();
        for (int i = 0; i < PUSHERS; i++) {
            File c = client(i, "branch" + i);
            clients.add(c);
            pushedNodes.add(tip(c));
        }
        List<File> pullDirs = new ArrayList<>();
        for (int i = 0; i < PULLERS; i++) {
            File dir = tempDir.resolve("puller" + i).toFile();
            HgTestUtils.hg(tempDir.toFile(), "init", dir.getAbsolutePath());
            pullDirs.add(dir);
        }
        List<Callable<Result>> tasks = new ArrayList<>();
        for (File c : clients) {
            tasks.add(() -> run(c, "push", "--new-branch", url()));
        }
        for (File d : pullDirs) {
            tasks.add(() -> run(d, "pull", url()));
        }
        List<Result> results = together(tasks);
        for (int i = 0; i < PUSHERS; i++) {
            assertEquals(0, results.get(i).code, "push " + i + " failed: " + results.get(i).output);
        }
        for (int i = 0; i < PULLERS; i++) {
            Result pull = results.get(PUSHERS + i);
            assertEquals(0, pull.code, "pull " + i + " failed: " + pull.output);
            File d = pullDirs.get(i);
            String verify = HgTestUtils.hg(d, "verify");
            assertTrue(verify.contains("checked "), verify);
            // every head of the pulled repository is the base or a node that was pushed: no half-applied push
            String heads = HgTestUtils.hg(d, "heads", "--template", "{node}\\n");
            for (String head : heads.split("\n")) {
                assertTrue(head.equals(baseNode) || pushedNodes.contains(head), "pull " + i + " saw an unknown head " + head);
            }
        }
        assertServerConsistentAndIdle();
        assertEquals(PUSHERS + 1, HgTestUtils.hg(serverDir, "log", "--template", "{rev}\\n").split("\n").length);
    }
}
