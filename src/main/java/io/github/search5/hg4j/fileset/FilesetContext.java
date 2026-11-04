package io.github.search5.hg4j.fileset;

import io.github.search5.hg4j.match.Matcher;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * What a fileset is evaluated against: Mercurial's context (a revision or the working directory). The predicates ask it for the
 * files, their flags, sizes and contents, and the status.
 */
public interface FilesetContext {

    /** The repository root, against which patterns are rooted. */
    File root();

    /** The extensions that contribute predicates (for example {@code lfs}). */
    default Set<String> extensions() {
        return Set.of();
    }

    /** Whether the file is tracked in this context ({@code ctx.__contains__}). */
    boolean contains(String path) throws IOException;

    /** True for the working directory, false for a revision. */
    default boolean isWorkingDirectory() {
        return false;
    }

    /** {@code ctx.flags(path)}: {@code "x"} executable, {@code "l"} symlink, {@code ""} neither (or not in the context). */
    default String flags(String path) throws IOException {
        throw new UnsupportedOperationException("flags");
    }

    /** The content of a file, or {@code null} if the context has no such file. */
    default byte[] data(String path) throws IOException {
        throw new UnsupportedOperationException("data");
    }

    /** The size of a file, or -1 if the context has no such file. */
    default long size(String path) throws IOException {
        throw new UnsupportedOperationException("size");
    }

    /** The file this one is recorded as a copy of, or {@code null}. */
    default String copySource(String path) throws IOException {
        throw new UnsupportedOperationException("copySource");
    }

    /** The first parent as a context (an empty context for the first revision). */
    default FilesetContext p1() throws IOException {
        throw new UnsupportedOperationException("p1");
    }

    /**
     * The status of {@code target} compared with this context (the base), limited to what {@code match} selects
     * ({@code base.status(ctx, match, listignored, listclean, listunknown)}).
     */
    default FileStatus status(FilesetContext target, Matcher match, boolean listIgnored, boolean listClean, boolean listUnknown)
            throws IOException {
        throw new UnsupportedOperationException("status");
    }

    /** The revisions a revision set expression selects, in the order of the set. */
    default List<FilesetContext> revisions(String revset) throws IOException {
        throw new UnsupportedOperationException("revisions");
    }

    /** The merge state of a file in the working directory: {@code "r"} resolved, {@code "u"} unresolved, or {@code null}. */
    default String mergeState(String path) throws IOException {
        return null;
    }

    /** The matcher of the active {@code .hgignore} (and {@code ui.ignore}) patterns. */
    default Matcher ignoreMatcher() throws IOException {
        throw new UnsupportedOperationException("ignoreMatcher");
    }

    /** The paths of the subrepositories ({@code ctx.substate}). */
    default Set<String> subrepos() throws IOException {
        return Set.of();
    }

    /** The files stored in LFS (the {@code lfs()} predicate); empty for the working directory. */
    default Set<String> lfsFiles() throws IOException {
        return Set.of();
    }
}
