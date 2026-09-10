package io.github.search5.hg4j.storage;
import io.github.search5.hg4j.diff.DeltaEngine;
import io.github.search5.hg4j.bundle.ChangegroupParser;
import io.github.search5.hg4j.util.NodeIdUtil;
import io.github.search5.hg4j.util.SafeFileIO;
import com.github.luben.zstd.Zstd;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
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
import io.github.search5.hg4j.lfs.HgLfsPointer;
import java.io.ByteArrayOutputStream;
import java.io.FileWriter;
import java.io.Writer;
import java.util.Collections;
import java.util.zip.Deflater;

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

    /**
     * One revision's parsed 64-byte (or general-v2/changelog-v2 wider) index record: the physical
     * location and length of its data, its delta base and link/parent revisions, its node id, and
     * (v2 only) its sidedata location and rank.
     *
     * @param revision the revision number (0-based)
     * @param offset byte offset of this revision's data within the {@code .d} file (or, for an
     *     inline revlog, within the {@code .i} file itself)
     * @param flags per-revision flag bits (e.g. censored, external storage)
     * @param compLen on-disk (possibly compressed) length of this revision's data
     * @param uncompLen length of this revision's fully reconstructed (delta-applied,
     *     decompressed) content
     * @param baseRev the revision this one deltas against, or itself (or its own revision number)
     *     for a full-text snapshot
     * @param linkRev the changelog revision that introduced this revision
     * @param parent1 first parent's revision number, or {@code -1} if none
     * @param parent2 second parent's revision number, or {@code -1} if none
     * @param nodeId this revision's 20-byte SHA-1 node id
     * @param sidedataOffset byte offset of this revision's sidedata chunk in the resolved
     *     {@code .sda} file (v2 only)
     * @param sidedataCompLen on-disk (possibly compressed) length of this revision's sidedata
     *     chunk; {@code 0} means no sidedata
     * @param sidedataCompressionMode sidedata compression mode: {@code 0}=PLAIN, {@code 1}=DEFAULT
     *     (zstd), {@code 2}=INLINE
     * @param rank the changelog-v2-only {@code rank} field ({@code mercurial/revlogutils/
     *     constants.py}'s {@code RANK_UNKNOWN = -1} sentinel when not applicable/not persisted --
     *     every non-CHANGELOGV2 record uses this default via the compatibility constructor below).
     *     Real hg defines it as "the size of the set ancestors(r), r included" and computes it
     *     recursively as revisions are appended (see real hg's {@code revlog.py}
     *     {@code addrevision}/{@code fast_rank}); {@link Revlog#appendRevisionV2} mirrors that
     *     exact recursion for CL_V2 records instead of just writing {@code rev} -- a lone initial
     *     commit's rank must be {@code 1}, not {@code 0}, or every later rank computed on top
     *     drifts by one from what real hg would compute for the same history.
     */
    public record IndexRecord(int revision, long offset, int flags, int compLen, int uncompLen,
                             int baseRev, int linkRev, int parent1, int parent2, byte[] nodeId,
                             long sidedataOffset, int sidedataCompLen, int sidedataCompressionMode,
                             int rank) {
        /** Defensively truncates an over-long {@code nodeId} to the canonical 20 bytes. */
        public IndexRecord {
            if (nodeId != null && nodeId.length > 20) {
                nodeId = Arrays.copyOf(nodeId, 20);
            }
        }

        /**
         * Backward-compatible constructor for every call site that doesn't carry sidedata/rank
         * information (v1 revlogs, and general-v2 {@code INDEX_ENTRY_V2} records which have no
         * {@code rank} field at all). Equivalent to the full constructor with
         * {@code sidedataOffset=0}, {@code sidedataCompLen=0} (meaning "no sidedata" — see
         * {@link Revlog#getSidedata(int)}), {@code sidedataCompressionMode=COMP_MODE_PLAIN}, and
         * {@code rank=-1} (real hg's own {@code RANK_UNKNOWN} sentinel).
         *
         * @param revision the revision number (0-based)
         * @param offset byte offset of this revision's data within the {@code .d} file (or,
         *     for an inline revlog, within the {@code .i} file itself)
         * @param flags per-revision flag bits
         * @param compLen on-disk (possibly compressed) length of this revision's data
         * @param uncompLen length of this revision's fully reconstructed content
         * @param baseRev the revision this one deltas against, or itself for a full-text snapshot
         * @param linkRev the changelog revision that introduced this revision
         * @param parent1 first parent's revision number, or {@code -1} if none
         * @param parent2 second parent's revision number, or {@code -1} if none
         * @param nodeId this revision's 20-byte SHA-1 node id
         * @param sidedataOffset byte offset of this revision's sidedata chunk (v2 only)
         * @param sidedataCompLen on-disk length of this revision's sidedata chunk; {@code 0}
         *     means no sidedata
         * @param sidedataCompressionMode sidedata compression mode: {@code 0}=PLAIN,
         *     {@code 1}=DEFAULT (zstd), {@code 2}=INLINE
         */
        public IndexRecord(int revision, long offset, int flags, int compLen, int uncompLen,
                           int baseRev, int linkRev, int parent1, int parent2, byte[] nodeId,
                           long sidedataOffset, int sidedataCompLen, int sidedataCompressionMode) {
            this(revision, offset, flags, compLen, uncompLen, baseRev, linkRev, parent1, parent2,
                    nodeId, sidedataOffset, sidedataCompLen, sidedataCompressionMode, -1);
        }

        /**
         * Backward-compatible constructor for v1 revlogs and any other call site that has no
         * sidedata to report (v1 has no sidedata at all). Equivalent to the full constructor
         * with {@code sidedataOffset=0}, {@code sidedataCompLen=0} (meaning "no sidedata" — see
         * {@link Revlog#getSidedata(int)}), {@code sidedataCompressionMode=COMP_MODE_PLAIN}.
         *
         * @param revision the revision number (0-based)
         * @param offset byte offset of this revision's data within the {@code .d} file (or,
         *     for an inline revlog, within the {@code .i} file itself)
         * @param flags per-revision flag bits
         * @param compLen on-disk (possibly compressed) length of this revision's data
         * @param uncompLen length of this revision's fully reconstructed content
         * @param baseRev the revision this one deltas against, or itself for a full-text snapshot
         * @param linkRev the changelog revision that introduced this revision
         * @param parent1 first parent's revision number, or {@code -1} if none
         * @param parent2 second parent's revision number, or {@code -1} if none
         * @param nodeId this revision's 20-byte SHA-1 node id
         */
        public IndexRecord(int revision, long offset, int flags, int compLen, int uncompLen,
                           int baseRev, int linkRev, int parent1, int parent2, byte[] nodeId) {
            this(revision, offset, flags, compLen, uncompLen, baseRev, linkRev, parent1, parent2,
                    nodeId, 0L, 0, 0, -1);
        }

        /**
         * Returns the revision number.
         * @return the revision number (0-based)
         */
        public int getRevision() { return revision; }
        /**
         * Returns the byte offset of this revision's data.
         * @return the byte offset within the {@code .d} (or inline {@code .i}) file
         */
        public long getOffset() { return offset; }
        /**
         * Returns this revision's flag bits.
         * @return the per-revision flag bits
         */
        public int getFlags() { return flags; }
        /**
         * Returns the on-disk length of this revision's data.
         * @return the (possibly compressed) on-disk length
         */
        public int getCompLen() { return compLen; }
        /**
         * Returns the length of this revision's fully reconstructed content.
         * @return the decompressed, delta-applied content length
         */
        public int getUncompLen() { return uncompLen; }
        /**
         * Returns the revision this one deltas against.
         * @return the base revision number, or this revision's own number for a full-text snapshot
         */
        public int getBaseRev() { return baseRev; }
        /**
         * Returns the changelog revision that introduced this revision.
         * @return the link revision number
         */
        public int getLinkRev() { return linkRev; }
        /**
         * Returns this revision's first parent.
         * @return the first parent's revision number, or {@code -1} if none
         */
        public int getParent1() { return parent1; }
        /**
         * Returns this revision's second parent.
         * @return the second parent's revision number, or {@code -1} if none
         */
        public int getParent2() { return parent2; }
        /**
         * Returns this revision's node id.
         * @return the 20-byte SHA-1 node id
         */
        public byte[] getNodeId() { return nodeId; }
        /**
         * Byte offset of this revision's sidedata chunk in the resolved {@code .sda} file (v2 only).
         * @return the sidedata byte offset
         */
        public long getSidedataOffset() { return sidedataOffset; }
        /**
         * On-disk (possibly compressed) length of this revision's sidedata chunk; 0 = no sidedata.
         * @return the on-disk sidedata chunk length
         */
        public int getSidedataCompLen() { return sidedataCompLen; }
        /**
         * Sidedata compression mode: {@code 0}=PLAIN (stored as-is), {@code 1}=DEFAULT
         * (revlog's default compression, zstd — self-describing frame, no length prefix needed),
         * {@code 2}=INLINE (legacy per-chunk marker-byte convention). See
         * {@code mercurial/revlogutils/constants.py} {@code COMP_MODE_*}.
         * @return the sidedata compression mode
         */
        public int getSidedataCompressionMode() { return sidedataCompressionMode; }
        /**
         * See the class-level {@code @param rank} javadoc above.
         * @return the changelog-v2 rank, or {@code -1} ({@code RANK_UNKNOWN}) if not applicable
         */
        public int getRank() { return rank; }
    }

    /**
     * Opens (or prepares to create) a plain, non-zstd, v1 revlog.
     *
     * @param idxFile the revlog's {@code .i} index file
     * @param datFile the revlog's {@code .d} data file (used only for a non-inline v1 revlog)
     * @throws IOException if the index or data file cannot be read
     */
    public Revlog(File idxFile, File datFile) throws IOException {
        this(idxFile, datFile, false);
    }

    /**
     * Opens (or prepares to create) a v1 revlog, optionally zstd-compressed.
     *
     * @param idxFile the revlog's {@code .i} index file
     * @param datFile the revlog's {@code .d} data file (used only for a non-inline revlog)
     * @param useZstd whether new revisions should be compressed with zstd rather than zlib
     * @throws IOException if the index or data file cannot be read
     */
    public Revlog(File idxFile, File datFile, boolean useZstd) throws IOException {
        this(idxFile, datFile, useZstd, false, false);
    }

    /**
     * Opens (or prepares to create) a revlog, optionally as a general v2 revlog.
     *
     * @param idxFile the revlog's {@code .i} index file
     * @param datFile the revlog's {@code .d} data file (used only for a non-inline v1 revlog)
     * @param useZstd whether new revisions should be compressed with zstd rather than zlib
     * @param createAsGeneralV2 see {@link RevlogIndex#RevlogIndex(File, boolean)} -- pass
     *     {@code true} when the owning repository requires {@code exp-revlogv2.2} and
     *     {@code idxFile} may not exist yet, so a brand-new revlog starts out as v2 instead of
     *     silently defaulting to v1.
     * @throws IOException if the index or data file cannot be read
     */
    public Revlog(File idxFile, File datFile, boolean useZstd, boolean createAsGeneralV2) throws IOException {
        this(idxFile, datFile, useZstd, createAsGeneralV2, false);
    }

    /**
     * Opens (or prepares to create) a revlog, optionally with a persistent node map.
     *
     * @param idxFile the revlog's {@code .i} index file
     * @param datFile the revlog's {@code .d} data file (used only for a non-inline v1 revlog)
     * @param useZstd whether new revisions should be compressed with zstd rather than zlib
     * @param createAsGeneralV2 see {@link RevlogIndex#RevlogIndex(File, boolean)}.
     * @param usePersistentNodeMap when true and this revlog's store has the
     *     {@code persistent-nodemap} requirement, attempts to load the {@code <radix>.n} trie
     *     next to {@code idxFile} for accelerated node hash to revision lookups
     *     ({@link RevlogIndex#findRevision}). Never fails the constructor -- a missing, stale, or
     *     malformed {@code .n} file is silently ignored and this behaves exactly as if the flag
     *     were {@code false} (see {@link NodeMapFile#tryLoad}).
     * @throws IOException if the index or data file cannot be read
     */
    public Revlog(File idxFile, File datFile, boolean useZstd, boolean createAsGeneralV2, boolean usePersistentNodeMap) throws IOException {
        this(idxFile, datFile, useZstd, createAsGeneralV2, false, usePersistentNodeMap);
    }

    /**
     * Opens (or prepares to create) a revlog with full control over its v2 flavor and node-map
     * usage. This is the designated constructor every other {@code Revlog} constructor delegates
     * to.
     *
     * @param idxFile the revlog's {@code .i} index file
     * @param datFile the revlog's {@code .d} data file (used only for a non-inline v1 revlog; a
     *     v2 revlog's actual data file is instead discovered from its docket)
     * @param useZstd whether new revisions should be compressed with zstd rather than zlib
     * @param createAsGeneralV2 see {@link RevlogIndex#RevlogIndex(File, boolean)}.
     * @param createAsChangelogV2 see {@link RevlogIndex#RevlogIndex(File, boolean, boolean,
     *     NodeMapFile)} -- pass {@code true} instead of (never together with) {@code
     *     createAsGeneralV2} when {@code idxFile} may not exist yet and this repository's
     *     requires declare {@code exp-changelog-v2} specifically (the changelog, not a general
     *     {@code exp-revlogv2.2} manifest/filelog).
     * @param usePersistentNodeMap when true and this revlog's store has the
     *     {@code persistent-nodemap} requirement, attempts to load the persistent node-map trie
     *     next to {@code idxFile}
     * @throws IOException if the index or data file cannot be read
     */
    public Revlog(File idxFile, File datFile, boolean useZstd, boolean createAsGeneralV2, boolean createAsChangelogV2, boolean usePersistentNodeMap) throws IOException {
        this.idxFile = idxFile;
        this.persistentNodeMapEnabled = usePersistentNodeMap;
        NodeMapFile persistentNodeMap = usePersistentNodeMap ? NodeMapFile.tryLoad(idxFile) : null;
        this.index = new RevlogIndex(idxFile, createAsGeneralV2, createAsChangelogV2, persistentNodeMap, useZstd);
        if (index.isV2()) {
            // v2 is always non-inline, and the actual data file is discovered from the docket's
            // UUID -- the datFile passed to the constructor (e.g. "00changelog.d") does not
            // exist in a v2 store.
            this.datFile = index.getResolvedDataFile();
            this.inline = false;
        } else {
            this.datFile = datFile;
            // Real hg's revlogv1 starts every filelog/manifest INLINE by default and only splits
            // to a separate .d file past 131072 bytes (mercurial/revlog.py:
            // REVLOG_DEFAULT_FLAGS=FLAG_INLINE_DATA, _maxinline, _enforceinlinesize; changelog.py
            // opts out with may_inline=False). `index.isInline()` only reflects what an EXISTING
            // on-disk index's first record says -- for a brand-new revlog (idxFile doesn't exist
            // yet) it's always false, so the inline/non-inline choice for a new revlog must be
            // decided here instead. All three write paths (appendRevision,
            // appendChangeGroupEntry, appendRawRevision/appendOptimizedRevision) branch on
            // `inline` consistently, so it's safe to default new non-changelog v1 revlogs to
            // inline.
            boolean isNewRevlog = !idxFile.exists();
            boolean isChangelog = idxFile.getName().contains("00changelog");
            this.inline = isNewRevlog ? !isChangelog : index.isInline();
        }
        this.useZstd = useZstd;
    }

    /**
     * Returns the underlying parsed index for this revlog.
     *
     * @return the revlog's {@link RevlogIndex}
     */
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

    /**
     * Returns the number of revisions currently stored in this revlog.
     *
     * @return the revision count
     */
    public synchronized int getRevisionCount() {
        return index.getRevisionCount();
    }

    /**
     * Returns the parsed index record for one revision.
     *
     * @param rev the revision number (0-based)
     * @return the parsed {@link IndexRecord} for that revision
     */
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
     *
     * @return {@code true} if revision data is stored inline in the {@code .i} file
     */
    public synchronized boolean isInline() {
        return inline;
    }

    /**
     * Physical byte offset of revision {@code rev}'s 64-byte index record within the {@code .i}
     * file. For a non-inline revlog this is simply {@code rev * 64}; for an inline revlog it
     * additionally accounts for every preceding revision's interleaved payload bytes.
     *
     * @param rev the revision number (0-based)
     * @return the byte offset of that revision's index record within the {@code .i} file
     */
    public synchronized long getFileOffset(int rev) {
        return index.getFileOffset(rev);
    }

    /**
     * Truncates this revlog in place so only revisions {@code [0, keepCount)} survive, handling
     * all three on-disk layouts uniformly -- used by {@code StripCommand}. Must be called on a
     * {@code Revlog} instance loaded from the still-untruncated files (its own {@code
     * getIndexRecord}/{@code getFileOffset} reads must reflect the PRE-truncation state to compute
     * the correct cut points), and only once per instance (its own view of the revlog is stale
     * afterwards -- callers must {@code clearRevlogCache()} the owning repository before reopening
     * it).
     *
     * <ol>
     *   <li><b>v2 / docket-based</b> (changelog-v2 or general-v2, {@link RevlogIndex#isV2()}):
     *   the physical index/data companion files are resolved via the docket's UUIDs (not {@code
     *   idxFile}/{@code datFile} literally), records are a fixed {@value RevlogIndex#V2_RECORD_SIZE}
     *   bytes each, and the docket header's own {@code index_end}/{@code data_end} fields (plus
     *   their {@code pending} twins) must be rewritten to match or a later reopen sees stale
     *   lengths and real hg rejects the revlog as corrupt ({@link
     *   RevlogIndex#updateV2DocketSizes(long, long)}).
     *   <li><b>inline v1</b> ({@link #isInline()}): data is interleaved with each 64-byte header
     *   directly inside {@code idxFile}, no separate data file exists at all -- only {@code
     *   idxFile} is truncated, to {@link #getFileOffset} of the first discarded revision (which
     *   already accounts for every preceding revision's interleaved bytes).
     *   <li><b>non-inline v1</b>: the original fixed-64-bytes-per-record {@code idxFile} truncation
     *   plus an exact-byte-offset {@code datFile} truncation (never the old "assume every revision
     *   is the same size" estimate -- see the {@code StripCommand} history this replaced).
     * </ol>
     *
     * <p>Handling all three cases here (rather than the non-inline-v1-only truncation
     * {@code StripCommand} used to do on its own) matters: truncating an inline-v1 manifest/
     * filelog with the non-inline logic corrupts it (real hg then aborts with "index 00manifest
     * is corrupted"), and a v2/docket-based changelog needs its docket end-pointers updated too,
     * or real hg's {@code verify} reports "changeset refers to unknown revision" for the stale
     * pointers.
     *
     * @param keepCount the number of revisions to keep; every revision from this index onward is
     *     discarded
     * @throws IOException if the index, data, or docket files cannot be read or truncated
     */
    public synchronized void truncate(int keepCount) throws IOException {
        if (index.isV2()) {
            File resolvedIdx = index.getResolvedIndexFile();
            File resolvedDat = index.getResolvedDataFile();
            long newIndexEnd = (long) keepCount * RevlogIndex.V2_RECORD_SIZE;
            long newDataEnd;
            if (keepCount == 0) {
                newDataEnd = 0;
            } else if (keepCount < getRevisionCount()) {
                newDataEnd = getIndexRecord(keepCount).getOffset();
            } else {
                newDataEnd = (resolvedDat != null && resolvedDat.exists()) ? resolvedDat.length() : 0;
            }
            if (resolvedIdx != null && resolvedIdx.exists()) {
                try (RandomAccessFile raf = new RandomAccessFile(resolvedIdx, "rw")) {
                    raf.setLength(newIndexEnd);
                }
            }
            if (resolvedDat != null && resolvedDat.exists()) {
                try (RandomAccessFile raf = new RandomAccessFile(resolvedDat, "rw")) {
                    raf.setLength(newDataEnd);
                }
            }
            index.updateV2DocketSizes(newIndexEnd, newDataEnd);
            return;
        }

        if (!idxFile.exists()) {
            return;
        }

        if (inline) {
            if (keepCount == 0) {
                // DELETE rather than zero-length: a zero-length-but-still-EXISTING .i file makes
                // this same file's own constructor treat a later reopen as "existing" (`isNewRevlog
                // = !idxFile.exists()` is false) instead of "brand new", which reads `index.isInline()`
                // off the (empty, no records to read flags from) file instead of defaulting to
                // inline. Deleting instead matches what this repository's OWN filelog-truncate-to-zero
                // path (StripCommand.call()'s `if (flKeepCount == 0) { flIdx.delete(); ... }`) already
                // did.
                Files.deleteIfExists(idxFile.toPath());
                return;
            }
            long targetIdxSize;
            if (keepCount < getRevisionCount()) {
                targetIdxSize = getFileOffset(keepCount);
            } else {
                targetIdxSize = idxFile.length();
            }
            try (RandomAccessFile raf = new RandomAccessFile(idxFile, "rw")) {
                raf.setLength(targetIdxSize);
            }
            // Inline revlogs never have a companion .d file -- leave datFile (if one somehow
            // exists, e.g. stale from an unrelated operation) untouched.
            return;
        }

        if (keepCount == 0) {
            // See the inline branch above for why delete (not zero-length) is required.
            Files.deleteIfExists(idxFile.toPath());
            if (datFile != null) {
                Files.deleteIfExists(datFile.toPath());
            }
            return;
        }

        // Compute the .d target size (and read getRevisionCount()) BEFORE touching idxFile at all:
        // truncating idxFile FIRST and only THEN calling getRevisionCount()/getIndexRecord() for
        // the .d computation is wrong, because RevlogIndex.checkAndUpdate() notices idxFile's size
        // just changed (its own physical file, bypassed via RandomAccessFile rather than through
        // the index's normal write path) and eagerly re-derives revisionCount from the
        // now-ALREADY-shrunk .i file -- so `keepCount < getRevisionCount()` silently flips from
        // true to false mid-computation and the .d file is left completely untruncated (real hg's
        // `hg verify` then reports "changelog@?: data length off by N bytes", N being exactly the
        // stripped revision's own compLen). Offsets must be read from the still-untruncated
        // revlog before mutating anything.
        long targetDatSize = -1;
        if (datFile != null && datFile.exists()) {
            targetDatSize = (keepCount < getRevisionCount())
                    ? getIndexRecord(keepCount).getOffset()
                    : datFile.length();
        }

        long keepIndexLength = (long) keepCount * 64;
        try (RandomAccessFile rafIdx = new RandomAccessFile(idxFile, "rw")) {
            rafIdx.setLength(keepIndexLength);
        }
        if (targetDatSize >= 0) {
            try (RandomAccessFile rafDat = new RandomAccessFile(datFile, "rw")) {
                rafDat.setLength(targetDatSize);
            }
        }
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

    /**
     * {@code flags} bit marking a revision's stored text as an external-storage pointer rather
     * than the real file content (real hg's {@code REVIDX_EXTSTORED}, used by the {@code lfs}
     * extension; see {@code mercurial/interfaces/repository.py}'s
     * {@code REVISION_FLAG_EXTSTORED = 1 << 13}).
     */
    public static final int REVIDX_EXTSTORED = 0x2000;

    /**
     * Returns whether a revision is marked censored.
     *
     * @param rev the revision number (0-based)
     * @return {@code true} if {@link #REVIDX_ISCENSORED} is set on that revision's flags
     */
    public synchronized boolean isCensored(int rev) {
        return (getIndexRecord(rev).getFlags() & REVIDX_ISCENSORED) != 0;
    }

    /**
     * Returns whether a revision's stored text is an external-storage pointer rather than actual
     * file content.
     *
     * @param rev the revision number (0-based)
     * @return {@code true} if {@link #REVIDX_EXTSTORED} is set on that revision's flags
     */
    public synchronized boolean isExtStored(int rev) {
        return (getIndexRecord(rev).getFlags() & REVIDX_EXTSTORED) != 0;
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
     * @param rev the revision number (0-based)
     * @return an empty map if this revision has no sidedata (v1 revlog, or a v2 revision that
     *         simply never got any written — sidedataCompLen == 0)
     * @throws IOException if the sidedata file is missing when the index record claims a
     *     non-empty sidedata chunk, or cannot be read/decompressed/decoded
     */
    public synchronized Map<Integer, byte[]> getSidedata(int rev) throws IOException {
        IndexRecord rec = getIndexRecord(rev);
        if (rec.getSidedataCompLen() <= 0) {
            return Collections.emptyMap();
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
                // COMP_MODE_DEFAULT does NOT mean "zstd" unconditionally here either -- exactly
                // the same "whatever this repository's actual default engine is" point
                // appendRevisionV2's javadoc makes for the MAIN data chunk applies equally to
                // sidedata. A changelog-v2+sidedata repository created WITHOUT the
                // revlog-compression-zstd requirement (real hg's own format.usezstd=false /
                // format.revlog-compression=zlib) compresses its sidedata with plain zlib/DEFLATE
                // instead when compression actually shrinks it, so unconditionally attempting
                // zstd here would fail on perfectly valid real-hg-written data.
                //
                // A genuine zstd frame's own magic number happens to start with byte 0x28 --
                // exactly DeltaCodec.decompress's dedicated zstd marker byte -- so sniff that
                // first, matching the COMP_MODE_INLINE branch below; only when it IS a zstd frame
                // do we need the frame-embedded content size (sidedata has no separate uncompLen
                // field to fall back on). Any other first byte is handed to DeltaCodec.decompress,
                // whose zlib branch grows its own output buffer dynamically (the uncompLen
                // argument is only a sizing hint there, never load-bearing for correctness).
                if (chunk.length > 0 && (chunk[0] & 0xFF) == 0x28) {
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
                return DeltaCodec.decompress(chunk, Math.max(chunk.length * 4, 64));
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
     *
     * <p>For a general-v2 (docket-based {@code exp-revlogv2.2}) filelog, the classic {@code .i}
     * path is a tiny docket "pointer" file (magic {@code 00 00 de ad}), not a real index --
     * rewriting it with the classic revlogv1 layout would clobber the docket and orphan the real
     * companion {@code <basename>-<hash>.idx}/{@code .dat}/{@code .sda} files. {@link
     * #index}{@code .isV2()} therefore routes to {@link
     * #censorRevisionV2(int, int, byte[][], IndexRecord[])} instead, which reuses {@link
     * #truncate(int)} (already correctly docket-aware, see its own javadoc) plus {@link
     * #appendRevisionV2} (already correctly docket-aware and, crucially, takes an explicit {@code
     * nodeId} rather than hashing content -- exactly what preserving node identity across a
     * content-changing censor requires) to rebuild the revlog revision-by-revision in its native
     * v2 layout instead.
     *
     * @param censorRev the revision number whose payload should be replaced with a tombstone
     * @param tombstoneRawContent the raw content to store in place of {@code censorRev}'s payload
     * @throws IOException if the revlog files cannot be read or rewritten
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

        if (index.isV2()) {
            censorRevisionV2(censorRev, count, rawContents, records);
            return;
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

    /**
     * v2 (docket-based, {@code changelog-v2} or general-v2) counterpart of {@link
     * #censorRevision(int, byte[])} -- see that method's javadoc for why this branch exists.
     * Empties the revlog via {@link #truncate(int)} (already handles the docket bookkeeping
     * correctly) and re-appends every revision via {@link #appendRevisionV2}, which -- because it
     * always stores a revision as an independent fulltext (never a delta) and takes the node id as
     * an explicit parameter rather than deriving it from content -- exactly matches what censoring
     * needs: every revision's node identity, parents, and linkrev preserved verbatim, only {@code
     * censorRev}'s payload replaced and flagged {@link #REVIDX_ISCENSORED}.
     */
    private void censorRevisionV2(int censorRev, int count, byte[][] rawContents, IndexRecord[] records) throws IOException {
        truncate(0);
        for (int r = 0; r < count; r++) {
            IndexRecord rec = records[r];
            int extraFlags = (r == censorRev) ? REVIDX_ISCENSORED : 0;
            appendRevisionV2(r, rawContents[r], rec.getParent1(), rec.getParent2(), rec.getNodeId(),
                    rec.getLinkRev(), null, extraFlags);
        }
        clearCache();
    }

    /**
     * Reconstructs a revision's raw stored content, resolving the delta chain back to its base
     * full-text and applying every delta in order. Unlike {@link #getRevisionContent(int)}, this
     * does not strip the leading {@code \x01\n...\x01\n} metadata block or reject a censored
     * revision -- it returns exactly what is stored on disk.
     *
     * @param rev the revision number (0-based), or {@code -1} for the null revision
     * @return the reconstructed raw content, or an empty array for revision {@code -1}
     * @throws IOException if the revlog files cannot be read, or the delta chain is corrupt
     *     (cyclic, or its base revision's data cannot be located)
     */
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

    /**
     * Returns a revision's logical file content, with the leading {@code \x01\n...\x01\n}
     * metadata block (if any) stripped and the result cached for subsequent calls.
     *
     * @param rev the revision number (0-based), or {@code -1} for the null revision
     * @return the revision's content, with any metadata block removed
     * @throws IOException if the revlog files cannot be read
     * @throws HgCensoredContentException if the revision is marked censored (see
     *     {@link #isCensored(int)}) -- use {@link #getRawRevisionContent(int)} to read the raw
     *     tombstone bytes instead
     */
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

    /**
     * For an LFS-flagged revision ({@link #isExtStored}), real
     * hg's {@code hgext/lfs/wrapper.py} {@code filelogrenamed} wrapper does NOT look at the
     * ordinary {@code \x01\n...\x01\n} metadata block at all -- because for such a revision the
     * stored content is the LFS pointer text itself, and the pointer's OWN {@code x-hg-<key>}
     * fields (folded in there by real hg's {@code writetostore} instead of a separate metadata
     * block) carry the copy metadata. Parses the pointer and returns those {@code x-hg-*} fields
     * (prefix stripped) as
     * if they were the ordinary block, so every existing caller of this method ({@code
     * AnnotateCommand}'s rename-crossing, {@code LogCommand --follow}) transparently keeps
     * working across an LFS-tracked rename with no caller-side change needed.
     *
     * @param rev the revision number (0-based)
     * @return the parsed metadata fields (e.g. {@code copy}/{@code copyrev} for a rename/copy
     *     source), or an empty map if the revision carries no metadata block
     * @throws IOException if the revlog files cannot be read
     */
    public synchronized Map<String, String> getRevisionMetadata(int rev) throws IOException {
        if (isExtStored(rev)) {
            Map<String, String> lfsMeta = new HashMap<>();
            byte[] rawPointer = getRawRevisionContent(rev);
            if (rawPointer.length == 0) {
                return lfsMeta;
            }
            try {
                HgLfsPointer pointer = HgLfsPointer.parse(rawPointer);
                for (Map.Entry<String, String> entry : pointer.getExtra().entrySet()) {
                    if (entry.getKey().startsWith("x-hg-")) {
                        lfsMeta.put(entry.getKey().substring("x-hg-".length()), entry.getValue());
                    }
                }
            } catch (IOException malformedPointer) {
                // Not a parseable pointer (shouldn't normally happen for a REVIDX_EXTSTORED
                // revision) -- treat as "no copy metadata" rather than propagating a parse
                // failure out of what every caller treats as a best-effort lookup.
            }
            return lfsMeta;
        }

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
     *
     * @param baseText the base (old) revision content
     * @param newText the new revision content
     * @return the encoded delta from {@code baseText} to {@code newText}
     */
    public static byte[] createSimpleDelta(byte[] baseText, byte[] newText) {
        return DeltaEngine.createSimpleDelta(baseText, newText);
    }

    /**
     * Creates a highly optimized multi-hunk delta using LCS Line Diff.
     * Delegates to {@link DeltaEngine}.
     *
     * @param baseText the base (old) revision content
     * @param newText the new revision content
     * @return the encoded delta from {@code baseText} to {@code newText}
     */
    public static byte[] createDelta(byte[] baseText, byte[] newText) {
        return DeltaEngine.createDelta(baseText, newText);
    }

    /**
     * Appends a new revision with no metadata block, as an ordinary (non-LFS/copy-tracing)
     * revision. Equivalent to calling {@link #appendRevision(byte[], Map, int, int, byte[],
     * byte[], int)} with a {@code null} metadata map.
     *
     * @param content the revision's raw content
     * @param parent1 first parent's revision number, or {@code -1} if none
     * @param parent2 second parent's revision number, or {@code -1} if none
     * @param p1Node first parent's node id
     * @param p2Node second parent's node id
     * @param linkRev the changelog revision this revision is linked to
     * @return the newly appended revision's node id
     * @throws IOException if the revlog files cannot be read or written
     */
    public synchronized byte[] appendRevision(byte[] content, int parent1, int parent2,
                                 byte[] p1Node, byte[] p2Node, int linkRev) throws IOException {
        return appendRevision(content, null, parent1, parent2, p1Node, p2Node, linkRev);
    }

    /**
     * Real hg's {@code _maxinline} (mercurial/revlog.py) -- the cumulative inline-data-size
     * threshold past which {@code _enforceinlinesize()} converts an inline v1 revlog to the
     * separate-{@code .d}-file non-inline layout. See {@link #enforceInlineSize(int)}.
     */
    private static final long MAXINLINE = 131072L;

    /**
     * Real hg's {@code revlog.py} {@code _enforceinlinesize()} equivalent, called right after a
     * revision has been durably appended to this (v1, inline) revlog, mirroring real hg's own
     * call site ({@code _writeentry()} calls {@code self._enforceinlinesize(tr)} immediately
     * after writing each single revision -- so a single first revision whose own compressed size
     * alone already exceeds {@code _maxinline} is written inline and then immediately split,
     * exactly like the multi-revision case below).
     *
     * <p>After appending revision {@code rev}, if this revlog is still inline and the cumulative
     * data size so far ({@code offset(rev) + compLen(rev)} -- real hg's own
     * {@code self.start(tiprev) + self.length(tiprev)}, which for a v1 revlog's "offset" field
     * always means pure compressed-data bytes excluding the 64-byte headers, in EITHER layout)
     * has reached or passed 131072 bytes, the revlog is converted to non-inline via
     * {@link #splitInlineToNonInline()}. Only relevant to v1: a v2 revlog is already permanently
     * non-inline ({@code this.inline} is always {@code false} whenever {@link RevlogIndex#isV2()}
     * -- see the constructor), so this is naturally a no-op for v2, matching real hg's own
     * {@code not self._inline} fast-return; changelog is likewise unaffected since it's
     * {@code may_inline=False}/never inline to begin with (also already reflected in
     * {@code this.inline} via the constructor's {@code isChangelog} check).
     */
    private void enforceInlineSize(int rev) throws IOException {
        if (!inline) {
            return;
        }
        IndexRecord rec = getIndexRecord(rev);
        long totalSize = rec.getOffset() + rec.getCompLen();
        if (totalSize < MAXINLINE) {
            return;
        }
        splitInlineToNonInline();
    }

    /**
     * Performs the actual inline&rarr;non-inline on-disk conversion: splits every revision's raw
     * (already-compressed) data bytes out of the inline {@code .i} file into a fresh {@code .d}
     * file (in revision order, exactly like real hg's own
     * {@code InnerRevlog.split_inline}: read each revision's existing segment unchanged, append
     * it to the new data file, then rewrite the index with plain 64-byte records), then rewrites
     * the {@code .i} file to hold only fixed-64-byte index records with the inline format-flag
     * bit cleared.
     *
     * <p>Critically, every other field of every existing record (offset, flags, lengths, baseRev,
     * linkRev, parents, nodeId) is carried over byte-for-byte unchanged -- a v1 revlog's "offset"
     * field is always a pure data-byte count that never includes the interleaved 64-byte headers,
     * in EITHER layout, so no offset recomputation is needed when converting -- only where the
     * bytes physically live
     * changes. This exactly matches real hg's own {@code split_inline}, which re-serializes each
     * existing {@code index.entry_binary(i)} unchanged (only rev 0's packed header bits differ,
     * losing the inline flag).
     */
    private void splitInlineToNonInline() throws IOException {
        int count = index.getRevisionCount();
        // Snapshot every revision's raw data hunk from the CURRENT inline .i file layout before
        // any bytes move -- index.getFileOffset(rev) still reflects the pre-split physical inline
        // layout at this point (interleaved 64-byte headers + data).
        byte[][] dataHunks = new byte[count][];
        try (RandomAccessFile raf = new RandomAccessFile(idxFile, "r")) {
            for (int rev = 0; rev < count; rev++) {
                IndexRecord rec = getIndexRecord(rev);
                long headerOffset = index.getFileOffset(rev);
                byte[] hunk = new byte[rec.getCompLen()];
                raf.seek(headerOffset + 64);
                raf.readFully(hunk);
                dataHunks[rev] = hunk;
            }
        }

        // Write the new .d file holding every revision's data, in order, contiguously -- matches
        // real hg's split_inline() truncate-then-sequential-write.
        try (FileOutputStream out = new FileOutputStream(datFile, false)) {
            for (byte[] hunk : dataHunks) {
                out.write(hunk);
            }
            out.getFD().sync();
        }

        // Rewrite the .i file: plain 64-byte records only, inline format-flag bit cleared. Built
        // in a temp file and atomically renamed into place so a crash mid-rewrite can never leave
        // a half-converted .i file.
        File tmpIdx = new File(idxFile.getParentFile(), idxFile.getName() + ".tmpsplit");
        try (FileOutputStream out = new FileOutputStream(tmpIdx, false)) {
            for (int rev = 0; rev < count; rev++) {
                IndexRecord rec = getIndexRecord(rev);
                long offsetFlags;
                if (rev == 0) {
                    long formatFlags = 0x0002L; // generaldelta, inline(0x0001) bit cleared
                    long version = 1L;
                    offsetFlags = (formatFlags << 48) | (version << 32) | (rec.getFlags() & 0xFFFFL);
                } else {
                    offsetFlags = (rec.getOffset() << 16) | (rec.getFlags() & 0xFFFFL);
                }

                ByteBuffer recordBuf = ByteBuffer.allocate(64);
                recordBuf.putLong(offsetFlags);
                recordBuf.putInt(rec.getCompLen());
                recordBuf.putInt(rec.getUncompLen());
                recordBuf.putInt(rec.getBaseRev());
                recordBuf.putInt(rec.getLinkRev());
                recordBuf.putInt(rec.getParent1());
                recordBuf.putInt(rec.getParent2());
                byte[] nodeField = new byte[32];
                System.arraycopy(rec.getNodeId(), 0, nodeField, 0, 20);
                recordBuf.put(nodeField);

                out.write(recordBuf.array());
            }
            out.getFD().sync();
        }
        Files.move(tmpIdx.toPath(), idxFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

        this.inline = false;
        // Force a full fresh reload from the just-rewritten on-disk state: RevlogIndex.loadIndex()
        // determines `inline` from rev 0's actual on-disk format flags and recomputes fileOffsets
        // as plain `rev * 64` for a non-inline layout, so this is simpler and less error-prone
        // than hand-maintaining every piece of in-memory bookkeeping (fileOffsets, addedRecords,
        // nodeMapDeferred, etc.) that a partial in-place update would otherwise have to track.
        index.clearCache();

        registerNewDataFileInFncache();
    }

    /**
     * Real hg's {@code store.py} {@code fncachestore._fncachevfs.register_file()} equivalent,
     * called ONLY at the exact moment {@link #splitInlineToNonInline()} creates a brand-new
     * {@code .d} file for a previously-inline revlog: real hg's own {@code _enforceinlinesize()}
     * calls exactly this ({@code self.opener.register_file(self._datafile)}) right after the
     * split, registering the just-created data file into {@code .hg/store/fncache} alongside the
     * index file that (for a filelog/treemanifest revlog under {@code data/}/{@code meta/}) was
     * already registered when the revlog was first created. Without this, real hg's own
     * {@code hg verify} reports {@code warning: revlog 'data/<name>.d' not in fncache!} for every
     * filelog/manifest hg4j itself transitions to non-inline.
     *
     * <p>Real hg's {@code RE_FNCACHE_FILE = re.compile(r'^(data|meta)/.*\.[id]$')} only tracks
     * files under {@code data/} (filelogs) and {@code meta/} (per-directory treemanifest
     * revlogs) -- the well-known root-level {@code 00changelog.i}/{@code 00manifest.i} are never
     * fncache-tracked at all (irrelevant here anyway: changelog never goes inline in the first
     * place, and even if the root manifest transitions, its path has no {@code data/}/{@code
     * meta/} prefix). Best-effort and silent like this class's other post-append bookkeeping
     * ({@link #updatePersistentNodeMapAfterAppend()}): a repository with no {@code fncache} file
     * at all (the {@code fncache} requirement not in play) is deliberately left untouched rather
     * than have one spuriously created.
     */
    private void registerNewDataFileInFncache() {
        try {
            String idxPath = idxFile.getAbsolutePath().replace(File.separatorChar, '/');
            int storeMarker = idxPath.lastIndexOf("/store/");
            if (storeMarker == -1) {
                return;
            }
            String storeRelative = idxPath.substring(storeMarker + "/store/".length());
            if (!(storeRelative.startsWith("data/") || storeRelative.startsWith("meta/"))
                    || !storeRelative.endsWith(".i")) {
                return;
            }
            File storeDir = new File(idxPath.substring(0, storeMarker + "/store".length()));
            File fncacheFile = new File(storeDir, "fncache");
            if (!fncacheFile.exists()) {
                // No fncache requirement active for this repository -- nothing to update, and
                // real hg itself would never create one here either.
                return;
            }
            String dataEntry = storeRelative.substring(0, storeRelative.length() - ".i".length()) + ".d";
            List<String> existing = Files.readAllLines(fncacheFile.toPath());
            if (!existing.contains(dataEntry)) {
                try (Writer w = new FileWriter(fncacheFile, true)) {
                    w.write(dataEntry);
                    w.write("\n");
                }
            }
        } catch (IOException ignored) {
            // best-effort, matches this class's other post-append bookkeeping
        }
    }

    /**
     * Appends a new revision to a v2 (changelog-v2 or general revlog-v2) store. Reproduces the
     * exact on-disk layout verified by directly comparing a real hg CLI-produced fixture via
     * hexdump/struct -- both formats store every revision as an independent fulltext with no
     * delta chain (a simplification; changelog-v2 was already implemented this way, and general
     * v2 adopts the same strategy -- see RevlogV2ParserTest/RevlogV2GeneralParserTest and
     * src/test/resources/fixtures/revlogv2-{changelog,general}/README.md).
     *
     * <p>changelog-v2 (magic 0xD34D, {@code INDEX_ENTRY_CL_V2}) writes each revision as an
     * independent zstd frame (no prefix byte, COMP_MODE_DEFAULT). General revlog-v2 (magic
     * 0xDEAD, {@code exp-revlogv2.2}, {@code INDEX_ENTRY_V2}, used for manifests/filelogs)
     * instead writes COMP_MODE_PLAIN (the original bytes, uncompressed) -- this is itself a
     * normal encoding real hg chooses for small revisions where compression wouldn't help
     * (confirmed: RevlogV2GeneralParserTest's real hg fixture is in exactly this form), and using
     * only the {@code compression_mode == COMP_MODE_PLAIN -> uncomp = data} path that
     * `mercurial/revlog.py` explicitly supports is always valid, without the risk of having to
     * newly verify the zstd frame format. The two formats also differ in record field layout --
     * CL_V2 does not store baseRev/linkRev (synthesized as rev==rev), and has node at offset 24
     * plus a rank field; general V2 explicitly stores baseRev/linkRev/parent1/parent2, has node
     * at offset 32, and has no rank field (just 19 bytes of padding).</p>
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
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(data.length);
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
        return appendRevisionV2(rev, processedContent, parent1, parent2, nodeId, linkRev, sidedataContainer, 0);
    }

    /** @param extraFlags additional {@code flags} bits (e.g. {@link #REVIDX_EXTSTORED}) to OR in. */
    private synchronized byte[] appendRevisionV2(int rev, byte[] processedContent, int parent1, int parent2,
                                                   byte[] nodeId, int linkRev, byte[] sidedataContainer, int extraFlags) throws IOException {
        File resolvedIndexFile = index.getResolvedIndexFile();
        File resolvedDataFile = index.getResolvedDataFile();
        boolean changelogV2 = index.isChangelogV2();

        // CL_V2 compression is chosen dynamically per revision, real hg's own way: try the
        // repository's actual default engine, and only actually use it when it genuinely shrinks
        // the content -- otherwise store the revision's raw bytes as-is (COMP_MODE_PLAIN,
        // complen==uncomplen, NO marker byte) rather than DeltaCodec.compress's v1-revlog-style
        // 'u'+rawdata fallback (that extra marker byte is invalid here: CL_V2 readers expect
        // either a genuine compressed frame or exactly the raw content, decided purely by the
        // per-record compression-mode bits, never a payload-level marker). Always hardcoding
        // COMP_MODE_DEFAULT and always running content through DeltaCodec.compress would produce
        // 'u'-prefixed garbage for any revision short enough that zstd's frame overhead doesn't
        // pay for itself (real hg's own fixture,
        // src/test/resources/fixtures/sidedata-copytracing/data.idx, confirms two of its three
        // revisions are genuinely stored PLAIN this way: complen==uncomplen, compression byte
        // 0x00) -- real hg's zstd decompressor then rejects that bogus frame with "Unknown frame
        // descriptor".
        //
        // COMP_MODE_DEFAULT does NOT mean "zstd" unconditionally -- it means "whatever this
        // repository's own default revlog compression engine is", which real hg's reader infers
        // purely from the `revlog-compression-zstd` requirement string (there is no per-record
        // codec discriminator bit), not from anything this method writes. A repository created
        // without that requirement (real hg's own `--config format.usezstd=false`/
        // `format.revlog-compression=zlib`, still a fully valid changelog-v2 repository) uses
        // zlib for COMP_MODE_DEFAULT instead -- unconditionally attempting zstd here would produce
        // a zstd frame real hg's zlib-only reader could never decompress. `this.useZstd`
        // (populated from that exact requirement string, see the constructor) must gate which
        // codec is attempted, matching real hg's own engine choice byte for byte.
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

        long offsetFlags = (rev == 0) ? (extraFlags & 0xFFFFL) : ((offset << 16) | (extraFlags & 0xFFFFL));
        byte[] node20 = Arrays.copyOf(nodeId, 20);

        ByteBuffer recordBuf = ByteBuffer.allocate(96);
        recordBuf.putLong(offsetFlags);
        recordBuf.putInt(dataHunk.length);
        recordBuf.putInt(processedContent.length);
        if (changelogV2) {
            // INDEX_ENTRY_CL_V2 = >Qiiii20s12xQiBi23x (96 bytes, matches
            // mercurial/revlogutils/constants.py)
            recordBuf.putInt(parent1);
            recordBuf.putInt(parent2);
            recordBuf.put(node20);
            recordBuf.put(new byte[12]); // padding
            recordBuf.putLong(sidedataOffset);
            recordBuf.putInt(sidedataCompLen);
            // Compression mode (low 2 bits, main data): COMP_MODE_DEFAULT(1) only when zstd was
            // actually used above and it shrank the data, otherwise COMP_MODE_PLAIN(0) since the
            // original bytes were stored as-is -- this varies per revision, so it must not be
            // hardcoded. The
            // high 2 bits (2-3) are sidedata's own compression mode (left as 00 since
            // COMP_MODE_PLAIN=0 is used, no change needed) -- mirrors RevlogIndex's
            // `(compressionByte >> 2) & 3` parsing.
            recordBuf.put((byte) (changelogUsesCompression ? 1 : 0));
            recordBuf.putInt(computeCl2Rank(parent1, parent2)); // rank (real hg's own recursive formula)
            recordBuf.put(new byte[23]); // padding
        } else {
            // INDEX_ENTRY_V2 = >Qiiiiii20s12xQiB19x (96 bytes, matches
            // mercurial/revlogutils/constants.py)
            recordBuf.putInt(rev); // baseRev (simplification: always itself/fulltext, no delta chain)
            recordBuf.putInt(linkRev);
            recordBuf.putInt(parent1);
            recordBuf.putInt(parent2);
            recordBuf.put(node20);
            recordBuf.put(new byte[12]); // padding
            recordBuf.putLong(sidedataOffset);
            recordBuf.putInt(sidedataCompLen);
            recordBuf.put((byte) 0); // COMP_MODE_PLAIN (dataHunk == processedContent, uncompressed; sidedata is also PLAIN so the high bits stay unchanged)
            recordBuf.put(new byte[19]); // padding (unlike CL_V2, there is no rank field)
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
        int recordedRank = changelogV2 ? computeCl2Rank(parent1, parent2) : -1;
        index.addRecord(new IndexRecord(rev, offset, extraFlags, dataHunk.length, processedContent.length,
                rev, recordedLinkRev, parent1, parent2, nodeId, sidedataOffset, sidedataCompLen, 0, recordedRank));
        updatePersistentNodeMapAfterAppend();

        byte[] hash = new byte[20];
        System.arraycopy(nodeId, 0, hash, 0, 20);
        return hash;
    }

    /**
     * Mirrors real hg's own recursive {@code rank} computation for a CL_V2 (changelog-v2) revision
     * exactly (see {@code mercurial/revlog.py}'s {@code addrevision}: {@code rank = 1} for a root,
     * {@code rank = 1 + fast_rank(parent)} for a single-parent revision, {@code rank = 1 +
     * fast_rank(max(p1, p2))} for a merge -- real hg itself uses this same non-rust-extension
     * fallback formula, not a from-scratch ancestor-set count, so mirroring it exactly (rather than
     * "more correctly" computing a true ancestor-set size) is what actually matches what real hg
     * would have persisted for the same history). {@code parent1}/{@code parent2} are {@code -1}
     * for "no parent" (nullrev), matching every other parent-index convention in this class.
     *
     * <p>Writing {@code rev} directly as the rank (rank 0 for the first commit) would diverge
     * from real hg's own convention that a root revision's rank is {@code 1} -- every later rank
     * real hg computes on top of such a repository would then be off by one.
     */
    private int computeCl2Rank(int parent1, int parent2) {
        if (parent1 < 0 && parent2 < 0) {
            return 1;
        } else if (parent2 < 0) {
            return 1 + rankOfAlreadyWritten(parent1);
        } else if (parent1 < 0) {
            return 1 + rankOfAlreadyWritten(parent2);
        } else {
            return 1 + rankOfAlreadyWritten(Math.max(parent1, parent2));
        }
    }

    /** {@code rank} of an already-appended revision, treating nullrev/unknown defensively as 0
     * (real hg's own convention for nullrev -- see {@code fast_rank}'s {@code if rev == nullrev:
     * return 0} -- this should never actually observe an unknown rank for CL_V2-authored history,
     * since every CL_V2 revision this class ever writes always gets a real one via {@link
     * #computeCl2Rank}). */
    private int rankOfAlreadyWritten(int rev) {
        if (rev < 0) {
            return 0;
        }
        int rank = getIndexRecord(rev).getRank();
        return rank < 0 ? 0 : rank;
    }

    /**
     * Appends a new revision, wrapping {@code content} with a rename/copy metadata block when
     * {@code metadata} is non-empty. Equivalent to calling {@link #appendRevision(byte[], Map,
     * int, int, byte[], byte[], int, byte[])} with a {@code null} sidedata container.
     *
     * @param content the revision's raw content, before any metadata wrapping
     * @param metadata rename/copy-style key/value pairs to prepend, or {@code null}/empty for none
     * @param parent1 first parent's revision number, or {@code -1} if none
     * @param parent2 second parent's revision number, or {@code -1} if none
     * @param p1Node first parent's node id
     * @param p2Node second parent's node id
     * @param linkRev the changelog revision this revision is linked to
     * @return the newly appended revision's node id
     * @throws IOException if the revlog files cannot be read or written
     */
    public synchronized byte[] appendRevision(byte[] content, Map<String, String> metadata, int parent1, int parent2,
                                 byte[] p1Node, byte[] p2Node, int linkRev) throws IOException {
        return appendRevision(content, metadata, parent1, parent2, p1Node, p2Node, linkRev, null);
    }

    /**
     * Appends a new revision, optionally attaching sidedata. Equivalent to calling {@link
     * #appendRevision(byte[], Map, int, int, byte[], byte[], int, byte[], int)} with
     * {@code extraFlags=0}.
     *
     * @param content the revision's raw content, before any metadata wrapping
     * @param metadata rename/copy-style key/value pairs to prepend, or {@code null}/empty for none
     * @param parent1 first parent's revision number, or {@code -1} if none
     * @param parent2 second parent's revision number, or {@code -1} if none
     * @param p1Node first parent's node id
     * @param p2Node second parent's node id
     * @param linkRev the changelog revision this revision is linked to
     * @param sidedataContainer already-serialized {@link SidedataCodec} bytes to attach to this
     *     revision (only meaningful for a v2 revlog -- silently ignored otherwise, matching real
     *     hg where sidedata is a revlog-v2-only feature), or {@code null} for none. Used by {@code
     *     api.CommitCommand} to write {@code SD_FILES} copy-tracing sidedata on the changelog
     *     revision when the repository has {@code exp-copies-sidedata-changeset}.
     * @return the newly appended revision's node id
     * @throws IOException if the revlog files cannot be read or written
     */
    public synchronized byte[] appendRevision(byte[] content, Map<String, String> metadata, int parent1, int parent2,
                                 byte[] p1Node, byte[] p2Node, int linkRev, byte[] sidedataContainer) throws IOException {
        return appendRevision(content, metadata, parent1, parent2, p1Node, p2Node, linkRev, sidedataContainer, 0);
    }

    /**
     * Builds the exact {@code "\x01\n<key>: <value>\n...\x01\n"}-prefixed byte array real hg's
     * filelog storage uses to embed rename/copy (or other) metadata ahead of a revision's real
     * content -- extracted out of {@link #appendRevision} so callers that need to precompute the
     * SAME bytes for a purpose other than storage (e.g. {@code api.CommitCommand}'s LFS pipeline,
     * which needs this exact metadata-wrapped form as the filelog node hash basis for a renamed
     * file that is ALSO LFS-flagged -- real hg's LFS flag-processor hashes the real bytes as
     * {@code readfromstore} would hand them back to a caller, which re-wraps any {@code x-hg-*}
     * pointer fields into precisely this block) can reuse it verbatim rather than re-deriving the
     * format by hand.
     *
     * @param content the revision's raw content, before any metadata wrapping
     * @param metadata rename/copy-style key/value pairs to prepend, or {@code null}/empty for none
     * @return {@code content} unchanged when {@code metadata} is null/empty AND {@code content}
     *     doesn't itself start with the {@code "\x01\n"} marker (in which case an empty
     *     {@code "\x01\n\x01\n"} block is prepended instead, to disambiguate real leading marker
     *     bytes in the content from an actual metadata block on the next read) -- otherwise the
     *     metadata block followed by {@code content}.
     */
    public static byte[] wrapMetadata(byte[] content, Map<String, String> metadata) {
        if (metadata != null && !metadata.isEmpty()) {
            StringBuilder msb = new StringBuilder();
            msb.append('\u0001').append('\n');
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                msb.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
            }
            msb.append('\u0001').append('\n');
            byte[] metaBytes = msb.toString().getBytes(StandardCharsets.UTF_8);
            byte[] result = new byte[metaBytes.length + content.length];
            System.arraycopy(metaBytes, 0, result, 0, metaBytes.length);
            System.arraycopy(content, 0, result, metaBytes.length, content.length);
            return result;
        } else if (content.length >= 2 && content[0] == '\u0001' && content[1] == '\n') {
            byte[] prefix = new byte[]{'\u0001', '\n', '\u0001', '\n'};
            byte[] result = new byte[prefix.length + content.length];
            System.arraycopy(prefix, 0, result, 0, prefix.length);
            System.arraycopy(content, 0, result, prefix.length, content.length);
            return result;
        }
        return content;
    }

    /**
     * Appends a new revision, optionally OR-ing extra flag bits into its index record. Equivalent
     * to calling {@link #appendRevision(byte[], Map, int, int, byte[], byte[], int, byte[], int,
     * byte[])} with a {@code null} hash-basis override.
     *
     * @param content the revision's raw content, before any metadata wrapping
     * @param metadata rename/copy-style key/value pairs to prepend, or {@code null}/empty for none
     * @param parent1 first parent's revision number, or {@code -1} if none
     * @param parent2 second parent's revision number, or {@code -1} if none
     * @param p1Node first parent's node id
     * @param p2Node second parent's node id
     * @param linkRev the changelog revision this revision is linked to
     * @param sidedataContainer already-serialized {@link SidedataCodec} bytes to attach to this
     *     revision, or {@code null} for none
     * @param extraFlags additional {@code flags} bits (e.g. {@link #REVIDX_EXTSTORED}) to OR into
     *     this revision's index record, on top of whatever this method already computes on its
     *     own (currently nothing -- flags are otherwise always 0 on this path). Used by {@code
     *     api.CommitCommand}'s LFS pipeline to flag a revision whose stored {@code
     *     content} is an LFS pointer, not the real file bytes.
     * @return the newly appended revision's node id
     * @throws IOException if the revlog files cannot be read or written
     */
    public synchronized byte[] appendRevision(byte[] content, Map<String, String> metadata, int parent1, int parent2,
                                 byte[] p1Node, byte[] p2Node, int linkRev, byte[] sidedataContainer, int extraFlags) throws IOException {
        return appendRevision(content, metadata, parent1, parent2, p1Node, p2Node, linkRev, sidedataContainer, extraFlags, null);
    }

    /**
     * Appends a new revision to this revlog, computing its delta/full-text storage, node id, and
     * index record, and durably writing them (plus any sidedata) to the underlying files. This is
     * the designated implementation every other {@code appendRevision} overload delegates to.
     *
     * @param content the revision's raw content, before any metadata wrapping
     * @param metadata rename/copy-style key/value pairs to prepend, or {@code null}/empty for none
     * @param parent1 first parent's revision number, or {@code -1} if none
     * @param parent2 second parent's revision number, or {@code -1} if none
     * @param p1Node first parent's node id
     * @param p2Node second parent's node id
     * @param linkRev the changelog revision this revision is linked to
     * @param sidedataContainer already-serialized {@link SidedataCodec} bytes to attach to this
     *     revision, or {@code null} for none
     * @param extraFlags additional {@code flags} bits to OR into this revision's index record
     * @param hashBasisOverride when non-null, the revlog node id is computed as
     *     {@code SHA1(p1Node, p2Node, hashBasisOverride)} instead of over the (post-metadata-
     *     escaping) stored {@code content} -- real hg's LFS extension does exactly this: the
     *     filelog node hash for an LFS-flagged revision is computed over the REAL file bytes
     *     (what the flag-processor's {@code readfromstore} hands back to callers), even though
     *     the bytes actually stored on disk are the pointer text. Used by {@code
     *     api.CommitCommand}'s LFS pipeline.
     * @return the newly appended revision's node id
     * @throws IOException if the revlog files cannot be read or written
     */
    public synchronized byte[] appendRevision(byte[] content, Map<String, String> metadata, int parent1, int parent2,
                                 byte[] p1Node, byte[] p2Node, int linkRev, byte[] sidedataContainer, int extraFlags,
                                 byte[] hashBasisOverride) throws IOException {
        int rev = index.getRevisionCount();

        // Escaping logic for content and metadata
        byte[] processedContent = wrapMetadata(content, metadata);

        // Calculate NodeID: SHA-1(p1Node + p2Node + hashBasis) where parents are sorted
        // lexicographically. hashBasis is normally processedContent (what actually gets stored),
        // except when hashBasisOverride is supplied (LFS -- see javadoc above).
        byte[] hashBasis = hashBasisOverride != null ? hashBasisOverride : processedContent;
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
            md.update(hashBasis);
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
        // never had that path) would otherwise corrupt the store on every such call -- without
        // this check, real hg's `hg verify` on such output reports "duplicate revision N (M)"
        // and "<node> not in manifests".
        int existingRev = findRevision(nodeId);
        if (existingRev != -1) {
            return getIndexRecord(existingRev).getNodeId();
        }

        if (index.isV2()) {
            return appendRevisionV2(rev, processedContent, parent1, parent2, nodeId, linkRev, sidedataContainer, extraFlags);
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
                offsetFlags = (formatFlags << 48) | (version << 32) | (extraFlags & 0xFFFF);
            } else {
                offsetFlags = (offset << 16) | (extraFlags & 0xFFFF);
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
                offsetFlags = (formatFlags << 48) | (version << 32) | (extraFlags & 0xFFFF);
            } else {
                offsetFlags = (offset << 16) | (extraFlags & 0xFFFF);
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

        index.addRecord(new IndexRecord(rev, offset, extraFlags, dataHunk.length, processedContent.length,
                baseRev, linkRev, parent1, parent2, nodeId));
        enforceInlineSize(rev);
        updatePersistentNodeMapAfterAppend();

        return hash;
    }

    /**
     * Appends a raw ChangeGroupEntry from remote bundle, preserving the original remote Node ID.
     *
     * @param entry the parsed changegroup entry to append
     * @param linkRev the changelog revision this revision is linked to
     * @throws IOException if the revlog files cannot be read or written, or the reconstructed
     *     content's computed node id does not match {@code entry}'s declared node id
     */
    public synchronized void appendChangeGroupEntry(ChangegroupParser.ChangeGroupEntry entry, int linkRev) throws IOException {
        appendChangeGroupEntry(entry, linkRev, null, false);
    }

    /**
     * Same as {@link #appendChangeGroupEntry(ChangegroupParser.ChangeGroupEntry, int)}, but for a
     * cg1 entry (whose wire format carries no explicit {@code deltabase} -- the base is implicit:
     * "whatever was packed immediately before it, in the SAME incoming changegroup", per real
     * hg's ChangeGroupPacker01/forcedeltaparentprev=True, see the in-method comment below)
     * resolves that implicit base correctly even when this revlog already holds unrelated
     * revisions before this changegroup gets applied.
     *
     * <p>The plain 2-arg overload approximates "the group's first entry" as "this revlog's
     * local revision count is still 0", which only happens to be correct when the LOCAL revlog
     * was completely empty (or purely linear so far) before applying the incoming group. A
     * receiver that already has its own diverging history -- e.g. a repository with two
     * bookmarks/heads, exactly what a PR merge commit gets pushed into -- would decode the
     * group's first entry against the wrong base (its own highest-numbered existing revision,
     * unrelated to the entry's real DAG parent), corrupting the reconstructed content and
     * tripping the SHA-1 node-hash check below with a false "Security Integrity Error".
     *
     * <p>The caller (currently only {@link io.github.search5.hg4j.api.FetchCommand#applyBundle})
     * must track {@code previousGroupEntryContent} itself across a single group's entries,
     * mirroring {@code PushCommand}'s own sender-side {@code prevClContent}/{@code
     * prevMfContent}/per-file {@code prevContent} bookkeeping exactly: {@code null} for the
     * group's first entry (this method then resolves the base via the entry's own {@code p1},
     * which must already be locally known -- or all-zero for a root commit), and the
     * immediately-previously-decoded entry's own content for every entry after that.
     *
     * @param entry the parsed changegroup entry to append
     * @param linkRev the changelog revision this revision is linked to
     * @param previousGroupEntryContent the immediately-previously-decoded entry's content, or
     *     {@code null} for the group's first entry
     * @throws IOException if the revlog files cannot be read or written, or the reconstructed
     *     content's computed node id does not match {@code entry}'s declared node id
     */
    public synchronized void appendChangeGroupEntry(ChangegroupParser.ChangeGroupEntry entry, int linkRev, byte[] previousGroupEntryContent) throws IOException {
        appendChangeGroupEntry(entry, linkRev, previousGroupEntryContent, true);
    }

    private synchronized void appendChangeGroupEntry(ChangegroupParser.ChangeGroupEntry entry, int linkRev, byte[] previousGroupEntryContent, boolean trackGroupPosition) throws IOException {
        if (findRevision(entry.node) != -1) {
            return;
        }

        int rev = index.getRevisionCount();
        int parent1 = findRevision(entry.p1);
        int parent2 = findRevision(entry.p2);

        byte[] content;
        if (entry.fullText) {
            // cg4-only (real spec: mercurial/changegroup.py's cg4unpacker.deltachunk -- when
            // protocol_flags & CG_FLAG_FULL_TEXT is set, the payload is not a bdiff delta but
            // the uncompressed original text itself. Used as the content directly, regardless
            // of the deltabase value.
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
        } else if (trackGroupPosition) {
            // cg1 (entry.deltabase == null): the wire format itself has no base field. Real
            // Mercurial's own cg1 packer (ChangeGroupPacker01) always uses
            // forcedeltaparentprev=True, so the base is always "the entry immediately preceding
            // this one in the group stream" -- except the group's very first entry, which
            // instead uses that entry's own actual DAG parent (p1). (Exactly mirrors
            // PushCommand's sender-side packing rule -- see that class's own comment.)
            if (previousGroupEntryContent != null) {
                content = applyDelta(previousGroupEntryContent, entry.delta);
            } else if (entry.p1 == null || NodeIdUtil.isAllZero(entry.p1)) {
                content = applyDelta(new byte[0], entry.delta);
            } else {
                int baseRev = findRevision(entry.p1);
                if (baseRev == -1) {
                    throw new HgCorruptDataException("Delta base revision (p1) not found in local index: " + NodeIdUtil.toHex(entry.p1) + " for commit: " + NodeIdUtil.toHex(entry.node));
                }
                content = applyDelta(getRawRevisionContent(baseRev), entry.delta);
            }
        } else {
            // Legacy 2-argument overload only -- keeps the old position-based approximation for
            // callers that don't know the group boundary (accurate only when the local revlog is
            // empty or a purely linear history -- a caller that can't guarantee that condition
            // must use the 3-argument overload above instead).
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

        // If this revlog is already in v2 format (most commonly a changelog with
        // exp-copies-sidedata-changeset enabled), the v1-only manual byte-writing path below must
        // never be taken -- the index record size itself differs (64 vs 96 bytes, and the field
        // layout differs too). Instead, reuse appendRevisionV2 as-is so the result has exactly
        // the same on-disk layout a local commit (CommitCommand) would produce -- if
        // entry.sidedata is present (the raw sidedata container bytes carried by cg5's
        // CG_FLAG_SIDEDATA, or null if absent), it is written to .sda unchanged. entry.sidedata
        // is already in the same "already-serialized external container" format SidedataCodec
        // itself writes, so it can be passed straight through without re-encoding.
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
        if (rev > 0) {
            IndexRecord prevRec = getIndexRecord(rev - 1);
            offset = prevRec.getOffset() + prevRec.getCompLen();
        }

        byte[] nodeId32 = new byte[32];
        System.arraycopy(entry.node, 0, nodeId32, 0, 20);

        // Must mirror appendRevision()'s inline/non-inline branching exactly: taking the
        // non-inline shape (separate datFile, formatFlags without the inline bit, offset
        // computed from datFile.length()) regardless of `this.inline` would corrupt any inline
        // revlog that receives a pull/push changegroup entry.
        if (inline) {
            long offsetFlags;
            if (rev == 0) {
                long formatFlags = 0x0003L; // inline(1) + generaldelta(2) = 3
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
            recordBuf.put(nodeId32);

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
            recordBuf.put(nodeId32);

            try (FileOutputStream out = new FileOutputStream(idxFile, true)) {
                out.write(recordBuf.array());
                out.getFD().sync();
            }
        }

        index.addRecord(new IndexRecord(rev, offset, flags, dataHunk.length, content.length,
                baseRev, linkRev, parent1, parent2, entry.node));
        enforceInlineSize(rev);
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
     *
     * @param content the as-stored revision text to inspect
     * @return {@code true} if {@code content} carries a censor tombstone metadata block
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

    /**
     * Looks up a revision by its node id.
     *
     * @param nodeId the node id to look up
     * @return the revision number, or {@code -1} if not found
     */
    public synchronized int findRevision(byte[] nodeId) {
        return index.findRevision(nodeId);
    }

    /**
     * See {@link RevlogIndex#hasLocallyAddedRecords()}.
     * @return {@code true} if this revlog has revisions added since it was loaded
     */
    public synchronized boolean hasLocallyAddedRecords() {
        return index.hasLocallyAddedRecords();
    }

    /**
     * Delegates to {@link DeltaEngine}.
     *
     * @param baseText the base (old) revision content the delta was computed against
     * @param delta the encoded delta to apply
     * @return the reconstructed content
     * @throws IOException if the delta is malformed
     */
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

    /**
     * Appends a revision verbatim as a full-text entry, using a caller-supplied node id instead
     * of hashing the content. Unlike {@link #appendRevision}, this neither wraps {@code
     * rawToWrite} in a metadata block nor verifies its node id against the content -- the caller
     * is trusted to supply both correctly. Used by {@code RebaseCommand} to restore
     * filelog/manifest/changelog revisions from a backup, where the original node id must be
     * preserved exactly rather than recomputed.
     *
     * @param rawToWrite the exact bytes to store as this revision's content
     * @param node the node id to record for this revision (not derived from {@code rawToWrite})
     * @param parent1 first parent's revision number, or {@code -1} if none
     * @param parent2 second parent's revision number, or {@code -1} if none
     * @param p1Node first parent's node id
     * @param p2Node second parent's node id
     * @param linkRev the changelog revision this revision is linked to
     * @return {@code node}, unchanged
     * @throws IOException if the revlog files cannot be read or written
     */
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
        enforceInlineSize(rev);
        updatePersistentNodeMapAfterAppend();

        clearCache();
        return node;
    }

    /**
     * Appends an already-processed revision (a caller-supplied node id, no metadata wrapping),
     * choosing between a delta or full-text encoding for compact storage. Used by {@code
     * GcCommand} when repacking a revlog into a fresh, defragmented copy.
     *
     * @param processedContent the revision's content, already wrapped/escaped as needed
     * @param nodeId the node id to record for this revision (not derived from {@code
     *     processedContent})
     * @param parent1 first parent's revision number, or {@code -1} if none
     * @param parent2 second parent's revision number, or {@code -1} if none
     * @param p1Node first parent's node id
     * @param p2Node second parent's node id
     * @param linkRev the changelog revision this revision is linked to
     * @throws IOException if the revlog files cannot be read or written
     */
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
        enforceInlineSize(rev);
        updatePersistentNodeMapAfterAppend();

        clearCache();
    }
}
