package org.hg4j.api;

import org.hg4j.core.HgRepository;
import org.hg4j.core.ChangegroupParser;
import org.hg4j.core.SafeFileIO;
import org.hg4j.core.Revlog;
import org.hg4j.core.NodeIdUtil;
import org.hg4j.errors.HgAuthException;
import org.hg4j.errors.HgProtocolException;
import org.hg4j.transport.HgSshClient;
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

public class HgSshTransportRoundtripTest {

    @Test
    public void testSshInvalidHeaderThrowsHgProtocolException() throws Exception {
        String url = "ssh://hg4juser@127.0.0.1:22/test/repo";
        try (HgSshClient client = new HgSshClient(url)) {
            // Inject invalid header response in the stream
            java.lang.reflect.Field inField = HgSshClient.class.getDeclaredField("in");
            inField.setAccessible(true);
            inField.set(client, new ByteArrayInputStream("invalid response format\n".getBytes(StandardCharsets.UTF_8)));

            java.lang.reflect.Method readCapabilities = HgSshClient.class.getDeclaredMethod("readCapabilities");
            readCapabilities.setAccessible(true);

            java.lang.reflect.InvocationTargetException ex = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> {
                readCapabilities.invoke(client);
            });
            assertTrue(ex.getCause() instanceof HgProtocolException);
        }
    }

    @Test
    public void testSshAbruptEofDuringChunkRead() throws Exception {
        String url = "ssh://hg4juser@127.0.0.1:22/test/repo";
        try (HgSshClient client = new HgSshClient(url)) {
            java.lang.reflect.Field connectedField = HgSshClient.class.getDeclaredField("connected");
            connectedField.setAccessible(true);
            connectedField.set(client, true);

            java.lang.reflect.Field capabilitiesField = HgSshClient.class.getDeclaredField("capabilities");
            capabilitiesField.setAccessible(true);
            capabilitiesField.set(client, List.of("lookup", "changegroupsubsets", "branchmap", "getbundle"));

            java.lang.reflect.Field inField = HgSshClient.class.getDeclaredField("in");
            inField.setAccessible(true);
            // Incomplete chunk length simulation (EOF)
            inField.set(client, new ByteArrayInputStream(new byte[]{0, 0}));

            java.lang.reflect.Field outField = HgSshClient.class.getDeclaredField("out");
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
        ChangegroupParser.ChangegroupBundle bundle = createMockBundleFromRepo(srcRepo);
        byte[] rawCg = serializeBundleToBytes(bundle);
        
        ByteArrayOutputStream serverResponse = new ByteArrayOutputStream();
        // Heads response: c2 + "\n"
        serverResponse.write((NodeIdUtil.toHex(c2) + "\n").getBytes(StandardCharsets.UTF_8));
        
        // Wrap getbundle response in Mercurial SSH binary chunked format
        byte[] payload = new byte[6 + rawCg.length];
        System.arraycopy("HG10UN".getBytes(StandardCharsets.US_ASCII), 0, payload, 0, 6);
        System.arraycopy(rawCg, 0, payload, 6, rawCg.length);

        int len = payload.length;
        serverResponse.write((len >> 24) & 0xFF);
        serverResponse.write((len >> 16) & 0xFF);
        serverResponse.write((len >> 8) & 0xFF);
        serverResponse.write(len & 0xFF);
        serverResponse.write(payload);

        // Terminal chunk (length 0)
        serverResponse.write(0);
        serverResponse.write(0);
        serverResponse.write(0);
        serverResponse.write(0);
        
        // 3. Prepare local destination repository
        File destDir = tempDir.resolve("dest_repo").toFile();
        new InitCommand().setDirectory(destDir).call();
        HgRepository destRepo = new HgRepository(destDir);
        
        // 4. Inject reflection mock to HgSshClient
        String url = "ssh://hg4juser@127.0.0.1:22/dummy/path";
        try (HgSshClient client = new HgSshClient(url)) {
            java.lang.reflect.Field connectedField = HgSshClient.class.getDeclaredField("connected");
            connectedField.setAccessible(true);
            connectedField.set(client, true);
            
            java.lang.reflect.Field capabilitiesField = HgSshClient.class.getDeclaredField("capabilities");
            capabilitiesField.setAccessible(true);
            capabilitiesField.set(client, List.of("lookup", "changegroupsubsets", "branchmap", "getbundle"));
            
            java.lang.reflect.Field inField = HgSshClient.class.getDeclaredField("in");
            inField.setAccessible(true);
            inField.set(client, new ByteArrayInputStream(serverResponse.toByteArray()));
            
            java.lang.reflect.Field outField = HgSshClient.class.getDeclaredField("out");
            outField.setAccessible(true);
            ByteArrayOutputStream clientSentBytes = new ByteArrayOutputStream();
            outField.set(client, clientSentBytes);
            
            // 5. Test SSH Pull operation client protocol
            List<String> heads = client.getHeads();
            assertEquals(1, heads.size());
            assertEquals(NodeIdUtil.toHex(c2), heads.get(0));
            
            byte[] pulledBundle = client.getBundle(List.of(), List.of(NodeIdUtil.toHex(c2)), List.of("bundle2"));
            
            // 6. Apply pulled bundle to destination repository
            byte[] cgBytes = new byte[pulledBundle.length - 6];
            System.arraycopy(pulledBundle, 6, cgBytes, 0, cgBytes.length);
            ChangegroupParser.ChangegroupBundle parsedBundle = ChangegroupParser.parseBundle(new ByteArrayInputStream(cgBytes), "01");
            
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
        ChangegroupParser.ChangegroupBundle bundle = createMockBundleFromRepo(localRepo);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write("HG10UN".getBytes(StandardCharsets.US_ASCII));
        bos.write(serializeBundleToBytes(bundle));
        byte[] localBundleBytes = bos.toByteArray();
        
        // 3. Inject reflection mock to HgSshClient
        String url = "ssh://hg4juser@127.0.0.1:22/dummy/path";
        try (HgSshClient client = new HgSshClient(url)) {
            java.lang.reflect.Field connectedField = HgSshClient.class.getDeclaredField("connected");
            connectedField.setAccessible(true);
            connectedField.set(client, true);
            
            java.lang.reflect.Field capabilitiesField = HgSshClient.class.getDeclaredField("capabilities");
            capabilitiesField.setAccessible(true);
            capabilitiesField.set(client, List.of("lookup", "changegroupsubsets", "branchmap", "unbundle"));
            
            java.lang.reflect.Field inField = HgSshClient.class.getDeclaredField("in");
            inField.setAccessible(true);
            // Inject successful unbundle response matching Mercurial spec (1 line message follows)
            inField.set(client, new ByteArrayInputStream("1\nno errors\n".getBytes(StandardCharsets.UTF_8)));
            
            java.lang.reflect.Field outField = HgSshClient.class.getDeclaredField("out");
            outField.setAccessible(true);
            ByteArrayOutputStream clientSentBytes = new ByteArrayOutputStream();
            outField.set(client, clientSentBytes);
            
            // 4. Test SSH Push operation client protocol
            String pushResult = client.push(localBundleBytes, List.of(NodeIdUtil.toHex(c1)));
            
            // 5. Verify pushed commands and data
            assertEquals("1\nno errors", pushResult.trim());
            
            byte[] sentData = clientSentBytes.toByteArray();
            assertTrue(sentData.length > 0);
            
            String sentString = new String(sentData, StandardCharsets.UTF_8);
            assertTrue(sentString.contains("unbundle\n"));
        }
    }

    private ChangegroupParser.ChangegroupBundle createMockBundleFromRepo(HgRepository repo) throws Exception {
        ChangegroupParser.ChangegroupBundle bundle = new ChangegroupParser.ChangegroupBundle();
        bundle.changelogEntries = new ArrayList<>();
        bundle.manifestEntries = new ArrayList<>();
        bundle.fileGroups = new ArrayList<>();

        Revlog cl = new Revlog(new File(repo.getStoreDir(), "00changelog.i"), new File(repo.getStoreDir(), "00changelog.d"));
        for (int i = 0; i < cl.getRevisionCount(); i++) {
            Revlog.IndexRecord rec = cl.getIndexRecord(i);
            ChangegroupParser.ChangeGroupEntry entry = new ChangegroupParser.ChangeGroupEntry();
            entry.node = rec.getNodeId();
            entry.p1 = rec.getParent1() != -1 ? cl.getIndexRecord(rec.getParent1()).getNodeId() : new byte[20];
            entry.p2 = rec.getParent2() != -1 ? cl.getIndexRecord(rec.getParent2()).getNodeId() : new byte[20];
            entry.cs = rec.getNodeId();
            
            byte[] rawContent = cl.getRawRevisionContent(i);
            byte[] delta;
            if (i == 0) {
                delta = Revlog.createSimpleDelta(new byte[0], rawContent);
            } else {
                byte[] prevContent = cl.getRawRevisionContent(i - 1);
                delta = Revlog.createSimpleDelta(prevContent, rawContent);
            }
            entry.delta = delta;
            bundle.changelogEntries.add(entry);
        }

        Revlog mf = new Revlog(new File(repo.getStoreDir(), "00manifest.i"), new File(repo.getStoreDir(), "00manifest.d"));
        for (int i = 0; i < mf.getRevisionCount(); i++) {
            Revlog.IndexRecord rec = mf.getIndexRecord(i);
            ChangegroupParser.ChangeGroupEntry entry = new ChangegroupParser.ChangeGroupEntry();
            entry.node = rec.getNodeId();
            entry.p1 = rec.getParent1() != -1 ? mf.getIndexRecord(rec.getParent1()).getNodeId() : new byte[20];
            entry.p2 = rec.getParent2() != -1 ? mf.getIndexRecord(rec.getParent2()).getNodeId() : new byte[20];
            entry.cs = cl.getIndexRecord(rec.getLinkRev()).getNodeId();
            
            byte[] rawContent = mf.getRawRevisionContent(i);
            byte[] delta;
            if (i == 0) {
                delta = Revlog.createSimpleDelta(new byte[0], rawContent);
            } else {
                byte[] prevContent = mf.getRawRevisionContent(i - 1);
                delta = Revlog.createSimpleDelta(prevContent, rawContent);
            }
            entry.delta = delta;
            bundle.manifestEntries.add(entry);
        }

        File fncacheFile = new File(repo.getStoreDir(), "fncache");
        if (fncacheFile.exists()) {
            List<String> paths = Files.readAllLines(fncacheFile.toPath(), StandardCharsets.UTF_8);
            for (String p : paths) {
                if (p.endsWith(".i")) {
                    String rawPath = p.substring("data/".length(), p.length() - 2);
                    File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), rawPath);
                    File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");

                    Revlog fl = new Revlog(flIdx, flDat);
                    ChangegroupParser.FileGroup fg = new ChangegroupParser.FileGroup();
                    fg.path = rawPath;
                    fg.entries = new ArrayList<>();
                    for (int j = 0; j < fl.getRevisionCount(); j++) {
                        Revlog.IndexRecord rec = fl.getIndexRecord(j);
                        ChangegroupParser.ChangeGroupEntry entry = new ChangegroupParser.ChangeGroupEntry();
                        entry.node = rec.getNodeId();
                        entry.p1 = rec.getParent1() != -1 ? fl.getIndexRecord(rec.getParent1()).getNodeId() : new byte[20];
                        entry.p2 = rec.getParent2() != -1 ? fl.getIndexRecord(rec.getParent2()).getNodeId() : new byte[20];
                        entry.cs = cl.getIndexRecord(rec.getLinkRev()).getNodeId();

                        byte[] rawContent = fl.getRawRevisionContent(j);
                        byte[] delta;
                        if (j == 0) {
                            delta = Revlog.createSimpleDelta(new byte[0], rawContent);
                        } else {
                            byte[] prevContent = fl.getRawRevisionContent(j - 1);
                            delta = Revlog.createSimpleDelta(prevContent, rawContent);
                        }
                        entry.delta = delta;
                        fg.entries.add(entry);
                    }
                    bundle.fileGroups.add(fg);
                }
            }
        }

        return bundle;
    }

    private byte[] serializeBundleToBytes(ChangegroupParser.ChangegroupBundle bundle) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            for (ChangegroupParser.ChangeGroupEntry entry : bundle.changelogEntries) {
                int totalLen = 4 + 80 + entry.delta.length;
                dos.writeInt(totalLen);
                dos.write(entry.node);
                dos.write(entry.p1);
                dos.write(entry.p2);
                dos.write(entry.cs);
                dos.write(entry.delta);
            }
            dos.writeInt(0);

            for (ChangegroupParser.ChangeGroupEntry entry : bundle.manifestEntries) {
                int totalLen = 4 + 80 + entry.delta.length;
                dos.writeInt(totalLen);
                dos.write(entry.node);
                dos.write(entry.p1);
                dos.write(entry.p2);
                dos.write(entry.cs);
                dos.write(entry.delta);
            }
            dos.writeInt(0);

            for (ChangegroupParser.FileGroup fg : bundle.fileGroups) {
                byte[] pathBytes = fg.path.getBytes(StandardCharsets.UTF_8);
                dos.writeInt(4 + pathBytes.length);
                dos.write(pathBytes);
                for (ChangegroupParser.ChangeGroupEntry entry : fg.entries) {
                    int totalLen = 4 + 80 + entry.delta.length;
                    dos.writeInt(totalLen);
                    dos.write(entry.node);
                    dos.write(entry.p1);
                    dos.write(entry.p2);
                    dos.write(entry.cs);
                    dos.write(entry.delta);
                }
                dos.writeInt(0);
            }
            dos.writeInt(0);
        }
        return baos.toByteArray();
    }
}
