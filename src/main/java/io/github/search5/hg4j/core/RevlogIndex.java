package io.github.search5.hg4j.core;

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
 * Class responsible for managing and parsing the index (.i) file of a revlog.
 * To prevent heap memory pressure even with millions of revisions in large repositories,
 * only the physical file offset of each revision is maintained in a long array,
 * while IndexRecord is lazily loaded from the disk as needed via a SoftReference cache.
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

    // Physical file offset of each revision on disk
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
            // When the disk file does not exist, in-memory addedRecords and nodeMap must be preserved, so return without clearing the cache.
            return;
        }
        long now = System.currentTimeMillis();
        if (now < lastCheckedTime + 200) {
            // Time-based throttling: Skip disk lookups for frequent calls within 200ms
            return;
        }
        lastCheckedTime = now;

        long currentSize = idxFile.length();
        if (currentSize != lastKnownSize) {
            // During local write transactions, addedRecords is populated. Reset the cache only when addedRecords is empty to maintain transaction consistency.
            if (addedRecords.isEmpty()) {
                try {
                    if (currentSize > lastKnownSize && lastKnownSize > 0) {
                        // Incremental Update: If the file has grown, parse incrementally without invalidating the cache
                        loadIndexIncremental(lastKnownSize);
                    } else {
                        // If the file has shrunk or upon initial load, invalidate the entire cache and reload
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
                throw new io.github.search5.hg4j.errors.HgCorruptDataException("Invalid revlog index: too short");
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
                        throw new io.github.search5.hg4j.errors.HgCorruptDataException("Unsupported revlog version: " + version);
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
                        throw new io.github.search5.hg4j.errors.HgCorruptDataException("Truncated inline revlog data at revision " + revisionCount);
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
                        throw new io.github.search5.hg4j.errors.HgCorruptDataException("Truncated inline revlog data at revision " + revisionCount);
                    }
                }
                currentPos = nextPos;
                revisionCount++;
            }
        }
    }

    /**
     * Invalidates the cache to synchronize the in-memory cache state with physical disk changes (e.g., Rebase/GC).
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
                // Non-inline path: Since only 64-byte index records are written sequentially in the index file (.i),
                // the physical offset within the index file is rev * 64 bytes.
                // (Note: This is the offset within the index file (.i) used for lazy loading, not the physical data offset in the data file (.d).)
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
