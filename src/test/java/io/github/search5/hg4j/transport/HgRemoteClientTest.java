package io.github.search5.hg4j.transport;
import io.github.search5.hg4j.HgTestUtils;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.bundle.Bundle2Parser;
import io.github.search5.hg4j.bundle.ChangegroupParser;
import io.github.search5.hg4j.dirstate.Dirstate;

import org.junit.jupiter.api.Test;
import io.github.search5.hg4j.transport.HgRemoteClient;
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
import io.github.search5.hg4j.api.*;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import org.eclipse.jetty.server.Server;

import static org.junit.jupiter.api.Assertions.*;
import io.github.search5.hg4j.errors.HgCorruptDataException;
import io.github.search5.hg4j.errors.HgProtocolException;
import io.github.search5.hg4j.errors.HgTransportException;
import io.github.search5.hg4j.transport.wireprotov2.Cbor;
import io.github.search5.hg4j.transport.wireprotov2.Wire2Commands;
import io.github.search5.hg4j.transport.wireprotov2.Wire2Transport;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.zip.DeflaterOutputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;

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
        try (DeflaterOutputStream dos = new DeflaterOutputStream(compressedStream)) {
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
    public void testEncodeExtraKeyMatchesRealHg() {
        // 실제 hg(mercurial.changelog._string_escape/_string_unescape/decodeextra)로
        // 직접 확인(2026-09-01): 콜론은 전혀 이스케이프하지 않고 \, \n, \r, \0 만
        // 이스케이프하며, key:value 분리는 이스케이프 여부와 무관하게 "첫 번째 콜론"에서
        // 이루어진다(Python str.split(':', 1)와 동일).
        String value = "mybranch:with:colons\\and\\slashes";
        String encoded = CommitCommand.encodeExtraKey(value);
        assertEquals("mybranch:with:colons\\\\and\\\\slashes", encoded);
        assertFalse(encoded.contains("\\:"), "실제 hg는 콜론을 이스케이프하지 않는다");

        String decoded = CommitCommand.decodeExtraKey(encoded);
        assertEquals(value, decoded);

        String part = "branch:my:branch:with:colons";
        int idx = CommitCommand.findUnescapedColon(part);
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
        try (BZip2CompressorOutputStream bz2 =
             new BZip2CompressorOutputStream(bzOut)) {
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
        // 실제 스펙(mercurial/changegroup.py의 generatemanifests(): "if tree: yield
        // _fileheader(tree)") — 루트 매니페스트 그룹(tree == b'')은 경로 청크 없이 먼저
        // (여기서는 비어 있으므로 곧바로 자신의 종료 청크만) 온다. 이 픽스처는 예전엔 이
        // 루트 그룹을 아예 안 쓰고 "dir1" 경로 청크로 바로 시작했는데, 그건 실제 hg가
        // 만든 cg3/cg4/cg5 번들과 다른 구조였다(ChangegroupParser#parseBundle의
        // 2026-09-03 버그 수정 참고 — 실제 hg 바이트로 직접 확인).
        out.write(new byte[]{0, 0, 0, 0}); // empty root ("") manifest group

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
        // index 0 is always the bare root ("") group; "dir1" is the first (only) subdirectory group.
        assertEquals(2, bundle.manifestGroups.size());
        assertEquals("", bundle.manifestGroups.get(0).path);
        assertEquals(0, bundle.manifestGroups.get(0).entries.size());

        ChangegroupParser.ManifestGroup mg = bundle.manifestGroups.get(1);
        assertEquals("dir1", mg.path);
        assertEquals(1, mg.entries.size());
        
        ChangegroupParser.ChangeGroupEntry entry = mg.entries.get(0);
        assertEquals(30, entry.node[0]);
        assertEquals(50, entry.deltabase[0]);
        assertEquals(9, entry.flags);
        assertEquals("Delta", new String(entry.delta, StandardCharsets.US_ASCII));
    }

    private static void writeChunk(ByteArrayOutputStream out, byte[] payload) {
        int totalLen = payload.length + 4;
        out.write((totalLen >> 24) & 0xFF);
        out.write((totalLen >> 16) & 0xFF);
        out.write((totalLen >> 8) & 0xFF);
        out.write(totalLen & 0xFF);
        out.write(payload, 0, payload.length);
    }

    private static void writeInt(byte[] chunk, int offset, int value) {
        chunk[offset] = (byte) ((value >> 24) & 0xFF);
        chunk[offset + 1] = (byte) ((value >> 16) & 0xFF);
        chunk[offset + 2] = (byte) ((value >> 8) & 0xFF);
        chunk[offset + 3] = (byte) (value & 0xFF);
    }

    @Test
    public void testChangegroupParserSingleArgParseBundleDefaultsToCg1() throws Exception {
        // parseBundle(InputStream) is the convenience 1-arg overload used by ShelveCommand/IncomingCommand;
        // verify it defaults to cg1 parsing (80-byte header, no deltabase) end-to-end through a full bundle.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] cg1Chunk = new byte[80 + 3];
        cg1Chunk[0] = 7; // node
        System.arraycopy("abc".getBytes(StandardCharsets.US_ASCII), 0, cg1Chunk, 80, 3);
        writeChunk(out, cg1Chunk);
        out.write(new byte[]{0, 0, 0, 0}); // changelog terminal
        out.write(new byte[]{0, 0, 0, 0}); // manifest (empty)
        out.write(new byte[]{0, 0, 0, 0}); // file groups terminal

        ChangegroupParser.ChangegroupBundle bundle = ChangegroupParser.parseBundle(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(1, bundle.changelogEntries.size());
        assertNull(bundle.changelogEntries.get(0).deltabase);
        assertEquals(7, bundle.changelogEntries.get(0).node[0]);
        assertNotNull(bundle.manifestEntries);
        assertTrue(bundle.manifestEntries.isEmpty());
        assertTrue(bundle.fileGroups.isEmpty());
    }

    @Test
    public void testChangegroupParserMalformedHeaderChunkThrows() {
        // A first (and only) header chunk shorter than the required cg1 header size (80 bytes)
        // must be rejected as corrupt data rather than silently truncated/misread.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeChunk(out, new byte[]{1, 2, 3});

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        HgCorruptDataException ex = assertThrows(HgCorruptDataException.class, () -> ChangegroupParser.parseGroup(in));
        assertTrue(ex.getMessage().contains("Malformed changegroup header chunk"));
    }

    @Test
    public void testChangegroupParserAutoDetectsVersion02FromUnspecifiedHeader() throws Exception {
        // When version "01" is requested but the first chunk actually carries a plausible cg2 delta
        // header (offset 100) and NOT a plausible cg3 one (offset 102), auto-detection must upgrade
        // parsing to cg2 (100-byte header, deltabase present) rather than misreading it as cg1.
        byte[] chunk = new byte[100 + 12 + 5];
        chunk[0] = 11; // node
        chunk[60] = 22; // deltabase
        chunk[80] = 33; // cs
        writeInt(chunk, 100, 0);  // delta start
        writeInt(chunk, 104, 0);  // delta end
        writeInt(chunk, 108, 5);  // delta len == remaining bytes -> valid v2 header
        System.arraycopy("HELLO".getBytes(StandardCharsets.US_ASCII), 0, chunk, 112, 5);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeChunk(out, chunk);
        out.write(new byte[]{0, 0, 0, 0});

        String[] outVersion = new String[1];
        List<ChangegroupParser.ChangeGroupEntry> group =
                ChangegroupParser.parseGroup(new ByteArrayInputStream(out.toByteArray()), "01", outVersion);
        assertEquals("02", outVersion[0]);
        assertEquals(1, group.size());
        ChangegroupParser.ChangeGroupEntry entry = group.get(0);
        assertEquals(11, entry.node[0]);
        assertNotNull(entry.deltabase);
        assertEquals(22, entry.deltabase[0]);
        assertEquals(33, entry.cs[0]);
    }

    @Test
    public void testChangegroupParserAutoDetectsVersion03FromDeltaHeader() throws Exception {
        // A first chunk whose offset-102 window decodes into a structurally plausible delta
        // header (start<=end, len<=remaining) must be auto-detected as cg3 (102-byte header,
        // deltabase + flags present) even though "01" was requested.
        byte[] chunk = new byte[102 + 12 + 5];
        chunk[0] = 44; // node
        chunk[60] = 55; // deltabase
        chunk[80] = 66; // cs
        chunk[100] = 0; // flags high
        chunk[101] = 3; // flags low
        writeInt(chunk, 102, 0); // delta start
        writeInt(chunk, 106, 0); // delta end
        writeInt(chunk, 110, 5); // delta len == remaining bytes -> valid v3 header
        System.arraycopy("WORLD".getBytes(StandardCharsets.US_ASCII), 0, chunk, 114, 5);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeChunk(out, chunk);
        out.write(new byte[]{0, 0, 0, 0});

        String[] outVersion = new String[1];
        List<ChangegroupParser.ChangeGroupEntry> group =
                ChangegroupParser.parseGroup(new ByteArrayInputStream(out.toByteArray()), "01", outVersion);
        assertEquals("03", outVersion[0]);
        assertEquals(1, group.size());
        ChangegroupParser.ChangeGroupEntry entry = group.get(0);
        assertEquals(44, entry.node[0]);
        assertEquals(55, entry.deltabase[0]);
        assertEquals(66, entry.cs[0]);
        assertEquals(3, entry.flags);
        assertTrue(new String(entry.delta, StandardCharsets.US_ASCII).endsWith("WORLD"));
    }

    @Test
    public void testChangegroupParserAutoDetectFallsBackToCg1ForShortHeader() throws Exception {
        // A first chunk shorter than 80 bytes can never carry cg2/cg3 delta-header info, so
        // auto-detection must fall back to cg1 without attempting to inspect offsets 100/102 --
        // it is then rejected as a malformed cg1 header (too short for even the 80-byte minimum),
        // which is the only way to observe this branch from the public API without a valid header.
        byte[] shortChunk = new byte[40];
        shortChunk[0] = 9;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeChunk(out, shortChunk);
        out.write(new byte[]{0, 0, 0, 0});

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        HgCorruptDataException ex = assertThrows(HgCorruptDataException.class,
                () -> ChangegroupParser.parseGroup(in, "01", new String[1]));
        assertTrue(ex.getMessage().contains("Malformed changegroup header chunk"));
        assertTrue(ex.getMessage().contains("version: 01"));
    }

    @Test
    public void testChangegroupParserAutoDetectFallsBackToCg1WhenDeltaHeaderImplausible() throws Exception {
        // A chunk that is >=100 bytes but whose bytes at offset 100/102 do NOT decode into a
        // plausible (start<=end, len<=remaining) delta header must be treated as cg1, not
        // misidentified as cg2/cg3 just because it happens to be long enough.
        byte[] chunk = new byte[100 + 12];
        chunk[0] = 12;
        writeInt(chunk, 100, 500); // start
        writeInt(chunk, 104, 10);  // end < start -> implausible
        writeInt(chunk, 108, 0);   // len
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeChunk(out, chunk);
        out.write(new byte[]{0, 0, 0, 0});

        String[] outVersion = new String[1];
        List<ChangegroupParser.ChangeGroupEntry> group =
                ChangegroupParser.parseGroup(new ByteArrayInputStream(out.toByteArray()), "01", outVersion);
        assertEquals("01", outVersion[0]);
        assertEquals(1, group.size());
        assertNull(group.get(0).deltabase);
    }

    @Test
    public void testChangegroupParserAutoDetectRejectsNegativeStartOrEndFields() throws Exception {
        // A delta-header start/end field decoded as a negative int (top bit set) must never be
        // treated as plausible, however "in range" it might look after masking each byte to 0xFF.
        byte[] chunk = new byte[102 + 12 + 5];
        chunk[0] = 21;
        writeInt(chunk, 102, -1); // v3 window start: -1 -> start>=0 is false
        // These same bytes, read 2 bytes earlier as the v2 window's end field, also decode negative
        // (0xFFFF0000) while the v2 window's start field (bytes 100-103, mostly zero) stays >=0 --
        // exercising both the start>=0 false and (separately) end>=0 false branches in one chunk.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeChunk(out, chunk);
        out.write(new byte[]{0, 0, 0, 0});

        String[] outVersion = new String[1];
        List<ChangegroupParser.ChangeGroupEntry> group =
                ChangegroupParser.parseGroup(new ByteArrayInputStream(out.toByteArray()), "01", outVersion);
        assertEquals("01", outVersion[0]);
        assertEquals(1, group.size());
        assertNull(group.get(0).deltabase);
        assertEquals(21, group.get(0).node[0]);
    }

    @Test
    public void testChangegroupParserAutoDetectRejectsNegativeLenField() throws Exception {
        // A delta-header length field decoded as negative must be rejected outright, independent
        // of the start/end/remaining checks.
        byte[] chunk = new byte[102 + 12 + 5];
        chunk[0] = 22;
        writeInt(chunk, 102, 0);  // v3 window start
        writeInt(chunk, 106, 0);  // v3 window end
        writeInt(chunk, 110, -1); // v3 window len: -1 -> len>=0 is false
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeChunk(out, chunk);
        out.write(new byte[]{0, 0, 0, 0});

        String[] outVersion = new String[1];
        List<ChangegroupParser.ChangeGroupEntry> group =
                ChangegroupParser.parseGroup(new ByteArrayInputStream(out.toByteArray()), "01", outVersion);
        assertEquals("01", outVersion[0]);
        assertEquals(1, group.size());
        assertNull(group.get(0).deltabase);
        assertEquals(22, group.get(0).node[0]);
    }

    @Test
    public void testNewPorcelainCommandsE2E() throws Exception {
        // Initialize an isolated repository
        Path tempPath = Files.createTempDirectory("hg4j_porcelain_test_");
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
        HgTransportException ex = assertThrows(HgTransportException.class, () -> client.getHeads());
        assertTrue(ex.getMessage().contains("Malformed URL"));
    }

    @Test
    public void testUnwrapResponseStreamUnsupportedCompressionException() throws Exception {
        // application/mercurial-0.2
        // compNameLen = 7, compName = "brotli " -- a real compression algorithm hg4j does not
        // implement decoding for (unlike zlib/deflate/zstd/none, which are all supported).
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(7);
        out.write("brotli ".getBytes(StandardCharsets.US_ASCII));

        Method method = HgRemoteClient.class.getDeclaredMethod("unwrapResponseStream", InputStream.class, String.class);
        method.setAccessible(true);
        
        HgRemoteClient client = new HgRemoteClient("http://127.0.0.1/");
        InputStream inStream = new ByteArrayInputStream(out.toByteArray());
        
        assertThrows(HgProtocolException.class, () -> {
            try {
                method.invoke(client, inStream, "application/mercurial-0.2");
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    @Test
    public void testUnwrapResponseStreamUnexpectedEofHeaderException() throws Exception {
        // compNameLen = 10, but EOF immediately
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(10);

        Method method = HgRemoteClient.class.getDeclaredMethod("unwrapResponseStream", InputStream.class, String.class);
        method.setAccessible(true);
        
        HgRemoteClient client = new HgRemoteClient("http://127.0.0.1/");
        InputStream inStream = new ByteArrayInputStream(out.toByteArray());
        
        assertThrows(HgProtocolException.class, () -> {
            try {
                method.invoke(client, inStream, "application/mercurial-0.2");
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    @Test
    public void testV1HttpHeaderLimitNegotiationDoesNotFabricateV2Support() {
        HgRemoteClient client = new HgRemoteClient("http://127.0.0.1/");

        // A real v1 capabilities response never contains a "v2 available" token — earlier code
        // matched a fictional "http-v2"/"api-v2" flag here, which real hg never sends, so the
        // auto-upgrade could never actually trigger against a live server. Even if a capabilities
        // list happens to contain that (unrealistic) literal token, it must no longer be treated
        // as a v2 signal — only the real X-HgUpgrade-1/X-HgProto-1 handshake response can do that
        // (see below). httpheader=NNNN parsing is real and must still work.
        List<String> v1Caps = Arrays.asList("lookup", "changegroup=01,02", "httpheader=2048", "http-v2");
        boolean supportsV2 = client.negotiateV2(v1Caps);
        assertFalse(supportsV2, "A plain-text v1 capabilities token must never fabricate v2 support");
        assertEquals(2048, client.getMaxHttpHeaderLimit(), "Header limit should be correctly parsed as 2048");
    }

    @Test
    public void getCapabilitiesAutoUpgradesToV2WhenServerAdvertisesTheRealHandshake() throws Exception {
        HgRepository repo = Hg.init().setDirectory(Files.createTempDirectory("hg4j-v2-upgrade-server").toFile()).call();

        Server server = HgTestUtils.startServlet(new HgHttpWireServer(repo));
        try {
            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + HgTestUtils.port(server));
            List<String> caps = client.getCapabilities();

            // Real v2's command set (heads/known/listkeys/... — see HgHttpTransportV2RoundtripTest),
            // not the plain-text v1 tokens the fake handler above would have returned had the
            // upgrade not been detected — proves HgRemoteClient itself auto-upgraded.
            assertTrue(caps.contains("changesetdata"), "Auto-upgrade must have switched to the real v2 command set");
        } finally {
            HgTestUtils.stop(server);
        }
    }

    @Test
    public void getCapabilitiesFallsBackToV1WhenServerIgnoresTheUpgradeHeaders() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/", exchange -> {
                // A real v1-only server: always returns the plain-text v1 line, regardless of
                // any X-HgUpgrade-1/X-HgProto-1 headers the client may have sent.
                byte[] body = "lookup changegroupsubset httpheader=1024".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/mercurial-0.1");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();

            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort());
            List<String> caps = client.getCapabilities();

            assertTrue(caps.contains("lookup"));
            assertTrue(caps.contains("changegroupsubset"));
            assertEquals(1024, client.getMaxHttpHeaderLimit(),
                    "A garbled-as-CBOR v1 response must still fall back to correct plain-text v1 parsing");
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void supportsClonebundlesReflectsTheRealCapabilityToken() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/", exchange -> {
                byte[] body = "lookup clonebundles httpheader=1024".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();

            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort());
            client.getCapabilities();

            assertTrue(client.supportsClonebundles(), "Client must recognize the real 'clonebundles' capability token");
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void supportsClonebundlesIsFalseWhenTheServerDoesNotAdvertiseIt() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/", exchange -> {
                byte[] body = "lookup changegroupsubset".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();

            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort());
            client.getCapabilities();

            assertFalse(client.supportsClonebundles());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void fetchClonebundlesManifestReturnsTheServerFileVerbatim() throws Exception {
        String manifestBody = "https://example.com/bundle.hg BUNDLESPEC=none-v2\n";
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        try {
            server.createContext("/", exchange -> {
                String query = exchange.getRequestURI().getQuery();
                byte[] body;
                if (query != null && query.contains("cmd=clonebundles")) {
                    body = manifestBody.getBytes(StandardCharsets.UTF_8);
                } else {
                    body = "lookup clonebundles".getBytes(StandardCharsets.UTF_8);
                }
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();

            HgRemoteClient client = new HgRemoteClient("http://127.0.0.1:" + server.getAddress().getPort());
            String manifest = client.fetchClonebundlesManifest();

            assertEquals(manifestBody, manifest);
        } finally {
            server.stop(0);
        }
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
        HgRepository repository = new HgRepository(tempStore);
        Server server = HgTestUtils.startServlet(new HgHttpWireServer(repository));
        try {
            URL url = new URL("http://127.0.0.1:" + HgTestUtils.port(server) + "/?cmd=capabilities");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("X-HgUpgrade-1", "exp-http-v2-0003");
            conn.setRequestProperty("X-HgProto-1", "cbor");
            byte[] body;
            try (InputStream in = conn.getInputStream()) {
                body = in.readAllBytes();
            }

            // 실제 스펙(real Mercurial 6.0 서버로 확인, 2026-09-01): 캡ability 발견 응답은
            // {"apibase": "api/", "apis": {"exp-http-v2-0003": {"commands": {...},
            // "framingmediatypes": [...]}}, "v1capabilities": ...} 형태다 — 예전의 평면
            // {"commands": {...}} 는 실제 서버가 절대 만들지 않는 가짜 형태였다.
            List<Object> decoded = Cbor.decodeAll(body);
            Map<String, Object> resp = Cbor.asMap(decoded.get(0));
            assertEquals("api/", Cbor.asString(resp.get("apibase")));
            Map<String, Object> apis = Cbor.asMap(resp.get("apis"));
            Map<String, Object> descriptor = Cbor.asMap(
                    apis.get(Wire2Commands.NAMESPACE));
            assertNotNull(descriptor);
            Map<String, Object> commands = Cbor.asMap(descriptor.get("commands"));
            assertTrue(commands.containsKey("heads"));
            assertTrue(commands.containsKey("changesetdata"));
            List<String> framingTypes = new ArrayList<>();
            for (Object o : (List<?>) descriptor.get("framingmediatypes")) {
                framingTypes.add(Cbor.asString(o));
            }
            assertTrue(framingTypes.contains(Wire2Transport.FRAMINGTYPE));
        } finally {
            HgTestUtils.stop(server);
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

