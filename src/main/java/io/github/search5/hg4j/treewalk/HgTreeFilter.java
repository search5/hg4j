package io.github.search5.hg4j.treewalk;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.treewalk.PathFilter;

/**
 * Filter interface for pruning and matching file paths during SCM tree walks.
 * Inspired by JGit's TreeFilter api to support narrow/sparse operations.
 */
public abstract class HgTreeFilter implements PathFilter {

    /**
     * Creates a bridge filter that wraps a generic PathFilter.
     */
    public static HgTreeFilter fromPathFilter(final PathFilter pathFilter) {
        if (pathFilter == null) {
            return ALL;
        }
        if (pathFilter instanceof HgTreeFilter) {
            return (HgTreeFilter) pathFilter;
        }
        return new HgTreeFilter() {
            @Override
            public boolean accept(String path) {
                return pathFilter.accept(path);
            }
        };
    }

    /**
     * Determines whether the specified path matches the filter criteria.
     *
     * @param path relative file path from repository root
     * @return true if the path is matched and should be traversed
     */
    public abstract boolean accept(String path);

    /**
     * Default filter that accepts all paths.
     */
    public static final HgTreeFilter ALL = new HgTreeFilter() {
        @Override
        public boolean accept(String path) {
            return true;
        }
    };

    /**
     * Creates a filter that matches only paths matching specific prefix rules.
     * Useful for sparse checkout or narrow clone scenarios.
     */
    public static HgTreeFilter createPathPrefixFilter(Collection<String> includePrefixes, Collection<String> excludePrefixes) {
        final Set<String> includes = includePrefixes != null ? new HashSet<>(includePrefixes) : Set.of();
        final Set<String> excludes = excludePrefixes != null ? new HashSet<>(excludePrefixes) : Set.of();

        return new HgTreeFilter() {
            @Override
            public boolean accept(String path) {
                if (path == null) return false;
                
                // Exclude matches first
                for (String ex : excludes) {
                    if (path.startsWith(ex)) {
                        return false;
                    }
                }

                // If no includes are specified, accept all (except those excluded)
                if (includes.isEmpty()) {
                    return true;
                }

                // Check include matches
                for (String inc : includes) {
                    if (path.startsWith(inc)) {
                        return true;
                    }
                }

                return false;
            }
        };
    }

    /**
     * A single normalized narrowspec pattern, mirroring real hg's
     * {@code mercurial/narrowspec.py} data model: a validated {@code kind} (only
     * {@code "path"} or {@code "rootfilesin"} are legal on-disk) plus the POSIX-style
     * path with any trailing {@code "/"} stripped.
     */
    public static final class NarrowPattern {
        public final String kind;
        public final String path;

        private NarrowPattern(String kind, String path) {
            this.kind = kind;
            this.path = path;
        }

        /** Renders back to the {@code "kind:path"} textual form stored in narrowspec files. */
        public String toSpecString() {
            return kind + ":" + path;
        }

        @Override
        public String toString() {
            return toSpecString();
        }
    }

    /**
     * Normalizes and validates a single user-supplied narrow pattern exactly like real hg's
     * {@code narrowspec.normalizepattern()}/{@code _validatepattern()}: defaults to the
     * {@code path:} kind when no recognized prefix is present, strips a single trailing
     * {@code "/"}, and rejects patterns real hg would abort on (an unsupported kind prefix
     * such as {@code glob:}/{@code re:}, embedded {@code "."}/{@code ".."} components, empty
     * path components, or leading/trailing whitespace).
     *
     * @throws IllegalArgumentException if the pattern is not a legal narrowspec pattern
     */
    public static NarrowPattern normalizeNarrowPattern(String pattern) {
        if (pattern == null) {
            throw new IllegalArgumentException("Narrow pattern cannot be null");
        }
        String kind;
        String pat;
        if (pattern.startsWith("path:")) {
            kind = "path";
            pat = pattern.substring("path:".length());
        } else if (pattern.startsWith("rootfilesin:")) {
            kind = "rootfilesin";
            pat = pattern.substring("rootfilesin:".length());
        } else {
            int colon = pattern.indexOf(':');
            if (colon > 0 && isLikelyKindPrefix(pattern.substring(0, colon))) {
                throw new IllegalArgumentException(
                        "invalid prefix on narrow pattern: " + pattern
                                + " (narrow patterns must begin with one of: path:, rootfilesin:)");
            }
            kind = "path";
            pat = pattern;
        }

        while (pat.endsWith("/")) {
            pat = pat.substring(0, pat.length() - 1);
        }

        if (!pat.equals(pat.strip())) {
            throw new IllegalArgumentException(
                    "leading or trailing whitespace is not allowed in narrowspec paths: " + pattern);
        }
        if (pat.contains("\n")) {
            throw new IllegalArgumentException("newlines are not allowed in narrowspec paths");
        }
        if (!pat.isEmpty()) {
            for (String component : pat.split("/", -1)) {
                if (component.isEmpty()) {
                    throw new IllegalArgumentException(
                            "empty path components are not allowed in narrowspec paths: " + pattern);
                }
                if (component.equals(".") || component.equals("..")) {
                    throw new IllegalArgumentException(
                            "\".\" and \"..\" are not allowed in narrowspec paths: " + pattern);
                }
            }
        }

        return new NarrowPattern(kind, pat);
    }

    private static boolean isLikelyKindPrefix(String candidate) {
        // A conservative allowlist of prefixes real hg (or its sparse/fileset machinery) would
        // recognize as an explicit pattern kind. Anything else (e.g. a Windows drive letter, or
        // a bare directory name that happens to contain ':') is treated as a plain path.
        switch (candidate) {
            case "path":
            case "rootfilesin":
            case "glob":
            case "re":
            case "relglob":
            case "relpath":
            case "relre":
            case "rootglob":
            case "set":
            case "include":
            case "subinclude":
                return true;
            default:
                return false;
        }
    }

    /**
     * Builds a matcher that reproduces real hg's {@code mercurial/narrowspec.py} matching
     * semantics exactly (verified against hg 7.2's {@code narrow} extension), rather than the
     * simplified/generic prefix semantics of {@link #createPathPrefixFilter}:
     * <ul>
     *   <li>Patterns must already be normalized (see {@link #normalizeNarrowPattern}) -- only the
     *       {@code path:} and {@code rootfilesin:} kinds are honored.</li>
     *   <li>{@code path:foo} matches {@code foo} itself and anything under the {@code foo/}
     *       directory (component-boundary aware: it does NOT match a sibling like
     *       {@code foobar/baz}), matching real hg byte-for-byte.</li>
     *   <li>{@code path:} (empty path, i.e. the repo root / {@code path:.}) matches every path.</li>
     *   <li>{@code rootfilesin:foo} matches only files directly inside {@code foo/} -- not nested
     *       subdirectories, and not {@code foo} itself.</li>
     *   <li>Excludes always win over includes.</li>
     *   <li>If no include patterns are given at all, real hg's {@code narrowspec.match()} returns
     *       its "never" matcher -- so, matching that, this returns a filter that accepts nothing.
     *       (This differs deliberately from {@link #createPathPrefixFilter}'s generic
     *       "no includes means accept everything" default, which exists for non-narrow callers.)</li>
     * </ul>
     */
    public static HgTreeFilter createNarrowSpecFilter(Collection<NarrowPattern> includes, Collection<NarrowPattern> excludes) {
        final List<NarrowPattern> inc = includes != null ? new ArrayList<>(includes) : List.of();
        final List<NarrowPattern> exc = excludes != null ? new ArrayList<>(excludes) : List.of();
        return new NarrowSpecFilter(inc, exc);
    }

    /**
     * Concrete narrowspec-backed filter returned by {@link #createNarrowSpecFilter}. Unlike the
     * anonymous {@link HgTreeFilter} instances elsewhere in this class, this one keeps its
     * {@code include}/{@code exclude} {@link NarrowPattern} lists around (not just the compiled
     * predicate) so wire-protocol callers -- {@link io.github.search5.hg4j.api.FetchCommand},
     * specifically -- can recover the original narrowspec patterns and forward them to a real hg
     * server's {@code getbundle} {@code includepats}/{@code excludepats} wire arguments (backlog
     * item 40: genuine wire-protocol-level narrow clone, negotiating actual server-side filelog
     * filtering instead of always fetching the full changegroup and discarding out-of-scope
     * content locally after the fact).
     */
    public static final class NarrowSpecFilter extends HgTreeFilter {
        private final List<NarrowPattern> includes;
        private final List<NarrowPattern> excludes;

        private NarrowSpecFilter(List<NarrowPattern> includes, List<NarrowPattern> excludes) {
            this.includes = includes;
            this.excludes = excludes;
        }

        /** The narrowspec's include patterns, exactly as normalized at narrow-clone time. */
        public List<NarrowPattern> getIncludes() {
            return includes;
        }

        /** The narrowspec's exclude patterns, exactly as normalized at narrow-clone time. */
        public List<NarrowPattern> getExcludes() {
            return excludes;
        }

        @Override
        public boolean accept(String path) {
            if (path == null) {
                return false;
            }
            String normalized = path;
            while (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }

            for (NarrowPattern ex : excludes) {
                if (matchesPattern(ex, normalized)) {
                    return false;
                }
            }
            if (includes.isEmpty()) {
                return false;
            }
            for (NarrowPattern in : includes) {
                if (matchesPattern(in, normalized)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Backlog 30 (narrow clone wire-level re-integration): rebuilds the same matcher
     * {@link #createNarrowSpecFilter} built at narrow-clone time, from the narrowspec real hg
     * itself stores on disk ({@code .hg/store/narrowspec}, written by {@code NarrowCloneCommand}
     * -- see its {@code formatNarrowSpec}, whose {@code "[include]"}/{@code "[exclude]"} format
     * this parses back). {@link io.github.search5.hg4j.api.PullCommand}/
     * {@link io.github.search5.hg4j.api.FetchCommand}/
     * {@link io.github.search5.hg4j.api.UpdateCommand} call this so a narrow clone's scope is
     * automatically honored on every later {@code pull}/{@code update} too, not just at the
     * initial {@code NarrowCloneCommand} call site -- without callers having to remember to pass
     * the same filter by hand every time. Returns {@link #ALL} when the repository has no stored
     * narrowspec (not a narrow clone), so callers can apply this result unconditionally.
     *
     * <p>Backlog item 40: the returned filter now also doubles as the source of the
     * {@code includepats}/{@code excludepats} wire arguments {@link
     * io.github.search5.hg4j.api.FetchCommand} negotiates with a narrow-capable remote (real hg's
     * {@code exp-narrow-1} capability), so that a subsequent {@code pull} -- not just the initial
     * {@code NarrowCloneCommand} clone -- also gets a genuinely filelog-filtered changegroup from
     * the server instead of a full one filtered locally after the fact.
     */
    public static HgTreeFilter loadFromRepository(HgRepository repository) throws IOException {
        File narrowSpecFile = new File(repository.getStoreDir(), "narrowspec");
        if (!narrowSpecFile.exists()) {
            return ALL;
        }
        List<NarrowPattern> includes = new ArrayList<>();
        List<NarrowPattern> excludes = new ArrayList<>();
        List<NarrowPattern> current = null;
        String raw = Files.readString(narrowSpecFile.toPath(), StandardCharsets.UTF_8);
        for (String rawLine : raw.split("\n", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.equals("[include]")) {
                current = includes;
            } else if (line.equals("[exclude]")) {
                current = excludes;
            } else if (current != null) {
                current.add(normalizeNarrowPattern(line));
            }
        }
        return createNarrowSpecFilter(includes, excludes);
    }

    private static boolean matchesPattern(NarrowPattern pattern, String path) {
        if ("rootfilesin".equals(pattern.kind)) {
            if (pattern.path.isEmpty()) {
                return !path.contains("/");
            }
            if (!path.startsWith(pattern.path + "/")) {
                return false;
            }
            String remainder = path.substring(pattern.path.length() + 1);
            return !remainder.isEmpty() && !remainder.contains("/");
        }
        // "path" kind (the default).
        if (pattern.path.isEmpty()) {
            return true; // path:. / path: matches the whole tree
        }
        return path.equals(pattern.path) || path.startsWith(pattern.path + "/");
    }
}
