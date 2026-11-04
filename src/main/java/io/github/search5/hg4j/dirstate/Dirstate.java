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
 */
public class Dirstate {

    private NodeId parent1 = NodeId.NULL;
    private NodeId parent2 = NodeId.NULL;
    private final LazyEntryMap entries = new LazyEntryMap();
    private final Map<String, String> copyMap = new LinkedHashMap<>();
    private boolean isV2 = false;

    // What the attached dirstate-v2 data file looked like when it was read: the append writer needs them (see write(File))
    private String baseUid;
    private int baseLength;
    private int baseUnreachable;
    private byte[] baseIgnoreHash = new byte[20];
    private Map<String, String> baseCopies = new LinkedHashMap<>();
    private File baseDocket;

    /**
     * Returns the live map of copy and rename records, from destination path to source path.
     * Both are repository-relative, slash separated paths.
     * <p>The returned map is the internal one, never {@code null}. Changing it changes only this in-memory object;
     * nothing reaches the disk until {@link #write(File)} (normally through {@code HgRepository.writeDirstate}) is called.
     * The map is not cleared by {@link #read(byte[])}; records found in the data read are added to it.
     * @return the mutable copy map
     */
    public Map<String, String> getCopyMap() {
        return copyMap;
    }

    /**
     * Records that {@code dest} was copied or renamed from {@code src}, replacing any earlier record for {@code dest}.
     * Equivalent to {@code getCopyMap().put(dest, src)}. In memory only until the dirstate is written; no argument is validated.
     * @param dest repository-relative destination path
     * @param src repository-relative source path
     */
    public void addCopy(String dest, String src) {
        copyMap.put(dest, src);
    }

    /**
     * Tells whether this dirstate is written in the dirstate-v2 format (a docket file plus a separate data file) rather than the
     * classic v1 format.
     * <p>It is {@code true} after {@link #read(File)} has read a v2 docket, or after {@link #setV2(boolean)} was called with {@code true}.
     * @return {@code true} if {@link #write(File)} will use the v2 format
     */
    public boolean isV2() {
        return isV2;
    }

    /**
     * Chooses the on-disk format used by the next {@link #write(File)}.
     * <p>This only sets a flag; nothing is converted or written. {@code HgRepository.writeDirstate} sets it from the repository's
     * {@code dirstate-v2} requirement on every write.
     * @param v2 {@code true} for dirstate-v2, {@code false} for the v1 format
     */
    public void setV2(boolean v2) {
        this.isV2 = v2;
    }

    /**
     * One dirstate entry: what the dirstate remembers about a single tracked path.
     * <p>The mtime is limited to the unsigned 32-bit range because the v1 format stores it that way; the compact constructor
     * throws {@link IllegalArgumentException} for a {@code time} below 0 or above {@code 0xFFFFFFFF}.
     * Instances are immutable.
     * @param state the state character: {@code 'n'} normal, {@code 'a'} added, {@code 'r'} removed, {@code 'm'} merged
     * @param mode the Unix file mode (for example {@code 0100644}, {@code 0100755} or {@code 0120000} for a symlink)
     * @param size the file size in bytes; a negative value means "unknown, compare the content" (see {@link #isStatAmbiguous()})
     * @param time the modification time in seconds since the epoch, 0 to {@code 0xFFFFFFFF}, or {@link #AMBIGUOUS_TIME}
     * @param nanos the nanosecond part of the modification time; only the v2 format stores it, so it is 0 for entries read from a v1 file
     */
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

        /**
         * Creates an entry with no nanosecond part (the same as passing 0 as {@code nanos}).
         * @param state the state character, see {@link Entry}
         * @param mode the Unix file mode
         * @param size the file size in bytes, or a negative value if unknown
         * @param time the modification time in seconds since the epoch, 0 to {@code 0xFFFFFFFF}
         * @throws IllegalArgumentException if {@code time} is outside the unsigned 32-bit range
         */
        public Entry(char state, int mode, int size, long time) {
            this(state, mode, size, time, 0);
        }

        /**
         * Returns the state character of this entry.
         * @return {@code 'n'}, {@code 'a'}, {@code 'r'} or {@code 'm'} for entries written by Mercurial
         */
        public char getState() {
            return state;
        }

        /**
         * Returns the Unix file mode recorded for this entry.
         * @return the mode, for example {@code 0100644}
         */
        public int getMode() {
            return mode;
        }

        /**
         * Returns the recorded file size.
         * @return the size in bytes, or a negative value if it is unknown
         */
        public int getSize() {
            return size;
        }

        /**
         * Returns the recorded modification time.
         * @return seconds since the epoch (0 to {@code 0xFFFFFFFF}), or {@link #AMBIGUOUS_TIME} if it cannot be trusted
         */
        public long getTime() {
            return time;
        }

        /**
         * Returns the nanosecond part of the recorded modification time.
         * @return the nanoseconds, 0 if the format did not store them
         */
        public int getNanos() {
            return nanos;
        }

        /**
         * Whether this entry's cached stat info (size/mtime) cannot be trusted at face value and
         * a genuine content-level comparison is required instead. Real hg ties this to BOTH a
         * negative size (its own "unset"/needs-lookup size sentinel, e.g. minted for a brand new
         * {@code hg add} or a same-second racy commit -- verified live: `hg commit` immediately
         * after `hg add` on the same wall-clock second produces a 'n' entry with mode=0, size=-1,
         * mtime=-1 all together) AND/OR the {@link #AMBIGUOUS_TIME} mtime sentinel alone. Every
         * dirty-check that trusts a dirstate entry's cached size/mtime WITHOUT checking this first
         * will wrongly treat such an entry as unconditionally "modified" the instant its real
         * on-disk size differs from -1 (which is always, for any non-empty-sentinel file) --
         * confirmed live against real hg 7.2's own dirstate output for exactly this scenario.
         */
        public boolean isStatAmbiguous() {
            return size < 0 || time == AMBIGUOUS_TIME;
        }
    }

    /**
     * Returns the first parent of the working directory.
     * @return a new 20-byte array holding the node id; all zero bytes when there is no parent
     */
    public byte[] getParent1() {
        return parent1.getBytes();
    }

    /**
     * Returns the second parent of the working directory, which is set only while a merge is in progress.
     * @return a new 20-byte array holding the node id; all zero bytes when there is no second parent
     */
    public byte[] getParent2() {
        return parent2.getBytes();
    }

    /**
     * Returns the first parent of the working directory as a {@link NodeId}.
     * @return the parent; {@link NodeId#NULL} when there is none, never {@code null}
     */
    public NodeId getParent1Node() {
        return parent1;
    }

    /**
     * Returns the second parent of the working directory as a {@link NodeId}.
     * @return the parent; {@link NodeId#NULL} when there is no merge in progress, never {@code null}
     */
    public NodeId getParent2Node() {
        return parent2;
    }

    /**
     * Sets both working directory parents. In memory only until the dirstate is written.
     * @param p1 the first parent; use {@link NodeId#NULL} for none, must not be {@code null}
     * @param p2 the second parent; use {@link NodeId#NULL} for none, must not be {@code null}
     * @throws IllegalArgumentException if either argument is {@code null}
     */
    public void setParents(NodeId p1, NodeId p2) {
        if (p1 == null || p2 == null) {
            throw new IllegalArgumentException("Parents cannot be null");
        }
        this.parent1 = p1;
        this.parent2 = p2;
    }

    /**
     * Sets both working directory parents from raw node ids. In memory only until the dirstate is written.
     * @param p1 the first parent, exactly 20 bytes (all zero for none)
     * @param p2 the second parent, exactly 20 bytes (all zero for none)
     * @throws IllegalArgumentException if either array is {@code null} or not exactly 20 bytes long
     */
    public void setParents(byte[] p1, byte[] p2) {
        setParents(new NodeId(p1), new NodeId(p2));
    }

    /**
     * Returns the live map of tracked paths to their entries; keys are repository-relative, slash separated paths.
     * <p>For a dirstate read from a v2 file the map reads entries lazily from the data file and keeps only the changes made since in memory;
     * a lookup can then throw {@link java.io.UncheckedIOException} if the data file cannot be read.
     * Changes made through the map are in memory only, exactly like {@link #addEntry} and {@link #removeEntry}, until the dirstate is written.
     * @return the mutable entry map, never {@code null}
     */
    public Map<String, Entry> getEntries() {
        return entries;
    }

    /**
     * Adds the entry for {@code path}, replacing an existing one. In memory only until the dirstate is written;
     * the copy map is not touched.
     * @param path repository-relative, slash separated path
     * @param entry the entry to store
     */
    public void addEntry(String path, Entry entry) {
        entries.put(path, entry);
    }

    /**
     * Removes the entry for {@code path} if there is one. In memory only until the dirstate is written.
     * A copy record for the path in {@link #getCopyMap()} is left in place.
     * @param path repository-relative, slash separated path
     */
    public void removeEntry(String path) {
        entries.remove(path);
    }

    /**
     * Parses the content of a classic v1 dirstate file: two 20-byte parents followed by the entries, each a 17-byte header
     * (state, mode, size, unsigned 32-bit mtime, path length) and the path; a copy source follows the path after a NUL byte.
     * <p>The parents and entries are replaced by the parsed ones. Copy records found are added to the copy map, which is not cleared first.
     * Nothing is read from or written to the disk and no lock is involved. A v2 docket cannot be parsed by this method; use {@link #read(File)}.
     * @param bytes the complete content of a v1 dirstate file
     * @throws HgCorruptDataException if {@code bytes} is {@code null}, shorter than 40 bytes or truncated in an entry
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
     * Reads the dirstate from a file, usually {@code .hg/dirstate}. A file that starts with the {@code dirstate-v2} magic is read as a docket:
     * the parents and the tree description are taken from it and the data file {@code dirstate.<uid>} next to it is mapped read-only and used
     * in place (entries are not loaded up front); {@link #isV2()} then returns {@code true}. Any other file is parsed as v1 through
     * {@link #read(byte[])}.
     * <p>The docket and data file named in it are remembered so that a later {@link #write(File)} to the same file can append to the data file.
     * This method takes no lock; to get a consistent view while other processes may write, the caller must hold the working copy lock
     * ({@code HgRepository.lockWorkingCopy()}). Reading a dirstate through {@code HgRepository.getDirstate()} reads the file every time it is called
     * (except inside an open changing context).
     * @param file the dirstate file; must exist
     * @throws IOException if {@code file} is {@code null} or does not exist, or cannot be read
     * @throws HgCorruptDataException if the v2 data file is missing or shorter than the docket says, or the v1 content is malformed
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

                // The data file named by the docket, read in place: its first `data_length` bytes are what the docket describes, and it
                // may be longer (a writer appended and has not yet replaced the docket, or died before it did)
                File dataFile = new File(file.getParentFile(), "dirstate." + uid);
                if (!dataFile.exists()) {
                    throw new HgCorruptDataException("Dirstate-v2 data file not found for uid: " + uid);
                }
                this.isV2 = true;
                this.parent1 = new NodeId(p1);
                this.parent2 = new NodeId(p2);
                int rootStart = docketBuf.getInt(76);
                int rootCount = docketBuf.getInt(80);
                int entryCount = docketBuf.getInt(84);
                int copyCount = docketBuf.getInt(88);
                java.nio.ByteBuffer mapped;
                try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(dataFile.toPath(),
                        java.nio.file.StandardOpenOption.READ)) {
                    if (channel.size() < dataLength) {
                        throw new HgCorruptDataException("Dirstate-v2 data file is shorter than the docket says. Expected " + dataLength
                                + " but got " + channel.size());
                    }
                    mapped = dataLength == 0 ? java.nio.ByteBuffer.allocate(0)
                            : channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, dataLength);
                }
                DirstateV2Tree tree = new DirstateV2Tree(mapped, rootStart, rootCount, entryCount, copyCount);
                this.entries.attach(tree);
                // BUG (backlog #39 wave 4, 2026-09-05): the copy sources of the entries have to be kept as well, or a later write of
                // the dirstate loses the record of a pending `hg copy`.
                this.copyMap.clear();
                this.copyMap.putAll(tree.copies());
                this.baseUid = uid;
                this.baseLength = dataLength;
                this.baseUnreachable = docketBuf.getInt(92);
                this.baseIgnoreHash = new byte[20];
                docketBuf.get(100, this.baseIgnoreHash);
                this.baseCopies = new LinkedHashMap<>(this.copyMap);
                this.baseDocket = file;
                return;
            }
        }

        // Fallback to v1 parse
        read(bytes);
    }

    /**
     * Returns this dirstate in the classic v1 format, whatever {@link #isV2()} says: the two parents, then each entry with a 17-byte header
     * and its path, followed by NUL and the copy source for a copied path. The nanosecond part of the entries is not stored.
     * Nothing is written to the disk.
     * @return the v1 file content
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
     * The usual way to save a dirstate-v2: the changes made since it was read are appended to the data file and the docket is replaced,
     * as the Rust implementation does ({@code DirstateMapWriteMode::Auto}). The data comes first and the docket, which holds the length
     * that is valid, last, so that a crash in between leaves the old state intact. When the file on disk is not the one that was read,
     * or too much of it is unreachable (ratio of 0.5, {@code ACCEPTABLE_UNREACHABLE_BYTES_RATIO}), the caller writes a new file instead.
     */
    private boolean appendTo(File docket) throws IOException {
        DirstateV2Tree tree = entries.base();
        if (tree == null || baseUid == null || baseDocket == null || !docket.equals(baseDocket) || !docket.exists()) {
            return false;
        }
        byte[] onDisk = Files.readAllBytes(docket.toPath());
        if (onDisk.length < 125 || !"dirstate-v2\n".equals(new String(onDisk, 0, 12, StandardCharsets.US_ASCII))) {
            return false;
        }
        ByteBuffer disk = ByteBuffer.wrap(onDisk).order(ByteOrder.BIG_ENDIAN);
        int uidSize = disk.get(124) & 0xFF;
        String diskUid = new String(onDisk, 125, uidSize, StandardCharsets.US_ASCII);
        if (!diskUid.equals(baseUid) || disk.getInt(120) != baseLength) {
            return false; // somebody else replaced it: write from scratch
        }
        Set<String> changed = new HashSet<>(entries.changed().keySet());
        changed.addAll(entries.removedPaths());
        for (String path : copyMap.keySet()) {
            if (!copyMap.get(path).equals(baseCopies.get(path))) {
                changed.add(path);
            }
        }
        for (String path : baseCopies.keySet()) {
            if (!copyMap.containsKey(path)) {
                changed.add(path);
            }
        }
        DirstateV2Appender.Result result = new DirstateV2Appender(tree, baseLength, entries::get, copyMap).append(changed);
        long unreachable = (long) baseUnreachable + result.unreachableAdded();
        if (baseLength == 0 || (double) unreachable / baseLength >= 0.5) {
            return false;
        }
        File dataFile = new File(docket.getParentFile(), "dirstate." + baseUid);
        long newLength = (long) baseLength + result.data().length;
        if (result.data().length > 0) {
            try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(dataFile, "rw")) {
                file.setLength(baseLength); // whatever a writer that died left past the valid length goes
                file.seek(baseLength);
                file.write(result.data());
                file.getFD().sync();
            }
        }
        ByteBuffer next = ByteBuffer.allocate(onDisk.length).order(ByteOrder.BIG_ENDIAN);
        next.put(onDisk);
        byte[] p1 = new byte[32];
        byte[] p2 = new byte[32];
        System.arraycopy(parent1.getBytes(), 0, p1, 0, 20);
        System.arraycopy(parent2.getBytes(), 0, p2, 0, 20);
        next.put(12, p1);
        next.put(44, p2);
        next.putInt(76, result.rootStart());
        next.putInt(80, result.rootCount());
        next.putInt(84, entries.size());
        next.putInt(88, copyMap.size());
        next.putInt(92, (int) unreachable);
        next.putInt(120, (int) newLength);
        SafeFileIO.writeAtomic(docket, next.array());
        read(docket);
        return true;
    }

    /**
     * Writes this dirstate to {@code file}, usually {@code .hg/dirstate}.
     * <p>For v1 the file is replaced atomically (temporary file and rename) with the result of {@link #serialize()}.
     * For v2 ({@link #isV2()}) the changes made since the dirstate was read are appended to the existing data file and the docket is then replaced
     * atomically (the docket holds the valid length, so a crash in between leaves the old state intact); if the file on disk is no longer the one that was
     * read, or at least half of the data file is unreachable, a new data file {@code dirstate.<uid>} is written first, then the docket, and the old data file
     * is deleted. Afterwards the v2 dirstate is attached to the file just written, so it can be written again.
     * <p>This method does not acquire any Mercurial lock. It is meant to run while the caller holds the working copy lock
     * ({@code HgRepository.lockWorkingCopy()}); inside {@code HgRepository.changingFiles} or {@code changingParents} the repository calls it
     * once, when the outermost context ends without an exception.
     * @param file the dirstate file to write; its directory receives the v2 data file
     * @throws IllegalArgumentException if {@code file} is {@code null}
     * @throws IOException if writing fails
     */
    public void write(File file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("Target file cannot be null");
        }

        if (isV2 && appendTo(file)) {
            return;
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

            // 6. Attach the file just written (the changes made so far are in it now), then delete the old data file: a mapped file
            // cannot be deleted on every platform, so it is released first and a failure to delete only leaves an orphan behind
            read(file);
            if (oldUid != null && !oldUid.equals(uid)) {
                try {
                    Files.deleteIfExists(new File(file.getParentFile(), "dirstate." + oldUid).toPath());
                } catch (IOException ignored) {
                    // an orphan data file is harmless
                }
            }
            return;
        }

        // v1 write
        byte[] bytes = serialize();
        SafeFileIO.writeAtomic(file, bytes);
    }
}
