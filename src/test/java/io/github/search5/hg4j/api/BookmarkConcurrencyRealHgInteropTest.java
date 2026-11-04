package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.transport.HgLocalClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real hg processes and hg4j threads change the bookmarks of one repository at the same moment. Both use the same lock files
 * ({@code .hg/wlock}, {@code .hg/store/lock}), so every change must survive and no lock may stay behind.
 */
@Tag("interop")
class BookmarkConcurrencyRealHgInteropTest {

    @TempDir
    Path tempDir;

    private static String hg(File dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("hg", "--config", "format.usezstd=false", "--config", "ui.username=t"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), String.join(" ", args) + ":\n" + out);
        return out;
    }

    private File repositoryWithOneCommit() throws Exception {
        File dir = tempDir.resolve("repo").toFile();
        assertTrue(dir.mkdirs());
        hg(dir, "init");
        Files.writeString(new File(dir, "a.txt").toPath(), "a\n");
        hg(dir, "add", "a.txt");
        hg(dir, "commit", "-m", "one");
        return dir;
    }

    private static void assertNoLockLeft(File dir) {
        assertFalse(Files.exists(new File(dir, ".hg/wlock").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS), "wlock left behind");
        assertFalse(Files.exists(new File(dir, ".hg/store/lock").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS), "store lock left behind");
    }

    @Test
    void realHgAndHg4jWritingTogetherLoseNothing() throws Exception {
        File dir = repositoryWithOneCommit();
        String node = hg(dir, "log", "-r", "0", "-T", "{node}").trim();
        int each = 6;
        ExecutorService pool = Executors.newFixedThreadPool(each * 2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        for (int i = 0; i < each; i++) {
            String real = "real" + i;
            String own = "own" + i;
            done.add(pool.submit(() -> {
                start.await();
                return hg(dir, "bookmark", "-r", "0", real);
            }));
            done.add(pool.submit(() -> {
                try (HgRepository repo = new HgRepository(dir)) {
                    start.await();
                    new BookmarkCommand(repo).setBookmarkName(own).setRevision(node).call();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : done) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        String listed = hg(dir, "bookmarks", "-T", "{bookmark}\n");
        for (int i = 0; i < each; i++) {
            assertTrue(listed.contains("real" + i + "\n"), "real hg bookmark lost:\n" + listed);
            assertTrue(listed.contains("own" + i + "\n"), "hg4j bookmark lost:\n" + listed);
        }
        assertNoLockLeft(dir);
        hg(dir, "verify");
    }

    @Test
    void pushkeysToOneLocalRepositoryLoseNothing() throws Exception {
        File dir = repositoryWithOneCommit();
        String node = hg(dir, "log", "-r", "0", "-T", "{node}").trim();
        int writers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> done = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            String key = "key" + i;
            done.add(pool.submit(() -> {
                try (HgRepository repo = new HgRepository(dir)) {
                    HgLocalClient client = new HgLocalClient(repo);
                    start.await();
                    return client.pushkey("bookmarks", key, "", node);
                }
            }));
        }
        start.countDown();
        for (Future<Boolean> f : done) {
            assertTrue(f.get(120, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        String listed = hg(dir, "bookmarks", "-T", "{bookmark}\n");
        for (int i = 0; i < writers; i++) {
            assertTrue(listed.contains("key" + i + "\n"), "pushed bookmark lost:\n" + listed);
        }
        assertNoLockLeft(dir);
    }
}
