package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code add}, {@code forget}, {@code remove}, {@code revert} and {@code addremove} reach into hg subrepositories the way
 * {@code cmdutil} does (the expected output of each case was observed with Mercurial 7.2): the parent's matcher is handed down,
 * and the subrepository works under its own working copy lock while the parent's lock stays held.
 */
class SubrepoRecursionTest {

    @TempDir
    Path tempDir;

    File parentDir;
    File subDir;

    @BeforeEach
    void fixture() throws Exception {
        parentDir = tempDir.resolve("parent").toFile();
        subDir = new File(parentDir, "sub");
        try (HgRepository sub = Hg.init().setDirectory(subDir).call()) {
            write(subDir, "s.txt", "s\n");
            write(subDir, "t.txt", "t\n");
            new AddCommand(sub).call();
            new CommitCommand(sub).setMessage("s").setAuthor("t").call();
        }
        try (HgRepository parent = Hg.init().setDirectory(parentDir).call()) {
            write(parentDir, ".hgsub", "sub = sub\n");
            write(parentDir, "p.txt", "p\n");
            new AddCommand(parent).call();
            new CommitCommand(parent).setMessage("p").setAuthor("t").call();
        }
    }

    private static void write(File dir, String name, String text) throws Exception {
        Files.writeString(new File(dir, name).toPath(), text);
    }

    private Map<String, Character> subStates() throws Exception {
        try (HgRepository sub = new HgRepository(subDir)) {
            Map<String, Character> states = new TreeMap<>();
            for (Map.Entry<String, Dirstate.Entry> e : sub.getDirstate().getEntries().entrySet()) {
                states.put(e.getKey(), e.getValue().getState());
            }
            return states;
        }
    }

    @Test
    void aNamedFileInsideTheSubrepositoryIsAddedThere() throws Exception {
        write(subDir, "new1.txt", "1\n");
        write(subDir, "new2.txt", "2\n");
        try (HgRepository parent = new HgRepository(parentDir)) {
            new AddCommand(parent).setPatterns("sub/new1.txt").call();
        }
        assertEquals('a', subStates().get("new1.txt"));
        assertNull(subStates().get("new2.txt"), "only the named file");
    }

    @Test
    void withoutSNothingElseInTheSubrepositoryIsAddedAndWithSEverythingIs() throws Exception {
        write(subDir, "new1.txt", "1\n");
        write(subDir, "new2.txt", "2\n");
        try (HgRepository parent = new HgRepository(parentDir)) {
            new AddCommand(parent).call();
        }
        assertNull(subStates().get("new1.txt"));
        try (HgRepository parent = new HgRepository(parentDir)) {
            new AddCommand(parent).setSubrepos(true).call();
        }
        assertEquals('a', subStates().get("new1.txt"));
        assertEquals('a', subStates().get("new2.txt"));
    }

    @Test
    void forgetReachesOnlyTheFilesNamedExactly() throws Exception {
        write(subDir, "new1.txt", "1\n");
        write(subDir, "new2.txt", "2\n");
        try (HgRepository parent = new HgRepository(parentDir)) {
            new AddCommand(parent).setSubrepos(true).call();
            // a glob does not reach into the subrepository (explicitonly)
            assertEquals(List.of(), new ForgetCommand(parent).setPatterns("glob:sub/new*").callSelected());
            assertEquals('a', subStates().get("new1.txt"));
            assertEquals(List.of("sub/new1.txt"), new ForgetCommand(parent).setPatterns("sub/new1.txt").callSelected());
        }
        assertNull(subStates().get("new1.txt"));
        assertEquals('a', subStates().get("new2.txt"));
    }

    @Test
    void removeNamesTheFileOrWalksTheWholeSubrepositoryWithS() throws Exception {
        try (HgRepository parent = new HgRepository(parentDir)) {
            assertEquals(List.of("sub/s.txt"), new RemoveCommand(parent).setPatterns("sub/s.txt").callSelected());
            assertEquals('r', subStates().get("s.txt"));
            assertEquals('n', subStates().get("t.txt"));
            assertFalse(new File(subDir, "s.txt").exists());
            RemoveCommand everything = new RemoveCommand(parent).setPatterns(".").setSubrepos(true);
            List<String> removed = everything.callSelected();
            assertTrue(removed.contains("p.txt") && removed.contains("sub/t.txt"), removed + " warnings=" + everything.getWarnings());
        }
        assertEquals('r', subStates().get("t.txt"));
    }

    @Test
    void revertPutsTheSubrepositoryBackAndKeepsABackupOfTheEditedFile() throws Exception {
        write(subDir, "s.txt", "edited\n");
        List<String> messages;
        try (HgRepository parent = new HgRepository(parentDir)) {
            RevertCommand revert = new RevertCommand(parent).setPatterns("sub");
            revert.callSelected();
            messages = revert.getSubrepoMessages();
        }
        assertEquals(List.of("reverting subrepo sub\n"), messages);
        assertEquals("s\n", Files.readString(new File(subDir, "s.txt").toPath()));
        assertEquals("edited\n", Files.readString(new File(subDir, "s.txt.orig").toPath()), "the edit is kept as .orig");
    }

    @Test
    void revertAllEntersEverySubrepository() throws Exception {
        write(subDir, "t.txt", "edited\n");
        try (HgRepository parent = new HgRepository(parentDir)) {
            new RevertCommand(parent).setAll(true).callSelected();
        }
        assertEquals("t\n", Files.readString(new File(subDir, "t.txt").toPath()));
    }

    @Test
    void addremoveWithSWorksInsideTheSubrepository() throws Exception {
        Files.delete(new File(subDir, "s.txt").toPath());
        write(subDir, "zz.txt", "z\n");
        try (HgRepository parent = new HgRepository(parentDir)) {
            assertEquals(List.of(), new AddremoveCommand(parent).call(), "without -S the subrepository is left alone");
            assertNull(subStates().get("zz.txt"));
            List<String> changed = new AddremoveCommand(parent).setSubrepos(true).call();
            assertTrue(changed.contains("A sub/zz.txt") && changed.contains("R sub/s.txt"), changed.toString());
        }
        assertEquals('a', subStates().get("zz.txt"));
        assertEquals('r', subStates().get("s.txt"));
    }

    @Test
    void aSubrepositoryThatIsNotOnTheDiskIsSkippedWithAMessage() throws Exception {
        deleteTree(subDir.toPath());
        try (HgRepository parent = new HgRepository(parentDir)) {
            AddCommand add = new AddCommand(parent).setSubrepos(true);
            add.call();
            assertEquals(List.of("skipping missing subrepository: sub\n"), add.getSubrepoMessages());
        }
    }

    private static void deleteTree(Path root) throws Exception {
        try (var walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        }
    }

    @Test
    void theSubrepositoryTakesItsOwnWorkingCopyLockAndFailsWhenItIsBusy() throws Exception {
        write(subDir, "new1.txt", "1\n");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (HgRepository other = new HgRepository(subDir); HgLock l = other.lockWorkingCopy()) {
                held.countDown();
                release.await();
            } catch (Exception ignored) {
                held.countDown();
            }
        });
        holder.start();
        assertTrue(held.await(10, TimeUnit.SECONDS));
        try (HgRepository parent = new HgRepository(parentDir)) {
            assertThrows(HgLockException.class, () -> new AddCommand(parent).setPatterns("sub/new1.txt").call());
        } finally {
            release.countDown();
            holder.join();
        }
        assertNull(subStates().get("new1.txt"), "nothing was written without the subrepository's lock");
        assertFalse(new File(parentDir, ".hg/wlock").exists(), "the parent's lock was released");
    }

    @Test
    void addsThroughTheParentAndDirectlyInTheSubrepositoryAtTheSameMomentLoseNoFile() throws Exception {
        int perSide = 12;
        for (int i = 0; i < perSide; i++) {
            write(subDir, "viaparent" + i + ".txt", "x\n");
            write(subDir, "direct" + i + ".txt", "x\n");
        }
        for (File dir : List.of(parentDir, subDir)) {
            Files.writeString(new File(dir, ".hg/hgrc").toPath(), "[ui]\ntimeout = 120\n");
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        for (int i = 0; i < perSide; i++) {
            int n = i;
            done.add(pool.submit(() -> {
                try (HgRepository parent = new HgRepository(parentDir)) {
                    start.await();
                    new AddCommand(parent).setPatterns("sub/viaparent" + n + ".txt").call();
                }
                return null;
            }));
            done.add(pool.submit(() -> {
                try (HgRepository sub = new HgRepository(subDir)) {
                    start.await();
                    new AddCommand(sub).addFile("direct" + n + ".txt").call();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : done) {
            f.get(180, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        Map<String, Character> states = subStates();
        for (int i = 0; i < perSide; i++) {
            assertEquals('a', states.get("viaparent" + i + ".txt"), "lost: viaparent" + i + " in " + states);
            assertEquals('a', states.get("direct" + i + ".txt"), "lost: direct" + i + " in " + states);
        }
        assertFalse(new File(subDir, ".hg/wlock").exists());
        assertFalse(new File(parentDir, ".hg/wlock").exists());
    }
}
