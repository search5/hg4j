package com.github.search5.hg4j.storage;
import com.github.search5.hg4j.util.NodeIdUtil;
import com.github.search5.hg4j.util.SafeFileIO;

import java.io.File;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import com.github.search5.hg4j.errors.HgCorruptDataException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Class responsible for managing and parsing the index (.i) file of a revlog.
 * To prevent heap memory pressure even with millions of revisions in large repositories,
 * only the physical file offset of each revision is maintained in a long array,
 * while IndexRecord is lazily loaded from the disk as needed via a SoftReference cache.
 */
public class RevlogIndex {

    private final File idxFile;
    private final Map<ByteBuffer, Integer> nodeMap = new HashMap<>();
    private final TreeMap<String, byte[]> hexNodeMap = new TreeMap<>();
    private final Map<Integer, SoftReference<Revlog.IndexRecord>> recordCache = new HashMap<>();
    private final Map<Integer, Revlog.IndexRecord> addedRecords = new HashMap<>();
    private boolean inline = false;
    private int revisionCount = 0;
    private long lastKnownSize = 0;

    // v2 Docket Fields.
    // 실제 hg CLI(Mercurial 7.2)로 생성한 changelog-v2 저장소를 hexdump/python struct로
    // 직접 대조해 검증된 레이아웃이다 (mercurial/revlogutils/docket.py의 S_HEADER,
    // mercurial/revlogutils/constants.py의 REVLOGV2/CHANGELOGV2/INDEX_ENTRY_V2/INDEX_ENTRY_CL_V2).
    // src/test/resources/fixtures/revlogv2-changelog/README.md 참고.
    static final int MAGIC_REVLOGV2 = 0xDEAD;
    static final int MAGIC_CHANGELOGV2 = 0xD34D;
    static final int V2_HEADER_SIZE = 59; // >I (4) + BBBBBB (6) + QQQQQQ (48) + c (1)
    static final int V2_RECORD_SIZE = 96; // INDEX_ENTRY_V2 / INDEX_ENTRY_CL_V2 (32*3)

    private boolean isV2 = false;
    private boolean isChangelogV2 = false;
    private int versionHeader = 0;
    private long docketIndexEnd = 0;
    private long docketPendingIndexEnd = 0;
    private long docketDataEnd = 0;
    private long docketPendingDataEnd = 0;
    private long docketSidedataEnd = 0;
    private long docketPendingSidedataEnd = 0;
    private byte docketDefaultCompression = 0;
    private String radix = "";
    private File resolvedIndexFile;
    private File resolvedDataFile;
    private File resolvedSidedataFile;

    // Physical file offset of each revision on disk
    private long[] fileOffsets = new long[1024];

    public RevlogIndex(File idxFile) throws IOException {
        this.idxFile = idxFile;
        if (idxFile.exists()) {
            loadIndex();
        }
    }

    public boolean isV2() {
        return isV2;
    }

    public boolean isChangelogV2() {
        return isChangelogV2;
    }

    public int getVersionHeader() {
        return versionHeader;
    }

    public long getDocketIndexEnd() {
        return docketIndexEnd;
    }

    public long getDocketDataEnd() {
        return docketDataEnd;
    }

    public long getDocketSidedataEnd() {
        return docketSidedataEnd;
    }

    public byte getDocketCompression() {
        return docketDefaultCompression;
    }

    /** v2 docket이 가리키는 실제 인덱스(.idx) 파일. v1이면 null. */
    public File getResolvedIndexFile() {
        return resolvedIndexFile;
    }

    /** v2 docket이 가리키는 실제 데이터(.dat) 파일. v1이면 null. */
    public File getResolvedDataFile() {
        return resolvedDataFile;
    }

    /** v2 docket이 가리키는 실제 sidedata(.sda) 파일. v1이면 null. */
    public File getResolvedSidedataFile() {
        return resolvedSidedataFile;
    }

    private static String readAsciiUid(ByteBuffer buf, int size) {
        byte[] b = new byte[size];
        buf.get(b);
        return new String(b, StandardCharsets.US_ASCII);
    }

    /** S_OLD_UID = '>BL' (uid 크기 1B + 파일 크기 4B) * count, 이어서 실제 uuid 바이트들. */
    private static void skipOldUids(ByteBuffer buf, int count) {
        if (count == 0) return;
        int[] sizes = new int[count];
        for (int i = 0; i < count; i++) {
            sizes[i] = buf.get() & 0xFF;
            buf.getInt();
        }
        for (int size : sizes) {
            buf.position(buf.position() + size);
        }
    }

    private static String deriveRadix(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * v2 인덱스 레코드(96바이트)에서 20바이트 node id를 추출한다.
     * CHANGELOGV2(INDEX_ENTRY_CL_V2 = {@code >Qiiii20s12xQiBi23x})는 node가 offset 24,
     * 일반 REVLOGV2(INDEX_ENTRY_V2 = {@code >Qiiiiii20s12xQiB19x})는 offset 32에 위치한다
     * (실측: Qiiii=8+4*4=24 vs Qiiiiii=8+4*6=32).
     */
    private byte[] extractV2NodeId(ByteBuffer buf) {
        int nodeOffset = isChangelogV2 ? 24 : 32;
        byte[] node = new byte[20];
        buf.position(nodeOffset);
        buf.get(node);
        return node;
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
        // No time-based throttling here (removed 2026-09-02): idxFile.length() is a single,
        // sub-microsecond stat() syscall, and PerformanceBenchmarkTest's 1,000-read SLA (2s
        // budget) has ample headroom for one extra stat per call. A wall-clock throttle window
        // instead created a real correctness bug -- a long-lived HgRepository/Revlog handle
        // (e.g. the remote side of two separate PushCommand.call() invocations against the same
        // bare repo, each going through its own HgRepository instance) could silently keep
        // returning a stale revision count for up to the throttle window after another instance
        // wrote to the same store, with no way for the caller to know its read was stale.
        long currentSize = idxFile.length();
        if (currentSize != lastKnownSize) {
            // During local write transactions, addedRecords is populated. Reset the cache only
            // when addedRecords is empty to maintain transaction consistency.
            //
            // This also turns out to be load-bearing for a very different reason than the name
            // suggests: StripCommand/RebaseCommand/HisteditCommand physically truncate a revlog's
            // .i/.d files directly with RandomAccessFile#setLength (bypassing addRecord()
            // entirely -- see StripCommand.truncateRevlog()), then keep using the SAME already-
            // held Revlog/RevlogIndex reference afterward (e.g. StripCommand's bookmark-
            // relocation loop calls changelog.findRevision() on nodes that were just stripped, to
            // tell "does this bookmark point at what I just removed"). If checkAndUpdate() were
            // allowed to reload here, it would rebuild nodeMap from the now-truncated file and
            // lose all knowledge of the just-stripped revisions, silently breaking that lookup
            // (verified: removing this guard makes
            // StripCommandCoverageTest#stripMovesBookmarkPointingAtStrippedRevisionToNewTip fail
            // -- the bookmark gets dropped instead of relocated). Any RevlogIndex that has ever
            // written locally is presumed to already know its own history and is trusted not to
            // need an automatic reload; genuinely fresh cross-instance reads (this repository
            // object has never written anything itself, e.g. the read-only remote side of a
            // PushCommand) are unaffected and still get picked up below.
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
        hexNodeMap.clear();
        recordCache.clear();
        addedRecords.clear();
        revisionCount = 0;
        isV2 = false;

        try (FileChannel channel = FileChannel.open(idxFile.toPath(), StandardOpenOption.READ)) {
            long len = channel.size();
            this.lastKnownSize = len;
            if (len == 0) return;

            // v2 판별: 4바이트 빅엔디안 version_header의 하위 16비트가 REVLOGV2(0xDEAD)
            // 또는 CHANGELOGV2(0xD34D)인지 확인 (mercurial/revlogutils/constants.py 실측값).
            if (len >= 4) {
                ByteBuffer firstFour = ByteBuffer.allocate(4);
                channel.position(0);
                channel.read(firstFour);
                firstFour.flip();
                int header = firstFour.getInt();
                int rlVersion = header & 0xFFFF;

                if (rlVersion == MAGIC_REVLOGV2 || rlVersion == MAGIC_CHANGELOGV2) {
                    if (len < V2_HEADER_SIZE) {
                        throw new HgCorruptDataException("Invalid revlog v2 docket: too short");
                    }
                    this.isV2 = true;
                    this.isChangelogV2 = (rlVersion == MAGIC_CHANGELOGV2);
                    this.versionHeader = header;

                    // S_HEADER = >I BBBBBB QQQQQQ c (59바이트, 오프셋은 mercurial/revlogutils/docket.py 실측)
                    ByteBuffer headerBuf = ByteBuffer.allocate(V2_HEADER_SIZE);
                    channel.position(0);
                    channel.read(headerBuf);
                    headerBuf.flip();

                    headerBuf.getInt(); // version_header (이미 읽음)
                    int indexUuidSize = headerBuf.get() & 0xFF;
                    int olderIndexUuidCount = headerBuf.get() & 0xFF;
                    int dataUuidSize = headerBuf.get() & 0xFF;
                    int olderDataUuidCount = headerBuf.get() & 0xFF;
                    int sidedataUuidSize = headerBuf.get() & 0xFF;
                    int olderSidedataUuidCount = headerBuf.get() & 0xFF;
                    this.docketIndexEnd = headerBuf.getLong();
                    this.docketPendingIndexEnd = headerBuf.getLong();
                    this.docketDataEnd = headerBuf.getLong();
                    this.docketPendingDataEnd = headerBuf.getLong();
                    this.docketSidedataEnd = headerBuf.getLong();
                    this.docketPendingSidedataEnd = headerBuf.getLong();
                    this.docketDefaultCompression = headerBuf.get();

                    // 헤더 이후: index_uuid, older_index_uuids, data_uuid, older_data_uuids,
                    // sidedata_uuid, older_sidedata_uuids 순서 (parse_docket_args와 동일 순서).
                    ByteBuffer tail = ByteBuffer.allocate((int) (len - V2_HEADER_SIZE));
                    channel.position(V2_HEADER_SIZE);
                    channel.read(tail);
                    tail.flip();

                    String indexUuid = readAsciiUid(tail, indexUuidSize);
                    skipOldUids(tail, olderIndexUuidCount);
                    String dataUuid = readAsciiUid(tail, dataUuidSize);
                    skipOldUids(tail, olderDataUuidCount);
                    String sidedataUuid = readAsciiUid(tail, sidedataUuidSize);
                    skipOldUids(tail, olderSidedataUuidCount);

                    // 파일명 규칙: {radix}-{uuid}.idx / .dat / .sda (mercurial/revlogutils/docket.py)
                    this.radix = deriveRadix(idxFile.getName());
                    this.resolvedIndexFile = new File(idxFile.getParentFile(), radix + "-" + indexUuid + ".idx");
                    this.resolvedDataFile = new File(idxFile.getParentFile(), radix + "-" + dataUuid + ".dat");
                    this.resolvedSidedataFile = new File(idxFile.getParentFile(), radix + "-" + sidedataUuid + ".sda");

                    if (!resolvedIndexFile.exists()) {
                        throw new HgCorruptDataException(
                                "Invalid v2 docket: companion index file missing: " + resolvedIndexFile);
                    }

                    // 인덱스 레코드는 실제 companion .idx 파일에서 읽는다 (docket 자체가 아님).
                    try (FileChannel idxChannel = FileChannel.open(resolvedIndexFile.toPath(), StandardOpenOption.READ)) {
                        long idxLen = idxChannel.size();
                        ByteBuffer buf = ByteBuffer.allocate(V2_RECORD_SIZE);
                        long currentPos = 0;
                        while (currentPos + V2_RECORD_SIZE <= idxLen) {
                            idxChannel.position(currentPos);
                            buf.clear();
                            while (buf.hasRemaining()) {
                                if (idxChannel.read(buf) == -1) break;
                            }
                            if (buf.hasRemaining()) break;
                            buf.flip();

                            byte[] nodeId = extractV2NodeId(buf);
                            nodeMap.put(ByteBuffer.wrap(nodeId), revisionCount);
                            hexNodeMap.put(NodeIdUtil.toHex(nodeId), nodeId);

                            addFileOffset(currentPos);
                            currentPos += V2_RECORD_SIZE;
                            revisionCount++;
                        }
                    }
                    return;
                }

                // v2가 아닌 경우 포지션 원복
                channel.position(0);
            }

            if (len < 64) {
                throw new HgCorruptDataException("Invalid revlog index: too short");
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
                        throw new HgCorruptDataException("Unsupported revlog version: " + version);
                    }
                    this.inline = (formatFlags & 0x0001) != 0;
                }

                buf.position(32); // Seek directly to NodeID
                byte[] nodeId = new byte[32];
                buf.get(nodeId);

                byte[] clippedNode = Arrays.copyOf(nodeId, 20);
                nodeMap.put(ByteBuffer.wrap(clippedNode), revisionCount);
                hexNodeMap.put(NodeIdUtil.toHex(clippedNode), clippedNode);

                addFileOffset(currentPos);

                long nextPos = currentPos + 64;
                if (inline) {
                    buf.position(8);
                    int compLen = buf.getInt();
                    nextPos += compLen;
                    if (nextPos > len) {
                        throw new HgCorruptDataException("Truncated inline revlog data at revision " + revisionCount);
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
                hexNodeMap.put(NodeIdUtil.toHex(clippedNode), clippedNode);

                addFileOffset(currentPos);

                long nextPos = currentPos + 64;
                if (inline) {
                    buf.position(8);
                    int compLen = buf.getInt();
                    nextPos += compLen;
                    if (nextPos > len) {
                        throw new HgCorruptDataException("Truncated inline revlog data at revision " + revisionCount);
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
        hexNodeMap.clear();
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
        File sourceFile = (isV2 && resolvedIndexFile != null) ? resolvedIndexFile : idxFile;
        int recordSize = isV2 ? V2_RECORD_SIZE : 64;
        try (FileChannel channel = FileChannel.open(sourceFile.toPath(), StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(recordSize);
            channel.position(fileOffset);
            while (buf.hasRemaining()) {
                if (channel.read(buf) == -1) break;
            }
            buf.flip();

            Revlog.IndexRecord record = isV2 ? decodeV2Record(rev, buf) : decodeV1Record(rev, buf);

            recordCache.put(rev, new SoftReference<>(record));
            return record;
        } catch (IOException e) {
            throw new RuntimeException("Failed to lazy load index record at rev " + rev + " (offset: " + fileOffset + ", file: " + sourceFile + ")", e);
        }
    }

    private Revlog.IndexRecord decodeV1Record(int rev, ByteBuffer buf) {
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

        return new Revlog.IndexRecord(rev, offset, flags, compLen, uncompLen,
                baseRev, linkRev, parent1, parent2, nodeId);
    }

    /**
     * INDEX_ENTRY_V2 / INDEX_ENTRY_CL_V2(96바이트)를 디코딩한다. 실제 hg CLI로 생성한
     * changelog-v2 픽스처로 검증됨 (RevlogV2ParserTest 참고). changelog-v2는 baseRev/linkRev를
     * 별도로 저장하지 않는다 — 실측 결과 각 리비전은 델타 체인 없이 독립 zstd 프레임으로
     * 저장되므로(=완전한 fulltext), baseRev=rev로 두면 {@link Revlog#getRawRevisionContent}의
     * 델타 체인 추적이 즉시 종료되어 올바르게 동작한다. linkRev도 changelog 자기 자신을
     * 가리키므로 rev와 동일하게 둔다.
     */
    private Revlog.IndexRecord decodeV2Record(int rev, ByteBuffer buf) {
        long offsetFlags = buf.getLong();
        long offset = (rev == 0) ? 0 : (offsetFlags >>> 16);
        int flags = (int) (offsetFlags & 0xFFFF);

        int compLen = buf.getInt();
        int uncompLen = buf.getInt();

        int baseRev;
        int linkRev;
        int parent1;
        int parent2;
        if (isChangelogV2) {
            parent1 = buf.getInt();
            parent2 = buf.getInt();
            baseRev = rev;
            linkRev = rev;
        } else {
            baseRev = buf.getInt();
            linkRev = buf.getInt();
            parent1 = buf.getInt();
            parent2 = buf.getInt();
        }

        byte[] nodeId = new byte[32];
        buf.get(nodeId);

        return new Revlog.IndexRecord(rev, offset, flags, compLen, uncompLen,
                baseRev, linkRev, parent1, parent2, nodeId);
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
        
        return -1;
    }

    public boolean isInline() {
        return inline;
    }

    public synchronized void addRecord(Revlog.IndexRecord record) {
        checkAndUpdate();
        int rev = record.getRevision();
        addedRecords.put(rev, record);

        long physicalIndexOffset = isV2 ? ((long) rev * V2_RECORD_SIZE) : 0;
        if (!isV2 && rev > 0) {
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
        hexNodeMap.put(NodeIdUtil.toHex(clippedNode), clippedNode);
        recordCache.put(rev, new SoftReference<>(record));
        
        // 물리 파일 쓰기는 Revlog.appendRevision()/appendRevisionV2()의 책임이다
        // (v1과 동일한 계약 — addRecord()는 순수 인메모리 북키핑만 담당).
        // v2에서 실제 companion 파일 크기가 바뀌었으면 여기서 lastKnownSize를 갱신해
        // checkAndUpdate()가 불필요하게 재파싱하지 않도록 한다.
        File sizeTrackedFile = (isV2 && resolvedIndexFile != null) ? resolvedIndexFile : idxFile;
        if (sizeTrackedFile.exists()) {
            this.lastKnownSize = sizeTrackedFile.length();
        }
    }

    /**
     * v2 docket 헤더의 index_end/data_end(및 pending 쌍) 필드를 갱신한다.
     * 헤더의 나머지 필드(uuid, sidedata 크기, 압축 헤더)는 그대로 유지한다.
     * 물리 companion 파일에 이미 데이터를 쓴 뒤 {@code Revlog.appendRevisionV2()}에서 호출된다.
     */
    synchronized void updateV2DocketSizes(long newIndexEnd, long newDataEnd) throws IOException {
        this.docketIndexEnd = newIndexEnd;
        this.docketPendingIndexEnd = newIndexEnd;
        this.docketDataEnd = newDataEnd;
        this.docketPendingDataEnd = newDataEnd;

        ByteBuffer buf = ByteBuffer.allocate(32);
        buf.putLong(docketIndexEnd);
        buf.putLong(docketPendingIndexEnd);
        buf.putLong(docketDataEnd);
        buf.putLong(docketPendingDataEnd);
        buf.flip();
        try (FileChannel ch = FileChannel.open(idxFile.toPath(), StandardOpenOption.WRITE)) {
            ch.position(10); // index_end 오프셋 (S_HEADER: version_header(4)+6*B(6)=10)
            ch.write(buf);
            ch.force(false);
        }
    }

    /**
     * Resolves a prefix hex string to a collection of matching 20-byte node IDs.
     * Extremely fast using an in-memory TreeMap lookup (O(log N)).
     */
    public synchronized List<byte[]> findByHexPrefix(String prefix) {
        checkAndUpdate();
        if (prefix == null || prefix.isEmpty()) {
            return Collections.emptyList();
        }
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        String endPrefix = lowerPrefix + "\uffff";
        SortedMap<String, byte[]> subMap = hexNodeMap.subMap(lowerPrefix, endPrefix);
        return new ArrayList<>(subMap.values());
    }
}
