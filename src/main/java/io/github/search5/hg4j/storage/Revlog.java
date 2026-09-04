package io.github.search5.hg4j.storage;
import io.github.search5.hg4j.diff.DeltaEngine;
import io.github.search5.hg4j.bundle.ChangegroupParser;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.util.SafeFileIO;
import com.github.luben.zstd.Zstd;

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
import io.github.search5.hg4j.errors.HgCensoredContentException;
import io.github.search5.hg4j.errors.HgCorruptDataException;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Core implementation for Mercurial Revlog (index .i and data .d files).
 */
public class Revlog {

    private final File idxFile;
    private final File datFile;
    private final RevlogIndex index;
    private boolean inline = false;
    private boolean useZstd = false;
    private final boolean persistentNodeMapEnabled;

    // In-memory LRU revision content cache (max 100 entries)
    private final Map<Integer, byte[]> contentCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> eldest) {
            return size() > 100;
        }
    };

    public record IndexRecord(int revision, long offset, int flags, int compLen, int uncompLen,
                             int baseRev, int linkRev, int parent1, int parent2, byte[] nodeId,
                             long sidedataOffset, int sidedataCompLen, int sidedataCompressionMode) {
        public IndexRecord {
            if (nodeId != null && nodeId.length > 20) {
                nodeId = Arrays.copyOf(nodeId, 20);
            }
        }

        /**
         * Backward-compatible constructor for v1 revlogs and any other call site that has no
         * sidedata to report (v1 has no sidedata at all). Equivalent to the full constructor
         * with {@code sidedataOffset=0}, {@code sidedataCompLen=0} (meaning "no sidedata" — see
         * {@link Revlog#getSidedata(int)}), {@code sidedataCompressionMode=COMP_MODE_PLAIN}.
         */
        public IndexRecord(int revision, long offset, int flags, int compLen, int uncompLen,
                           int baseRev, int linkRev, int parent1, int parent2, byte[] nodeId) {
            this(revision, offset, flags, compLen, uncompLen, baseRev, linkRev, parent1, parent2,
                    nodeId, 0L, 0, 0);
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
        /** Byte offset of this revision's sidedata chunk in the resolved {@code .sda} file (v2 only). */
        public long getSidedataOffset() { return sidedataOffset; }
        /** On-disk (possibly compressed) length of this revision's sidedata chunk; 0 = no sidedata. */
        public int getSidedataCompLen() { return sidedataCompLen; }
        /**
         * Sidedata compression mode: {@code 0}=PLAIN (stored as-is), {@code 1}=DEFAULT
         * (revlog's default compression, zstd — self-describing frame, no length prefix needed),
         * {@code 2}=INLINE (legacy per-chunk marker-byte convention). See
         * {@code mercurial/revlogutils/constants.py} {@code COMP_MODE_*}.
         */
        public int getSidedataCompressionMode() { return sidedataCompressionMode; }
    }

    public Revlog(File idxFile, File datFile) throws IOException {
        this(idxFile, datFile, false);
    }

    public Revlog(File idxFile, File datFile, boolean useZstd) throws IOException {
        this(idxFile, datFile, useZstd, false, false);
    }

    /**
     * @param createAsGeneralV2 see {@link RevlogIndex#RevlogIndex(File, boolean)} -- pass
     *     {@code true} when the owning repository requires {@code exp-revlogv2.2} and
     *     {@code idxFile} may not exist yet, so a brand-new revlog starts out as v2 instead of
     *     silently defaulting to v1.
     */
    public Revlog(File idxFile, File datFile, boolean useZstd, boolean createAsGeneralV2) throws IOException {
        this(idxFile, datFile, useZstd, createAsGeneralV2, false);
    }

    /**
     * @param createAsGeneralV2 see {@link RevlogIndex#RevlogIndex(File, boolean)}.
     * @param usePersistentNodeMap when true and this revlog's store has the
     *     {@code persistent-nodemap} requirement, attempts to load the {@code <radix>.n} trie
     *     next to {@code idxFile} for accelerated node hash to revision lookups
     *     ({@link RevlogIndex#findRevision}). Never fails the constructor -- a missing, stale, or
     *     malformed {@code .n} file is silently ignored and this behaves exactly as if the flag
     *     were {@code false} (see {@link NodeMapFile#tryLoad}).
     */
    public Revlog(File idxFile, File datFile, boolean useZstd, boolean createAsGeneralV2, boolean usePersistentNodeMap) throws IOException {
        this(idxFile, datFile, useZstd, createAsGeneralV2, false, usePersistentNodeMap);
    }

    /**
     * @param createAsChangelogV2 see {@link RevlogIndex#RevlogIndex(File, boolean, boolean,
     *     NodeMapFile)} -- pass {@code true} instead of (never together with) {@code
     *     createAsGeneralV2} when {@code idxFile} may not exist yet and this repository's
     *     requires declare {@code exp-changelog-v2} specifically (the changelog, not a general
     *     {@code exp-revlogv2.2} manifest/filelog).
     */
    public Revlog(File idxFile, File datFile, boolean useZstd, boolean createAsGeneralV2, boolean createAsChangelogV2, boolean usePersistentNodeMap) throws IOException {
        this.idxFile = idxFile;
        this.persistentNodeMapEnabled = usePersistentNodeMap;
        NodeMapFile persistentNodeMap = usePersistentNodeMap ? NodeMapFile.tryLoad(idxFile) : null;
        this.index = new RevlogIndex(idxFile, createAsGeneralV2, createAsChangelogV2, persistentNodeMap, useZstd);
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

    /**
     * Best-effort persistent-nodemap maintenance, called right after a new revision has been
     * durably appended to this revlog (see the {@code appendXxx} methods below) -- mirrors real
     * hg's transaction-finalize {@code persist_nodemap()} (mercurial/revlogutils/nodemap.py), but
     * triggered per-append here since hg4j has no equivalent transaction-batching abstraction at
     * this layer (a single multi-revision operation like a clone/pull thus does several small
     * incremental appends instead of real hg's one larger batched write -- same final on-disk
     * state, more syscalls). Only active when this revlog's owning repository declared the
     * {@code persistent-nodemap} requirement <em>and</em> this revlog is non-inline, matching real
     * hg's own {@code revlog._nodemap_file is None}/{@code revlog._inline} gates (inline revlogs
     * are considered too small for this to be worth it). Never throws -- see {@link
     * NodeMapFile#persist}'s own best-effort contract.
     */
    private void updatePersistentNodeMapAfterAppend() {
        if (!persistentNodeMapEnabled || inline) {
            return;
        }
        NodeMapFile updated = NodeMapFile.persist(idxFile, index.getPersistentNodeMap(), index.getRevisionCount(),
                rev -> index.getIndexRecord(rev).getNodeId());
        index.setPersistentNodeMap(updated);
    }

    public synchronized int getRevisionCount() {
        return index.getRevisionCount();
    }

    public synchronized IndexRecord getIndexRecord(int rev) {
        return index.getIndexRecord(rev);
    }

    /**
     * True when this revlog stores revision data inline within the {@code .i} file itself
     * (no separate {@code .d} file) -- real hg's default layout for any revlog small enough to
     * stay under its inline-size threshold, which in practice covers most manifests/filelogs of
     * a freshly-created or lightly-populated repository. Callers that need to compute physical
     * byte offsets within the index file (e.g. to truncate it) must branch on this: for an
     * inline revlog, consecutive revisions' data is interleaved with their 64-byte headers
     * directly in the {@code .i} file, so a plain {@code revCount * 64} byte offset (correct
     * only for the non-inline layout) would silently discard every revision's payload bytes.
     */
    public synchronized boolean isInline() {
        return inline;
    }

    /**
     * Physical byte offset of revision {@code rev}'s 64-byte index record within the {@code .i}
     * file. For a non-inline revlog this is simply {@code rev * 64}; for an inline revlog it
     * additionally accounts for every preceding revision's interleaved payload bytes.
     */
    public synchronized long getFileOffset(int rev) {
        return index.getFileOffset(rev);
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

    /** {@code flags} bit marking a revision as censored (real hg's {@code REVIDX_ISCENSORED}). */
    public static final int REVIDX_ISCENSORED = 0x8000;

    public synchronized boolean isCensored(int rev) {
        return (getIndexRecord(rev).getFlags() & REVIDX_ISCENSORED) != 0;
    }

    /** {@code compressionMode} value meaning "stored as-is, no compression" (real hg's {@code COMP_MODE_PLAIN}). */
    private static final int COMP_MODE_PLAIN = 0;
    /** {@code compressionMode} value meaning "revlog's default compression" (real hg's {@code COMP_MODE_DEFAULT} — zstd here). */
    private static final int COMP_MODE_DEFAULT = 1;
    /** {@code compressionMode} value meaning "legacy per-chunk marker-byte convention" (real hg's {@code COMP_MODE_INLINE}). */
    private static final int COMP_MODE_INLINE = 2;

    /**
     * Reads and decodes revision {@code rev}'s sidedata block from the revlog-v2 {@code .sda}
     * file (only v2/changelog-v2 revlogs carry sidedata — v1 always returns an empty map).
     * Sidedata is auxiliary per-revision metadata stored alongside (not part of) the revision's
     * hashed content; the changelog uses it to cache copy-tracing info when the repository has
     * the {@code exp-copies-sidedata-changeset} requirement (see {@link
     * io.github.search5.hg4j.api.SidedataChangedFilesCommand} / {@link
     * io.github.search5.hg4j.api.ChangingFiles} for the consumer side of the {@code SD_FILES}
     * key specifically).
     *
     * <p>Real hg's on-disk layout (mercurial/revlog.py {@code sidedata()},
     * mercurial/revlogutils/constants.py): the index record carries a byte offset + on-disk
     * length into the {@code .sda} file plus a 2-bit compression mode for that chunk (distinct
     * from the main data chunk's own compression mode — both are packed into the same index
     * byte, data mode in bits 0-1, sidedata mode in bits 2-3). The decompressed chunk is then an
     * outer sidedata container (see {@link SidedataCodec}) mapping small integer keys to raw
     * byte payloads.
     *
     * @return an empty map if this revision has no sidedata (v1 revlog, or a v2 revision that
     *         simply never got any written — sidedataCompLen == 0)
     */
    public synchronized Map<Integer, byte[]> getSidedata(int rev) throws IOException {
        IndexRecord rec = getIndexRecord(rev);
        if (rec.getSidedataCompLen() <= 0) {
            return java.util.Collections.emptyMap();
        }
        File sdaFile = index.getResolvedSidedataFile();
        if (sdaFile == null || !sdaFile.exists()) {
            throw new HgCorruptDataException("Sidedata file does not exist: " + sdaFile
                    + " (revision " + rev + " claims a sidedata chunk of " + rec.getSidedataCompLen() + " bytes)");
        }

        byte[] chunk;
        try (FileChannel channel = FileChannel.open(sdaFile.toPath(), StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(rec.getSidedataCompLen());
            long position = rec.getSidedataOffset();
            while (buf.hasRemaining()) {
                int read = channel.read(buf, position);
                if (read == -1) break;
                position += read;
            }
            if (buf.hasRemaining()) {
                throw new HgCorruptDataException("Failed to read complete sidedata chunk for revision " + rev
                        + " (offset " + rec.getSidedataOffset() + ", length " + rec.getSidedataCompLen()
                        + ", file " + sdaFile + ")");
            }
            chunk = buf.array();
        }

        byte[] container = decompressSidedataChunk(chunk, rec.getSidedataCompressionMode());
        return SidedataCodec.deserialize(container);
    }

    /**
     * Decompresses one revision's sidedata chunk per its 2-bit compression mode. Unlike the main
     * data chunk (whose uncompressed length is recorded explicitly in the index via {@code
     * uncompLen}), the index carries NO explicit uncompressed-length field for sidedata — real
     * hg's zstd decompressor instead relies on the size embedded in the zstd frame header itself
     * (frames written by a one-shot {@code compress()} call always embed it), which is exactly
     * what {@link Zstd#getFrameContentSize(byte[])} reads back out.
     */
    private byte[] decompressSidedataChunk(byte[] chunk, int compressionMode) throws IOException {
        switch (compressionMode) {
            case COMP_MODE_PLAIN:
                return chunk;
            case COMP_MODE_DEFAULT: {
                long size = Zstd.getFrameContentSize(chunk);
                if (size < 0) {
                    throw new HgCorruptDataException("Invalid zstd sidedata frame: could not determine content size");
                }
                byte[] dest = new byte[(int) size];
                long result = Zstd.decompress(dest, chunk);
                if (Zstd.isError(result)) {
                    throw new HgCorruptDataException("Failed to decompress zstd sidedata chunk: " + Zstd.getErrorName(result));
                }
                return dest;
            }
            case COMP_MODE_INLINE: {
                // Legacy per-hunk marker-byte convention (same one used for v1 data chunks).
                // Only the zstd branch of that convention needs an explicit size hint; give it
                // one from the frame header when the marker byte says zstd, otherwise let
                // DeltaCodec's other branches (zlib/none/uncompressed-prefix/raw) size themselves.
                int hint = 0;
                if (chunk.length > 0 && (chunk[0] & 0xFF) == 0x28) {
                    long frameSize = Zstd.getFrameContentSize(chunk);
                    if (frameSize > 0) {
                        hint = (int) frameSize;
                    }
                }
                return DeltaCodec.decompress(chunk, hint);
            }
            default:
                throw new HgCorruptDataException("Unknown sidedata compression mode: " + compressionMode);
        }
    }

    /**
     * Rewrites this revlog so that {@code censorRev}'s stored payload becomes {@code
     * tombstoneRawContent} and its {@code flags} gains {@link #REVIDX_ISCENSORED} — real hg's
     * {@code hg censor} (mercurial/revlogutils/rewrite.py's {@code v1_censor}). Node identity,
     * parents, and linkrev for every revision (including the censored one) are preserved exactly;
     * only the payload of {@code censorRev} changes. History/DAG shape is untouched.
     *
     * <p>Unlike real hg's rewrite (which keeps each surviving revision's original delta-or-full
     * storage choice), every revision here is rewritten as a full (non-delta) entry — simpler and
     * always correct, at the cost of a larger file than real hg would produce for the same
     * content. This only affects on-disk size, not readability: any reader (hg4j or real hg)
     * reconstructs identical revision content either way.</p>
     *
     * <p>This instance's own cache is refreshed in place via {@link #clearCache()} once the
     * on-disk files are swapped, so it remains usable after this call returns.</p>
     */
    public synchronized void censorRevision(int censorRev, byte[] tombstoneRawContent) throws IOException {
        int count = index.getRevisionCount();
        if (censorRev < 0 || censorRev >= count) {
            throw new HgRevisionNotFoundException(
                    "Revision " + censorRev + " not found. Total revisions: " + count);
        }

        // Capture every revision's raw (as-currently-stored) content and index metadata before
        // touching any file -- getRawRevisionContent()/getIndexRecord() read from the files being
        // rewritten, so this must all happen before the new files start replacing them.
        byte[][] rawContents = new byte[count][];
        IndexRecord[] records = new IndexRecord[count];
        for (int r = 0; r < count; r++) {
            records[r] = getIndexRecord(r);
            rawContents[r] = (r == censorRev) ? tombstoneRawContent : getRawRevisionContent(r);
        }

        File tmpIdx = new File(idxFile.getParentFile(), idxFile.getName() + ".tmpcensored");
        File tmpDat = inline ? null : new File(datFile.getParentFile(), datFile.getName() + ".tmpcensored");
        Files.deleteIfExists(tmpIdx.toPath());
        if (tmpDat != null) {
            Files.deleteIfExists(tmpDat.toPath());
        }

        try {
            long dataOffset = 0;
            try (FileOutputStream idxOut = new FileOutputStream(tmpIdx);
                 FileOutputStream datOut = inline ? null : new FileOutputStream(tmpDat)) {
                for (int r = 0; r < count; r++) {
                    IndexRecord rec = records[r];
                    byte[] content = rawContents[r];
                    int flags = (r == censorRev) ? REVIDX_ISCENSORED : rec.getFlags();
                    byte[] dataHunk = DeltaCodec.compress(content, useZstd);

                    long offsetFlags;
                    if (r == 0) {
                        long formatFlags = inline ? 0x0003L : 0x0002L; // (inline+)generaldelta
                        long version = 1L;
                        offsetFlags = (formatFlags << 48) | (version << 32) | (flags & 0xFFFFL);
                    } else {
                        offsetFlags = (dataOffset << 16) | (flags & 0xFFFFL);
                    }

                    ByteBuffer recordBuf = ByteBuffer.allocate(64);
                    recordBuf.putLong(offsetFlags);
                    recordBuf.putInt(dataHunk.length);
                    recordBuf.putInt(content.length);
                    recordBuf.putInt(r); // baseRev = r: always a full (non-delta) revision
                    recordBuf.putInt(rec.getLinkRev());
                    recordBuf.putInt(rec.getParent1());
                    recordBuf.putInt(rec.getParent2());
                    byte[] node32 = new byte[32];
                    System.arraycopy(rec.getNodeId(), 0, node32, 0, Math.min(20, rec.getNodeId().length));
                    recordBuf.put(node32);

                    idxOut.write(recordBuf.array());
                    if (inline) {
                        idxOut.write(dataHunk);
                    } else {
                        datOut.write(dataHunk);
                    }
                    dataOffset += dataHunk.length;
                }
                idxOut.getFD().sync();
                if (datOut != null) {
                    datOut.getFD().sync();
                }
            }

            // Same ordering real hg's v1_censor uses (index, then data) -- swapping both files
            // together isn't atomic across the pair either way; matched here rather than
            // "improved" so behavior under a mid-swap crash matches what real hg itself accepts.
            Files.move(tmpIdx.toPath(), idxFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            if (tmpDat != null) {
                Files.move(tmpDat.toPath(), datFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        } finally {
            Files.deleteIfExists(tmpIdx.toPath());
            if (tmpDat != null) {
                Files.deleteIfExists(tmpDat.toPath());
            }
        }

        clearCache();
    }

    public synchronized byte[] getRawRevisionContent(int rev) throws IOException {
        if (rev == -1) {
            return new byte[0];
        }

        if (rev < -1 || rev >= getRevisionCount()) {
            throw new HgRevisionNotFoundException("Revision " + rev + " not found. Total revisions: " + getRevisionCount());
        }

        List<Integer> chain = new ArrayList<>();
        int curr = rev;
        Set<Integer> visited = new HashSet<>();
        while (true) {
            if (!visited.add(curr)) {
                throw new HgCorruptDataException("Cycle detected in revlog delta chain at revision: " + curr);
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
            throw new HgCorruptDataException("Revlog data file does not exist: " + targetFile);
        }

        try (FileChannel channel = FileChannel.open(targetFile.toPath(), StandardOpenOption.READ)) {
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
            throw new HgRevisionNotFoundException("Revision " + rev + " not found. Total revisions: " + getRevisionCount());
        }

        if (isCensored(rev)) {
            // Real hg raises error.CensoredNodeError by default (censor.policy != "ignore")
            // rather than silently handing back the tombstone text; getRawRevisionContent()
            // remains available for callers that explicitly want the raw tombstone bytes.
            byte[] rawTombstone = getRawRevisionContent(rev);
            throw new HgCensoredContentException(idxFile.getName(), rev, rawTombstone);
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

    public synchronized Map<String, String> getRevisionMetadata(int rev) throws IOException {
        byte[] raw = getRawRevisionContent(rev);
        Map<String, String> meta = new HashMap<>();
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

    private byte[] readHunk(FileChannel channel, IndexRecord rec) throws IOException {
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
            MappedByteBuffer mapBuf = channel.map(FileChannel.MapMode.READ_ONLY, seekOffset, compLen);
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
            throw new HgCorruptDataException("Failed to read complete hunk of size " + compLen + " at offset " + seekOffset);
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
     * v2(changelog-v2 또는 일반 revlog-v2) 저장소에 새 리비전을 append한다. 실제 hg CLI로
     * 생성한 픽스처를 hexdump/struct로 직접 대조해 검증된 레이아웃을 그대로 재현한다 — 두
     * 포맷 모두 델타 체인 없이 매 리비전을 독립 fulltext로 저장한다(단순화, changelog-v2는
     * 이미 이렇게 구현돼 있었고 일반 v2도 동일 전략 채택 — RevlogV2ParserTest/
     * RevlogV2GeneralParserTest, src/test/resources/fixtures/revlogv2-{changelog,general}/
     * README.md 참고).
     *
     * <p>changelog-v2(매직 0xD34D, {@code INDEX_ENTRY_CL_V2})는 각 리비전을 독립 zstd
     * 프레임(prefix byte 없음, COMP_MODE_DEFAULT)으로 쓴다. 일반 revlog-v2(매직 0xDEAD,
     * {@code exp-revlogv2.2}, {@code INDEX_ENTRY_V2}, 매니페스트/파일로그에 쓰임)는 대신
     * COMP_MODE_PLAIN(압축 없이 원본 그대로)으로 쓴다 — 실제 hg 자신도 압축해도 이득이
     * 없는 작은 리비전에는 PLAIN을 선택하는 정상 인코딩이고(REVLOGV2GeneralParserTest의
     * 실제 hg 픽스처가 정확히 이 형태), zstd 프레임 포맷을 새로 검증해야 하는 위험 없이
     * `mercurial/revlog.py`가 명시적으로 지원하는 {@code compression_mode == COMP_MODE_PLAIN
     * -> uncomp = data} 경로만 쓰면 항상 유효하다. 두 포맷은 레코드 필드 배치도 다르다 —
     * CL_V2는 baseRev/linkRev를 저장하지 않고(rev==rev로 합성) node가 오프셋 24, rank
     * 필드가 있음; 일반 V2는 baseRev/linkRev/parent1/parent2를 전부 명시적으로 저장하고
     * node가 오프셋 32, rank 필드가 없다(패딩만 19바이트).</p>
     */
    private synchronized byte[] appendRevisionV2(int rev, byte[] processedContent, int parent1, int parent2,
                                                   byte[] nodeId, int linkRev) throws IOException {
        return appendRevisionV2(rev, processedContent, parent1, parent2, nodeId, linkRev, null);
    }

    /**
     * Raw zlib/DEFLATE compression with no marker byte prefix (unlike {@link DeltaCodec#compress})
     * -- CL_V2's COMP_MODE_DEFAULT payload is either a genuine compressed frame or exactly the raw
     * content, decided purely by the record's own compression-mode bits, never a leading marker
     * byte. Used only by {@link #appendRevisionV2} when the repository's default engine is zlib
     * (no {@code revlog-compression-zstd} requirement), matching {@code java.util.zip.Deflater}'s
     * default settings -- the same codec real hg itself falls back to.
     */
    private static byte[] deflateNoMarker(byte[] data) {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater();
        deflater.setInput(data);
        deflater.finish();
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream(data.length);
        byte[] buf = new byte[1024];
        try {
            while (!deflater.finished()) {
                int count = deflater.deflate(buf);
                baos.write(buf, 0, count);
            }
        } finally {
            deflater.end();
        }
        return baos.toByteArray();
    }

    /**
     * @param sidedataContainer already-serialized {@link SidedataCodec} outer-container bytes
     *     (see {@link SidedataCodec#serialize}) to attach to this revision, or {@code null} for
     *     none. Written to the {@code .sda} file uncompressed (real hg's {@code COMP_MODE_PLAIN}
     *     — no need to also validate a zstd sidedata frame format, and real hg accepts plain
     *     sidedata chunks equally validly; only used by the changelog when the repository has
     *     {@code exp-copies-sidedata-changeset} — see {@code api.CommitCommand}).
     */
    private synchronized byte[] appendRevisionV2(int rev, byte[] processedContent, int parent1, int parent2,
                                                   byte[] nodeId, int linkRev, byte[] sidedataContainer) throws IOException {
        File resolvedIndexFile = index.getResolvedIndexFile();
        File resolvedDataFile = index.getResolvedDataFile();
        boolean changelogV2 = index.isChangelogV2();

        // CL_V2 compression is chosen dynamically per revision, real hg's own way: try the
        // repository's actual default engine, and only actually use it when it genuinely shrinks
        // the content -- otherwise store the revision's raw bytes as-is (COMP_MODE_PLAIN,
        // complen==uncomplen, NO marker byte) rather than DeltaCodec.compress's v1-revlog-style
        // 'u'+rawdata fallback (that extra marker byte is invalid here: CL_V2 readers expect
        // either a genuine compressed frame or exactly the raw content, decided purely by the
        // per-record compression-mode bits, never a payload-level marker). A prior implementation
        // always hardcoded COMP_MODE_DEFAULT and always ran content through DeltaCodec.compress --
        // this silently produced 'u'-prefixed garbage for any revision short enough that zstd's
        // frame overhead didn't pay for itself (real hg's own fixture,
        // src/test/resources/fixtures/sidedata-copytracing/data.idx, confirms two of its three
        // revisions are genuinely stored PLAIN this way: complen==uncomplen, compression byte
        // 0x00) -- real hg's zstd decompressor then rejected the bogus frame with "Unknown frame
        // descriptor" (found and fixed 2026-09-03, verified against real hg on a from-scratch-
        // bootstrapped changelog-v2 repository, see ChangelogV2BootstrapTest).
        //
        // COMP_MODE_DEFAULT does NOT mean "zstd" unconditionally -- it means "whatever this
        // repository's own default revlog compression engine is", which real hg's reader infers
        // purely from the `revlog-compression-zstd` requirement string (there is no per-record
        // codec discriminator bit), not from anything this method writes. A repository created
        // without that requirement (real hg's own `--config format.usezstd=false`/
        // `format.revlog-compression=zlib`, still a fully valid changelog-v2 repository) uses
        // zlib for COMP_MODE_DEFAULT instead -- unconditionally attempting zstd here produced a
        // zstd frame real hg's zlib-only reader could never decompress ("revlog decompress error:
        // Error -3 while decompressing data: incorrect header check"), a real interop bug found
        // 2026-09-04 by the requirement matrix (see decisions/exhaustive-interop-matrix-plan.md)
        // while committing on top of a real-hg-bootstrapped, non-zstd changelog-v2 repository --
        // `this.useZstd` (populated from that exact requirement string, see the constructor) must
        // gate which codec is attempted, matching real hg's own engine choice byte for byte.
        boolean changelogUsesCompression = false;
        byte[] dataHunk;
        if (changelogV2) {
            byte[] compressAttempt = useZstd ? Zstd.compress(processedContent) : deflateNoMarker(processedContent);
            if (compressAttempt.length < processedContent.length) {
                dataHunk = compressAttempt;
                changelogUsesCompression = true;
            } else {
                dataHunk = processedContent;
            }
        } else {
            dataHunk = processedContent;
        }

        long offset = resolvedDataFile.exists() ? resolvedDataFile.length() : 0;
        try (FileOutputStream out = new FileOutputStream(resolvedDataFile, true)) {
            out.write(dataHunk);
            out.getFD().sync();
        }

        long sidedataOffset = 0L;
        int sidedataCompLen = 0;
        if (sidedataContainer != null && sidedataContainer.length > 0) {
            File sdaFile = index.getResolvedSidedataFile();
            sidedataOffset = sdaFile.exists() ? sdaFile.length() : 0L;
            try (FileOutputStream sdaOut = new FileOutputStream(sdaFile, true)) {
                sdaOut.write(sidedataContainer);
                sdaOut.getFD().sync();
            }
            sidedataCompLen = sidedataContainer.length;
        }

        long offsetFlags = (rev == 0) ? 0 : ((offset << 16));
        byte[] node20 = Arrays.copyOf(nodeId, 20);

        ByteBuffer recordBuf = ByteBuffer.allocate(96);
        recordBuf.putLong(offsetFlags);
        recordBuf.putInt(dataHunk.length);
        recordBuf.putInt(processedContent.length);
        if (changelogV2) {
            // INDEX_ENTRY_CL_V2 = >Qiiii20s12xQiBi23x (96바이트, mercurial/revlogutils/constants.py 실측)
            recordBuf.putInt(parent1);
            recordBuf.putInt(parent2);
            recordBuf.put(node20);
            recordBuf.put(new byte[12]); // 패딩
            recordBuf.putLong(sidedataOffset);
            recordBuf.putInt(sidedataCompLen);
            // 압축 모드(하위 2비트, main data): 위에서 실제로 zstd를 써서 줄어들었을 때만
            // COMP_MODE_DEFAULT(1), 아니면 원본 그대로 저장했으므로 COMP_MODE_PLAIN(0) —
            // 실제 hg 픽스처(sidedata-copytracing/data.idx)로 3개 리비전 다 대조해 확인된
            // 대로 리비전마다 동적으로 다르다(하드코딩 금지, 2026-09-03에 발견·수정된 버그).
            // 상위 2비트(2-3)는 sidedata의 압축 모드(COMP_MODE_PLAIN=0을 쓰므로 00 그대로,
            // 값 변경 불필요) — RevlogIndex의 `(compressionByte >> 2) & 3` 파싱과 대칭.
            recordBuf.put((byte) (changelogUsesCompression ? 1 : 0));
            recordBuf.putInt(rev); // rank (단순화: 선형 히스토리 가정)
            recordBuf.put(new byte[23]); // 패딩
        } else {
            // INDEX_ENTRY_V2 = >Qiiiiii20s12xQiB19x (96바이트, mercurial/revlogutils/constants.py 실측)
            recordBuf.putInt(rev); // baseRev (단순화: 델타 체인 없이 항상 자기 자신 = fulltext)
            recordBuf.putInt(linkRev);
            recordBuf.putInt(parent1);
            recordBuf.putInt(parent2);
            recordBuf.put(node20);
            recordBuf.put(new byte[12]); // 패딩
            recordBuf.putLong(sidedataOffset);
            recordBuf.putInt(sidedataCompLen);
            recordBuf.put((byte) 0); // COMP_MODE_PLAIN (dataHunk == processedContent, 압축 안 함; sidedata도 PLAIN이므로 상위비트 불변)
            recordBuf.put(new byte[19]); // 패딩 (CL_V2와 달리 rank 필드가 없음)
        }
        recordBuf.flip();

        try (FileOutputStream out = new FileOutputStream(resolvedIndexFile, true)) {
            out.write(recordBuf.array());
            out.getFD().sync();
        }

        if (sidedataContainer != null && sidedataContainer.length > 0) {
            index.updateV2DocketSizes(resolvedIndexFile.length(), resolvedDataFile.length(), sidedataOffset + sidedataCompLen);
        } else {
            index.updateV2DocketSizes(resolvedIndexFile.length(), resolvedDataFile.length());
        }

        int recordedLinkRev = changelogV2 ? rev : linkRev;
        index.addRecord(new IndexRecord(rev, offset, 0, dataHunk.length, processedContent.length,
                rev, recordedLinkRev, parent1, parent2, nodeId, sidedataOffset, sidedataCompLen, 0));
        updatePersistentNodeMapAfterAppend();

        byte[] hash = new byte[20];
        System.arraycopy(nodeId, 0, hash, 0, 20);
        return hash;
    }

    public synchronized byte[] appendRevision(byte[] content, Map<String, String> metadata, int parent1, int parent2,
                                 byte[] p1Node, byte[] p2Node, int linkRev) throws IOException {
        return appendRevision(content, metadata, parent1, parent2, p1Node, p2Node, linkRev, null);
    }

    /**
     * @param sidedataContainer already-serialized {@link SidedataCodec} bytes to attach to this
     *     revision (only meaningful for a v2 revlog -- silently ignored otherwise, matching real
     *     hg where sidedata is a revlog-v2-only feature), or {@code null} for none. Used by {@code
     *     api.CommitCommand} to write {@code SD_FILES} copy-tracing sidedata on the changelog
     *     revision when the repository has {@code exp-copies-sidedata-changeset}.
     */
    public synchronized byte[] appendRevision(byte[] content, Map<String, String> metadata, int parent1, int parent2,
                                 byte[] p1Node, byte[] p2Node, int linkRev, byte[] sidedataContainer) throws IOException {
        int rev = index.getRevisionCount();

        // Escaping logic for content and metadata
        byte[] processedContent;
        if (metadata != null && !metadata.isEmpty()) {
            StringBuilder msb = new StringBuilder();
            msb.append('\u0001').append('\n');
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
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

        // A revlog node id is exactly hash(p1, p2, content) -- if a revision with that identical
        // (parents, content) triple already exists anywhere in this revlog, real hg's own
        // revlog.addrevision()/filelog.add() reuse it instead of appending a byte-for-byte
        // duplicate node id (two index entries sharing one node id is a corrupt revlog, flagged
        // by `hg verify` as "duplicate revision"). This matters well beyond a curiosity: any
        // caller that re-adds an existing file's content with no filelog parent of its own (e.g.
        // RebaseCommand cherry-picking a revision that added a file fresh onto a destination that
        // never had that path -- verified live: without this check, real hg's `hg verify` on such
        // a rebase's output reports "duplicate revision N (M)" and "<node> not in manifests") would
        // otherwise corrupt the store on every such call.
        int existingRev = findRevision(nodeId);
        if (existingRev != -1) {
            return getIndexRecord(existingRev).getNodeId();
        }

        if (index.isV2()) {
            return appendRevisionV2(rev, processedContent, parent1, parent2, nodeId, linkRev, sidedataContainer);
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
        updatePersistentNodeMapAfterAppend();

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
        if (entry.fullText) {
            // cg4 전용(실제 스펙: mercurial/changegroup.py의 cg4unpacker.deltachunk —
            // protocol_flags & CG_FLAG_FULL_TEXT가 서 있으면 페이로드는 bdiff 델타가 아니라
            // 압축 없는 원문 그대로다. deltabase 값과 무관하게 그대로 콘텐츠로 쓴다.
            content = entry.delta;
        } else if (entry.deltabase != null) {
            int baseRev = findRevision(entry.deltabase);
            if (baseRev == -1) {
                if (NodeIdUtil.isAllZero(entry.deltabase)) {
                    content = applyDelta(new byte[0], entry.delta);
                } else {
                    throw new HgCorruptDataException("Delta base revision not found in local index: " + NodeIdUtil.toHex(entry.deltabase) + " for commit: " + NodeIdUtil.toHex(entry.node));
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

        // E3: Verify node hash integrity from remote -- skipped for censored content. A censored
        // revision's node identity is intentionally preserved from BEFORE censoring while its
        // content is replaced with a tombstone, so hash(parents, tombstone) can never equal the
        // transmitted node by design; treating that mismatch as corruption would make it
        // impossible to ever pull/clone a repository containing a censored revision.
        if (!isCensoredText(content)) {
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
                throw new HgCorruptDataException("Security Integrity Error: Changegroup entry hash mismatch! Expected: "
                    + NodeIdUtil.toHex(expectedNodeId) + " but received: " + NodeIdUtil.toHex(remoteNodeId));
            }
        }

        // 백로그 26번: 이 revlog가 이미 v2 포맷(가장 흔하게는 exp-copies-sidedata-changeset이
        // 켜진 changelog)이면 아래 v1 전용 수동 바이트 라이팅 경로를 절대 타면 안 된다 --
        // 인덱스 레코드 크기 자체가 다르다(64바이트 대 96바이트, 필드 배치도 다름). 대신
        // appendRevisionV2를 그대로 재사용해 로컬 커밋(CommitCommand)이 만드는 것과 완전히
        // 동일한 온디스크 레이아웃으로 남긴다 -- entry.sidedata(cg5의 CG_FLAG_SIDEDATA로 온
        // 원시 sidedata 컨테이너 바이트, 없으면 null)가 있으면 그대로 .sda에 반영된다.
        // entry.sidedata는 이미 SidedataCodec이 쓰는 것과 같은 "이미 직렬화된 외부 컨테이너"
        // 포맷이라 재인코딩 없이 곧장 넘길 수 있다 -- 이전엔 이 메서드가 index.isV2()를 전혀
        // 확인하지 않아 v2 revlog에 pull/push로 들어오는 리비전을 전부 v1 레이아웃으로
        // 깨뜨렸고(사이드 이펙트로, 받은 cg5 sidedata도 통째로 버려졌다), 로컬 커밋에만
        // 쓰이던 backlog 19의 sidedata 저장 능력이 changegroup 적용 경로에는 전혀 연결돼 있지
        // 않았다.
        if (index.isV2()) {
            appendRevisionV2(rev, content, parent1, parent2, entry.node, linkRev, entry.sidedata);
            clearCache();
            return;
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

        // A censored revision must always be stored as a full (non-delta) entry: real hg forbids
        // deltas against (or of) a censored revision (revlog.py's iscensored()+delta rejection),
        // since a delta can't sensibly reconstruct a tombstone that replaced arbitrary-length
        // original content.
        int flags = isCensoredText(content) ? REVIDX_ISCENSORED : 0;

        if (!isMetadataLog && flags == 0 && rev > 0 && parent1 != -1 && chainLen < 100 && !isCensored(parent1)) {
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
            offsetFlags = (formatFlags << 48) | (version << 32) | (flags & 0xFFFFL);
        } else {
            offsetFlags = (offset << 16) | (flags & 0xFFFFL);
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

        index.addRecord(new IndexRecord(rev, offset, flags, dataHunk.length, content.length,
                baseRev, linkRev, parent1, parent2, entry.node));
        updatePersistentNodeMapAfterAppend();

        clearCache();
    }

    /**
     * Detects real hg's censor tombstone marker in as-stored revision text: a {@code \x01\n}
     * metadata header whose key/value lines include a {@code censored} key (mirrors
     * {@code mercurial/utils/storageutil.py}'s {@code iscensoredtext}/{@code parsemeta}). Used as
     * a fallback to recover the {@link #REVIDX_ISCENSORED} flag for changegroup formats that
     * don't carry an explicit per-entry flags field (cg1/cg2 — only cg3 does), exactly the way
     * real hg's own {@code revlog.py} peeks at incoming delta/fulltext content
     * ({@code _peek_iscensored}) to reconstruct the flag when it isn't explicitly transmitted.
     */
    static boolean isCensoredText(byte[] content) {
        if (content == null || content.length < 2 || content[0] != '' || content[1] != '\n') {
            return false;
        }
        int metaEnd = -1;
        for (int i = 2; i < content.length - 1; i++) {
            if (content[i] == '' && content[i + 1] == '\n') {
                metaEnd = i;
                break;
            }
        }
        if (metaEnd <= 2) {
            return false;
        }
        String metaText = new String(content, 2, metaEnd - 2, StandardCharsets.UTF_8);
        for (String line : metaText.split("\n")) {
            int colon = line.indexOf(':');
            if (colon != -1 && line.substring(0, colon).equals("censored")) {
                return true;
            }
        }
        return false;
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

        byte[] nodeId32 = new byte[32];
        System.arraycopy(node, 0, nodeId32, 0, 20);

        // BUGFIX: this method used to always write dataHunk to datFile and a bare 64-byte record to
        // idxFile, ignoring this.inline entirely. For a revlog reopened from an on-disk *inline*
        // layout (real hg stores small filelogs inline -- exactly what RebaseCommand reopens and
        // calls this method on when restoring filelog/manifest/changelog backups), that silently
        // wrote the new revision's data to the wrong place: readHunk()'s inline path ignores the
        // record's offset field and instead seeks to index.getFileOffset(rev) + 64 inside idxFile,
        // so the data written to datFile was never found there, corrupting the very next read of
        // that revision. Fixed by branching on inline exactly like appendRevision()/
        // appendOptimizedRevision() already do.
        long offset;
        if (inline) {
            offset = 0;
            if (rev > 0) {
                IndexRecord prevRec = getIndexRecord(rev - 1);
                offset = prevRec.getOffset() + prevRec.getCompLen();
            }

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
            recordBuf.putInt(rawToWrite.length); // uncompLen
            recordBuf.putInt(rev); // baseRev
            recordBuf.putInt(linkRev);
            recordBuf.putInt(parent1);
            recordBuf.putInt(parent2);
            recordBuf.put(nodeId32);

            try (FileOutputStream out = new FileOutputStream(idxFile, true)) {
                out.write(recordBuf.array());
                out.write(dataHunk);
                out.getFD().sync();
            }
        } else {
            offset = datFile.exists() ? datFile.length() : 0;

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
            recordBuf.put(nodeId32);

            try (FileOutputStream out = new FileOutputStream(idxFile, true)) {
                out.write(recordBuf.array());
                out.getFD().sync();
            }
        }

        index.addRecord(new IndexRecord(rev, offset, 0, dataHunk.length, rawToWrite.length,
                rev, linkRev, parent1, parent2, node));
        updatePersistentNodeMapAfterAppend();

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
        updatePersistentNodeMapAfterAppend();

        clearCache();
    }
}
