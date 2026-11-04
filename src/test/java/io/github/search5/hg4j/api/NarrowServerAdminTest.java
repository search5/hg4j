package io.github.search5.hg4j.api;

import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.shape.ShapeConfigStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code admin::narrow-server}: the read only listings, the update with its three refusals, the saved previous configs, and above all the
 * locks ({@code store-shapes-lock} without waiting, then the store lock only around the write).
 */
class NarrowServerAdminTest {

    private static final String BASE = "version = 0\n[[shards]]\nname = \"foo\"\npaths = [\"foo\"]\n[[profiles]]\nname = \"default\"\nincludes = [\"base\"]\npublic = true\n";
    private static final String WITH_BAR = "version = 0\n[[shards]]\nname = \"foo\"\npaths = [\"foo\"]\n[[shards]]\nname = \"bar\"\npaths = [\"bar\"]\n"
            + "[[profiles]]\nname = \"default\"\nincludes = [\"base\", \"bar\"]\npublic = true\n";
    private static final String RESHAPED = BASE + "[[profiles]]\nname = \"everything\"\nincludes = [\"foo\", \"default\"]\npublic = true\n";

    @TempDir
    Path tempDir;

    File repoDir;

    @BeforeEach
    void init() throws Exception {
        repoDir = tempDir.resolve("server").toFile();
        Hg.init().setDirectory(repoDir).call().close();
    }

    private File shapesFile() {
        return new File(repoDir, ".hg/store/server-shapes");
    }

    /** The first config of a repository: from "nothing" both the shapes and the shards change, which hg refuses without the override. */
    private void initial(String config) throws Exception {
        try (HgRepository repo = new HgRepository(repoDir)) {
            new NarrowServerAdmin(repo).shapeUpdate(config.getBytes(StandardCharsets.UTF_8), new NarrowServerAdmin.UpdateOptions(true, false, false));
        }
    }

    @Test
    void theVeryFirstConfigNeedsTheResizeOverrideBecauseItReshapesAndReshardsAtOnce() throws Exception {
        try (HgRepository repo = new HgRepository(repoDir)) {
            HgValidationException e = assertThrows(HgValidationException.class,
                    () -> new NarrowServerAdmin(repo).shapeUpdate(BASE.getBytes(), NarrowServerAdmin.UpdateOptions.strict()));
            assertTrue(e.getMessage().startsWith("cannot reshape and reshard in the same update\nshapes changed: \"default\"\nshards changed: \"base\", \"foo\""),
                    e.getMessage());
            assertFalse(shapesFile().exists());
        }
        initial(BASE);
        assertTrue(shapesFile().isFile());
    }

    private void update(String config) throws Exception {
        try (HgRepository repo = new HgRepository(repoDir)) {
            new NarrowServerAdmin(repo).shapeUpdate(config.getBytes(StandardCharsets.UTF_8), NarrowServerAdmin.UpdateOptions.strict());
        }
    }

    @Test
    void theFirstUpdateWritesTheFileWithTheHeaderAndSavesNothing() throws Exception {
        initial(BASE);
        assertEquals(NarrowServerAdmin.HEADER + BASE, Files.readString(shapesFile().toPath()));
        try (HgRepository repo = new HgRepository(repoDir)) {
            assertEquals(List.of(), ShapeConfigStore.previousConfigs(repo));
        }
        assertFalse(new File(repoDir, ".hg/store/store-shapes-lock").exists(), "the shapes lock is released");
        assertFalse(new File(repoDir, ".hg/store/lock").exists(), "the store lock is released");
    }

    @Test
    void anUpdateKeepsTheHeaderItAlreadyHas() throws Exception {
        initial(NarrowServerAdmin.HEADER + BASE);
        assertEquals(NarrowServerAdmin.HEADER + BASE, Files.readString(shapesFile().toPath()));
    }

    @Test
    void reshardingSavesTheOldConfigAndAReshapeMustWaitForItToBeCleared() throws Exception {
        initial(BASE);
        update(WITH_BAR.replace("includes = [\"base\", \"bar\"]", "includes = [\"base\", \"bar\"]")); // adjusted shape: only a reshard
        try (HgRepository repo = new HgRepository(repoDir)) {
            List<String> saved = ShapeConfigStore.previousConfigs(repo);
            assertEquals(1, saved.size());
            assertTrue(saved.get(0).matches("server-shapes-previous-[0-9a-f]{12}"), saved.get(0));
            assertEquals(NarrowServerAdmin.HEADER + BASE, Files.readString(new File(repo.getStoreDir(), saved.get(0)).toPath()));
            // a reshape while a config is saved is refused
            HgValidationException refused = assertThrows(HgValidationException.class, () -> new NarrowServerAdmin(repo).shapeUpdate(
                    (WITH_BAR + "[[profiles]]\nname = \"more\"\nincludes = [\"foo\"]\npublic = true\n").getBytes(), NarrowServerAdmin.UpdateOptions.strict()));
            assertTrue(refused.getMessage().startsWith("cannot reshape yet: 1 previous server-shapes still saved\nshapes changed: \"more\""),
                    refused.getMessage());
            assertEquals("cleared 1 saved server-shapes\n", new NarrowServerAdmin(repo).shapeClearPrevious());
            assertEquals(List.of(), ShapeConfigStore.previousConfigs(repo));
            assertEquals(0, new File(repo.getStoreDir(), "server-shapes-previous-list").length());
            new NarrowServerAdmin(repo).shapeUpdate((WITH_BAR + "[[profiles]]\nname = \"more\"\nincludes = [\"foo\"]\npublic = true\n").getBytes(),
                    NarrowServerAdmin.UpdateOptions.strict());
        }
    }

    @Test
    void theThreeRefusalsAndTheirOverrides() throws Exception {
        initial(BASE);
        try (HgRepository repo = new HgRepository(repoDir)) {
            NarrowServerAdmin admin = new NarrowServerAdmin(repo);
            // redefining an existing shape: `default` now includes foo as well
            String redefined = BASE.replace("includes = [\"base\"]", "includes = [\"base\", \"foo\"]");
            HgValidationException e1 = assertThrows(HgValidationException.class,
                    () -> admin.shapeUpdate(redefined.getBytes(), NarrowServerAdmin.UpdateOptions.strict()));
            assertTrue(e1.getMessage().startsWith("won't change the fingerprint of existing shapes: \"default\""), e1.getMessage());
            // reshaping and resharding at once
            String both = WITH_BAR + "[[profiles]]\nname = \"extra\"\nincludes = [\"foo\"]\npublic = true\n";
            HgValidationException e2 = assertThrows(HgValidationException.class,
                    () -> admin.shapeUpdate(both.getBytes(), NarrowServerAdmin.UpdateOptions.strict()));
            assertTrue(e2.getMessage().startsWith("cannot reshape and reshard in the same update\nshapes changed: "), e2.getMessage());
            assertTrue(e2.getMessage().contains("\nshards changed: \"bar\", \"base\""), e2.getMessage());
            // nothing was written by the refusals
            assertEquals(NarrowServerAdmin.HEADER + BASE, Files.readString(shapesFile().toPath()));
            assertEquals(List.of(), ShapeConfigStore.previousConfigs(repo));
            // the overrides let them through
            admin.shapeUpdate(redefined.getBytes(), new NarrowServerAdmin.UpdateOptions(false, true, false));
            assertTrue(Files.readString(shapesFile().toPath()).contains("\"base\", \"foo\""));
        }
    }

    @Test
    void aDocumentThatDoesNotParseChangesNothing() throws Exception {
        initial(BASE);
        byte[] before = Files.readAllBytes(shapesFile().toPath());
        try (HgRepository repo = new HgRepository(repoDir)) {
            assertThrows(HgValidationException.class, () -> new NarrowServerAdmin(repo).shapeUpdate(
                    "version = 0\n[[shards]]\nname = \"Bad\"\npaths = [\"x\"]\n".getBytes(), NarrowServerAdmin.UpdateOptions.strict()));
        }
        assertArrayEquals(before, Files.readAllBytes(shapesFile().toPath()));
    }

    @Test
    void listingsMatchTheFormatOfTheCommand() throws Exception {
        initial(RESHAPED);
        try (HgRepository repo = new HgRepository(repoDir)) {
            NarrowServerAdmin admin = new NarrowServerAdmin(repo);
            List<String> fingerprints = admin.shapeFingerprints();
            assertEquals(3, fingerprints.size()); // default, everything, full
            assertTrue(fingerprints.get(0).matches("shape00-[0-9a-f]{64} default"), fingerprints.get(0));
            assertTrue(fingerprints.get(1).matches("shape00-[0-9a-f]{64} everything"), fingerprints.get(1));
            assertTrue(fingerprints.get(2).matches("shape00-[0-9a-f]{64} full"), fingerprints.get(2));
            assertEquals(List.of("inc:/", "exc:/foo"), admin.shapePatterns("default"), "base without foo; the special files are redundant");
            assertEquals("inc:/", admin.shapePatterns("full").get(0));
            assertEquals(List.of("[exclude]", "path:foo"), admin.shapeNarrowPatterns("default"));
            HgValidationException unknown = assertThrows(HgValidationException.class, () -> admin.shapePatterns("nope"));
            assertEquals("shape 'nope' not found", unknown.getMessage());
            assertEquals("invalid shard name 'Nope': only lowercase alphanumeric, hyphen or dot are accepted",
                    assertThrows(HgValidationException.class, () -> admin.shapePatterns("Nope")).getMessage());
            List<String> shards = admin.storeShardsListing();
            assertTrue(shards.stream().allMatch(s -> s.matches("shard00-[0-9a-f]{64}( read-write-access=.*)?")), shards.toString());
        }
    }

    @Test
    void aReadingCommandWarnsWhileAnUpdateIsInProgress() throws Exception {
        initial(BASE);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (HgRepository other = new HgRepository(repoDir); HgLock l = other.lockStoreShapes(0)) {
                held.countDown();
                release.await();
            } catch (Exception ignored) {
                held.countDown();
            }
        });
        holder.start();
        assertTrue(held.await(10, TimeUnit.SECONDS));
        try (HgRepository repo = new HgRepository(repoDir)) {
            NarrowServerAdmin admin = new NarrowServerAdmin(repo);
            admin.shapeFingerprints();
            assertEquals(1, admin.getWarnings().size());
            assertTrue(admin.getWarnings().get(0).startsWith("warning: store shapes are currently being updated (lock held by "), admin.getWarnings().toString());
        } finally {
            release.countDown();
            holder.join();
        }
    }

    @Test
    void anUpdateWhileAnotherHoldsTheShapesLockFailsAtOnceAndWritesNothing() throws Exception {
        initial(BASE);
        byte[] before = Files.readAllBytes(shapesFile().toPath());
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (HgRepository other = new HgRepository(repoDir); HgLock l = other.lockStoreShapes(0)) {
                held.countDown();
                release.await();
            } catch (Exception ignored) {
                held.countDown();
            }
        });
        holder.start();
        assertTrue(held.await(10, TimeUnit.SECONDS));
        long begin = System.currentTimeMillis();
        try (HgRepository repo = new HgRepository(repoDir)) {
            assertThrows(HgLockException.class, () -> new NarrowServerAdmin(repo).shapeUpdate(WITH_BAR.getBytes(), NarrowServerAdmin.UpdateOptions.strict()));
            assertThrows(HgLockException.class, () -> new NarrowServerAdmin(repo).shapeClearPrevious());
        } finally {
            release.countDown();
            holder.join();
        }
        assertTrue(System.currentTimeMillis() - begin < 20_000, "wait=False does not wait");
        assertArrayEquals(before, Files.readAllBytes(shapesFile().toPath()));
    }

    @Test
    void theStoreLockIsOnlyNeededForTheWrite() throws Exception {
        initial(BASE);
        // a held store lock: the validation passes, then the write waits for ui.timeout (0 in tests) and fails without touching the file
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (HgRepository other = new HgRepository(repoDir); HgLock l = other.lockStore()) {
                held.countDown();
                release.await();
            } catch (Exception ignored) {
                held.countDown();
            }
        });
        holder.start();
        assertTrue(held.await(10, TimeUnit.SECONDS));
        byte[] before = Files.readAllBytes(shapesFile().toPath());
        try (HgRepository repo = new HgRepository(repoDir)) {
            assertThrows(HgLockException.class, () -> new NarrowServerAdmin(repo).shapeUpdate(WITH_BAR.getBytes(), NarrowServerAdmin.UpdateOptions.strict()));
            // a read does not need the store lock at all
            assertEquals(2, new NarrowServerAdmin(repo).shapeFingerprints().size());
        } finally {
            release.countDown();
            holder.join();
        }
        assertArrayEquals(before, Files.readAllBytes(shapesFile().toPath()));
        assertFalse(new File(repoDir, ".hg/store/store-shapes-lock").exists(), "the shapes lock was released after the failure");
    }

    @Test
    void severalUpdatesAtTheSameMomentNeverLoseAConfigOrTearTheFile() throws Exception {
        initial(BASE);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger won = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        List<Future<?>> done = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int n = t;
            done.add(pool.submit(() -> {
                try (HgRepository own = new HgRepository(repoDir)) {
                    start.await();
                    // each writer adds its own shard: a reshard, always allowed on its own
                    String config = BASE + "[[shards]]\nname = \"s" + n + "\"\npaths = [\"s" + n + "\"]\n";
                    try {
                        new NarrowServerAdmin(own).shapeUpdate(config.getBytes(), new NarrowServerAdmin.UpdateOptions(true, true, true));
                        won.incrementAndGet();
                    } catch (HgLockException busy) {
                        refused.incrementAndGet(); // wait=False: somebody else is updating
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : done) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertEquals(threads, won.get() + refused.get());
        assertTrue(won.get() >= 1);
        // the file is one complete valid config, and every config that was replaced was saved exactly once
        try (HgRepository repo = new HgRepository(repoDir)) {
            io.github.search5.hg4j.shape.StoreShards.fromBytes(Files.readAllBytes(shapesFile().toPath()));
            List<String> saved = ShapeConfigStore.previousConfigs(repo);
            assertEquals(new java.util.HashSet<>(saved).size(), saved.size(), "no config saved twice");
            assertEquals(won.get(), saved.size(), "each of the updates replaced a config that was saved (the first one replaced BASE)");
            for (String name : saved) {
                assertTrue(new File(repo.getStoreDir(), name).isFile(), name);
            }
        }
        assertFalse(new File(repoDir, ".hg/store/store-shapes-lock").exists());
        assertFalse(new File(repoDir, ".hg/store/lock").exists());
    }
}
