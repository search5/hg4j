package io.github.search5.hg4j.obsolete;

import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.transport.HgLocalClient;
import io.github.search5.hg4j.util.Base85;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
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
 * The {@code obsolete} pushkey namespace ({@code obsolete.listmarkers} / {@code pushmarker}). The expected bytes were produced by
 * Mercurial 7.2 ({@code debugobsolete} twice, then {@code debugpushkey . obsolete} and a push of that value into another repository).
 */
class ObsStorePushkeyTest {

    @TempDir
    Path tempDir;

    /** hg: `debugobsolete N0 N1 -d "100 0" -u u1` and `debugobsolete N2 -d "200 -3600" -u u2`; the store bytes it wrote. */
    private static final String REAL_OBSSTORE_HEX = "010000004340590000000000000000000001030" + "16a784b1de1c3573b696b82ae421df1af6fe097592dc39730dfd892c39c72f5d04b009c57a62ec22f040275736572753100"
            + "00002f4069000000000000ffc40000000301" + "1bc5265535f4c97" + "5c960875765e5d287063423e20402757365727532";

    private static final String N0 = "6a784b1de1c3573b696b82ae421df1af6fe09759";
    private static final String N1 = "2dc39730dfd892c39c72f5d04b009c57a62ec22f";

    private HgRepository repository(String name, boolean evolution) throws Exception {
        File dir = tempDir.resolve(name).toFile();
        HgRepository repo = Hg.init().setDirectory(dir).call();
        if (evolution) {
            Files.writeString(new File(dir, ".hg/hgrc").toPath(), "[experimental]\nevolution = all\n[ui]\ntimeout = 60\n");
            repo = new HgRepository(dir);
        }
        return repo;
    }

    private static byte[] hex(String s) {
        return HexFormat.of().parseHex(s);
    }

    /** two markers like the ones real hg wrote, built by hand: a pushed value then lands in the store as fm1. */
    private static byte[] fm0Dump() throws Exception {
        ObsStore.Marker a = new ObsStore.Marker(hex(N0), List.of(hex(N1)), 0, new TreeMap<>(Map.of("user", "u1")), 100.0, 0, null);
        ObsStore.Marker b = new ObsStore.Marker(hex("1bc5265535f4c975c960875765e5d28706" + "3423e2"), List.of(), 0,
                new TreeMap<>(Map.of("user", "u2")), 200.0, -3600, null);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(0);
        for (ObsStore.Marker m : List.of(b, a)) {
            out.write(ObsStore.encodeFm0(m));
        }
        return out.toByteArray();
    }

    @Test
    void base85RoundTripsAndMatchesTheValueRealHgListed() {
        String real = "0000002lxp#U@oX^vQL}V24*_<<f@+G$Z0<VRU6WGB7YMFd!{6HZU*%b#rBMI(0Gu0000K0BU$k9pS@QJ85fzu0kF0uW#U&SuMktFyGjc!<=&U&`SWESEeq)FJxhKWjZl1FfK44FaULPWpX-oF#";
        byte[] decoded = Base85.decode(real);
        assertEquals(0, decoded[0], "the fm0 version byte");
        assertEquals(real, Base85.encode(decoded));
        for (int n = 0; n < 12; n++) {
            byte[] data = new byte[n];
            for (int i = 0; i < n; i++) {
                data[i] = (byte) (i * 37 + 11);
            }
            assertArrayEquals(data, Base85.decode(Base85.encode(data)), "length " + n);
        }
    }

    @Test
    void theStoreBytesRealHgWroteAreReadAndWrittenBackIdentically() throws Exception {
        byte[] real = HexFormat.of().parseHex(REAL_OBSSTORE_HEX.replace(" ", ""));
        List<ObsStore.Marker> markers = ObsStore.read(real);
        assertEquals(2, markers.size());
        assertEquals(-3600, markers.get(1).tz());
        java.io.ByteArrayOutputStream rewritten = new java.io.ByteArrayOutputStream();
        rewritten.write(1);
        for (ObsStore.Marker m : markers) {
            rewritten.write(ObsStore.encodeFm1(m));
        }
        assertArrayEquals(real, rewritten.toByteArray());
    }

    @Test
    void theNamespaceIsOnlyOfferedWhenEvolutionExchangeIsEnabled() throws Exception {
        try (HgRepository off = repository("off", false); HgRepository on = repository("on", true)) {
            assertFalse(new HgLocalClient(off).listKeys("namespaces").containsKey("obsolete"));
            assertFalse(ObsStore.exchangeEnabled(off));
            assertTrue(ObsStore.exchangeEnabled(on));
            assertFalse(new HgLocalClient(off).pushkey("obsolete", "dump0", "", Base85.encode(fm0Dump())));
        }
    }

    @Test
    void aPushedDumpIsMergedIntoTheStoreAndThenListedBack() throws Exception {
        try (HgRepository target = repository("target", true)) {
            HgLocalClient client = new HgLocalClient(target);
            String value = Base85.encode(fm0Dump());
            assertTrue(client.pushkey("obsolete", "dump0", "", value));
            List<ObsStore.Marker> stored = ObsStore.markers(target);
            assertEquals(2, stored.size());
            assertEquals(1, Files.readAllBytes(new File(target.getStoreDir(), "obsstore").toPath())[0], "stored as fm1");
            // idempotent: the second push adds nothing
            long size = new File(target.getStoreDir(), "obsstore").length();
            assertTrue(client.pushkey("obsolete", "dump0", "", value));
            assertEquals(size, new File(target.getStoreDir(), "obsstore").length());
            // the listing gives the same markers back, sorted
            Map<String, String> listed = client.listKeys("obsolete");
            assertEquals(List.of("dump0"), new ArrayList<>(listed.keySet()));
            assertEquals(value, listed.get("dump0"));
        }
    }

    @Test
    void anUnknownKeyOrAnOldValueIsRefused() throws Exception {
        try (HgRepository target = repository("refuse", true)) {
            HgLocalClient client = new HgLocalClient(target);
            String value = Base85.encode(fm0Dump());
            assertFalse(client.pushkey("obsolete", "foo", "", value));
            assertFalse(client.pushkey("obsolete", "dump0", "x", value));
            assertFalse(new File(target.getStoreDir(), "obsstore").exists());
        }
    }

    @Test
    void aBrokenDumpLeavesTheStoreAsItWas() throws Exception {
        try (HgRepository target = repository("broken", true)) {
            HgLocalClient client = new HgLocalClient(target);
            assertTrue(client.pushkey("obsolete", "dump0", "", Base85.encode(fm0Dump())));
            byte[] before = Files.readAllBytes(new File(target.getStoreDir(), "obsstore").toPath());
            byte[] truncated = fm0Dump();
            byte[] bad = java.util.Arrays.copyOf(truncated, truncated.length - 3);
            assertThrows(Exception.class, () -> client.pushkey("obsolete", "dump1", "", Base85.encode(bad)));
            assertArrayEquals(before, Files.readAllBytes(new File(target.getStoreDir(), "obsstore").toPath()));
            assertFalse(new File(target.getStoreDir(), "lock").exists());
        }
    }

    @Test
    void theStoreLockIsTakenForTheMerge() throws Exception {
        try (HgRepository target = repository("locked", true)) {
            Files.writeString(new File(target.getDirectory(), ".hg/hgrc").toPath(), "[experimental]\nevolution = all\n[ui]\ntimeout = 0\n");
        }
        File dir = tempDir.resolve("locked").toFile();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (HgRepository other = new HgRepository(dir); HgLock l = other.lockStore()) {
                held.countDown();
                release.await();
            } catch (Exception ignored) {
                held.countDown();
            }
        });
        holder.start();
        assertTrue(held.await(10, TimeUnit.SECONDS));
        try (HgRepository target = new HgRepository(dir)) {
            assertThrows(Exception.class, () -> new HgLocalClient(target).pushkey("obsolete", "dump0", "", Base85.encode(fm0Dump())));
        } finally {
            release.countDown();
            holder.join();
        }
        assertFalse(new File(dir, ".hg/store/obsstore").exists(), "nothing was written without the lock");
    }

    @Test
    void manyPushesAtTheSameMomentLoseNoMarkerAndKeepTheStoreWellFormed() throws Exception {
        try (HgRepository target = repository("many", true)) {
            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> done = new ArrayList<>();
            File dir = target.getDirectory();
            for (int t = 0; t < threads; t++) {
                int n = t;
                done.add(pool.submit(() -> {
                    try (HgRepository own = new HgRepository(dir)) {
                        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                        out.write(0);
                        for (int i = 0; i < 6; i++) {
                            byte[] pre = new byte[20];
                            pre[0] = (byte) (n % 2); // the threads push overlapping sets: each marker must end up in the store once
                            pre[1] = (byte) i;
                            out.write(ObsStore.encodeFm0(new ObsStore.Marker(pre, List.of(), 0, new TreeMap<>(Map.of("user", "same")),
                                    1000.0 + i, 0, null)));
                        }
                        start.await();
                        assertTrue(new HgLocalClient(own).pushkey("obsolete", "dump0", "", Base85.encode(out.toByteArray())));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : done) {
                f.get(120, TimeUnit.SECONDS);
            }
            pool.shutdownNow();
            List<ObsStore.Marker> stored = ObsStore.markers(target);
            assertEquals(2 * 6, stored.size(), "every distinct marker exactly once, none duplicated or torn");
        }
    }
}
