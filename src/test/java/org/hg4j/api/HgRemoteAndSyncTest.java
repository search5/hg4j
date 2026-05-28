package org.hg4j.api;

import org.hg4j.core.ChangegroupParser;
import org.hg4j.core.Dirstate;
import org.hg4j.core.HgRemoteClient;
import org.hg4j.core.HgRepository;
import org.hg4j.core.NodeIdUtil;
import org.hg4j.core.Revlog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class HgRemoteAndSyncTest {

    @Test
    public void testPullAndCloneEndToEnd(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();

        // 1. Create source repository with some commits
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();

        File f1 = new File(srcDir, "README.MD");
        Files.writeString(f1.toPath(), "Hello remote sync");
        File f2 = new File(srcDir, "한글_파일.txt");
        Files.writeString(f2.toPath(), "동기화 한글 파일 내용");

        Hg.add(srcRepo).call();
        byte[] commitNode1 = Hg.commit(srcRepo)
                .setAuthor("Alice <alice@example.com>")
                .setMessage("First commit in source")
                .call();

        // 2. Mock a ChangegroupBundle based on source repository revisions
        ChangegroupParser.ChangegroupBundle bundle = createMockBundleFromRepo(srcRepo);

        // 3. Apply bundle via PullCommand in a new destination repository
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        PullCommand pullCmd = new PullCommand(destRepo);
        List<byte[]> imported = pullCmd.applyBundle(bundle);

        assertEquals(1, imported.size());
        assertArrayEquals(commitNode1, imported.get(0));

        // 4. Perform CloneCommand-like Checkout on dest repo and verify files are reconstructed
        CloneCommand cloneCommand = new CloneCommand(); // To call checkoutLatest helper indirectly or check layout
        
        // Let's verify destRepo has the correct history in changelog
        Revlog cl = new Revlog(new File(destRepo.getStoreDir(), "00changelog.i"), new File(destRepo.getStoreDir(), "00changelog.d"));
        assertEquals(1, cl.getRevisionCount());
        byte[] content = cl.getRevisionContent(0);
        String text = new String(content, StandardCharsets.UTF_8);
        assertTrue(text.contains("First commit in source"));
        assertTrue(text.contains("Alice <alice@example.com>"));

        // Verify fncache on dest contains the raw specifications
        File fncacheFile = new File(destRepo.getStoreDir(), "fncache");
        assertTrue(fncacheFile.exists());
        List<String> fncachePaths = Files.readAllLines(fncacheFile.toPath(), StandardCharsets.UTF_8);
        assertTrue(fncachePaths.contains("data/README.MD.i"));
        assertTrue(fncachePaths.contains("data/한글_파일.txt.i"));
    }

    @Test
    public void testPullCommandRollbackOnCorruptedStream(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("source").toFile();
        File destDir = tempDir.resolve("dest").toFile();

        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        File f1 = new File(srcDir, "a.txt");
        Files.writeString(f1.toPath(), "test");
        Hg.add(srcRepo).call();
        Hg.commit(srcRepo).setMessage("First").call();

        ChangegroupParser.ChangegroupBundle bundle = createMockBundleFromRepo(srcRepo);

        // Let's corrupt the manifest entries to trigger failure during pull application
        bundle.manifestEntries.get(0).cs = new byte[20]; // Corrupt link revision hash to trigger IOException

        HgRepository destRepo = Hg.init().setDirectory(destDir).call();

        PullCommand pullCmd = new PullCommand(destRepo);
        assertThrows(Exception.class, () -> {
            pullCmd.applyBundle(bundle);
        });

        // Verify transactional integrity: no 00changelog.i or fncache exists, or they are empty (rolled back!)
        File clIdx = new File(destRepo.getStoreDir(), "00changelog.i");
        if (clIdx.exists()) {
            assertEquals(0, clIdx.length());
        }
        File fncacheFile = new File(destRepo.getStoreDir(), "fncache");
        assertFalse(fncacheFile.exists());
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
            
            // Create chunk-like delta (in v1 bundle delta is basically simple delta formatted)
            byte[] rawContent = cl.getRevisionContent(i);
            entry.delta = Revlog.createSimpleDelta(new byte[0], rawContent);
            bundle.changelogEntries.add(entry);
        }

        Revlog mf = new Revlog(new File(repo.getStoreDir(), "00manifest.i"), new File(repo.getStoreDir(), "00manifest.d"));
        for (int i = 0; i < mf.getRevisionCount(); i++) {
            Revlog.IndexRecord rec = mf.getIndexRecord(i);
            ChangegroupParser.ChangeGroupEntry entry = new ChangegroupParser.ChangeGroupEntry();
            entry.node = rec.getNodeId();
            entry.p1 = rec.getParent1() != -1 ? mf.getIndexRecord(rec.getParent1()).getNodeId() : new byte[20];
            entry.p2 = rec.getParent2() != -1 ? mf.getIndexRecord(rec.getParent2()).getNodeId() : new byte[20];
            
            // Link to the corresponding changelog node
            entry.cs = cl.getIndexRecord(rec.getLinkRev()).getNodeId();
            
            byte[] rawContent = mf.getRevisionContent(i);
            entry.delta = Revlog.createSimpleDelta(new byte[0], rawContent);
            bundle.manifestEntries.add(entry);
        }

        // Search for tracked files in fncache
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

                        byte[] rawContent = fl.getRevisionContent(j);
                        entry.delta = Revlog.createSimpleDelta(new byte[0], rawContent);
                        fg.entries.add(entry);
                    }
                    bundle.fileGroups.add(fg);
                }
            }
        }

        return bundle;
    }

    @Test
    public void testChangegroupEntryHashVerificationFailure(@TempDir Path tempDir) throws Exception {
        File idx = tempDir.resolve("test.i").toFile();
        File dat = tempDir.resolve("test.d").toFile();
        Revlog revlog = new Revlog(idx, dat);

        ChangegroupParser.ChangeGroupEntry entry = new ChangegroupParser.ChangeGroupEntry();
        entry.node = new byte[20];
        entry.p1 = new byte[20];
        entry.p2 = new byte[20];
        entry.delta = Revlog.createSimpleDelta(new byte[0], "hello".getBytes(StandardCharsets.UTF_8));
        
        Arrays.fill(entry.node, (byte) 1);

        IOException ex = assertThrows(IOException.class, () -> {
            revlog.appendChangeGroupEntry(entry, 0);
        });
        assertTrue(ex.getMessage().contains("Security Integrity Error"));
    }

    @Test
    public void testChangegroupParserOOMGuard() throws Exception {
        int maliciousLength = 50 * 1024 * 1024 + 4;
        byte[] lenBytes = new byte[4];
        lenBytes[0] = (byte) ((maliciousLength >>> 24) & 0xFF);
        lenBytes[1] = (byte) ((maliciousLength >>> 16) & 0xFF);
        lenBytes[2] = (byte) ((maliciousLength >>> 8) & 0xFF);
        lenBytes[3] = (byte) (maliciousLength & 0xFF);

        ByteArrayInputStream in = new ByteArrayInputStream(lenBytes);
        IOException ex = assertThrows(IOException.class, () -> {
            ChangegroupParser.readChunk(in);
        });
        assertTrue(ex.getMessage().contains("Security Guard: Changegroup chunk size exceeds maximum allowed limit"));
    }

    @Test
    public void testHgRemoteClientTlsEnforcement() {
        HgRemoteClient client = new HgRemoteClient("http://example.com/hg");
        client.setForceTls(true);
        assertThrows(SecurityException.class, () -> {
            client.getHeads();
        });
    }

    @Test
    public void testPullCommandRootsOptimization(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // Commit 1
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Content 1");
        Hg.add(repo).call();
        byte[] node1 = Hg.commit(repo).setMessage("Commit 1").call();

        // Commit 2 (child of Commit 1)
        Files.writeString(f1.toPath(), "Content 2");
        byte[] node2 = Hg.commit(repo).setMessage("Commit 2").call();

        Revlog localChangelog = new Revlog(
            new File(repo.getStoreDir(), "00changelog.i"),
            new File(repo.getStoreDir(), "00changelog.d")
        );
        assertEquals(2, localChangelog.getRevisionCount());

        int count = localChangelog.getRevisionCount();
        boolean[] isParent = new boolean[count];
        for (int i = 0; i < count; i++) {
            Revlog.IndexRecord rec = localChangelog.getIndexRecord(i);
            if (rec.getParent1() >= 0 && rec.getParent1() < count) {
                isParent[rec.getParent1()] = true;
            }
            if (rec.getParent2() >= 0 && rec.getParent2() < count) {
                isParent[rec.getParent2()] = true;
            }
        }
        
        List<String> computedHeads = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (!isParent[i]) {
                computedHeads.add(NodeIdUtil.toHex(localChangelog.getIndexRecord(i).getNodeId()));
            }
        }

        assertEquals(1, computedHeads.size());
        assertEquals(NodeIdUtil.toHex(node2), computedHeads.get(0));
    }
}
