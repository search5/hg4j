package com.github.search5.hg4j.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.github.search5.hg4j.core.HgRepository;
import com.github.search5.hg4j.core.ChangegroupParser;
import com.github.search5.hg4j.util.SafeFileIO;
import com.github.search5.hg4j.core.Revlog;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.github.search5.hg4j.errors.HgAuthException;
import com.github.search5.hg4j.errors.HgProtocolException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class HgHttpTransportRoundtripTest {

    private HttpServer server;
    private int port;
    private volatile int responseCode = 200;
    private volatile boolean simulateDisconnect = false;
    private volatile boolean corruptStream = false;
    
    // Test data storage for success roundtrips
    private volatile byte[] mockBundleResponse = null;
    private volatile String mockHeadsResponse = null;
    private final AtomicReference<byte[]> capturedPushBundle = new AtomicReference<>();

    @BeforeEach
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        responseCode = 200;
        simulateDisconnect = false;
        corruptStream = false;
        mockBundleResponse = null;
        mockHeadsResponse = null;
        capturedPushBundle.set(null);

        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if (responseCode != 200) {
                    exchange.sendResponseHeaders(responseCode, 0);
                    exchange.close();
                    return;
                }

                String query = exchange.getRequestURI().getQuery();
                if (simulateDisconnect) {
                    exchange.close();
                    return;
                }

                if (corruptStream) {
                    byte[] garbage = "CORRUPTED_STREAM_GARBAGE_DATA".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, garbage.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(garbage);
                    }
                    return;
                }

                if (query != null && query.contains("cmd=capabilities")) {
                    String caps = "lookup changegroupsubsets branchmap getbundle\n";
                    byte[] resp = caps.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, resp.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(resp);
                    }
                } else if (query != null && query.contains("cmd=heads")) {
                    String heads = "0000000000000000000000000000000000000000\n";
                    if (mockHeadsResponse != null) {
                        heads = mockHeadsResponse;
                    }
                    byte[] resp = heads.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, resp.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(resp);
                    }
                } else if (query != null && (query.contains("cmd=getbundle") || query.contains("cmd=changegroup"))) {
                    byte[] resp = mockBundleResponse != null ? mockBundleResponse : new byte[0];
                    exchange.sendResponseHeaders(200, resp.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(resp);
                    }
                } else if (query != null && query.contains("cmd=unbundle")) {
                    try (java.io.InputStream is = exchange.getRequestBody()) {
                        byte[] body = is.readAllBytes();
                        capturedPushBundle.set(body);
                    }
                    byte[] resp = "0\nno errors\n".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, resp.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(resp);
                    }
                } else {
                    byte[] resp = "0\n".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, resp.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(resp);
                    }
                }
            }
        });
        server.start();
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void testHttp401ThrowsHgAuthException(@TempDir Path tempDir) throws Exception {
        responseCode = 401;
        
        File localDir = tempDir.resolve("local_repo").toFile();
        new InitCommand().setDirectory(localDir).call();
        HgRepository repository = new HgRepository(localDir);

        PullCommand pull = new PullCommand(repository)
                .setSource("http://127.0.0.1:" + port + "/");

        assertThrows(HgAuthException.class, pull::call, "401 error must throw HgAuthException");
    }

    @Test
    public void testHttp500ThrowsHgProtocolException(@TempDir Path tempDir) throws Exception {
        responseCode = 500;
        
        File localDir = tempDir.resolve("local_repo").toFile();
        new InitCommand().setDirectory(localDir).call();
        HgRepository repository = new HgRepository(localDir);

        PullCommand pull = new PullCommand(repository)
                .setSource("http://127.0.0.1:" + port + "/");

        assertThrows(HgProtocolException.class, pull::call, "500 error must throw HgProtocolException");
    }

    @Test
    public void testHttpDisconnectThrowsHgProtocolException(@TempDir Path tempDir) throws Exception {
        simulateDisconnect = true;

        File localDir = tempDir.resolve("local_repo").toFile();
        new InitCommand().setDirectory(localDir).call();
        HgRepository repository = new HgRepository(localDir);

        PullCommand pull = new PullCommand(repository)
                .setSource("http://127.0.0.1:" + port + "/");

        assertThrows(Exception.class, pull::call, "Disconnect must throw Exception");
    }

    @Test
    public void testCorruptedStreamThrowsException(@TempDir Path tempDir) throws Exception {
        corruptStream = true;

        File localDir = tempDir.resolve("local_repo").toFile();
        new InitCommand().setDirectory(localDir).call();
        HgRepository repository = new HgRepository(localDir);

        PullCommand pull = new PullCommand(repository)
                .setSource("http://127.0.0.1:" + port + "/");

        assertThrows(Exception.class, pull::call, "Corrupted/garbage headers/capabilities stream must fail with exception");
    }

    @Test
    public void testPullCommitsMatchExactly(@TempDir Path tempDir) throws Exception {
        // 1. Create a source repository (srcRepo) dynamically and commit changes
        File srcDir = tempDir.resolve("src_repo").toFile();
        new InitCommand().setDirectory(srcDir).call();
        HgRepository srcRepo = new HgRepository(srcDir);
        
        File file1 = new File(srcDir, "file1.txt");
        Files.writeString(file1.toPath(), "Content 1");
        new AddCommand(srcRepo).addFile("file1.txt").call();
        byte[] c1 = new CommitCommand(srcRepo).setAuthor("Tester <tester@example.com>").setMessage("Initial commit").call();
        
        Files.writeString(file1.toPath(), "Content 1 modified");
        byte[] c2 = new CommitCommand(srcRepo).setAuthor("Tester <tester@example.com>").setMessage("Second commit").call();
        
        Files.writeString(file1.toPath(), "Content 1 final");
        byte[] c3 = new CommitCommand(srcRepo).setAuthor("Tester <tester@example.com>").setMessage("Third commit").call();
        
        // 2. Serialize source repo to mock bundle
        ChangegroupParser.ChangegroupBundle bundle = com.github.search5.hg4j.HgTestUtils.createMockBundleFromRepo(srcRepo);
        byte[] rawCg = com.github.search5.hg4j.HgTestUtils.serializeBundleToBytes(bundle);
        
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write("HG10UN".getBytes(StandardCharsets.US_ASCII));
        bos.write(rawCg);
        mockBundleResponse = bos.toByteArray();
        mockHeadsResponse = NodeIdUtil.toHex(c3) + "\n";
        
        // 3. Perform pull command in local destination repository
        File destDir = tempDir.resolve("dest_repo").toFile();
        new InitCommand().setDirectory(destDir).call();
        HgRepository destRepo = new HgRepository(destDir);
        
        PullCommand pull = new PullCommand(destRepo)
                .setSource("http://127.0.0.1:" + port + "/");
        List<byte[]> pulledCommits = pull.call();
        
        // 4. Assert correctness
        assertEquals(3, pulledCommits.size());
        
        LogCommand log = new LogCommand(destRepo);
        List<HgCommit> commits = log.call();
        assertEquals(3, commits.size());
        assertEquals("Third commit", commits.get(0).getMessage());
        assertEquals("Second commit", commits.get(1).getMessage());
        assertEquals("Initial commit", commits.get(2).getMessage());
        assertEquals(NodeIdUtil.toHex(c3), commits.get(0).getNodeId().toHex());
    }

    @Test
    public void testPushBundleBytesAreValid(@TempDir Path tempDir) throws Exception {
        // 1. Create a local repository and commit changes
        File localDir = tempDir.resolve("local_repo").toFile();
        new InitCommand().setDirectory(localDir).call();
        HgRepository localRepo = new HgRepository(localDir);
        
        File file1 = new File(localDir, "hello.txt");
        Files.writeString(file1.toPath(), "Hello World");
        new AddCommand(localRepo).addFile("hello.txt").call();
        byte[] c1 = new CommitCommand(localRepo).setAuthor("Tester <tester@example.com>").setMessage("Push Commit").call();
        
        // 2. Perform push command
        PushCommand push = new PushCommand(localRepo)
                .setDestination("http://127.0.0.1:" + port + "/");
        push.call();
        
        // 3. Verify pushed bundle bytes
        byte[] pushedData = capturedPushBundle.get();
        assertNotNull(pushedData);
        assertTrue(pushedData.length > 6);
        
        String magic = new String(pushedData, 0, 6, StandardCharsets.US_ASCII);
        assertEquals("HG10UN", magic);
        
        byte[] cgBytes = new byte[pushedData.length - 6];
        System.arraycopy(pushedData, 6, cgBytes, 0, cgBytes.length);
        ChangegroupParser.ChangegroupBundle bundle = ChangegroupParser.parseBundle(new java.io.ByteArrayInputStream(cgBytes), "01");
        
        assertEquals(1, bundle.changelogEntries.size());
        assertArrayEquals(c1, bundle.changelogEntries.get(0).node);
    }
}
