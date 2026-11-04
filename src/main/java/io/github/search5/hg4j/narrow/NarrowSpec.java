package io.github.search5.hg4j.narrow;

import io.github.search5.hg4j.errors.HgValidationException;
import io.github.search5.hg4j.match.Matcher;
import io.github.search5.hg4j.shape.ShardTreeNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * A port of {@code mercurial/narrowspec.py} (and of the part of {@code sparse.py} it reads the file with): the patterns of a narrow
 * clone, their validation and normalization, the file format, and the matcher. Messages are Mercurial's.
 *
 * <p>Patterns are {@code kind:path} strings; only {@code path:} and {@code rootfilesin:} are allowed. Internally the root may be written
 * {@code path:.} (what {@code hg clone} sends for "everything"), which a user may not type.</p>
 */
public final class NarrowSpec {

    /** {@code FILENAME}: in the store. */
    public static final String FILENAME = "narrowspec";
    /** {@code DIRSTATE_FILENAME}: in {@code .hg}, the copy the working copy was updated to. */
    public static final String DIRSTATE_FILENAME = "narrowspec.dirstate";
    /** {@code VALID_PREFIXES}. */
    public static final List<String> VALID_PREFIXES = List.of("path:", "rootfilesin:");

    /** {@code matchmod.allpatternkinds}: what {@code _patsplit} takes for a kind. */
    private static final Set<String> ALL_PATTERN_KINDS = Set.of("re", "glob", "path", "filepath", "relglob", "relpath", "relre", "rootglob",
            "listfile", "listfile0", "set", "include", "subinclude", "rootfilesin");

    private NarrowSpec() {
    }

    /** The includes and excludes of a narrowspec. */
    public record Patterns(Set<String> includes, Set<String> excludes) {
    }

    // ---------------------------------------------------------------- normalizing and validating

    /** {@code _numlines}: the number of lines of {@code s}, counting empty ones at the end. */
    private static int numLines(String s) {
        int count = 1;
        String t = s + "x";
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\n' || c == '\r' && (i + 1 >= t.length() || t.charAt(i + 1) != '\n') || c == '\u000b' || c == '\f' || c == '\u001c'
                    || c == '\u001d' || c == '\u001e' || c == '\u0085' || c == ' ' || c == ' ') {
                count++;
            }
        }
        return count;
    }

    /**
     * {@code _validatepattern}: newlines, surrounding whitespace, {@code .} and {@code ..} components, and empty components (unless the
     * path is empty) are refused.
     */
    public static void validatePattern(String pat) throws HgValidationException {
        if (numLines(pat) > 1) {
            throw new HgValidationException("newlines are not allowed in narrowspec paths");
        }
        if (!pat.strip().equals(pat)) {
            throw new HgValidationException("leading or trailing whitespace is not allowed in narrowspec paths");
        }
        List<String> components = Arrays.asList(pat.split("/", -1));
        if (components.contains(".") || components.contains("..")) {
            throw new HgValidationException("\".\" and \"..\" are not allowed in narrowspec paths");
        }
        if (!pat.isEmpty() && components.contains("")) {
            throw new HgValidationException("empty path components are not allowed in narrowspec paths");
        }
    }

    /** {@code normalizesplitpattern}: the path without trailing slashes, validated. */
    public static String normalizeSplitPattern(String pat) throws HgValidationException {
        int end = pat.length();
        while (end > 0 && pat.charAt(end - 1) == '/') {
            end--;
        }
        String stripped = pat.substring(0, end);
        validatePattern(stripped);
        return stripped;
    }

    /** {@code matchmod._patsplit}: {@code {kind, pattern}}; a prefix that is not a pattern kind belongs to the path. */
    public static String[] patSplit(String pattern, String defaultKind) {
        int colon = pattern.indexOf(':');
        if (colon >= 0) {
            String kind = pattern.substring(0, colon);
            if (ALL_PATTERN_KINDS.contains(kind)) {
                return new String[]{kind, pattern.substring(colon + 1)};
            }
        }
        return new String[]{defaultKind, pattern};
    }

    /** {@code normalizepattern}: the text form with the default kind {@code path} added when there is none. */
    public static String normalizePattern(String pattern) throws HgValidationException {
        String[] split = patSplit(pattern, "path");
        return split[0] + ":" + normalizeSplitPattern(split[1]);
    }

    /**
     * {@code validatepatterns}: for internal data, each pattern has to begin with {@code path:} or {@code rootfilesin:}; nothing else about
     * it is checked (the root is stored as {@code path:.}).
     */
    public static void validatePatterns(Collection<String> patterns) throws HgValidationException {
        for (String pat : patterns) {
            if (VALID_PREFIXES.stream().noneMatch(pat::startsWith)) {
                throw new HgValidationException("invalid prefix on narrow pattern: " + pat
                        + "\n(hint: narrow patterns must begin with one of the following: " + String.join(", ", VALID_PREFIXES) + ")");
            }
        }
    }

    /** {@code parsepatterns}: patterns from a user, normalized and then validated as a set. */
    public static Set<String> parsePatterns(Collection<String> patterns) throws HgValidationException {
        Set<String> result = new LinkedHashSet<>();
        for (String original : patterns) {
            result.add(normalizePattern(original));
        }
        validatePatterns(result);
        return result;
    }

    // ---------------------------------------------------------------- the file

    /**
     * {@code format}: {@code [include]} (when there are includes) with {@code sorted(includes - excludes)}, then {@code [exclude]} (when there
     * are excludes) with the sorted excludes; one pattern per line. Note that the section is written even if every include is also excluded.
     */
    public static String format(Collection<String> includes, Collection<String> excludes) {
        StringBuilder out = new StringBuilder();
        if (!includes.isEmpty()) {
            out.append("[include]\n");
            Set<String> sorted = new TreeSet<>(NarrowSpec::compareBytes);
            sorted.addAll(includes);
            sorted.removeAll(excludes);
            sorted.forEach(i -> out.append(i).append('\n'));
        }
        if (!excludes.isEmpty()) {
            out.append("[exclude]\n");
            Set<String> sorted = new TreeSet<>(NarrowSpec::compareBytes);
            sorted.addAll(excludes);
            sorted.forEach(e -> out.append(e).append('\n'));
        }
        return out.toString();
    }

    private static int compareBytes(String a, String b) {
        return Arrays.compareUnsigned(a.getBytes(java.nio.charset.StandardCharsets.UTF_8), b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * {@code parseconfig}: {@code sparse.parseconfig(..., 'narrow')} then {@code validatepatterns}. Lines are stripped; blank lines and
     * {@code #} comments are skipped; entries need a section; a pattern starting with {@code /} is dropped with a warning; {@code %include}
     * is refused.
     *
     * @param warnings receives {@code warning: narrow profile cannot use paths starting with /, ignoring ...}
     */
    public static Patterns parseConfig(String raw, List<String> warnings) throws HgValidationException {
        Set<String> includes = new LinkedHashSet<>();
        Set<String> excludes = new LinkedHashSet<>();
        boolean profiles = false;
        Set<String> current = null;
        boolean haveSection = false;
        for (String rawLine : raw.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            } else if (line.startsWith("%include ")) {
                if (!line.substring(9).strip().isEmpty()) {
                    profiles = true;
                }
            } else if (line.equals("[include]")) {
                if (haveSection && current != includes) {
                    throw new HgValidationException("narrow config cannot have includes after excludes");
                }
                haveSection = true;
                current = includes;
            } else if (line.equals("[exclude]")) {
                haveSection = true;
                current = excludes;
            } else {
                if (current == null) {
                    throw new HgValidationException("narrow config entry outside of section: " + line
                            + "\n(hint: add an [include] or [exclude] line to declare the entry type)");
                }
                if (line.startsWith("/")) {
                    if (warnings != null) {
                        warnings.add("warning: narrow profile cannot use paths starting with /, ignoring " + line + "\n");
                    }
                    continue;
                }
                current.add(line);
            }
        }
        if (profiles) {
            throw new HgValidationException("including other spec files using '%include' is not supported in narrowspec");
        }
        validatePatterns(includes);
        validatePatterns(excludes);
        return new Patterns(includes, excludes);
    }

    // ---------------------------------------------------------------- patterns of a shape, restrictions

    /** {@code to_legacy_patterns}: plain paths of a shape to {@code path:} patterns, the empty path being {@code path:.}. */
    public static Patterns toLegacyPatterns(Collection<String> includes, Collection<String> excludes) throws HgValidationException {
        Set<String> legacyIncludes = new LinkedHashSet<>();
        includes.forEach(p -> legacyIncludes.add(p.isEmpty() ? "path:." : "path:" + p));
        Set<String> legacyExcludes = new LinkedHashSet<>();
        excludes.forEach(p -> legacyExcludes.add(p.isEmpty() ? "path:." : "path:" + p));
        validatePatterns(legacyIncludes);
        validatePatterns(legacyExcludes);
        return new Patterns(legacyIncludes, legacyExcludes);
    }

    /** {@code restrictpatterns}'s result: includes, excludes, and the requested includes that fall outside the repository's. */
    public record Restricted(Set<String> includes, Set<String> excludes, List<String> invalidIncludes) {
    }

    /** {@code restrictpatterns}: the requested patterns restricted by the repository's (a logical AND). */
    public static Restricted restrictPatterns(Collection<String> reqIncludes, Collection<String> reqExcludes,
                                              Collection<String> repoIncludes, Collection<String> repoExcludes) {
        Set<String> resExcludes = new LinkedHashSet<>(reqExcludes);
        resExcludes.addAll(repoExcludes);
        List<String> invalid = new ArrayList<>();
        Set<String> resIncludes;
        if (reqIncludes.isEmpty()) {
            resIncludes = new LinkedHashSet<>(repoIncludes);
        } else if (!repoIncludes.contains("path:.")) {
            List<String> kept = new ArrayList<>();
            for (String requested : reqIncludes) {
                String include = normpath(requested);
                if (repoIncludes.contains(include)) {
                    kept.add(include);
                    continue;
                }
                boolean valid = false;
                for (String repoInclude : repoIncludes) {
                    if (include.startsWith(repoInclude + "/")) {
                        valid = true;
                        kept.add(include);
                        break;
                    }
                }
                if (!valid) {
                    invalid.add(include);
                }
            }
            if (kept.isEmpty()) {
                resExcludes = new LinkedHashSet<>(List.of("path:."));
                resIncludes = new LinkedHashSet<>();
            } else {
                resIncludes = new LinkedHashSet<>(kept);
            }
        } else {
            resIncludes = new LinkedHashSet<>(reqIncludes);
        }
        return new Restricted(resIncludes, resExcludes, invalid);
    }

    /** {@code util.expandpath(util.normpath(...))} for the paths of a request: collapse {@code //}, {@code /./}; {@code ~} is not expanded. */
    private static String normpath(String path) {
        String normalized = java.nio.file.Path.of(path).normalize().toString();
        return normalized.isEmpty() ? "." : normalized;
    }

    // ---------------------------------------------------------------- matching

    /** {@code narrowspec.match}: nothing without includes; a tree for {@code path:} only specs; otherwise includes minus excludes. */
    public static Matcher match(Collection<String> includes, Collection<String> excludes) throws HgValidationException {
        if (includes.isEmpty()) {
            return io.github.search5.hg4j.match.Matchers.never();
        }
        boolean onlyPaths = includes.stream().allMatch(p -> p.startsWith("path:")) && excludes.stream().allMatch(p -> p.startsWith("path:"));
        if (onlyPaths) {
            Set<String> inc = new LinkedHashSet<>();
            includes.forEach(p -> inc.add(p.substring("path:".length())));
            Set<String> exc = new LinkedHashSet<>();
            excludes.forEach(p -> exc.add(p.substring("path:".length())));
            return new io.github.search5.hg4j.shape.ShapeMatcher(ShardTreeNode.fromPatterns(inc, exc));
        }
        // the old reading ({@code matchmod.match(root, '', [], include=..., exclude=...)}): includes minus excludes
        List<String[]> inc = new ArrayList<>();
        includes.forEach(p -> inc.add(splitKind(p)));
        List<String[]> exc = new ArrayList<>();
        excludes.forEach(p -> exc.add(splitKind(p)));
        return new Matcher() {
            @Override
            public boolean matches(String path) {
                for (String[] e : exc) {
                    if (kindMatches(e, path)) {
                        return false;
                    }
                }
                for (String[] i : inc) {
                    if (kindMatches(i, path)) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    private static String[] splitKind(String pattern) {
        int colon = pattern.indexOf(':');
        String path = pattern.substring(colon + 1);
        return new String[]{pattern.substring(0, colon), path.equals(".") ? "" : path};
    }

    /** {@code path:} matches the directory and what is under it ({@code path:} of nothing is everything); {@code rootfilesin:} its direct files. */
    private static boolean kindMatches(String[] pattern, String path) {
        String dir = pattern[1];
        if (pattern[0].equals("rootfilesin")) {
            if (dir.isEmpty()) {
                return !path.contains("/");
            }
            if (!path.startsWith(dir + "/")) {
                return false;
            }
            String rest = path.substring(dir.length() + 1);
            return !rest.isEmpty() && !rest.contains("/");
        }
        return dir.isEmpty() || path.equals(dir) || path.startsWith(dir + "/");
    }

    /** True when the working copy's narrowspec differs from the store's: {@code checkworkingcopynarrowspec}'s condition. */
    public static boolean isWorkingCopyStale(Patterns store, Patterns workingCopy) {
        return !store.includes().equals(workingCopy.includes()) || !store.excludes().equals(workingCopy.excludes());
    }

    /** {@code checkworkingcopynarrowspec}'s message. */
    public static final String STALE_MESSAGE = "working copy's narrowspec is stale\n(hint: run 'hg tracked --update-working-copy')";
}
