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
        /**
         * Pattern kind; either {@code "path"} or {@code "rootfilesin"}.
         */
        public final String kind;
        /**
         * POSIX-style path with any trailing {@code "/"} removed.
         */
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
        try {
            String normalized = io.github.search5.hg4j.narrow.NarrowSpec.normalizePattern(pattern);
            io.github.search5.hg4j.narrow.NarrowSpec.validatePatterns(java.util.List.of(normalized)); // parsepatterns: only path: / rootfilesin:
            return internalNarrowPattern(normalized);
        } catch (io.github.search5.hg4j.errors.HgValidationException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /**
     * A pattern of the internal representation ({@code narrowspec.validatepatterns}): only the prefix is checked, so the root can be
     * {@code path:.} as a clone sends it and the narrowspec file may hold it.
     */
    public static NarrowPattern internalNarrowPattern(String spec) {
        try {
            io.github.search5.hg4j.narrow.NarrowSpec.validatePatterns(java.util.List.of(spec));
        } catch (io.github.search5.hg4j.errors.HgValidationException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        int colon = spec.indexOf(':');
        return new NarrowPattern(spec.substring(0, colon), spec.substring(colon + 1));
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

        /**
         * {@code narrowspec.match}: when every pattern is a {@code path:} one the spec is read as a tree, so a path included inside an
         * excluded directory that is itself inside an included one is included ({@code shard_tree_matcher}); {@code null} for the old
         * "excludes win" reading, used when a {@code rootfilesin:} pattern is present.
         */
        private final io.github.search5.hg4j.shape.ShardTreeNode tree;

        private NarrowSpecFilter(List<NarrowPattern> includes, List<NarrowPattern> excludes) {
            this.includes = includes;
            this.excludes = excludes;
            io.github.search5.hg4j.shape.ShardTreeNode built = null;
            boolean onlyPaths = includes.stream().allMatch(p -> "path".equals(p.kind)) && excludes.stream().allMatch(p -> "path".equals(p.kind));
            if (onlyPaths && !includes.isEmpty()) {
                try {
                    // narrowspec.match -> shard_tree_matcher: the paths as they are, "." being the root
                    built = io.github.search5.hg4j.shape.ShardTreeNode.fromPatterns(
                            includes.stream().map(p -> p.path).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)),
                            excludes.stream().map(p -> p.path).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new)));
                } catch (io.github.search5.hg4j.errors.HgValidationException invalid) {
                    built = null; // a pattern the tree cannot hold: keep the old reading
                }
            }
            this.tree = built;
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

            if (tree != null) {
                return tree.deepestPrefixNode(normalized).included();
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
        // narrowspec.load -> parseconfig: sparse.parseconfig then validatepatterns
        io.github.search5.hg4j.narrow.NarrowSpec.Patterns parsed;
        try {
            parsed = io.github.search5.hg4j.narrow.NarrowSpec.parseConfig(Files.readString(narrowSpecFile.toPath(), StandardCharsets.UTF_8), null);
        } catch (io.github.search5.hg4j.errors.HgValidationException e) {
            throw new IOException(e.getMessage(), e);
        }
        parsed.includes().forEach(p -> includes.add(internalNarrowPattern(p)));
        parsed.excludes().forEach(p -> excludes.add(internalNarrowPattern(p)));
        return createNarrowSpecFilter(includes, excludes);
    }

    /**
     * {@code localrepo._narrowmatch}: the narrowspec as {@link #loadFromRepository} reads it, after {@code checkworkingcopynarrowspec}: the
     * copy the working copy was updated to ({@code .hg/narrowspec.dirstate}, empty when missing) must be the store's.
     *
     * @throws IOException {@code working copy's narrowspec is stale} with the hint to run {@code hg tracked --update-working-copy}
     */
    public static HgTreeFilter loadForWorkingCopy(HgRepository repository) throws IOException {
        File storeFile = new File(repository.getStoreDir(), io.github.search5.hg4j.narrow.NarrowSpec.FILENAME);
        if (storeFile.exists()) {
            try {
                io.github.search5.hg4j.narrow.NarrowSpec.Patterns store = io.github.search5.hg4j.narrow.NarrowSpec.parseConfig(
                        Files.readString(storeFile.toPath(), StandardCharsets.UTF_8), null);
                File copy = new File(repository.getHgDir(), io.github.search5.hg4j.narrow.NarrowSpec.DIRSTATE_FILENAME);
                io.github.search5.hg4j.narrow.NarrowSpec.Patterns working = io.github.search5.hg4j.narrow.NarrowSpec.parseConfig(
                        copy.exists() ? Files.readString(copy.toPath(), StandardCharsets.UTF_8) : "", null);
                if (io.github.search5.hg4j.narrow.NarrowSpec.isWorkingCopyStale(store, working)) {
                    throw new io.github.search5.hg4j.errors.HgValidationException(io.github.search5.hg4j.narrow.NarrowSpec.STALE_MESSAGE);
                }
            } catch (io.github.search5.hg4j.errors.HgValidationException e) {
                throw new IOException(e.getMessage(), e);
            }
        }
        return loadFromRepository(repository);
    }

    private static boolean matchesPattern(NarrowPattern pattern, String path) {
        if (pattern.path.equals(".")) {
            pattern = new NarrowPattern(pattern.kind, ""); // path:. is the root
        }
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
