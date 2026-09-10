package io.github.search5.hg4j.treewalk;

import java.io.IOException;

/**
 * Common abstraction interface for iterating over different repository trees.
 */
public interface TreeIterator {

    /**
     * Returns the relative path of the current entry.
     *
     * @return the current entry's repository-relative path, or {@code null} if the iterator is
     *     exhausted or not yet positioned
     */
    String getEntryPath();

    /**
     * Returns the node ID byte array of the current entry, or null if not applicable.
     *
     * @return the current entry's raw node ID, or {@code null} if not applicable
     */
    byte[] getEntryNodeId();

    /**
     * Returns whether the current entry is executable.
     *
     * @return {@code true} if the current entry is executable
     */
    boolean isExecutable();

    /**
     * Returns the tracking state character of the current entry (e.g. 'n', 'a', 'r', '?').
     *
     * @return the current entry's tracking state character
     */
    char getEntryState();

    /**
     * Advances to the next entry in the tree.
     *
     * @return true if there is a next entry, false otherwise
     * @throws IOException if advancing requires reading store data and that read fails
     */
    boolean next() throws IOException;

    /**
     * Resets the iterator to the beginning.
     *
     * @throws IOException if re-reading the underlying tree's data fails
     */
    void reset() throws IOException;
}
