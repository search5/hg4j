package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.lib.HgLock;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.transport.HgHttpWireServer;
import io.github.search5.hg4j.transport.HgRemoteConnection;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Proxy;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * The legacy stream clone from the client's side ({@code streamclone.maybeperformlegacystreamclone} + {@code consumev1}), against the
 * hg4j wire server: the store files arrive as they are, under the store lock, and a failed transfer leaves nothing behind.
 */
class StreamCloneClientTest {

    @TempDir
    Path tempDir;

    private Server server;
    private File serverDir;

    @BeforeEach
    void startServer() throws Exception {
        serverDir = tempDir.resolve("server").toFile();
        try (HgRepository repo = Hg.init().setDirectory(serverDir).call()) {
            new File(serverDir, "dir/Sub").mkdirs();
            for (int i = 0; i < 4; i++) {
                Files.writeString(new File(serverDir, "a.txt").toPath(), "version " + i + "\n");
                Files.writeString(new File(serverDir, "dir/Sub/Mixed" + i + ".txt").toPath(), "file " + i + "\n");
                new AddCommand(repo).call();
                new CommitCommand(repo).setMessage("r" + i).setAuthor("t").call();
            }
        }
        // a real hg server waits ui.timeout for its store lock; concurrent clones need that
        Files.writeString(new File(serverDir, ".hg/hgrc").toPath(), "[ui]\ntimeout = 60\n");
        server = HgTestUtils.startServlet(new HgHttpWireServer(new HgRepository(serverDir)));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            HgTestUtils.stop(server);
        }
    }

    private String url() {
        return "http://127.0.0.1:" + HgTestUtils.port(server);
    }

    private static int revisions(File dir) throws Exception {
        try (HgRepository repo = new HgRepository(dir)) {
            return repo.getRevlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d")).getRevisionCount();
        }
    }

    @Test
    void aStreamCloneCopiesTheStoreFilesAsTheyAre() throws Exception {
        File dest = tempDir.resolve("dest").toFile();
        CloneCommand clone = new CloneCommand().setSource(url()).setDirectory(dest).setStream(true);
        clone.call().close();
        // both sides speak bundle2 streaming, so the stream2 part is used and the legacy "streaming all changes" is not printed
        assertFalse(clone.getMessages().contains("streaming all changes\n"), clone.getMessages().toString());
        assertTrue(clone.getMessages().stream().anyMatch(m -> m.matches("\\d+ files to transfer, .* of data\n")), clone.getMessages().toString());
        assertEquals(4, revisions(dest));
        for (String file : List.of("00changelog.i", "00manifest.i")) {
            assertArrayEquals(Files.readAllBytes(new File(serverDir, ".hg/store/" + file).toPath()),
                    Files.readAllBytes(new File(dest, ".hg/store/" + file).toPath()), file + " is the server's file");
        }
        assertEquals("version 3\n", Files.readString(new File(dest, "a.txt").toPath()));
        assertEquals("file 3\n", Files.readString(new File(dest, "dir/Sub/Mixed3.txt").toPath()));
        try (HgRepository repo = new HgRepository(dest)) {
            assertEquals(List.of(), new VerifyCommand(repo).call());
        }
        assertFalse(new File(dest, ".hg/store/lock").exists());
        assertFalse(new File(dest, ".hg/wlock").exists());
    }

    @Test
    void theLegacyStreamIsUsedWhenOnlyItIsOffered() throws Exception {
        byte[] body;
        try (HgRemoteConnection real = io.github.search5.hg4j.transport.HgRemoteConnectionFactory.createConnection(url())) {
            body = real.streamOut();
        }
        File dest = tempDir.resolve("legacy").toFile();
        try (HgRepository repo = Hg.init().setDirectory(dest).call()) {
            StreamCloneClient.Outcome outcome = StreamCloneClient.maybePerform(repo, streaming(body, List.of("stream")), true);
            assertTrue(outcome.done());
            assertEquals("streaming all changes\n", outcome.messages().get(0));
        }
        assertEquals(4, revisions(dest));
        try (HgRepository repo = new HgRepository(dest)) {
            assertEquals(List.of(), new VerifyCommand(repo).call());
        }
    }

    @Test
    void withoutStreamTheChangesetsArePulledAsBefore() throws Exception {
        File dest = tempDir.resolve("dest").toFile();
        CloneCommand clone = new CloneCommand().setSource(url()).setDirectory(dest).setStream(false);
        clone.call().close();
        assertEquals(List.of(), clone.getMessages());
        assertEquals(4, revisions(dest));
    }

    @Test
    void aRepositoryThatAlreadyHasChangesetsIsNotStreamedInto() throws Exception {
        File dest = tempDir.resolve("dest").toFile();
        new CloneCommand().setSource(url()).setDirectory(dest).setStream(false).call().close();
        try (HgRepository repo = new HgRepository(dest)) {
            PullCommand pull = new PullCommand(repo).setSource(url()).setStream(true);
            pull.call();
            assertEquals(List.of(), pull.getMessages(), "len(repo) != 0: no stream clone");
        }
    }

    private static HgRemoteConnection streaming(byte[] body, List<String> capabilities) {
        return (HgRemoteConnection) Proxy.newProxyInstance(StreamCloneClientTest.class.getClassLoader(),
                new Class<?>[]{HgRemoteConnection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getCapabilities" -> capabilities;
                    case "streamOut" -> body;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static byte[] body(String status, String counts, String... fileHeadersAndData) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((status + "\n").getBytes(StandardCharsets.US_ASCII));
        if (counts != null) {
            out.write((counts + "\n").getBytes(StandardCharsets.US_ASCII));
        }
        for (String part : fileHeadersAndData) {
            out.write(part.getBytes(StandardCharsets.ISO_8859_1));
        }
        return out.toByteArray();
    }

    private HgRepository emptyRepo(String name) throws Exception {
        return Hg.init().setDirectory(tempDir.resolve(name).toFile()).call();
    }

    @Test
    void theServerRefusingOrFailingToLockIsReportedAsHgDoes() throws Exception {
        try (HgRepository repo = emptyRepo("refused")) {
            Exception e1 = assertThrows(Exception.class,
                    () -> StreamCloneClient.maybePerform(repo, streaming(body("1", null), List.of("stream")), true));
            assertTrue(e1.getMessage().contains("operation forbidden by server"), e1.getMessage());
            Exception e2 = assertThrows(Exception.class,
                    () -> StreamCloneClient.maybePerform(repo, streaming(body("2", null), List.of("stream")), true));
            assertTrue(e2.getMessage().contains("locking the remote repository failed"), e2.getMessage());
            Exception e3 = assertThrows(Exception.class,
                    () -> StreamCloneClient.maybePerform(repo, streaming(body("7", null), List.of("stream")), true));
            assertTrue(e3.getMessage().contains("the server sent an unknown error code"), e3.getMessage());
        }
    }

    @Test
    void aTransferThatBreaksOffLeavesNoStoreFilesAndReleasesTheLock() throws Exception {
        try (HgRepository repo = emptyRepo("broken")) {
            File store = repo.getStoreDir();
            // announces two files, the second one ends in the middle of its data
            byte[] broken = body("0", "2 20", "00changelog.i\0" + "5\n" + "abcde", "00manifest.i\0" + "10\n" + "xyz");
            assertThrows(Exception.class, () -> StreamCloneClient.maybePerform(repo, streaming(broken, List.of("stream")), true));
            assertFalse(new File(store, "00changelog.i").exists(), "the first file was removed again");
            assertFalse(new File(store, "00manifest.i").exists());
            assertFalse(new File(store, "lock").exists());
        }
    }

    @Test
    void theStoreLockIsNeededAndWaitedForPerTheTimeout() throws Exception {
        File dest = tempDir.resolve("locked").toFile();
        try (HgRepository repo = Hg.init().setDirectory(dest).call()) {
            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread holder = new Thread(() -> {
                try (HgRepository other = new HgRepository(dest); HgLock l = other.lockStore()) {
                    held.countDown();
                    release.await();
                } catch (Exception ignored) {
                    held.countDown();
                }
            });
            holder.start();
            assertTrue(held.await(10, TimeUnit.SECONDS));
            byte[] good = body("0", "1 3", "00changelog.i\0" + "3\n" + "abc");
            try {
                assertThrows(HgLockException.class, () -> StreamCloneClient.maybePerform(repo, streaming(good, List.of("stream")), true));
            } finally {
                release.countDown();
                holder.join();
            }
            assertFalse(new File(repo.getStoreDir(), "00changelog.i").exists(), "nothing was written without the lock");
        }
    }

    @Test
    void severalStreamClonesFromOneServerAtTheSameMomentAllComeOutIntact() throws Exception {
        int clones = 5;
        ExecutorService pool = Executors.newFixedThreadPool(clones);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        for (int i = 0; i < clones; i++) {
            File dest = tempDir.resolve("many" + i).toFile();
            done.add(pool.submit(() -> {
                start.await();
                new CloneCommand().setSource(url()).setDirectory(dest).setStream(true).call().close();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : done) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        for (int i = 0; i < clones; i++) {
            File dest = tempDir.resolve("many" + i).toFile();
            assertEquals(4, revisions(dest));
            try (HgRepository repo = new HgRepository(dest)) {
                assertEquals(List.of(), new VerifyCommand(repo).call());
            }
        }
    }
}
