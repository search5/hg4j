package io.github.search5.hg4j.dirstate;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Represents a single fixed-size (44 bytes) node layout in Mercurial dirstate-v2 format.
 * Utilizes a ByteBuffer backing view for high-performance off-heap/on-heap mapping.
 *
 * <p>Field offsets and flag bit positions verified byte-for-byte against a real Mercurial 6.0
 * server (Docker, {@code storage.dirstate-v2.slow-path=allow} pure-Python path — the last
 * release with a working non-Rust dirstate-v2 implementation) by reading
 * {@code mercurial/dirstateutils/v2.py}'s {@code NODE = struct.Struct('>LHHLHLLLLHlll')} and
 * {@code mercurial/pure/parsers.py}'s {@code DIRSTATE_V2_*} bit constants directly, then
 * cross-checked against a captured real {@code .hg/dirstate}+{@code dirstate.<uuid>} fixture.
 * An earlier version of this class used entirely different (fictional, never-verified) offsets
 * and flag bits that happened to round-trip against hg4j's own reader/writer but could not read
 * or be read by real hg.</p>
 */
public class DirstateV2Node {
    /**
     * Size of one node in bytes (44).
     */
    public static final int NODE_SIZE = 44;

    // Bit flags (mercurial/pure/parsers.py DIRSTATE_V2_*, `flags` is a 16-bit field)
    /**
     * Flag bit: the path is tracked in the working directory.
     */
    public static final int WDIR_TRACKED = 1 << 0;
    /**
     * Flag bit: the path is tracked in the first parent.
     */
    public static final int P1_TRACKED = 1 << 1;
    /**
     * Flag bit: there is information from the second parent (merge state).
     */
    public static final int P2_INFO = 1 << 2;
    /**
     * Flag bit: the executable permission is set.
     */
    public static final int MODE_EXEC_PERM = 1 << 3;
    /**
     * Flag bit: the path is a symbolic link.
     */
    public static final int MODE_IS_SYMLINK = 1 << 4;
    /**
     * Flag bit: the node holds a meaningful size; the mode is taken from the permission and symlink bits.
     */
    public static final int HAS_MODE_AND_SIZE = 1 << 10;
    /**
     * Flag bit: the node holds a meaningful modification time.
     */
    public static final int HAS_MTIME = 1 << 11;
    // NOTE: mercurial/pure/parsers.py's own DIRSTATE_V2_DIRECTORY constant says `1 << 13`, but
    // that constant is never actually consulted when parsing (a node is treated as a directory
    // simply by having none of WDIR_TRACKED/P1_TRACKED/P2_INFO set -- see DirstateV2Parser, which
    // mirrors real hg's `if not item.any_tracked: continue`). The value that is actually written
    // to disk for directory placeholder nodes comes from a *different*, locally-redefined
    // constant of the same name in mercurial/dirstateutils/v2.py, which is `1 << 5` -- confirmed
    // against a real captured fixture where an intermediate directory node's flags field is
    // 0x0020 (32). Kept here for documentation; DirstateV2Parser does not need to check it.
    /**
     * Flag value written for the placeholder node of an intermediate directory. It is not consulted when parsing.
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
     * Derives the state character from the tracking flags.
     * @return {@code 'm'} (working directory tracked with second parent info), {@code 'n'} (tracked in the working directory and first parent),
     * {@code 'a'} (tracked in the working directory only), {@code 'r'} (not tracked in the working directory but in a parent) or
     * {@code '\0'} for an intermediate directory node
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
     * Rewrites the tracking flag bits so that {@link #getState()} returns {@code state}; the other flag bits are kept. Writes into the backing buffer.
     * @param state {@code 'n'}, {@code 'a'}, {@code 'm'} or {@code 'r'}; any other character clears the three tracking bits
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

    // Helper: Map unix mode directly into MODE_EXEC_PERM / MODE_IS_SYMLINK flags for strict native hg compatibility
    /**
     * Reconstructs a Unix mode from the flags.
     * @return 0 for a removed or directory node; otherwise {@code 0120000} for a symlink, {@code 0100755} if the executable bit is set, else {@code 0100644}
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
     * Sets the symlink and executable flag bits from a Unix mode; other flag bits are kept. A symlink gets both bits (a symlink always reports
     * the executable permission bits on the file system). The {@code HAS_MODE_AND_SIZE} bit is not changed. Writes into the backing buffer.
     * @param mode a Unix mode such as {@code 0100644}, {@code 0100755} or {@code 0120000}
     */
    public void setMode(int mode) {
        int flags = getFlags() & 0xFFFF;
        flags &= ~(MODE_EXEC_PERM | MODE_IS_SYMLINK);
        boolean isSymlink = (mode & 0120000) == 0120000;
        if (isSymlink) {
            flags |= MODE_IS_SYMLINK;
            // Backlog #39 fix, verified directly against real hg 7.2.4's Rust dirstate-v2 source
            // (rust/hg-core/src/dirstate/entry.rs, mode_changed(): `dirstate_exec_bit =
            // self.mode() & EXEC_BIT_MASK(0o100)` compared against `fs_exec_bit = fresh lstat mode
            // & 0o100`). A real OS symlink's lstat mode ALWAYS reports the full rwxrwxrwx
            // permission bits (there is no such thing as a "non-executable" symlink at the
            // filesystem level) -- so `fs_exec_bit` is unconditionally true for every symlink, and
            // MODE_EXEC_PERM must be set alongside MODE_IS_SYMLINK (never mutually exclusive) for
            // synthesize_unix_mode()'s reconstructed "dirstate_exec_bit" to agree, or real hg's own
            // `hg status` reports every untouched symlink as modified. Confirmed live (2026-09-05)
            // against a real Rust-hg-written dirstate-v2 repository.
            flags |= MODE_EXEC_PERM;
        } else if ((mode & 0111) != 0) {
            flags |= MODE_EXEC_PERM;
        }
        setFlags((short) flags);
    }

    /**
     * Returns the offset of this node's full path in the data file (field at byte 0 of the node).
     * @return the offset of this node's full path in the data file (field at byte 0 of the node)
     */
    public int getPathOffset() {
        return buffer.getInt(offset + 0);
    }

    /**
     * Sets the offset of the path in the data file (field at byte 0).
     * @param pathOffset the new offset
     */
    public void setPathOffset(int pathOffset) {
        buffer.putInt(offset + 0, pathOffset);
    }

    /**
     * Returns the length in bytes of this node's full path (field at byte 4).
     * @return the length in bytes of this node's full path (field at byte 4)
     */
    public short getPathLen() {
        return buffer.getShort(offset + 4);
    }

    /**
     * Sets the length of the full path (field at byte 4).
     * @param pathLen the new length in bytes
     */
    public void setPathLen(short pathLen) {
        buffer.putShort(offset + 4, pathLen);
    }

    /**
     * Returns the index within the full path where the last path segment starts (field at byte 6).
     * @return the index within the full path where the last path segment starts (field at byte 6)
     */
    public short getBasenameStart() {
        return buffer.getShort(offset + 6);
    }

    /**
     * Sets the index within the full path where the last segment starts (field at byte 6).
     * @param start the new index
     */
    public void setBasenameStart(short start) {
        buffer.putShort(offset + 6, start);
    }

    /**
     * Returns the offset of the copy source path in the data file (field at byte 8); only meaningful when {@link #getCopySourceLen()} is not 0.
     * @return the offset of the copy source path in the data file (field at byte 8); only meaningful when {@link #getCopySourceLen()} is not 0
     */
    public int getCopySourceOffset() {
        return buffer.getInt(offset + 8);
    }

    /**
     * Sets the offset of the copy source path in the data file (field at byte 8).
     * @param offsetVal the new offset
     */
    public void setCopySourceOffset(int offsetVal) {
        buffer.putInt(offset + 8, offsetVal);
    }

    /**
     * Returns the length in bytes of the copy source path, 0 if the path is not a copy (field at byte 12).
     * @return the length in bytes of the copy source path, 0 if the path is not a copy (field at byte 12)
     */
    public short getCopySourceLen() {
        return buffer.getShort(offset + 12);
    }

    /**
     * Sets the length of the copy source path (field at byte 12).
     * @param len the new length in bytes, 0 for none
     */
    public void setCopySourceLen(short len) {
        buffer.putShort(offset + 12, len);
    }

    /**
     * Returns the offset in the data file of this node's first child node (field at byte 14).
     * @return the offset in the data file of this node's first child node (field at byte 14)
     */
    public int getChildrenStart() {
        return buffer.getInt(offset + 14);
    }

    /**
     * Sets the offset of the first child node (field at byte 14).
     * @param childrenStart the new offset
     */
    public void setChildrenStart(int childrenStart) {
        buffer.putInt(offset + 14, childrenStart);
    }

    /**
     * Returns the number of child nodes (field at byte 18).
     * @return the number of child nodes (field at byte 18)
     */
    public int getChildrenCount() {
        return buffer.getInt(offset + 18);
    }

    /**
     * Sets the number of child nodes (field at byte 18).
     * @param childrenCount the new count
     */
    public void setChildrenCount(int childrenCount) {
        buffer.putInt(offset + 18, childrenCount);
    }

    /**
     * Returns the number of descendant nodes that have an entry (field at byte 22).
     * @return the number of descendant nodes that have an entry (field at byte 22)
     */
    public int getDescendantsWithEntryCount() {
        return buffer.getInt(offset + 22);
    }

    /**
     * Sets the number of descendants that have an entry (field at byte 22).
     * @param count the new count
     */
    public void setDescendantsWithEntryCount(int count) {
        buffer.putInt(offset + 22, count);
    }

    /**
     * Returns the number of descendant nodes that are tracked (field at byte 26).
     * @return the number of descendant nodes that are tracked (field at byte 26)
     */
    public int getTrackedDescendants() {
        return buffer.getInt(offset + 26);
    }

    /**
     * Sets the number of tracked descendants (field at byte 26).
     * @param count the new count
     */
    public void setTrackedDescendants(int count) {
        buffer.putInt(offset + 26, count);
    }

    /**
     * Returns the 16-bit flags field (byte 30), a combination of the flag constants of this class.
     * @return the 16-bit flags field (byte 30), a combination of the flag constants of this class
     */
    public short getFlags() {
        return buffer.getShort(offset + 30);
    }

    /**
     * Replaces the 16-bit flags field (byte 30).
     * @param flags the new flags
     */
    public void setFlags(short flags) {
        buffer.putShort(offset + 30, flags);
    }

    /**
     * Returns the file size in bytes (field at byte 32), or -1 if {@code HAS_MODE_AND_SIZE} is not set (size unknown, the content must be compared).
     * @return the file size in bytes (field at byte 32), or -1 if {@code HAS_MODE_AND_SIZE} is not set (size unknown, the content must be compared)
     */
    public int getSize() {
        // Real hg's own DirstateItem (mercurial/pure/parsers.py's DirstateItem.from_v2_data)
        // leaves size as `None` (no meaningful cached value -- a full content comparison is
        // required) whenever the HAS_MODE_AND_SIZE bit is unset, most commonly for a same-second
        // racy write. Returning a concrete 0 here (as if the file's real recorded size were
        // genuinely zero) instead of the dirstate-v1 "-1" ambiguous-size sentinel already used
        // elsewhere (see Dirstate.Entry#isStatAmbiguous()) silently converted "ambiguous, needs
        // lookup" into a definite wrong value -- every dirty-check that trusts this size then
        // wrongly concluded the file had shrunk to 0 bytes. Confirmed live: any hg4j write
        // command that reads a real-hg-authored dirstate-v2 file containing such an entry (e.g.
        // the OTHER, untouched file next to the one hg4j is actually modifying) and later writes
        // the dirstate back out would re-serialize that untouched file's entry with a bogus
        // concrete size=0/mtime=epoch, making real hg's next `status`/`commit` see it as spuriously
        // modified.
        if ((getFlags() & HAS_MODE_AND_SIZE) == 0) {
            return -1;
        }
        return buffer.getInt(offset + 32);
    }

    /**
     * Sets the size field (byte 32). The {@code HAS_MODE_AND_SIZE} flag is not changed; set it with {@link #setFlags(short)}.
     * @param size the file size in bytes
     */
    public void setSize(int size) {
        buffer.putInt(offset + 32, size);
    }

    /**
     * Returns the modification time in seconds as an unsigned 32-bit value (field at byte 36), or {@link Dirstate.Entry#AMBIGUOUS_TIME} if {@code HAS_MTIME} is not set.
     * @return the modification time in seconds as an unsigned 32-bit value (field at byte 36), or {@link Dirstate.Entry#AMBIGUOUS_TIME} if {@code HAS_MTIME} is not set
     */
    public long getMtime() {
        // See the identical reasoning on getSize() above -- an absent HAS_MTIME bit means real
        // hg has no meaningful cached mtime, not a real mtime of epoch zero.
        if ((getFlags() & HAS_MTIME) == 0) {
            return Dirstate.Entry.AMBIGUOUS_TIME;
        }
        return buffer.getInt(offset + 36) & 0xFFFFFFFFL;
    }

    /**
     * Sets the modification time field (byte 36) from the low 32 bits of {@code mtime}. The {@code HAS_MTIME} flag is not changed.
     * @param mtime the modification time in seconds since the epoch
     */
    public void setMtime(long mtime) {
        buffer.putInt(offset + 36, (int) (mtime & 0xFFFFFFFFL));
    }

    /**
     * Returns the nanosecond part of the modification time (field at byte 40), or 0 if {@code HAS_MTIME} is not set.
     * @return the nanosecond part of the modification time (field at byte 40), or 0 if {@code HAS_MTIME} is not set
     */
    public int getMtimeNanoseconds() {
        if ((getFlags() & HAS_MTIME) == 0) {
            return 0;
        }
        return buffer.getInt(offset + 40);
    }

    /**
     * Sets the nanosecond field (byte 40). The {@code HAS_MTIME} flag is not changed.
     * @param nanos the nanoseconds
     */
    public void setMtimeNanoseconds(int nanos) {
        buffer.putInt(offset + 40, nanos);
    }
}
