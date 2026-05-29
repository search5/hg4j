package org.hg4j.core;

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

/**
 * Core implementation for Mercurial Revlog (index .i and data .d files).
 */
public class Revlog {

    private final File idxFile;
    private final File datFile;
    private final RevlogIndex index;
    private boolean inline = false;
    private boolean useZstd = false;

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
        this(idxFile, datFile, false);
    }

    public Revlog(File idxFile, File datFile, boolean useZstd) throws IOException {
        this.idxFile = idxFile;
        this.datFile = datFile;
        this.index = new RevlogIndex(idxFile);
        this.inline = index.isInline();
        this.useZstd = useZstd;
    }



    public synchronized int getRevisionCount() {
        return index.getRevisionCount();
    }

    public synchronized IndexRecord getIndexRecord(int rev) {
        return index.getIndexRecord(rev);
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

        if (rev < -1 || rev >= getRevisionCount()) {
            throw new org.hg4j.errors.HgRevisionNotFoundException("Revision " + rev + " not found. Total revisions: " + getRevisionCount());
        }

        List<Integer> chain = new ArrayList<>();
        int curr = rev;
        java.util.Set<Integer> visited = new java.util.HashSet<>();
        while (true) {
            if (!visited.add(curr)) {
                throw new org.hg4j.errors.HgCorruptDataException("Cycle detected in revlog delta chain at revision: " + curr);
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
            throw new org.hg4j.errors.HgCorruptDataException("Revlog data file does not exist: " + targetFile);
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

        if (rev < -1 || rev >= getRevisionCount()) {
            throw new org.hg4j.errors.HgRevisionNotFoundException("Revision " + rev + " not found. Total revisions: " + getRevisionCount());
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

        // Hardening for OOM (L-3): Use memory-mapping for large hunks to save JVM heap space
        if (compLen > 5 * 1024 * 1024) { 
            java.nio.MappedByteBuffer mapBuf = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, seekOffset, compLen);
            byte[] data = new byte[compLen];
            mapBuf.get(data);
            return data;
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
            throw new org.hg4j.errors.HgCorruptDataException("Failed to read complete hunk of size " + compLen + " at offset " + seekOffset);
        }
        return buf.array();
    }



    private byte[] decompressHunk(byte[] hunk, IndexRecord rec) throws IOException {
        return DeltaCodec.decompress(hunk, rec.getUncompLen());
    }

    /**
     * Creates a simple raw delta between baseText and newText using prefix-suffix matching.
     * Preserved for verification comparisons. Delegates to {@link DeltaEngine}.
     */
    public static byte[] createSimpleDelta(byte[] baseText, byte[] newText) {
        return DeltaEngine.createSimpleDelta(baseText, newText);
    }

    /**
     * Creates a highly optimized multi-hunk delta using LCS Line Diff.
     * Delegates to {@link DeltaEngine}.
     */
    public static byte[] createDelta(byte[] baseText, byte[] newText) {
        return DeltaEngine.createDelta(baseText, newText);
    }


    public synchronized byte[] appendRevision(byte[] content, int parent1, int parent2,
                                 byte[] p1Node, byte[] p2Node, int linkRev) throws IOException {
        return appendRevision(content, null, parent1, parent2, p1Node, p2Node, linkRev);
    }

    public synchronized byte[] appendRevision(byte[] content, java.util.Map<String, String> metadata, int parent1, int parent2,
                                 byte[] p1Node, byte[] p2Node, int linkRev) throws IOException {
        int rev = index.getRevisionCount();

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
        byte[] rawToWrite = processedContent;
        int baseRev = rev;

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

        boolean isMetadataLog = idxFile.getName().contains("00manifest") || idxFile.getName().contains("00changelog");

        if (!isMetadataLog && rev > 0 && parent1 != -1 && chainLen < 100) {
            byte[] baseContent = getRawRevisionContent(parent1);
            byte[] delta = createDelta(baseContent, processedContent);
            if (delta.length < processedContent.length) {
                rawToWrite = delta;
                baseRev = parent1;
            } else {
                rawToWrite = processedContent;
                baseRev = rev;
            }
        } else {
            rawToWrite = processedContent;
            baseRev = rev;
        }

        // Compress rawToWrite
        byte[] dataHunk = DeltaCodec.compress(rawToWrite, useZstd);

        long offset = 0;
        if (rev > 0) {
            IndexRecord prevRec = getIndexRecord(rev - 1);
            offset = prevRec.getOffset() + prevRec.getCompLen();
        }

        if (inline) {
            // Write 64-byte index record followed by dataHunk into idxFile (Inline Format Implementation)
            long offsetFlags;
            if (rev == 0) {
                long formatFlags = 0x0003L; // inline(1) + generaldelta(2) = 3
                long version = 1L;
                offsetFlags = (formatFlags << 48) | (version << 32) | (0 & 0xFFFF);
            } else {
                offsetFlags = (offset << 16) | (0 & 0xFFFF);
            }

            ByteBuffer recordBuf = ByteBuffer.allocate(64);
            recordBuf.putLong(offsetFlags);
            recordBuf.putInt(dataHunk.length);
            recordBuf.putInt(processedContent.length);
            recordBuf.putInt(baseRev);
            recordBuf.putInt(linkRev);
            recordBuf.putInt(parent1);
            recordBuf.putInt(parent2);
            recordBuf.put(nodeId);

            try (FileOutputStream out = new FileOutputStream(idxFile, true)) {
                out.write(recordBuf.array());
                out.write(dataHunk);
                out.getFD().sync();
            }
        } else {
            // Non-inline: Write dataHunk into datFile, and 64-byte record into idxFile
            try (FileOutputStream out = new FileOutputStream(datFile, true)) {
                out.write(dataHunk);
                out.getFD().sync();
            }

            long offsetFlags;
            if (rev == 0) {
                long formatFlags = 0x0002L; // generaldelta
                long version = 1L;
                offsetFlags = (formatFlags << 48) | (version << 32) | (0 & 0xFFFF);
            } else {
                offsetFlags = (offset << 16) | (0 & 0xFFFF);
            }

            ByteBuffer recordBuf = ByteBuffer.allocate(64);
            recordBuf.putLong(offsetFlags);
            recordBuf.putInt(dataHunk.length);
            recordBuf.putInt(processedContent.length);
            recordBuf.putInt(baseRev);
            recordBuf.putInt(linkRev);
            recordBuf.putInt(parent1);
            recordBuf.putInt(parent2);
            recordBuf.put(nodeId);

            try (FileOutputStream out = new FileOutputStream(idxFile, true)) {
                out.write(recordBuf.array());
                out.getFD().sync();
            }
        }

        index.addRecord(new IndexRecord(rev, offset, 0, dataHunk.length, processedContent.length,
                baseRev, linkRev, parent1, parent2, nodeId));

        return hash;
    }

    /**
     * Appends a raw ChangeGroupEntry from remote bundle, preserving the original remote Node ID.
     */
    public synchronized void appendChangeGroupEntry(ChangegroupParser.ChangeGroupEntry entry, int linkRev) throws IOException {
        if (findRevision(entry.node) != -1) {
            return;
        }

        int rev = index.getRevisionCount();
        int parent1 = findRevision(entry.p1);
        int parent2 = findRevision(entry.p2);

        byte[] content;
        if (entry.deltabase != null) {
            int baseRev = findRevision(entry.deltabase);
            if (baseRev == -1) {
                if (NodeIdUtil.isAllZero(entry.deltabase)) {
                    content = applyDelta(new byte[0], entry.delta);
                } else {
                    throw new org.hg4j.errors.HgCorruptDataException("Delta base revision not found in local index: " + NodeIdUtil.toHex(entry.deltabase) + " for commit: " + NodeIdUtil.toHex(entry.node));
                }
            } else {
                byte[] baseContent = getRawRevisionContent(baseRev);
                content = applyDelta(baseContent, entry.delta);
            }
        } else {
            if (rev == 0) {
                content = applyDelta(new byte[0], entry.delta);
            } else {
                byte[] baseContent = getRawRevisionContent(rev - 1);
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
            throw new org.hg4j.errors.HgCorruptDataException("Security Integrity Error: Changegroup entry hash mismatch! Expected: " 
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

        boolean isMetadataLog = idxFile.getName().contains("00manifest") || idxFile.getName().contains("00changelog");

        if (!isMetadataLog && rev > 0 && parent1 != -1 && chainLen < 100) {
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

        byte[] dataHunk = DeltaCodec.compress(rawToWrite, useZstd);

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

        index.addRecord(new IndexRecord(rev, offset, 0, dataHunk.length, content.length,
                baseRev, linkRev, parent1, parent2, entry.node));

        clearCache();
    }

    public synchronized int findRevision(byte[] nodeId) {
        return index.findRevision(nodeId);
    }

    /** Delegates to {@link DeltaEngine}. */
    public static byte[] applyDelta(byte[] baseText, byte[] delta) throws IOException {
        return DeltaEngine.applyDelta(baseText, delta);
    }

    private static int compareBytes(byte[] a, byte[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int byteA = a[i] & 0xFF;
            int byteB = b[i] & 0xFF;
            if (byteA != byteB) return byteA - byteB;
        }
        return a.length - b.length;
    }

    public synchronized byte[] appendRawRevision(byte[] rawToWrite, byte[] node, int parent1, int parent2,
                                                 byte[] p1Node, byte[] p2Node, int linkRev) throws IOException {
        int rev = index.getRevisionCount();

        // Compress rawToWrite
        byte[] dataHunk = DeltaCodec.compress(rawToWrite, useZstd);

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

        index.addRecord(new IndexRecord(rev, offset, 0, dataHunk.length, rawToWrite.length,
                rev, linkRev, parent1, parent2, node));

        clearCache();
        return node;
    }
}
