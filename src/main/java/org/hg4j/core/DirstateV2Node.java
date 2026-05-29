package org.hg4j.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Represents a single fixed-size (44 bytes) node layout in Mercurial dirstate-v2 format.
 * Utilizes a ByteBuffer backing view for high-performance off-heap/on-heap mapping.
 */
public class DirstateV2Node {
    public static final int NODE_SIZE = 44;

    // Bit flags
    public static final int WDIR_TRACKED = 1 << 0;
    public static final int P1_TRACKED = 1 << 1;
    public static final int P2_INFO = 1 << 2;
    public static final int HAS_MODE_AND_SIZE = 1 << 3;
    public static final int HAS_MTIME = 1 << 4;
    public static final int MODE_EXEC_PERM = 1 << 5;
    public static final int MODE_IS_SYMLINK = 1 << 6;

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

    public void setMode(int mode) {
        int flags = getFlags() & 0xFFFF;
        flags &= ~(MODE_EXEC_PERM | MODE_IS_SYMLINK);
        if ((mode & 0120000) == 0120000) {
            flags |= MODE_IS_SYMLINK;
        } else if ((mode & 0111) != 0) {
            flags |= MODE_EXEC_PERM;
        }
        setFlags((short) flags);
    }

    public int getPathOffset() {
        return buffer.getInt(offset + 30);
    }

    public void setPathOffset(int pathOffset) {
        buffer.putInt(offset + 30, pathOffset);
    }

    public short getPathLen() {
        return buffer.getShort(offset + 34);
    }

    public void setPathLen(short pathLen) {
        buffer.putShort(offset + 34, pathLen);
    }

    public short getBasenameStart() {
        return buffer.getShort(offset + 36);
    }

    public void setBasenameStart(short start) {
        buffer.putShort(offset + 36, start);
    }

    public int getCopySourceOffset() {
        return buffer.getInt(offset + 38);
    }

    public void setCopySourceOffset(int offsetVal) {
        buffer.putInt(offset + 38, offsetVal);
    }

    public short getCopySourceLen() {
        return buffer.getShort(offset + 42);
    }

    public void setCopySourceLen(short len) {
        buffer.putShort(offset + 42, len);
    }

    public int getChildrenStart() {
        return buffer.getInt(offset + 0);
    }

    public void setChildrenStart(int childrenStart) {
        buffer.putInt(offset + 0, childrenStart);
    }

    public int getChildrenCount() {
        return buffer.getInt(offset + 4);
    }

    public void setChildrenCount(int childrenCount) {
        buffer.putInt(offset + 4, childrenCount);
    }

    public int getDescendantsWithEntryCount() {
        return buffer.getInt(offset + 8);
    }

    public void setDescendantsWithEntryCount(int count) {
        buffer.putInt(offset + 8, count);
    }

    public int getTrackedDescendants() {
        return buffer.getInt(offset + 12);
    }

    public void setTrackedDescendants(int count) {
        buffer.putInt(offset + 12, count);
    }

    public short getFlags() {
        return buffer.getShort(offset + 16);
    }

    public void setFlags(short flags) {
        buffer.putShort(offset + 16, flags);
    }

    public int getSize() {
        if ((getFlags() & HAS_MODE_AND_SIZE) == 0) {
            return 0;
        }
        return buffer.getInt(offset + 18);
    }

    public void setSize(int size) {
        buffer.putInt(offset + 18, size);
    }

    public long getMtime() {
        if ((getFlags() & HAS_MTIME) == 0) {
            return 0;
        }
        return buffer.getInt(offset + 22) & 0xFFFFFFFFL;
    }

    public void setMtime(long mtime) {
        buffer.putInt(offset + 22, (int) (mtime & 0xFFFFFFFFL));
    }

    public int getMtimeNanoseconds() {
        if ((getFlags() & HAS_MTIME) == 0) {
            return 0;
        }
        return buffer.getInt(offset + 26);
    }

    public void setMtimeNanoseconds(int nanos) {
        buffer.putInt(offset + 26, nanos);
    }
}
