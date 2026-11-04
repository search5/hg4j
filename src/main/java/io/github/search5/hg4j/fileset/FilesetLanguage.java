package io.github.search5.hg4j.fileset;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The fileset language: Mercurial's {@code filesetlang.py} (tokenizer, parser, {@code analyze}, {@code optimize},
 * {@code prettyformat}). The stages produce exactly the trees {@code hg debugfileset --show-stage} prints.
 */
public final class FilesetLanguage {

    /** The weights {@code filesetlang} gives predicates for static optimization. */
    public static final double WEIGHT_CHECK_FILENAME = 0.5;
    public static final double WEIGHT_READ_CONTENTS = 30;
    public static final double WEIGHT_STATUS = 10;
    public static final double WEIGHT_STATUS_THOROUGH = 50;

    /** What the language needs to know about a predicate: how heavy it is and whether it reads the status. */
    public record Symbol(double weight, boolean callStatus) {
    }

    /** The predicate table; the evaluator adds the implementations. */
    public static Map<String, Symbol> coreSymbols() {
        Map<String, Symbol> s = new HashMap<>();
        for (String name : new String[] {"modified", "added", "removed", "deleted", "missing", "clean"}) {
            s.put(name, new Symbol(WEIGHT_STATUS, true));
        }
        s.put("unknown", new Symbol(WEIGHT_STATUS_THOROUGH, true));
        s.put("ignored", new Symbol(WEIGHT_STATUS_THOROUGH, true));
        s.put("tracked", new Symbol(1, false));
        s.put("binary", new Symbol(WEIGHT_READ_CONTENTS, false));
        s.put("exec", new Symbol(1, false));
        s.put("symlink", new Symbol(1, false));
        s.put("resolved", new Symbol(WEIGHT_STATUS, false));
        s.put("unresolved", new Symbol(WEIGHT_STATUS, false));
        s.put("hgignore", new Symbol(WEIGHT_STATUS, false));
        s.put("portable", new Symbol(WEIGHT_CHECK_FILENAME, false));
        s.put("grep", new Symbol(WEIGHT_READ_CONTENTS, false));
        s.put("size", new Symbol(WEIGHT_STATUS, false));
        s.put("encoding", new Symbol(WEIGHT_READ_CONTENTS, false));
        s.put("eol", new Symbol(WEIGHT_READ_CONTENTS, false));
        s.put("copied", new Symbol(1, false));
        s.put("revs", new Symbol(WEIGHT_STATUS, false));
        s.put("status", new Symbol(WEIGHT_STATUS, false));
        s.put("subrepo", new Symbol(1, false));
        return s;
    }

    /** The predicates with the {@code lfs} extension on. */
    public static Map<String, Symbol> symbolsWithLfs() {
        Map<String, Symbol> s = coreSymbols();
        s.put("lfs", new Symbol(1, false));
        return s;
    }

    private FilesetLanguage() {
    }

    // ---------------------------------------------------------------- tokenizer and parser

    private record Token(String type, String value, int pos) {
    }

    /** {@code (binding, primary, prefix, infix, suffix)} of an element; a prefix/infix is {@code (name, bind, end)}. */
    private record Element(int binding, String primary, String[] prefix, String[] infix, String suffix) {
        boolean newTerm() {
            return primary != null || prefix != null;
        }
    }

    private static final Map<String, Element> ELEMENTS = new HashMap<>();

    static {
        ELEMENTS.put("(", new Element(20, null, new String[] {"group", "1", ")"}, new String[] {"func", "1", ")"}, null));
        ELEMENTS.put(":", new Element(15, null, null, new String[] {"kindpat", "15"}, null));
        ELEMENTS.put("-", new Element(5, null, new String[] {"negate", "19"}, new String[] {"minus", "5"}, null));
        ELEMENTS.put("not", new Element(10, null, new String[] {"not", "10"}, null, null));
        ELEMENTS.put("!", new Element(10, null, new String[] {"not", "10"}, null, null));
        ELEMENTS.put("and", new Element(5, null, null, new String[] {"and", "5"}, null));
        ELEMENTS.put("&", new Element(5, null, null, new String[] {"and", "5"}, null));
        ELEMENTS.put("or", new Element(4, null, null, new String[] {"or", "4"}, null));
        ELEMENTS.put("|", new Element(4, null, null, new String[] {"or", "4"}, null));
        ELEMENTS.put("+", new Element(4, null, null, new String[] {"or", "4"}, null));
        ELEMENTS.put(",", new Element(2, null, null, new String[] {"list", "2"}, null));
        ELEMENTS.put(")", new Element(0, null, null, null, null));
        ELEMENTS.put("symbol", new Element(0, "symbol", null, null, null));
        ELEMENTS.put("string", new Element(0, "string", null, null, null));
        ELEMENTS.put("end", new Element(0, null, null, null, null));
    }

    private static final String GLOB_CHARS = ".*{}[]?/\\_";
    private static final Set<String> KEYWORDS = Set.of("and", "or", "not");

    /** The tokens, produced one at a time like Mercurial's generator (an error shows when the parser reaches it). */
    private static final class Tokenizer {
        private final byte[] program;
        private int pos;
        private boolean ended;

        Tokenizer(byte[] program) {
            this.program = program;
        }

        private static boolean isSpace(int c) {
            return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0b || c == 0x0c || c == 0x1c || c == 0x1d || c == 0x1e
                    || c == 0x1f;
        }

        private static boolean isAlnum(int c) {
            return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
        }

        private static boolean symbolChar(int c) {
            return isAlnum(c) || GLOB_CHARS.indexOf(c) >= 0 || c > 127;
        }

        private static String str(byte[] bytes, int from, int to) {
            return new String(bytes, from, to - from, StandardCharsets.ISO_8859_1);
        }

        Token next() throws FilesetParseException {
            int l = program.length;
            while (pos < l) {
                int c = program[pos] & 0xff;
                if (isSpace(c)) {
                    pos++;
                    continue;
                }
                if ("(),-:|&+!".indexOf(c) >= 0) {
                    Token t = new Token(String.valueOf((char) c), null, pos);
                    pos++;
                    return t;
                }
                boolean rawPrefix = c == 'r' && pos + 1 < l && (program[pos + 1] == '\'' || program[pos + 1] == '"');
                if (c == '"' || c == '\'' || rawPrefix) {
                    boolean raw = false;
                    if (rawPrefix) {
                        raw = true;
                        pos++;
                        c = program[pos] & 0xff;
                    }
                    pos++;
                    int s = pos;
                    while (pos < l) {
                        int d = program[pos] & 0xff;
                        if (d == '\\') {
                            pos += 2;
                            continue;
                        }
                        if (d == c) {
                            String text = str(program, s, pos);
                            pos++;
                            return new Token("string", raw ? text : unescape(text), s);
                        }
                        pos++;
                    }
                    throw new FilesetParseException("unterminated string", s);
                }
                if (symbolChar(c)) {
                    int s = pos;
                    pos++;
                    while (pos < l && symbolChar(program[pos] & 0xff)) {
                        pos++;
                    }
                    String sym = str(program, s, pos);
                    return KEYWORDS.contains(sym) ? new Token(sym, null, s) : new Token("symbol", sym, s);
                }
                throw new FilesetParseException("syntax error", pos);
            }
            if (!ended) {
                ended = true;
            }
            return new Token("end", null, pos);
        }
    }

    /** {@code codecs.escape_decode} on the bytes of a quoted string (kept as ISO-8859-1 characters). */
    static String unescape(String s) {
        StringBuilder out = new StringBuilder();
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= n) {
                out.append(c);
                continue;
            }
            char d = s.charAt(++i);
            switch (d) {
                case '\\' -> out.append('\\');
                case '\'' -> out.append('\'');
                case '"' -> out.append('"');
                case 'a' -> out.append((char) 7);
                case 'b' -> out.append((char) 8);
                case 'f' -> out.append((char) 12);
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'v' -> out.append((char) 11);
                case '\n' -> { /* line continuation */ }
                case 'x' -> {
                    if (i + 2 < n + 0 && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2))) {
                        out.append((char) Integer.parseInt(s.substring(i + 1, i + 3), 16));
                        i += 2;
                    } else {
                        out.append("\\x");
                    }
                }
                default -> {
                    if (d >= '0' && d <= '7') {
                        int j = i;
                        int value = 0;
                        int digits = 0;
                        while (j < n && digits < 3 && s.charAt(j) >= '0' && s.charAt(j) <= '7') {
                            value = value * 8 + (s.charAt(j) - '0');
                            j++;
                            digits++;
                        }
                        out.append((char) (value & 0xff));
                        i = j - 1;
                    } else {
                        out.append('\\').append(d);
                    }
                }
            }
        }
        return out.toString();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static final class Parser {
        private final Tokenizer tokens;
        private Token current;

        Parser(Tokenizer tokens) {
            this.tokens = tokens;
        }

        private Token advance() throws FilesetParseException {
            Token t = current;
            current = tokens.next();
            return t;
        }

        private void match(String type) throws FilesetParseException {
            if (!current.type().equals(type)) {
                throw new FilesetParseException("unexpected token: " + current.type(), current.pos());
            }
            advance();
        }

        private Object parseOperand(int bind, String end) throws FilesetParseException {
            Object expr;
            if (end != null && current.type().equals(end)) {
                expr = null;
            } else {
                expr = parse(bind);
            }
            if (end != null) {
                match(end);
            }
            return expr;
        }

        Object parse(int bind) throws FilesetParseException {
            Token t = advance();
            Element e = ELEMENTS.get(t.type());
            Object expr;
            if (e.primary() != null && !(e.prefix() != null && ELEMENTS.get(current.type()).newTerm())) {
                expr = new Tree(e.primary(), t.value());
            } else if (e.prefix() != null) {
                String[] prefix = e.prefix();
                expr = new Tree(prefix[0], parseOperand(Integer.parseInt(prefix[1]), prefix.length > 2 ? prefix[2] : null));
            } else {
                throw new FilesetParseException("not a prefix: " + t.type(), t.pos());
            }
            while (bind < ELEMENTS.get(current.type()).binding()) {
                Token op = advance();
                Element oe = ELEMENTS.get(op.type());
                if (oe.suffix() != null && !(oe.infix() != null && ELEMENTS.get(current.type()).newTerm())) {
                    expr = new Tree(oe.suffix(), expr);
                } else if (oe.infix() != null) {
                    String[] infix = oe.infix();
                    expr = new Tree(infix[0], expr, parseOperand(Integer.parseInt(infix[1]), infix.length > 2 ? infix[2] : null));
                } else {
                    throw new FilesetParseException("not an infix: " + op.type(), op.pos());
                }
            }
            return expr;
        }
    }

    /** {@code filesetlang.parse}: the raw tree of an expression. */
    public static Tree parse(String expression) throws FilesetParseException {
        byte[] program = expression.getBytes(StandardCharsets.UTF_8);
        Parser parser = new Parser(new Tokenizer(program));
        parser.current = parser.tokens.next();
        Object tree = parser.parse(0);
        if (parser.current.pos() != program.length) {
            throw new FilesetParseException("invalid token", parser.current.pos());
        }
        return (Tree) simplifyInfixOps(tree, Set.of("list", "or"));
    }

    /** {@code parser.simplifyinfixops}: chains of the same left-associative operator become one flat node. */
    private static Object simplifyInfixOps(Object tree, Set<String> targets) {
        if (!(tree instanceof Tree t)) {
            return tree;
        }
        String op = t.op();
        if (!targets.contains(op)) {
            List<Object> args = new ArrayList<>();
            for (Object a : t.args()) {
                args.add(simplifyInfixOps(a, targets));
            }
            return new Tree(op, args);
        }
        List<Object> simplified = new ArrayList<>();
        Object x = tree;
        while (x instanceof Tree xt && xt.op().equals(op)) {
            simplified.add(simplifyInfixOps(xt.args().get(1), targets));
            x = xt.args().get(0);
        }
        simplified.add(simplifyInfixOps(x, targets));
        java.util.Collections.reverse(simplified);
        return new Tree(op, simplified);
    }

    // ---------------------------------------------------------------- helpers shared with the evaluator

    /** {@code filesetlang.getsymbol}. */
    public static String getSymbol(Object x) throws FilesetParseException {
        if (x instanceof Tree t && t.op().equals("symbol")) {
            return t.text(0);
        }
        throw new FilesetParseException("not a symbol");
    }

    /** {@code filesetlang.getstring}. */
    public static String getString(Object x, String err) throws FilesetParseException {
        if (x instanceof Tree t && (t.op().equals("string") || t.op().equals("symbol"))) {
            return t.text(0);
        }
        throw new FilesetParseException(err);
    }

    /** {@code filesetlang.getkindpat}. */
    public static String getKindPat(Object x, Object y, Set<String> allKinds, String err) throws FilesetParseException {
        String kind = getSymbol(x);
        String pat = getString(y, err);
        if (!allKinds.contains(kind)) {
            throw new FilesetParseException("invalid pattern kind: " + kind);
        }
        return kind + ":" + pat;
    }

    /** {@code filesetlang.getpattern}. */
    public static String getPattern(Object x, Set<String> allKinds, String err) throws FilesetParseException {
        if (x instanceof Tree t && t.op().equals("kindpat")) {
            return getKindPat(t.args().get(0), t.args().get(1), allKinds, err);
        }
        return getString(x, err);
    }

    /** {@code filesetlang.getlist}. */
    public static List<Object> getList(Object x) {
        if (x == null) {
            return List.of();
        }
        if (x instanceof Tree t && t.op().equals("list")) {
            return new ArrayList<>(t.args());
        }
        return List.of(x);
    }

    /** {@code filesetlang.getargs}. */
    public static List<Object> getArgs(Object x, int min, int max, String err) throws FilesetParseException {
        List<Object> l = getList(x);
        if (l.size() < min || l.size() > max) {
            throw new FilesetParseException(err);
        }
        return l;
    }

    // ---------------------------------------------------------------- analyze

    private static Object analyzeNode(Object x) throws FilesetParseException {
        if (x == null) {
            return null;
        }
        Tree t = (Tree) x;
        switch (t.op()) {
            case "string", "symbol":
                return t;
            case "kindpat": {
                getSymbol(t.args().get(0)); // the kind must be a symbol
                return new Tree("kindpat", t.args().get(0), analyzeNode(t.args().get(1)));
            }
            case "group":
                return analyzeNode(t.args().get(0));
            case "negate":
                throw new FilesetParseException("can't use negate operator in this context");
            case "not":
                return new Tree("not", analyzeNode(t.args().get(0)));
            case "and":
                return new Tree("and", analyzeNode(t.args().get(0)), analyzeNode(t.args().get(1)));
            case "minus":
                return analyzeNode(new Tree("and", t.args().get(0), new Tree("not", t.args().get(1))));
            case "list", "or": {
                List<Object> parts = new ArrayList<>();
                for (Object a : t.args()) {
                    parts.add(analyzeNode(a));
                }
                return new Tree(t.op(), parts);
            }
            case "func": {
                getSymbol(t.args().get(0)); // the function name must be a symbol
                return new Tree("func", t.args().get(0), analyzeNode(t.args().get(1)));
            }
            default:
                throw new IllegalStateException("invalid operator " + t.op());
        }
    }

    /** Status names a subtree refers to plus the tree with {@code withstatus} hint nodes ({@code _insertstatushints}). */
    private record Hinted(List<String> names, Object tree) {
    }

    private static Hinted insertStatusHints(Object x, Map<String, Symbol> symbols) throws FilesetParseException {
        if (x == null) {
            return new Hinted(List.of(), null);
        }
        Tree t = (Tree) x;
        switch (t.op()) {
            case "string", "symbol", "kindpat":
                return new Hinted(List.of(), t);
            case "not": {
                Hinted h = insertStatusHints(t.args().get(0), symbols);
                return new Hinted(h.names(), new Tree("not", h.tree()));
            }
            case "and": {
                Hinted a = insertStatusHints(t.args().get(0), symbols);
                Hinted b = insertStatusHints(t.args().get(1), symbols);
                List<String> all = new ArrayList<>(a.names());
                all.addAll(b.names());
                Tree and = new Tree("and", a.tree(), b.tree());
                if (!a.names().isEmpty() && !b.names().isEmpty()) {
                    return new Hinted(all, new Tree("withstatus", and, new Tree("string", String.join(" ", all))));
                }
                return new Hinted(all, and);
            }
            case "or": {
                List<String> all = new ArrayList<>();
                List<Object> parts = new ArrayList<>();
                int withNames = 0;
                for (Object a : t.args()) {
                    Hinted h = insertStatusHints(a, symbols);
                    all.addAll(h.names());
                    parts.add(h.tree());
                    if (!h.names().isEmpty()) {
                        withNames++;
                    }
                }
                Tree or = new Tree("or", parts);
                if (withNames > 1) {
                    return new Hinted(all, new Tree("withstatus", or, new Tree("string", String.join(" ", all))));
                }
                return new Hinted(all, or);
            }
            case "list": {
                List<String> all = new ArrayList<>();
                List<Object> parts = new ArrayList<>();
                for (Object a : t.args()) {
                    Hinted h = insertStatusHints(a, symbols);
                    all.addAll(h.names());
                    parts.add(h.tree());
                }
                return new Hinted(all, new Tree("list", parts));
            }
            case "func": {
                String f = getSymbol(t.args().get(0));
                Hinted h = insertStatusHints(t.args().get(1), symbols); // a function boundary stops what it found
                Tree func = new Tree("func", t.args().get(0), h.tree());
                Symbol s = symbols.get(f);
                if (s != null && s.callStatus()) {
                    return new Hinted(List.of(f), new Tree("withstatus", func, new Tree("string", f)));
                }
                return new Hinted(List.of(), func);
            }
            default:
                throw new IllegalStateException("invalid operator " + t.op());
        }
    }

    private static Object mergeStatusHints(Object x, boolean inStatus) {
        if (x == null) {
            return null;
        }
        Tree t = (Tree) x;
        switch (t.op()) {
            case "withstatus": {
                if (inStatus) {
                    return mergeStatusHints(t.args().get(0), true); // drop the redundant hint node
                }
                return new Tree("withstatus", mergeStatusHints(t.args().get(0), true), t.args().get(1));
            }
            case "string", "symbol", "kindpat":
                return t;
            case "not":
                return new Tree("not", mergeStatusHints(t.args().get(0), inStatus));
            case "and":
                return new Tree("and", mergeStatusHints(t.args().get(0), inStatus), mergeStatusHints(t.args().get(1), inStatus));
            case "list", "or": {
                List<Object> parts = new ArrayList<>();
                for (Object a : t.args()) {
                    parts.add(mergeStatusHints(a, inStatus));
                }
                return new Tree(t.op(), parts);
            }
            case "func":
                return new Tree("func", t.args().get(0), mergeStatusHints(t.args().get(1), false)); // a function boundary resets it
            default:
                throw new IllegalStateException("invalid operator " + t.op());
        }
    }

    /** {@code filesetlang.analyze}: pseudo operations become real ones and status hint nodes are inserted. */
    public static Tree analyze(Tree x, Map<String, Symbol> symbols) throws FilesetParseException {
        Object t = analyzeNode(x);
        Hinted hinted = insertStatusHints(t, symbols);
        return (Tree) mergeStatusHints(hinted.tree(), false);
    }

    // ---------------------------------------------------------------- optimize

    private record Weighted(double weight, Object tree) {
    }

    private static Object optimizeAndOps(String op, Object ta, Object tb) {
        if (tb instanceof Tree b && b.op().equals("not")) {
            return new Tree("minus", ta, b.args().get(0));
        }
        return new Tree(op, ta, tb);
    }

    private static Weighted optimizeNode(Object x, Map<String, Symbol> symbols) throws FilesetParseException {
        if (x == null) {
            return new Weighted(0, null);
        }
        Tree t = (Tree) x;
        switch (t.op()) {
            case "withstatus": {
                Weighted w = optimizeNode(t.args().get(0), symbols);
                return new Weighted(w.weight(), new Tree("withstatus", w.tree(), t.args().get(1)));
            }
            case "string", "symbol":
                return new Weighted(WEIGHT_CHECK_FILENAME, t);
            case "kindpat": {
                Weighted w = optimizeNode(t.args().get(1), symbols);
                return new Weighted(w.weight(), new Tree("kindpat", t.args().get(0), w.tree()));
            }
            case "not": {
                Weighted w = optimizeNode(t.args().get(0), symbols);
                return new Weighted(w.weight(), new Tree("not", w.tree()));
            }
            case "and": {
                Weighted a = optimizeNode(t.args().get(0), symbols);
                Weighted b = optimizeNode(t.args().get(1), symbols);
                if (a.weight() <= b.weight()) {
                    return new Weighted(a.weight(), optimizeAndOps("and", a.tree(), b.tree()));
                }
                return new Weighted(b.weight(), optimizeAndOps("and", b.tree(), a.tree()));
            }
            case "or": {
                List<Double> ws = new ArrayList<>();
                List<Object> ts = new ArrayList<>();
                List<Object> strings = new ArrayList<>();
                for (Object a : t.args()) {
                    Weighted w = optimizeNode(a, symbols);
                    if (w.tree() instanceof Tree wt && (wt.op().equals("string") || wt.op().equals("symbol") || wt.op().equals("kindpat"))) {
                        strings.add(wt);
                        continue;
                    }
                    ws.add(w.weight());
                    ts.add(w.tree());
                }
                if (!strings.isEmpty()) {
                    ws.add(WEIGHT_CHECK_FILENAME);
                    ts.add(new Tree("patterns", strings));
                }
                if (ts.size() == 1) {
                    return new Weighted(ws.get(0), ts.get(0)); // 'or' is fully optimized out
                }
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < ts.size(); i++) {
                    order.add(i);
                }
                order.sort((i, j) -> Double.compare(ws.get(i), ws.get(j))); // stable
                List<Object> sorted = new ArrayList<>();
                for (int i : order) {
                    sorted.add(ts.get(i));
                }
                double max = Double.NEGATIVE_INFINITY;
                for (double w : ws) {
                    max = Math.max(max, w);
                }
                return new Weighted(max, new Tree("or", sorted));
            }
            case "list": {
                double sum = 0;
                List<Object> parts = new ArrayList<>();
                for (Object a : t.args()) {
                    Weighted w = optimizeNode(a, symbols);
                    sum += w.weight();
                    parts.add(w.tree());
                }
                return new Weighted(sum, new Tree("list", parts));
            }
            case "func": {
                String f = getSymbol(t.args().get(0));
                Symbol s = symbols.get(f);
                double w = s != null ? s.weight() : 1;
                Weighted a = optimizeNode(t.args().get(1), symbols);
                return new Weighted(w + a.weight(), new Tree("func", t.args().get(0), a.tree()));
            }
            default:
                throw new IllegalStateException("invalid operator " + t.op());
        }
    }

    /** {@code filesetlang.optimize}: reorders and rewrites an analyzed tree. */
    public static Tree optimize(Tree x, Map<String, Symbol> symbols) throws FilesetParseException {
        return (Tree) optimizeNode(x, symbols).tree();
    }

    // ---------------------------------------------------------------- pretty format

    /** {@code parser.prettyformat} with {@code string} and {@code symbol} as leaves, as {@code debugfileset} prints trees. */
    public static String prettyFormat(Object tree) {
        StringBuilder out = new StringBuilder();
        pretty(tree, 0, out);
        return out.toString();
    }

    private static void pretty(Object tree, int level, StringBuilder out) {
        if (!(tree instanceof Tree t)) {
            out.append(tree == null ? "None" : "'" + escape((String) tree) + "'");
            return;
        }
        if (t.op().equals("string") || t.op().equals("symbol")) {
            out.append('(').append(t.op());
            for (Object a : t.args()) {
                out.append(' ').append(a == null ? "None" : "'" + escape((String) a) + "'");
            }
            out.append(')');
            return;
        }
        out.append('(').append(t.op());
        int next = level + 1;
        for (Object a : t.args()) {
            out.append('\n');
            out.append(" ".repeat(next * 2));
            pretty(a, next, out);
        }
        out.append(')');
    }

    /** {@code codecs.escape_encode} on the UTF-8 bytes of the text. */
    private static String escape(String text) {
        StringBuilder out = new StringBuilder();
        // string values read from the program keep one character per byte (ISO-8859-1); other strings are encoded first
        byte[] bytes = isByteString(text) ? text.getBytes(StandardCharsets.ISO_8859_1) : text.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            int c = b & 0xff;
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\'' -> out.append("\\'");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20 || c >= 0x7f) {
                        out.append(String.format("\\x%02x", c));
                    } else {
                        out.append((char) c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static boolean isByteString(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0xff) {
                return false;
            }
        }
        return true;
    }

    /** All stages of an expression as {@code hg debugfileset --show-stage} prints them, keyed by stage name. */
    public static Map<String, String> stages(String expression, Map<String, Symbol> symbols) throws FilesetParseException {
        Map<String, String> out = new TreeMap<>();
        Tree parsed = parse(expression);
        out.put("parsed", prettyFormat(parsed));
        Tree analyzed = analyze(parsed, symbols);
        out.put("analyzed", prettyFormat(analyzed));
        out.put("optimized", prettyFormat(optimize(analyzed, symbols)));
        return out;
    }
}
