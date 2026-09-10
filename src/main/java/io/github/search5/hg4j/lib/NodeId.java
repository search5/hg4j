package io.github.search5.hg4j.lib;

import java.util.Arrays;

/**
 * Immutable value object representing Mercurial's 20-byte node identifier (a SHA-1 hash).
 *
 * @apiNote A type-safe alternative to passing raw {@code byte[]}/hex-string node IDs around,
 *     giving {@link #equals}, {@link #hashCode}, and a natural {@link #compareTo} ordering for
 *     free. Most of hg4j's internals (revlog, dirstate, transport) still pass node IDs as raw
 *     {@code byte[]} or hex {@code String} for performance and wire-format reasons — see {@link
 *     io.github.search5.hg4j.util.NodeIdUtil} for the corresponding {@code byte[]}/hex helpers —
 *     but code that wants to use a node ID as, e.g., a {@code Map} key or a value that
 *     round-trips through {@code equals()}/{@code hashCode()} should prefer this type.
 */
public final class NodeId implements Comparable<NodeId> {
    /** The all-zero node ID Mercurial uses to mean "no such revision" (its {@code nullid}). */
    public static final NodeId NULL = new NodeId(new byte[20]);
    private final byte[] bytes;

    /**
     * Creates a node ID wrapping the given raw bytes.
     *
     * @param bytes the raw 20-byte node ID; copied defensively, so later mutation of the array
     *              by the caller has no effect on this instance
     * @throws IllegalArgumentException if {@code bytes} is {@code null} or not exactly 20 bytes
     */
    public NodeId(byte[] bytes) {
        if (bytes == null || bytes.length != 20) {
            throw new IllegalArgumentException("NodeId must be exactly 20 bytes");
        }
        this.bytes = Arrays.copyOf(bytes, 20);
    }

    /**
     * Parses a 40-character hexadecimal node ID string, as found e.g. in {@code hg log}
     * output or user-supplied revision arguments.
     *
     * @param hex the 40-character hexadecimal node ID string to parse
     * @return the parsed node ID
     * @throws IllegalArgumentException if {@code hex} is {@code null}, not exactly 40
     *         characters, or contains a non-hexadecimal character
     */
    public static NodeId fromHex(String hex) {
        if (hex == null || hex.length() != 40) {
            throw new IllegalArgumentException("Hex string must be exactly 40 characters");
        }
        byte[] raw = new byte[20];
        for (int i = 0; i < 20; i++) {
            String byteHex = hex.substring(i * 2, i * 2 + 2);
            try {
                int val = Integer.parseInt(byteHex, 16);
                raw[i] = (byte) val;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid hex character sequence: " + byteHex, e);
            }
        }
        return new NodeId(raw);
    }

    /**
     * Returns a defensive copy of the raw 20-byte node ID.
     *
     * @return a fresh 20-byte array containing this node ID's bytes
     */
    public byte[] getBytes() {
        return Arrays.copyOf(bytes, 20);
    }

    /**
     * Renders the node ID as a 40-character lowercase hexadecimal string.
     *
     * @return the full 40-character hex representation of this node ID
     */
    public String toHex() {
        StringBuilder sb = new StringBuilder(40);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Whether this is the all-zero {@link #NULL} node ID.
     *
     * @return {@code true} if this node ID equals {@link #NULL}
     */
    public boolean isNull() {
        return Arrays.equals(this.bytes, NULL.bytes);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NodeId nodeId = (NodeId) o;
        return Arrays.equals(bytes, nodeId.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public int compareTo(NodeId o) {
        return Arrays.compare(this.bytes, o.bytes);
    }

    @Override
    public String toString() {
        return toHex().substring(0, 12);
    }
}
