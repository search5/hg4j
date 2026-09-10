package io.github.search5.hg4j.treewalk;

/**
 * Filter for filtering paths during tree walks.
 */
public interface PathFilter {
    /**
     * Returns true if the path should be accepted.
     *
     * @param path the repository-relative path to test
     * @return {@code true} if the path should be accepted, {@code false} if it should be filtered out
     */
    boolean accept(String path);
}
