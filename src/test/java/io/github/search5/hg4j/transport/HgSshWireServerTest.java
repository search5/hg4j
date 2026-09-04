package io.github.search5.hg4j.transport;

import io.github.search5.hg4j.api.AddCommand;
import io.github.search5.hg4j.api.CommitCommand;
import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
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
import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.bundle.ChangegroupParser;

/**
 * Verifies {@link HgSshWireServer} against hand-crafted byte sequences shaped exactly like real
 * hg's own SSH v1 client would send them ({@code mercurial/wireprotoserver.py}'s {@code
 * sshv1protocolhandler.getargs}/{@code _sshv1respondbytes}, Mercurial 6.0). {@link HgSshClient}
 * itself used to speak an entirely different, never-verified wire format (a blank-line arg
 * terminator and unframed responses that don't exist in the real protocol); as of 2026-09-03 it's
 * been rewritten to match this same real spec and is round-tripped against this server directly
 * in {@link HgSshClientTest}/{@code HgSshClientTransportTest}. Real-hg-as-client end-to-end
 * interop lives in {@link HgSshWireServerRealHgInteropTest}.
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

    /** Splits a byte stream of concatenated {@code "<len>\n<bytes>"} framed responses (real hg's
     * {@code unbundle} reply shape is up to three of these back to back: a pre-payload "OK to
     * continue" check, an error-or-empty check, and -- only on success -- the actual result) into
     * the individual payload strings. */
    private static List<String> splitFramedResponses(byte[] response) {
        List<String> parts = new ArrayList<>();
        int pos = 0;
        while (pos < response.length) {
            int nl = -1;
            for (int i = pos; i < response.length; i++) {
                if (response[i] == '\n') {
                    nl = i;
                    break;
                }
            }
            if (nl == -1) break;
            int len = Integer.parseInt(new String(response, pos, nl - pos, StandardCharsets.US_ASCII));
            int start = nl + 1;
            parts.add(new String(response, start, len, StandardCharsets.UTF_8));
            pos = start + len;
        }
        return parts;
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
        // Backlog item 38 (push-race re-validation): destRepo is headless, so the wire `heads`
        // argument representing "what the client believed the remote's heads were" must be
        // empty here, not the incoming commit's own hex -- see Wire1CommandsTest's matching
        // comment for the full explanation of why this used to be harmless filler.
        reqBytes.write("heads 0\n".getBytes(StandardCharsets.US_ASCII));
        // getpayload() chunk framing: <chunk-size>\n<chunk bytes> ... terminated by "0\n".
        reqBytes.write((bundleBytes.length + "\n").getBytes(StandardCharsets.US_ASCII));
        reqBytes.write(bundleBytes);
        reqBytes.write("0\n".getBytes(StandardCharsets.US_ASCII));

        byte[] response = run(destRepo, reqBytes.toByteArray());
        // Real hg's unbundle reply shape (mercurial/sshpeer.py's _callpush(), confirmed against
        // Mercurial 7.2.4 source 2026-09-03): a pre-payload "OK to continue" empty frame (already
        // consumed by the time this request was fully written above -- it's simply the FIRST of
        // the three responses concatenated here), then an error-or-empty check, then -- since this
        // push succeeds -- the actual integer result.
        List<String> parts = splitFramedResponses(response);
        assertEquals(3, parts.size(), "precheck + error-or-empty + result: " + parts);
        assertEquals("", parts.get(0), "pre-payload OK-to-continue frame must be empty");
        assertEquals("", parts.get(1), "no error");
        assertEquals("1", parts.get(2), "unbundle must report new revisions added");

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
        // Backlog item 38: see unbundleReadsTheChunkedPayloadAndAppliesIt's matching comment --
        // destRepo is headless, so the wire `heads` value must be empty here.
        reqBytes.write("heads 0\n".getBytes(StandardCharsets.US_ASCII));
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
        // Real hg's unbundle reply shape: pre-payload "OK to continue" empty frame, then -- since
        // this push is rejected -- a single non-empty error frame (the client, real or hg4j's own,
        // treats ANY non-empty frame here as the error message and never even attempts the
        // would-be-third "result" read; see HgSshClient#push).
        List<String> parts = splitFramedResponses(out.toByteArray());
        assertEquals(2, parts.size(), "precheck + error (no separate result frame on rejection): " + parts);
        assertEquals("", parts.get(0), "pre-payload OK-to-continue frame must be empty");
        assertFalse(parts.get(1).isEmpty(), "a rejected push must report a non-empty error");

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
