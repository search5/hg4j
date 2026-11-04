package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.shape.ShardTreeNode;
import io.github.search5.hg4j.shape.StoreShapeFile;
import io.github.search5.hg4j.transport.HgHttpWireServer;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code hg clone --shape}: the client asks the server for the shape ({@code store_shape}), narrows to its patterns, keeps the names in
 * {@code store-shape}, and pulls later by naming the shape and its fingerprint.
 */
class ShapeCloneTest {

    private static final String CONFIG = "version = 0\n[[shards]]\nname = \"foo-files\"\npaths = [\"foo\"]\n[[shards]]\nname = \"bar-files\"\npaths = [\"bar\"]\n"
            + "[[profiles]]\nname = \"foo\"\nincludes = [\"foo-files\"]\npublic = true\n[[profiles]]\nname = \"bar\"\nincludes = [\"bar-files\"]\npublic = true\n";

    @TempDir
    Path tempDir;

    private HgRepository serverRepo;
    private File serverDir;
    private Server server;

    private void commit(String message) throws Exception {
        new AddCommand(serverRepo).call();
        new CommitCommand(serverRepo).setMessage(message).setAuthor("t").call();
    }

    @BeforeEach
    void startServer() throws Exception {
        serverDir = tempDir.resolve("server").toFile();
        Hg.init().setDirectory(serverDir).call().close();
        Files.writeString(new File(serverDir, ".hg/hgrc").toPath(), "[experimental]\nnarrow = true\nadvertise-shapes = true\n[ui]\ntimeout = 5\n");
        serverRepo = new HgRepository(serverDir);
        for (String sub : List.of("foo", "bar", "other")) {
            new File(serverDir, sub).mkdirs();
            Files.writeString(new File(serverDir, sub + "/f.txt").toPath(), sub + " 1\n");
        }
        Files.writeString(new File(serverDir, "top.txt").toPath(), "top\n");
        commit("first");
        new NarrowServerAdmin(serverRepo).shapeUpdate(CONFIG.getBytes(StandardCharsets.UTF_8), new NarrowServerAdmin.UpdateOptions(true, false, false));
        server = HgTestUtils.startServlet(new HgHttpWireServer(serverRepo));
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

    @Test
    void cloningAShapeBringsOnlyItsFilesAndRemembersTheShape() throws Exception {
        File dest = tempDir.resolve("foo-clone").toFile();
        new NarrowCloneCommand().setSource(url()).setDirectory(dest).setShape("foo").call();
        assertEquals("foo 1\n", Files.readString(new File(dest, "foo/f.txt").toPath()));
        assertFalse(new File(dest, "bar/f.txt").exists());
        assertFalse(new File(dest, "other/f.txt").exists());
        assertFalse(new File(dest, "top.txt").exists());
        try (HgRepository repo = new HgRepository(dest)) {
            assertEquals(List.of("foo"), StoreShapeFile.load(repo));
        }
        assertEquals("0\nfoo\n", Files.readString(new File(dest, ".hg/store/store-shape").toPath()));
    }

    @Test
    void aLaterPullNamesTheShapeAndStaysNarrow() throws Exception {
        File dest = tempDir.resolve("foo-clone").toFile();
        new NarrowCloneCommand().setSource(url()).setDirectory(dest).setShape("foo").call();
        Files.writeString(new File(serverDir, "foo/f.txt").toPath(), "foo 2\n");
        Files.writeString(new File(serverDir, "bar/f.txt").toPath(), "bar 2\n");
        commit("second");
        try (HgRepository repo = new HgRepository(dest)) {
            assertEquals(1, new PullCommand(repo).setSource(url()).call().size());
        }
        try (HgRepository repo = new HgRepository(dest)) {
            new UpdateCommand(repo).setTreeFilter(io.github.search5.hg4j.treewalk.HgTreeFilter.createNarrowSpecFilter(
                    List.of(io.github.search5.hg4j.treewalk.HgTreeFilter.normalizeNarrowPattern("path:foo")), List.of())).call();
        }
        assertEquals("foo 2\n", Files.readString(new File(dest, "foo/f.txt").toPath()));
        assertFalse(new File(dest, "bar/f.txt").exists(), "the pull was for the shape only");
    }

    @Test
    void aCompositeShapeCombinesTheirFiles() throws Exception {
        File dest = tempDir.resolve("both").toFile();
        new NarrowCloneCommand().setSource(url()).setDirectory(dest).setShape("bar,foo").call();
        assertTrue(new File(dest, "foo/f.txt").exists());
        assertTrue(new File(dest, "bar/f.txt").exists());
        assertFalse(new File(dest, "other/f.txt").exists());
        try (HgRepository repo = new HgRepository(dest)) {
            assertEquals(List.of("bar", "foo"), StoreShapeFile.load(repo), "names are sorted");
        }
    }

    @Test
    void anUnknownShapeLeavesNoRepositoryBehind() throws Exception {
        File dest = tempDir.resolve("nope").toFile();
        HgValidationException e = assertThrows(HgValidationException.class,
                () -> new NarrowCloneCommand().setSource(url()).setDirectory(dest).setShape("nope").call());
        assertEquals("shape not found on remote: 'nope'", e.getMessage());
        assertFalse(dest.exists());
    }

    @Test
    void aServerWithoutShapesIsRefusedWithTheCapabilityMessage() throws Exception {
        Files.writeString(new File(serverDir, ".hg/hgrc").toPath(), "[experimental]\nnarrow = true\n");
        HgTestUtils.stop(server); // the server reads its configuration when it starts
        serverRepo = new HgRepository(serverDir);
        server = HgTestUtils.startServlet(new HgHttpWireServer(serverRepo));
        File dest = tempDir.resolve("nocap").toFile();
        HgValidationException e = assertThrows(HgValidationException.class,
                () -> new NarrowCloneCommand().setSource(url()).setDirectory(dest).setShape("foo").call());
        assertEquals("cannot use store shapes; remote repository does not support the 'exp-shape-1' capability", e.getMessage());
        assertFalse(dest.exists());
    }

    @Test
    void theStoreShapeFileFormatAndItsErrors() throws Exception {
        assertArrayEquals("0\nfoo,bar\n".getBytes(), StoreShapeFile.format(List.of("foo", "bar")));
        assertEquals(List.of("foo", "bar"), StoreShapeFile.parse("0\nfoo,bar\n".getBytes()));
        assertEquals("empty store-hape file", assertThrows(HgValidationException.class, () -> StoreShapeFile.parse(new byte[0])).getMessage());
        assertEquals("invalid store-hape version line", assertThrows(HgValidationException.class, () -> StoreShapeFile.parse("x\nfoo\n".getBytes())).getMessage());
        assertEquals("unknown store-hape version number: 1", assertThrows(HgValidationException.class, () -> StoreShapeFile.parse("1\nfoo\n".getBytes())).getMessage());
        assertEquals("too many lines in store-hape: expected 2, got 3",
                assertThrows(HgValidationException.class, () -> StoreShapeFile.parse("0\nfoo\n\n".getBytes())).getMessage());
        assertEquals("store-shape contains invalid UTF8",
                assertThrows(HgValidationException.class, () -> StoreShapeFile.parse(new byte[]{'0', '\n', (byte) 0xff, '\n'})).getMessage());
    }

    @Test
    void theClientFingerprintOfItsLocalPatternsIsWhatTheServerComputes() throws Exception {
        // the fingerprint a shape clone sends is computed from the narrowspec on disk; it must equal the server's for the same shape
        File dest = tempDir.resolve("fp").toFile();
        new NarrowCloneCommand().setSource(url()).setDirectory(dest).setShape("foo").call();
        io.github.search5.hg4j.shape.StoreShards shards = io.github.search5.hg4j.shape.StoreShards.fromRepository(serverRepo);
        ShardTreeNode.Patterns patterns = shards.shape(List.of("foo")).patterns();
        assertEquals(shards.shape(List.of("foo")).storeFingerprint(),
                ShardTreeNode.fromPatterns(Set.copyOf(patterns.includes()), Set.copyOf(patterns.excludes())).fingerprint());
    }
}
