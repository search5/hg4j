package io.github.search5.hg4j.dirstate;

import io.github.search5.hg4j.util.SafeFileIO;

import io.github.search5.hg4j.lib.NodeId;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import io.github.search5.hg4j.errors.HgCorruptDataException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Parses and writes the Mercurial binary .hg/dirstate file.
 *
 * @apiNote The in-memory working-copy tracking state — obtained via {@link
 *     io.github.search5.hg4j.lib.HgRepository#getDirstate()} and persisted via {@link
 *     io.github.search5.hg4j.lib.HgRepository#writeDirstate}, both of which delegate to {@link
 *     #read(File)}/{@link #write(File)} here. Nearly every mutating porcelain command (e.g.
 *     {@code AddCommand}, {@code RemoveCommand}, {@code CommitCommand}, {@code UpdateCommand})
 *     reads the current dirstate, mutates it via {@link #addEntry}/{@link #removeEntry}/{@link
 *     #setParents}, and writes it back. Transparently reads/writes both the legacy v1 flat format
 *     and the v2 (docket + tree) format depending on {@link #isV2()} / the file's own magic
 *     bytes — most callers do not need to care which format is in use.
 */
public class Dirstate {

    private NodeId parent1 = NodeId.NULL;
    private NodeId parent2 = NodeId.NULL;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, String> copyMap = new LinkedHashMap<>();
    private boolean isV2 = false;

    /** Pending (uncommitted) copy records: destination path → source path. */
    public Map<String, String> getCopyMap() {
        return copyMap;
    }

    /**
     * Records that {@code dest} was copied from {@code src}, for a pending {@code hg copy} not
     * yet committed.
     */
    public void addCopy(String dest, String src) {
        copyMap.put(dest, src);
    }

    /** Whether this dirstate should be read/written in the v2 (docket + tree) on-disk format. */
    public boolean isV2() {
        return isV2;
    }

    /**
     * Selects which on-disk format {@link #write(File)} uses.
     *
     * @apiNote Set by {@link io.github.search5.hg4j.lib.HgRepository#writeDirstate} from the
     *     repository's {@code dirstate-v2} requirement before every write, so callers mutating a
     *     {@code Dirstate} directly normally don't need to call this themselves.
     */
    public void setV2(boolean v2) {
        this.isV2 = v2;
    }

    public record Entry(char state, int mode, int size, long time, int nanos) {
        /**
         * Real Mercurial's 32-bit "-1" sentinel for an entry's mtime (0xFFFFFFFF), written
         * whenever it cannot trust an actual on-disk timestamp for that entry -- most commonly
         * when the entry was (re)written within the very same wall-clock second as the dirstate
         * file itself (mercurial/dirstate.py's classic ambiguous-time handling). See {@link
         * #isStatAmbiguous()}.
         */
        public static final long AMBIGUOUS_TIME = 0xFFFFFFFFL;

        public Entry {
            // Mercurial dirstate-v1 stores mtime as unsigned 32-bit integer.
            // Valid range: 0 to 4294967295 (year 2106). Values beyond this will be truncated on serialization.
            if (time < 0 || time > 0xFFFFFFFFL) {
                throw new IllegalArgumentException(
                    "mtime " + time + " exceeds unsigned 32-bit range (dirstate-v1 limitation). " +
                    "Maximum supported: 4294967295 (2106-02-07). Use dirstate-v2 for post-2106 timestamps.");
            }
        }

        public Entry(char state, int mode, int size, long time) {
            this(state, mode, size, time, 0);
        }

        public char getState() {
            return state;
        }

        public int getMode() {
            return mode;
        }

        public int getSize() {
            return size;
        }

        public long getTime() {
            return time;
        }

        public int getNanos() {
            return nanos;
        }

        /**
         * Whether this entry's cached stat info (size/mtime) cannot be trusted at face value and
         * a genuine content-level comparison is required instead. Real hg ties this to BOTH a
         * negative size (its own "unset"/needs-lookup size sentinel, e.g. minted for a brand new
         * {@code hg add} or a same-second racy commit: `hg commit` immediately after `hg add` on
         * the same wall-clock second produces a 'n' entry with mode=0, size=-1, mtime=-1 all
         * together) AND/OR the {@link #AMBIGUOUS_TIME} mtime sentinel alone. Every dirty-check
         * that trusts a dirstate entry's cached size/mtime WITHOUT checking this first will
         * wrongly treat such an entry as unconditionally "modified" the instant its real on-disk
         * size differs from -1 (which is always, for any non-empty-sentinel file).
         */
        public boolean isStatAmbiguous() {
            return size < 0 || time == AMBIGUOUS_TIME;
        }
    }

    public byte[] getParent1() {
        return parent1.getBytes();
    }

    public byte[] getParent2() {
        return parent2.getBytes();
    }

    public NodeId getParent1Node() {
        return parent1;
    }

    public NodeId getParent2Node() {
        return parent2;
    }

    /**
     * Sets the working copy's parent revisions (the dirstate's own {@code p1}/{@code p2}).
     *
     * @apiNote Called after a commit, update, or merge to record the new working-copy parent(s);
     *     {@link io.github.search5.hg4j.lib.HgRepository#rebuildDirstateFromManifest} also calls
     *     this when reconstructing a lost dirstate from the changelog.
     */
    public void setParents(NodeId p1, NodeId p2) {
        if (p1 == null || p2 == null) {
            throw new IllegalArgumentException("Parents cannot be null");
        }
        this.parent1 = p1;
        this.parent2 = p2;
    }

    public void setParents(byte[] p1, byte[] p2) {
        setParents(new NodeId(p1), new NodeId(p2));
    }

    public Map<String, Entry> getEntries() {
        return entries;
    }

    /**
     * Adds or replaces the tracking entry for {@code path}.
     *
     * @apiNote The primary mutation used by {@code AddCommand} (new 'a' entries), {@code
     *     CommitCommand} (transitioning entries to 'n' with fresh stat info), and {@code
     *     UpdateCommand}/{@code MergeCommand} (rewriting entries to match the new working copy).
     */
    public void addEntry(String path, Entry entry) {
        entries.put(path, entry);
    }

    /**
     * Stops tracking {@code path} entirely (as opposed to marking it removed with state 'r').
     *
     * @apiNote Used by {@code ForgetCommand} and by commands cleaning up an entry that should no
     *         longer appear in the dirstate at all (e.g. after a purge of an added-then-untracked
     *         file).
     */
    public void removeEntry(String path) {
        entries.remove(path);
    }

    /**
     * Parses a v1 (flat) dirstate from raw bytes.
     *
     * @apiNote Called by {@link #read(File)} after ruling out the v2 docket magic; most callers
     *     should use {@link #read(File)} instead, which auto-detects the format.
     */
    public void read(byte[] bytes) throws IOException {
        if (bytes == null) {
            throw new HgCorruptDataException("Invalid dirstate file: content cannot be null");
        }

        if (bytes.length < 40) {
            throw new HgCorruptDataException("Invalid dirstate file: must be at least 40 bytes");
        }

        ByteBuffer buf = ByteBuffer.wrap(bytes);
        byte[] p1 = new byte[20];
        byte[] p2 = new byte[20];
        buf.get(p1);
        buf.get(p2);
        this.parent1 = new NodeId(p1);
        this.parent2 = new NodeId(p2);

        entries.clear();
        while (buf.hasRemaining()) {
            if (buf.remaining() < 17) {
                throw new HgCorruptDataException("Truncated dirstate entry header");
            }
            char state = (char) buf.get();
            int mode = buf.getInt();
            int size = buf.getInt();
            long time = buf.getInt() & 0xFFFFFFFFL; // Parse as unsigned 32-bit int
            int pathLen = buf.getInt();

            if (pathLen < 0 || buf.remaining() < pathLen) {
                throw new HgCorruptDataException("Truncated dirstate entry path");
            }

            byte[] pathBytes = new byte[pathLen];
            buf.get(pathBytes);
            String rawPath = new String(pathBytes, StandardCharsets.UTF_8);
            int nullIdx = rawPath.indexOf('\0');
            if (nullIdx != -1) {
                String target = rawPath.substring(0, nullIdx);
                String source = rawPath.substring(nullIdx + 1);
                entries.put(target, new Entry(state, mode, size, time));
                copyMap.put(target, source);
            } else {
                entries.put(rawPath, new Entry(state, mode, size, time));
            }
        }
    }

    /**
     * Reads a dirstate from disk, auto-detecting v1 vs. v2 by checking for the v2 docket's
     * {@code "dirstate-v2\n"} magic bytes.
     *
     * @apiNote The main entry point used by {@link
     *     io.github.search5.hg4j.lib.HgRepository#getDirstate()} (via the default {@link
     *     io.github.search5.hg4j.storage.StoreEngine}); a v2 docket's tree is parsed by {@link
     *     DirstateV2Parser}.
     */
    public void read(File file) throws IOException {
        if (file == null || !file.exists()) {
            throw new IOException("Dirstate file does not exist");
        }
        byte[] bytes = Files.readAllBytes(file.toPath());

        // Detect dirstate-v2 docket magic bytes (official 12-byte specification)
        if (bytes.length >= 12) {
            String magicStr = new String(bytes, 0, 12, StandardCharsets.US_ASCII);
            if ("dirstate-v2\n".equals(magicStr)) {
                ByteBuffer docketBuf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);

                // parents (32 bytes at offset 12, 32 bytes at offset 44)
                byte[] p1_32 = new byte[32];
                byte[] p2_32 = new byte[32];
                docketBuf.position(12);
                docketBuf.get(p1_32);
                docketBuf.get(p2_32);

                // The valid Node ID of the parents is the first 20 bytes
                byte[] p1 = new byte[20];
                byte[] p2 = new byte[20];
                System.arraycopy(p1_32, 0, p1, 0, 20);
                System.arraycopy(p2_32, 0, p2, 0, 20);

                // data_length (4-byte int at offset 120)
                int dataLength = docketBuf.getInt(120);

                // uid_size (1 byte at offset 124)
                int uidSize = docketBuf.get(124) & 0xFF;

                // uid (uidSize bytes starting from offset 125)
                byte[] uidBytes = new byte[uidSize];
                docketBuf.position(125);
                docketBuf.get(uidBytes);
                String uid = new String(uidBytes, StandardCharsets.US_ASCII);

                // Load .hg/dirstate.<uid> data file
                File dataFile = new File(file.getParentFile(), "dirstate." + uid);
                if (!dataFile.exists()) {
                    throw new HgCorruptDataException("Dirstate-v2 data file not found for uid: " + uid);
                }
                byte[] dataBytes = Files.readAllBytes(dataFile.toPath());
                if (dataBytes.length != dataLength) {
                    throw new HgCorruptDataException("Dirstate-v2 data file length mismatch. Expected " + dataLength + " but got " + dataBytes.length);
                }

                this.isV2 = true;
                DirstateV2Parser parser = new DirstateV2Parser();
                int rootStart = docketBuf.getInt(76);
                int rootCount = docketBuf.getInt(80);
                Dirstate parsed = parser.parse(dataBytes, rootStart, rootCount);
                this.parent1 = new NodeId(p1);
                this.parent2 = new NodeId(p2);
                this.entries.clear();
                this.entries.putAll(parsed.getEntries());
                // The parsed copyMap must also be copied over, not just the entries -- otherwise
                // a dirstate-v2 repo with a pending (uncommitted) `hg copy`'s copy-source
                // metadata would lose that copyMap record on a later re-read: the destination
                // would stay correctly tracked as an added file, but its copy-source linkage
                // would vanish, so the eventual commit records no copy metadata for it at all.
                this.copyMap.clear();
                this.copyMap.putAll(parsed.getCopyMap());
                return;
            }
        }

        // Fallback to v1 parse
        read(bytes);
    }

    /**
     * Serializes this dirstate to the v1 (flat) on-disk byte format.
     *
     * @apiNote Called by {@link #write(File)} for a v1 write; {@link DirstateV2Serializer}
     *     handles the v2 case instead.
     */
    public byte[] serialize() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(parent1.getBytes());
            out.write(parent2.getBytes());
            ByteBuffer buf = ByteBuffer.allocate(17);
            for (Map.Entry<String, Entry> item : entries.entrySet()) {
                String path = item.getKey();
                if (copyMap.containsKey(path)) {
                    path = path + "\0" + copyMap.get(path);
                }
                byte[] pathBytes = path.getBytes(StandardCharsets.UTF_8);
                Entry entry = item.getValue();
                buf.clear();
                buf.put((byte) entry.getState());
                buf.putInt(entry.getMode());
                buf.putInt(entry.getSize());
                // BUG-05: Due to Java's two's complement sign extension, even if the 64-bit mtime is masked with & 0xFFFFFFFFL 
                // and cast to a 32-bit int, masking it again with & 0xFFFFFFFFL during restoration ensures that the unsigned 32-bit 
                // time information in the 2038-2106 range is preserved and restored without data loss.
                buf.putInt((int) (entry.getTime() & 0xFFFFFFFFL)); // Mask safely to 32-bit for serialization
                buf.putInt(pathBytes.length);
                out.write(buf.array());
                out.write(pathBytes);
            }
        } catch (IOException e) {
            // ByteArrayOutputStream should not throw IOException
            throw new RuntimeException("Serialization failed unexpectedly", e);
        }
        return out.toByteArray();
    }

    /**
     * Writes this dirstate to disk, atomically, in the format selected by {@link #isV2()}.
     *
     * @apiNote The main entry point used by {@link
     *     io.github.search5.hg4j.lib.HgRepository#writeDirstate}. A v2 write generates a fresh
     *     random uid, writes the new {@code .hg/dirstate.<uid>} data file, then atomically
     *     replaces the {@code .hg/dirstate} docket, and finally deletes the previous uid's data
     *     file (the "W-LEAK" cleanup) — this ordering ensures a concurrent reader never observes
     *     a docket pointing at a missing data file.
     */
    public void write(File file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("Target file cannot be null");
        }

        if (isV2) {
            // W-LEAK: If the existing docket file (`.hg/dirstate`) is present before writing,
            // read it to determine the old uid.
            String oldUid = null;
            if (file.exists()) {
                try {
                    byte[] oldBytes = Files.readAllBytes(file.toPath());
                    if (oldBytes.length >= 12) {
                        String oldMagic = new String(oldBytes, 0, 12, StandardCharsets.US_ASCII);
                        if ("dirstate-v2\n".equals(oldMagic)) {
                            ByteBuffer oldBuf = ByteBuffer.wrap(oldBytes).order(ByteOrder.BIG_ENDIAN);
                            int oldUidSize = oldBuf.get(124) & 0xFF;
                            byte[] oldUidBytes = new byte[oldUidSize];
                            oldBuf.position(125);
                            oldBuf.get(oldUidBytes);
                            oldUid = new String(oldUidBytes, StandardCharsets.US_ASCII);
                        }
                    }
                } catch (Exception ignored) {
                    // Ignore if the old file is corrupt or cannot be parsed
                }
            }

            // 1. Serialize data file content
            byte[] dataBytes = DirstateV2Serializer.serialize(this);

            // 2. Generate a unique UID (in the form of a random UUID)
            String uid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

            // 3. Write data file .hg/dirstate.<uid>
            File dataFile = new File(file.getParentFile(), "dirstate." + uid);
            SafeFileIO.writeAtomic(dataFile, dataBytes);

            // 4. Assemble Docket bytes
            byte[] uidBytes = uid.getBytes(StandardCharsets.US_ASCII);
            int docketSize = 12 + 32 + 32 + 44 + 4 + 1 + uidBytes.length;
            ByteBuffer docketBuf = ByteBuffer.allocate(docketSize).order(ByteOrder.BIG_ENDIAN);

            // Magic (12 bytes): "dirstate-v2\n"
            byte[] v2Magic = "dirstate-v2\n".getBytes(StandardCharsets.US_ASCII);
            docketBuf.put(v2Magic);

            // P1 (32 bytes, 20-byte hash + 12-byte zero padding)
            byte[] p1_32 = new byte[32];
            System.arraycopy(parent1.getBytes(), 0, p1_32, 0, 20);
            docketBuf.put(p1_32);

            // P2 (32 bytes, 20-byte hash + 12-byte zero padding)
            byte[] p2_32 = new byte[32];
            System.arraycopy(parent2.getBytes(), 0, p2_32, 0, 20);
            docketBuf.put(p2_32);

            // Tree Metadata (44 bytes): root_nodes (start=0 [4B] + count=rootCount [4B]) + nodes_with_entry_count [4B] + nodes_with_copy_source_count [4B] + 28-byte zero padding
            Set<String> rootSegments = new HashSet<>();
            for (String path : entries.keySet()) {
                int slashIdx = path.indexOf('/');
                if (slashIdx == -1) {
                    rootSegments.add(path);
                } else {
                    rootSegments.add(path.substring(0, slashIdx));
                }
            }
            int rootCount = rootSegments.size();

            byte[] treeMetadataBytes = new byte[44];
            ByteBuffer metaBuf = ByteBuffer.wrap(treeMetadataBytes).order(ByteOrder.BIG_ENDIAN);
            metaBuf.putInt(0); // root_nodes children_start
            metaBuf.putInt(rootCount); // root_nodes children_count
            metaBuf.putInt(entries.size()); // nodes_with_entry_count
            metaBuf.putInt(copyMap.size()); // nodes_with_copy_source_count

            docketBuf.put(treeMetadataBytes);

            // Data length (4-byte int)
            docketBuf.putInt(dataBytes.length);

            // UID Size (1 byte)
            docketBuf.put((byte) uidBytes.length);

            // UID (variable)
            docketBuf.put(uidBytes);

            // 5. Write Docket file
            SafeFileIO.writeAtomic(file, docketBuf.array());

            // 6. W-LEAK: Delete the old data file
            if (oldUid != null && !oldUid.equals(uid)) {
                File oldDataFile = new File(file.getParentFile(), "dirstate." + oldUid);
                Files.deleteIfExists(oldDataFile.toPath());
            }
            return;
        }

        // v1 write
        byte[] bytes = serialize();
        SafeFileIO.writeAtomic(file, bytes);
    }
}
