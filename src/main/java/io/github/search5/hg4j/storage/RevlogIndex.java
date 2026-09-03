package io.github.search5.hg4j.storage;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.util.SafeFileIO;

import java.io.File;
import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import io.github.search5.hg4j.errors.HgCorruptDataException;
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

    // persistent-nodemap (.n trie) acceleration -- see NodeMapFile for the on-disk format.
    // When non-null and it exactly covers the revlog's current revision count (verified against
    // the actual tip node, not just trusted blindly), loadIndex() skips the full per-record scan
    // that would otherwise be needed purely to populate nodeMap/hexNodeMap: fileOffsets is
    // computed analytically (non-inline revlogs place record `rev` at byte `rev*64`) and
    // nodeMap/hexNodeMap population is deferred. findRevision() then answers node->rev lookups
    // straight from the trie (with a single-record verification read, never trusted blindly --
    // see NodeMapFile#findRevision's javadoc on why). findByHexPrefix() cannot be answered from
    // the trie alone (it doesn't store full node hashes) so it materializes the deferred maps on
    // first use, exactly like the pre-persistent-nodemap behavior from then on.
    private NodeMapFile persistentNodeMap;
    private boolean nodeMapDeferred = false;

    public RevlogIndex(File idxFile) throws IOException {
        this(idxFile, false, null);
    }

    /**
     * @param createAsGeneralV2 if {@code idxFile} does not exist yet AND this is {@code true},
     *     initializes a brand-new empty general revlog-v2 ({@code exp-revlogv2.2}, magic
     *     {@code REVLOGV2}/0xDEAD) docket instead of leaving this index in its default empty-v1
     *     state. Needed because v2-ness can normally only be detected by reading an *existing*
     *     docket's magic bytes -- a repository whose {@code .hg/store/requires} lists
     *     {@code exp-revlogv2.2} still needs every *brand-new* revlog (e.g. the filelog for a
     *     file that has never been committed before) to start out as v2 too, not silently fall
     *     back to v1 just because there was nothing on disk yet to detect the format from.
     */
    public RevlogIndex(File idxFile, boolean createAsGeneralV2) throws IOException {
        this(idxFile, createAsGeneralV2, null);
    }

    public RevlogIndex(File idxFile, NodeMapFile persistentNodeMap) throws IOException {
        this(idxFile, false, persistentNodeMap);
    }

    public RevlogIndex(File idxFile, boolean createAsGeneralV2, NodeMapFile persistentNodeMap) throws IOException {
        this(idxFile, createAsGeneralV2, false, persistentNodeMap);
    }

    /**
     * @param createAsChangelogV2 when {@code true} and {@code idxFile} does not exist yet,
     *     bootstrap a brand-new {@code exp-changelog-v2} docket (CHANGELOGV2 magic, {@code
     *     isChangelogV2()} true) instead of a general {@code exp-revlogv2.2} one -- ignored if
     *     {@code createAsGeneralV2} is also true (that already implies v2; changelog-v2 takes
     *     precedence since it is the more specific format). Used by {@code
     *     DefaultFileStoreEngine} to originate {@code 00changelog.i} for a repository whose
     *     requires declare {@code exp-changelog-v2} but has never been committed to yet.
     */
    public RevlogIndex(File idxFile, boolean createAsGeneralV2, boolean createAsChangelogV2, NodeMapFile persistentNodeMap) throws IOException {
        this.idxFile = idxFile;
        this.persistentNodeMap = persistentNodeMap;
        if (idxFile.exists()) {
            loadIndex();
        } else if (createAsGeneralV2) {
            initializeNewV2Docket(false);
        } else if (createAsChangelogV2) {
            initializeNewV2Docket(true);
        }
    }

    /**
     * Writes a brand-new, empty v2 docket (59-byte S_HEADER + 3 UUIDs, no revisions yet) plus
     * empty companion .idx/.dat/.sda files, matching the exact byte layout real hg 7.2.4
     * (Rust-extension build) writes for a freshly-created {@code exp-revlogv2.2} revlog --
     * verified via docker/hg-rust-7.2.4 (see
     * src/test/resources/fixtures/revlogv2-general/README.md). UUIDs are generated the same way
     * real hg does (mercurial/utils/docket.py's {@code make_uid()}: 4 random bytes, hex-encoded
     * to 8 lowercase ascii chars).
     *
     * <p>{@code asChangelogV2} selects between the two v2 flavors: the on-disk docket/companion
     * -file bootstrap bytes are byte-for-byte identical either way (same header shape, same
     * empty companions) -- only the docket's own magic value and {@link #isChangelogV2} differ.
     * {@link Revlog#appendRevisionV2} is what actually picks {@code INDEX_ENTRY_CL_V2} vs {@code
     * INDEX_ENTRY_V2} per-record layout based on {@link #isChangelogV2()} for every revision
     * appended after this bootstrap.
     */
    private void initializeNewV2Docket(boolean asChangelogV2) throws IOException {
        this.isV2 = true;
        this.isChangelogV2 = asChangelogV2;
        this.versionHeader = asChangelogV2 ? MAGIC_CHANGELOGV2 : MAGIC_REVLOGV2;
        this.radix = deriveRadix(idxFile.getName());

        java.security.SecureRandom rnd = new java.security.SecureRandom();
        String indexUuid = randomUid(rnd);
        String dataUuid = randomUid(rnd);
        String sidedataUuid = randomUid(rnd);

        this.resolvedIndexFile = new File(idxFile.getParentFile(), radix + "-" + indexUuid + ".idx");
        this.resolvedDataFile = new File(idxFile.getParentFile(), radix + "-" + dataUuid + ".dat");
        this.resolvedSidedataFile = new File(idxFile.getParentFile(), radix + "-" + sidedataUuid + ".sda");

        File parent = idxFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        SafeFileIO.writeAtomic(resolvedIndexFile, new byte[0]);
        SafeFileIO.writeAtomic(resolvedDataFile, new byte[0]);
        SafeFileIO.writeAtomic(resolvedSidedataFile, new byte[0]);

        this.docketIndexEnd = 0;
        this.docketPendingIndexEnd = 0;
        this.docketDataEnd = 0;
        this.docketPendingDataEnd = 0;
        this.docketSidedataEnd = 0;
        this.docketPendingSidedataEnd = 0;
        this.docketDefaultCompression = '(';

        ByteBuffer header = ByteBuffer.allocate(V2_HEADER_SIZE);
        header.putInt(this.versionHeader);
        header.put((byte) UID_SIZE); // index_uuid_size
        header.put((byte) 0);        // older_index_uuid_count
        header.put((byte) UID_SIZE); // data_uuid_size
        header.put((byte) 0);        // older_data_uuid_count
        header.put((byte) UID_SIZE); // sidedata_uuid_size
        header.put((byte) 0);        // older_sidedata_uuid_count
        header.putLong(0L); // index_end
        header.putLong(0L); // pending_index_end
        header.putLong(0L); // data_end
        header.putLong(0L); // pending_data_end
        header.putLong(0L); // sidedata_end
        header.putLong(0L); // pending_sidedata_end
        header.put(docketDefaultCompression);
        header.flip();

        byte[] docketBytes = new byte[V2_HEADER_SIZE + UID_SIZE * 3];
        System.arraycopy(header.array(), 0, docketBytes, 0, V2_HEADER_SIZE);
        System.arraycopy(indexUuid.getBytes(StandardCharsets.US_ASCII), 0, docketBytes, V2_HEADER_SIZE, UID_SIZE);
        System.arraycopy(dataUuid.getBytes(StandardCharsets.US_ASCII), 0, docketBytes, V2_HEADER_SIZE + UID_SIZE, UID_SIZE);
        System.arraycopy(sidedataUuid.getBytes(StandardCharsets.US_ASCII), 0, docketBytes, V2_HEADER_SIZE + UID_SIZE * 2, UID_SIZE);
        SafeFileIO.writeAtomic(idxFile, docketBytes);

        this.lastKnownSize = docketBytes.length;
    }

    private static final int UID_SIZE = 8;

    private static String randomUid(java.security.SecureRandom rnd) {
        byte[] raw = new byte[UID_SIZE / 2];
        rnd.nextBytes(raw);
        StringBuilder sb = new StringBuilder(UID_SIZE);
        for (byte b : raw) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
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
        nodeMapDeferred = false;

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

            if (persistentNodeMap != null && tryFastLoadWithPersistentNodeMap(channel, len)) {
                return;
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

    /**
     * Attempts an O(1) index load using an already-attached persistent nodemap trie, in place of
     * scanning every 64-byte record purely to build {@code nodeMap}/{@code hexNodeMap}. Only
     * non-inline revlogs are eligible -- real hg never persists a nodemap for inline revlogs (see
     * {@code nodemap.py}'s own {@code persist_nodemap}, which returns immediately when
     * {@code revlog._inline}) -- and the trie must exactly cover the revlog's current on-disk
     * revision count with a matching tip node; otherwise this returns {@code false} and the
     * caller falls back to the unmodified full-scan path.
     *
     * <p>On success, {@code fileOffsets} is filled analytically ({@code rev * 64}, valid for any
     * non-inline v1 revlog -- the same formula {@link #addRecord} already trusts for this case)
     * and {@code nodeMap}/{@code hexNodeMap} population is deferred: {@link #findRevision} then
     * answers lookups straight from the trie, and {@link #findByHexPrefix} materializes the maps
     * lazily on first use.
     */
    private boolean tryFastLoadWithPersistentNodeMap(FileChannel channel, long len) throws IOException {
        if (len % 64 != 0) {
            return false; // not a clean multiple of the v1 record size -- let the normal path handle/throw
        }
        ByteBuffer first = ByteBuffer.allocate(64);
        channel.position(0);
        while (first.hasRemaining()) {
            if (channel.read(first) == -1) break;
        }
        if (first.hasRemaining()) {
            return false;
        }
        first.flip();
        long offsetFlags = first.getLong();
        int formatFlags = (int) (offsetFlags >>> 48);
        int version = (int) ((offsetFlags >>> 32) & 0xFFFF);
        if (version != 1) {
            return false; // let the normal path raise the appropriate corruption error
        }
        boolean firstInline = (formatFlags & 0x0001) != 0;
        if (firstInline) {
            return false; // persistent nodemap is never written for inline revlogs -- nothing to accelerate
        }

        int computedRevisionCount = (int) (len / 64);
        if (computedRevisionCount == 0 || persistentNodeMap.getTipRev() != computedRevisionCount - 1) {
            return false; // stale (repo grew/shrank since the .n was written) -- fall back
        }

        byte[] tipRecordNode;
        if (persistentNodeMap.getTipRev() == 0) {
            first.position(32);
            tipRecordNode = new byte[32];
            first.get(tipRecordNode);
        } else {
            ByteBuffer tipBuf = ByteBuffer.allocate(64);
            channel.position((long) persistentNodeMap.getTipRev() * 64L);
            while (tipBuf.hasRemaining()) {
                if (channel.read(tipBuf) == -1) break;
            }
            if (tipBuf.hasRemaining()) {
                return false;
            }
            tipBuf.flip();
            tipBuf.position(32);
            tipRecordNode = new byte[32];
            tipBuf.get(tipRecordNode);
        }
        byte[] actualTipNode20 = Arrays.copyOf(tipRecordNode, 20);
        byte[] docketTipNode20 = NodeMapFile.clip20(persistentNodeMap.getTipNode());
        if (!Arrays.equals(actualTipNode20, docketTipNode20)) {
            return false; // docket is stale relative to this on-disk index (e.g. rewritten history)
        }

        this.inline = false;
        this.revisionCount = computedRevisionCount;
        this.lastKnownSize = len;
        this.fileOffsets = new long[computedRevisionCount];
        for (int rev = 0; rev < computedRevisionCount; rev++) {
            this.fileOffsets[rev] = (long) rev * 64L;
        }
        this.nodeMapDeferred = true;
        return true;
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

        // Sidedata fields immediately follow the node-id section in BOTH INDEX_ENTRY_V2 and
        // INDEX_ENTRY_CL_V2 (only the padding/rank bytes after them differ, which we don't need):
        // 8 bytes sidedata offset, 4 bytes sidedata compressed length, 1 byte compression-mode
        // byte whose bits 0-1 are the main data's compression mode and bits 2-3 are sidedata's
        // own compression mode (mercurial/pure/parsers.py IndexObject2._unpack_entry: `data_comp
        // = data[10] & 3`, `sidedata_comp = (data[10] & (3 << 2)) >> 2`).
        long sidedataOffset = buf.getLong();
        int sidedataCompLen = buf.getInt();
        int compressionByte = buf.get() & 0xFF;
        int sidedataCompressionMode = (compressionByte >> 2) & 3;

        return new Revlog.IndexRecord(rev, offset, flags, compLen, uncompLen,
                baseRev, linkRev, parent1, parent2, nodeId,
                sidedataOffset, sidedataCompLen, sidedataCompressionMode);
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

        if (nodeMapDeferred && persistentNodeMap != null) {
            Integer candidate = persistentNodeMap.findRevision(clippedNode);
            if (candidate != null && candidate >= 0 && candidate < revisionCount) {
                // Never trust a trie hit blindly (see NodeMapFile#findRevision javadoc): confirm
                // the candidate revision's actual node matches before returning it.
                Revlog.IndexRecord record = getIndexRecord(candidate);
                if (record != null && record.getNodeId() != null
                        && Arrays.equals(Arrays.copyOf(record.getNodeId(), 20), clippedNode)) {
                    return candidate;
                }
            }
            // Not (verifiably) in the persisted trie -- fall through to nodeMap, which still
            // covers any revisions appended locally since the trie was loaded (via addRecord()).
        }

        Integer rev = nodeMap.get(ByteBuffer.wrap(clippedNode));
        if (rev != null) {
            return rev;
        }

        return -1;
    }

    public boolean isInline() {
        return inline;
    }

    /**
     * True when this instance loaded via the persistent-nodemap fast path and hasn't yet
     * materialized its in-memory {@code nodeMap}/{@code hexNodeMap} (i.e. only
     * {@link #findRevision} has been used so far, answered straight from the {@code .n} trie).
     * Exposed for tests; not meaningful application state.
     */
    public synchronized boolean isNodeMapDeferred() {
        return nodeMapDeferred;
    }

    /** The attached persistent nodemap trie reader, or {@code null} if none was provided/usable. */
    public NodeMapFile getPersistentNodeMap() {
        return persistentNodeMap;
    }

    /**
     * Replaces the in-memory persistent-nodemap snapshot, e.g. right after {@link
     * NodeMapFile#persist} has written a fresh/updated {@code .n}+{@code .nd} pair to disk for
     * this revlog, so subsequent {@link #findRevision} calls within this same process benefit
     * from the just-written trie instead of the (now stale) one loaded at construction time.
     */
    public synchronized void setPersistentNodeMap(NodeMapFile persistentNodeMap) {
        this.persistentNodeMap = persistentNodeMap;
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
     * v2 docket 헤더의 index_end/data_end(및 pending 쌍) 필드를 갱신한다. {@code
     * docketSidedataEnd}/{@code docketPendingSidedataEnd}는 그대로 유지한다 — sidedata를
     * 함께 쓴 append라면 {@link #updateV2DocketSizes(long, long, long)}를 대신 쓸 것.
     * 헤더의 나머지 필드(uuid, 압축 헤더)는 그대로 유지한다. 물리 companion 파일에 이미
     * 데이터를 쓴 뒤 {@code Revlog.appendRevisionV2()}에서 호출된다.
     */
    synchronized void updateV2DocketSizes(long newIndexEnd, long newDataEnd) throws IOException {
        updateV2DocketSizes(newIndexEnd, newDataEnd, this.docketSidedataEnd);
    }

    /**
     * {@link #updateV2DocketSizes(long, long)}와 같지만 {@code sidedata_end}(및 pending)도
     * 함께 갱신한다 — real hg의 {@code hg verify}/{@code hg debugchangedfiles}가 {@code .sda}
     * 파일의 실제 바이트가 아니라 <b>이 docket 헤더에 기록된 값</b>을 "유효한 sidedata
     * 길이"로 신뢰하기 때문에(실측: 이 필드를 갱신하지 않고 sidedata를 append만 하면
     * "expected N bytes from offset M, data size is <stale sidedata_end>"로 거부됨 —
     * SidedataFilesWriteTest에서 재현·확인), sidedata를 쓸 때마다 반드시 같이 갱신해야
     * 한다. {@code newSidedataEnd}는 이번 append로 이 revlog 전체 sidedata 파일에 누적된
     * 총 유효 바이트 수(= 이번 청크의 offset + length)여야 한다.
     */
    synchronized void updateV2DocketSizes(long newIndexEnd, long newDataEnd, long newSidedataEnd) throws IOException {
        this.docketIndexEnd = newIndexEnd;
        this.docketPendingIndexEnd = newIndexEnd;
        this.docketDataEnd = newDataEnd;
        this.docketPendingDataEnd = newDataEnd;
        this.docketSidedataEnd = newSidedataEnd;
        this.docketPendingSidedataEnd = newSidedataEnd;

        ByteBuffer buf = ByteBuffer.allocate(48);
        buf.putLong(docketIndexEnd);
        buf.putLong(docketPendingIndexEnd);
        buf.putLong(docketDataEnd);
        buf.putLong(docketPendingDataEnd);
        buf.putLong(docketSidedataEnd);
        buf.putLong(docketPendingSidedataEnd);
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
        materializeDeferredNodeMap();
        if (prefix == null || prefix.isEmpty()) {
            return Collections.emptyList();
        }
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        String endPrefix = lowerPrefix + "\uffff";
        SortedMap<String, byte[]> subMap = hexNodeMap.subMap(lowerPrefix, endPrefix);
        return new ArrayList<>(subMap.values());
    }

    /**
     * Fully populates {@code nodeMap}/{@code hexNodeMap} for the persistent-nodemap-deferred
     * revision range by reading each covered record's node id from disk once. Needed by
     * {@link #findByHexPrefix} -- unlike an exact-node {@link #findRevision} lookup, prefix
     * resolution can't be answered from the trie alone (it doesn't persist full node hashes, only
     * enough of the hex prefix to disambiguate the revisions it was actually built from). After
     * this runs once, this RevlogIndex behaves exactly as if persistent-nodemap acceleration had
     * never been used -- it's a one-time fallback cost, not a correctness compromise.
     */
    private void materializeDeferredNodeMap() {
        if (!nodeMapDeferred) {
            return;
        }
        for (int rev = 0; rev < revisionCount; rev++) {
            Revlog.IndexRecord record = getIndexRecord(rev);
            byte[] clippedNode = Arrays.copyOf(record.getNodeId(), 20);
            nodeMap.put(ByteBuffer.wrap(clippedNode), rev);
            hexNodeMap.put(NodeIdUtil.toHex(clippedNode), clippedNode);
        }
        nodeMapDeferred = false;
    }
}
