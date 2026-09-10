package io.github.search5.hg4j.dirstate;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Represents a single fixed-size (44 bytes) node layout in Mercurial dirstate-v2 format.
 * Utilizes a ByteBuffer backing view for high-performance off-heap/on-heap mapping.
 *
 * <p>Field offsets and flag bit positions match Mercurial's pure-Python dirstate-v2 path
 * ({@code storage.dirstate-v2.slow-path=allow}) exactly: {@code
 * mercurial/dirstateutils/v2.py}'s {@code NODE = struct.Struct('>LHHLHLLLLHlll')} and
 * {@code mercurial/pure/parsers.py}'s {@code DIRSTATE_V2_*} bit constants.</p>
 *
 * @apiNote A mutable, zero-copy view over one 44-byte node record inside the raw dirstate-v2
 *     data buffer -- {@link io.github.search5.hg4j.dirstate.DirstateV2Parser} constructs
 *     instances to read an existing on-disk dirstate, and {@link
 *     io.github.search5.hg4j.dirstate.DirstateV2Serializer} constructs them to write a new one.
 *     Callers should go through those two classes rather than using this class directly.
 */
public class DirstateV2Node {
    /** Fixed size, in bytes, of one dirstate-v2 node record. */
    public static final int NODE_SIZE = 44;

    // Bit flags (mercurial/pure/parsers.py DIRSTATE_V2_*, `flags` is a 16-bit field)
    /** Flag bit: the file is tracked in the working directory. */
    public static final int WDIR_TRACKED = 1 << 0;
    /** Flag bit: the file is tracked in the first parent commit. */
    public static final int P1_TRACKED = 1 << 1;
    /** Flag bit: the file has second-parent (merge) info recorded. */
    public static final int P2_INFO = 1 << 2;
    /** Flag bit: the cached file mode has the executable permission bit set. */
    public static final int MODE_EXEC_PERM = 1 << 3;
    /** Flag bit: the cached file mode is a symlink. */
    public static final int MODE_IS_SYMLINK = 1 << 4;
    /** Flag bit: {@link #getMode()}'s mode/{@link #getSize()}'s size fields hold a meaningful cached value. */
    public static final int HAS_MODE_AND_SIZE = 1 << 10;
    /** Flag bit: {@link #getMtime()}/{@link #getMtimeNanoseconds()} hold a meaningful cached value. */
    public static final int HAS_MTIME = 1 << 11;

    /**
     * Flag bit written to disk for an intermediate directory placeholder node.
     *
     * @apiNote {@code mercurial/pure/parsers.py}'s own {@code DIRSTATE_V2_DIRECTORY} constant
     *     says {@code 1 << 13}, but that constant is never actually consulted when parsing (a
     *     node is treated as a directory simply by having none of {@link #WDIR_TRACKED}/{@link
     *     #P1_TRACKED}/{@link #P2_INFO} set -- see {@link DirstateV2Parser}, which mirrors real
     *     hg's {@code if not item.any_tracked: continue}). The value that is actually written to
     *     disk for directory placeholder nodes comes from a <em>different</em>, locally-redefined
     *     constant of the same name in {@code mercurial/dirstateutils/v2.py}, which is {@code
     *     1 << 5} -- matching a real captured fixture where an intermediate directory node's
     *     flags field is {@code 0x0020} (32). Kept here for documentation; {@link
     *     DirstateV2Parser} does not need to check it.
     */
    public static final int DIRECTORY = 1 << 5;

    private final ByteBuffer buffer;
    private final int offset;

    /**
     * Constructs a node view over the backing byte array at a specific offset.
     *
     * @param data backing byte array
     * @param offset offset where the node starts (must be 4-byte aligned for performance)
     */
    public DirstateV2Node(byte[] data, int offset) {
        this(ByteBuffer.wrap(data), offset);
    }

    /**
     * Constructs a node view over the backing ByteBuffer at a specific offset.
     *
     * @param buffer backing ByteBuffer
     * @param offset offset where the node starts
     */
    public DirstateV2Node(ByteBuffer buffer, int offset) {
        if (buffer == null) {
            throw new IllegalArgumentException("Backing buffer cannot be null");
        }
        if (offset < 0 || offset + NODE_SIZE > buffer.capacity()) {
            throw new IndexOutOfBoundsException("Node boundary exceeds buffer capacity");
        }
        this.buffer = buffer.duplicate().order(ByteOrder.BIG_ENDIAN);
        this.offset = offset;
    }

    /**
     * Derives the dirstate-v1-style single-character state ({@code 'n'}/{@code 'a'}/{@code
     * 'r'}/{@code 'm'}, or {@code '\0'} for an intermediate directory placeholder) from this
     * node's {@link #WDIR_TRACKED}/{@link #P1_TRACKED}/{@link #P2_INFO} flag bits.
     *
     * @return the derived state character
     */
    public char getState() {
        int flags = getFlags() & 0xFFFF;
        boolean wdirTracked = (flags & WDIR_TRACKED) != 0;
        boolean p1Tracked = (flags & P1_TRACKED) != 0;
        boolean p2Info = (flags & P2_INFO) != 0;

        if (wdirTracked) {
            if (p2Info) {
                return 'm';
            } else if (p1Tracked) {
                return 'n';
            } else {
                return 'a';
            }
        } else {
            if (p1Tracked || p2Info) {
                return 'r';
            } else {
                return '\0'; // intermediate directory
            }
        }
    }

    /**
     * Sets this node's {@link #WDIR_TRACKED}/{@link #P1_TRACKED}/{@link #P2_INFO} flag bits from
     * a dirstate-v1-style state character, the inverse of {@link #getState()}.
     *
     * @param state one of {@code 'n'}, {@code 'a'}, {@code 'm'}, {@code 'r'}; any other value
     *     (including {@code 'd'} and {@code '\0'}) clears all three flags
     */
    public void setState(char state) {
        int flags = getFlags() & 0xFFFF;
        flags &= ~(WDIR_TRACKED | P1_TRACKED | P2_INFO);

        switch (state) {
            case 'n':
                flags |= (WDIR_TRACKED | P1_TRACKED);
                break;
            case 'a':
                flags |= WDIR_TRACKED;
                break;
            case 'm':
                flags |= (WDIR_TRACKED | P2_INFO);
                break;
            case 'r':
                flags |= P1_TRACKED;
                break;
            case 'd':
            case '\0':
            default:
                break;
        }
        setFlags((short) flags);
    }

    /**
     * Reconstructs a Unix file mode from this node's {@link #MODE_EXEC_PERM}/{@link
     * #MODE_IS_SYMLINK} flag bits, for strict native-hg compatibility.
     *
     * @return {@code 0} for a removed/directory/placeholder entry ({@link #getState()} of {@code
     *     'r'}, {@code 'd'}, or {@code '\0'}); otherwise the synthesized mode ({@code 0120000}
     *     for a symlink, {@code 0100755} for an executable regular file, {@code 0100644}
     *     otherwise)
     */
    public int getMode() {
        char state = getState();
        if (state == 'r' || state == 'd' || state == '\0') {
            return 0;
        }
        int flags = getFlags() & 0xFFFF;
        if ((flags & MODE_IS_SYMLINK) != 0) {
            return 0120000; // S_IFLNK
        } else if ((flags & MODE_EXEC_PERM) != 0) {
            return 0100755;
        } else {
            return 0100644;
        }
    }

    /**
     * Sets this node's {@link #MODE_EXEC_PERM}/{@link #MODE_IS_SYMLINK} flag bits from a Unix
     * file mode, the inverse of {@link #getMode()}.
     *
     * @param mode Unix file mode; a symlink mode ({@code 0120000}) sets both flags together (see
     *     this method's own inline note on why a symlink is always treated as executable), and an
     *     executable-permission mode sets just {@link #MODE_EXEC_PERM}
     */
    public void setMode(int mode) {
        int flags = getFlags() & 0xFFFF;
        flags &= ~(MODE_EXEC_PERM | MODE_IS_SYMLINK);
        boolean isSymlink = (mode & 0120000) == 0120000;
        if (isSymlink) {
            flags |= MODE_IS_SYMLINK;
            // Real hg's Rust dirstate-v2 source (rust/hg-core/src/dirstate/entry.rs,
            // mode_changed(): `dirstate_exec_bit = self.mode() & EXEC_BIT_MASK(0o100)` compared
            // against `fs_exec_bit = fresh lstat mode & 0o100`) means a real OS symlink's lstat
            // mode ALWAYS reports the full rwxrwxrwx permission bits (there is no such thing as a
            // "non-executable" symlink at the filesystem level) -- so `fs_exec_bit` is
            // unconditionally true for every symlink, and MODE_EXEC_PERM must be set alongside
            // MODE_IS_SYMLINK (never mutually exclusive) for synthesize_unix_mode()'s
            // reconstructed "dirstate_exec_bit" to agree, or real hg's own `hg status` reports
            // every untouched symlink as modified.
            flags |= MODE_EXEC_PERM;
        } else if ((mode & 0111) != 0) {
            flags |= MODE_EXEC_PERM;
        }
        setFlags((short) flags);
    }

    /**
     * Returns the byte offset, within the dirstate-v2 data block, of this node's full path
     * string.
     *
     * @return path string offset
     */
    public int getPathOffset() {
        return buffer.getInt(offset + 0);
    }

    /**
     * Sets the byte offset, within the dirstate-v2 data block, of this node's full path string.
     *
     * @param pathOffset path string offset
     */
    public void setPathOffset(int pathOffset) {
        buffer.putInt(offset + 0, pathOffset);
    }

    /**
     * Returns the length, in bytes, of this node's path string.
     *
     * @return path string length
     */
    public short getPathLen() {
        return buffer.getShort(offset + 4);
    }

    /**
     * Sets the length, in bytes, of this node's path string.
     *
     * @param pathLen path string length
     */
    public void setPathLen(short pathLen) {
        buffer.putShort(offset + 4, pathLen);
    }

    /**
     * Returns the byte offset, within the path string, where the final path component
     * (basename) begins.
     *
     * @return basename start offset, relative to the start of the path string
     */
    public short getBasenameStart() {
        return buffer.getShort(offset + 6);
    }

    /**
     * Sets the byte offset, within the path string, where the final path component (basename)
     * begins.
     *
     * @param start basename start offset, relative to the start of the path string
     */
    public void setBasenameStart(short start) {
        buffer.putShort(offset + 6, start);
    }

    /**
     * Returns the byte offset, within the dirstate-v2 data block, of this node's copy-source
     * path string (when the file is recorded as a copy).
     *
     * @return copy-source path string offset
     */
    public int getCopySourceOffset() {
        return buffer.getInt(offset + 8);
    }

    /**
     * Sets the byte offset, within the dirstate-v2 data block, of this node's copy-source path
     * string.
     *
     * @param offsetVal copy-source path string offset
     */
    public void setCopySourceOffset(int offsetVal) {
        buffer.putInt(offset + 8, offsetVal);
    }

    /**
     * Returns the length, in bytes, of this node's copy-source path string.
     *
     * @return copy-source path string length, or {@code 0} if the file is not a copy
     */
    public short getCopySourceLen() {
        return buffer.getShort(offset + 12);
    }

    /**
     * Sets the length, in bytes, of this node's copy-source path string.
     *
     * @param len copy-source path string length
     */
    public void setCopySourceLen(short len) {
        buffer.putShort(offset + 12, len);
    }

    /**
     * Returns the byte offset, within the dirstate-v2 data block, of this directory node's first
     * child node record.
     *
     * @return children block start offset
     */
    public int getChildrenStart() {
        return buffer.getInt(offset + 14);
    }

    /**
     * Sets the byte offset, within the dirstate-v2 data block, of this directory node's first
     * child node record.
     *
     * @param childrenStart children block start offset
     */
    public void setChildrenStart(int childrenStart) {
        buffer.putInt(offset + 14, childrenStart);
    }

    /**
     * Returns the number of direct children of this directory node.
     *
     * @return direct child count
     */
    public int getChildrenCount() {
        return buffer.getInt(offset + 18);
    }

    /**
     * Sets the number of direct children of this directory node.
     *
     * @param childrenCount direct child count
     */
    public void setChildrenCount(int childrenCount) {
        buffer.putInt(offset + 18, childrenCount);
    }

    /**
     * Returns the number of descendants of this directory node (direct or indirect) that have a
     * tracked-file entry.
     *
     * @return descendants-with-entry count
     */
    public int getDescendantsWithEntryCount() {
        return buffer.getInt(offset + 22);
    }

    /**
     * Sets the number of descendants of this directory node that have a tracked-file entry.
     *
     * @param count descendants-with-entry count
     */
    public void setDescendantsWithEntryCount(int count) {
        buffer.putInt(offset + 22, count);
    }

    /**
     * Returns the number of descendants of this directory node that are themselves tracked.
     *
     * @return tracked-descendants count
     */
    public int getTrackedDescendants() {
        return buffer.getInt(offset + 26);
    }

    /**
     * Sets the number of descendants of this directory node that are themselves tracked.
     *
     * @param count tracked-descendants count
     */
    public void setTrackedDescendants(int count) {
        buffer.putInt(offset + 26, count);
    }

    /**
     * Returns this node's raw 16-bit flags field (see the {@code DIRSTATE_V2_*} constants on
     * this class).
     *
     * @return raw flags bit field
     */
    public short getFlags() {
        return buffer.getShort(offset + 30);
    }

    /**
     * Sets this node's raw 16-bit flags field.
     *
     * @param flags raw flags bit field
     */
    public void setFlags(short flags) {
        buffer.putShort(offset + 30, flags);
    }

    /**
     * Returns the cached file size, or a sentinel when no meaningful cached size exists.
     *
     * @implNote Real hg's own {@code DirstateItem} ({@code mercurial/pure/parsers.py}'s {@code
     *     DirstateItem.from_v2_data}) leaves size as {@code None} (no meaningful cached value --
     *     a full content comparison is required) whenever the {@link #HAS_MODE_AND_SIZE} bit is
     *     unset, most commonly for a same-second racy write. Returning a concrete {@code 0} here
     *     (as if the file's real recorded size were genuinely zero) instead of the dirstate-v1
     *     {@code -1} ambiguous-size sentinel already used elsewhere (see {@link
     *     Dirstate.Entry#isStatAmbiguous()}) would silently convert "ambiguous, needs lookup"
     *     into a definite wrong value -- every dirty-check that trusts this size would then
     *     wrongly conclude the file had shrunk to 0 bytes, and a subsequent dirstate rewrite of
     *     an untouched entry with such a size would make real hg's next {@code status}/{@code
     *     commit} see it as spuriously modified.
     * @return the cached size in bytes, or {@code -1} if {@link #HAS_MODE_AND_SIZE} is unset
     */
    public int getSize() {
        if ((getFlags() & HAS_MODE_AND_SIZE) == 0) {
            return -1;
        }
        return buffer.getInt(offset + 32);
    }

    /**
     * Sets the cached file size.
     *
     * @param size cached size in bytes
     */
    public void setSize(int size) {
        buffer.putInt(offset + 32, size);
    }

    /**
     * Returns the cached mtime (seconds since epoch), or a sentinel when no meaningful cached
     * mtime exists.
     *
     * @implNote See the identical reasoning on {@link #getSize()} above -- an absent {@link
     *     #HAS_MTIME} bit means real hg has no meaningful cached mtime, not a real mtime of
     *     epoch zero.
     * @return the cached mtime in seconds since epoch, or {@link Dirstate.Entry#AMBIGUOUS_TIME}
     *     if {@link #HAS_MTIME} is unset
     */
    public long getMtime() {
        if ((getFlags() & HAS_MTIME) == 0) {
            return Dirstate.Entry.AMBIGUOUS_TIME;
        }
        return buffer.getInt(offset + 36) & 0xFFFFFFFFL;
    }

    /**
     * Sets the cached mtime (seconds since epoch).
     *
     * @param mtime cached mtime in seconds since epoch
     */
    public void setMtime(long mtime) {
        buffer.putInt(offset + 36, (int) (mtime & 0xFFFFFFFFL));
    }

    /**
     * Returns the sub-second component of the cached mtime.
     *
     * @return cached mtime nanoseconds, or {@code 0} if {@link #HAS_MTIME} is unset
     */
    public int getMtimeNanoseconds() {
        if ((getFlags() & HAS_MTIME) == 0) {
            return 0;
        }
        return buffer.getInt(offset + 40);
    }

    /**
     * Sets the sub-second component of the cached mtime.
     *
     * @param nanos cached mtime nanoseconds
     */
    public void setMtimeNanoseconds(int nanos) {
        buffer.putInt(offset + 40, nanos);
    }
}
