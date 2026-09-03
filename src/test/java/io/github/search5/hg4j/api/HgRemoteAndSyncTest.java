package io.github.search5.hg4j.api;
import io.github.search5.hg4j.bundle.Bundle2Parser;
import io.github.search5.hg4j.bundle.ChangegroupParser;

import io.github.search5.hg4j.transport.HgRemoteClient;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.storage.Revlog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.treewalk.ManifestTreeIterator;
import io.github.search5.hg4j.treewalk.TreeWalk;
import io.github.search5.hg4j.treewalk.WorkingDirTreeIterator;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Timeout;

public class HgRemoteAndSyncTest {

    @Test
    public void testPullCommandValidationAndEdgeCases(@TempDir Path tempDir) throws Exception {
        File destDir = tempDir.resolve("dest_validation").toFile();
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        
        PullCommand pullCmd = new PullCommand(destRepo);
        
        // 1. URL이 null일 때 예외 검증
        assertThrows(IllegalStateException.class, () -> pullCmd.call());
        
        // 2. URL이 empty일 때 예외 검증
        pullCmd.setSource("");
        assertThrows(IllegalStateException.class, () -> pullCmd.call());
    }

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

        new AddCommand(srcRepo).call();
        byte[] commitNode1 = new CommitCommand(srcRepo)
                .setAuthor("Alice <alice@example.com>")
                .setMessage("First commit in source")
                .call();

        // 2. Mock a ChangegroupBundle based on source repository revisions
        ChangegroupParser.ChangegroupBundle bundle = HgTestUtils.createMockBundleFromRepo(srcRepo);

        // 3. Apply bundle via PullCommand in a new destination repository
        HgRepository destRepo = Hg.init().setDirectory(destDir).call();
        PullCommand pullCmd = new PullCommand(destRepo);
        List<byte[]> imported = pullCmd.applyBundle(bundle);

        assertEquals(1, imported.size());
        assertArrayEquals(commitNode1, imported.get(0));


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
        new AddCommand(srcRepo).call();
        new CommitCommand(srcRepo).setMessage("First").call();

        ChangegroupParser.ChangegroupBundle bundle = HgTestUtils.createMockBundleFromRepo(srcRepo);

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
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit 1").call();

        // Commit 2 (child of Commit 1)
        Files.writeString(f1.toPath(), "Content 2");
        byte[] node2 = new CommitCommand(repo).setMessage("Commit 2").call();

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

    @Test
    public void testPushCommandWithMockServer(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // 1. Create a baseline commit
        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Hello push safety\n");
        new AddCommand(repo).call();
        byte[] localHeadNode = new CommitCommand(repo).setMessage("First local commit").call();

        // 2. Setup mock server
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        
        // State variables to assert within handler
        final List<String> remoteHeads = List.of("0000000000000000000000000000000000000000"); // empty remote head
        final boolean[] headsCalled = {false};
        final boolean[] unbundleCalled = {false};
        final boolean[] magicMatches = {false};
        final String[] unbundleQuery = {null};

        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String query = exchange.getRequestURI().getQuery();
                String method = exchange.getRequestMethod();

                if ("GET".equalsIgnoreCase(method) && query != null && query.contains("cmd=heads")) {
                    headsCalled[0] = true;
                    String response = String.join("\n", remoteHeads) + "\n";
                    byte[] respBytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, respBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(respBytes);
                    }
                } else if ("POST".equalsIgnoreCase(method) && query != null && query.contains("cmd=unbundle")) {
                    unbundleCalled[0] = true;
                    unbundleQuery[0] = query;

                    // Verify headers
                    assertEquals("application/mercurial-0.1", exchange.getRequestHeaders().getFirst("Content-Type"));
                    assertEquals("application/mercurial-0.1", exchange.getRequestHeaders().getFirst("Accept"));

                    // Verify body starting with "HG10UN"
                    try (InputStream is = exchange.getRequestBody()) {
                        byte[] magicBytes = new byte[6];
                        int read = is.read(magicBytes);
                        if (read == 6 && "HG10UN".equals(new String(magicBytes, StandardCharsets.US_ASCII))) {
                            magicMatches[0] = true;
                        }

                        // Consume remaining bytes
                        byte[] buffer = new byte[4096];
                        while (is.read(buffer) != -1) {}
                    }

                    String response = "0\nno changes\n";
                    byte[] respBytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, respBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(respBytes);
                    }
                } else {
                    exchange.sendResponseHeaders(404, 0);
                    exchange.close();
                }
            }
        });

        server.start();
        int port = server.getAddress().getPort();

        try {
            // 3. Perform push
            String destUrl = "http://127.0.0.1:" + port + "/";
            String result = new PushCommand(repo).setDestination(destUrl).call();
            assertNotNull(result);

            // 4. Assert correctness
            assertTrue(headsCalled[0], "GET heads should have been called");
            assertTrue(unbundleCalled[0], "POST unbundle should have been called");
            assertTrue(magicMatches[0], "Push payload should have started with 'HG10UN' magic bytes");
            
            // Assert that the 'heads' query param passed to unbundle matches remote heads, not local heads
            assertNotNull(unbundleQuery[0]);
            assertTrue(unbundleQuery[0].contains("heads=" + String.join("+", remoteHeads)), 
                "unbundle query should contain precondition remote heads: " + unbundleQuery[0]);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testCloneCommandWithMockServer(@TempDir Path tempDir) throws Exception {
        File srcDir = tempDir.resolve("src").toFile();
        File cloneDir = tempDir.resolve("cloned").toFile();

        // 1. Create local repository with commits
        HgRepository srcRepo = Hg.init().setDirectory(srcDir).call();
        File f1 = new File(srcDir, "a.txt");
        Files.writeString(f1.toPath(), "Revision 1 Data\n");
        new AddCommand(srcRepo).call();
        byte[] headNode = new CommitCommand(srcRepo).setMessage("Initial Commit").call();
        String headHex = NodeIdUtil.toHex(headNode);

        // Prepare bundle payload
        ChangegroupParser.ChangegroupBundle bundle = HgTestUtils.createMockBundleFromRepo(srcRepo);
        byte[] rawCgBytes = HgTestUtils.serializeBundleToBytes(bundle);

        // 2. Setup mock server
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        
        final boolean[] capsCalled = {false};
        final boolean[] headsCalled = {false};
        final boolean[] cgCalled = {false};

        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String query = exchange.getRequestURI().getQuery();
                String method = exchange.getRequestMethod();

                if ("GET".equalsIgnoreCase(method) && query != null && query.contains("cmd=capabilities")) {
                    capsCalled[0] = true;
                    // Return empty capabilities to force changegroup v1 pull instead of getbundle
                    byte[] respBytes = "".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, respBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(respBytes);
                    }
                } else if ("GET".equalsIgnoreCase(method) && query != null && query.contains("cmd=heads")) {
                    headsCalled[0] = true;
                    String response = headHex + "\n";
                    byte[] respBytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, respBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(respBytes);
                    }
                } else if (query != null && query.contains("cmd=changegroup")) {
                    // Real hg's actual v1 arg transport (see HgRemoteClient#executeArgsCommand):
                    // a server that never advertised httpheader=/httppostargs gets the legacy 3rd
                    // tier -- a plain GET with args appended to the query string, not a POST.
                    cgCalled[0] = true;
                    exchange.sendResponseHeaders(200, rawCgBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(rawCgBytes);
                    }
                } else {
                    exchange.sendResponseHeaders(404, 0);
                    exchange.close();
                }
            }
        });

        server.start();
        int port = server.getAddress().getPort();

        try {
            // 3. Perform clone
            String sourceUrl = "http://127.0.0.1:" + port + "/";
            HgRepository clonedRepo = Hg.cloneRepository()
                    .setSource(sourceUrl)
                    .setDirectory(cloneDir)
                    .call();

            assertNotNull(clonedRepo);

            // 4. Assert correctness
            assertTrue(capsCalled[0], "capabilities should be retrieved");
            assertTrue(headsCalled[0], "heads should be retrieved");
            assertTrue(cgCalled[0], "changegroup payload should be downloaded");

            // Verify files checked out
            File clonedF1 = new File(cloneDir, "a.txt");
            assertTrue(clonedF1.exists());
            assertEquals("Revision 1 Data\n", Files.readString(clonedF1.toPath()));
        } finally {
            server.stop(0);
        }
    }



    private void runProcess(File dir, String... command) throws Exception {
        String[] cmd = command;
        if (command.length > 0 && "hg".equals(command[0])) {
            cmd = new String[command.length + 2];
            cmd[0] = "hg";
            cmd[1] = "--config";
            cmd[2] = "format.usezstd=false";
            System.arraycopy(command, 1, cmd, 3, command.length - 1);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (InputStream is = process.getInputStream()) {
            is.readAllBytes();
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Process " + Arrays.toString(cmd) + " failed with exit code: " + exitCode);
        }
    }

    @Test
    public void testNativeHgCopyRenamePull(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping native hg pull integration test.");

        // 1. Setup native hg remote repository
        File remoteRepoDir = tempDir.resolve("remote_repo").toFile();
        remoteRepoDir.mkdirs();
        runProcess(remoteRepoDir, "hg", "init");

        // Write file a.txt and commit
        File fa = new File(remoteRepoDir, "a.txt");
        Files.writeString(fa.toPath(), "Initial source content\n");
        runProcess(remoteRepoDir, "hg", "add", "a.txt");
        runProcess(remoteRepoDir, "hg", "commit", "-m", "Initial commit");

        // Copy a.txt to b.txt and commit (This generates copy metadata)
        runProcess(remoteRepoDir, "hg", "cp", "a.txt", "b.txt");
        runProcess(remoteRepoDir, "hg", "commit", "-m", "Copy a.txt to b.txt");

        // 2. Start native hg serve on port 0
        ProcessBuilder servePb = new ProcessBuilder("hg", "serve", "-p", "0", "--address", "127.0.0.1");
        servePb.directory(remoteRepoDir);
        servePb.redirectErrorStream(true);
        Process serveProcess = servePb.start();

        InputStream rawIn = serveProcess.getInputStream();
        InputStream nonCloseableIn = new FilterInputStream(rawIn) {
            @Override
            public void close() throws IOException {
                // Do not close the underlying process stream
            }
        };

        String remoteUrl = null;
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(nonCloseableIn, StandardCharsets.UTF_8));
        
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 5000) {
            if (reader.ready()) {
                String line = reader.readLine();
                if (line != null && line.contains("listening at")) {
                    int idx = line.indexOf("http://");
                    if (idx != -1) {
                        int end = line.indexOf("/", idx + 7);
                        if (end != -1) {
                            remoteUrl = line.substring(idx, end + 1);
                        } else {
                            remoteUrl = line.substring(idx).trim();
                        }
                        if (remoteUrl != null) {
                            remoteUrl = remoteUrl.replaceAll("http://[^:]+:", "http://127.0.0.1:");
                        }
                        break;
                    }
                }
            }
            Thread.sleep(50);
        }

        assertNotNull(remoteUrl, "Failed to parse remote URL from hg serve output");

        try {
            // 3. Initialize local hg4j repository and pull from native hg serve
            File localRepoDir = tempDir.resolve("local_repo").toFile();
            HgRepository localRepo = Hg.init().setDirectory(localRepoDir).call();

            // Execute pull command
            List<byte[]> pulledNodes = new PullCommand(localRepo).setSource(remoteUrl).call();
            assertFalse(pulledNodes.isEmpty(), "Should have pulled some changesets from native hg");

            // 4. Verify that b.txt copy metadata is preserved and resolved correctly in localRepo
            File bIdx = CommitCommand.getFilelogIndex(localRepo.getStoreDir(), "b.txt");
            File bDat = new File(bIdx.getPath().substring(0, bIdx.getPath().length() - 2) + ".d");
            assertTrue(bIdx.exists(), "b.txt filelog index must exist locally after pull");

            Revlog bFilelog = localRepo.getRevlog(bIdx, bDat);
            assertEquals(1, bFilelog.getRevisionCount(), "b.txt must have 1 revision");

            // Read metadata and verify copy source
            Map<String, String> metadata = bFilelog.getRevisionMetadata(0);
            assertEquals("a.txt", metadata.get("copy"), "Copy source metadata must match 'a.txt'");

            // Read logical content and verify it matches
            byte[] logical = bFilelog.getRevisionContent(0);
            assertEquals("Initial source content\n", new String(logical, StandardCharsets.UTF_8));
        } finally {
            serveProcess.destroy();
            serveProcess.waitFor();
        }
    }

    @Test
    public void testNativeHgPush(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), "Native Mercurial (hg) is not installed. Skipping native hg push integration test.");

        // 1. Setup native hg remote repository
        File remoteRepoDir = tempDir.resolve("remote_repo").toFile();
        remoteRepoDir.mkdirs();
        runProcess(remoteRepoDir, "hg", "init");

        // Allow push over http in the remote repo config
        File hgrc = new File(remoteRepoDir, ".hg/hgrc");
        Files.writeString(hgrc.toPath(), "[web]\nallow_push = *\npush_ssl = false\n");

        // 2. Start native hg serve on port 0
        ProcessBuilder servePb = new ProcessBuilder("hg", "serve", "-p", "0", "--address", "127.0.0.1");
        servePb.directory(remoteRepoDir);
        servePb.redirectErrorStream(true);
        Process serveProcess = servePb.start();

        InputStream rawIn = serveProcess.getInputStream();
        InputStream nonCloseableIn = new FilterInputStream(rawIn) {
            @Override
            public void close() throws IOException {
                // Do not close the underlying process stream
            }
        };

        String remoteUrl = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(nonCloseableIn, StandardCharsets.UTF_8))) {
            
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 5000) {
                if (reader.ready()) {
                    String line = reader.readLine();
                    if (line != null && line.contains("listening at")) {
                        int idx = line.indexOf("http://");
                        if (idx != -1) {
                            int end = line.indexOf("/", idx + 7);
                            if (end != -1) {
                                remoteUrl = line.substring(idx, end + 1);
                            } else {
                                remoteUrl = line.substring(idx).trim();
                            }
                            if (remoteUrl != null) {
                                remoteUrl = remoteUrl.replaceAll("http://[^:]+:", "http://127.0.0.1:");
                            }
                            break;
                        }
                    }
                }
                Thread.sleep(50);
            }
        }

        assertNotNull(remoteUrl, "Failed to parse remote URL from hg serve output");

        try {
            // 3. Initialize local hg4j repository and commit a file
            File localRepoDir = tempDir.resolve("local_repo").toFile();
            HgRepository localRepo = Hg.init().setDirectory(localRepoDir).call();

            File f = new File(localRepoDir, "test.txt");
            Files.writeString(f.toPath(), "Content pushed from local hg4j repo\n");
            new AddCommand(localRepo).call();
            byte[] pushedNode = new CommitCommand(localRepo)
                    .setAuthor("Bob <bob@example.com>")
                    .setMessage("Push commit from hg4j")
                    .call();

            // Execute push command
            String pushResult = new PushCommand(localRepo).setDestination(remoteUrl).call();
            assertNotNull(pushResult, "Push result should not be null");

            // 4. Verify that remote repository indeed has the pushed revision
            ProcessBuilder logPb = new ProcessBuilder("hg", "log", "-r", "0", "--template", "{node} {desc} {author}");
            logPb.directory(remoteRepoDir);
            logPb.redirectErrorStream(true);
            Process logProcess = logPb.start();
            String logOutput = new String(logProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            logProcess.waitFor();

            assertTrue(logOutput.contains(NodeIdUtil.toHex(pushedNode)), "Remote log should contain the pushed node: " + NodeIdUtil.toHex(pushedNode));
            assertTrue(logOutput.contains("Push commit from hg4j"), "Remote log should contain the commit message");
            assertTrue(logOutput.contains("Bob <bob@example.com>"), "Remote log should contain the author");
        } finally {
            serveProcess.destroy();
            serveProcess.waitFor();
        }
    }

    @Test
    public void testPushCommandEdgeCases(@TempDir Path tempDir) throws Exception {
        File localDir = tempDir.resolve("local_repo").toFile();
        HgRepository localRepo = Hg.init().setDirectory(localDir).call();

        // 1. Destination URL is empty or null
        PushCommand pushEmpty = new PushCommand(localRepo);
        assertThrows(IllegalStateException.class, pushEmpty::call);
        pushEmpty.setDestination("");
        assertThrows(IllegalStateException.class, pushEmpty::call);

        // 2. Empty local repository push behavior
        pushEmpty.setDestination(localDir.getAbsolutePath());
        assertEquals("No changesets to push (empty local repository)", pushEmpty.call());

        // Create initial local commit
        File f1 = new File(localDir, "a.txt");
        Files.writeString(f1.toPath(), "test");
        new AddCommand(localRepo).call();
        new CommitCommand(localRepo).setMessage("First").call();

        // 3. Remote is up-to-date (pushing to self)
        PushCommand pushSelf = new PushCommand(localRepo).setDestination(localDir.getAbsolutePath());
        assertEquals("No changesets to push (remote is up-to-date)", pushSelf.call());

        // 4. Repository is unrelated
        File unrelatedDir = tempDir.resolve("unrelated_repo").toFile();
        HgRepository unrelatedRepo = Hg.init().setDirectory(unrelatedDir).call();
        File f2 = new File(unrelatedDir, "b.txt");
        Files.writeString(f2.toPath(), "different content");
        new AddCommand(unrelatedRepo).call();
        new CommitCommand(unrelatedRepo).setMessage("Unrelated").call();

        PushCommand pushUnrelated = new PushCommand(localRepo).setDestination(unrelatedDir.getAbsolutePath());
        HgValidationException ex = assertThrows(HgValidationException.class, pushUnrelated::call);
        assertTrue(ex.getMessage().contains("repository is unrelated"));

        // 5. Pack Filelogs missing filelog index (continue branch at line 188)
        File f3 = new File(localDir, "b.txt");
        Files.writeString(f3.toPath(), "test b.txt");
        new AddCommand(localRepo).call();
        new CommitCommand(localRepo).setMessage("Commit for b.txt").call();

        // Pull and sync remoteNew BEFORE deleting the filelog index so remoteNew is related to First
        File remoteNewDir = tempDir.resolve("remote_new").toFile();
        HgRepository remoteNew = Hg.init().setDirectory(remoteNewDir).call();
        new PullCommand(remoteNew).setSource(localDir.getAbsolutePath()).call();

        // Now delete the filelog index on local
        File flIdxB = CommitCommand.getFilelogIndex(localRepo.getStoreDir(), "b.txt");
        assertTrue(flIdxB.exists());
        assertTrue(flIdxB.delete());

        // Push should not crash and skip the missing filelog index quietly
        PushCommand pushMissingFl = new PushCommand(localRepo).setDestination(remoteNewDir.getAbsolutePath());
        assertNotNull(pushMissingFl.call());
    }

    @Test
    @Disabled("Disabled due to high resource usage and flakiness of Apache SSHD in container envs")
    @Timeout(10)
    public void testNativeHgSshPull(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), 
                "Native Mercurial (hg) is not installed. Skipping native hg-over-ssh pull integration test.");

        // 1. Setup native remote repository
        File remoteRepoDir = tempDir.resolve("remote_repo").toFile();
        remoteRepoDir.mkdirs();
        runProcess(remoteRepoDir, "hg", "init");

        File f1 = new File(remoteRepoDir, "a.txt");
        Files.writeString(f1.toPath(), "Content in SSH Remote repo\n");
        runProcess(remoteRepoDir, "hg", "add", "a.txt");
        runProcess(remoteRepoDir, "hg", "commit", "-m", "Initial commit over SSH");

        // 2. Start embedded SSHD server bridging native 'hg serve --stdio'
        SshServer sshServer = SshServer.setUpDefaultServer();
        sshServer.setPort(0);
        Path tempKey = Files.createTempFile("ssh_real_interop_test_", ".key");
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempKey));
        Files.deleteIfExists(tempKey);

        sshServer.setPasswordAuthenticator((username, password, session) -> true);
        sshServer.setCommandFactory((channel, command) -> new Command() {
            private InputStream in;
            private OutputStream out;
            private OutputStream err;
            private ExitCallback callback;
            private Process process;
            private Thread t1, t2, t3;

            @Override public void setInputStream(InputStream in) { this.in = in; }
            @Override public void setOutputStream(OutputStream out) { this.out = out; }
            @Override public void setErrorStream(OutputStream err) { this.err = err; }
            @Override public void setExitCallback(ExitCallback cb) { this.callback = cb; }

            @Override
            public void start(ChannelSession s, Environment env) throws IOException {
                ProcessBuilder pb = new ProcessBuilder("hg", "-R", remoteRepoDir.getAbsolutePath(), "serve", "--stdio");
                pb.redirectErrorStream(false);
                process = pb.start();

                t1 = new Thread(() -> {
                    byte[] buf = new byte[4096];
                    int len;
                    try (OutputStream procIn = process.getOutputStream()) {
                        while ((len = in.read(buf)) != -1) {
                            procIn.write(buf, 0, len);
                            procIn.flush();
                        }
                    } catch (IOException ignored) {}
                });

                t2 = new Thread(() -> {
                    byte[] buf = new byte[4096];
                    int len;
                    try (InputStream procOut = process.getInputStream()) {
                        while ((len = procOut.read(buf)) != -1) {
                            out.write(buf, 0, len);
                            out.flush();
                        }
                    } catch (IOException ignored) {}
                    if (callback != null) {
                        callback.onExit(0);
                    }
                });

                t3 = new Thread(() -> {
                    byte[] buf = new byte[4096];
                    int len;
                    try (InputStream procErr = process.getErrorStream()) {
                        while ((len = procErr.read(buf)) != -1) {
                            err.write(buf, 0, len);
                            err.flush();
                        }
                    } catch (IOException ignored) {}
                });

                t1.setDaemon(true);
                t2.setDaemon(true);
                t3.setDaemon(true);
                t1.start();
                t2.start();
                t3.start();
            }

            @Override
            public void destroy(ChannelSession s) {
                if (process != null) {
                    process.destroy();
                }
                if (t1 != null) t1.interrupt();
                if (t2 != null) t2.interrupt();
                if (t3 != null) t3.interrupt();
            }
        });

        sshServer.start();
        int port = sshServer.getPort();

        try {
            // 3. Initialize local hg4j repository and pull over real SSH connection
            File localRepoDir = tempDir.resolve("local_repo").toFile();
            HgRepository localRepo = Hg.init().setDirectory(localRepoDir).call();

            // 인라인 비밀번호(anypass)가 주입된 SSH URL 사용
            String sshUrl = "ssh://hguser:anypass@127.0.0.1:" + port + "/";
            PullCommand pullCmd = new PullCommand(localRepo).setSource(sshUrl);

            List<byte[]> pulledNodes = pullCmd.call();
            assertFalse(pulledNodes.isEmpty(), "Should pull changes over SSH from native hg serve --stdio");

            // Verify a.txt is correctly present in the local repository after sync
            File fIdx = CommitCommand.getFilelogIndex(localRepo.getStoreDir(), "a.txt");
            assertTrue(fIdx.exists(), "a.txt filelog must be pulled over SSH");
        } finally {
            sshServer.stop(true);
            Files.deleteIfExists(tempKey);
        }
    }

    @Test
    @Disabled("Disabled due to high resource usage and flakiness of Apache SSHD in container envs")
    @Timeout(10)
    public void testNativeHgSshPush(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(HgTestUtils.isHgInstalled(), 
                "Native Mercurial (hg) is not installed. Skipping native hg-over-ssh push integration test.");

        // 1. Setup native remote repository
        File remoteRepoDir = tempDir.resolve("remote_repo").toFile();
        remoteRepoDir.mkdirs();
        runProcess(remoteRepoDir, "hg", "init");

        // Allow push by default in hgrc
        File hgrc = new File(remoteRepoDir, ".hg/hgrc");
        Files.writeString(hgrc.toPath(), "[web]\nallow_push = *\npush_ssl = false\n");

        // 2. Start embedded SSHD server bridging native 'hg serve --stdio'
        SshServer sshServer = SshServer.setUpDefaultServer();
        sshServer.setPort(0);
        Path tempKey = Files.createTempFile("ssh_real_push_interop_", ".key");
        sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempKey));
        Files.deleteIfExists(tempKey);

        sshServer.setPasswordAuthenticator((username, password, session) -> true);
        sshServer.setCommandFactory((channel, command) -> new Command() {
            private InputStream in;
            private OutputStream out;
            private OutputStream err;
            private ExitCallback callback;
            private Process process;
            private Thread t1, t2, t3;

            @Override public void setInputStream(InputStream in) { this.in = in; }
            @Override public void setOutputStream(OutputStream out) { this.out = out; }
            @Override public void setErrorStream(OutputStream err) { this.err = err; }
            @Override public void setExitCallback(ExitCallback cb) { this.callback = cb; }

            @Override
            public void start(ChannelSession s, Environment env) throws IOException {
                ProcessBuilder pb = new ProcessBuilder("hg", "-R", remoteRepoDir.getAbsolutePath(), "serve", "--stdio");
                pb.redirectErrorStream(false);
                process = pb.start();

                t1 = new Thread(() -> {
                    byte[] buf = new byte[4096];
                    int len;
                    try (OutputStream procIn = process.getOutputStream()) {
                        while ((len = in.read(buf)) != -1) {
                            procIn.write(buf, 0, len);
                            procIn.flush();
                        }
                    } catch (IOException ignored) {}
                });

                t2 = new Thread(() -> {
                    byte[] buf = new byte[4096];
                    int len;
                    try (InputStream procOut = process.getInputStream()) {
                        while ((len = procOut.read(buf)) != -1) {
                            out.write(buf, 0, len);
                            out.flush();
                        }
                    } catch (IOException ignored) {}
                    if (callback != null) {
                        callback.onExit(0);
                    }
                });

                t3 = new Thread(() -> {
                    byte[] buf = new byte[4096];
                    int len;
                    try (InputStream procErr = process.getErrorStream()) {
                        while ((len = procErr.read(buf)) != -1) {
                            err.write(buf, 0, len);
                            err.flush();
                        }
                    } catch (IOException ignored) {}
                });

                t1.setDaemon(true);
                t2.setDaemon(true);
                t3.setDaemon(true);
                t1.start();
                t2.start();
                t3.start();
            }

            @Override
            public void destroy(ChannelSession s) {
                if (process != null) {
                    process.destroy();
                }
                if (t1 != null) t1.interrupt();
                if (t2 != null) t2.interrupt();
                if (t3 != null) t3.interrupt();
            }
        });

        sshServer.start();
        int port = sshServer.getPort();

        try {
            // 3. Initialize local hg4j repository and commit a file
            File localRepoDir = tempDir.resolve("local_repo").toFile();
            HgRepository localRepo = Hg.init().setDirectory(localRepoDir).call();

            File f = new File(localRepoDir, "pushed.txt");
            Files.writeString(f.toPath(), "Data pushed over real SSH connection\n");
            new AddCommand(localRepo).call();
            byte[] pushedNode = new CommitCommand(localRepo)
                    .setAuthor("SSHDester <tester@ssh.org>")
                    .setMessage("Real SSH push commit")
                    .call();

            // 4. Execute SSH Push (인라인 비밀번호 사용)
            String sshUrl = "ssh://hguser:anypass@127.0.0.1:" + port + "/";
            String pushResult = new PushCommand(localRepo).setDestination(sshUrl).call();
            assertNotNull(pushResult, "SSH Push should return standard unbundle confirmation");

            // 5. Verify that remote repository indeed has the pushed revision over SSH
            ProcessBuilder logPb = new ProcessBuilder("hg", "log", "-r", "0", "--template", "{node} {desc} {author}");
            logPb.directory(remoteRepoDir);
            logPb.redirectErrorStream(true);
            Process logProcess = logPb.start();
            String logOutput = new String(logProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            logProcess.waitFor();

            assertTrue(logOutput.contains(NodeIdUtil.toHex(pushedNode)), "Remote must contain SSH pushed node");
            assertTrue(logOutput.contains("Real SSH push commit"));
            assertTrue(logOutput.contains("SSHDester <tester@ssh.org>"));
        } finally {
            sshServer.stop(true);
            Files.deleteIfExists(tempKey);
        }
    }

    @Test
    public void testBundle2CompressionNegotiation() throws Exception {
        List<String> capabilities = Arrays.asList(
            "bundle2",
            "HG20",
            "getbundle",
            "compression=GZ,BZ,ZS"
        );
        boolean hasGzip = capabilities.stream().anyMatch(c -> c.contains("compression=") && c.contains("GZ"));
        boolean hasBzip = capabilities.stream().anyMatch(c -> c.contains("compression=") && c.contains("BZ"));
        assertTrue(hasGzip, "Negotiation must detect GZIP capability");
        assertTrue(hasBzip, "Negotiation must detect BZIP capability");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write("HG10GZ".getBytes(StandardCharsets.US_ASCII));
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (DeflaterOutputStream dos = new DeflaterOutputStream(compressed)) {
            dos.write(new byte[0]);
        }
        baos.write(compressed.toByteArray());
        byte[] payload = baos.toByteArray();
        assertEquals('H', payload[0]);
        assertEquals('G', payload[1]);
        assertEquals('1', payload[2]);
        assertEquals('0', payload[3]);
        assertEquals('G', payload[4]);
        assertEquals('Z', payload[5]);

        ByteArrayInputStream bais = new ByteArrayInputStream(payload, 6, payload.length - 6);
        try (InflaterInputStream iis = new InflaterInputStream(bais)) {
            byte[] decompressed = iis.readAllBytes();
            assertEquals(0, decompressed.length);
        }
    }

    @Test
    public void testIncrementalPullCommonNegotiation(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("local_incremental").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "Revision 1");
        new AddCommand(repo).call();
        byte[] c1 = new CommitCommand(repo).setMessage("Commit 1").call();

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog localChangelog = repo.getRevlog(clIdx, clDat);
        List<String> common = new ArrayList<>();
        int count = localChangelog.getRevisionCount();
        if (count > 0) {
            boolean[] isParent = new boolean[count];
            for (int i = 0; i < count; i++) {
                Revlog.IndexRecord rec = localChangelog.getIndexRecord(i);
                if (rec.getParent1() >= 0 && rec.getParent1() < count) isParent[rec.getParent1()] = true;
                if (rec.getParent2() >= 0 && rec.getParent2() < count) isParent[rec.getParent2()] = true;
            }
            for (int i = 0; i < count; i++) {
                if (!isParent[i]) {
                    common.add(NodeIdUtil.toHex(localChangelog.getIndexRecord(i).getNodeId()));
                }
            }
        }
        assertEquals(1, common.size());
        assertEquals(NodeIdUtil.toHex(c1), common.get(0));
    }

    @Test
    public void testNamedBranchAndMergeHistoryPull(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("merge_history_test").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f1 = new File(repoDir, "file1.txt");
        Files.writeString(f1.toPath(), "default branch file");
        new AddCommand(repo).call();
        byte[] base = new CommitCommand(repo).setMessage("base default").call();

        new BranchCommand(repo).setBranchName("feature").call();
        File f2 = new File(repoDir, "file2.txt");
        Files.writeString(f2.toPath(), "feature branch file");
        new AddCommand(repo).call();
        byte[] feat = new CommitCommand(repo).setMessage("feature branch commit").call();

        Dirstate ds = repo.getDirstate();
        ds.setParents(base, new byte[20]);
        repo.writeDirstate(ds);

        ds.setParents(base, feat);
        repo.writeDirstate(ds);

        Files.writeString(f2.toPath(), "feature branch file");
        new AddCommand(repo).call();
        byte[] mergeCommit = new CommitCommand(repo).setMessage("merge feature into default").call();

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog changelog = repo.getRevlog(clIdx, clDat);

        assertEquals(3, changelog.getRevisionCount());
        Revlog.IndexRecord mergeRec = changelog.getIndexRecord(2);
        int p1Rev = mergeRec.getParent1();
        int p2Rev = mergeRec.getParent2();

        assertArrayEquals(base, changelog.getIndexRecord(p1Rev).getNodeId());
        assertArrayEquals(feat, changelog.getIndexRecord(p2Rev).getNodeId());
    }

    @Test
    public void testLargeAndNonAsciiFileIntegration(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.resolve("large_non_ascii").toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        String nonAsciiPath = "한글 경로 테스트_디렉터리/안녕하세요_공백 테스트.txt";
        File nonAsciiFile = new File(repoDir, nonAsciiPath);
        nonAsciiFile.getParentFile().mkdirs();

        byte[] largeData = new byte[1024 * 1024];
        Arrays.fill(largeData, (byte) 'A');
        Files.write(nonAsciiFile.toPath(), largeData);

        new AddCommand(repo).call();
        byte[] commitNode = new CommitCommand(repo).setMessage("Large and non-ascii commit").call();

        Map<String, String> manifest = repo.getManifestAtCommit(commitNode);
        assertTrue(manifest.containsKey(nonAsciiPath), "Manifest must contain Hangul file path");

        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), nonAsciiPath);
        assertTrue(flIdx.exists());

        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        Revlog filelog = repo.getRevlog(flIdx, flDat);
        byte[] recovered = filelog.getRevisionContent(0);
        assertEquals(largeData.length, recovered.length);
        assertArrayEquals(largeData, recovered);

        TreeWalk tw = new TreeWalk();
        tw.addTree(new ManifestTreeIterator(repo, "0"));
        tw.addTree(new WorkingDirTreeIterator(repo));

        tw.reset();
        assertTrue(tw.next());
        assertEquals(nonAsciiPath, tw.getPath());
        assertTrue(tw.isTracked(0));
        assertTrue(tw.isTracked(1));
    }
}
