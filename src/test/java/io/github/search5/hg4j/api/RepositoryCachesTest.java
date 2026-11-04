package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The caches under {@code .hg/cache} (branch2-served, tags2-visible, hgtagsfnodes1, rbc-names-v2, rbc-revs-v2): hg4j writes the very
 * bytes Mercurial 7.2 wrote for the same repository, and honours hg's lock rules ({@code lock(wait=False)} for hgtagsfnodes1,
 * {@code wlock(wait=False)} for the rbc files, nothing for branch2 and tags2).
 */
class RepositoryCachesTest {

    private static final List<String> FILES = List.of("branch2-served", "tags2-visible", "hgtagsfnodes1", "rbc-names-v2", "rbc-revs-v2");
    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/caches");

    @TempDir
    Path tempDir;

    File repoDir;

    @BeforeEach
    void copyRepository() throws IOException {
        repoDir = tempDir.resolve("repo").toFile();
        copyTree(FIXTURE.resolve("work/hg-dir"), repoDir.toPath().resolve(".hg"));
        for (String name : List.of("f1", "f2", "fy", ".hgtags")) {
            Files.copy(FIXTURE.resolve("work/" + name), repoDir.toPath().resolve(name));
        }
        Files.createDirectories(repoDir.toPath().resolve(".hg/cache"));
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (var walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target);
                }
            }
        }
    }

    private byte[] cache(String name) throws IOException {
        return Files.readAllBytes(repoDir.toPath().resolve(".hg/cache/" + name));
    }

    private static byte[] expected(String name) throws IOException {
        return Files.readAllBytes(FIXTURE.resolve("expected/" + name));
    }

    @Test
    void everyCacheIsByteIdenticalToTheOneMercurialWrote() throws Exception {
        try (HgRepository repo = new HgRepository(repoDir)) {
            RepositoryCaches.Outcome outcome = RepositoryCaches.update(repo);
            assertEquals(List.of(), outcome.skipped());
            assertEquals(5, outcome.written().size(), outcome.written().toString());
        }
        for (String name : FILES) {
            assertArrayEquals(expected(name), cache(name), name);
        }
    }

    @Test
    void updatingAgainChangesNothing() throws Exception {
        try (HgRepository repo = new HgRepository(repoDir)) {
            RepositoryCaches.update(repo);
            RepositoryCaches.update(repo);
        }
        for (String name : FILES) {
            assertArrayEquals(expected(name), cache(name), name);
        }
    }

    @Test
    void aTruncatedOrDamagedFnodesCacheIsRepaired() throws Exception {
        try (HgRepository repo = new HgRepository(repoDir)) {
            RepositoryCaches.update(repo);
            Path fnodes = repoDir.toPath().resolve(".hg/cache/hgtagsfnodes1");
            Files.write(fnodes, java.util.Arrays.copyOf(expected("hgtagsfnodes1"), 50)); // cut in the middle of a record
            RepositoryCaches.update(repo);
        }
        assertArrayEquals(expected("hgtagsfnodes1"), cache("hgtagsfnodes1"));
    }

    private static Thread holding(File dir, boolean store, CountDownLatch held, CountDownLatch release) {
        Thread holder = new Thread(() -> {
            try (HgRepository other = new HgRepository(dir); HgLock l = store ? other.lockStore() : other.lockWorkingCopy()) {
                held.countDown();
                release.await();
            } catch (Exception ignored) {
                held.countDown();
            }
        });
        holder.start();
        return holder;
    }

    @Test
    void aBusyStoreLockSkipsOnlyTheFnodesCacheWithoutWaiting() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = holding(repoDir, true, held, release);
        assertTrue(held.await(10, TimeUnit.SECONDS));
        long begin = System.currentTimeMillis();
        RepositoryCaches.Outcome outcome;
        try (HgRepository repo = new HgRepository(repoDir)) {
            outcome = RepositoryCaches.update(repo);
        } finally {
            release.countDown();
            holder.join();
        }
        assertTrue(System.currentTimeMillis() - begin < 20_000, "lock(wait=False) does not wait");
        assertFalse(Files.exists(repoDir.toPath().resolve(".hg/cache/hgtagsfnodes1")), "not written without the lock");
        assertTrue(outcome.skipped().stream().anyMatch(s -> s.startsWith("hgtagsfnodes1")), outcome.skipped().toString());
        for (String name : List.of("branch2-served", "tags2-visible", "rbc-names-v2", "rbc-revs-v2")) {
            assertArrayEquals(expected(name), cache(name), name + " needs no store lock");
        }
    }

    @Test
    void aBusyWorkingCopyLockSkipsOnlyTheRevisionBranchCache() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = holding(repoDir, false, held, release);
        assertTrue(held.await(10, TimeUnit.SECONDS));
        try (HgRepository repo = new HgRepository(repoDir)) {
            RepositoryCaches.update(repo);
        } finally {
            release.countDown();
            holder.join();
        }
        assertFalse(Files.exists(repoDir.toPath().resolve(".hg/cache/rbc-names-v2")));
        assertFalse(Files.exists(repoDir.toPath().resolve(".hg/cache/rbc-revs-v2")));
        for (String name : List.of("branch2-served", "tags2-visible", "hgtagsfnodes1")) {
            assertArrayEquals(expected(name), cache(name), name);
        }
    }

    @Test
    void manyUpdatesAtTheSameMomentLeaveEveryFileComplete() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            done.add(pool.submit(() -> {
                try (HgRepository repo = new HgRepository(repoDir)) {
                    start.await();
                    for (int i = 0; i < 20; i++) {
                        RepositoryCaches.update(repo);
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : done) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        // a final update from a quiet state settles on exactly Mercurial's bytes: none of the concurrent writers left a torn file
        try (HgRepository repo = new HgRepository(repoDir)) {
            RepositoryCaches.update(repo);
        }
        for (String name : FILES) {
            assertArrayEquals(expected(name), cache(name), name);
        }
        assertFalse(new File(repoDir, ".hg/store/lock").exists());
        assertFalse(new File(repoDir, ".hg/wlock").exists());
    }
}
