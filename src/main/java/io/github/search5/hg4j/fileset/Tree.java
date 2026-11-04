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

    public Tree(String op, Object... args) {
        this.op = op;
        this.args = Arrays.asList(args);
    }

    public Tree(String op, List<Object> args) {
        this.op = op;
        this.args = args;
    }

    public String op() {
        return op;
    }

    public List<Object> args() {
        return args;
    }

    public Tree tree(int index) {
        return (Tree) args.get(index);
    }

    public String text(int index) {
        return (String) args.get(index);
    }

    @Override
    public String toString() {
        return FilesetLanguage.prettyFormat(this);
    }
}
