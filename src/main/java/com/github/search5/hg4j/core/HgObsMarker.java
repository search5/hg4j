package com.github.search5.hg4j.core;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Represents a single Obsolescence Marker (Evolve mechanism).
 * Marks a predecessor revision as "obsolete" and maps it to optional successor revisions.
 */
public final class HgObsMarker {
    private final byte[] predecessor;
    private final List<byte[]> successors;
    private final int flags;
    private final Map<String, String> metadata;

    public HgObsMarker(byte[] predecessor, List<byte[]> successors, int flags, Map<String, String> metadata) {
        if (predecessor == null || predecessor.length != 20) {
            throw new IllegalArgumentException("Predecessor node must be exactly 20 bytes");
        }
        this.predecessor = predecessor.clone();
        this.successors = successors != null ? successors : List.of();
        this.flags = flags;
        this.metadata = metadata != null ? metadata : Map.of();
    }

    public byte[] getPredecessor() {
        return predecessor.clone();
    }

    public List<byte[]> getSuccessors() {
        return successors.stream().map(byte[]::clone).toList();
    }

    public int getFlags() {
        return flags;
    }

    public Map<String, String> getMetadata() {
        return Map.copyOf(metadata);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HgObsMarker that = (HgObsMarker) o;
        return flags == that.flags &&
                Arrays.equals(predecessor, that.predecessor) &&
                successorsEqual(this.successors, that.successors) &&
                metadata.equals(that.metadata);
    }

    private static boolean successorsEqual(List<byte[]> a, List<byte[]> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!Arrays.equals(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(predecessor);
        result = 31 * result + successors.size();
        result = 31 * result + flags;
        result = 31 * result + metadata.hashCode();
        return result;
    }
}
