package io.github.search5.hg4j.api;

import io.github.search5.hg4j.fileset.Fileset;
import io.github.search5.hg4j.fileset.RepositoryFilesetContext;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.match.Matcher;
import io.github.search5.hg4j.match.Matchers;
import io.github.search5.hg4j.treewalk.HgTreeFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Which files a command works on, as the command line of Mercurial gives it: patterns (a file name, or {@code kind:pattern}, and
 * {@code set:EXPRESSION}; a bare name is a path relative to the current directory), {@code -I} include patterns (a bare pattern is a
 * glob), {@code -X} exclude patterns, and the current directory relative to the repository root.
 */
public final class FileSelection {

    private final List<String> patterns = new ArrayList<>();
    private final List<String> include = new ArrayList<>();
    private final List<String> exclude = new ArrayList<>();
    private String cwd = "";
    private String defaultKind = "relpath";
    private Consumer<String> warn;
    private Matcher fixed;

    /**
     * A selection that is exactly this matcher (what {@code subdirmatcher} gives a subrepository: the parent's matcher seen from
     * inside the subrepository). It is never empty, and patterns, {@code -I} and {@code -X} are not consulted.
     */
    public static FileSelection of(Matcher matcher) {
        FileSelection selection = new FileSelection();
        selection.fixed = matcher;
        return selection;
    }

    public FileSelection patterns(String... patterns) {
        this.patterns.clear();
        if (patterns != null) {
            this.patterns.addAll(List.of(patterns));
        }
        return this;
    }

    public FileSelection include(String... patterns) {
        this.include.clear();
        if (patterns != null) {
            this.include.addAll(List.of(patterns));
        }
        return this;
    }

    public FileSelection exclude(String... patterns) {
        this.exclude.clear();
        if (patterns != null) {
            this.exclude.addAll(List.of(patterns));
        }
        return this;
    }

    /** The kind of a pattern without a prefix: {@code relpath} for most commands, {@code relglob} for {@code locate}. */
    public FileSelection defaultKind(String kind) {
        this.defaultKind = kind;
        return this;
    }

    /** The current directory, relative to the repository root ({@code ""} for the root). */
    public FileSelection cwd(String cwd) {
        this.cwd = cwd == null ? "" : cwd;
        return this;
    }

    /** Receives the warnings of unreadable pattern files. */
    public FileSelection warn(Consumer<String> warn) {
        this.warn = warn;
        return this;
    }

    /** How many patterns were given as arguments (not counting {@code -I} and {@code -X}). */
    public int patternCount() {
        return fixed != null ? Math.max(1, fixed.files().size()) : patterns.size();
    }

    /** True if nothing narrows the selection (every file is selected). */
    public boolean isEmpty() {
        return fixed == null && patterns.isEmpty() && include.isEmpty() && exclude.isEmpty();
    }

    /**
     * The matcher. {@code revision} is the revision {@code set:} patterns are evaluated against ({@code null} for the working
     * directory).
     */
    public Matcher matcher(HgRepository repository, String revision) throws IOException {
        if (fixed != null) {
            return fixed;
        }
        RepositoryFilesetContext ctx = new RepositoryFilesetContext(repository, revision);
        return Matchers.match(repository.getDirectory(), cwd, patterns, include, exclude, defaultKind,
                (dir, expression) -> Fileset.compile(ctx, dir, expression), warn);
    }

    /** The matcher as a tree filter, for the commands that take one. */
    public HgTreeFilter treeFilter(HgRepository repository, String revision) throws IOException {
        if (isEmpty()) {
            return HgTreeFilter.ALL;
        }
        Matcher matcher = matcher(repository, revision);
        return new HgTreeFilter() {
            @Override
            public boolean accept(String path) {
                return matcher.matches(path);
            }
        };
    }
}
