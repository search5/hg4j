package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import com.github.search5.hg4j.transport.CredentialsProvider;
import com.github.search5.hg4j.transport.HgRemoteConnection;
import com.github.search5.hg4j.transport.HgRemoteConnectionFactory;
import com.github.search5.hg4j.transport.TransportProtocol;
import com.github.search5.hg4j.util.NodeIdUtil;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage-focused tests for {@link OutgoingCommand}, driving branches that are impractical to
 * reach through a real network round trip (connection failures, precise control over the
 * advertised remote heads) via a fully scripted {@link HgRemoteConnection} double registered
 * under its own "outgoing-scripted://" scheme -- the same technique
 * {@code IncomingCommandCoverageTest} uses for the twin command.
 */
public class OutgoingCommandCoverageTest {

    private static final Map<String, ScriptedRemoteConnection> SCRIPTED_CONNECTIONS = new ConcurrentHashMap<>();

    static {
        HgRemoteConnectionFactory.register(new TransportProtocol() {
            @Override
            public boolean canHandle(String url) {
                return url != null && url.startsWith("outgoing-scripted://");
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
        String url = "outgoing-scripted://" + UUID.randomUUID();
        SCRIPTED_CONNECTIONS.put(url, conn);
        return url;
    }

    /** Minimal, fully controllable {@link HgRemoteConnection} double. */
    private static final class ScriptedRemoteConnection implements HgRemoteConnection {
        List<String> heads = new ArrayList<>();
        RuntimeException getHeadsException;

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
            return new byte[0];
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

    // ------------------------------------------------------------------
    // Destination URL validation
    // ------------------------------------------------------------------

    @Test
    public void callThrowsWhenDestinationIsEmptyString(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        OutgoingCommand cmd = new OutgoingCommand(repo).setDestination("");
        assertThrows(IllegalArgumentException.class, cmd::call);
    }

    // ------------------------------------------------------------------
    // Remote heads retrieval
    // ------------------------------------------------------------------

    @Test
    public void callWrapsRemoteHeadsFailureAsIOException(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();
        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.getHeadsException = new RuntimeException("simulated wire failure");
        String url = registerScripted(conn);

        OutgoingCommand cmd = new OutgoingCommand(repo).setDestination(url);
        Exception ex = assertThrows(java.io.IOException.class, cmd::call);
        assertTrue(ex.getMessage().contains(url), "Wrapped exception must mention the failing destination URL");
        assertEquals("simulated wire failure", ex.getCause().getMessage());
    }

    @Test
    public void treatsNullRemoteHeadsListAsNoHeadsAndReportsLocalRevisionsOutgoing(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "hello");
        new AddCommand(repo).call();
        byte[] node0 = new CommitCommand(repo).setAuthor("Dev <dev@example.com>").setMessage("v1").call();

        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.heads = null;
        String url = registerScripted(conn);

        List<String> result = new OutgoingCommand(repo).setDestination(url).call();

        String expectedHex = NodeIdUtil.toHex(node0).substring(0, 12);
        assertEquals(List.of(
                "changeset:   0:" + expectedHex,
                "user:        Dev <dev@example.com>",
                "summary:     v1"
        ), result);
    }

    @Test
    public void reportsNoOutgoingWhenThereAreNoLocalRevisionsAtAll(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();

        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.heads = List.of(NodeIdUtil.toHex(node(0x11)));
        String url = registerScripted(conn);

        List<String> result = new OutgoingCommand(repo).setDestination(url).call();
        assertEquals(List.of("no outgoing changes found"), result);
    }

    // ------------------------------------------------------------------
    // Linear chain: ancestor detection, topological shortcut, unknown
    // heads, and the multi-head loop (mismatch-then-match / break).
    // ------------------------------------------------------------------

    private static final class Chain {
        HgRepository repo;
        byte[] node0;
        byte[] node1;
        byte[] node2;
    }

    private static Chain buildLinearChain(File repoDir) throws Exception {
        Chain c = new Chain();
        c.repo = Hg.init().setDirectory(repoDir).call();
        File f1 = new File(repoDir, "f1.txt");
        Files.writeString(f1.toPath(), "v0");
        new AddCommand(c.repo).call();
        c.node0 = new CommitCommand(c.repo).setAuthor("A <a@example.com>").setMessage("c0").call();

        Files.writeString(f1.toPath(), "v1");
        c.node1 = new CommitCommand(c.repo).setAuthor("B <b@example.com>").setMessage("c1").call();

        Files.writeString(f1.toPath(), "v2");
        // Multi-line commit message doubles as the regression check for the summary bug: real
        // `hg outgoing` prints only the FIRST line of the description as "summary", not the last
        // raw line of the whole changelog blob (verified against real hg 7.2 on a scratch repo).
        c.node2 = new CommitCommand(c.repo).setAuthor("C <c@example.com>").setMessage("multi\nline\nsummary message").call();
        return c;
    }

    @Test
    public void reportsNoOutgoingWhenRemoteHeadIsLocalTip(@TempDir Path tempDir) throws Exception {
        Chain c = buildLinearChain(tempDir.resolve("repo").toFile());

        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.heads = List.of(NodeIdUtil.toHex(c.node2));
        String url = registerScripted(conn);

        List<String> result = new OutgoingCommand(c.repo).setDestination(url).call();
        assertEquals(List.of("no outgoing changes found"), result);
    }

    @Test
    public void reportsCommitsAheadOfOlderRemoteHeadAndFixesMultilineSummaryBug(@TempDir Path tempDir) throws Exception {
        Chain c = buildLinearChain(tempDir.resolve("repo").toFile());

        // Remote only knows about the root commit -- both later commits (topologically "after"
        // the remote head, i.e. ancestorRev > descendantRev) must be reported as outgoing.
        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.heads = List.of(NodeIdUtil.toHex(c.node0));
        String url = registerScripted(conn);

        List<String> result = new OutgoingCommand(c.repo).setDestination(url).call();

        String hex1 = NodeIdUtil.toHex(c.node1).substring(0, 12);
        String hex2 = NodeIdUtil.toHex(c.node2).substring(0, 12);
        assertEquals(List.of(
                "changeset:   1:" + hex1,
                "user:        B <b@example.com>",
                "summary:     c1",
                "changeset:   2:" + hex2,
                "user:        C <c@example.com>",
                // Regression: must be the FIRST description line, not the LAST raw changelog line.
                "summary:     multi"
        ), result);
    }

    @Test
    public void reportsAllLocalRevisionsOutgoingWhenRemoteHeadIsUnknownAndUnrelated(@TempDir Path tempDir) throws Exception {
        Chain c = buildLinearChain(tempDir.resolve("repo").toFile());

        // A remote head that this repository's changelog has never seen and that does not equal
        // any local node -- exercises the (rHeadRev == -1) / Arrays.equals(...)==false branch.
        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.heads = List.of(NodeIdUtil.toHex(node(0x99)));
        String url = registerScripted(conn);

        List<String> result = new OutgoingCommand(c.repo).setDestination(url).call();
        long changesetLines = result.stream().filter(l -> l.startsWith("changeset:")).count();
        assertEquals(3, changesetLines, "All three local revisions should be reported outgoing, got: " + result);
    }

    @Test
    public void reportsNoOutgoingWhenSecondOfMultipleRemoteHeadsMatchesViaAncestor(@TempDir Path tempDir) throws Exception {
        Chain c = buildLinearChain(tempDir.resolve("repo").toFile());

        // First head is unrelated/unknown (mismatch, loop continues); second head is the local
        // tip and covers every revision by ancestry -- exercises the multi-head loop together
        // with its "continue to next head" and "break on match" branches.
        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.heads = List.of(NodeIdUtil.toHex(node(0x77)), NodeIdUtil.toHex(c.node2));
        String url = registerScripted(conn);

        List<String> result = new OutgoingCommand(c.repo).setDestination(url).call();
        assertEquals(List.of("no outgoing changes found"), result);
    }

    // ------------------------------------------------------------------
    // Merge topology: deep BFS ancestor traversal through both parents,
    // duplicate-visit de-duplication, and the "curr < ancestorRev" skip.
    // ------------------------------------------------------------------

    private static final class MergeFixture {
        HgRepository repo;
        byte[] baseNode;
        byte[] yoursNode;
        byte[] theirsNode;
        byte[] mergeNode;
    }

    private static MergeFixture buildMergeFixture(File repoDir) throws Exception {
        MergeFixture m = new MergeFixture();
        m.repo = Hg.init().setDirectory(repoDir).call();

        File f1 = new File(repoDir, "hello.txt");
        Files.writeString(f1.toPath(), "Line 1\nLine 2\nLine 3\n");
        new AddCommand(m.repo).call();
        m.baseNode = new CommitCommand(m.repo).setAuthor("A <a@example.com>").setMessage("base").call(); // rev0

        Files.writeString(f1.toPath(), "Line 1 [MINE]\nLine 2\nLine 3\n");
        m.yoursNode = new CommitCommand(m.repo).setAuthor("A <a@example.com>").setMessage("yours").call(); // rev1

        Dirstate dirstate = m.repo.getDirstate();
        dirstate.setParents(m.baseNode, new byte[20]);
        m.repo.writeDirstate(dirstate);

        Files.writeString(f1.toPath(), "Line 1\nLine 2\nLine 3 [THEIRS]\n");
        m.theirsNode = new CommitCommand(m.repo).setAuthor("A <a@example.com>").setMessage("theirs").call(); // rev2

        new MergeCommand(m.repo).setNodeId(m.yoursNode).call();
        m.mergeNode = new CommitCommand(m.repo).setAuthor("A <a@example.com>").setMessage("merge").call(); // rev3 (p1=2, p2=1)
        return m;
    }

    @Test
    public void ancestorBfsTraversesBothMergeParentsAndDedupesRevisitedAncestor(@TempDir Path tempDir) throws Exception {
        MergeFixture m = buildMergeFixture(tempDir.resolve("repo").toFile());

        // Remote head is the merge commit (rev3, parents 2 and 1): every earlier revision is an
        // ancestor of it, reachable only by walking BOTH parent edges of the merge and then both
        // parent edges of "theirs" -- which revisits the base commit a second time via "yours",
        // exercising the visited-set de-duplication (`visited.add(p1)` returning false).
        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.heads = List.of(NodeIdUtil.toHex(m.mergeNode));
        String url = registerScripted(conn);

        List<String> result = new OutgoingCommand(m.repo).setDestination(url).call();
        assertEquals(List.of("no outgoing changes found"), result);
    }

    @Test
    public void siblingBranchIsNotReportedKnownAndHitsBelowAncestorSkipBranch(@TempDir Path tempDir) throws Exception {
        MergeFixture m = buildMergeFixture(tempDir.resolve("repo").toFile());

        // Remote only knows "theirs" (rev2). "yours" (rev1) is a sibling branch off the same base,
        // not an ancestor of "theirs" -- the BFS from rev2 walks down to the base commit (rev0,
        // which has a smaller revision number than the ancestorRev=1 target) and must skip
        // exploring further from it via the "curr < ancestorRev -> continue" branch, correctly
        // concluding non-ancestry. The base and "theirs" itself remain known; "yours" and the
        // later merge commit must be reported outgoing.
        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.heads = List.of(NodeIdUtil.toHex(m.theirsNode));
        String url = registerScripted(conn);

        List<String> result = new OutgoingCommand(m.repo).setDestination(url).call();

        String yoursHex = NodeIdUtil.toHex(m.yoursNode).substring(0, 12);
        String mergeHex = NodeIdUtil.toHex(m.mergeNode).substring(0, 12);
        assertEquals(List.of(
                "changeset:   1:" + yoursHex,
                "user:        A <a@example.com>",
                "summary:     yours",
                "changeset:   3:" + mergeHex,
                "user:        A <a@example.com>",
                "summary:     merge"
        ), result);
    }

    // ------------------------------------------------------------------
    // Header/description parsing edge cases, exercised via a raw changelog
    // revision appended directly to the local changelog (bypassing
    // CommitCommand, which always emits a well-formed blob).
    // ------------------------------------------------------------------

    @Test
    public void fallsBackToDefaultsWhenChangelogTextHasNoHeaderDescriptionSeparator(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog changelog = repo.getRevlog(clIdx, clDat);
        byte[] rawContent = "just a single line with no blank-line separator at all"
                .getBytes(StandardCharsets.UTF_8);
        changelog.appendRevision(rawContent, -1, -1, new byte[20], new byte[20], 0);

        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.heads = List.of();
        String url = registerScripted(conn);

        List<String> result = new OutgoingCommand(repo).setDestination(url).call();

        assertTrue(result.contains("user:        unknown"), "Expected default author, got: " + result);
        assertTrue(result.contains("summary:     commit msg"), "Expected default summary, got: " + result);
    }

    // ------------------------------------------------------------------
    // Disconnected history: a second, independent root revision (parent1
    // == parent2 == -1) that is NOT an ancestor of the first root's line
    // of descent. Exercises isAncestor's BFS actually fetching an index
    // record whose own parent1/parent2 are -1 (unreachable for the a
    // priori single repository root, since the early curr==ancestorRev /
    // curr<ancestorRev returns always short-circuit before that fetch --
    // only a second, higher-numbered disconnected root makes it reachable).
    // ------------------------------------------------------------------

    @Test
    public void disconnectedSecondRootIsNotAnAncestorOfFirstRootsDescendant(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog changelog = repo.getRevlog(clIdx, clDat);

        byte[] zero = new byte[20];

        byte[] node0 = changelog.appendRevision(rawChangelogText("Root A <a@example.com>", "root a"), -1, -1, zero, zero, 0);
        byte[] node1 = changelog.appendRevision(rawChangelogText("Child <a@example.com>", "child of a"), 0, -1, node0, zero, 1);
        // A second, wholly independent root: no relation to revisions 0/1.
        byte[] node2 = changelog.appendRevision(rawChangelogText("Root B <b@example.com>", "root b"), -1, -1, zero, zero, 2);

        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.heads = List.of(NodeIdUtil.toHex(node2));
        String url = registerScripted(conn);

        List<String> result = new OutgoingCommand(repo).setDestination(url).call();

        String hex0 = NodeIdUtil.toHex(node0).substring(0, 12);
        String hex1 = NodeIdUtil.toHex(node1).substring(0, 12);
        assertEquals(List.of(
                "changeset:   0:" + hex0,
                "user:        Root A <a@example.com>",
                "summary:     root a",
                "changeset:   1:" + hex1,
                "user:        Child <a@example.com>",
                "summary:     child of a"
        ), result);
    }

    // ------------------------------------------------------------------
    // Diamond merge where the shared ancestor is reachable via one
    // sibling's parent1 slot AND the other sibling's parent2 slot --
    // exercises the `p2 != -1 && visited.add(p2)` de-duplication (the
    // shared ancestor is already in the visited set by the time the
    // second sibling's parent2 edge is examined).
    // ------------------------------------------------------------------

    @Test
    public void diamondMergeDedupesSharedAncestorReachedThroughEitherParentSlot(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.resolve("repo").toFile()).call();

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog changelog = repo.getRevlog(clIdx, clDat);

        byte[] zero = new byte[20];

        byte[] node0 = changelog.appendRevision(rawChangelogText("Root <r@example.com>", "root"), -1, -1, zero, zero, 0);
        // Sibling reaching the root through the parent1 slot.
        byte[] node1 = changelog.appendRevision(rawChangelogText("Left <l@example.com>", "left"), 0, -1, node0, zero, 1);
        // Sibling reaching the SAME root through the parent2 slot instead.
        byte[] node2 = changelog.appendRevision(rawChangelogText("Right <r2@example.com>", "right"), -1, 0, zero, node0, 2);
        byte[] node3 = changelog.appendRevision(rawChangelogText("Merger <m@example.com>", "merge"), 1, 2, node1, node2, 3);

        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.heads = List.of(NodeIdUtil.toHex(node3));
        String url = registerScripted(conn);

        // The merge commit's ancestry covers every revision (root, both siblings, and itself),
        // regardless of which parent slot each edge back to the shared root used.
        List<String> result = new OutgoingCommand(repo).setDestination(url).call();
        assertEquals(List.of("no outgoing changes found"), result);
    }

    private static byte[] rawChangelogText(String userLine, String summary) {
        String text = "0000000000000000000000000000000000000000\n"
                + userLine + "\n"
                + "1700000000 0\n"
                + "f.txt\n"
                + "\n"
                + summary;
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void fallsBackToDefaultSummaryWhenDescriptionFirstLineIsBlank(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("repo").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog changelog = repo.getRevlog(clIdx, clDat);
        String rawText = "manifestline\nAuthor <author@example.com>\ndateline\nfilesline\n\n\nreal message on second line";
        changelog.appendRevision(rawText.getBytes(StandardCharsets.UTF_8), -1, -1, new byte[20], new byte[20], 0);

        ScriptedRemoteConnection conn = new ScriptedRemoteConnection();
        conn.heads = List.of();
        String url = registerScripted(conn);

        List<String> result = new OutgoingCommand(repo).setDestination(url).call();

        assertTrue(result.contains("user:        Author <author@example.com>"), "Expected parsed author, got: " + result);
        assertTrue(result.contains("summary:     commit msg"), "Expected default summary when description's first line is blank, got: " + result);
    }
}
