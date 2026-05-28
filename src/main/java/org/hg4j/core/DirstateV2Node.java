package org.hg4j.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Represents a single fixed-size (32 bytes) node layout in Mercurial dirstate-v2 format.
 * Utilizes a ByteBuffer backing view for high-performance off-heap/on-heap mapping.
 */
public class DirstateV2Node {
    public static final int NODE_SIZE = 32;

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
        return (char) buffer.get(offset + 0);
    }

    public void setState(char state) {
        buffer.put(offset + 0, (byte) state);
    }

    public byte getFlags() {
        return buffer.get(offset + 1);
    }

    public void setFlags(byte flags) {
        buffer.put(offset + 1, flags);
    }

    public int getMode() {
        return buffer.getInt(offset + 4);
    }

    public void setMode(int mode) {
        buffer.putInt(offset + 4, mode);
    }

    public int getSize() {
        return buffer.getInt(offset + 8);
    }

    public void setSize(int size) {
        buffer.putInt(offset + 8, size);
    }

    public long getMtime() {
        // Parse unsigned 32-bit int safely to long
        return buffer.getInt(offset + 12) & 0xFFFFFFFFL;
    }

    public void setMtime(long mtime) {
        buffer.putInt(offset + 12, (int) (mtime & 0xFFFFFFFFL));
    }

    public int getPathOffset() {
        return buffer.getInt(offset + 16);
    }

    public void setPathOffset(int pathOffset) {
        buffer.putInt(offset + 16, pathOffset);
    }

    public short getPathLen() {
        return buffer.getShort(offset + 20);
    }

    public void setPathLen(short pathLen) {
        buffer.putShort(offset + 20, pathLen);
    }

    public int getChildrenStart() {
        return buffer.getInt(offset + 24);
    }

    public void setChildrenStart(int childrenStart) {
        buffer.putInt(offset + 24, childrenStart);
    }

    public int getChildrenCount() {
        return buffer.getInt(offset + 28);
    }

    public void setChildrenCount(int childrenCount) {
        buffer.putInt(offset + 28, childrenCount);
    }
}
