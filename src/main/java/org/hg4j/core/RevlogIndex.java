package org.hg4j.core;

import java.io.File;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Revlog의 인덱스 (.i) 파일을 관리하고 파싱하는 책임을 가지는 클래스.
 * 대형 저장소에서 수백만 리비전이 있어도 힙 메모리 압박이 없도록
 * 각 리비전의 파일 물리 오프셋만 long 배열로 들고 있으며, IndexRecord는 SoftReference 캐시로 필요시 디스크에서 Lazy loading합니다.
 */
public class RevlogIndex {

    private final File idxFile;
    private final Map<ByteBuffer, Integer> nodeMap = new HashMap<>();
    private final Map<Integer, SoftReference<Revlog.IndexRecord>> recordCache = new HashMap<>();
    private final Map<Integer, Revlog.IndexRecord> addedRecords = new HashMap<>();
    private boolean inline = false;
    private int revisionCount = 0;
    private long lastKnownSize = 0;
    private long lastCheckedTime = 0;

    // 디스크에 있는 각 리비전의 실제 파일 물리 오프셋
    private long[] fileOffsets = new long[1024];

    public RevlogIndex(File idxFile) throws IOException {
        this.idxFile = idxFile;
        if (idxFile.exists()) {
            loadIndex();
        }
    }

    private void addFileOffset(long offset) {
        if (revisionCount >= fileOffsets.length) {
            fileOffsets = Arrays.copyOf(fileOffsets, fileOffsets.length * 2);
        }
        fileOffsets[revisionCount] = offset;
    }

    private synchronized void checkAndUpdate() {
        if (!idxFile.exists()) {
            // 디스크 파일이 없을 때는 메모리에 기입 중인 addedRecords와 nodeMap이 보존되어야 하므로 캐시를 지우지 않고 그대로 리턴합니다.
            return;
        }
        long now = System.currentTimeMillis();
        if (now < lastCheckedTime + 200) {
            // 시간 기반 Throttling: 200ms 이내의 빈번한 호출 시 디스크 조회를 전면 스킵
            return;
        }
        lastCheckedTime = now;

        long currentSize = idxFile.length();
        if (currentSize != lastKnownSize) {
            // 로컬 쓰기 트랜잭션 중에는 addedRecords가 채워지므로, addedRecords가 비어있을 때만 캐시를 재설정하여 트랜잭션 일관성을 완벽히 보호
            if (addedRecords.isEmpty()) {
                try {
                    if (currentSize > lastKnownSize && lastKnownSize > 0) {
                        // Incremental Update: 파일이 커진 경우에는 무효화하지 않고 증분 파싱
                        loadIndexIncremental(lastKnownSize);
                    } else {
                        // 파일이 작아졌거나 최초 로드 시에는 전체 무효화 후 새로 로드
                        clearCache();
                    }
                } catch (IOException ignored) {
                }
            }
        }
    }

    private synchronized void loadIndex() throws IOException {
        if (!idxFile.exists()) return;
        nodeMap.clear();
        recordCache.clear();
        addedRecords.clear();
        revisionCount = 0;

        try (FileChannel channel = FileChannel.open(idxFile.toPath(), StandardOpenOption.READ)) {
            long len = channel.size();
            this.lastKnownSize = len;
            if (len == 0) return;
            if (len < 64) {
                throw new org.hg4j.errors.HgCorruptDataException("Invalid revlog index: too short");
            }

            ByteBuffer buf = ByteBuffer.allocate(64);
            long currentPos = 0;

            while (currentPos + 64 <= len) {
                channel.position(currentPos);
                buf.clear();
                while (buf.hasRemaining()) {
                    if (channel.read(buf) == -1) break;
                }
                if (buf.hasRemaining()) break; // EOF

                buf.flip();
                long offsetFlags = buf.getLong();

                if (revisionCount == 0) {
                    int formatFlags = (int) (offsetFlags >>> 48);
                    int version = (int) ((offsetFlags >>> 32) & 0xFFFF);
                    if (version != 1) {
                        throw new org.hg4j.errors.HgCorruptDataException("Unsupported revlog version: " + version);
                    }
                    this.inline = (formatFlags & 0x0001) != 0;
                }

                buf.position(32); // Seek directly to NodeID
                byte[] nodeId = new byte[32];
                buf.get(nodeId);

                byte[] clippedNode = Arrays.copyOf(nodeId, 20);
                nodeMap.put(ByteBuffer.wrap(clippedNode), revisionCount);

                addFileOffset(currentPos);

                long nextPos = currentPos + 64;
                if (inline) {
                    buf.position(8);
                    int compLen = buf.getInt();
                    nextPos += compLen;
                    if (nextPos > len) {
                        throw new org.hg4j.errors.HgCorruptDataException("Truncated inline revlog data at revision " + revisionCount);
                    }
                }
                currentPos = nextPos;
                revisionCount++;
            }
        }
    }

    private synchronized void loadIndexIncremental(long fromPos) throws IOException {
        if (!idxFile.exists()) return;
        try (FileChannel channel = FileChannel.open(idxFile.toPath(), StandardOpenOption.READ)) {
            long len = channel.size();
            this.lastKnownSize = len;
            if (fromPos >= len) return;

            ByteBuffer buf = ByteBuffer.allocate(64);
            long currentPos = fromPos;

            while (currentPos + 64 <= len) {
                channel.position(currentPos);
                buf.clear();
                while (buf.hasRemaining()) {
                    if (channel.read(buf) == -1) break;
                }
                if (buf.hasRemaining()) break; // EOF

                buf.flip();
                long offsetFlags = buf.getLong();

                buf.position(32); // Seek directly to NodeID
                byte[] nodeId = new byte[32];
                buf.get(nodeId);

                byte[] clippedNode = Arrays.copyOf(nodeId, 20);
                nodeMap.put(ByteBuffer.wrap(clippedNode), revisionCount);

                addFileOffset(currentPos);

                long nextPos = currentPos + 64;
                if (inline) {
                    buf.position(8);
                    int compLen = buf.getInt();
                    nextPos += compLen;
                    if (nextPos > len) {
                        throw new org.hg4j.errors.HgCorruptDataException("Truncated inline revlog data at revision " + revisionCount);
                    }
                }
                currentPos = nextPos;
                revisionCount++;
            }
        }
    }

    /**
     * 물리 디스크 데이터 변경 시(Rebase/GC 등) 메모리 캐시 상태를 완전 동기화하기 위한 캐시 무효화.
     */
    public synchronized void clearCache() throws IOException {
        nodeMap.clear();
        recordCache.clear();
        addedRecords.clear();
        revisionCount = 0;
        lastKnownSize = 0;
        if (idxFile.exists()) {
            loadIndex();
        }
    }

    public synchronized int getRevisionCount() {
        checkAndUpdate();
        return revisionCount;
    }

    public synchronized Revlog.IndexRecord getIndexRecord(int rev) {
        checkAndUpdate();
        int maxCount = getRevisionCount();
        if (rev < 0 || rev >= maxCount) {
            throw new IndexOutOfBoundsException("Revision out of bounds: " + rev);
        }

        // 1. Check SoftReference Cache
        SoftReference<Revlog.IndexRecord> ref = recordCache.get(rev);
        if (ref != null) {
            Revlog.IndexRecord cached = ref.get();
            if (cached != null) {
                return cached;
            }
        }

        // 2. Check added records in memory
        if (addedRecords.containsKey(rev)) {
            return addedRecords.get(rev);
        }

        // 3. Lazy load from disk file on demand (Seek directly using fileOffsets)
        long fileOffset = fileOffsets[rev];
        try (FileChannel channel = FileChannel.open(idxFile.toPath(), StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(64);
            channel.position(fileOffset);
            while (buf.hasRemaining()) {
                if (channel.read(buf) == -1) break;
            }
            buf.flip();

            long offsetFlags = buf.getLong();
            long offset = (rev == 0) ? 0 : (offsetFlags >>> 16);
            int flags = (int) (offsetFlags & 0xFFFF);

            int compLen = buf.getInt();
            int uncompLen = buf.getInt();
            int baseRev = buf.getInt();
            int linkRev = buf.getInt();
            int parent1 = buf.getInt();
            int parent2 = buf.getInt();
            byte[] nodeId = new byte[32];
            buf.get(nodeId);

            Revlog.IndexRecord record = new Revlog.IndexRecord(rev, offset, flags, compLen, uncompLen,
                    baseRev, linkRev, parent1, parent2, nodeId);

            recordCache.put(rev, new SoftReference<>(record));
            return record;
        } catch (IOException e) {
            throw new RuntimeException("Failed to lazy load index record at rev " + rev + " (offset: " + fileOffset + ")", e);
        }
    }

    public synchronized long getFileOffset(int rev) {
        checkAndUpdate();
        int maxCount = getRevisionCount();
        if (rev < 0 || rev >= maxCount) {
            throw new IndexOutOfBoundsException("Revision out of bounds: " + rev);
        }
        return fileOffsets[rev];
    }

    public synchronized int findRevision(byte[] nodeId) {
        checkAndUpdate();
        if (nodeId == null) return -1;
        byte[] clippedNode = new byte[20];
        System.arraycopy(nodeId, 0, clippedNode, 0, Math.min(nodeId.length, 20));
        Integer rev = nodeMap.get(ByteBuffer.wrap(clippedNode));
        if (rev != null) {
            return rev;
        }
        
        // Also scan addedRecords in memory if not indexed yet
        for (Revlog.IndexRecord rec : addedRecords.values()) {
            byte[] clippedRecNode = Arrays.copyOf(rec.getNodeId(), 20);
            if (Arrays.equals(clippedNode, clippedRecNode)) {
                return rec.getRevision();
            }
        }
        return -1;
    }

    public boolean isInline() {
        return inline;
    }

    public synchronized void addRecord(Revlog.IndexRecord record) {
        checkAndUpdate();
        int rev = record.getRevision();
        addedRecords.put(rev, record);

        long physicalIndexOffset = 0;
        if (rev > 0) {
            if (inline) {
                Revlog.IndexRecord prev = getIndexRecord(rev - 1);
                physicalIndexOffset = getFileOffset(rev - 1) + 64 + prev.getCompLen();
            } else {
                // non-inline 경로: 인덱스 파일(.i)에는 오직 64바이트짜리 인덱스 레코드들이 연달아 기입되므로
                // 인덱스 파일 내에서의 물리 오프셋은 정확히 rev * 64바이트가 됩니다.
                // (주의: 이는 데이터 파일(.d)의 물리 데이터 오프셋이 아니며, 오직 인덱스 파일(.i)의 lazy load seek 전용 오프셋입니다!)
                physicalIndexOffset = (long) rev * 64;
            }
        }

        if (rev >= fileOffsets.length) {
            fileOffsets = Arrays.copyOf(fileOffsets, Math.max(fileOffsets.length * 2, rev + 1));
        }
        fileOffsets[rev] = physicalIndexOffset;

        revisionCount = Math.max(revisionCount, rev + 1);
        
        byte[] clippedNode = Arrays.copyOf(record.getNodeId(), 20);
        nodeMap.put(ByteBuffer.wrap(clippedNode), rev);
        recordCache.put(rev, new SoftReference<>(record));
        
        if (idxFile.exists()) {
            this.lastKnownSize = idxFile.length();
        }
    }
}
