package io.github.search5.hg4j.match;

import io.github.search5.hg4j.errors.HgValidationException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.PatternSyntaxException;

/**
 * Builds matchers from patterns, like Mercurial's {@code match.match()}: the pattern kinds {@code glob:}, {@code re:},
 * {@code path:}, {@code filepath:}, {@code rootfilesin:}, {@code relglob:}, {@code relpath:}, {@code relre:},
 * {@code rootglob:}, {@code listfile:}, {@code listfile0:}, {@code include:}, {@code subinclude:} and {@code set:}; the
 * {@code include} patterns restrict, the {@code exclude} patterns remove.
 */
public final class Matchers {

    /** Every pattern kind ({@code match.allpatternkinds}). */
    public static final Set<String> ALL_KINDS = Set.of("re", "glob", "path", "filepath", "relglob", "relpath", "relre", "rootglob",
            "listfile", "listfile0", "set", "include", "subinclude", "rootfilesin");

    private static final Set<String> CWD_RELATIVE = Set.of("relpath", "glob");
    private static final Set<String> NORMALIZED = Set.of("relglob", "path", "filepath", "rootfilesin", "rootglob");
    private static final int MAX_RE_SIZE = 20000;

    /** Turns a {@code set:} pattern into a matcher (the fileset language lives elsewhere). */
    @FunctionalInterface
    public interface SetResolver {
        Matcher resolve(String cwd, String expression) throws IOException;
    }

    private Matchers() {
    }

    // ---------------------------------------------------------------- the simple matchers

    private static final Matcher ALWAYS = new Matcher() {
        @Override
        public boolean matches(String path) {
            return true;
        }

        @Override
        public boolean always() {
            return true;
        }

        @Override
        public String toString() {
            return "<alwaysmatcher>";
        }
    };

    private static final Matcher NEVER = new Matcher() {
        @Override
        public boolean matches(String path) {
            return false;
        }

        @Override
        public boolean isExact() {
            return true;
        }

        @Override
        public boolean visitDir(String dir) {
            return false;
        }

        @Override
        public String toString() {
            return "<nevermatcher>";
        }
    };

    public static Matcher always() {
        return ALWAYS;
    }

    public static Matcher never() {
        return NEVER;
    }

    /** {@code match.exact}: only the listed files. */
    public static Matcher exact(Collection<String> files) {
        Set<String> set = new LinkedHashSet<>(files);
        List<String> list = new ArrayList<>(set);
        return new Matcher() {
            @Override
            public boolean matches(String path) {
                return set.contains(path);
            }

            @Override
            public List<String> files() {
                return list;
            }

            @Override
            public boolean isExact() {
                return true;
            }

            @Override
            public boolean visitDir(String dir) {
                String prefix = dir.isEmpty() || dir.equals(".") ? "" : dir + "/";
                for (String f : set) {
                    if (f.startsWith(prefix)) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    public static Matcher union(List<Matcher> matchers) {
        if (matchers.size() == 1) {
            return matchers.get(0);
        }
        return new Matcher() {
            @Override
            public boolean matches(String path) {
                for (Matcher m : matchers) {
                    if (m.matches(path)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public List<String> files() {
                List<String> all = new ArrayList<>();
                matchers.forEach(m -> all.addAll(m.files()));
                return all;
            }

            @Override
            public boolean visitDir(String dir) {
                for (Matcher m : matchers) {
                    if (m.visitDir(dir)) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    /** {@code match.intersectmatchers}: both must match. */
    public static Matcher intersection(Matcher m1, Matcher m2) {
        if (m1 == null || m2 == null) {
            return m1 != null ? m1 : m2;
        }
        if (m1.always()) {
            return m2;
        }
        if (m2.always()) {
            return m1;
        }
        return new Matcher() {
            @Override
            public boolean matches(String path) {
                return m1.matches(path) && m2.matches(path);
            }

            @Override
            public List<String> files() {
                if (isExact()) {
                    Set<String> both = new LinkedHashSet<>(m1.files());
                    both.retainAll(new HashSet<>(m2.files()));
                    return new ArrayList<>(both);
                }
                List<String> all = new ArrayList<>(m1.files());
                all.addAll(m2.files());
                return all;
            }

            @Override
            public boolean isExact() {
                return m1.isExact() || m2.isExact();
            }

            @Override
            public boolean visitDir(String dir) {
                return m1.visitDir(dir) && m2.visitDir(dir);
            }
        };
    }

    /** {@code match.differencematcher}: {@code m1} but not {@code m2}. */
    public static Matcher difference(Matcher m1, Matcher m2) {
        return new Matcher() {
            @Override
            public boolean matches(String path) {
                return m1.matches(path) && !m2.matches(path);
            }

            @Override
            public List<String> files() {
                if (isExact()) {
                    List<String> kept = new ArrayList<>();
                    for (String f : m1.files()) {
                        if (matches(f)) {
                            kept.add(f);
                        }
                    }
                    return kept;
                }
                return m1.files();
            }

            @Override
            public boolean isExact() {
                return m1.isExact();
            }

            @Override
            public boolean visitDir(String dir) {
                return m1.visitDir(dir);
            }
        };
    }

    // ---------------------------------------------------------------- building

    /**
     * {@code match.match()}: patterns (restricted by {@code include}, reduced by {@code exclude}).
     *
     * @param root        the repository root
     * @param cwd         the current directory relative to {@code root} ({@code ""} for the root itself)
     * @param patterns    the patterns; none means everything
     * @param defaultKind the kind of a pattern without a prefix ({@code glob} for {@code -I}/{@code -X}, {@code relpath} for
     *                    command line arguments)
     * @param sets        resolves {@code set:} patterns, may be {@code null} (then such a pattern is refused)
     * @param warn        receives warnings, may be {@code null}
     */
    public static Matcher match(File root, String cwd, List<String> patterns, List<String> include, List<String> exclude,
                                String defaultKind, SetResolver sets, Consumer<String> warn) throws IOException {
        Matcher m;
        if (patterns != null && !patterns.isEmpty()) {
            List<KindPattern> kindpats = normalize(patterns, defaultKind, root, cwd, warn);
            m = alwaysMatches(kindpats) ? ALWAYS : build(false, root, cwd, kindpats, sets, warn);
        } else {
            m = ALWAYS; // it is a little strange that no patterns means everything, but that is what Mercurial does
        }
        if (include != null && !include.isEmpty()) {
            m = intersection(m, build(true, root, cwd, normalize(include, "glob", root, cwd, warn), sets, warn));
        }
        if (exclude != null && !exclude.isEmpty()) {
            m = difference(m, build(true, root, cwd, normalize(exclude, "glob", root, cwd, warn), sets, warn));
        }
        return m;
    }

    /** Patterns, no include or exclude, no {@code set:} patterns; paths relative to the repository root. */
    public static Matcher match(File root, List<String> patterns, String defaultKind) throws IOException {
        return match(root, "", patterns, null, null, defaultKind, null, null);
    }

    private static boolean alwaysMatches(List<KindPattern> kindpats) {
        for (KindPattern kp : kindpats) {
            if (!kp.pattern().isEmpty() || !(kp.kind().equals("relpath") || kp.kind().equals("glob"))) {
                return false;
            }
        }
        return true;
    }

    /** {@code match._donormalize}: kinds split off, paths made root relative, list files and include files expanded. */
    public static List<KindPattern> normalize(List<String> patterns, String defaultKind, File root, String cwd,
                                              Consumer<String> warn) throws IOException {
        List<KindPattern> out = new ArrayList<>();
        for (String raw : patterns) {
            String[] split = split(raw, defaultKind);
            String kind = split[0];
            String pat = split[1];
            if (CWD_RELATIVE.contains(kind)) {
                pat = canonPath(root, cwd, pat);
            } else if (NORMALIZED.contains(kind)) {
                pat = normPath(pat);
            } else if (kind.equals("listfile") || kind.equals("listfile0")) {
                List<String> files = new ArrayList<>();
                try {
                    String text = new String(Files.readAllBytes(new File(pat).toPath()), StandardCharsets.ISO_8859_1);
                    for (String f : kind.equals("listfile0") ? text.split("\0", -1) : text.split("\r?\n|\r", -1)) {
                        if (!f.isEmpty()) {
                            files.add(f);
                        }
                    }
                } catch (IOException e) {
                    throw new HgValidationException("unable to read file list (" + pat + ")");
                }
                for (KindPattern kp : normalize(files, defaultKind, root, cwd, warn)) {
                    out.add(new KindPattern(kp.kind(), kp.pattern(), pat));
                }
                continue;
            } else if (kind.equals("include")) {
                File full = new File(root, pat);
                List<String> included;
                try {
                    included = PatternFiles.read(full, warn);
                } catch (IOException e) {
                    if (warn != null) {
                        warn.accept("skipping unreadable pattern file '" + pat + "': " + e.getMessage() + "\n");
                    }
                    continue;
                }
                for (KindPattern kp : normalize(included, defaultKind, root, cwd, warn)) {
                    out.add(new KindPattern(kp.kind(), kp.pattern(), kp.source().isEmpty() ? pat : kp.source()));
                }
                continue;
            }
            out.add(new KindPattern(kind, pat, "")); // re or relre cannot be normalized
        }
        return out;
    }

    /** {@code match._patsplit}: the kind prefix (if it is a known kind) and the rest; the kind is {@code defaultKind} otherwise. */
    public static String[] split(String pattern, String defaultKind) {
        int colon = pattern.indexOf(':');
        if (colon >= 0) {
            String kind = pattern.substring(0, colon);
            if (ALL_KINDS.contains(kind)) {
                return new String[] {kind, pattern.substring(colon + 1)};
            }
        }
        return new String[] {defaultKind, pattern};
    }

    /** {@code util.normpath}: collapses {@code //}, {@code .} and {@code ..}. */
    static String normPath(String path) {
        if (path.isEmpty()) {
            return ".";
        }
        boolean absolute = path.startsWith("/");
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!parts.isEmpty() && !parts.get(parts.size() - 1).equals("..")) {
                    parts.remove(parts.size() - 1);
                } else if (!absolute) {
                    parts.add(part);
                }
            } else {
                parts.add(part);
            }
        }
        String joined = String.join("/", parts);
        if (absolute) {
            return "/" + joined;
        }
        return joined.isEmpty() ? "." : joined;
    }

    /**
     * {@code pathutil.canonpath}: a path given relative to {@code cwd} (or absolute) as a path relative to the root, refusing
     * what lies outside the root or inside {@code .hg}.
     */
    public static String canonPath(File root, String cwd, String name) throws HgValidationException {
        String rootPath = root.getAbsolutePath();
        String rootSep = rootPath.endsWith("/") ? rootPath : rootPath + "/";
        String joined = name.startsWith("/") ? name : rootSep + (cwd == null || cwd.isEmpty() ? "" : cwd + "/") + name;
        String normalized = normPath(joined);
        if (!normalized.equals(rootSep) && normalized.startsWith(rootSep)) {
            String rel = normalized.substring(rootSep.length());
            auditPath(rel);
            return rel;
        } else if (normalized.equals(rootPath) || normalized.equals(rootSep)) {
            return "";
        }
        throw new HgValidationException(name + " not under root '" + rootPath + "'");
    }

    private static void auditPath(String rel) throws HgValidationException {
        for (String part : rel.split("/")) {
            String lower = part.toLowerCase(java.util.Locale.ROOT);
            if (lower.equals(".hg") || lower.equals(".hg.") || lower.equals("..") || lower.equals(".")) {
                throw new HgValidationException("path contains illegal component: " + rel);
            }
        }
    }

    private static Matcher build(boolean include, File root, String cwd, List<KindPattern> kindpats, SetResolver sets,
                                 Consumer<String> warn) throws IOException {
        List<Matcher> matchers = new ArrayList<>();
        List<KindPattern> rest = new ArrayList<>();
        for (KindPattern kp : kindpats) {
            if (kp.kind().equals("set")) {
                if (sets == null) {
                    throw new HgValidationException("fileset expression with no context");
                }
                matchers.add(sets.resolve(cwd, kp.pattern()));
            } else {
                rest.add(kp);
            }
        }
        if (!rest.isEmpty()) {
            matchers.add(0, include ? includeMatcher(root, rest, warn) : patternMatcher(root, rest, warn));
        }
        if (matchers.isEmpty()) {
            return NEVER;
        }
        return union(matchers);
    }

    // ---------------------------------------------------------------- pattern matchers

    private static Matcher patternMatcher(File root, List<KindPattern> kindpats, Consumer<String> warn) throws IOException {
        List<String> explicit = explicitFiles(kindpats);
        Set<String> fileset = new HashSet<>(explicit);
        Matcher regex = regexMatcher(kindpats, "$", root, warn);
        return new Matcher() {
            @Override
            public boolean matches(String path) {
                return fileset.contains(path) || regex.matches(path);
            }

            @Override
            public List<String> files() {
                return explicit;
            }

            @Override
            public boolean visitDir(String dir) {
                return dirVisitable(kindpats, dir);
            }
        };
    }

    private static Matcher includeMatcher(File root, List<KindPattern> kindpats, Consumer<String> warn) throws IOException {
        Matcher regex = regexMatcher(kindpats, "(?:/|$)", root, warn);
        return new Matcher() {
            @Override
            public boolean matches(String path) {
                return regex.matches(path);
            }

            @Override
            public List<String> files() {
                return List.of();
            }

            @Override
            public boolean visitDir(String dir) {
                return dirVisitable(kindpats, dir);
            }
        };
    }

    /** {@code match._buildmatch}: subincludes, the {@code rootfilesin} shortcut, or the joined regular expression. */
    private static Matcher regexMatcher(List<KindPattern> kindpats, String globSuffix, File root, Consumer<String> warn)
            throws IOException {
        List<Matcher> parts = new ArrayList<>();
        List<KindPattern> rest = new ArrayList<>();
        List<String[]> subs = new ArrayList<>(); // prefix, include path
        for (KindPattern kp : kindpats) {
            if (kp.kind().equals("subinclude")) {
                String sourceRoot = dirName(normPath(kp.source()));
                String path = joinPath(sourceRoot, kp.pattern());
                String newRoot = dirName(path);
                String prefix = canonPath(root, "", newRoot.isEmpty() ? "." : newRoot);
                if (!prefix.isEmpty()) {
                    prefix += "/";
                }
                subs.add(new String[] {prefix, path, newRoot});
            } else {
                rest.add(kp);
            }
        }
        if (!subs.isEmpty()) {
            java.util.Map<String, Matcher> built = new java.util.HashMap<>();
            parts.add(path -> {
                for (String[] sub : subs) {
                    if (path.startsWith(sub[0])) {
                        Matcher mf = built.get(sub[0]);
                        if (mf == null) {
                            try {
                                mf = match(new File(root, sub[2]), "", List.of(), List.of("include:" + sub[1]), List.of(), "glob", null, warn);
                            } catch (IOException e) {
                                throw new java.io.UncheckedIOException(e);
                            }
                            built.put(sub[0], mf);
                        }
                        if (mf.matches(path.substring(sub[0].length()))) {
                            return true;
                        }
                    }
                }
                return false;
            });
        }
        if (!rest.isEmpty()) {
            boolean allRootFilesIn = rest.stream().allMatch(kp -> kp.kind().equals("rootfilesin"));
            if (allRootFilesIn) {
                Set<String> dirs = new HashSet<>();
                rest.forEach(kp -> dirs.add(kp.pattern()));
                parts.add(f -> {
                    int i = f.lastIndexOf('/');
                    return dirs.contains(i >= 0 ? f.substring(0, i) : ".");
                });
            } else {
                parts.add(regexOf(rest, globSuffix));
            }
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return path -> {
            for (Matcher m : parts) {
                if (m.matches(path)) {
                    return true;
                }
            }
            return false;
        };
    }

    /** {@code match._buildregexmatch}: one alternation of the regular expressions, plus the exact {@code filepath:} names. */
    private static Matcher regexOf(List<KindPattern> kindpats, String globSuffix) throws HgValidationException {
        Set<String> exact = new HashSet<>();
        List<String> regexps = new ArrayList<>();
        for (KindPattern kp : kindpats) {
            if (kp.kind().equals("filepath")) {
                exact.add(kp.pattern());
                continue;
            }
            regexps.add(Globs.regex(kp.kind(), kp.pattern(), globSuffix));
        }
        for (String r : regexps) {
            if (r.length() > MAX_RE_SIZE) {
                throw new HgValidationException("matcher pattern is too long (" + r.length() + " bytes)");
            }
        }
        java.util.regex.Pattern compiled = null;
        if (!regexps.isEmpty()) {
            try {
                compiled = Globs.compile(String.join("|", regexps));
            } catch (PatternSyntaxException e) {
                for (KindPattern kp : kindpats) {
                    if (kp.kind().equals("filepath")) {
                        continue;
                    }
                    try {
                        Globs.compile(Globs.regex(kp.kind(), kp.pattern(), globSuffix));
                    } catch (PatternSyntaxException inner) {
                        throw Globs.invalid(kp.kind(), kp.pattern(), kp.source());
                    }
                }
                throw new HgValidationException("invalid pattern");
            }
        }
        java.util.regex.Pattern pattern = compiled;
        return path -> exact.contains(path) || (pattern != null && pattern.matcher(path).lookingAt());
    }

    // ---------------------------------------------------------------- roots (match._roots and friends)

    /** The roots and the exact directories of the patterns ({@code match._patternrootsanddirs}). */
    private static void rootsAndDirs(List<KindPattern> kindpats, List<String> roots, List<String> dirs) {
        for (KindPattern kp : kindpats) {
            String kind = kp.kind();
            String pat = kp.pattern();
            if (kind.equals("glob") || kind.equals("rootglob")) {
                List<String> root = new ArrayList<>();
                for (String p : pat.split("/", -1)) {
                    if (p.contains("[") || p.contains("{") || p.contains("*") || p.contains("?")) {
                        break;
                    }
                    root.add(p);
                }
                roots.add(String.join("/", root));
            } else if (kind.equals("relpath") || kind.equals("path") || kind.equals("filepath")) {
                roots.add(pat.equals(".") ? "" : pat);
            } else if (kind.equals("rootfilesin")) {
                dirs.add(pat.equals(".") ? "" : pat);
            } else if (!kind.equals("subinclude")) {
                roots.add(""); // relglob, re, relre
            }
        }
    }

    private static List<String> explicitFiles(List<KindPattern> kindpats) {
        List<KindPattern> filable = new ArrayList<>();
        for (KindPattern kp : kindpats) {
            if (!kp.kind().equals("rootfilesin")) {
                filable.add(kp);
            }
        }
        List<String> roots = new ArrayList<>();
        rootsAndDirs(filable, roots, new ArrayList<>());
        return roots;
    }

    /** Whether a directory can hold files the patterns select. Conservative (true when unsure). */
    private static boolean dirVisitable(List<KindPattern> kindpats, String dir) {
        String d = dir.equals(".") ? "" : dir;
        for (KindPattern kp : kindpats) {
            String kind = kp.kind();
            if (!(kind.equals("glob") || kind.equals("rootglob") || kind.equals("relpath") || kind.equals("path")
                    || kind.equals("filepath") || kind.equals("rootfilesin"))) {
                return true; // relglob, re, relre, subinclude: anything can match anywhere
            }
        }
        List<String> roots = new ArrayList<>();
        List<String> dirs = new ArrayList<>();
        rootsAndDirs(kindpats, roots, dirs);
        for (String root : roots) {
            if (d.equals(root) || root.startsWith(d.isEmpty() ? "" : d + "/") || d.startsWith(root.isEmpty() ? "" : root + "/")
                    || root.isEmpty()) {
                return true;
            }
        }
        for (String exactDir : dirs) {
            if (d.equals(exactDir) || exactDir.startsWith(d.isEmpty() ? "" : d + "/") || d.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static String dirName(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    private static String joinPath(String base, String name) {
        return base.isEmpty() ? name : base + "/" + name;
    }

    /** For tests: the sorted names of the matcher kinds. */
    static List<String> kindNames() {
        List<String> names = new ArrayList<>(ALL_KINDS);
        names.sort(null);
        return Arrays.asList(names.toArray(new String[0]));
    }
}
