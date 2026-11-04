package io.github.search5.hg4j.api;

import io.github.search5.hg4j.dirstate.Dirstate;
import io.github.search5.hg4j.errors.HgRevisionNotFoundException;
import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.lib.NodeId;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.treewalk.HgTreeFilter;
import io.github.search5.hg4j.treewalk.SparsePathFilter;
import io.github.search5.hg4j.util.NodeIdUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Porcelain command mirroring {@code hg files}: lists tracked file paths (relative to
 * the repository root, forward-slash separated) that match an optional glob/path
 * pattern, at a given revision.
 * <p>
 * With no revision set, the listing reflects the current working copy's tracked set
 * as recorded in the dirstate (added-but-uncommitted files are included, files marked
 * removed are excluded) &mdash; matching real {@code hg files}' default behavior of
 * describing the working copy rather than literally the tip commit. With a revision
 * set (via {@link #setRevision(String)} / {@link #setRevision(NodeId)}), the listing
 * reflects that historical commit's manifest instead.
 */
public class FilesCommand {

    private final HgRepository repository;
    private String revision;
    private HgTreeFilter treeFilter = HgTreeFilter.ALL;

    public FilesCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Sets the revision to list files at (revision number, hex node id/prefix, or
     * {@code "tip"}). When unset (the default), the working copy's tracked set
     * (dirstate) is used instead of any specific commit.
     */
    public FilesCommand setRevision(String revision) {
        this.revision = revision;
        return this;
    }

    public FilesCommand setRevision(NodeId nodeId) {
        this.revision = nodeId != null ? nodeId.toHex() : null;
        return this;
    }

    /**
     * Sets an explicit tree filter used to match paths, e.g. one built via
     * {@link HgTreeFilter#createPathPrefixFilter} or wrapping a {@link SparsePathFilter}.
     */
    public FilesCommand setTreeFilter(HgTreeFilter treeFilter) {
        this.treeFilter = treeFilter != null ? treeFilter : HgTreeFilter.ALL;
        return this;
    }

    /**
     * Convenience setter matching {@code hg files <pattern>}: accepts one or more glob
     * patterns and matches them the same way {@link SparsePathFilter} already does
     * (including its directory-prefix semantics, where a pattern that names a directory
     * matches everything below it).
     */
    public FilesCommand setPattern(String... globPatterns) {
        if (globPatterns != null && globPatterns.length == 1 && globPatterns[0] != null && globPatterns[0].startsWith("set:")) {
            this.fileset = globPatterns[0].substring(4);
            return this;
        }
        this.treeFilter = HgTreeFilter.fromPathFilter(new SparsePathFilter(globPatterns));
        return this;
    }

    /**
     * Restricts the listing to the files a fileset expression selects, like {@code hg files "set:EXPR"}. The supported
     * expression is {@link io.github.search5.hg4j.lfs.MiniFileset}'s (patterns {@code **.ext} and {@code path:},
     * {@code all()}, {@code none()}, {@code size()}, {@code and}/{@code or}/{@code not}) plus {@code lfs()}, the files stored in LFS.
     * A pattern given to {@link #setPattern} as {@code set:EXPR} means the same.
     */
    public FilesCommand setFileset(String expression) {
        this.fileset = expression;
        return this;
    }

    private String fileset;
    private final FileSelection selection = new FileSelection();

    /**
     * The files to list, given as {@code hg files [OPTION]... [FILE]...} takes them: patterns ({@code relpath} by default),
     * {@code kind:pattern} and {@code set:EXPRESSION}.
     */
    public FilesCommand setPatterns(String... patterns) {
        selection.patterns(patterns);
        return this;
    }

    /** {@code hg files -I}: only the files matching these patterns (globs by default). */
    public FilesCommand setInclude(String... patterns) {
        selection.include(patterns);
        return this;
    }

    /** {@code hg files -X}: not the files matching these patterns (globs by default). */
    public FilesCommand setExclude(String... patterns) {
        selection.exclude(patterns);
        return this;
    }

    /** The current directory relative to the repository root, against which relative patterns are read. */
    public FilesCommand setCwd(String cwd) {
        selection.cwd(cwd);
        return this;
    }

    public List<String> call() throws IOException {
        List<String> paths;

        if (revision == null) {
            paths = listWorkingCopyTrackedFiles();
        } else {
            paths = listRevisionFiles();
        }

        if (treeFilter != null && treeFilter != HgTreeFilter.ALL) {
            List<String> filtered = new ArrayList<>();
            for (String path : paths) {
                if (treeFilter.accept(path)) {
                    filtered.add(path);
                }
            }
            paths = filtered;
        }

        if (fileset != null) {
            paths = applyFileset(paths);
        }

        if (!selection.isEmpty()) {
            io.github.search5.hg4j.match.Matcher matcher = selection.matcher(repository, revision == null ? null : revision);
            List<String> kept = new ArrayList<>();
            for (String path : paths) {
                if (matcher.matches(path)) {
                    kept.add(path);
                }
            }
            paths = kept;
        }

        paths.sort(NodeIdUtil.UTF8_STRING_COMPARATOR);
        return paths;
    }

    private List<String> applyFileset(List<String> paths) throws IOException {
        io.github.search5.hg4j.fileset.RepositoryFilesetContext ctx =
                new io.github.search5.hg4j.fileset.RepositoryFilesetContext(repository, revision);
        io.github.search5.hg4j.match.Matcher matcher = io.github.search5.hg4j.fileset.Fileset.compile(ctx, "", fileset);
        List<String> kept = new ArrayList<>();
        for (String path : paths) {
            if (matcher.matches(path)) {
                kept.add(path);
            }
        }
        return kept;
    }

    private List<String> listWorkingCopyTrackedFiles() throws IOException {
        Dirstate dirstate = repository.getDirstate();
        Map<String, Dirstate.Entry> tracked = dirstate.getEntries();

        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Dirstate.Entry> entry : tracked.entrySet()) {
            if (entry.getValue().getState() != 'r') {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    private List<String> listRevisionFiles() throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        if (!clIdx.exists()) {
            return new ArrayList<>();
        }

        Revlog changelog = repository.getRevlog(clIdx, clDat);
        if (changelog.getRevisionCount() == 0) {
            // Mirrors real hg: on a repo with no commits yet, any revision reference
            // (including "0" or "tip") resolves to the null revision rather than
            // aborting, and simply yields no files.
            return new ArrayList<>();
        }

        byte[] targetNodeId = NodeIdUtil.resolveRevision(changelog, revision);
        if (targetNodeId == null) {
            throw new HgRevisionNotFoundException("Unable to resolve revision: " + revision);
        }

        Map<String, String> manifestMap = repository.getManifestAtCommit(targetNodeId);
        return new ArrayList<>(manifestMap.keySet());
    }
}
