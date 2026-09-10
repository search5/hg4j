package io.github.search5.hg4j.treewalk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import io.github.search5.hg4j.util.NodeIdUtil;

/**
 * Simultaneously walks multiple {@link TreeIterator}s (e.g. two manifests, or a manifest and the
 * working directory) in merged, sorted path order — modeled after JGit's own {@code TreeWalk}.
 *
 * @apiNote The shared multi-tree comparison engine behind {@code StatusCommand}, {@code
 *     DiffCommand}, {@code CommitCommand}, {@code UpdateCommand}, {@code MergeCommand}, and
 *     {@code MergeCommitCommand}. Add each tree to compare with {@link #addTree}, then call
 *     {@link #next()} in a loop; at each step, use {@link #isTracked}/{@link #getNodeId}/{@link
 *     #getState} (indexed by the order trees were added) to inspect each tree's state at the
 *     current path — a tree not tracking the current path simply reports "not tracked" rather
 *     than throwing.
 */
public class TreeWalk {

    private final List<TreeIterator> trees = new ArrayList<>();
    private String currentPath;
    private boolean first = true;
    private PathFilter filter = null;
    private boolean recursive = true;

    /** Creates an empty tree walk; add trees to compare via {@link #addTree}. */
    public TreeWalk() {
    }

    /**
     * Adds a tree to be walked alongside any trees already added, resetting it to its initial
     * position first. Trees are indexed in the order they are added -- that index is what
     * {@link #isTracked}/{@link #getNodeId}/{@link #getState} and friends take as {@code treeIndex}.
     *
     * @param iterator the tree to add to this walk
     * @throws IOException if resetting the iterator fails
     */
    public void addTree(TreeIterator iterator) throws IOException {
        iterator.reset();
        trees.add(iterator);
    }

    /**
     * Restricts the walk to paths accepted by {@code filter}.
     *
     * @param filter the path filter to apply, or {@code null} to walk every path
     * @return this walk, for chaining
     */
    public TreeWalk setFilter(PathFilter filter) {
        this.filter = filter;
        return this;
    }

    /**
     * Sets whether the walk descends into subdirectories (the default) or stops at
     * the last directory accepted by the current {@link #setFilter filter}.
     *
     * @param recursive {@code false} to only report entries directly inside the filter's base directory
     * @return this walk, for chaining
     */
    public TreeWalk setRecursive(boolean recursive) {
        this.recursive = recursive;
        return this;
    }

    /**
     * Advances the walk to the next path (in merged, sorted order across every added tree) that
     * passes the current filter/recursive settings.
     *
     * @return {@code true} if a next path was found and is now current, {@code false} once every
     *     tree is exhausted
     * @throws IOException if advancing any underlying tree iterator fails
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
     * The current path, shared across every added tree at this step of the walk.
     *
     * @return the current path, or {@code null} before the first {@link #next()} call or after it returns {@code false}
     */
    public String getPath() {
        return currentPath;
    }

    /**
     * Whether the tree at {@code treeIndex} tracks the current path.
     *
     * @param treeIndex the index (in add order) of the tree to query
     * @return {@code true} if that tree's current entry is exactly the current path
     */
    public boolean isTracked(int treeIndex) {
        if (treeIndex < 0 || treeIndex >= trees.size()) {
            return false;
        }
        TreeIterator tree = trees.get(treeIndex);
        return currentPath != null && currentPath.equals(tree.getEntryPath());
    }

    /**
     * The node ID the tree at {@code treeIndex} records for the current path.
     *
     * @param treeIndex the index (in add order) of the tree to query
     * @return the raw node ID bytes, or {@code null} if that tree does not track the current path
     */
    public byte[] getNodeId(int treeIndex) {
        if (isTracked(treeIndex)) {
            return trees.get(treeIndex).getEntryNodeId();
        }
        return null;
    }

    /**
     * Whether the tree at {@code treeIndex} records the current path as executable.
     *
     * @param treeIndex the index (in add order) of the tree to query
     * @return {@code true} if that tree tracks the current path and flags it executable
     */
    public boolean isExecutable(int treeIndex) {
        if (isTracked(treeIndex)) {
            return trees.get(treeIndex).isExecutable();
        }
        return false;
    }

    /**
     * Whether the tree at {@code treeIndex} records the current path as a symlink. Only
     * {@link ManifestTreeIterator}-backed trees can report this; any other tree type reports
     * {@code false}.
     *
     * @param treeIndex the index (in add order) of the tree to query
     * @return {@code true} if that tree tracks the current path and flags it as a symlink
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
     * The dirstate-style state character the tree at {@code treeIndex} records for the current path.
     *
     * @param treeIndex the index (in add order) of the tree to query
     * @return the entry's state character (e.g. {@code 'n'}/{@code 'a'}/{@code 'r'}/{@code 'm'}),
     *     or {@code '?'} if that tree does not track the current path
     */
    public char getState(int treeIndex) {
        if (isTracked(treeIndex)) {
            return trees.get(treeIndex).getEntryState();
        }
        return '?';
    }

    /**
     * Resets the walk (and every added tree) back to its initial, pre-first-{@link #next()} state.
     *
     * @throws IOException if resetting any underlying tree iterator fails
     */
    public void reset() throws IOException {
        currentPath = null;
        first = true;
        for (TreeIterator tree : trees) {
            tree.reset();
        }
    }
}
