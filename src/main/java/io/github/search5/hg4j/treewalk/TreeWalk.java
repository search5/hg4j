package io.github.search5.hg4j.treewalk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import io.github.search5.hg4j.util.NodeIdUtil;

/**
 * Walks several trees (for example a manifest and the working directory) in lockstep, visiting the union
 * of their paths once each, in ascending UTF-8 path order. Every added tree must itself yield its entries in
 * that order.
 * <p>After {@link #next()} returns true, the per-tree accessors take the index of a tree in the order it was
 * added and report that tree's view of the current path. Instances are not thread-safe.
 */
public class TreeWalk {

    private final List<TreeIterator> trees = new ArrayList<>();
    private String currentPath;
    private boolean first = true;
    private PathFilter filter = null;
    private boolean recursive = true;

    /**
     * Adds a tree to the walk and resets it immediately. The tree's index is the number of trees added before it.
     * @param iterator the tree to add; must not be null
     * @throws IOException if resetting the iterator fails
     */
    public void addTree(TreeIterator iterator) throws IOException {
        iterator.reset();
        trees.add(iterator);
    }

    /**
     * Sets the filter applied to each candidate path; rejected paths are skipped.
     * @param filter the filter, or null for no filtering (the default)
     * @return this walk
     */
    public TreeWalk setFilter(PathFilter filter) {
        this.filter = filter;
        return this;
    }

    /**
     * Sets whether to descend into subdirectories. When false, a path is skipped if, relative to its nearest
     * ancestor directory accepted by the filter (or the root when there is none), it still contains a
     * {@code /}.
     * @param recursive true to report all paths (the default)
     * @return this walk
     */
    public TreeWalk setRecursive(boolean recursive) {
        this.recursive = recursive;
        return this;
    }

    /**
     * Advances to the next path present in at least one tree that passes the filter.
     * @return true if a path is now current, false when the walk is exhausted
     * @throws IOException if a tree fails to advance
     */
    public boolean next() throws IOException {
        while (true) {
            if (first) {
                first = false;
                for (TreeIterator tree : trees) {
                    tree.next();
                }
            } else {
                for (TreeIterator tree : trees) {
                    if (currentPath != null && currentPath.equals(tree.getEntryPath())) {
                        tree.next();
                    }
                }
            }

            String minPath = null;
            for (TreeIterator tree : trees) {
                String path = tree.getEntryPath();
                if (path != null) {
                    if (minPath == null || NodeIdUtil.UTF8_STRING_COMPARATOR.compare(path, minPath) < 0) {
                        minPath = path;
                    }
                }
            }

            if (minPath == null) {
                currentPath = null;
                return false;
            }

            currentPath = minPath;

            if (filter != null && !filter.accept(currentPath)) {
                continue;
            }

            if (!recursive) {
                // Find the base directory for this currentPath according to filter
                String baseDir = "";
                if (filter != null) {
                    int lastSlash = currentPath.lastIndexOf('/');
                    while (lastSlash != -1) {
                        String parent = currentPath.substring(0, lastSlash);
                        if (filter.accept(parent)) {
                            baseDir = parent;
                            break;
                        }
                        lastSlash = parent.lastIndexOf('/');
                    }
                }
                
                // Remainder of path relative to baseDir must not contain '/'
                String remainder = currentPath;
                if (!baseDir.isEmpty()) {
                    if (currentPath.startsWith(baseDir + "/")) {
                        remainder = currentPath.substring(baseDir.length() + 1);
                    }
                }
                if (remainder.contains("/")) {
                    continue;
                }
            }

            return true;
        }
    }

    /**
     * Returns the current path.
     * @return the repository-relative path, or null before the first {@link #next()} or after it returned false
     */
    public String getPath() {
        return currentPath;
    }

    /**
     * Returns whether the given tree contains the current path.
     * @param treeIndex index of the tree in the order added
     * @return true if the tree has the current path; false if it does not or the index is out of range
     */
    public boolean isTracked(int treeIndex) {
        if (treeIndex < 0 || treeIndex >= trees.size()) {
            return false;
        }
        TreeIterator tree = trees.get(treeIndex);
        return currentPath != null && currentPath.equals(tree.getEntryPath());
    }

    /**
     * Returns the node hash of the current path in the given tree.
     * @param treeIndex index of the tree in the order added
     * @return the node hash, or null if the tree does not contain the path, the index is out of range, or the
     *         tree carries no node hashes (as with the working directory)
     */
    public byte[] getNodeId(int treeIndex) {
        if (isTracked(treeIndex)) {
            return trees.get(treeIndex).getEntryNodeId();
        }
        return null;
    }

    /**
     * Returns whether the current path is executable in the given tree.
     * @param treeIndex index of the tree in the order added
     * @return true if the tree contains the path and marks it executable; false otherwise
     */
    public boolean isExecutable(int treeIndex) {
        if (isTracked(treeIndex)) {
            return trees.get(treeIndex).isExecutable();
        }
        return false;
    }

    /**
     * Returns whether the current path is a symbolic link in the given tree. Only manifest trees report
     * symlinks; any other tree type yields false.
     * @param treeIndex index of the tree in the order added
     * @return true if the tree is a {@link ManifestTreeIterator} containing the path as a symlink
     */
    public boolean isSymlink(int treeIndex) {
        if (isTracked(treeIndex)) {
            TreeIterator tree = trees.get(treeIndex);
            if (tree instanceof ManifestTreeIterator) {
                return ((ManifestTreeIterator) tree).isSymlink();
            }
        }
        return false;
    }

    /**
     * Returns the tracking state of the current path in the given tree.
     * @param treeIndex index of the tree in the order added
     * @return the state character reported by the tree (for example {@code 'n'} for a manifest entry), or
     *         {@code '?'} if the tree does not contain the path or the index is out of range
     */
    public char getState(int treeIndex) {
        if (isTracked(treeIndex)) {
            return trees.get(treeIndex).getEntryState();
        }
        return '?';
    }

    /**
     * Resets every added tree and rewinds the walk so that {@link #next()} starts from the beginning again.
     * @throws IOException if resetting a tree fails
     */
    public void reset() throws IOException {
        currentPath = null;
        first = true;
        for (TreeIterator tree : trees) {
            tree.reset();
        }
    }
}
