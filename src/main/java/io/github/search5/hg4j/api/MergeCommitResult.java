package io.github.search5.hg4j.api;

import io.github.search5.hg4j.util.NodeIdUtil;

import java.util.List;

/**
 * The outcome of a {@link MergeCommitCommand}: either the new merge changeset or the list of files that could not be merged.
 * A conflict is a normal outcome and is not thrown; nothing has been written to the repository when {@link #isConflicted()} is true.
 */
public final class MergeCommitResult {
    private final byte[] node;
    private final List<String> conflicts;

    private MergeCommitResult(byte[] node, List<String> conflicts) {
        this.node = node;
        this.conflicts = conflicts;
    }

    static MergeCommitResult merged(byte[] node) {
        return new MergeCommitResult(node, List.of());
    }

    static MergeCommitResult conflicted(List<String> conflicts) {
        return new MergeCommitResult(null, List.copyOf(conflicts));
    }

    /**
     * Tells whether the two sides could not be merged.
     * @return {@code true} if {@link #getConflicts()} is not empty and no changeset was created
     */
    public boolean isConflicted() {
        return node == null;
    }

    /**
     * Returns the paths that could not be merged: files both sides changed in overlapping ways, and files one side deleted and the other
     * changed. Sorted by path; empty if the merge succeeded.
     * @return an unmodifiable list of repository-relative paths
     */
    public List<String> getConflicts() {
        return conflicts;
    }

    /**
     * Returns the node of the new merge changeset.
     * @return the 20-byte node id, or {@code null} if the merge is conflicted
     */
    public byte[] getNode() {
        return node == null ? null : node.clone();
    }

    /**
     * Returns the node of the new merge changeset as hex.
     * @return the 40-character lowercase hex node id, or {@code null} if the merge is conflicted
     */
    public String getNodeHex() {
        return node == null ? null : NodeIdUtil.toHex(node);
    }
}
