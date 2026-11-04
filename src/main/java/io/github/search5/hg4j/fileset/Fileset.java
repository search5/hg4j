package io.github.search5.hg4j.fileset;

import io.github.search5.hg4j.match.Matcher;
import io.github.search5.hg4j.match.Matchers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Evaluates fileset expressions: Mercurial's {@code fileset.py}. {@link #compile} parses, analyzes and optimizes an expression and
 * turns it into a {@link Matcher} over the files of a {@link FilesetContext}.
 */
public final class Fileset {

    /** A fileset predicate such as {@code size(...)}: builds a matcher from its argument tree. */
    @FunctionalInterface
    public interface PredicateImpl {
        Matcher apply(MatchContext mctx, Object argument) throws IOException;
    }

    /** The registered predicates (name to implementation); more are added by the predicate classes. */
    private static final Map<String, PredicateImpl> IMPLEMENTATIONS = new LinkedHashMap<>();

    private Fileset() {
    }

    /** Registers the implementation of a predicate whose weight is known to {@link FilesetLanguage}. */
    public static void register(String name, PredicateImpl implementation) {
        IMPLEMENTATIONS.put(name, implementation);
    }

    /** The predicates available in a context (the core ones plus those of the enabled extensions). */
    static Map<String, FilesetLanguage.Symbol> symbolsFor(FilesetContext ctx) {
        return ctx.extensions().contains("lfs") ? FilesetLanguage.symbolsWithLfs() : FilesetLanguage.coreSymbols();
    }

    /** {@code fileset.match}: the matcher of one fileset expression; {@code cwd} is relative to the root ({@code ""} for it). */
    public static Matcher compile(FilesetContext ctx, String cwd, String expression) throws IOException {
        Map<String, FilesetLanguage.Symbol> symbols = symbolsFor(ctx);
        Tree tree = FilesetLanguage.parse(expression);
        tree = FilesetLanguage.analyze(tree, symbols);
        tree = FilesetLanguage.optimize(tree, symbols);
        return getMatch(new MatchContext(ctx, cwd, null, Set.of()), tree);
    }

    // ---------------------------------------------------------------- the evaluation context

    /** Mercurial's {@code matchctx}: the context, the current directory and the matcher the subtree is narrowed by. */
    public static final class MatchContext {
        private final FilesetContext ctx;
        private final String cwd;
        private final Matcher narrowedBy;
        private final Set<String> statusKeys;

        MatchContext(FilesetContext ctx, String cwd, Matcher narrowedBy, Set<String> statusKeys) {
            this.ctx = ctx;
            this.cwd = cwd;
            this.narrowedBy = narrowedBy;
            this.statusKeys = statusKeys;
        }

        public FilesetContext ctx() {
            return ctx;
        }

        public String cwd() {
            return cwd;
        }

        /** The matcher this subtree is narrowed by, or {@code null}. */
        public Matcher narrowedBy() {
            return narrowedBy;
        }

        /** The status names that were precomputed for this subtree. */
        public Set<String> statusKeys() {
            return statusKeys;
        }

        /** A matcher for patterns (kind {@code glob} by default, like {@code ctx.match}). */
        public Matcher matcher(List<String> patterns) throws IOException {
            return Matchers.match(ctx.root(), cwd, patterns, null, null, "glob",
                    (dir, expression) -> compile(ctx, dir, expression), null);
        }

        /** A matcher selecting the files for which the function is true ({@code mctx.predicate}). */
        public Matcher predicate(Predicate<String> fn) {
            return fn::test;
        }

        /** A matcher selecting nothing. */
        public Matcher never() {
            return Matchers.never();
        }

        MatchContext narrowed(Matcher match) {
            return new MatchContext(ctx, cwd, match, statusKeys);
        }

        MatchContext withStatus(Set<String> keys) {
            return new MatchContext(ctx, cwd, narrowedBy, keys);
        }
    }

    // ---------------------------------------------------------------- the methods table

    private static Matcher getMatch(MatchContext mctx, Object x) throws IOException {
        if (x == null) {
            throw new FilesetParseException("missing argument");
        }
        Tree t = (Tree) x;
        switch (t.op()) {
            case "withstatus": {
                Set<String> keys = new HashSet<>(Arrays.asList(
                        FilesetLanguage.getString(t.args().get(1), "status hint must be a string").split("\\s+")));
                return getMatch(mctx.withStatus(keys), t.args().get(0));
            }
            case "string", "symbol":
                return mctx.matcher(List.of(decode(t.text(0))));
            case "kindpat":
                return mctx.matcher(List.of(decode(
                        FilesetLanguage.getKindPat(t.args().get(0), t.args().get(1), Matchers.ALL_KINDS, "pattern must be a string"))));
            case "patterns": {
                List<String> patterns = new ArrayList<>();
                for (Object a : t.args()) {
                    patterns.add(decode(FilesetLanguage.getPattern(a, Matchers.ALL_KINDS, "pattern must be a string")));
                }
                return mctx.matcher(patterns);
            }
            case "and": {
                Matcher xm = getMatch(mctx, t.args().get(0));
                Matcher ym = getMatch(mctx.narrowed(xm), t.args().get(1));
                return Matchers.intersection(xm, ym);
            }
            case "or": {
                List<Matcher> ms = new ArrayList<>();
                for (Object a : t.args()) {
                    ms.add(getMatch(mctx, a));
                }
                return Matchers.union(ms);
            }
            case "not": {
                Matcher m = getMatch(mctx, t.args().get(0));
                return mctx.predicate(f -> !m.matches(f));
            }
            case "minus": {
                Matcher xm = getMatch(mctx, t.args().get(0));
                Matcher ym = getMatch(mctx.narrowed(xm), t.args().get(1));
                return Matchers.difference(xm, ym);
            }
            case "list":
                throw new FilesetParseException("can't use a list in this context", -1, "see 'hg help \"filesets.x or y\"'");
            case "func": {
                String name = FilesetLanguage.getSymbol(t.args().get(0));
                Map<String, FilesetLanguage.Symbol> known = symbolsFor(mctx.ctx());
                PredicateImpl impl = IMPLEMENTATIONS.get(name);
                if (impl != null && known.containsKey(name)) {
                    return impl.apply(mctx, t.args().get(1));
                }
                if (known.containsKey(name)) {
                    throw new UnsupportedOperationException("the fileset predicate " + name + "() is not implemented yet");
                }
                throw unknownIdentifier(name, known.keySet());
            }
            default:
                throw new IllegalStateException("invalid operator " + t.op());
        }
    }

    /** The text of a tree leaf is kept one character per byte; patterns and file names are ordinary strings. */
    static String decode(String byteString) {
        for (int i = 0; i < byteString.length(); i++) {
            if (byteString.charAt(i) > 0xff) {
                return byteString;
            }
        }
        return new String(byteString.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    /** {@code error.UnknownIdentifier}: the message and the "did you mean" hint over the similar predicate names. */
    static FilesetParseException unknownIdentifier(String name, Set<String> symbols) {
        List<String> similar = new ArrayList<>();
        for (String s : symbols) {
            if (Difflib.ratio(decode(name), s) > 0.6) {
                similar.add(s);
            }
        }
        String hint = null;
        if (similar.size() == 1) {
            hint = "did you mean " + similar.get(0) + "?";
        } else if (!similar.isEmpty()) {
            List<String> sorted = new ArrayList<>(similar);
            sorted.sort(null);
            hint = "did you mean one of " + String.join(", ", sorted) + "?";
        }
        return new FilesetParseException("unknown identifier: " + name, -1, hint);
    }

    /** Names of the predicates (for tests). */
    static Map<String, PredicateImpl> implementations() {
        return new HashMap<>(IMPLEMENTATIONS);
    }
}
