package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** {@code hg shelve --delete} and {@code --cleanup} (shelve.py deletecmd, cleanupcmd, cleanupoldbackups, Shelf.movetobackup). */
class ShelveDeleteCleanupTest {

    @TempDir
    Path tempDir;

    private File repoDir;

    private HgRepository repositoryWithShelves(String... shelfNames) throws Exception {
        repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "base\n");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("base").setAuthor("t").call();
        for (String name : shelfNames) {
            Files.writeString(new File(repoDir, "a.txt").toPath(), "change for " + name + "\n");
            new ShelveCommand(repo).setName(name).call();
        }
        return repo;
    }

    private TreeSet<String> files(String dir) {
        String[] names = new File(repoDir, ".hg/" + dir).list();
        return names == null ? new TreeSet<>() : new TreeSet<>(Arrays.asList(names));
    }

    @Test
    void deleteMovesTheNamedShelfToTheBackupDirectory() throws Exception {
        HgRepository repo = repositoryWithShelves("one", "two");
        new ShelveCommand(repo).setDelete(true).setNames("one").call();
        assertFalse(files("shelved").stream().anyMatch(n -> n.startsWith("one.")));
        assertTrue(files("shelved").stream().anyMatch(n -> n.startsWith("two.")), "other shelves stay");
        assertTrue(files("shelve-backup").containsAll(java.util.List.of("one.hg", "one.patch", "one.shelve", "one.state")), files("shelve-backup").toString());
    }

    @Test
    void deleteWithoutNamesOrWithAnUnknownNameIsRefusedLikeHg() throws Exception {
        HgRepository repo = repositoryWithShelves("one");
        HgValidationException none = assertThrows(HgValidationException.class, () -> new ShelveCommand(repo).setDelete(true).call());
        assertEquals("no shelved changes specified!", none.getMessage());
        HgValidationException missing = assertThrows(HgValidationException.class,
                () -> new ShelveCommand(repo).setDelete(true).setNames("nope").call());
        assertEquals("shelved change 'nope' not found", missing.getMessage());
        assertTrue(files("shelved").stream().anyMatch(n -> n.startsWith("one.")), "a refused delete changes nothing");
    }

    @Test
    void aBackupNameThatIsTakenGetsANumber() throws Exception {
        HgRepository repo = repositoryWithShelves("same");
        new ShelveCommand(repo).setDelete(true).setNames("same").call();
        // shelve the same name again and delete it again: hg writes same-1.* next to the first backup
        Files.writeString(new File(repoDir, "a.txt").toPath(), "again\n");
        new ShelveCommand(repo).setName("same").call();
        new ShelveCommand(repo).setDelete(true).setNames("same").call();
        TreeSet<String> backups = files("shelve-backup");
        assertTrue(backups.containsAll(java.util.List.of("same.hg", "same.patch", "same.shelve", "same.state",
                "same-1.hg", "same-1.patch", "same-1.shelve", "same-1.state")), backups.toString());
    }

    @Test
    void cleanupMovesEveryShelf() throws Exception {
        HgRepository repo = repositoryWithShelves("a", "b", "c");
        new ShelveCommand(repo).setCleanup(true).call();
        assertTrue(files("shelved").stream().noneMatch(n -> n.endsWith(".hg") || n.endsWith(".patch") || n.endsWith(".shelve")),
                files("shelved").toString());
        assertEquals(12, files("shelve-backup").size());
    }

    @Test
    void onlyTheNewestMaxBackupsBackupsAreKept() throws Exception {
        HgRepository repo = repositoryWithShelves("a", "b", "c");
        Files.writeString(new File(repoDir, ".hg/hgrc").toPath(), "[shelve]\nmaxbackups = 2\n");
        // distinct modification times so that "newest" is defined (hg compares whole seconds)
        long now = System.currentTimeMillis() / 1000L;
        int i = 0;
        for (String n : new String[] {"a", "b", "c"}) {
            for (String ext : new String[] {"hg", "patch", "shelve", "state"}) {
                assertTrue(new File(repoDir, ".hg/shelved/" + n + "." + ext).setLastModified((now - 100 + 10 * i) * 1000L));
            }
            i++;
        }
        try (HgRepository fresh = new HgRepository(repoDir)) {
            new ShelveCommand(fresh).setCleanup(true).call();
        }
        // cleanup moves the newest shelf first (c), so when a is moved the two oldest backups beyond the limit are dropped
        TreeSet<String> backups = files("shelve-backup");
        assertEquals(8, backups.size(), backups.toString());
        assertTrue(backups.stream().noneMatch(n -> n.startsWith("a.")), "the oldest backup is dropped: " + backups);
    }

    @Test
    void deleteAndCleanupWaitForTheWorkingCopyLockAndFailWhenItStaysHeld() throws Exception {
        HgRepository repo = repositoryWithShelves("one");
        Files.writeString(new File(repoDir, ".hg/hgrc").toPath(), "[ui]\ntimeout = 1\n");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (HgRepository other = new HgRepository(repoDir); HgLock l = other.lockWorkingCopy()) {
                held.countDown();
                release.await();
            } catch (Exception ignored) {
                held.countDown();
            }
        });
        holder.start();
        assertTrue(held.await(10, TimeUnit.SECONDS));
        try (HgRepository fresh = new HgRepository(repoDir)) {
            assertThrows(HgLockException.class, () -> new ShelveCommand(fresh).setDelete(true).setNames("one").call());
            assertThrows(HgLockException.class, () -> new ShelveCommand(fresh).setCleanup(true).call());
        } finally {
            release.countDown();
            holder.join();
        }
        assertTrue(files("shelved").stream().anyMatch(n -> n.startsWith("one.")), "nothing may be moved without the lock");
    }
}
