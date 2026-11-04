package io.github.search5.hg4j.fileset;

import java.util.Arrays;
import java.util.List;

/**
 * A node of the parse tree: an operation name and its operands, which are {@link Tree}s, {@link String}s (the text of
 * {@code symbol} and {@code string} nodes) or {@code null}. Mirrors the tuples of Mercurial's {@code filesetlang}.
 */
public final class Tree {

    private final String op;
    private final List<Object> args;

    /**
     * Creates a node.
     *
     * @param op the operation name
     * @param args the operands: {@link Tree}s, {@link String}s or {@code null}
     */
    public Tree(String op, Object... args) {
        this.op = op;
        this.args = Arrays.asList(args);
    }

    /**
     * Creates a node from an operand list. The list is used as given, not copied.
     *
     * @param op the operation name
     * @param args the operands: {@link Tree}s, {@link String}s or {@code null}
     */
    public Tree(String op, List<Object> args) {
        this.op = op;
        this.args = args;
    }

    /**
     * Returns the operation name.
     *
     * @return the name, for example {@code symbol} or {@code func}
     */
    public String op() {
        return op;
    }

    /**
     * Returns the operands.
     *
     * @return the operand list as stored in the node
     */
    public List<Object> args() {
        return args;
    }

    /**
     * Returns an operand as a subtree.
     *
     * @param index zero-based operand position
     * @return the operand
     * @throws ClassCastException if the operand is not a {@link Tree} (a {@code null} operand is returned as {@code null})
     * @throws IndexOutOfBoundsException if there is no such operand
     */
    public Tree tree(int index) {
        return (Tree) args.get(index);
    }

    /**
     * Returns an operand as text.
     *
     * @param index zero-based operand position
     * @return the operand
     * @throws ClassCastException if the operand is not a {@link String}
     * @throws IndexOutOfBoundsException if there is no such operand
     */
    public String text(int index) {
        return (String) args.get(index);
    }

    @Override
    public String toString() {
        return FilesetLanguage.prettyFormat(this);
    }
}
