package io.github.search5.hg4j.api;

import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.bundle.ChangegroupParser;
import io.github.search5.hg4j.diff.DeltaEngine;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.transport.CredentialsProvider;
import io.github.search5.hg4j.transport.HgRemoteConnection;
import io.github.search5.hg4j.transport.HgRemoteConnectionFactory;
import io.github.search5.hg4j.transport.TransportProtocol;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage-focused tests for {@link IncomingCommand}, driving branches that are impractical to
 * reach through a real network round trip (connection failures, malformed/degenerate changegroup
 * payloads) via a fully scripted {@link HgRemoteConnection} double registered under its own
 * "incoming-scripted://" scheme -- the same technique {@code FetchCommandTest} uses.
 */
public class IncomingCommandCoverageTest {

    private static final Map<String, ScriptedRemoteConnection> SCRIPTED_CONNECTIONS = new ConcurrentHashMap<>();

    static {
        HgRemoteConnectionFactory.register(new TransportProtocol() {
            @Override
            public boolean canHandle(String url) {
                return url != null && url.startsWith("incoming-scripted://");
            }

            @Override
            public HgRemoteConnection open(String url) {
                ScriptedRemoteConnection conn = SCRIPTED_CONNECTIONS.get(url);
                assertTrue(conn != null, "No scripted connection registered for " + url);
                return conn;
            }
        });
    }

    private static String registerScripted(ScriptedRemoteConnection conn) {
        String url = "incoming-scripted://" + UUID.randomUUID();
        SCRIPTED_CONNECTIONS.put(url, conn);
        return url;
    }

    /** Minimal, fully controllable {@link HgRemoteConnection} double. */
    private static final class ScriptedRemoteConnection implements HgRemoteConnection {
        List<String> heads = new ArrayList<>();
        RuntimeException getHeadsException;
        byte[] changegroupBytes = new byte[0];
        RuntimeException getChangegroupException;

        @Override
        public List<String> getCapabilities() {
            return List.of();
        }

        @Override
        public List<String> getHeads() {
            if (getHeadsException != null) {
                throw getHeadsException;
            }
            return heads;
        }

        @Override
        public byte[] getChangegroup(List<String> roots) {
            if (getChangegroupException != null) {
                throw getChangegroupException;
            }
            return changegroupBytes;
        }

        @Override
        public byte[] getBundle(List<String> common, List<String> hds, List<String> bundleCaps) {
            return new byte[0];
        }

        @Override
        public String push(byte[] bundleBytes, List<String> hds) {
            return "ok";
        }

        @Override
        public Map<String, String> listKeys(String namespace) {
            return Map.of();
        }

        @Override
        public boolean pushkey(String namespace, String key, String oldVal, String newVal) {
            return true;
        }

        @Override
        public String known(List<String> nodes) {
            return "";
        }

        @Override
        public void setCredentialsProvider(CredentialsProvider provider) {
            // not exercised here
        }

        @Override
        public void close() {
            // no resources held
        }
    }

    private static byte[] node(int fillByte) {
        byte[] n = new byte[20];
        java.util.Arrays.fill(n, (byte) fillByte);
        return n;
    }

    private static ChangegroupParser.ChangeGroupEntry entryWithDelta(byte[] node, byte[] delta) {
        ChangegroupParser.ChangeGroupEntry e = new ChangegroupParser.ChangeGroupEntry();
        e.node = node;
        e.p1 = new byte[20];
        e.p2 = new byte[20];
        e.cs = node;
        e.delta = delta;
        return e;
    }

    private static byte[] rawChangelogDelta(String text) {
        return DeltaEngine.createSimpleDelta(new byte[0], text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] singleEntryBundle(ChangegroupParser.ChangeGroupEntry entry) throws Exception {
        ChangegroupParser.ChangegroupBundle bundle = new ChangegroupParser.ChangegroupBundle();
        bundle.changelogEntries = new ArrayList<>(List.of(entry));
        bundle.manifestEntries = new ArrayList<>();
        bundle.fileGroups = new ArrayList<>();
        return HgTestUtils.serializeBundleToBytes(bundle);
    }

    // ------------------------------------------------------------------
    // Source URL validation
    // ------------------------------------------------------------------

    @Test
    public void callThrowsWhenSourceIsEmptyString(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        IncomingCommand cmd = new IncomingCommand(repo).setSource("");
        assertThrows(IllegalArgumentException.class, cmd::call);
    }

    // ------------------------------------------------------------------
    // First connection: heads retrieval
    // ------------------------------------------------------------------

    @Test
    public void callWrapsRemoteHeadsFailureAsIOException(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.getHeadsException = new RuntimeException("simulated wire failure");
        String url = registerScripted(conn);

        IncomingCommand cmd = new IncomingCommand(repo).setSource(url);
        Exception ex = assertThrows(java.io.IOException.class, cmd::call);
        assertTrue(ex.getMessage().contains(url), "Wrapped exception must mention the failing source URL");
        assertEquals("simulated wire failure", ex.getCause().getMessage());
    }

    @Test
    public void treatsNullRemoteHeadsListAsNoHeads(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.heads = null;
        String url = registerScripted(conn);

        List<String> result = new IncomingCommand(repo).setSource(url).call();
        assertEquals(List.of("no incoming changes found"), result);
    }

    // ------------------------------------------------------------------
    // Second connection: changegroup retrieval failure fallback
    // ------------------------------------------------------------------

    @Test
    public void fallsBackToOfflineHeadListingWhenChangegroupFetchFails(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        byte[] missingHead = node(0x42);
        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.heads = List.of(NodeIdUtil.toHex(missingHead));
        conn.getChangegroupException = new RuntimeException("changegroup boom");
        String url = registerScripted(conn);

        List<String> result = new IncomingCommand(repo).setSource(url).call();

        String expectedHex = NodeIdUtil.toHex(missingHead).substring(0, 12);
        assertTrue(result.contains("changeset:   " + expectedHex + " (incoming head - offline)"));
        assertTrue(result.contains("user:        remote_developer (fetch failed)"));
        assertTrue(result.contains("summary:     Failed to fetch remote metadata: changegroup boom"));
    }

    // ------------------------------------------------------------------
    // Changegroup retrieved but empty / null -> no entries parsed
    // ------------------------------------------------------------------

    @Test
    public void reportsNoIncomingWhenChangegroupBytesAreNull(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.heads = List.of(NodeIdUtil.toHex(node(0x43)));
        conn.changegroupBytes = null;
        String url = registerScripted(conn);

        List<String> result = new IncomingCommand(repo).setSource(url).call();
        assertEquals(List.of("no incoming changes found"), result);
    }

    @Test
    public void reportsNoIncomingWhenChangegroupBytesAreEmpty(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.heads = List.of(NodeIdUtil.toHex(node(0x44)));
        conn.changegroupBytes = new byte[0];
        String url = registerScripted(conn);

        List<String> result = new IncomingCommand(repo).setSource(url).call();
        assertEquals(List.of("no incoming changes found"), result);
    }

    // ------------------------------------------------------------------
    // Entry already known locally -> skipped -> final empty-list fallback
    // ------------------------------------------------------------------

    @Test
    public void skipsChangegroupEntryAlreadyPresentLocallyAndFallsBackToNoIncoming(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "hello");
        new AddCommand(repo).call();
        byte[] knownNode = new CommitCommand(repo).setAuthor("dev").setMessage("v1").call();

        // Remote advertises a genuinely unknown head so the command proceeds to fetch, but the
        // changegroup it returns (synthetically, for full branch control) carries only an entry
        // whose node this repository already has -- exercising the "already known" skip branch.
        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.heads = List.of(NodeIdUtil.toHex(node(0x45)));
        conn.changegroupBytes = singleEntryBundle(entryWithDelta(knownNode, rawChangelogDelta("irrelevant")));
        String url = registerScripted(conn);

        List<String> result = new IncomingCommand(repo).setSource(url).call();
        assertEquals(List.of("no incoming changes found"), result);
    }

    // ------------------------------------------------------------------
    // New entry: normal multi-line changelog text -> summary is FIRST line
    // of the description (bug fix verified against real `hg incoming`,
    // which shows only the first description line as the summary).
    // ------------------------------------------------------------------

    @Test
    public void newEntrySummaryUsesFirstLineOfMultilineDescription(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        byte[] newNode = node(0x46);
        String rawChangelogText = "0".repeat(40) + "\n"
                + "Alice <alice@example.com>\n"
                + "1700000000 0\n"
                + "file1.txt\n"
                + "\n"
                + "first summary line\n"
                + "second body line\n"
                + "third body line";

        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.heads = List.of(NodeIdUtil.toHex(newNode));
        conn.changegroupBytes = singleEntryBundle(entryWithDelta(newNode, rawChangelogDelta(rawChangelogText)));
        String url = registerScripted(conn);

        List<String> result = new IncomingCommand(repo).setSource(url).call();

        String expectedHex = NodeIdUtil.toHex(newNode).substring(0, 12);
        assertEquals(List.of(
                "changeset:   " + expectedHex,
                "user:        Alice <alice@example.com>",
                "summary:     first summary line"
        ), result);
    }

    // ------------------------------------------------------------------
    // New entry: description separator present but its first line is blank
    // -> falls back to the default placeholder summary.
    // ------------------------------------------------------------------

    @Test
    public void newEntrySummaryFallsBackToDefaultWhenDescriptionFirstLineIsBlank(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        byte[] newNode = node(0x47);
        String rawChangelogText = "0".repeat(40) + "\n"
                + "Bob <bob@example.com>\n"
                + "1700000000 0\n"
                + "file1.txt\n"
                + "\n"
                + "\nreal message on second line";

        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.heads = List.of(NodeIdUtil.toHex(newNode));
        conn.changegroupBytes = singleEntryBundle(entryWithDelta(newNode, rawChangelogDelta(rawChangelogText)));
        String url = registerScripted(conn);

        List<String> result = new IncomingCommand(repo).setSource(url).call();

        assertTrue(result.contains("user:        Bob <bob@example.com>"));
        assertTrue(result.contains("summary:     Remote changeset summary"));
    }

    // ------------------------------------------------------------------
    // New entry: single-line description with no trailing newline at all
    // (the common case for e.g. `hg commit -m "second"`) -> the whole
    // description is the summary as-is, not a substring up to a newline.
    // ------------------------------------------------------------------

    @Test
    public void newEntrySummaryHandlesSingleLineDescriptionWithNoTrailingNewline(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        byte[] newNode = node(0x4a);
        String rawChangelogText = "0".repeat(40) + "\n"
                + "Carol <carol@example.com>\n"
                + "1700000000 0\n"
                + "file1.txt\n"
                + "\n"
                + "second";

        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.heads = List.of(NodeIdUtil.toHex(newNode));
        conn.changegroupBytes = singleEntryBundle(entryWithDelta(newNode, rawChangelogDelta(rawChangelogText)));
        String url = registerScripted(conn);

        List<String> result = new IncomingCommand(repo).setSource(url).call();

        String expectedHex = NodeIdUtil.toHex(newNode).substring(0, 12);
        assertEquals(List.of(
                "changeset:   " + expectedHex,
                "user:        Carol <carol@example.com>",
                "summary:     second"
        ), result);
    }

    // ------------------------------------------------------------------
    // New entry: zero-length reconstructed content -> "binary" placeholder
    // ------------------------------------------------------------------

    @Test
    public void newEntryWithEmptyReconstructedContentUsesBinaryPlaceholder(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        byte[] newNode = node(0x48);
        // start=0, end=0, length=0: applying this to the empty first-entry base yields an
        // empty reconstructed changelog text.
        byte[] emptyHunkDelta = new byte[12];

        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.heads = List.of(NodeIdUtil.toHex(newNode));
        conn.changegroupBytes = singleEntryBundle(entryWithDelta(newNode, emptyHunkDelta));
        String url = registerScripted(conn);

        List<String> result = new IncomingCommand(repo).setSource(url).call();

        String expectedHex = NodeIdUtil.toHex(newNode).substring(0, 12);
        assertEquals(List.of(
                "changeset:   " + expectedHex,
                "user:        remote_developer",
                "summary:     [Binary delta metadata]"
        ), result);
    }

    // ------------------------------------------------------------------
    // New entry: delta too short to be a valid hunk header -> applyDelta
    // throws -> falls back to using the raw delta bytes as the changelog
    // text, whose short "header" section then also defaults the author.
    // ------------------------------------------------------------------

    @Test
    public void newEntryFallsBackToRawDeltaBytesWhenApplyDeltaFails(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        byte[] newNode = node(0x49);
        byte[] tooShortDelta = "short".getBytes(StandardCharsets.UTF_8); // < 12 bytes: header truncated

        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.heads = List.of(NodeIdUtil.toHex(newNode));
        conn.changegroupBytes = singleEntryBundle(entryWithDelta(newNode, tooShortDelta));
        String url = registerScripted(conn);

        List<String> result = new IncomingCommand(repo).setSource(url).call();

        String expectedHex = NodeIdUtil.toHex(newNode).substring(0, 12);
        assertEquals(List.of(
                "changeset:   " + expectedHex,
                "user:        remote_developer",
                "summary:     Remote changeset summary"
        ), result);
    }

    // ------------------------------------------------------------------
    // Sanity: exercising the double must not throw for a normal round trip
    // ------------------------------------------------------------------

    @Test
    public void scriptedConnectionRoundTripDoesNotThrow(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.heads = List.of();
        String url = registerScripted(conn);

        assertDoesNotThrow(() -> new IncomingCommand(repo).setSource(url).call());
    }
}
