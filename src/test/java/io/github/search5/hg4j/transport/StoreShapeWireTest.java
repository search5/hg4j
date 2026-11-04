package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.NarrowServerAdmin;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.shape.Fingerprint;
import io.github.search5.hg4j.shape.ShapeWire;
import io.github.search5.hg4j.shape.ShardTreeNode;
import io.github.search5.hg4j.shape.StoreShards;
import io.github.search5.hg4j.transport.wireprotov1.Wire1Commands;
import io.github.search5.hg4j.transport.wireprotov1.Wire1Response;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code store_shape} wire command (server side {@code wireprotov1server.store_shape}, client side {@code wireprotov1peer.store_shape}),
 * its {@code exp-shape-1} capability, and the {@code store_shape_with_fingerprint} argument of {@code getbundle}.
 */
class StoreShapeWireTest {

    private static final String CONFIG = "version = 0\n[[shards]]\nname = \"foo-files\"\npaths = [\"foo\"]\n[[shards]]\nname = \"bar-files\"\npaths = [\"bar\"]\n"
            + "[[profiles]]\nname = \"foo\"\nincludes = [\"foo-files\"]\npublic = true\n[[profiles]]\nname = \"bar\"\nincludes = [\"bar-files\"]\npublic = true\n";

    @TempDir
    Path tempDir;

    private HgRepository serverRepo;
    private Server server;

    @BeforeEach
    void startServer() throws Exception {
        File dir = tempDir.resolve("server").toFile();
        serverRepo = Hg.init().setDirectory(dir).call();
        Files.writeString(new File(dir, ".hg/hgrc").toPath(), "[experimental]\nnarrow = true\nadvertise-shapes = true\n[ui]\ntimeout = 5\n");
        serverRepo = new HgRepository(dir);
        for (String sub : List.of("foo", "bar", "other")) {
            new File(dir, sub).mkdirs();
            Files.writeString(new File(dir, sub + "/f.txt").toPath(), sub + "\n");
        }
        new AddCommand(serverRepo).call();
        new CommitCommand(serverRepo).setMessage("c").setAuthor("t").call();
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
    void theCapabilityNeedsBothNarrowAndAdvertiseShapes() throws Exception {
        try (HgRemoteConnection c = HgRemoteConnectionFactory.createConnection(url())) {
            assertTrue(c.getCapabilities().contains("exp-shape-1"));
            assertTrue(c.getCapabilities().contains("exp-narrow-1"));
        }
        for (String config : List.of("[experimental]\nnarrow = true\n", "[experimental]\nadvertise-shapes = true\n", "")) {
            Files.writeString(new File(serverRepo.getDirectory(), ".hg/hgrc").toPath(), config);
            try (HgRepository fresh = new HgRepository(serverRepo.getDirectory())) {
                String caps = new String(Wire1Commands.capabilities(fresh).getPayload(), StandardCharsets.UTF_8);
                assertFalse(caps.contains("exp-shape-1"), "only inside the narrow block and with advertise-shapes: " + config);
            }
        }
    }

    @Test
    void aClientGetsThePatternsFingerprintAndShardSetsOfAShape() throws Exception {
        try (HgRemoteConnection c = HgRemoteConnectionFactory.createConnection(url())) {
            ShapeWire.Reply reply = c.storeShape(List.of("foo"));
            StoreShards shards = StoreShards.fromRepository(serverRepo);
            assertEquals(List.of(shards.shape(List.of("foo")).storeFingerprint()), reply.fingerprints());
            assertEquals(shards.shape(List.of("foo")).patterns().includes(), reply.patterns().includes());
            assertEquals(shards.shape(List.of("foo")).patterns().excludes(), reply.patterns().excludes());
            assertEquals(shards.shardFingerprintsForShape(shards.shape(List.of("foo"))), reply.shardsSets());
            // the patterns of foo: its files and the special ones, the rest excluded
            assertTrue(reply.patterns().includes().contains("foo"));
            assertTrue(reply.patterns().excludes().contains(""));
            // a composite shape
            ShapeWire.Reply both = c.storeShape(List.of("foo", "bar"));
            assertTrue(both.patterns().includes().containsAll(Set.of("foo", "bar")));
        }
    }

    @Test
    void anUnknownShapeIsReportedWithItsName() throws Exception {
        try (HgRemoteConnection c = HgRemoteConnectionFactory.createConnection(url())) {
            HgValidationException e = assertThrows(HgValidationException.class, () -> c.storeShape(List.of("nope")));
            assertEquals("shape not found on remote: 'nope'", e.getMessage());
            // a shard is not a shape either
            assertEquals("shape not found on remote: 'foo-files'",
                    assertThrows(HgValidationException.class, () -> c.storeShape(List.of("foo-files"))).getMessage());
        }
    }

    @Test
    void savedPreviousConfigsAddTheirShardSetsWhenOnlyTheShardsChanged() throws Exception {
        // old: shard a and a profile p over it. new: a/sub is split out of a, and p includes both: the shape p covers the same
        // files (same fingerprint) but is now made of other shards, so bundles made before the split must still be selectable
        String old = "version = 0\n[[shards]]\nname = \"a\"\npaths = [\"a\"]\n[[profiles]]\nname = \"p\"\nincludes = [\"a\"]\npublic = true\n";
        String split = "version = 0\n[[shards]]\nname = \"a\"\npaths = [\"a\"]\n[[shards]]\nname = \"a2\"\npaths = [\"a/sub\"]\n"
                + "[[profiles]]\nname = \"p\"\nincludes = [\"a\", \"a2\"]\npublic = true\n";
        NarrowServerAdmin admin = new NarrowServerAdmin(serverRepo);
        admin.shapeUpdate(old.getBytes(StandardCharsets.UTF_8), new NarrowServerAdmin.UpdateOptions(true, true, true));
        StoreShards before = StoreShards.fromRepository(serverRepo);
        Set<Fingerprint> oldSet = before.shardFingerprintsForShape(before.shape(List.of("p"))).get(0);
        admin.shapeUpdate(split.getBytes(StandardCharsets.UTF_8), NarrowServerAdmin.UpdateOptions.strict()); // a pure reshard: allowed as it is
        StoreShards now = StoreShards.fromRepository(serverRepo);
        assertEquals(before.shape(List.of("p")).storeFingerprint(), now.shape(List.of("p")).storeFingerprint(), "the shape did not change");
        Set<Fingerprint> newSet = now.shardFingerprintsForShape(now.shape(List.of("p"))).get(0);
        assertNotEquals(oldSet, newSet);
        try (HgRemoteConnection c = HgRemoteConnectionFactory.createConnection(url())) {
            ShapeWire.Reply reply = c.storeShape(List.of("p"));
            assertEquals(2, reply.shardsSets().size(), "the current set and the one of the saved previous config");
            assertEquals(newSet, reply.shardsSets().get(0));
            assertEquals(oldSet, reply.shardsSets().get(1));
        }
    }

    @Test
    void getbundleWithAShapeFingerprintNarrowsLikeTheLegacyPatterns() throws Exception {
        StoreShards shards = StoreShards.fromRepository(serverRepo);
        Fingerprint fingerprint = shards.shape(List.of("foo")).storeFingerprint();
        Wire1Response ok = Wire1Commands.getbundle(serverRepo, Map.of("common", "", "heads", "", "cg", "1",
                "store_shape_with_fingerprint", "foo:" + fingerprint.toText()));
        assertNotNull(ok);
        byte[] narrowBundle = ok.getPayload();
        byte[] fullBundle = Wire1Commands.getbundle(serverRepo, Map.of("common", "", "heads", "", "cg", "1")).getPayload();
        assertTrue(narrowBundle.length < fullBundle.length, "files outside the shape are left out: " + narrowBundle.length + " vs " + fullBundle.length);
    }

    @Test
    void getbundleRefusesAWrongOrMalformedShapeFingerprint() throws Exception {
        StoreShards shards = StoreShards.fromRepository(serverRepo);
        String bar = shards.shape(List.of("bar")).storeFingerprint().toText();
        IOException mismatch = assertThrows(IOException.class, () -> Wire1Commands.getbundle(serverRepo,
                Map.of("common", "", "heads", "", "store_shape_with_fingerprint", "foo:" + bar)));
        assertTrue(mismatch.getMessage().startsWith("fingerprint mismatch for shape 'foo'\n(hint: server: shape00-"), mismatch.getMessage());
        IOException unknown = assertThrows(IOException.class, () -> Wire1Commands.getbundle(serverRepo,
                Map.of("common", "", "heads", "", "store_shape_with_fingerprint", "nope:" + bar)));
        assertEquals("shape not found on remote: 'nope'", unknown.getMessage());
        IOException malformed = assertThrows(IOException.class, () -> Wire1Commands.getbundle(serverRepo,
                Map.of("common", "", "heads", "", "store_shape_with_fingerprint", "foo:not-a-fingerprint")));
        assertEquals("invalid store shape fingerprint: 'not-a-fingerprint'", malformed.getMessage());
    }

    @Test
    void theBlocksRoundTripAndTheClientChecksTheFingerprint() throws Exception {
        ShardTreeNode tree = ShardTreeNode.fromPatterns(Set.of("a", "b/c"), Set.of("b"));
        Fingerprint fp = tree.fingerprint();
        Fingerprint shardA = new Fingerprint(Fingerprint.Kind.SHARD, 0, new byte[32]);
        Fingerprint shardB = new Fingerprint(Fingerprint.Kind.SHARD, 0, new byte[]{1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        byte[] reply = ShapeWire.ok(fp, List.of(Set.of(shardA, shardB), Set.of(shardA)), tree.serializePatterns());
        ShapeWire.Reply decoded = ShapeWire.decodeReply(reply, List.of("x"));
        assertEquals(List.of(fp), decoded.fingerprints());
        assertEquals(2, decoded.shardsSets().size());
        assertEquals(Set.of(shardA, shardB), decoded.shardsSets().get(0));
        // a reply whose fingerprint is not the one of its patterns is refused
        byte[] wrong = ShapeWire.ok(new Fingerprint(Fingerprint.Kind.SHAPE, 0, new byte[32]), List.of(Set.of(shardA)), tree.serializePatterns());
        assertTrue(assertThrows(HgValidationException.class, () -> ShapeWire.decodeReply(wrong, List.of("x"))).getMessage()
                .startsWith("fingerprint mismatch for shape 'x'"));
        assertEquals("unknown shape error code: 9", assertThrows(HgValidationException.class, () -> ShapeWire.decodeReply(
                new byte[]{9, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, List.of("x"))).getMessage());
        assertEquals(List.of("a", "bc"), ShapeWire.unknownNamesDecode(ShapeWire.unknownNamesEncode(List.of("a", "bc"))));
    }
}
