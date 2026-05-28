package org.hg4j.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Core implementation for Mercurial Revlog (index .i and data .d files).
 */
public class Revlog {

    private final File idxFile;
    private final File datFile;
    private final List<IndexRecord> records = new ArrayList<>();
    private final java.util.Map<java.nio.ByteBuffer, Integer> nodeMap = new java.util.HashMap<>();
    private boolean inline = false;



    // 인메모리 LRU 리비전 컨텐트 캐시 (최대 100개)
    private final java.util.Map<Integer, byte[]> contentCache = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<Integer, byte[]> eldest) {
            return size() > 100;
        }
    };

    public record IndexRecord(int revision, long offset, int flags, int compLen, int uncompLen,
                             int baseRev, int linkRev, int parent1, int parent2, byte[] nodeId) {
        public IndexRecord {
            if (nodeId != null && nodeId.length > 20) {
                nodeId = Arrays.copyOf(nodeId, 20);
            }
        }

        public int getRevision() { return revision; }
        public long getOffset() { return offset; }
        public int getFlags() { return flags; }
        public int getCompLen() { return compLen; }
        public int getUncompLen() { return uncompLen; }
        public int getBaseRev() { return baseRev; }
        public int getLinkRev() { return linkRev; }
        public int getParent1() { return parent1; }
        public int getParent2() { return parent2; }
        public byte[] getNodeId() { return nodeId; }
    }

    public Revlog(File idxFile, File datFile) throws IOException {
        this.idxFile = idxFile;
        this.datFile = datFile;

        if (idxFile.exists()) {
            loadIndex();
        }
    }

    private synchronized void loadIndex() throws IOException {
        long len = idxFile.length();
        if (len == 0) return;
        if (len < 64) {
            throw new IOException("Invalid revlog index: too short");
        }

        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(idxFile.toPath(), java.nio.file.StandardOpenOption.READ)) {
            ByteBuffer buf = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, len);
            int revision = 0;
            while (buf.hasRemaining() && buf.remaining() >= 64) {
                long offsetFlags = buf.getLong();
                long offset;
                int flags;

                if (revision == 0) {
                    int formatFlags = (int) (offsetFlags >>> 48);
                    int version = (int) ((offsetFlags >>> 32) & 0xFFFF);
                    if (version != 1) {
                        throw new IOException("Unsupported revlog version: " + version);
                    }
                    this.inline = (formatFlags & 0x0001) != 0;
                    offset = 0;
                    flags = (int) (offsetFlags & 0xFFFF);
                } else {
                    offset = offsetFlags >>> 16;
                    flags = (int) (offsetFlags & 0xFFFF);
                }
                
                int compLen = buf.getInt();
                int uncompLen = buf.getInt();
                int baseRev = buf.getInt();
                int linkRev = buf.getInt();
                int parent1 = buf.getInt();
                int parent2 = buf.getInt();
                byte[] nodeId = new byte[32];
                buf.get(nodeId);

                records.add(new IndexRecord(revision, offset, flags, compLen, uncompLen,
                        baseRev, linkRev, parent1, parent2, nodeId));

                byte[] clippedNode = Arrays.copyOf(nodeId, 20);
                nodeMap.put(java.nio.ByteBuffer.wrap(clippedNode), revision);

                if (inline) {
                    if (buf.remaining() < compLen) {
                        throw new IOException("Truncated inline revlog data at revision " + revision);
                    }
                    buf.position(buf.position() + compLen);
                }
                revision++;
            }
        }
    }

    public synchronized int getRevisionCount() {
        return records.size();
    }

    public synchronized IndexRecord getIndexRecord(int rev) {
        if (rev < 0 || rev >= records.size()) {
            throw new IndexOutOfBoundsException("Revision out of bounds: " + rev);
        }
        return records.get(rev);
    }

    /**
     * 캐시 일관성 유지를 위해 인메모리 콘텐츠 캐시를 완전히 비웁니다.
     * (개선 권고 4번: 캐시 무효화 정책 완비)
     */
    public synchronized void clearCache() {
        contentCache.clear();
    }

    public synchronized byte[] getRawRevisionContent(int rev) throws IOException {
        if (rev == -1) {
            return new byte[0];
        }

        List<Integer> chain = new ArrayList<>();
        int curr = rev;
        java.util.Set<Integer> visited = new java.util.HashSet<>();
        while (true) {
            if (!visited.add(curr)) {
                throw new IOException("Cycle detected in revlog delta chain at revision: " + curr);
            }
            chain.add(curr);
            IndexRecord currRec = getIndexRecord(curr);
            if (currRec.getBaseRev() == curr) {
                break;
            }
            curr = currRec.getBaseRev();
        }

        int startRev = chain.get(chain.size() - 1);
        IndexRecord startRec = getIndexRecord(startRev);

        File targetFile = inline ? idxFile : datFile;
        if (!targetFile.exists()) {
            throw new IOException("Revlog data file does not exist: " + targetFile);
        }

        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(targetFile.toPath(), java.nio.file.StandardOpenOption.READ)) {
            byte[] hunk = readHunk(channel, startRec);
            byte[] content = decompressHunk(hunk, startRec);

            for (int i = chain.size() - 2; i >= 0; i--) {
                int nextRev = chain.get(i);
                IndexRecord nextRec = getIndexRecord(nextRev);
                byte[] nextHunk = readHunk(channel, nextRec);
                byte[] delta = decompressHunk(nextHunk, nextRec);
                content = applyDelta(content, delta);
            }

            return content;
        }
    }

    public synchronized byte[] getRevisionContent(int rev) throws IOException {
        if (rev == -1) {
            return new byte[0];
        }

        if (contentCache.containsKey(rev)) {
            return contentCache.get(rev).clone();
        }

        byte[] raw = getRawRevisionContent(rev);
        byte[] processed;

        // De-escaping logic for Mercurial's \x01\n metadata marker
        if (raw.length >= 2 && raw[0] == '\u0001' && raw[1] == '\n') {
            int secondMetaMarker = -1;
            for (int i = 2; i < raw.length - 1; i++) {
                if (raw[i] == '\u0001' && raw[i+1] == '\n') {
                    secondMetaMarker = i;
                    break;
                }
            }
            if (secondMetaMarker != -1) {
                int contentStart = secondMetaMarker + 2;
                processed = new byte[raw.length - contentStart];
                System.arraycopy(raw, contentStart, processed, 0, processed.length);
            } else {
                processed = raw;
            }
        } else {
            processed = raw;
        }

        contentCache.put(rev, processed.clone());

        return processed;
    }

    public synchronized java.util.Map<String, String> getRevisionMetadata(int rev) throws IOException {
        byte[] raw = getRawRevisionContent(rev);
        java.util.Map<String, String> meta = new java.util.HashMap<>();
        if (raw.length >= 2 && raw[0] == '\u0001' && raw[1] == '\n') {
            int secondMetaMarker = -1;
            for (int i = 2; i < raw.length - 1; i++) {
                if (raw[i] == '\u0001' && raw[i+1] == '\n') {
                    secondMetaMarker = i;
                    break;
                }
            }
            if (secondMetaMarker != -1 && secondMetaMarker > 2) {
                String metaText = new String(raw, 2, secondMetaMarker - 2, StandardCharsets.UTF_8);
                String[] lines = metaText.split("\n");
                for (String line : lines) {
                    if (line.isEmpty()) continue;
                    int colonIdx = line.indexOf(": ");
                    if (colonIdx != -1) {
                        meta.put(line.substring(0, colonIdx), line.substring(colonIdx + 2));
                    }
                }
            }
        }
        return meta;
    }

    private byte[] readHunk(java.nio.channels.FileChannel channel, IndexRecord rec) throws IOException {
        long seekOffset = rec.getOffset();
        if (inline) {
            seekOffset += (long) (rec.getRevision() + 1) * 64;
        }
        int compLen = rec.getCompLen();
        if (compLen <= 0) {
            return new byte[0];
        }

        ByteBuffer buf = ByteBuffer.allocate(compLen);
        long position = seekOffset;
        while (buf.hasRemaining()) {
            int read = channel.read(buf, position);
            if (read == -1) {
                break;
            }
            position += read;
        }
        if (buf.hasRemaining()) {
            throw new IOException("Failed to read complete hunk of size " + compLen + " at offset " + seekOffset);
        }
        return buf.array();
    }



    private byte[] decompressHunk(byte[] hunk, IndexRecord rec) throws IOException {
        if (hunk.length == 0 || rec.getUncompLen() == 0) {
            return new byte[0];
        }

        byte type = hunk[0];
        if (type == 'x') {
            Inflater inflater = new Inflater();
            boolean isZlibFromStart = false;
            boolean isZlibFromIndex1 = false;
            if (hunk.length >= 2) {
                int cmf = hunk[0] & 0xFF;
                int flg = hunk[1] & 0xFF;
                if ((cmf * 256 + flg) % 31 == 0 && (cmf & 0x0F) == 8) {
                    isZlibFromStart = true;
                }
            }
            if (!isZlibFromStart && hunk.length >= 3) {
                int cmf = hunk[1] & 0xFF;
                int flg = hunk[2] & 0xFF;
                if ((cmf * 256 + flg) % 31 == 0 && (cmf & 0x0F) == 8) {
                    isZlibFromIndex1 = true;
                }
            }
            if (isZlibFromStart) {
                inflater.setInput(hunk, 0, hunk.length);
            } else if (isZlibFromIndex1) {
                inflater.setInput(hunk, 1, hunk.length - 1);
            } else if (hunk.length > 1 && hunk[1] == 'x') {
                // Backwards compatibility with test/old format where 'x' was prepended to the zlib stream
                inflater.setInput(hunk, 1, hunk.length - 1);
            } else {
                inflater.setInput(hunk, 0, hunk.length);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(rec.getUncompLen());
            byte[] buf = new byte[1024];
            try {
                while (!inflater.finished()) {
                    int count = inflater.inflate(buf);
                    if (count == 0 && inflater.needsInput()) {
                        break;
                    }
                    out.write(buf, 0, count);
                }
            } catch (DataFormatException e) {
                throw new IOException("Failed to decompress zlib revlog hunk", e);
            } finally {
                inflater.end();
            }
            return out.toByteArray();
        } else if (type == 'u') {
            return Arrays.copyOfRange(hunk, 1, hunk.length);
        } else {
            // Uncompressed fallback if signature is not present
            return hunk;
        }
    }

    /**
     * Creates a simple raw delta between baseText and newText using prefix-suffix matching.
     * Preserved for verification comparisons.
     */
    public static byte[] createSimpleDelta(byte[] baseText, byte[] newText) {
        int prefixLen = 0;
        int maxLen = Math.min(baseText.length, newText.length);
        while (prefixLen < maxLen && baseText[prefixLen] == newText[prefixLen]) {
            prefixLen++;
        }

        int suffixLen = 0;
        int maxSuffix = maxLen - prefixLen;
        while (suffixLen < maxSuffix && baseText[baseText.length - 1 - suffixLen] == newText[newText.length - 1 - suffixLen]) {
            suffixLen++;
        }

        int start = prefixLen;
        int end = baseText.length - suffixLen;
        int insertLen = newText.length - suffixLen - prefixLen;

        byte[] insertData = new byte[insertLen];
        System.arraycopy(newText, prefixLen, insertData, 0, insertLen);

        ByteBuffer buf = ByteBuffer.allocate(12 + insertLen);
        buf.putInt(start);
        buf.putInt(end);
        buf.putInt(insertLen);
        buf.put(insertData);
        return buf.array();
    }

    private static class Line {
        final byte[] bytes;
        final int start;
        final int end;

        Line(byte[] bytes, int start, int end) {
            this.bytes = bytes;
            this.start = start;
            this.end = end;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof Line) {
                Line other = (Line) obj;
                return Arrays.equals(this.bytes, other.bytes);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }

    private static List<Line> splitLines(byte[] text) {
        List<Line> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length; i++) {
            if (text[i] == '\n') {
                byte[] lineBytes = Arrays.copyOfRange(text, start, i + 1);
                lines.add(new Line(lineBytes, start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length) {
            byte[] lineBytes = Arrays.copyOfRange(text, start, text.length);
            lines.add(new Line(lineBytes, start, text.length));
        }
        return lines;
    }

    /**
     * Creates a highly optimized multi-hunk delta using LCS (Longest Common Subsequence) Line Diff.
     */
    public static byte[] createDelta(byte[] baseText, byte[] newText) {
        if (baseText == null || baseText.length == 0) {
            ByteBuffer buf = ByteBuffer.allocate(12 + newText.length);
            buf.putInt(0);
            buf.putInt(0);
            buf.putInt(newText.length);
            buf.put(newText);
            return buf.array();
        }
        if (newText == null || newText.length == 0) {
            ByteBuffer buf = ByteBuffer.allocate(12);
            buf.putInt(0);
            buf.putInt(baseText.length);
            buf.putInt(0);
            return buf.array();
        }

        List<Line> baseLines = splitLines(baseText);
        List<Line> newLines = splitLines(newText);

        int n = baseLines.size();
        int m = newLines.size();

        // If the file is too large for O(N*M) DP, fall back to simple single-hunk delta to prevent OOM
        if ((long) n * m > 250000) {
            return createSimpleDelta(baseText, newText);
        }

        int[][] dp = new int[n + 1][m + 1];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                if (baseLines.get(i - 1).equals(newLines.get(j - 1))) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }

        int i = n, j = m;
        boolean[] baseMatched = new boolean[n];
        boolean[] newMatched = new boolean[m];
        while (i > 0 && j > 0) {
            if (baseLines.get(i - 1).equals(newLines.get(j - 1))) {
                baseMatched[i - 1] = true;
                newMatched[j - 1] = true;
                i--;
                j--;
            } else if (dp[i - 1][j] >= dp[i][j - 1]) {
                i--;
            } else {
                j--;
            }
        }

        ByteArrayOutputStream deltaOut = new ByteArrayOutputStream();
        int b = 0, g = 0;
        while (b < n || g < m) {
            if (b < n && g < m && baseMatched[b] && newMatched[g]) {
                b++;
                g++;
                continue;
            }

            int bStart = b;
            while (b < n && !baseMatched[b]) {
                b++;
            }
            int bEnd = b;

            int gStart = g;
            while (g < m && !newMatched[g]) {
                g++;
            }
            int gEnd = g;

            if (bEnd > bStart || gEnd > gStart) {
                int byteStart = (bStart < n) ? baseLines.get(bStart).start : baseText.length;
                int byteEnd = (bStart < n) ? ((bEnd > bStart) ? baseLines.get(bEnd - 1).end : baseLines.get(bStart).start) : baseText.length;
                if (bEnd == n && bStart < n) {
                    byteEnd = baseText.length;
                }

                ByteArrayOutputStream insertBuf = new ByteArrayOutputStream();
                for (int k = gStart; k < gEnd; k++) {
                    try {
                        insertBuf.write(newLines.get(k).bytes);
                    } catch (IOException ignored) {}
                }
                byte[] insertData = insertBuf.toByteArray();

                ByteBuffer hunkHeader = ByteBuffer.allocate(12);
                hunkHeader.putInt(byteStart);
                hunkHeader.putInt(byteEnd);
                hunkHeader.putInt(insertData.length);

                try {
                    deltaOut.write(hunkHeader.array());
                    deltaOut.write(insertData);
                } catch (IOException ignored) {}
            }
        }

        byte[] multiHunkDelta = deltaOut.toByteArray();
        // If the multi-hunk delta is actually larger than a simple delta due to hunk header overhead, fall back
        byte[] simpleDelta = createSimpleDelta(baseText, newText);
        if (multiHunkDelta.length > simpleDelta.length) {
            return simpleDelta;
        }

        return multiHunkDelta;
    }


    public synchronized byte[] appendRevision(byte[] content, int parent1, int parent2,
                                 byte[] p1Node, byte[] p2Node, int linkRev) throws IOException {
        return appendRevision(content, null, parent1, parent2, p1Node, p2Node, linkRev);
    }

    public synchronized byte[] appendRevision(byte[] content, java.util.Map<String, String> metadata, int parent1, int parent2,
                                 byte[] p1Node, byte[] p2Node, int linkRev) throws IOException {
        int rev = records.size();

        // Escaping logic for content and metadata
        byte[] processedContent;
        if (metadata != null && !metadata.isEmpty()) {
            StringBuilder msb = new StringBuilder();
            msb.append('\u0001').append('\n');
            for (java.util.Map.Entry<String, String> entry : metadata.entrySet()) {
                msb.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
            }
            msb.append('\u0001').append('\n');
            byte[] metaBytes = msb.toString().getBytes(StandardCharsets.UTF_8);
            processedContent = new byte[metaBytes.length + content.length];
            System.arraycopy(metaBytes, 0, processedContent, 0, metaBytes.length);
            System.arraycopy(content, 0, processedContent, metaBytes.length, content.length);
        } else if (content.length >= 2 && content[0] == '\u0001' && content[1] == '\n') {
            byte[] prefix = new byte[]{'\u0001', '\n', '\u0001', '\n'};
            processedContent = new byte[prefix.length + content.length];
            System.arraycopy(prefix, 0, processedContent, 0, prefix.length);
            System.arraycopy(content, 0, processedContent, prefix.length, content.length);
        } else {
            processedContent = content;
        }

        // Calculate NodeID: SHA-1(p1Node + p2Node + processedContent) where parents are sorted lexicographically
        byte[] hash;
        try {
            byte[] first = p1Node;
            byte[] second = p2Node;
            if (compareBytes(first, second) > 0) {
                first = p2Node;
                second = p1Node;
            }
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(first);
            md.update(second);
            md.update(processedContent);
            hash = md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 digest not available", e);
        }

        byte[] nodeId = new byte[32];
        System.arraycopy(hash, 0, nodeId, 0, 20);

        // Decide whether to write delta or fulltext
        byte[] rawToWrite;
        int baseRev;

        // 델타 체인 길이 계산 (개선 권고 1: 델타 체인 길이 상한)
        int chainLen = 0;
        int curr = parent1;
        while (curr != -1) {
            chainLen++;
            IndexRecord currRec = getIndexRecord(curr);
            if (currRec.getBaseRev() == curr || currRec.getBaseRev() == -1) {
                break;
            }
            curr = currRec.getBaseRev();
        }

        if (rev > 0 && parent1 != -1 && chainLen < 40) {
            byte[] baseContent = getRawRevisionContent(parent1);
            byte[] delta = createDelta(baseContent, processedContent);
            if (delta.length < processedContent.length) {
                rawToWrite = delta;
                baseRev = parent1; // C1 해결: baseRev는 델타의 직접적인 기준(parent1)을 가리켜야 generaldelta 체인 복원이 성립함
            } else {
                rawToWrite = processedContent;
                baseRev = rev;
            }
        } else {
            rawToWrite = processedContent;
            baseRev = rev;
        }

        // Compress rawToWrite
        ByteArrayOutputStream compOut = new ByteArrayOutputStream();
        Deflater def = new Deflater();
        def.setInput(rawToWrite);
        def.finish();
        byte[] zipBuf = new byte[1024];
        while (!def.finished()) {
            int count = def.deflate(zipBuf);
            compOut.write(zipBuf, 0, count);
        }
        def.end();
        byte[] compressed = compOut.toByteArray();

        byte[] dataHunk;
        if (compressed.length < rawToWrite.length) {
            dataHunk = compressed;
        } else {
            dataHunk = new byte[rawToWrite.length + 1];
            dataHunk[0] = 'u';
            System.arraycopy(rawToWrite, 0, dataHunk, 1, rawToWrite.length);
        }

        // We write separate .i and .d files (no inline)
        long offset = 0;
        if (datFile.exists()) {
            offset = datFile.length();
        }

        try (FileOutputStream out = new FileOutputStream(datFile, true)) {
            out.write(dataHunk);
            out.getFD().sync();
        }

        long offsetFlags;
        if (rev == 0) {
            // Version 1, format flags: 2 (generaldelta)
            long formatFlags = 0x0002L;
            long version = 1L;
            offsetFlags = (formatFlags << 48) | (version << 32) | (0 & 0xFFFF);
        } else {
            offsetFlags = (offset << 16) | (0 & 0xFFFF);
        }

        // Build 64-byte index record
        ByteBuffer recordBuf = ByteBuffer.allocate(64);
        recordBuf.putLong(offsetFlags);
        recordBuf.putInt(dataHunk.length);           // compLen
        recordBuf.putInt(processedContent.length);   // uncompLen: 메타데이터 prefix 포함 압축 전 길이 (실제 hg 명세)
        recordBuf.putInt(baseRev);
        recordBuf.putInt(linkRev);
        recordBuf.putInt(parent1);
        recordBuf.putInt(parent2);
        recordBuf.put(nodeId);

        try (FileOutputStream out = new FileOutputStream(idxFile, true)) {
            out.write(recordBuf.array());
            out.getFD().sync();
        }

        records.add(new IndexRecord(rev, offset, 0, dataHunk.length, processedContent.length,
                baseRev, linkRev, parent1, parent2, nodeId));

        byte[] clippedNode = Arrays.copyOf(nodeId, 20);
        nodeMap.put(java.nio.ByteBuffer.wrap(clippedNode), rev);

        return hash;
    }

    /**
     * Appends a raw ChangeGroupEntry from remote bundle, preserving the original remote Node ID.
     */
    public synchronized void appendChangeGroupEntry(ChangegroupParser.ChangeGroupEntry entry, int linkRev) throws IOException {
        if (findRevision(entry.node) != -1) {
            return;
        }

        int rev = records.size();
        int parent1 = findRevision(entry.p1);
        int parent2 = findRevision(entry.p2);

        byte[] content;
        if (entry.deltabase != null) {
            int baseRev = findRevision(entry.deltabase);
            if (baseRev == -1 || NodeIdUtil.isAllZero(entry.deltabase)) {
                content = applyDelta(new byte[0], entry.delta);
            } else {
                byte[] baseContent = getRawRevisionContent(baseRev);
                content = applyDelta(baseContent, entry.delta);
            }
        } else {
            if (parent1 == -1) {
                content = applyDelta(new byte[0], entry.delta);
            } else {
                byte[] baseContent = getRawRevisionContent(parent1);
                content = applyDelta(baseContent, entry.delta);
            }
        }

        byte[] p1Node = entry.p1 != null ? entry.p1 : new byte[20];
        byte[] p2Node = entry.p2 != null ? entry.p2 : new byte[20];

        // E3: Verify node hash integrity from remote
        byte[] expectedHash;
        try {
            byte[] first = p1Node;
            byte[] second = p2Node;
            if (compareBytes(first, second) > 0) {
                first = p2Node;
                second = p1Node;
            }
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(first);
            md.update(second);
            md.update(content);
            expectedHash = md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 digest not available", e);
        }

        byte[] expectedNodeId = new byte[20];
        System.arraycopy(expectedHash, 0, expectedNodeId, 0, 20);

        byte[] remoteNodeId = new byte[20];
        System.arraycopy(entry.node, 0, remoteNodeId, 0, 20);

        if (!Arrays.equals(expectedNodeId, remoteNodeId)) {
            throw new IOException("Security Integrity Error: Changegroup entry hash mismatch! Expected: " 
                + NodeIdUtil.toHex(expectedNodeId) + " but received: " + NodeIdUtil.toHex(remoteNodeId));
        }

        byte[] rawToWrite;
        int baseRev;

        int chainLen = 0;
        int curr = parent1;
        while (curr != -1) {
            chainLen++;
            IndexRecord currRec = getIndexRecord(curr);
            if (currRec.getBaseRev() == curr || currRec.getBaseRev() == -1) {
                break;
            }
            curr = currRec.getBaseRev();
        }

        if (rev > 0 && parent1 != -1 && chainLen < 100) {
            byte[] baseContent = getRawRevisionContent(parent1);
            byte[] delta = createDelta(baseContent, content);
            if (delta.length < content.length) {
                rawToWrite = delta;
                baseRev = parent1;
            } else {
                rawToWrite = content;
                baseRev = rev;
            }
        } else {
            rawToWrite = content;
            baseRev = rev;
        }

        ByteArrayOutputStream compOut = new ByteArrayOutputStream();
        Deflater def = new Deflater();
        def.setInput(rawToWrite);
        def.finish();
        byte[] zipBuf = new byte[1024];
        while (!def.finished()) {
            int count = def.deflate(zipBuf);
            compOut.write(zipBuf, 0, count);
        }
        def.end();
        byte[] compressed = compOut.toByteArray();

        byte[] dataHunk;
        if (compressed.length < rawToWrite.length) {
            dataHunk = compressed;
        } else {
            dataHunk = new byte[rawToWrite.length + 1];
            dataHunk[0] = 'u';
            System.arraycopy(rawToWrite, 0, dataHunk, 1, rawToWrite.length);
        }

        long offset = 0;
        if (datFile.exists()) {
            offset = datFile.length();
        }

        try (FileOutputStream out = new FileOutputStream(datFile, true)) {
            out.write(dataHunk);
            out.getFD().sync();
        }

        long offsetFlags;
        if (rev == 0) {
            long formatFlags = 0x0002L;
            long version = 1L;
            offsetFlags = (formatFlags << 48) | (version << 32) | (0 & 0xFFFF);
        } else {
            offsetFlags = (offset << 16) | (0 & 0xFFFF);
        }

        ByteBuffer recordBuf = ByteBuffer.allocate(64);
        recordBuf.putLong(offsetFlags);
        recordBuf.putInt(dataHunk.length);
        recordBuf.putInt(content.length);
        recordBuf.putInt(baseRev);
        recordBuf.putInt(linkRev);
        recordBuf.putInt(parent1);
        recordBuf.putInt(parent2);

        byte[] nodeId32 = new byte[32];
        System.arraycopy(entry.node, 0, nodeId32, 0, 20);
        recordBuf.put(nodeId32);

        try (FileOutputStream out = new FileOutputStream(idxFile, true)) {
            out.write(recordBuf.array());
            out.getFD().sync();
        }

        records.add(new IndexRecord(rev, offset, 0, dataHunk.length, content.length,
                baseRev, linkRev, parent1, parent2, entry.node));

        byte[] clippedEntryNode = Arrays.copyOf(entry.node, 20);
        nodeMap.put(java.nio.ByteBuffer.wrap(clippedEntryNode), rev);

        clearCache();
    }

    public synchronized int findRevision(byte[] nodeId) {
        if (nodeId == null) return -1;
        byte[] clippedNode = new byte[20];
        System.arraycopy(nodeId, 0, clippedNode, 0, Math.min(nodeId.length, 20));
        Integer rev = nodeMap.get(java.nio.ByteBuffer.wrap(clippedNode));
        return rev != null ? rev : -1;
    }

    public static byte[] applyDelta(byte[] baseText, byte[] delta) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(delta);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int lastCopied = 0;
        while (buf.hasRemaining()) {
            if (buf.remaining() < 12) {
                throw new IOException("Truncated delta hunk header");
            }
            int start = buf.getInt();
            int end = buf.getInt();
            int length = buf.getInt();
            if (length < 0 || buf.remaining() < length) {
                throw new IOException("Truncated delta hunk data");
            }
            byte[] insertData = new byte[length];
            buf.get(insertData);

            if (start < lastCopied || start > baseText.length || end < start || end > baseText.length) {
                throw new IOException("Invalid delta hunk offsets: start=" + start + ", end=" + end + ", baseLen=" + baseText.length);
            }
            out.write(baseText, lastCopied, start - lastCopied);
            out.write(insertData);
            lastCopied = end;
        }
        if (lastCopied < baseText.length) {
            out.write(baseText, lastCopied, baseText.length - lastCopied);
        }
        return out.toByteArray();
    }

    private static int compareBytes(byte[] a, byte[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int byteA = a[i] & 0xFF;
            int byteB = b[i] & 0xFF;
            if (byteA != byteB) {
                return byteA - byteB;
            }
        }
        return a.length - b.length;
    }

    public synchronized byte[] appendRawRevision(byte[] rawToWrite, byte[] node, int parent1, int parent2,
                                                 byte[] p1Node, byte[] p2Node, int linkRev) throws IOException {
        int rev = records.size();

        // Compress rawToWrite
        ByteArrayOutputStream compOut = new ByteArrayOutputStream();
        Deflater def = new Deflater();
        def.setInput(rawToWrite);
        def.finish();
        byte[] zipBuf = new byte[1024];
        while (!def.finished()) {
            int count = def.deflate(zipBuf);
            compOut.write(zipBuf, 0, count);
        }
        def.end();
        byte[] compressed = compOut.toByteArray();

        byte[] dataHunk;
        if (compressed.length < rawToWrite.length) {
            dataHunk = compressed;
        } else {
            dataHunk = new byte[rawToWrite.length + 1];
            dataHunk[0] = 'u';
            System.arraycopy(rawToWrite, 0, dataHunk, 1, rawToWrite.length);
        }

        long offset = 0;
        if (datFile.exists()) {
            offset = datFile.length();
        }

        try (FileOutputStream out = new FileOutputStream(datFile, true)) {
            out.write(dataHunk);
            out.getFD().sync();
        }

        long offsetFlags;
        if (rev == 0) {
            long formatFlags = 0x0002L;
            long version = 1L;
            offsetFlags = (formatFlags << 48) | (version << 32) | (0 & 0xFFFF);
        } else {
            offsetFlags = (offset << 16) | (0 & 0xFFFF);
        }

        ByteBuffer recordBuf = ByteBuffer.allocate(64);
        recordBuf.putLong(offsetFlags);
        recordBuf.putInt(dataHunk.length);
        recordBuf.putInt(rawToWrite.length); // uncompLen
        recordBuf.putInt(rev); // baseRev
        recordBuf.putInt(linkRev);
        recordBuf.putInt(parent1);
        recordBuf.putInt(parent2);

        byte[] nodeId32 = new byte[32];
        System.arraycopy(node, 0, nodeId32, 0, 20);
        recordBuf.put(nodeId32);

        try (FileOutputStream out = new FileOutputStream(idxFile, true)) {
            out.write(recordBuf.array());
            out.getFD().sync();
        }

        records.add(new IndexRecord(rev, offset, 0, dataHunk.length, rawToWrite.length,
                rev, linkRev, parent1, parent2, node));

        byte[] clippedNode = Arrays.copyOf(node, 20);
        nodeMap.put(java.nio.ByteBuffer.wrap(clippedNode), rev);

        clearCache();
        return node;
    }
}
