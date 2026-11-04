package io.github.search5.hg4j.fileset;

import java.util.Set;

/** Mercurial's {@code scmutil.status}: the files of each state of a context compared with a base. */
public record FileStatus(Set<String> modified, Set<String> added, Set<String> removed, Set<String> deleted, Set<String> unknown,
                         Set<String> ignored, Set<String> clean) {

    public static final FileStatus EMPTY = new FileStatus(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
}
