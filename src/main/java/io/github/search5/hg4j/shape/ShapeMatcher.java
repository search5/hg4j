package io.github.search5.hg4j.shape;

import io.github.search5.hg4j.match.Matcher;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The matcher of a shape ({@code ShapeMatcher} in {@code rust/hg-core/src/matchers.rs}): a file is selected when the deepest tree node that
 * is a directory prefix of it is included.
 */
public final class ShapeMatcher implements Matcher {

    /** What {@code visit_children_set} answers: {@code RECURSIVE}, {@code EMPTY}, or {@code SET} of the children worth visiting. */
    public record Visit(Kind kind, Set<String> children) {
        /** The kind of answer: {@code EMPTY} nothing under the directory needs visiting, {@code SET} only the listed children, {@code RECURSIVE} everything below. */
        public enum Kind { EMPTY, SET, RECURSIVE }
    }

    private static final Visit EMPTY = new Visit(Visit.Kind.EMPTY, Set.of());
    private static final Visit RECURSIVE = new Visit(Visit.Kind.RECURSIVE, Set.of());

    private final ShardTreeNode tree;

    /**
     * Creates a matcher over a tree.
     *
     * @param tree the root node of the shape's tree; must not be {@code null}
     */
    public ShapeMatcher(ShardTreeNode tree) {
        this.tree = tree;
    }

    @Override
    public boolean matches(String path) {
        if (path.isEmpty()) {
            return tree.included();
        }
        return tree.deepestPrefixNode(path).included();
    }

    @Override
    public boolean exact(String path) {
        return false;
    }

    @Override
    public List<String> files() {
        return List.of();
    }

    @Override
    public boolean always() {
        return tree.included() && tree.children().isEmpty();
    }

    /** {@code visit_children_set}: which entries of {@code directory} a walk has to look at. */
    public Visit visitChildrenSet(String directory) {
        ShardTreeNode node = tree.deepestPrefixNode(directory);
        boolean exactMatch = node.path().equals(directory);
        if (node.included()) {
            return RECURSIVE; // an exact or only a prefix match, the best node is included
        } else if (exactMatch) {
            if (node.children().isEmpty()) {
                return EMPTY;
            }
            return new Visit(Visit.Kind.SET, node.children().stream().map(ShardTreeNode::path).collect(Collectors.toCollection(java.util.LinkedHashSet::new)));
        }
        return EMPTY; // a prefix node that is excluded
    }

    @Override
    public boolean visitDir(String dir) {
        return visitChildrenSet(dir).kind() != Visit.Kind.EMPTY;
    }
}
