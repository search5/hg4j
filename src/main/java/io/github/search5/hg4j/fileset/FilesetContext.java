package io.github.search5.hg4j.fileset;

import java.io.File;
import java.io.IOException;
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
}
