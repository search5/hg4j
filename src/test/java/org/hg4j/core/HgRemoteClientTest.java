package org.hg4j.core;

import org.junit.jupiter.api.Test;
import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.hg4j.api.*;

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
        // paramName = "version" (7 bytes)
        // paramValue = "02" (2 bytes)
        // Total = 1 + 11 + 4 + 2 + 1 + 7 + 1 + 2 = 29 bytes
        int headerSize = 29;
        rawPayload.write((headerSize >> 24) & 0xFF);
        rawPayload.write((headerSize >> 16) & 0xFF);
        rawPayload.write((headerSize >> 8) & 0xFF);
        rawPayload.write(headerSize & 0xFF);
        
        rawPayload.write(11); // name size
        rawPayload.write("CHANGEGROUP".getBytes(StandardCharsets.US_ASCII));
        rawPayload.write(new byte[]{0, 0, 0, 1}); // partId = 1
        rawPayload.write(new byte[]{1, 0}); // mandatoryCount=1, advisoryCount=0
        
        // Parameter: version=02
        rawPayload.write(7); // param name size
        rawPayload.write("version".getBytes(StandardCharsets.US_ASCII));
        rawPayload.write(2); // param value size
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
        // CG2 Entry header size: 100 bytes (node(20) + p1(20) + p2(20) + cs(20) + deltabase(20))
        byte[] cg2Chunk = new byte[100 + 10]; // header(100) + delta(10)
        // fill node, p1, p2, cs, deltabase with distinctive values
        cg2Chunk[0] = 10;  // node
        cg2Chunk[20] = 20; // p1
        cg2Chunk[40] = 30; // p2
        cg2Chunk[60] = 40; // cs
        cg2Chunk[80] = 50; // deltabase
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
        java.lang.reflect.Constructor<?> constructor = Class.forName("org.hg4j.core.HgRemoteClient$MercurialChunkedInputStream")
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
    public void testEncodeExtraKeyColons() {
        String testInput = "mybranch:with:colons\\and\\slashes";
        String encoded = org.hg4j.api.CommitCommand.encodeExtraKey(testInput);
        assertTrue(encoded.contains("\\:"));
        assertTrue(encoded.contains("\\\\"));
        
        String decoded = org.hg4j.api.CommitCommand.decodeExtraKey(encoded);
        assertEquals(testInput, decoded);

        // Test unescaped colon parsing
        String key = "my\\:branch";
        String val = "some\\:value";
        String part = key + ":" + val;
        
        int unescapedIdx = org.hg4j.api.CommitCommand.findUnescapedColon(part);
        assertEquals(key.length(), unescapedIdx); // must split precisely at the middle colon
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
        cg3Chunk[80] = 50; // deltabase
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
            
            Hg.add(repo).addFile("sample.txt").call();
            byte[] rev0Node = Hg.commit(repo).setAuthor("tester <test@example.com>").setMessage("Commit 1").call();

            // Write second version of file
            Files.writeString(testFile.toPath(), "Version2_Content", StandardCharsets.UTF_8);
            byte[] rev1Node = Hg.commit(repo).setAuthor("tester <test@example.com>").setMessage("Commit 2").call();

            // Write third version with new file
            File anotherFile = new File(tempRepoDir, "another.txt");
            Files.writeString(anotherFile.toPath(), "Another_Content", StandardCharsets.UTF_8);
            Hg.add(repo).addFile("another.txt").call();
            byte[] rev2Node = Hg.commit(repo).setAuthor("tester <test@example.com>").setMessage("Commit 3").call();

            // 1. Verify CatCommand
            byte[] catContent = Hg.cat(repo).setFile("sample.txt").setRevision("0").call();
            assertEquals("Version1_Content", new String(catContent, StandardCharsets.UTF_8));

            // 2. Verify UpdateCommand (checkout to revision 1)
            byte[] updatedNode = Hg.update(repo).setRevision("1").call();
            assertArrayEquals(rev1Node, updatedNode);
            assertEquals("Version2_Content", Files.readString(testFile.toPath(), StandardCharsets.UTF_8));
            // another.txt should not exist at rev 1
            assertFalse(anotherFile.exists());

            // 3. Verify RevertCommand
            Hg.revert(repo).setFile("sample.txt").setRevision("0").call();
            assertEquals("Version1_Content", Files.readString(testFile.toPath(), StandardCharsets.UTF_8));

            // 4. Verify RemoveCommand
            Hg.remove(repo).setFile("sample.txt").call();
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
            Hg.add(repo).addFile("sample.txt").call();
            Hg.commit(repo).setAuthor("tester <test@example.com>").setMessage("Commit").call();

            PushCommand push = new PushCommand(repo);
            // Verify exception is thrown if destination is not specified
            assertThrows(IllegalStateException.class, () -> push.call());
        } finally {
            deleteDirRecursively(tempRepoDir);
        }
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
}
