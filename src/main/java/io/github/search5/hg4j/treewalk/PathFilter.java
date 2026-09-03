package io.github.search5.hg4j.treewalk;

/**
 * Filter for filtering paths during tree walks.
 */
public interface PathFilter {
    /**
     * Returns true if the path should be accepted.
     */
    boolean accept(String path);
}
