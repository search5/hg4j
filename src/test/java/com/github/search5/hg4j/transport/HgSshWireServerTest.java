package com.github.search5.hg4j.transport;

import com.github.search5.hg4j.api.AddCommand;
import com.github.search5.hg4j.api.CommitCommand;
import com.github.search5.hg4j.api.Hg;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import com.github.search5.hg4j.HgTestUtils;
import com.github.search5.hg4j.bundle.ChangegroupParser;

/**
 * Verifies {@link HgSshWireServer} against hand-crafted byte sequences shaped exactly like real
 * hg's own SSH v1 client would send them ({@code mercurial/wireprotoserver.py}'s {@code
 * sshv1protocolhandler.getargs}/{@code _sshv1respondbytes}, Mercurial 6.0) — not hg4j's own {@code
 * HgSshClient}, which turned out to use an entirely different, never-verified wire format (a
 * blank-line arg terminator and unframed responses that don't exist in the real protocol) and so
 * can't serve as a real-spec round-trip partner. Real-hg-as-SSH-client end-to-end interop (which
 * would require either a genuine SSH session or a documented bypass of one) was not set up in
 * this phase; this is a known follow-up, tracked in the JGit-restructuring plan.
 */
public class HgSshWireServerTest {

    private static HgRepository repoWithOneCommit(Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        File f = new File(tempDir.toFile(), "a.txt");
        Files.writeString(f.toPath(), "hello ssh wire");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("v1").setAuthor("dev").call();
        return repo;
    }

    private static byte[] run(HgRepository repo, byte[] request) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new HgSshWireServer(repo).handleConnection(new ByteArrayInputStream(request), out);
        return out.toByteArray();
    }

    /** A no-arg command is just its bare name line -- no argument lines follow at all. */
    @Test
    public void headsRespondsWithALengthPrefixedLine(@TempDir Path tempDir) throws Exception {
        HgRepository repo = repoWithOneCommit(tempDir);
        byte[] commit = repo.getDirstate().getParent1();
        String hex = NodeIdUtil.toHex(commit);

        byte[] request = "heads\n".getBytes(StandardCharsets.US_ASCII);
        byte[] response = run(repo, request);

        String expectedPayload = hex + "\n";
        String expected = expectedPayload.length() + "\n" + expectedPayload;
        assertEquals(expected, new String(response, StandardCharsets.US_ASCII));
    }

    /**
     * {@code known nodes *} -- one arg line for "nodes" (a fixed key), then a "*" line with count
     * 0 (no extra star args in this request), matching real hg's {@code getargs} exactly.
     */
    @Test
    public void knownReadsTheDeclaredArgAndTheEmptyStarBucket(@TempDir Path tempDir) throws Exception {
        HgRepository repo = repoWithOneCommit(tempDir);
        byte[] commit = repo.getDirstate().getParent1();
        String hex = NodeIdUtil.toHex(commit);
        String nodesArg = hex + " " + "f".repeat(40);

        StringBuilder req = new StringBuilder();
        req.append("known\n");
        req.append("nodes ").append(nodesArg.length()).append("\n").append(nodesArg);
        req.append("* 0\n");
        byte[] response = run(repo, req.toString().getBytes(StandardCharsets.US_ASCII));

        assertEquals("2\n10", new String(response, StandardCharsets.US_ASCII));
    }

    /** {@code lookup key} -- single fixed arg, no star bucket at all (spec is just "key"). */
    @Test
    public void lookupReadsItsSingleArgumentAndRespondsWithTheResolvedNode(@TempDir Path tempDir) throws Exception {
        HgRepository repo = repoWithOneCommit(tempDir);
        byte[] commit = repo.getDirstate().getParent1();
        String hex = NodeIdUtil.toHex(commit);

        String req = "lookup\nkey 1\n0";
        byte[] response = run(repo, req.getBytes(StandardCharsets.US_ASCII));

        String expectedPayload = "1 " + hex + "\n";
        String expected = expectedPayload.length() + "\n" + expectedPayload;
        assertEquals(expected, new String(response, StandardCharsets.US_ASCII));
    }

    /**
     * {@code getbundle *} -- an all-star command: the very first (and only) declared key is "*",
     * so every argument is read as a star-bucket entry. Response is a raw, unframed stream (no
     * length prefix at all), unlike bytesresponse commands.
     */
    @Test
    public void getbundleStreamsARawUnframedBundle(@TempDir Path tempDir) throws Exception {
        HgRepository repo = repoWithOneCommit(tempDir);
        byte[] commit = repo.getDirstate().getParent1();
        String hex = NodeIdUtil.toHex(commit);

        String common = "";
        String headsArg = hex;
        StringBuilder req = new StringBuilder();
        req.append("getbundle\n");
        req.append("* 2\n");
        req.append("common ").append(common.length()).append("\n").append(common);
        req.append("heads ").append(headsArg.length()).append("\n").append(headsArg);
        byte[] response = run(repo, req.toString().getBytes(StandardCharsets.US_ASCII));

        assertTrue(response.length > 0);
        // No length-prefix digits at the start -- real bundle/changegroup bytes are binary and
        // start with a 4-byte big-endian chunk length for the first changelog entry, not ASCII.
        assertFalse(Character.isDigit((char) response[0]) && response[1] == '\n',
                "streamres must not be length-prefixed like a bytesresponse");
    }

    @Test
    public void unbundleReadsTheChunkedPayloadAndAppliesIt(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        File f = new File(srcDir, "a.txt");
        Files.writeString(f.toPath(), "hello");
        new AddCommand(srcRepo).call();
        byte[] commit = new CommitCommand(srcRepo).setMessage("v1").setAuthor("dev").call();

        ChangegroupParser.ChangegroupBundle bundle =
                HgTestUtils.createMockBundleFromRepo(srcRepo);
        byte[] cgBytes = HgTestUtils.serializeBundleToBytes(bundle);
        byte[] bundleBytes = new byte[6 + cgBytes.length];
        System.arraycopy("HG10UN".getBytes(StandardCharsets.US_ASCII), 0, bundleBytes, 0, 6);
        System.arraycopy(cgBytes, 0, bundleBytes, 6, cgBytes.length);

        File destDir = tempDir.resolve("dest").toFile();
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        String hex = NodeIdUtil.toHex(commit);

        ByteArrayOutputStream reqBytes = new ByteArrayOutputStream();
        reqBytes.write("unbundle\n".getBytes(StandardCharsets.US_ASCII));
        reqBytes.write(("heads " + hex.length() + "\n" + hex).getBytes(StandardCharsets.US_ASCII));
        // getpayload() chunk framing: <chunk-size>\n<chunk bytes> ... terminated by "0\n".
        reqBytes.write((bundleBytes.length + "\n").getBytes(StandardCharsets.US_ASCII));
        reqBytes.write(bundleBytes);
        reqBytes.write("0\n".getBytes(StandardCharsets.US_ASCII));

        byte[] response = run(destRepo, reqBytes.toByteArray());
        String resp = new String(response, StandardCharsets.US_ASCII);
        int nl = resp.indexOf('\n');
        String payload = resp.substring(nl + 1);
        assertTrue(payload.startsWith("1\n"), "unbundle must report new revisions added: " + payload);

        File clIdx = new File(destRepo.getStoreDir(), "00changelog.i");
        File clDat = new File(destRepo.getStoreDir(), "00changelog.d");
        var cl = destRepo.getRevlog(clIdx, clDat);
        assertEquals(1, cl.getRevisionCount());
    }

    @Test
    public void unbundleFiresThePostChangegroupHookOverSsh(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "hello");
        new AddCommand(srcRepo).call();
        byte[] commit = new CommitCommand(srcRepo).setMessage("v1").setAuthor("dev").call();

        ChangegroupParser.ChangegroupBundle bundle =
                HgTestUtils.createMockBundleFromRepo(srcRepo);
        byte[] cgBytes = HgTestUtils.serializeBundleToBytes(bundle);
        byte[] bundleBytes = new byte[6 + cgBytes.length];
        System.arraycopy("HG10UN".getBytes(StandardCharsets.US_ASCII), 0, bundleBytes, 0, 6);
        System.arraycopy(cgBytes, 0, bundleBytes, 6, cgBytes.length);

        File destDir = tempDir.resolve("dest").toFile();
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        String hex = NodeIdUtil.toHex(commit);

        ByteArrayOutputStream reqBytes = new ByteArrayOutputStream();
        reqBytes.write("unbundle\n".getBytes(StandardCharsets.US_ASCII));
        reqBytes.write(("heads " + hex.length() + "\n" + hex).getBytes(StandardCharsets.US_ASCII));
        reqBytes.write((bundleBytes.length + "\n").getBytes(StandardCharsets.US_ASCII));
        reqBytes.write(bundleBytes);
        reqBytes.write("0\n".getBytes(StandardCharsets.US_ASCII));

        List<Map<String, Object>> observedContexts = new ArrayList<>();
        HgSshWireServer server = new HgSshWireServer(destRepo);
        server.registerPostChangegroupHook(ctx -> {
            observedContexts.add(ctx);
            return true;
        });
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        server.handleConnection(new ByteArrayInputStream(reqBytes.toByteArray()), out);

        assertEquals(1, observedContexts.size(), "The post-changegroup hook must fire exactly once for a successful push over SSH");
        @SuppressWarnings("unchecked")
        List<String> nodes = (List<String>) observedContexts.get(0).get("nodes");
        assertEquals(List.of(hex), nodes);
    }

    /** A pre-changegroup hook that rejects the push must stop it before anything is written to
     * the destination repository, exactly like real hg's {@code pretxnchangegroup} -- and the
     * rejection must surface to the SSH client through the normal {@code "0\n<message>"} error
     * shape rather than tearing down the connection. */
    @Test
    public void preChangegroupHookRejectionAbortsThePushOverSsh(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        Files.writeString(new File(srcDir, "a.txt").toPath(), "hello");
        new AddCommand(srcRepo).call();
        byte[] commit = new CommitCommand(srcRepo).setMessage("v1").setAuthor("dev").call();

        ChangegroupParser.ChangegroupBundle bundle =
                HgTestUtils.createMockBundleFromRepo(srcRepo);
        byte[] cgBytes = HgTestUtils.serializeBundleToBytes(bundle);
        byte[] bundleBytes = new byte[6 + cgBytes.length];
        System.arraycopy("HG10UN".getBytes(StandardCharsets.US_ASCII), 0, bundleBytes, 0, 6);
        System.arraycopy(cgBytes, 0, bundleBytes, 6, cgBytes.length);

        File destDir = tempDir.resolve("dest").toFile();
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        String hex = NodeIdUtil.toHex(commit);

        ByteArrayOutputStream reqBytes = new ByteArrayOutputStream();
        reqBytes.write("unbundle\n".getBytes(StandardCharsets.US_ASCII));
        reqBytes.write(("heads " + hex.length() + "\n" + hex).getBytes(StandardCharsets.US_ASCII));
        reqBytes.write((bundleBytes.length + "\n").getBytes(StandardCharsets.US_ASCII));
        reqBytes.write(bundleBytes);
        reqBytes.write("0\n".getBytes(StandardCharsets.US_ASCII));

        List<Map<String, Object>> observedContexts = new ArrayList<>();
        HgSshWireServer server = new HgSshWireServer(destRepo);
        server.registerPreChangegroupHook(ctx -> {
            observedContexts.add(ctx);
            return false;
        });
        server.registerPostChangegroupHook(ctx -> {
            throw new AssertionError("post-changegroup hook must not fire when the pre-changegroup hook rejects the push");
        });
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        server.handleConnection(new ByteArrayInputStream(reqBytes.toByteArray()), out);

        assertEquals(1, observedContexts.size(), "The pre-changegroup hook must fire exactly once even though it rejects the push");
        String resp = new String(out.toByteArray(), StandardCharsets.US_ASCII);
        int nl = resp.indexOf('\n');
        String payload = resp.substring(nl + 1);
        assertTrue(payload.startsWith("0\n"), "a rejected push must report zero new revisions: " + payload);

        File clIdx = new File(destRepo.getStoreDir(), "00changelog.i");
        File clDat = new File(destRepo.getStoreDir(), "00changelog.d");
        var cl = destRepo.getRevlog(clIdx, clDat);
        assertEquals(0, cl.getRevisionCount(), "a rejected push must not write any revisions to the destination repository");
    }

    /** An unrecognized command name has no declared {@code ARG_SPECS} entry, so real hg's SSH
     * peer reports it as an out-of-band error rather than trying to read argument lines for it. */
    @Test
    public void unsupportedCommandRespondsWithAnOobError(@TempDir Path tempDir) throws Exception {
        HgRepository repo = repoWithOneCommit(tempDir);

        byte[] response = run(repo, "frobnicate\n".getBytes(StandardCharsets.US_ASCII));

        String expectedPayload = "unsupported command: frobnicate";
        String expected = expectedPayload.length() + "\n" + expectedPayload;
        assertEquals(expected, new String(response, StandardCharsets.US_ASCII));
    }

    /** If the connection is severed right after the command line -- before the declared argument
     * line arrives -- {@code readArgs} fails parsing a {@code null} line. {@code handleConnection}
     * must swallow that rather than propagate it to the caller, matching a genuine dropped SSH
     * channel: it reports an empty response and simply closes rather than crashing the server. */
    @Test
    public void connectionDroppedBeforeArgumentLineIsHandledGracefully(@TempDir Path tempDir) throws Exception {
        HgRepository repo = repoWithOneCommit(tempDir);

        byte[] response = run(repo, "lookup\n".getBytes(StandardCharsets.US_ASCII));

        assertEquals("0\n", new String(response, StandardCharsets.US_ASCII));
    }

    /** A malformed argument line with no {@code "<name> <len>"} space separator hits the same
     * defensive path as a dropped connection: {@code indexOf(' ')} returns {@code -1} and the
     * resulting {@code substring} throws, which {@code handleConnection} must still contain. */
    @Test
    public void malformedArgumentLineWithNoLengthIsHandledGracefully(@TempDir Path tempDir) throws Exception {
        HgRepository repo = repoWithOneCommit(tempDir);

        byte[] response = run(repo, "lookup\nkey\n".getBytes(StandardCharsets.US_ASCII));

        assertEquals("0\n", new String(response, StandardCharsets.US_ASCII));
    }

    /** The argument line declares more bytes than the client actually sends before closing the
     * connection -- {@code readExactly} must detect the short read and fail loudly with an {@link
     * java.io.IOException} rather than silently returning a truncated value, and {@code
     * handleConnection} must still convert that into a clean empty response instead of crashing. */
    @Test
    public void truncatedArgumentPayloadIsHandledGracefully(@TempDir Path tempDir) throws Exception {
        HgRepository repo = repoWithOneCommit(tempDir);

        byte[] response = run(repo, "lookup\nkey 5\nab".getBytes(StandardCharsets.US_ASCII));

        assertEquals("0\n", new String(response, StandardCharsets.US_ASCII));
    }

    /** Real TCP/SSH connections can be closed by the peer right after the last byte of a command
     * line, with no trailing {@code '\n'} ever sent. {@code readLine} must still return the bytes
     * it did see (rather than discarding them as an incomplete line) so a final, newline-less
     * command still dispatches correctly. */
    @Test
    public void commandLineWithoutATrailingNewlineAtEofIsStillDispatched(@TempDir Path tempDir) throws Exception {
        HgRepository repo = repoWithOneCommit(tempDir);
        byte[] commit = repo.getDirstate().getParent1();
        String hex = NodeIdUtil.toHex(commit);

        byte[] response = run(repo, "heads".getBytes(StandardCharsets.US_ASCII));

        String expectedPayload = hex + "\n";
        String expected = expectedPayload.length() + "\n" + expectedPayload;
        assertEquals(expected, new String(response, StandardCharsets.US_ASCII));
    }
}
