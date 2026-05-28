package org.hg4j.lib;

import java.util.Arrays;

/**
 * Mercurial의 20바이트 노드 식별자(SHA-1 해시)를 표현하는 불변 값 객체(Value Object).
 */
public final class NodeId implements Comparable<NodeId> {
    public static final NodeId NULL = new NodeId(new byte[20]);
    private final byte[] bytes;

    public NodeId(byte[] bytes) {
        if (bytes == null || bytes.length != 20) {
            throw new IllegalArgumentException("NodeId must be exactly 20 bytes");
        }
        this.bytes = Arrays.copyOf(bytes, 20);
    }

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

    public byte[] getBytes() {
        return Arrays.copyOf(bytes, 20);
    }

    public String toHex() {
        StringBuilder sb = new StringBuilder(40);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

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
