package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.transport.HgLocalClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
 * The {@code bookmarksinstore} requirement against real hg 7.2: a repository made with {@code format.bookmarks-in-store=true} keeps its
 * bookmarks in {@code .hg/store/bookmarks} (the requirement is listed in {@code .hg/store/requires}), while the active bookmark
 * {@code bookmarks.current} stays in {@code .hg}. hg4j has to read and write the store file, never create {@code .hg/bookmarks}, and
 * follow {@code bookmarks.pushbookmark}'s locking: the working copy lock is taken only when the bookmarks file is not in the store.
 */
@Tag("interop")
class BookmarksInStoreRealHgInteropTest {

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

    /** A repository with one commit; {@code inStore} makes real hg write the requirement. */
    private File repositoryWithOneCommit(String name, boolean inStore) throws Exception {
        File dir = tempDir.resolve(name).toFile();
        assertTrue(dir.mkdirs());
        if (inStore) {
            hg(dir, "--config", "format.bookmarks-in-store=true", "init");
        } else {
            hg(dir, "init");
        }
        Files.writeString(new File(dir, "a.txt").toPath(), "a\n");
        hg(dir, "add", "a.txt");
        hg(dir, "commit", "-m", "one");
        return dir;
    }

    private static boolean exists(File f) {
        return Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    private static void assertNoLockLeft(File dir) {
        assertFalse(exists(new File(dir, ".hg/wlock")), "wlock left behind");
        assertFalse(exists(new File(dir, ".hg/store/lock")), "store lock left behind");
    }

    @Test
    void realHgPutsTheRequirementAndTheBookmarksInTheStore() throws Exception {
        File dir = repositoryWithOneCommit("repo", true);
        hg(dir, "bookmark", "-r", "0", "bm1");

        // what real hg did: this is the fact the rest of the class relies on
        assertTrue(Files.readString(new File(dir, ".hg/store/requires").toPath()).contains("bookmarksinstore"));
        assertTrue(exists(new File(dir, ".hg/store/bookmarks")));
        assertFalse(exists(new File(dir, ".hg/bookmarks")));

        HgRepository repo = new HgRepository(dir);
        assertTrue(repo.isBookmarksInStore());
        assertEquals(new File(dir, ".hg/store/bookmarks").getCanonicalFile(), repo.getBookmarksFile().getCanonicalFile());
    }

    @Test
    void aRepositoryWithoutTheRequirementStillUsesTheHgDirectory() throws Exception {
        File dir = repositoryWithOneCommit("plain", false);
        hg(dir, "bookmark", "-r", "0", "bm1");

        HgRepository repo = new HgRepository(dir);
        assertFalse(repo.isBookmarksInStore());
        assertEquals(new File(dir, ".hg/bookmarks").getCanonicalFile(), repo.getBookmarksFile().getCanonicalFile());
        assertTrue(exists(new File(dir, ".hg/bookmarks")));
        assertEquals("bm1", new BookmarkCommand(repo).list().keySet().iterator().next());
    }

    @Test
    void hg4jReadsTheBookmarksRealHgWroteInTheStore() throws Exception {
        File dir = repositoryWithOneCommit("repo", true);
        String node = hg(dir, "log", "-r", "0", "-T", "{node}").trim();
        hg(dir, "bookmark", "-r", "0", "bm1");
        hg(dir, "bookmark", "-r", "0", "bm2");

        HgRepository repo = new HgRepository(dir);
        Map<String, String> listed = new BookmarkCommand(repo).list();
        assertEquals(node, listed.get("bm1"));
        assertEquals(node, listed.get("bm2"));
        assertEquals(2, listed.size());

        // the wire view (listkeys / what a pushing or pulling client sees) reads the same file
        Map<String, String> keys = new HgLocalClient(repo).listKeys("bookmarks");
        assertEquals(node, keys.get("bm1"));
        assertEquals(node, keys.get("bm2"));
        assertEquals(2, keys.size());
    }

    @Test
    void hg4jWritesTheBookmarksIntoTheStoreAndRealHgSeesThem() throws Exception {
        File dir = repositoryWithOneCommit("repo", true);
        String node = hg(dir, "log", "-r", "0", "-T", "{node}").trim();

        HgRepository repo = new HgRepository(dir);
        new BookmarkCommand(repo).setBookmarkName("own").setRevision(node).call();

        assertTrue(Files.readString(new File(dir, ".hg/store/bookmarks").toPath()).contains(node + " own"));
        assertFalse(exists(new File(dir, ".hg/bookmarks")), "hg4j created .hg/bookmarks in a bookmarks-in-store repository");
        assertTrue(hg(dir, "bookmarks").contains("own"));
        assertNoLockLeft(dir);

        new BookmarkCommand(repo).setBookmarkName("own").setDelete(true).call();
        assertFalse(hg(dir, "bookmarks").contains("own"));
        assertFalse(exists(new File(dir, ".hg/bookmarks")));
        assertNoLockLeft(dir);
    }

    @Test
    void stripMovesABookmarkOfTheStoreBackToTheSurvivingParent() throws Exception {
        File dir = repositoryWithOneCommit("repo", true);
        String first = hg(dir, "log", "-r", "0", "-T", "{node}").trim();
        Files.writeString(new File(dir, "a.txt").toPath(), "a\nb\n");
        hg(dir, "commit", "-m", "two");
        String second = hg(dir, "log", "-r", "1", "-T", "{node}").trim();
        hg(dir, "bookmark", "-r", "1", "tipbm");
        hg(dir, "update", "-r", "0");

        HgRepository repo = new HgRepository(dir);
        new StripCommand(repo).setRevision(second).call();

        String bookmarks = hg(dir, "bookmarks");
        assertTrue(bookmarks.contains("tipbm"), bookmarks);
        assertTrue(bookmarks.contains("0:" + first.substring(0, 12)), bookmarks);
        assertFalse(exists(new File(dir, ".hg/bookmarks")));
        hg(dir, "verify");
        assertNoLockLeft(dir);
    }

    private void holdWorkingCopyLockWhile(HgRepository repo, Runnable whileHeld) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> {
            try (HgLock wlock = repo.lockWorkingCopy(0)) {
                held.countDown();
                release.await();
            }
            return null;
        });
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS), "the other thread did not get the working copy lock");
            whileHeld.run();
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            pool.shutdownNow();
        }
    }

    @Test
    void pushingABookmarkToAStoreRepositoryNeedsOnlyTheStoreLock() throws Exception {
        File dir = repositoryWithOneCommit("repo", true);
        String node = hg(dir, "log", "-r", "0", "-T", "{node}").trim();
        Files.writeString(new File(dir, ".hg/hgrc").toPath(), "[ui]\ntimeout = 0\n"); // fail at once instead of waiting 600 seconds

        HgRepository repo = new HgRepository(dir);
        assertTrue(repo.isBookmarksInStore());
        holdWorkingCopyLockWhile(repo, () -> {
            try {
                // real hg's bookmarks.pushbookmark: no wlock when the bookmarks are in the store, so the held wlock is not in the way
                assertTrue(new HgLocalClient(repo).pushkey("bookmarks", "pushed", "", node));
            } catch (IOException e) {
                throw new AssertionError("pushkey waited for the working copy lock: " + e.getMessage(), e);
            }
        });

        assertTrue(Files.readString(new File(dir, ".hg/store/bookmarks").toPath()).contains(node + " pushed"));
        assertFalse(exists(new File(dir, ".hg/bookmarks")));
        assertTrue(hg(dir, "bookmarks").contains("pushed"));
        assertNoLockLeft(dir);
    }

    @Test
    void pushingABookmarkToAnOrdinaryRepositoryStillNeedsTheWorkingCopyLock() throws Exception {
        File dir = repositoryWithOneCommit("plain", false);
        String node = hg(dir, "log", "-r", "0", "-T", "{node}").trim();
        Files.writeString(new File(dir, ".hg/hgrc").toPath(), "[ui]\ntimeout = 0\n");

        HgRepository repo = new HgRepository(dir);
        assertFalse(repo.isBookmarksInStore());
        holdWorkingCopyLockWhile(repo, () ->
                // the control: here the bookmarks file is in .hg, so real hg takes the wlock and so must hg4j
                assertThrows(IOException.class, () -> new HgLocalClient(repo).pushkey("bookmarks", "pushed", "", node)));

        assertFalse(hg(dir, "bookmarks").contains("pushed"), "the refused pushkey changed the bookmarks");
        assertNoLockLeft(dir);
    }
}
