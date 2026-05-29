package org.hg4j.treewalk;

import java.io.IOException;

/**
 * Common abstraction interface for iterating over different repository trees.
 */
public interface TreeIterator {

    /**
     * Returns the relative path of the current entry.
     */
    String getEntryPath();

    /**
     * Returns the node ID byte array of the current entry, or null if not applicable.
     */
    byte[] getEntryNodeId();

    /**
     * Returns whether the current entry is executable.
     */
    boolean isExecutable();

    /**
     * Returns the tracking state character of the current entry (e.g. 'n', 'a', 'r', '?').
     */
    char getEntryState();

    /**
     * Advances to the next entry in the tree.
     *
     * @return true if there is a next entry, false otherwise
     */
    boolean next() throws IOException;

    /**
     * Resets the iterator to the beginning.
     */
    void reset() throws IOException;
}
