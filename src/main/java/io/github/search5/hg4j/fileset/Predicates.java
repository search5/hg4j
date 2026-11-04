package io.github.search5.hg4j.fileset;

import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.match.Matcher;
import io.github.search5.hg4j.match.Matchers;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongPredicate;
import java.util.regex.Pattern;

/** The fileset predicates of {@code fileset.py} (and {@code lfs()} of the lfs extension). */
final class Predicates {

    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of("con", "prn", "aux", "nul", "com1", "com2", "com3", "com4",
            "com5", "com6", "com7", "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");
    private static final String WINDOWS_RESERVED_CHARS = ":*?\"<>|";

    private Predicates() {
    }

    /** Called once when the {@link Fileset} class is loaded. */
    static void registerAll() {
        status("modified", FileStatus::modified);
        status("added", FileStatus::added);
        status("removed", FileStatus::removed);
        status("deleted", FileStatus::deleted);
        status("missing", FileStatus::deleted);
        status("unknown", FileStatus::unknown);
        status("ignored", FileStatus::ignored);
        status("clean", FileStatus::clean);

        Fileset.register("tracked", (mctx, x) -> {
            noArguments("tracked", x);
            return mctx.predicate(f -> unchecked(() -> mctx.ctx().contains(f)));
        });
        Fileset.register("binary", (mctx, x) -> {
            noArguments("binary", x);
            return contentPredicate(mctx, data -> data != null && indexOfNul(data) >= 0);
        });
        Fileset.register("exec", (mctx, x) -> {
            noArguments("exec", x);
            return mctx.predicate(f -> unchecked(() -> "x".equals(mctx.ctx().flags(f))));
        });
        Fileset.register("symlink", (mctx, x) -> {
            noArguments("symlink", x);
            return mctx.predicate(f -> unchecked(() -> "l".equals(mctx.ctx().flags(f))));
        });
        Fileset.register("resolved", (mctx, x) -> mergeState(mctx, x, "resolved", "r"));
        Fileset.register("unresolved", (mctx, x) -> mergeState(mctx, x, "unresolved", "u"));
        Fileset.register("hgignore", (mctx, x) -> {
            noArguments("hgignore", x);
            return mctx.ctx().ignoreMatcher();
        });
        Fileset.register("portable", (mctx, x) -> {
            noArguments("portable", x);
            return mctx.predicate(f -> checkWinFilename(f) == null);
        });
        Fileset.register("grep", Predicates::grep);
        Fileset.register("size", Predicates::size);
        Fileset.register("encoding", Predicates::encoding);
        Fileset.register("eol", Predicates::eol);
        Fileset.register("copied", (mctx, x) -> {
            noArguments("copied", x);
            return mctx.predicate(f -> unchecked(() -> {
                String source = mctx.ctx().copySource(f);
                return source != null && !source.equals(f);
            }));
        });
        Fileset.register("revs", Predicates::revs);
        Fileset.register("status", Predicates::statusBetween);
        Fileset.register("subrepo", Predicates::subrepo);
        Fileset.register("lfs", (mctx, x) -> {
            FilesetLanguage.getArgs(x, 0, 0, "lfs takes no arguments");
            Set<String> files = mctx.ctx().lfsFiles();
            return mctx.predicate(files::contains);
        });
    }

    // ---------------------------------------------------------------- helpers

    private interface Check<T> {
        T run() throws IOException;
    }

    private static <T> T unchecked(Check<T> check) {
        try {
            return check.run();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void noArguments(String name, Object x) throws FilesetParseException {
        FilesetLanguage.getArgs(x, 0, 0, name + " takes no arguments");
    }

    private static void status(String name, Function<FileStatus, Set<String>> pick) {
        Fileset.register(name, (mctx, x) -> {
            noArguments(name, x);
            Set<String> s = pick.apply(mctx.status());
            return mctx.predicate(s::contains);
        });
    }

    private static boolean indexOfNulPresent(byte[] data) {
        return indexOfNul(data) >= 0;
    }

    private static int indexOfNul(byte[] data) {
        for (int i = 0; i < data.length; i++) {
            if (data[i] == 0) {
                return i;
            }
        }
        return -1;
    }

    /** {@code mctx.fpredicate(..., cache=True)}: a function of the file content; a missing file is never selected. */
    private static Matcher contentPredicate(Fileset.MatchContext mctx, java.util.function.Predicate<byte[]> test) {
        Map<String, Boolean> cache = new HashMap<>();
        return mctx.predicate(f -> cache.computeIfAbsent(f, name -> unchecked(() -> {
            byte[] data;
            try {
                data = mctx.ctx().data(name);
            } catch (IOException e) {
                return false; // a directory or an unreadable file
            }
            return data != null && test.test(data);
        })));
    }

    // ---------------------------------------------------------------- the predicates

    private static Matcher mergeState(Fileset.MatchContext mctx, Object x, String name, String state) throws IOException {
        noArguments(name, x);
        if (!mctx.ctx().isWorkingDirectory()) {
            return mctx.never();
        }
        return mctx.predicate(f -> unchecked(() -> state.equals(mctx.ctx().mergeState(f))));
    }

    private static Matcher grep(Fileset.MatchContext mctx, Object x) throws IOException {
        Pattern r = PythonRegex.compile(FilesetLanguage.getString(x, "grep requires a pattern"));
        return contentPredicate(mctx, data -> r.matcher(new String(data, StandardCharsets.ISO_8859_1)).find());
    }

    private static Matcher size(Fileset.MatchContext mctx, Object x) throws IOException {
        String expr = FilesetLanguage.getString(x, "size requires an expression");
        LongPredicate m = Sizes.matcher(expr);
        Map<String, Boolean> cache = new HashMap<>();
        return mctx.predicate(f -> cache.computeIfAbsent(f, name -> unchecked(() -> {
            long size;
            try {
                size = mctx.ctx().size(name);
            } catch (IOException e) {
                return false;
            }
            return size >= 0 && m.test(size);
        })));
    }

    private static Charset charsetOf(String name) {
        String n = name.toLowerCase(Locale.ROOT).replace('_', '-');
        switch (n) {
            case "utf8", "utf-8", "u8", "utf", "utf8-ucs2", "utf8-ucs4":
                return StandardCharsets.UTF_8;
            case "ascii", "us-ascii", "646", "ansi-x3.4-1968":
                return StandardCharsets.US_ASCII;
            case "latin1", "latin-1", "latin", "l1", "iso-8859-1", "iso8859-1", "8859", "cp819":
                return StandardCharsets.ISO_8859_1;
            default:
                try {
                    return Charset.forName(name);
                } catch (IllegalArgumentException e) {
                    return null;
                }
        }
    }

    private static Matcher encoding(Fileset.MatchContext mctx, Object x) throws IOException {
        String enc = FilesetLanguage.getString(x, "encoding requires an encoding name");
        Charset charset = charsetOf(enc);
        return contentPredicate(mctx, data -> {
            if (charset == null) {
                throw new java.io.UncheckedIOException(new HgValidationException("unknown encoding '" + enc + "'"));
            }
            try {
                charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(data));
                return true;
            } catch (CharacterCodingException e) {
                return false;
            }
        });
    }

    private static Matcher eol(Fileset.MatchContext mctx, Object x) throws IOException {
        String style = FilesetLanguage.getString(x, "eol requires a style name");
        return contentPredicate(mctx, data -> {
            if (indexOfNul(data) >= 0) {
                return false;
            }
            switch (style) {
                case "dos", "win":
                    for (int i = 0; i + 1 < data.length; i++) {
                        if (data[i] == '\r' && data[i + 1] == '\n') {
                            return true;
                        }
                    }
                    return false;
                case "unix":
                    for (int i = 0; i < data.length; i++) {
                        if (data[i] == '\n' && (i == 0 || data[i - 1] != '\r')) {
                            return true;
                        }
                    }
                    return false;
                case "mac":
                    for (int i = 0; i < data.length; i++) {
                        if (data[i] == '\r' && (i + 1 >= data.length || data[i + 1] != '\n')) {
                            return true;
                        }
                    }
                    return false;
                default:
                    return false;
            }
        });
    }

    private static Matcher revs(Fileset.MatchContext mctx, Object x) throws IOException {
        List<Object> args = FilesetLanguage.getArgs(x, 2, 2, "revs takes two arguments");
        String revspec = FilesetLanguage.getString(args.get(0), "first argument to revs must be a revision");
        List<Matcher> matchers = new ArrayList<>();
        for (FilesetContext revision : mctx.ctx().revisions(revspec)) {
            matchers.add(Fileset.match(mctx.switchTo(revision.p1(), revision), args.get(1)));
        }
        if (matchers.isEmpty()) {
            return mctx.never();
        }
        return Matchers.union(matchers);
    }

    private static Matcher statusBetween(Fileset.MatchContext mctx, Object x) throws IOException {
        List<Object> args = FilesetLanguage.getArgs(x, 3, 3, "status takes three arguments");
        String baseErr = "first argument to status must be a revision";
        String baseSpec = FilesetLanguage.getString(args.get(0), baseErr);
        if (baseSpec.isEmpty()) {
            throw new FilesetParseException(baseErr);
        }
        String revErr = "second argument to status must be a revision";
        String revSpec = FilesetLanguage.getString(args.get(1), revErr);
        if (revSpec.isEmpty()) {
            throw new FilesetParseException(revErr);
        }
        List<FilesetContext> bases = mctx.ctx().revisions(baseSpec);
        List<FilesetContext> targets = mctx.ctx().revisions(revSpec);
        if (bases.isEmpty() || targets.isEmpty()) {
            throw new HgValidationException("empty revision range");
        }
        return Fileset.match(mctx.switchTo(bases.get(0), targets.get(targets.size() - 1)), args.get(2));
    }

    private static Matcher subrepo(Fileset.MatchContext mctx, Object x) throws IOException {
        FilesetLanguage.getArgs(x, 0, 1, "subrepo takes at most one argument");
        Set<String> state = mctx.ctx().subrepos();
        if (x != null) {
            String pat = FilesetLanguage.getPattern(x, Matchers.ALL_KINDS, "subrepo requires a pattern or no arguments");
            boolean fast = !hasKind(pat);
            if (fast) {
                return mctx.predicate(s -> s.equals(pat));
            }
            Matcher m = Matchers.match(mctx.ctx().root(), "", List.of(pat), null, null, "glob", null, null);
            return mctx.predicate(f -> state.contains(f) && m.matches(f));
        }
        return mctx.predicate(state::contains);
    }

    private static boolean hasKind(String pattern) {
        int colon = pattern.indexOf(':');
        return colon >= 0 && Matchers.ALL_KINDS.contains(pattern.substring(0, colon));
    }

    // ---------------------------------------------------------------- util.checkwinfilename

    /** {@code util.checkwinfilename}: why the name is no valid file name on Windows, or {@code null}. */
    static String checkWinFilename(String path) {
        if (path.endsWith("\\")) {
            return "filename ends with '\\', which is invalid on Windows";
        }
        if (path.contains("\\/")) {
            return "directory name ends with '\\', which is invalid on Windows";
        }
        for (String n : path.replace('\\', '/').split("/")) {
            if (n.isEmpty()) {
                continue;
            }
            for (int i = 0; i < n.length(); i++) {
                char c = n.charAt(i);
                if (WINDOWS_RESERVED_CHARS.indexOf(c) >= 0) {
                    return "filename contains '" + c + "', which is reserved on Windows";
                }
                if (c <= 31) {
                    return "filename contains '" + String.format("\\x%02x", (int) c) + "', which is invalid on Windows";
                }
            }
            String base = n.split("\\.", -1)[0];
            if (!base.isEmpty() && WINDOWS_RESERVED_NAMES.contains(base.toLowerCase(Locale.ROOT))) {
                return "filename contains '" + base + "', which is reserved on Windows";
            }
            char t = n.charAt(n.length() - 1);
            if ((t == '.' || t == ' ') && !"..".contains(n)) {
                return "filename ends with '" + t + "', which is not allowed on Windows";
            }
        }
        return null;
    }
}
