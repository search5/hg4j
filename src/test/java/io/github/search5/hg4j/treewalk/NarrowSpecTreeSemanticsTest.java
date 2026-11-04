package io.github.search5.hg4j.treewalk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mercurial 7.2 reads a narrowspec made of {@code path:} patterns as a tree ({@code narrowspec.match} -> {@code shard_tree_matcher}): the
 * innermost pattern that covers a file decides, so an include inside an exclude inside an include is included. A {@code rootfilesin:}
 * pattern keeps the old "excludes win" reading.
 */
class NarrowSpecTreeSemanticsTest {

    private static HgTreeFilter filter(List<String> includes, List<String> excludes) {
        return HgTreeFilter.createNarrowSpecFilter(includes.stream().map(HgTreeFilter::normalizeNarrowPattern).toList(),
                excludes.stream().map(HgTreeFilter::normalizeNarrowPattern).toList());
    }

    @Test
    void anIncludeInsideAnExcludeInsideAnIncludeIsIncluded() {
        HgTreeFilter f = filter(List.of("path:a", "path:a/b/c"), List.of("path:a/b"));
        assertTrue(f.accept("a/x"));
        assertFalse(f.accept("a/b/x"));
        assertTrue(f.accept("a/b/c/x"));
        assertTrue(f.accept("a/b/c"));
        assertFalse(f.accept("other"));
        assertFalse(f.accept("ab/c"), "only whole directories");
    }

    @Test
    void anExcludeOfTheRootWithIncludesInsideKeepsTheIncludes() {
        HgTreeFilter f = filter(List.of("path:foo", "path:.hgtags"), List.of("path:"));
        assertTrue(f.accept("foo/x"));
        assertTrue(f.accept(".hgtags"));
        assertFalse(f.accept("bar/x"));
        assertFalse(f.accept("top.txt"));
    }

    @Test
    void theRootIncludedWithExcludes() {
        HgTreeFilter f = filter(List.of("path:"), List.of("path:private"));
        assertTrue(f.accept("anything"));
        assertFalse(f.accept("private/secret"));
    }

    @Test
    void noIncludesMatchesNothing() {
        assertFalse(filter(List.of(), List.of("path:x")).accept("y"));
        assertFalse(filter(List.of(), List.of()).accept("y"));
    }

    @Test
    void aRootFilesInPatternKeepsTheOldReading() {
        HgTreeFilter f = filter(List.of("path:a", "rootfilesin:b"), List.of("path:a/x"));
        assertTrue(f.accept("a/y"));
        assertFalse(f.accept("a/x/z"));
        assertTrue(f.accept("b/file"));
        assertFalse(f.accept("b/sub/file"));
    }
}
