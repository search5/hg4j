package io.github.search5.hg4j.api;
import io.github.search5.hg4j.bundle.Bundle2Parser;
import io.github.search5.hg4j.bundle.ChangegroupParser;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.SafeFileIO;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.errors.HgAuthException;
import io.github.search5.hg4j.errors.HgProtocolException;
import io.github.search5.hg4j.transport.HgSshClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import io.github.search5.hg4j.HgTestUtils;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class HgSshTransportRoundtripTest {

    /** Real hg's v1 "framed" response format: {@code "<len>\n<bytes>"}. */
    private static void writeFramed(ByteArrayOutputStream out, byte[] payload) {
        byte[] header = (payload.length + "\n").getBytes(StandardCharsets.US_ASCII);
        out.write(header, 0, header.length);
        out.write(payload, 0, payload.length);
    }

    @Test
    public void testSshInvalidHeaderThrowsHgProtocolException() throws Exception {
        String url = "ssh://hg4juser@127.0.0.1:22/test/repo";
        try (HgSshClient client = new HgSshClient(url)) {
            // Real hg's handshake expects the very first response to be a framed length line
            // ("<len>\n..."); arbitrary text there must fail cleanly.
            Field inField = HgSshClient.class.getDeclaredField("in");
            inField.setAccessible(true);
            inField.set(client, new ByteArrayInputStream("invalid response format\n".getBytes(StandardCharsets.UTF_8)));
            Field outField = HgSshClient.class.getDeclaredField("out");
            outField.setAccessible(true);
            outField.set(client, new ByteArrayOutputStream());

            Method performHandshake = HgSshClient.class.getDeclaredMethod("performHandshake");
            performHandshake.setAccessible(true);

            InvocationTargetException ex = assertThrows(InvocationTargetException.class, () -> {
                performHandshake.invoke(client);
            });
            assertTrue(ex.getCause() instanceof HgProtocolException);
        }
    }

    @Test
    public void testSshAbruptEofDuringChunkRead() throws Exception {
        String url = "ssh://hg4juser@127.0.0.1:22/test/repo";
        try (HgSshClient client = new HgSshClient(url)) {
            Field connectedField = HgSshClient.class.getDeclaredField("connected");
            connectedField.setAccessible(true);
            connectedField.set(client, true);

            Field capabilitiesField = HgSshClient.class.getDeclaredField("capabilities");
            capabilitiesField.setAccessible(true);
            capabilitiesField.set(client, List.of("lookup", "changegroupsubsets", "branchmap", "getbundle"));

            Field inField = HgSshClient.class.getDeclaredField("in");
            inField.setAccessible(true);
            // Incomplete chunk length simulation (EOF)
            inField.set(client, new ByteArrayInputStream(new byte[]{0, 0}));

            Field outField = HgSshClient.class.getDeclaredField("out");
            outField.setAccessible(true);
            outField.set(client, new ByteArrayOutputStream());

            assertThrows(HgProtocolException.class, 
                    () -> client.getBundle(List.of(), List.of("head"), List.of("bundle2")),
                    "Abrupt stream close must throw HgProtocolException");
        }
    }

    @Test
    public void testSshPullCommitsMatchExactly(@TempDir Path tempDir) throws Exception {
        // 1. Create a source repository dynamically and commit changes
        File srcDir = tempDir.resolve("src_repo").toFile();
        new InitCommand().setDirectory(srcDir).call();
        HgRepository srcRepo = new HgRepository(srcDir);
        
        File file1 = new File(srcDir, "file1.txt");
        Files.writeString(file1.toPath(), "SSH Content 1");
        new AddCommand(srcRepo).addFile("file1.txt").call();
        byte[] c1 = new CommitCommand(srcRepo).setAuthor("Tester <tester@example.com>").setMessage("SSH Initial").call();
        
        Files.writeString(file1.toPath(), "SSH Content 1 modified");
        byte[] c2 = new CommitCommand(srcRepo).setAuthor("Tester <tester@example.com>").setMessage("SSH Second").call();
        
        // 2. Serialize source repo to mock bundle
        ChangegroupParser.ChangegroupBundle bundle = HgTestUtils.createMockBundleFromRepo(srcRepo);
        byte[] rawCg = HgTestUtils.serializeBundleToBytes(bundle);
        
        // Real hg's v1 SSH wire format (see HgSshClient#readFramedResponse/#readBinaryResponse):
        // "heads" is a simple framed response ("<len>\n<bytes>"); getbundle/changegroup responses
        // are the raw changegroup bytes with NO extra outer framing at all -- the cg1 format's
        // own changelog/manifest/file-group structure (each 0000-terminated, INCLUSIVE chunk
        // lengths -- exactly what HgTestUtils#serializeBundleToBytes already produces) is the only
        // self-delimiting signal, so it's written here completely unwrapped.
        ByteArrayOutputStream serverResponse = new ByteArrayOutputStream();
        String headsBody = NodeIdUtil.toHex(c2) + "\n";
        byte[] headsBodyBytes = headsBody.getBytes(StandardCharsets.UTF_8);
        serverResponse.write((headsBodyBytes.length + "\n").getBytes(StandardCharsets.US_ASCII));
        serverResponse.write(headsBodyBytes);

        // Wire1Commands.getbundle() strips the "HG10UN" magic server-side before sending (real
        // hg's v1 getbundle response over the wire is the bare cg1 bytes, no format marker) --
        // so the mock response here must match that, not include it.
        serverResponse.write(rawCg);

        // 3. Prepare local destination repository
        File destDir = tempDir.resolve("dest_repo").toFile();
        new InitCommand().setDirectory(destDir).call();
        HgRepository destRepo = new HgRepository(destDir);
        
        // 4. Inject reflection mock to HgSshClient
        String url = "ssh://hg4juser@127.0.0.1:22/dummy/path";
        try (HgSshClient client = new HgSshClient(url)) {
            Field connectedField = HgSshClient.class.getDeclaredField("connected");
            connectedField.setAccessible(true);
            connectedField.set(client, true);
            
            Field capabilitiesField = HgSshClient.class.getDeclaredField("capabilities");
            capabilitiesField.setAccessible(true);
            capabilitiesField.set(client, List.of("lookup", "changegroupsubsets", "branchmap", "getbundle"));
            
            Field inField = HgSshClient.class.getDeclaredField("in");
            inField.setAccessible(true);
            inField.set(client, new ByteArrayInputStream(serverResponse.toByteArray()));
            
            Field outField = HgSshClient.class.getDeclaredField("out");
            outField.setAccessible(true);
            ByteArrayOutputStream clientSentBytes = new ByteArrayOutputStream();
            outField.set(client, clientSentBytes);
            
            // 5. Test SSH Pull operation client protocol
            List<String> heads = client.getHeads();
            assertEquals(1, heads.size());
            assertEquals(NodeIdUtil.toHex(c2), heads.get(0));
            
            byte[] pulledBundle = client.getBundle(List.of(), List.of(NodeIdUtil.toHex(c2)), List.of("bundle2"));

            // 6. Apply pulled bundle to destination repository (no "HG10UN" prefix to strip --
            // see the mock response construction above).
            ChangegroupParser.ChangegroupBundle parsedBundle = ChangegroupParser.parseBundle(new ByteArrayInputStream(pulledBundle), "01");
            
            PullCommand pullCmd = new PullCommand(destRepo);
            List<byte[]> imported = pullCmd.applyBundle(parsedBundle);
            assertEquals(2, imported.size());
            
            LogCommand log = new LogCommand(destRepo);
            List<HgCommit> commits = log.call();
            assertEquals(2, commits.size());
            assertEquals("SSH Second", commits.get(0).getMessage());
            assertEquals("SSH Initial", commits.get(1).getMessage());
            assertEquals(NodeIdUtil.toHex(c2), commits.get(0).getNodeId().toHex());
        }
    }

    @Test
    public void testSshPushBundleBytesAreValid(@TempDir Path tempDir) throws Exception {
        // 1. Create a local repository and commit changes
        File localDir = tempDir.resolve("local_repo").toFile();
        new InitCommand().setDirectory(localDir).call();
        HgRepository localRepo = new HgRepository(localDir);
        
        File file1 = new File(localDir, "ssh_hello.txt");
        Files.writeString(file1.toPath(), "SSH Hello World");
        new AddCommand(localRepo).addFile("ssh_hello.txt").call();
        byte[] c1 = new CommitCommand(localRepo).setAuthor("Tester <tester@example.com>").setMessage("SSH Push Commit").call();
        
        // 2. Serialize local repo bundle bytes
        ChangegroupParser.ChangegroupBundle bundle = HgTestUtils.createMockBundleFromRepo(localRepo);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write("HG10UN".getBytes(StandardCharsets.US_ASCII));
        bos.write(HgTestUtils.serializeBundleToBytes(bundle));
        byte[] localBundleBytes = bos.toByteArray();
        
        // 3. Inject reflection mock to HgSshClient
        String url = "ssh://hg4juser@127.0.0.1:22/dummy/path";
        try (HgSshClient client = new HgSshClient(url)) {
            Field connectedField = HgSshClient.class.getDeclaredField("connected");
            connectedField.setAccessible(true);
            connectedField.set(client, true);
            
            Field capabilitiesField = HgSshClient.class.getDeclaredField("capabilities");
            capabilitiesField.setAccessible(true);
            capabilitiesField.set(client, List.of("lookup", "changegroupsubsets", "branchmap", "unbundle"));
            
            // Real hg's actual unbundle reply shape (mercurial/sshpeer.py's _callpush(), see
            // HgSshClient#push): a pre-payload "OK to continue" empty framed response, then (once
            // the payload has been sent) an error-or-empty framed response, then -- since this
            // push succeeds -- the actual integer result, itself framed.
            ByteArrayOutputStream serverResponse = new ByteArrayOutputStream();
            writeFramed(serverResponse, new byte[0]); // precheck: OK to continue
            writeFramed(serverResponse, new byte[0]); // no error
            writeFramed(serverResponse, "1".getBytes(StandardCharsets.US_ASCII)); // result

            Field inField = HgSshClient.class.getDeclaredField("in");
            inField.setAccessible(true);
            inField.set(client, new ByteArrayInputStream(serverResponse.toByteArray()));

            Field outField = HgSshClient.class.getDeclaredField("out");
            outField.setAccessible(true);
            ByteArrayOutputStream clientSentBytes = new ByteArrayOutputStream();
            outField.set(client, clientSentBytes);

            // 4. Test SSH Push operation client protocol
            String pushResult = client.push(localBundleBytes, List.of(NodeIdUtil.toHex(c1)));

            // 5. Verify pushed commands and data
            assertEquals("1", pushResult.trim());

            byte[] sentData = clientSentBytes.toByteArray();
            assertTrue(sentData.length > 0);
            
            String sentString = new String(sentData, StandardCharsets.UTF_8);
            assertTrue(sentString.contains("unbundle\n"));
        }
    }
}
