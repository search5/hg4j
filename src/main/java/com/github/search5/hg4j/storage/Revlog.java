package com.github.search5.hg4j.storage;
import com.github.search5.hg4j.diff.DeltaEngine;
import com.github.search5.hg4j.bundle.ChangegroupParser;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.github.search5.hg4j.util.SafeFileIO;

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

    // In-memory LRU revision content cache (max 100 entries)
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
        this.index = new RevlogIndex(idxFile);
        if (index.isV2()) {
            // v2는 항상 non-inline이며 실제 데이터 파일은 docket의 UUID로부터 발견된다 —
            // 생성자로 넘어온 datFile(예: "00changelog.d")은 v2 저장소에는 존재하지 않는다.
            this.datFile = index.getResolvedDataFile();
            this.inline = false;
        } else {
            this.datFile = datFile;
            this.inline = index.isInline();
        }
        this.useZstd = useZstd;
    }

    public synchronized RevlogIndex getIndex() {
        return index;
    }

    public synchronized int getRevisionCount() {
        return index.getRevisionCount();
    }

    public synchronized IndexRecord getIndexRecord(int rev) {
        return index.getIndexRecord(rev);
    }

    /**
     * Completely clears the in-memory content cache and index, and reloads the disk state to maintain cache consistency.
     */
    public synchronized void clearCache() {
        contentCache.clear();
        try {
            index.clearCache();
        } catch (Exception e) {
            // ignore
        }
    }

    public synchronized byte[] getRawRevisionContent(int rev) throws IOException {
        if (rev == -1) {
            return new byte[0];
        }

        if (rev < -1 || rev >= getRevisionCount()) {
            throw new com.github.search5.hg4j.errors.HgRevisionNotFoundException("Revision " + rev + " not found. Total revisions: " + getRevisionCount());
        }

        List<Integer> chain = new ArrayList<>();
        int curr = rev;
        java.util.Set<Integer> visited = new java.util.HashSet<>();
        while (true) {
            if (!visited.add(curr)) {
                throw new com.github.search5.hg4j.errors.HgCorruptDataException("Cycle detected in revlog delta chain at revision: " + curr);
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
            throw new com.github.search5.hg4j.errors.HgCorruptDataException("Revlog data file does not exist: " + targetFile);
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
            throw new com.github.search5.hg4j.errors.HgRevisionNotFoundException("Revision " + rev + " not found. Total revisions: " + getRevisionCount());
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
            seekOffset = index.getFileOffset(rec.getRevision()) + 64;
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
            throw new com.github.search5.hg4j.errors.HgCorruptDataException("Failed to read complete hunk of size " + compLen + " at offset " + seekOffset);
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

    /**
     * v2(changelog-v2) 저장소에 새 리비전을 append한다. 실제 hg CLI로 생성한 changelog-v2
     * 픽스처를 hexdump/zstd로 직접 대조해 검증된 레이아웃을 그대로 재현한다 — 각 리비전은
     * 델타 체인 없이 독립 zstd 프레임(raw, prefix byte 없음)으로 저장된다 (RevlogV2ParserTest,
     * src/test/resources/fixtures/revlogv2-changelog/README.md 참고).
     *
     * <p>changelog-v2만 지원한다. 일반 revlog v2({@code exp-revlogv2.2}, 매니페스트/파일로그)는
     * 실제 hg 픽스처로 검증할 방법이 이 환경에 없어(영구 nodemap과 마찬가지로 Rust 확장
     * 필요) 의도적으로 구현하지 않았다 — 검증 안 된 추측으로 파일을 깨뜨리는 것보다
     * 명시적으로 예외를 던지는 편이 안전하다.</p>
     */
    private synchronized byte[] appendRevisionV2(int rev, byte[] processedContent, int parent1, int parent2,
                                                   byte[] nodeId) throws IOException {
        if (!index.isChangelogV2()) {
            throw new UnsupportedOperationException(
                    "hg4j does not yet support writing to a generic revlog-v2 (non-changelog) revlog; "
                    + "only reading is supported. See decisions/revlog-v2-support-plan.md.");
        }

        File resolvedIndexFile = index.getResolvedIndexFile();
        File resolvedDataFile = index.getResolvedDataFile();

        byte[] dataHunk = DeltaCodec.compress(processedContent, true);

        long offset = resolvedDataFile.exists() ? resolvedDataFile.length() : 0;
        try (FileOutputStream out = new FileOutputStream(resolvedDataFile, true)) {
            out.write(dataHunk);
            out.getFD().sync();
        }

        long offsetFlags = (rev == 0) ? 0 : ((offset << 16));

        // INDEX_ENTRY_CL_V2 = >Qiiii20s12xQiBi23x (96바이트, mercurial/revlogutils/constants.py 실측)
        ByteBuffer recordBuf = ByteBuffer.allocate(96);
        recordBuf.putLong(offsetFlags);
        recordBuf.putInt(dataHunk.length);
        recordBuf.putInt(processedContent.length);
        recordBuf.putInt(parent1);
        recordBuf.putInt(parent2);
        byte[] node20 = Arrays.copyOf(nodeId, 20);
        recordBuf.put(node20);
        recordBuf.put(new byte[12]); // 패딩
        recordBuf.putLong(0L); // sidedata offset (미지원)
        recordBuf.putInt(0);   // sidedata comp length
        // 압축 모드: 실제 hg 픽스처의 압축 모드 바이트는 9(0b1001) — 하위 2비트가
        // COMP_MODE_DEFAULT(1, docket의 default_compression_header=zstd 사용)를 가리킨다.
        // COMP_MODE_PLAIN(0)으로 잘못 쓰면 실제 hg가 zstd 바이트를 평문으로 취급해
        // `hg verify`에서 integrity check failed가 남을 남 뿐 아니라(id는
        // computeNodeId 시점에 이미 확정되어 있어 hg4j 자체 판독에는 영향 없지만) 실제
        // hg와의 상호운용성이 깨진다 — 실제 hg CLI로 재현·확인됨.
        recordBuf.put((byte) 1); // COMP_MODE_DEFAULT
        recordBuf.putInt(rev); // rank (단순화: 선형 히스토리 가정)
        recordBuf.put(new byte[23]); // 패딩
        recordBuf.flip();

        try (FileOutputStream out = new FileOutputStream(resolvedIndexFile, true)) {
            out.write(recordBuf.array());
            out.getFD().sync();
        }

        index.updateV2DocketSizes(resolvedIndexFile.length(), resolvedDataFile.length());

        index.addRecord(new IndexRecord(rev, offset, 0, dataHunk.length, processedContent.length,
                rev, rev, parent1, parent2, nodeId));

        byte[] hash = new byte[20];
        System.arraycopy(nodeId, 0, hash, 0, 20);
        return hash;
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

        if (index.isV2()) {
            return appendRevisionV2(rev, processedContent, parent1, parent2, nodeId);
        }

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
                    throw new com.github.search5.hg4j.errors.HgCorruptDataException("Delta base revision not found in local index: " + NodeIdUtil.toHex(entry.deltabase) + " for commit: " + NodeIdUtil.toHex(entry.node));
                }
            } else {
                byte[] baseContent = getRawRevisionContent(baseRev);
                content = applyDelta(baseContent, entry.delta);
            }
        } else {
            // cg1(entry.deltabase == null)은 와이어 포맷 자체에 베이스 필드가 없다. 실제
            // Mercurial의 cg1 패커(ChangeGroupPacker01)는 forcedeltaparentprev=True로 항상
            // "이 그룹 스트림에서 바로 직전에 나온 엔트리"를 베이스로 삼는다 — 해당 엔트리의
            // 실제 DAG 부모(p1)와는 무관한 순전히 위치 기반 규칙이다(mercurial/changegroup.py
            // 실측, 2026-09-01). hg4j의 자체 changegroup 생성기(HgLocalClient.getBundle())도
            // 이 규칙에 맞춰 "직전에 패킹한 엔트리"를 베이스로 델타를 만들도록 맞췄다 — 반드시
            // rev-1(로컬 revlog에 이번에 순서대로 추가되는 직전 리비전)이어야 하며 parent1로
            // 바꾸면 다중 head(branch) 저장소에서 실제 hg가 만든 cg1 번들 디코딩이 깨진다.
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
            throw new com.github.search5.hg4j.errors.HgCorruptDataException("Security Integrity Error: Changegroup entry hash mismatch! Expected: " 
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

    public synchronized void appendOptimizedRevision(byte[] processedContent, byte[] nodeId, int parent1, int parent2,
                                                     byte[] p1Node, byte[] p2Node, int linkRev) throws IOException {
        int rev = index.getRevisionCount();

        // Decide whether to write delta or fulltext (defragmentation / re-delta optimization)
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
            long offsetFlags;
            if (rev == 0) {
                long formatFlags = 0x0003L; // inline + generaldelta
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

        clearCache();
    }
}
