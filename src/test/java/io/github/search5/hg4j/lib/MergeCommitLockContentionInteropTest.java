package io.github.search5.hg4j.lib;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.MergeCommitCommand;
import io.github.search5.hg4j.api.MergeCommitResult;
import io.github.search5.hg4j.testsupport.LocalHg;
import io.github.search5.hg4j.util.NodeIdUtil;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store lock of {@link MergeCommitCommand} against the real {@code hg}, on a server repository that has no working copy: a real
 * {@code hg push} holds the store lock and has its transaction open (a hook sleeps in it) while hg4j merges two other heads in the
 * same repository. With the locks the merge waits and nothing is damaged; with hg4j's locks switched off
 * ({@link HgLock#disabledForTests}) the two writers overwrite each other's appends and the repository is damaged.
 */
@Tag("interop")
class MergeCommitLockContentionInteropTest {

    private static final String DATE = "1700000000 0";

    @TempDir
    Path tempDir;

    @BeforeEach
    void requireHg() {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "real hg required");
        HgLock.disabledForTests = false;
    }

    @AfterEach
    void locksBackOn() {
        HgLock.disabledForTests = false;
    }

    private static void commitFile(File dir, String name, String content, String message) throws Exception {
        Files.writeString(new File(dir, name).toPath(), content);
        HgTestUtils.hg(dir, "commit", "-A", "-m", message, "-u", "t", "-d", DATE);
    }

    private static String node(File dir, String rev) throws Exception {
        return HgTestUtils.hg(dir, "log", "-r", rev, "--template", "{node}").trim();
    }

    /** A server with the heads A and B on top of a base, and a clone with a third head C that is not in the server yet. */
    private File[] serverAndSource() throws Exception {
        File server = tempDir.resolve("server").toFile();
        server.mkdirs();
        HgTestUtils.hg(server, "init");
        commitFile(server, "base.txt", "base\n", "base");
        commitFile(server, "a.txt", "a\n", "A");
        HgTestUtils.hg(server, "update", "-r", "0");
        commitFile(server, "b.txt", "b\n", "B");
        Files.writeString(new File(server, ".hg/hgrc").toPath(), "[ui]\ntimeout = 60\nusername = t\n");
        File source = tempDir.resolve("source").toFile();
        HgTestUtils.hg(tempDir.toFile(), "clone", "-r", "0", server.getAbsolutePath(), source.getAbsolutePath());
        commitFile(source, "c.txt", "c\n", "C");
        return new File[] {server, source};
    }

    /** What is wrong with the server after both writers are done; null when nothing is. */
    private String merge(File server, File source) throws Exception {
        String a = node(server, "1");
        String b = node(server, "2");
        // the real push takes the store lock and keeps its transaction open for three seconds
        ProcessBuilder pb = LocalHg.processBuilder(source.toPath(), "push", "-f", server.getAbsolutePath(),
                "--config", "hooks.pretxnchangegroup.slow=sleep 3");
        pb.redirectErrorStream(true);
        Process push = pb.start();
        try {
            File lock = new File(server, ".hg/store/lock");
            long deadline = System.currentTimeMillis() + 30_000;
            while (!Files.isSymbolicLink(lock.toPath()) && !lock.exists() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            Thread.sleep(700);
            try (HgRepository repo = new HgRepository(server)) {
                MergeCommitResult result = new MergeCommitCommand(repo).setP1(NodeIdUtil.fromHex(a)).setP2(NodeIdUtil.fromHex(b))
                        .setUser("t").setDate(DATE).setMessage("merge").call();
                if (result.isConflicted()) {
                    return "conflicted: " + result.getConflicts();
                }
            }
            String out = new String(push.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!push.waitFor(60, TimeUnit.SECONDS) || push.exitValue() != 0) {
                return "the real push failed: " + out;
            }
        } catch (Exception e) {
            return "merge failed: " + e;
        } finally {
            push.destroyForcibly();
        }
        try {
            HgTestUtils.hg(server, "verify");
        } catch (AssertionError | Exception e) {
            return "hg verify failed: " + e.getMessage();
        }
        String count = HgTestUtils.hg(server, "log", "--template", "{rev} ").trim();
        if (count.split("\\s+").length != 5) {
            return "expected 5 changesets (base, A, B, C, merge) but the log shows: " + count;
        }
        String heads = HgTestUtils.hg(server, "heads", "--template", "{desc}\n").trim();
        if (!heads.contains("merge") || !heads.contains("C") || heads.split("\n").length != 2) {
            return "unexpected heads: " + heads;
        }
        return null;
    }

    @Test
    void withTheLocksAMergeWaitsForARealPushInProgressAndBothSurvive() throws Exception {
        File[] repos = serverAndSource();
        assertNull(merge(repos[0], repos[1]));
    }

    @Test
    void withoutTheLocksAMergeDuringARealPushDamagesTheRepository() throws Exception {
        File[] repos = serverAndSource();
        HgLock.disabledForTests = true;
        String damage = merge(repos[0], repos[1]);
        System.out.println("DAMAGE: " + damage);
        assertNotNull(damage, "the scenario passed without the locks, so it does not need them");
    }

    @Test
    void twoMergesAtTheSameTimeBothLandAndTheRepositoryVerifies() throws Exception {
        File server = tempDir.resolve("server2").toFile();
        server.mkdirs();
        HgTestUtils.hg(server, "init");
        commitFile(server, "base.txt", "base\n", "base");
        List<String> heads = new ArrayList<>();
        for (String name : List.of("a", "b", "c", "d")) {
            HgTestUtils.hg(server, "update", "-r", "0");
            commitFile(server, name + ".txt", name + "\n".repeat(2000), name);
            heads.add(node(server, "."));
        }
        Files.writeString(new File(server, ".hg/hgrc").toPath(), "[ui]\ntimeout = 60\nusername = t\n");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<MergeCommitResult>> results = new ArrayList<>();
            for (int i = 0; i < 4; i += 2) {
                final String left = heads.get(i);
                final String right = heads.get(i + 1);
                results.add(pool.submit(() -> {
                    try (HgRepository repo = new HgRepository(server)) {
                        return new MergeCommitCommand(repo).setP1(NodeIdUtil.fromHex(left)).setP2(NodeIdUtil.fromHex(right))
                                .setUser("t").setDate(DATE).setMessage("merge " + left.substring(0, 6)).call();
                    }
                }));
            }
            for (Future<MergeCommitResult> r : results) {
                assertTrue(!r.get(60, TimeUnit.SECONDS).isConflicted());
            }
        } finally {
            pool.shutdownNow();
        }
        HgTestUtils.hg(server, "verify");
        assertEquals(7, HgTestUtils.hg(server, "log", "--template", "{rev} ").trim().split("\\s+").length);
        assertEquals(2, HgTestUtils.hg(server, "heads", "--template", "{node}\n").trim().split("\n").length);
    }
}
