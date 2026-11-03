package io.github.search5.hg4j.lfs;

import io.github.search5.hg4j.errors.HgValidationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The subset of the fileset language real hg's {@code lfs.track} and {@code .hglfs} understand ({@code mercurial/minifileset.py}):
 * the patterns {@code **.ext} and {@code path:dir}, the functions {@code all()}, {@code none()} and {@code size("<expr>")}, and the
 * operators {@code and}/{@code &}, {@code or}/{@code |}/{@code +}, {@code not}/{@code !}, {@code -} (and not) and parentheses.
 * An expression compiles to a {@link Matcher} over a repository-relative path and a file size.
 *
 * <p>Operator binding strengths are those of {@code filesetlang.elements}: {@code (} 20, {@code :} 15, {@code not} 10,
 * {@code and} and {@code -} 5, {@code or} 4, {@code ,} 2.
 */
public final class MiniFileset {

    /** A compiled expression: does a file of this path and size match? */
    @FunctionalInterface
    public interface Matcher {
        boolean matches(String path, long size);
    }

    private MiniFileset() {
    }

    public static Matcher compile(String text) throws HgValidationException {
        return compile(text, null);
    }

    /**
     * Same, with the {@code lfs()} predicate of real hg's lfs extension ("files stored in LFS") answered by {@code lfsPaths};
     * without it {@code lfs()} is an unknown identifier, as it is in {@code lfs.track}.
     */
    public static Matcher compile(String text, java.util.function.Predicate<String> lfsPaths) throws HgValidationException {
        Parser parser = new Parser(tokenize(text), text);
        Node tree = parser.parseExpression(0);
        parser.expectEnd();
        return compileNode(tree, lfsPaths);
    }

    /** Parses only (so a syntax error is reported early); used to validate {@code lfs.track} before it is combined with a size. */
    public static void check(String text) throws HgValidationException {
        Parser parser = new Parser(tokenize(text), text);
        parser.parseExpression(0);
        parser.expectEnd();
    }

    // ---------------------------------------------------------------- sizes (mercurial.util.sizetoint and fileset.sizematcher)

    private static final String[] UNIT_NAMES = {"m", "k", "g", "kb", "mb", "gb", "b"};
    private static final long[] UNIT_VALUES = {1L << 20, 1L << 10, 1L << 30, 1L << 10, 1L << 20, 1L << 30, 1};

    static long sizeToInt(String spec) throws HgValidationException {
        String t = spec.strip().toLowerCase(Locale.ROOT);
        try {
            for (int i = 0; i < UNIT_NAMES.length; i++) {
                if (t.endsWith(UNIT_NAMES[i])) {
                    return (long) (parseFloat(t.substring(0, t.length() - UNIT_NAMES[i].length())) * UNIT_VALUES[i]);
                }
            }
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            throw new HgValidationException("couldn't parse size: " + spec);
        }
    }

    private static long sizeToMax(String spec) throws HgValidationException {
        String s = spec.strip().toLowerCase(Locale.ROOT);
        try {
            for (int i = 0; i < UNIT_NAMES.length; i++) {
                if (s.endsWith(UNIT_NAMES[i])) {
                    // max(4k) = 5k - 1, max(4.5k) = 4.6k - 1
                    String n = s.substring(0, s.length() - UNIT_NAMES[i].length());
                    double inc = 1.0;
                    int dot = n.indexOf('.');
                    if (dot >= 0) {
                        inc /= Math.pow(10, n.length() - dot - 1);
                    }
                    return (long) ((parseFloat(n) + inc) * UNIT_VALUES[i]) - 1;
                }
            }
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new HgValidationException("couldn't parse size: " + s);
        }
    }

    private static double parseFloat(String number) {
        double value = Double.parseDouble(number.strip());
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new NumberFormatException(number);
        }
        return value;
    }

    private static java.util.function.LongPredicate sizeMatcher(String expression) throws HgValidationException {
        String expr = expression.strip();
        if (expr.contains("-")) { // a range
            int dash = expr.indexOf('-');
            long a = sizeToInt(expr.substring(0, dash));
            long b = sizeToInt(expr.substring(dash + 1));
            return x -> x >= a && x <= b;
        } else if (expr.startsWith("<=")) {
            long a = sizeToInt(expr.substring(2));
            return x -> x <= a;
        } else if (expr.startsWith("<")) {
            long a = sizeToInt(expr.substring(1));
            return x -> x < a;
        } else if (expr.startsWith(">=")) {
            long a = sizeToInt(expr.substring(2));
            return x -> x >= a;
        } else if (expr.startsWith(">")) {
            long a = sizeToInt(expr.substring(1));
            return x -> x > a;
        }
        long a = sizeToInt(expr);
        long b = sizeToMax(expr);
        return x -> x >= a && x <= b;
    }

    // ---------------------------------------------------------------- tokens

    private record Token(String type, String value, int pos) {
    }

    private static final String GLOB_CHARS = ".*{}[]?/\\_";

    private static List<Token> tokenize(String program) throws HgValidationException {
        List<Token> tokens = new ArrayList<>();
        int pos = 0;
        int l = program.length();
        while (pos < l) {
            char c = program.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if ("(),-:|&+!".indexOf(c) >= 0) {
                tokens.add(new Token(String.valueOf(c), null, pos));
                pos++;
            } else if (c == '"' || c == '\'' || (c == 'r' && pos + 1 < l && (program.charAt(pos + 1) == '"' || program.charAt(pos + 1) == '\''))) {
                boolean raw = c == 'r';
                if (raw) {
                    pos++;
                    c = program.charAt(pos);
                }
                pos++;
                int start = pos;
                StringBuilder value = new StringBuilder();
                boolean closed = false;
                while (pos < l) {
                    char d = program.charAt(pos);
                    if (d == '\\') {
                        if (pos + 1 < l) {
                            value.append(raw ? "\\" + program.charAt(pos + 1) : unescape(program.charAt(pos + 1)));
                        }
                        pos += 2;
                        continue;
                    }
                    if (d == c) {
                        closed = true;
                        break;
                    }
                    value.append(d);
                    pos++;
                }
                if (!closed) {
                    throw new HgValidationException("unterminated string at " + start);
                }
                tokens.add(new Token("string", value.toString(), start));
                pos++;
            } else if (Character.isLetterOrDigit(c) || GLOB_CHARS.indexOf(c) >= 0 || c > 127) {
                int start = pos;
                pos++;
                while (pos < l) {
                    char d = program.charAt(pos);
                    if (!(Character.isLetterOrDigit(d) || GLOB_CHARS.indexOf(d) >= 0 || d > 127)) {
                        break;
                    }
                    pos++;
                }
                String symbol = program.substring(start, pos);
                if (symbol.equals("and") || symbol.equals("or") || symbol.equals("not")) {
                    tokens.add(new Token(symbol, null, start));
                } else {
                    tokens.add(new Token("symbol", symbol, start));
                }
            } else {
                throw new HgValidationException("syntax error at " + pos);
            }
        }
        tokens.add(new Token("end", null, pos));
        return tokens;
    }

    private static String unescape(char c) {
        return switch (c) {
            case 'n' -> "\n";
            case 't' -> "\t";
            case 'r' -> "\r";
            case '\\' -> "\\";
            case '\'' -> "'";
            case '"' -> "\"";
            default -> "\\" + c;
        };
    }

    // ---------------------------------------------------------------- parser (precedence climbing over filesetlang.elements)

    private sealed interface Node permits Leaf, Unary, Binary, Call, Group {
    }

    private record Leaf(String kind, String value) implements Node {
    }

    private record Unary(String op, Node operand) implements Node {
    }

    private record Binary(String op, Node left, Node right) implements Node {
    }

    private record Call(Node function, Node argument) implements Node {
    }

    private record Group(Node inner) implements Node {
    }

    private static final class Parser {
        private final List<Token> tokens;
        private final String text;
        private int index;

        Parser(List<Token> tokens, String text) {
            this.tokens = tokens;
            this.text = text;
        }

        private Token peek() {
            return tokens.get(index);
        }

        void expectEnd() throws HgValidationException {
            if (!peek().type().equals("end")) {
                throw new HgValidationException("invalid token at " + peek().pos());
            }
        }

        private static int bindingStrength(String type) {
            return switch (type) {
                case "(" -> 20;
                case ":" -> 15;
                case "-", "and", "&" -> 5;
                case "or", "|", "+" -> 4;
                case "," -> 2;
                default -> 0;
            };
        }

        Node parseExpression(int minBinding) throws HgValidationException {
            Token token = tokens.get(index++);
            Node left = switch (token.type()) {
                case "symbol", "string" -> new Leaf(token.type(), token.value());
                case "(" -> {
                    Node inner = parseExpression(0);
                    expect(")");
                    yield new Group(inner);
                }
                case "not", "!" -> new Unary("not", parseExpression(10));
                case "-" -> new Unary("negate", parseExpression(19));
                default -> throw new HgValidationException("syntax error at " + token.pos());
            };
            while (true) {
                Token next = peek();
                int strength = bindingStrength(next.type());
                if (strength <= minBinding) {
                    return left;
                }
                index++;
                switch (next.type()) {
                    case "(" -> {
                        Node argument = peek().type().equals(")") ? null : parseExpression(0);
                        expect(")");
                        left = new Call(left, argument);
                    }
                    case ":" -> left = new Binary("kindpat", left, parseExpression(15));
                    case "-" -> left = new Binary("minus", left, parseExpression(5));
                    case "and", "&" -> left = new Binary("and", left, parseExpression(5));
                    case "or", "|", "+" -> left = new Binary("or", left, parseExpression(4));
                    case "," -> left = new Binary("list", left, parseExpression(2));
                    default -> throw new HgValidationException("syntax error at " + next.pos());
                }
            }
        }

        private void expect(String type) throws HgValidationException {
            Token token = tokens.get(index);
            if (!token.type().equals(type)) {
                throw new HgValidationException("unexpected token at " + token.pos() + ", expected " + type);
            }
            index++;
        }
    }

    // ---------------------------------------------------------------- compile (minifileset._compile)

    private static Matcher compileNode(Node node, java.util.function.Predicate<String> lfsPaths) throws HgValidationException {
        if (node instanceof Group group) {
            return compileNode(group.inner(), lfsPaths);
        }
        if (node instanceof Leaf || (node instanceof Binary b && b.op().equals("kindpat"))) {
            return compilePattern(patternName(node));
        }
        if (node instanceof Binary binary) {
            switch (binary.op()) {
                case "or" -> {
                    Matcher left = compileNode(binary.left(), lfsPaths);
                    Matcher right = compileNode(binary.right(), lfsPaths);
                    return (p, s) -> left.matches(p, s) || right.matches(p, s);
                }
                case "and" -> {
                    Matcher left = compileNode(binary.left(), lfsPaths);
                    Matcher right = compileNode(binary.right(), lfsPaths);
                    return (p, s) -> left.matches(p, s) && right.matches(p, s);
                }
                case "minus" -> { // x and not y
                    Matcher left = compileNode(binary.left(), lfsPaths);
                    Matcher right = compileNode(binary.right(), lfsPaths);
                    return (p, s) -> left.matches(p, s) && !right.matches(p, s);
                }
                case "list" -> throw new HgValidationException("can't use a list in this context");
                default -> throw new HgValidationException("illegal tree: " + binary.op());
            }
        }
        if (node instanceof Unary unary) {
            if (unary.op().equals("not")) {
                Matcher operand = compileNode(unary.operand(), lfsPaths);
                return (p, s) -> !operand.matches(p, s);
            }
            throw new HgValidationException("illegal tree: " + unary.op());
        }
        Call call = (Call) node;
        if (!(call.function() instanceof Leaf leaf) || !leaf.kind().equals("symbol")) {
            throw new HgValidationException("not a symbol");
        }
        switch (leaf.value()) {
            case "all" -> {
                return (p, s) -> true;
            }
            case "none" -> {
                return (p, s) -> false;
            }
            case "lfs" -> {
                if (lfsPaths == null) {
                    throw new HgValidationException("unknown identifier: lfs");
                }
                return (p, s) -> lfsPaths.test(p);
            }
            case "size" -> {
                if (!(call.argument() instanceof Leaf arg) || (!arg.kind().equals("string") && !arg.kind().equals("symbol"))) {
                    throw new HgValidationException("size requires an expression");
                }
                java.util.function.LongPredicate size = sizeMatcher(arg.value());
                return (p, s) -> size.test(s);
            }
            default -> throw new HgValidationException("unknown identifier: " + leaf.value());
        }
    }

    /** {@code filesetlang.getpattern(tree, {'path'}, 'invalid file pattern')}: the text of a plain pattern or a {@code path:} one. */
    private static String patternName(Node node) throws HgValidationException {
        if (node instanceof Leaf leaf) {
            return leaf.value();
        }
        Binary kindpat = (Binary) node;
        if (!(kindpat.left() instanceof Leaf kind) || !(kindpat.right() instanceof Leaf pattern)) {
            throw new HgValidationException("invalid file pattern");
        }
        if (!kind.value().equals("path")) {
            throw new HgValidationException("invalid file pattern");
        }
        return kind.value() + ":" + pattern.value();
    }

    private static Matcher compilePattern(String name) throws HgValidationException {
        if (name.startsWith("**")) { // file extension test, ex. "**.tar.gz"
            String ext = name.substring(2);
            for (char c : ext.toCharArray()) {
                if ("*{}[]?/\\".indexOf(c) >= 0) {
                    throw new HgValidationException("reserved character: " + c);
                }
            }
            return (n, s) -> n.endsWith(ext);
        } else if (name.startsWith("path:")) { // directory or full path test
            String prefix = name.substring(5);
            int length = prefix.length();
            return (n, s) -> n.startsWith(prefix) && (n.length() == length || n.charAt(length) == '/');
        }
        throw new HgValidationException("unsupported file pattern: " + name + "\n(paths must be prefixed with \"path:\")");
    }
}
