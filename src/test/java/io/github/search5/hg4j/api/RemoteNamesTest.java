package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
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
 * {@code experimental.remotenames} (logexchange.py): the file contents below are the format real hg 7.2 writes
 * ({@code "0\n\n"}, then {@code node NUL remote NUL name} lines).
 */
class RemoteNamesTest {

    @TempDir
    Path tempDir;

    private File source(String name, String bookmark, String branch) throws Exception {
        File dir = tempDir.resolve(name).toFile();
        HgRepository repo = Hg.init().setDirectory(dir).call();
        Files.writeString(new File(dir, "a").toPath(), name + "\n");
        new AddCommand(repo).call();
        byte[] r0 = new CommitCommand(repo).setMessage("r0 " + name).setAuthor("t").call();
        new BookmarkCommand(repo).setBookmarkName(bookmark).setRevision(NodeIdUtil.toHex(r0)).call();
        if (branch != null) {
            new BranchCommand(repo).setBranchName(branch).call();
            Files.writeString(new File(dir, "b").toPath(), "b\n");
            new AddCommand(repo).call();
            new CommitCommand(repo).setMessage("r1 " + name).setAuthor("t").call();
        }
        repo.close();
        return dir;
    }

    private File destination(boolean enabled, String... aliasesAndPaths) throws Exception {
        File dir = tempDir.resolve("dest" + System.nanoTime()).toFile();
        Hg.init().setDirectory(dir).call().close();
        StringBuilder rc = new StringBuilder();
        if (enabled) {
            rc.append("[experimental]\nremotenames = true\n");
        }
        rc.append("[paths]\n");
        for (int i = 0; i < aliasesAndPaths.length; i += 2) {
            rc.append(aliasesAndPaths[i]).append(" = ").append(aliasesAndPaths[i + 1]).append('\n');
        }
        Files.writeString(new File(dir, ".hg/hgrc").toPath(), rc.toString());
        return dir;
    }

    private void pull(File dest, String from) throws Exception {
        try (HgRepository repo = new HgRepository(dest)) {
            new PullCommand(repo).setSource(from).call();
        }
    }

    private static String nodeOf(File repoDir, String message) throws Exception {
        try (HgRepository repo = new HgRepository(repoDir)) {
            var cl = repo.getRevlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"));
            for (int r = 0; r < cl.getRevisionCount(); r++) {
                if (new String(cl.getRevisionContent(r)).endsWith("\n" + message)) {
                    return NodeIdUtil.toHex(cl.getIndexRecord(r).getNodeId());
                }
            }
        }
        return null;
    }

    private static String file(File dest, String name) throws Exception {
        return Files.readString(new File(dest, ".hg/logexchange/" + name).toPath());
    }

    @Test
    void aPullKeepsTheRemoteBookmarksAndBranchHeads() throws Exception {
        File src = source("src", "mark1", "feat");
        File dest = destination(true, "default", src.getAbsolutePath());
        pull(dest, "default");
        String r0 = nodeOf(src, "r0 src");
        String r1 = nodeOf(src, "r1 src");
        assertEquals("0\n\n" + r0 + "\0default\0mark1\n", file(dest, "bookmarks"));
        assertEquals("0\n\n" + r0 + "\0default\0default\n" + r1 + "\0default\0feat\n", file(dest, "branches"));
        assertEquals(List.of(new RemoteNames.Entry(r0, "default", "mark1"), new RemoteNames.Entry(r0, "default", "default"),
                new RemoteNames.Entry(r1, "default", "feat")), RemoteNames.read(new HgRepository(dest)));
    }

    @Test
    void nothingIsKeptUnlessEnabled() throws Exception {
        File src = source("src", "mark1", null);
        File dest = destination(false, "default", src.getAbsolutePath());
        pull(dest, "default");
        assertFalse(new File(dest, ".hg/logexchange").exists());
    }

    @Test
    void theEntriesOfAnotherRemoteStayAndThoseOfThisOneAreReplaced() throws Exception {
        File a = source("a", "ma", null);
        File b = source("b", "mb", null);
        File dest = destination(true, "one", a.getAbsolutePath(), "two", b.getAbsolutePath());
        pull(dest, "one");
        pull(dest, "two");
        String na = nodeOf(a, "r0 a");
        String nb = nodeOf(b, "r0 b");
        // sorted by node first, as real hg writes them for the entries of other remotes
        String text = file(dest, "bookmarks");
        assertTrue(text.startsWith("0\n\n"));
        assertTrue(text.contains(na + "\0one\0ma\n"), text);
        assertTrue(text.contains(nb + "\0two\0mb\n"), text);
        // pulling "one" again rewrites its lines only
        pull(dest, "one");
        assertEquals(text.length(), file(dest, "bookmarks").length());
    }

    @Test
    void severalPullsAtTheSameMomentLoseNoRemote() throws Exception {
        int remotes = 4;
        List<String> pairs = new ArrayList<>();
        List<File> sources = new ArrayList<>();
        for (int i = 0; i < remotes; i++) {
            File s = source("s" + i, "m" + i, null);
            sources.add(s);
            pairs.add("remote" + i);
            pairs.add(s.getAbsolutePath());
        }
        File dest = destination(true, pairs.toArray(String[]::new));
        Files.writeString(new File(dest, ".hg/hgrc").toPath(), Files.readString(new File(dest, ".hg/hgrc").toPath()) + "[ui]\ntimeout = 120\n");
        ExecutorService pool = Executors.newFixedThreadPool(remotes);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        for (int i = 0; i < remotes; i++) {
            String alias = "remote" + i;
            done.add(pool.submit(() -> {
                try (HgRepository own = new HgRepository(dest)) {
                    start.await();
                    new PullCommand(own).setSource(alias).call();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : done) {
            f.get(180, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        String text = file(dest, "bookmarks");
        for (int i = 0; i < remotes; i++) {
            assertTrue(text.contains("\0remote" + i + "\0m" + i + "\n"), "remote" + i + " lost from:\n" + text);
        }
        try (HgRepository check = new HgRepository(dest)) {
            assertEquals(List.of(), new VerifyCommand(check).call());
        }
        assertFalse(new File(dest, ".hg/wlock").exists());
    }

    @Test
    void savingWaitsForTheWorkingCopyLock() throws Exception {
        File dest = destination(true);
        Files.writeString(new File(dest, ".hg/hgrc").toPath(), Files.readString(new File(dest, ".hg/hgrc").toPath()) + "[ui]\ntimeout = 1\n");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (HgRepository other = new HgRepository(dest); HgLock l = other.lockWorkingCopy()) {
                held.countDown();
                release.await();
            } catch (Exception ignored) {
                held.countDown();
            }
        });
        holder.start();
        assertTrue(held.await(10, TimeUnit.SECONDS));
        try (HgRepository repo = new HgRepository(dest)) {
            assertThrows(HgLockException.class, () -> RemoteNames.save(repo, "x", Map.of(), Map.of("m", "a".repeat(40))));
        } finally {
            release.countDown();
            holder.join();
        }
    }

    @Test
    void savesAtTheSameMomentLoseNoRemote() throws Exception {
        // read the file, replace one remote's lines, write it back: without the working copy lock two of these lose each other's lines
        File dest = destination(true);
        Files.writeString(new File(dest, ".hg/hgrc").toPath(), Files.readString(new File(dest, ".hg/hgrc").toPath()) + "[ui]\ntimeout = 120\n");
        int threads = 8;
        int rounds = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int n = t;
            done.add(pool.submit(() -> {
                try (HgRepository own = new HgRepository(dest)) {
                    start.await();
                    for (int round = 0; round < rounds; round++) {
                        RemoteNames.save(own, "remote" + n, Map.of(), Map.of("mark", String.format("%040d", n * 1000 + round)));
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : done) {
            f.get(180, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        String text = file(dest, "bookmarks");
        for (int t = 0; t < threads; t++) {
            assertTrue(text.contains("\0remote" + t + "\0mark\n"), "remote" + t + " lost from:\n" + text);
        }
        assertEquals(threads + 2, text.split("\n", -1).length - 1, "the header and one line per remote, nothing twice");
    }
}
