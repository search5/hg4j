package io.github.search5.hg4j.treewalk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class TreeWalk {

    private final List<TreeIterator> trees = new ArrayList<>();
    private String currentPath;
    private boolean first = true;
    private PathFilter filter = null;
    private boolean recursive = true;

    public void addTree(TreeIterator iterator) throws IOException {
        iterator.reset();
        trees.add(iterator);
    }

    public TreeWalk setFilter(PathFilter filter) {
        this.filter = filter;
        return this;
    }

    public TreeWalk setRecursive(boolean recursive) {
        this.recursive = recursive;
        return this;
    }

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
                    if (minPath == null || io.github.search5.hg4j.core.NodeIdUtil.UTF8_STRING_COMPARATOR.compare(path, minPath) < 0) {
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

    public String getPath() {
        return currentPath;
    }

    public boolean isTracked(int treeIndex) {
        if (treeIndex < 0 || treeIndex >= trees.size()) {
            return false;
        }
        TreeIterator tree = trees.get(treeIndex);
        return currentPath != null && currentPath.equals(tree.getEntryPath());
    }

    public byte[] getNodeId(int treeIndex) {
        if (isTracked(treeIndex)) {
            return trees.get(treeIndex).getEntryNodeId();
        }
        return null;
    }

    public boolean isExecutable(int treeIndex) {
        if (isTracked(treeIndex)) {
            return trees.get(treeIndex).isExecutable();
        }
        return false;
    }

    public boolean isSymlink(int treeIndex) {
        if (isTracked(treeIndex)) {
            TreeIterator tree = trees.get(treeIndex);
            if (tree instanceof ManifestTreeIterator) {
                return ((ManifestTreeIterator) tree).isSymlink();
            }
        }
        return false;
    }

    public char getState(int treeIndex) {
        if (isTracked(treeIndex)) {
            return trees.get(treeIndex).getEntryState();
        }
        return '?';
    }

    public void reset() throws IOException {
        currentPath = null;
        first = true;
        for (TreeIterator tree : trees) {
            tree.reset();
        }
    }
}
