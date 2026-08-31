package com.github.search5.hg4j.transport;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.bundle.Bundle2Parser;
import com.github.search5.hg4j.bundle.ChangegroupParser;
import com.github.search5.hg4j.dirstate.Dirstate;

import org.junit.jupiter.api.Test;
import com.github.search5.hg4j.transport.HgRemoteClient;
import com.github.search5.hg4j.transport.HgWireServer;
import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.github.search5.hg4j.api.*;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import static org.junit.jupiter.api.Assertions.*;

public class HgRemoteClientTest {

    @Test
    public void testChangegroupParserChunkBoundaries() throws Exception {
        // Prepare mock chunk: length (30) as big-endian 4-byte int, then 26-byte payload
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int payloadLen = 26;
        int totalLen = payloadLen + 4; // 30
        out.write((totalLen >> 24) & 0xFF);
        out.write((totalLen >> 16) & 0xFF);
        out.write((totalLen >> 8) & 0xFF);
        out.write(totalLen & 0xFF);
        
        byte[] mockPayload = "HelloWorldMockingPayload!!".getBytes(StandardCharsets.UTF_8);
        out.write(mockPayload);

        // Terminal chunk (length 0)
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(0);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        byte[] readPayload = ChangegroupParser.readChunk(in);
        
        assertNotNull(readPayload);
        assertArrayEquals(mockPayload, readPayload);

        // Next chunk should be terminal (null)
        byte[] terminalPayload = ChangegroupParser.readChunk(in);
        assertNull(terminalPayload);
    }

    @Test
    public void testChangegroupParserInvalidPayloadLength() {
        // High chunk length specification, but immediate EOF
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(100); // 100 total length -> 96 payload length

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        assertThrows(IOException.class, () -> ChangegroupParser.readChunk(in));
    }

    @Test
    public void testParseChangeGroupEntries() throws Exception {
        // Construct mock entry chunk (at least 80 bytes needed for metadata nodes)
        // Total length = 4 + 80 + 10 (delta payload) = 94
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int totalLen = 94;
        out.write((totalLen >> 24) & 0xFF);
        out.write((totalLen >> 16) & 0xFF);
        out.write((totalLen >> 8) & 0xFF);
        out.write(totalLen & 0xFF);

        // Write 80 bytes of mock nodes
        for (int i = 0; i < 4; i++) {
            byte[] mockNode = new byte[20];
            mockNode[0] = (byte) (i + 1);
            out.write(mockNode);
        }

        // Write 10 bytes of mock delta
        byte[] mockDelta = "MockDelta!".getBytes(StandardCharsets.UTF_8);
        out.write(mockDelta);

        // Terminal chunk
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(0);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        List<ChangegroupParser.ChangeGroupEntry> group = ChangegroupParser.parseGroup(in);

        assertEquals(1, group.size());
        ChangegroupParser.ChangeGroupEntry entry = group.get(0);
        assertEquals(1, entry.node[0]);
        assertEquals(2, entry.p1[0]);
        assertEquals(3, entry.p2[0]);
        assertEquals(4, entry.cs[0]);
        assertArrayEquals(mockDelta, entry.delta);
    }

    @Test
    public void testBundle2ParserExtractsChangegroupSuccessfully() throws Exception {
        // 1. Construct the raw payload to be compressed (Parts block)
        ByteArrayOutputStream rawPayload = new ByteArrayOutputStream();
        
        // --- Part 1: CHANGEGROUP ---
        // Header block: nameSize(1) + name(11) + partId(4) + counts(2) = 18 bytes
        int headerSize = 18;
        rawPayload.write((headerSize >> 24) & 0xFF);
        rawPayload.write((headerSize >> 16) & 0xFF);
        rawPayload.write((headerSize >> 8) & 0xFF);
        rawPayload.write(headerSize & 0xFF);
        
        rawPayload.write(11); // name size
        rawPayload.write("CHANGEGROUP".getBytes(StandardCharsets.US_ASCII));
        rawPayload.write(new byte[]{0, 0, 0, 1}); // partId = 1
        rawPayload.write(new byte[]{0, 0}); // mandatoryCount=0, advisoryCount=0
        
        // Chunks
        byte[] chunk1 = "FakeCGDataPayload".getBytes(StandardCharsets.UTF_8);
        int chunkSize = chunk1.length;
        rawPayload.write((chunkSize >> 24) & 0xFF);
        rawPayload.write((chunkSize >> 16) & 0xFF);
        rawPayload.write((chunkSize >> 8) & 0xFF);
        rawPayload.write(chunkSize & 0xFF);
        rawPayload.write(chunk1);
        
        // Chunk EOF
        rawPayload.write(new byte[]{0, 0, 0, 0});
        
        // --- Stream EOF (part header size 0) ---
        rawPayload.write(new byte[]{0, 0, 0, 0});
        
        byte[] uncompressedBytes = rawPayload.toByteArray();
        
        // Compress using zlib deflate
        ByteArrayOutputStream compressedStream = new ByteArrayOutputStream();
        try (java.util.zip.DeflaterOutputStream dos = new java.util.zip.DeflaterOutputStream(compressedStream)) {
            dos.write(uncompressedBytes);
        }
        byte[] compressedBytes = compressedStream.toByteArray();
        
        // 2. Assemble complete bundle2 stream: HG20 + paramsSize + params + compressedBytes
        ByteArrayOutputStream bundle2Out = new ByteArrayOutputStream();
        bundle2Out.write("HG20".getBytes(StandardCharsets.US_ASCII));
        
        byte[] params = "Compression=GZ".getBytes(StandardCharsets.UTF_8);
        int paramsLen = params.length;
        bundle2Out.write((paramsLen >> 24) & 0xFF);
        bundle2Out.write((paramsLen >> 16) & 0xFF);
        bundle2Out.write((paramsLen >> 8) & 0xFF);
        bundle2Out.write(paramsLen & 0xFF);
        bundle2Out.write(params);
        bundle2Out.write(compressedBytes);
        
        // 3. Extract and Verify
        byte[] result = Bundle2Parser.extractChangegroup(new ByteArrayInputStream(bundle2Out.toByteArray()));
        assertNotNull(result);
        assertEquals("FakeCGDataPayload", new String(result, StandardCharsets.UTF_8));
    }

    @Test
    public void testBundle2ParserInvalidMagic() {
        ByteArrayInputStream in = new ByteArrayInputStream("HG10invalid".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> Bundle2Parser.extractChangegroup(in));
    }

    @Test
    public void testBundle2ParserUnsupportedCompression() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write("HG20".getBytes(StandardCharsets.US_ASCII));
            byte[] params = "Compression=ZS".getBytes(StandardCharsets.UTF_8); // unsupported zstd
            int paramsLen = params.length;
            out.write((paramsLen >> 24) & 0xFF);
            out.write((paramsLen >> 16) & 0xFF);
            out.write((paramsLen >> 8) & 0xFF);
            out.write(paramsLen & 0xFF);
            out.write(params);
        } catch (IOException ignored) {}
        
        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        assertThrows(IOException.class, () -> Bundle2Parser.extractChangegroup(in));
    }

    @Test
    public void testBundle2ParserExtractsChangegroupDetailedCG2() throws Exception {
        // Construct bundle2 with 'version' parameter in CHANGEGROUP part
        ByteArrayOutputStream rawPayload = new ByteArrayOutputStream();
        
        // Header block size
        // partName = "CHANGEGROUP" (11 bytes)
        // partId = 1 (4 bytes)
        // mandatoryCount = 1 (1 byte)
        // advisoryCount = 0 (1 byte)
        // (keylen,vallen) pair for "version"/"02" (2 bytes)
        // paramName = "version" (7 bytes)
        // paramValue = "02" (2 bytes)
        // Total = 1 + 11 + 4 + 2 + 2 + 7 + 2 = 29 bytes
        int headerSize = 29;
        rawPayload.write((headerSize >> 24) & 0xFF);
        rawPayload.write((headerSize >> 16) & 0xFF);
        rawPayload.write((headerSize >> 8) & 0xFF);
        rawPayload.write(headerSize & 0xFF);

        rawPayload.write(11); // name size
        rawPayload.write("CHANGEGROUP".getBytes(StandardCharsets.US_ASCII));
        rawPayload.write(new byte[]{0, 0, 0, 1}); // partId = 1
        rawPayload.write(new byte[]{1, 0}); // mandatoryCount=1, advisoryCount=0

        // 실제 스펙: 먼저 (keylen,vallen) 쌍을 모두 쓴 다음, key/value 바이트를 순서대로 쓴다.
        rawPayload.write(7); // param name (key) length
        rawPayload.write(2); // param value length
        rawPayload.write("version".getBytes(StandardCharsets.US_ASCII));
        rawPayload.write("02".getBytes(StandardCharsets.US_ASCII));

        // Chunks
        byte[] chunk1 = "ExtractedCG2Data!".getBytes(StandardCharsets.UTF_8);
        int chunkSize = chunk1.length;
        rawPayload.write((chunkSize >> 24) & 0xFF);
        rawPayload.write((chunkSize >> 16) & 0xFF);
        rawPayload.write((chunkSize >> 8) & 0xFF);
        rawPayload.write(chunkSize & 0xFF);
        rawPayload.write(chunk1);
        
        // Chunk EOF
        rawPayload.write(new byte[]{0, 0, 0, 0});
        // Stream EOF
        rawPayload.write(new byte[]{0, 0, 0, 0});
        
        byte[] uncompressedBytes = rawPayload.toByteArray();
        
        // Assemble bundle2 stream without compression
        ByteArrayOutputStream bundle2Out = new ByteArrayOutputStream();
        bundle2Out.write("HG20".getBytes(StandardCharsets.US_ASCII));
        
        int paramsLen = 0;
        bundle2Out.write((paramsLen >> 24) & 0xFF);
        bundle2Out.write((paramsLen >> 16) & 0xFF);
        bundle2Out.write((paramsLen >> 8) & 0xFF);
        bundle2Out.write(paramsLen & 0xFF);
        bundle2Out.write(uncompressedBytes);

        // Extract and Verify
        Bundle2Parser.ExtractedBundle2 result = Bundle2Parser.extractChangegroupDetailed(new ByteArrayInputStream(bundle2Out.toByteArray()));
        assertNotNull(result);
        assertEquals("02", result.cgVersion);
        assertEquals("ExtractedCG2Data!", new String(result.changegroupBytes, StandardCharsets.UTF_8));
    }

    @Test
    public void testChangegroupParserHandlesCG2AndCG3Headers() throws Exception {
        // CG2 Entry header size: 100 bytes (node(20) + p1(20) + p2(20) + deltabase(20) + cs(20))
        // 실제 스펙(mercurial/changegroup.py _CHANGEGROUPV2_DELTA_HEADER): deltabase가
        // cs보다 앞에 온다 (node,p1,p2,deltabase,cs).
        byte[] cg2Chunk = new byte[100 + 10]; // header(100) + delta(10)
        // fill node, p1, p2, deltabase, cs with distinctive values
        cg2Chunk[0] = 10;  // node
        cg2Chunk[20] = 20; // p1
        cg2Chunk[40] = 30; // p2
        cg2Chunk[60] = 50; // deltabase
        cg2Chunk[80] = 40; // cs
        System.arraycopy("MockCG2Dlt".getBytes(StandardCharsets.US_ASCII), 0, cg2Chunk, 100, 10);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int totalLen = cg2Chunk.length + 4;
        out.write((totalLen >> 24) & 0xFF);
        out.write((totalLen >> 16) & 0xFF);
        out.write((totalLen >> 8) & 0xFF);
        out.write(totalLen & 0xFF);
        out.write(cg2Chunk);
        // Terminal
        out.write(new byte[]{0, 0, 0, 0});

        List<ChangegroupParser.ChangeGroupEntry> group = ChangegroupParser.parseGroup(new ByteArrayInputStream(out.toByteArray()), "02");
        assertEquals(1, group.size());
        ChangegroupParser.ChangeGroupEntry entry = group.get(0);
        assertEquals(10, entry.node[0]);
        assertEquals(20, entry.p1[0]);
        assertEquals(30, entry.p2[0]);
        assertEquals(40, entry.cs[0]);
        assertNotNull(entry.deltabase);
        assertEquals(50, entry.deltabase[0]);
        assertEquals("MockCG2Dlt", new String(entry.delta, StandardCharsets.US_ASCII));
    }

    @Test
    public void testMercurialChunkedInputStreamUnwrapsCorrectly() throws Exception {
        // Construct application/mercurial-0.2 chunk transfer stream
        // 1st chunk: 5 bytes "Hello"
        // 2nd chunk: 5 bytes "World"
        // 3rd chunk: 0 bytes (terminal)
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        
        // Chunk 1
        out.write(new byte[]{0, 0, 0, 5});
        out.write("Hello".getBytes(StandardCharsets.US_ASCII));
        // Chunk 2
        out.write(new byte[]{0, 0, 0, 5});
        out.write("World".getBytes(StandardCharsets.US_ASCII));
        // Chunk 3
        out.write(new byte[]{0, 0, 0, 0});
        
        // Create unwrapper
        java.lang.reflect.Constructor<?> constructor = Class.forName("com.github.search5.hg4j.transport.HgRemoteClient$MercurialChunkedInputStream")
                .getDeclaredConstructor(InputStream.class);
        constructor.setAccessible(true);
        InputStream chunkedStream = (InputStream) constructor.newInstance(new ByteArrayInputStream(out.toByteArray()));
        
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        byte[] buf = new byte[4];
        int count;
        while ((count = chunkedStream.read(buf)) != -1) {
            decoded.write(buf, 0, count);
        }
        
        assertEquals("HelloWorld", new String(decoded.toByteArray(), StandardCharsets.US_ASCII));
    }

    @Test
    public void testEncodeExtraKeyMatchesRealHg() {
        // 실제 hg(mercurial.changelog._string_escape/_string_unescape/decodeextra)로
        // 직접 확인(2026-09-01): 콜론은 전혀 이스케이프하지 않고 \, \n, \r, \0 만
        // 이스케이프하며, key:value 분리는 이스케이프 여부와 무관하게 "첫 번째 콜론"에서
        // 이루어진다(Python str.split(':', 1)와 동일).
        String value = "mybranch:with:colons\\and\\slashes";
        String encoded = com.github.search5.hg4j.api.CommitCommand.encodeExtraKey(value);
        assertEquals("mybranch:with:colons\\\\and\\\\slashes", encoded);
        assertFalse(encoded.contains("\\:"), "실제 hg는 콜론을 이스케이프하지 않는다");

        String decoded = com.github.search5.hg4j.api.CommitCommand.decodeExtraKey(encoded);
        assertEquals(value, decoded);

        String part = "branch:my:branch:with:colons";
        int idx = com.github.search5.hg4j.api.CommitCommand.findUnescapedColon(part);
        assertEquals("branch".length(), idx);
    }

    @Test
    public void testBZandZSPayloadExtraction() throws Exception {
        // Test BZip2 extraction
        ByteArrayOutputStream rawPayload = new ByteArrayOutputStream();
        
        // Let's write a simple CHANGEGROUP part in raw payload
        int headerSize = 18;
        rawPayload.write((headerSize >> 24) & 0xFF);
        rawPayload.write((headerSize >> 16) & 0xFF);
        rawPayload.write((headerSize >> 8) & 0xFF);
        rawPayload.write(headerSize & 0xFF);
        rawPayload.write(11);
        rawPayload.write("CHANGEGROUP".getBytes(StandardCharsets.US_ASCII));
        rawPayload.write(new byte[]{0, 0, 0, 1});
        rawPayload.write(new byte[]{0, 0});
        
        byte[] chunkData = "Bzip2CompressedChangegroupData".getBytes(StandardCharsets.UTF_8);
        rawPayload.write((chunkData.length >> 24) & 0xFF);
        rawPayload.write((chunkData.length >> 16) & 0xFF);
        rawPayload.write((chunkData.length >> 8) & 0xFF);
        rawPayload.write(chunkData.length & 0xFF);
        rawPayload.write(chunkData);
        rawPayload.write(new byte[]{0, 0, 0, 0}); // chunks EOF
        rawPayload.write(new byte[]{0, 0, 0, 0}); // parts EOF

        byte[] bzBytes;
        ByteArrayOutputStream bzOut = new ByteArrayOutputStream();
        try (org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream bz2 =
             new org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream(bzOut)) {
            bz2.write(rawPayload.toByteArray());
        }
        bzBytes = bzOut.toByteArray();

        // Assemble HG20 + Compression=BZ + bzBytes
        ByteArrayOutputStream bundle2Out = new ByteArrayOutputStream();
        bundle2Out.write("HG20".getBytes(StandardCharsets.US_ASCII));
        byte[] params = "Compression=BZ".getBytes(StandardCharsets.UTF_8);
        bundle2Out.write((params.length >> 24) & 0xFF);
        bundle2Out.write((params.length >> 16) & 0xFF);
        bundle2Out.write((params.length >> 8) & 0xFF);
        bundle2Out.write(params.length & 0xFF);
        bundle2Out.write(params);
        bundle2Out.write(bzBytes);

        byte[] extracted = Bundle2Parser.extractChangegroup(new ByteArrayInputStream(bundle2Out.toByteArray()));
        assertEquals("Bzip2CompressedChangegroupData", new String(extracted, StandardCharsets.UTF_8));
    }

    @Test
    public void testCg3TreeManifestParsing() throws Exception {
        // CG3 format mock stream (manifestGroups parsing)
        // changelog (empty)
        // manifestGroups:
        //   - path chunk: "dir1"
        //   - entries: 1 entry with 102 bytes cg3 header
        //   - path chunk: null (EOF of manifestGroups)
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        
        // 1. Changelog Entries (empty collection, just length 0 terminal chunk)
        out.write(new byte[]{0, 0, 0, 0});
        
        // 2. Manifest Groups
        // Dir path chunk "dir1"
        byte[] pathBytes = "dir1".getBytes(StandardCharsets.UTF_8);
        int pathLen = pathBytes.length + 4;
        out.write((pathLen >> 24) & 0xFF);
        out.write((pathLen >> 16) & 0xFF);
        out.write((pathLen >> 8) & 0xFF);
        out.write(pathLen & 0xFF);
        out.write(pathBytes);
        
        // Group entries: 1 entry of cg3 (102 bytes header + 5 bytes delta)
        byte[] cg3Chunk = new byte[102 + 5];
        cg3Chunk[0] = 30; // node
        cg3Chunk[60] = 50; // deltabase (node,p1,p2,deltabase,cs,flags 순서)
        cg3Chunk[100] = 0; // flags high
        cg3Chunk[101] = 9; // flags low
        System.arraycopy("Delta".getBytes(StandardCharsets.US_ASCII), 0, cg3Chunk, 102, 5);
        
        int entryChunkLen = cg3Chunk.length + 4;
        out.write((entryChunkLen >> 24) & 0xFF);
        out.write((entryChunkLen >> 16) & 0xFF);
        out.write((entryChunkLen >> 8) & 0xFF);
        out.write(entryChunkLen & 0xFF);
        out.write(cg3Chunk);
        
        // Group terminal
        out.write(new byte[]{0, 0, 0, 0});
        
        // Manifest groups terminal (null path chunk)
        out.write(new byte[]{0, 0, 0, 0});
        
        // 3. File Groups terminal
        out.write(new byte[]{0, 0, 0, 0});

        ChangegroupParser.ChangegroupBundle bundle = ChangegroupParser.parseBundle(new ByteArrayInputStream(out.toByteArray()), "03");
        assertNotNull(bundle);
        assertNotNull(bundle.manifestGroups);
        assertEquals(1, bundle.manifestGroups.size());
        
        ChangegroupParser.ManifestGroup mg = bundle.manifestGroups.get(0);
        assertEquals("dir1", mg.path);
        assertEquals(1, mg.entries.size());
        
        ChangegroupParser.ChangeGroupEntry entry = mg.entries.get(0);
        assertEquals(30, entry.node[0]);
        assertEquals(50, entry.deltabase[0]);
        assertEquals(9, entry.flags);
        assertEquals("Delta", new String(entry.delta, StandardCharsets.US_ASCII));
    }

    @Test
    public void testNewPorcelainCommandsE2E() throws Exception {
        // Initialize an isolated repository
        java.nio.file.Path tempPath = Files.createTempDirectory("hg4j_porcelain_test_");
        File tempRepoDir = tempPath.toFile();
        try {
            HgRepository repo = Hg.init().setDirectory(tempRepoDir).call();
            assertNotNull(repo);

            // Write first version of file
            File testFile = new File(tempRepoDir, "sample.txt");
            Files.writeString(testFile.toPath(), "Version1_Content", StandardCharsets.UTF_8);
            
            new AddCommand(repo).addFile("sample.txt").call();
            byte[] rev0Node = new CommitCommand(repo).setAuthor("tester <test@example.com>").setMessage("Commit 1").call();

            // Write second version of file
            Files.writeString(testFile.toPath(), "Version2_Content", StandardCharsets.UTF_8);
            byte[] rev1Node = new CommitCommand(repo).setAuthor("tester <test@example.com>").setMessage("Commit 2").call();

            // Write third version with new file
            File anotherFile = new File(tempRepoDir, "another.txt");
            Files.writeString(anotherFile.toPath(), "Another_Content", StandardCharsets.UTF_8);
            new AddCommand(repo).addFile("another.txt").call();
            byte[] rev2Node = new CommitCommand(repo).setAuthor("tester <test@example.com>").setMessage("Commit 3").call();

            // 1. Verify CatCommand
            byte[] catContent = new CatCommand(repo).setFile("sample.txt").setRevision("0").call();
            assertEquals("Version1_Content", new String(catContent, StandardCharsets.UTF_8));

            // 2. Verify UpdateCommand (checkout to revision 1)
            byte[] updatedNode = new UpdateCommand(repo).setRevision("1").call();
            assertArrayEquals(rev1Node, updatedNode);
            assertEquals("Version2_Content", Files.readString(testFile.toPath(), StandardCharsets.UTF_8));
            // another.txt should not exist at rev 1
            assertFalse(anotherFile.exists());

            // 3. Verify RevertCommand
            new RevertCommand(repo).setFile("sample.txt").setRevision("0").call();
            assertEquals("Version1_Content", Files.readString(testFile.toPath(), StandardCharsets.UTF_8));

            // 4. Verify RemoveCommand
            new RemoveCommand(repo).setFile("sample.txt").setForce(true).call();
            assertFalse(testFile.exists());
            Dirstate dirstate = repo.getDirstate();
            assertEquals('r', dirstate.getEntries().get("sample.txt").getState());
            
        } finally {
            // Clean up temporary directories
            deleteDirRecursively(tempRepoDir);
        }
    }

    @Test
    public void testPushCommandMock() throws Exception {
        File tempRepoDir = Files.createTempDirectory("hg4j_push_test_").toFile();
        try {
            HgRepository repo = Hg.init().setDirectory(tempRepoDir).call();
            File testFile = new File(tempRepoDir, "sample.txt");
            Files.writeString(testFile.toPath(), "Content", StandardCharsets.UTF_8);
            new AddCommand(repo).addFile("sample.txt").call();
            new CommitCommand(repo).setAuthor("tester <test@example.com>").setMessage("Commit").call();

            PushCommand push = new PushCommand(repo);
            // Verify exception is thrown if destination is not specified
            assertThrows(IllegalStateException.class, () -> push.call());
        } finally {
            deleteDirRecursively(tempRepoDir);
        }
    }

    @Test
    public void testHgRemoteClientNetworkOptions() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        
        final boolean[] headsCalled = {false};
        final String[] authHeader = {null};

        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                headsCalled[0] = true;
                authHeader[0] = exchange.getRequestHeaders().getFirst("Authorization");

                String response = "remotehead1234567890\n";
                byte[] respBytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, respBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(respBytes);
                }
            }
        });

        server.start();
        int port = server.getAddress().getPort();

        try {
            String url = "http://127.0.0.1:" + port + "/";
            HgRemoteClient client = new HgRemoteClient(url);
            
            // Set options
            client.setTimeouts(5000, 5000);
            client.setCredentials("user", "pass");
            client.setProxy(Proxy.NO_PROXY);
            client.setForceTls(false);

            List<String> heads = client.getHeads();
            assertNotNull(heads);
            assertEquals(1, heads.size());
            assertEquals("remotehead1234567890", heads.get(0));

            assertTrue(headsCalled[0]);
            assertNotNull(authHeader[0]);
            assertTrue(authHeader[0].startsWith("Basic "));
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void testHgRemoteClientTlsCheckAndErrors() {
        HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:9999/hg");
        client.setForceTls(true);

        // SecurityException due to forceTls on non-https url
        assertThrows(SecurityException.class, () -> client.getHeads());
        
        // Malformed URL check
        HgRemoteClient invalidUrlClient = new HgRemoteClient("invalid_url_protocol://foo");
        assertThrows(IOException.class, () -> invalidUrlClient.getHeads());
    }

    private void deleteDirRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirRecursively(child);
                }
            }
        }
        file.delete();
    }

    @Test
    public void testHgRemoteClientMalformedUrlException() {
        HgRemoteClient client = new HgRemoteClient("http://[invalid-url");
        com.github.search5.hg4j.errors.HgTransportException ex = assertThrows(com.github.search5.hg4j.errors.HgTransportException.class, () -> client.getHeads());
        assertTrue(ex.getMessage().contains("Malformed URL"));
    }

    @Test
    public void testMercurialChunkedInputStreamNegativeLengthException() throws Exception {
        // Chunk length is negative (-100 => 0xFF 0xFF 0xFF 0x9C)
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xFF);
        out.write(0xFF);
        out.write(0x9C);

        java.lang.reflect.Constructor<?> constructor = Class.forName("com.github.search5.hg4j.transport.HgRemoteClient$MercurialChunkedInputStream")
                .getDeclaredConstructor(InputStream.class);
        constructor.setAccessible(true);
        InputStream chunkedStream = (InputStream) constructor.newInstance(new ByteArrayInputStream(out.toByteArray()));

        assertThrows(com.github.search5.hg4j.errors.HgProtocolException.class, () -> chunkedStream.read());
    }

    @Test
    public void testMercurialChunkedInputStreamUnexpectedEofLengthException() throws Exception {
        // Chunk length is only 2 bytes instead of 4
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);
        out.write(10);

        java.lang.reflect.Constructor<?> constructor = Class.forName("com.github.search5.hg4j.transport.HgRemoteClient$MercurialChunkedInputStream")
                .getDeclaredConstructor(InputStream.class);
        constructor.setAccessible(true);
        InputStream chunkedStream = (InputStream) constructor.newInstance(new ByteArrayInputStream(out.toByteArray()));

        assertThrows(com.github.search5.hg4j.errors.HgProtocolException.class, () -> chunkedStream.read());
    }

    @Test
    public void testMercurialChunkedInputStreamUnexpectedEofPayloadException() throws Exception {
        // Chunk length is 10, but stream ends immediately
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(10);

        java.lang.reflect.Constructor<?> constructor = Class.forName("com.github.search5.hg4j.transport.HgRemoteClient$MercurialChunkedInputStream")
                .getDeclaredConstructor(InputStream.class);
        constructor.setAccessible(true);
        InputStream chunkedStream = (InputStream) constructor.newInstance(new ByteArrayInputStream(out.toByteArray()));

        assertThrows(com.github.search5.hg4j.errors.HgProtocolException.class, () -> chunkedStream.read());
    }

    @Test
    public void testUnwrapResponseStreamUnsupportedCompressionException() throws Exception {
        // application/mercurial-0.2
        // compNameLen = 5, compName = "zstd "
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(5);
        out.write("zstd ".getBytes(StandardCharsets.US_ASCII));

        java.lang.reflect.Method method = HgRemoteClient.class.getDeclaredMethod("unwrapResponseStream", InputStream.class, String.class);
        method.setAccessible(true);
        
        HgRemoteClient client = new HgRemoteClient("http://127.0.0.1/");
        InputStream inStream = new ByteArrayInputStream(out.toByteArray());
        
        assertThrows(com.github.search5.hg4j.errors.HgProtocolException.class, () -> {
            try {
                method.invoke(client, inStream, "application/mercurial-0.2");
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    @Test
    public void testUnwrapResponseStreamUnexpectedEofHeaderException() throws Exception {
        // compNameLen = 10, but EOF immediately
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(10);

        java.lang.reflect.Method method = HgRemoteClient.class.getDeclaredMethod("unwrapResponseStream", InputStream.class, String.class);
        method.setAccessible(true);
        
        HgRemoteClient client = new HgRemoteClient("http://127.0.0.1/");
        InputStream inStream = new ByteArrayInputStream(out.toByteArray());
        
        assertThrows(com.github.search5.hg4j.errors.HgProtocolException.class, () -> {
            try {
                method.invoke(client, inStream, "application/mercurial-0.2");
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    @Test
    public void testV2HandshakeNegotiation() {
        HgRemoteClient client = new HgRemoteClient("http://127.0.0.1/");
        
        List<String> v1Caps = Arrays.asList("lookup", "changegroup=01,02", "httpheader=2048", "http-v2");
        
        // v2 capability 협상 여부 및 header limit 해석 검증
        boolean supportsV2 = client.negotiateV2(v1Caps);
        assertTrue(supportsV2, "Client should negotiate and determine that remote server supports v2");
        assertEquals(2048, client.getMaxHttpHeaderLimit(), "Header limit should be correctly parsed as 2048");
    }

    // 이전에는 여기서 /api/capabilities, /api/heads라는 가짜(fictional) 평면 라우팅을 손으로
    // mock하는 테스트가 있었다 — 실제 Mercurial 6.0 서버로 직접 검증한 결과 실제 v2는 이런
    // 라우팅을 전혀 쓰지 않는다(캡ability 발견 핸드셰이크 + /api/<namespace>/<ro|rw>/<command>
    // 프레임 기반 전송). 손으로 만든 mock은 실제 서버가 바뀌어도 계속 통과하는(즉, 실제로는
    // 아무것도 검증하지 못하는) 자기참조적 테스트가 되기 쉬워 제거했다 — 같은 경로는 이제
    // HgHttpTransportV2RoundtripTest가 진짜 HgWireServer 구현으로 대체 검증한다
    // (2026-09-01, 실제 hg 6.0 도커 서버로 캡ability/heads/known/listkeys/pushkey/changesetdata/
    // manifestdata/filesdata까지 왕복 검증 완료).

    @Test
    public void testCborFrameParserStreaming() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // CBOR 포맷으로 여러 개별 객체들을 연속으로 씁니다
        out.write(mapper.writeValueAsBytes("frame1"));
        out.write(mapper.writeValueAsBytes("frame2"));
        out.write(mapper.writeValueAsBytes(100));

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        CborFrameParser parser = new CborFrameParser();
        List<Object> frames = parser.parseFrames(in);

        assertEquals(3, frames.size());
        assertEquals("frame1", frames.get(0));
        assertEquals("frame2", frames.get(1));
        assertEquals(100, frames.get(2));
    }

    @Test
    public void testFactoryProtocolFallbackAndNegotiation() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();

        // capabilities handshake에서 v1 응답만 주는 Mock 핸들러
        server.createContext("/capabilities", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String response = "lookup changegroup\n";
                exchange.sendResponseHeaders(200, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }
            }
        });

        server.start();

        try {
            // 1. v2 미지원 서버 -> V1 클라이언트로 폴백되어야 함
            HgRemoteConnection conn = HgRemoteConnectionFactory.createConnection("http://127.0.0.1:" + port);
            assertTrue(conn instanceof HgRemoteClient, "Should fallback to HgRemoteClient when v2 is not supported");
        } finally {
            server.stop(0);
        }
     }

    @Test
    public void testHgWireServerV2Integration() throws Exception {
        File tempStore = Files.createTempDirectory("hg4j_server_v2").toFile();
        tempStore.deleteOnExit();
        try {
            HgRepository repository = new HgRepository(tempStore);
            HgWireServer server = new HgWireServer(repository);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            server.handleCapabilitiesDiscovery("", out);

            // 실제 스펙(real Mercurial 6.0 서버로 확인, 2026-09-01): 캡ability 발견 응답은
            // {"apibase": "api/", "apis": {"exp-http-v2-0003": {"commands": {...},
            // "framingmediatypes": [...]}}, "v1capabilities": ...} 형태다 — 예전의 평면
            // {"commands": {...}} 는 실제 서버가 절대 만들지 않는 가짜 형태였다.
            java.util.List<Object> decoded = com.github.search5.hg4j.transport.wireprotov2.Cbor.decodeAll(out.toByteArray());
            Map<String, Object> resp = com.github.search5.hg4j.transport.wireprotov2.Cbor.asMap(decoded.get(0));
            assertEquals("api/", com.github.search5.hg4j.transport.wireprotov2.Cbor.asString(resp.get("apibase")));
            Map<String, Object> apis = com.github.search5.hg4j.transport.wireprotov2.Cbor.asMap(resp.get("apis"));
            Map<String, Object> descriptor = com.github.search5.hg4j.transport.wireprotov2.Cbor.asMap(
                    apis.get(com.github.search5.hg4j.transport.wireprotov2.Wire2Commands.NAMESPACE));
            assertNotNull(descriptor);
            Map<String, Object> commands = com.github.search5.hg4j.transport.wireprotov2.Cbor.asMap(descriptor.get("commands"));
            assertTrue(commands.containsKey("heads"));
            assertTrue(commands.containsKey("changesetdata"));
            List<String> framingTypes = new java.util.ArrayList<>();
            for (Object o : (List<?>) descriptor.get("framingmediatypes")) {
                framingTypes.add(com.github.search5.hg4j.transport.wireprotov2.Cbor.asString(o));
            }
            assertTrue(framingTypes.contains(com.github.search5.hg4j.transport.wireprotov2.Wire2Transport.FRAMINGTYPE));
        } finally {
            deleteRecursive(tempStore);
        }
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    deleteRecursive(f);
                }
            }
        }
        file.delete();
    }
}

