package io.github.search5.hg4j.shape;

import io.github.search5.hg4j.errors.HgValidationException;

import java.util.List;
import java.util.Set;

/**
 * A named narrow view into the repository's files ({@code narrow/shape.rs Shape}): the user facing concept. {@link #names()} has several
 * entries for a composite shape.
 */
public final class Shape {

    private final List<String> names;
    private final ShardTreeNode tree;

    Shape(List<String> names, ShardTreeNode tree) {
        this.names = List.copyOf(names);
        this.tree = tree;
    }

    /**
     * {@code Shape::from_patterns}: a shape that is not from the store, named {@code hg-internal}, from old style narrow patterns (already
     * stripped of {@code path:}).
     */
    public static Shape fromPatterns(Set<String> includes, Set<String> excludes) throws HgValidationException {
        return new Shape(List.of("hg-internal"), ShardTreeNode.fromPatterns(includes, excludes));
    }

    /** The shape's own name, or the names of the shapes a composite one is made of. */
    public List<String> names() {
        return names;
    }

    public ShardTreeNode tree() {
        return tree;
    }

    public ShapeMatcher matcher() {
        return new ShapeMatcher(tree);
    }

    public Fingerprint storeFingerprint() throws HgValidationException {
        return tree.fingerprint();
    }

    /** The patterns block of this shape. */
    public byte[] serializePatterns() throws HgValidationException {
        return tree.serializePatterns();
    }

    public ShardTreeNode.Patterns patterns() {
        return tree.flat();
    }
}
