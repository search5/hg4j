package io.github.search5.hg4j.treewalk;

import org.junit.jupiter.api.Test;

import java.util.List;

import io.github.search5.hg4j.treewalk.PathFilter;
import io.github.search5.hg4j.treewalk.HgTreeFilter.NarrowPattern;

import static org.junit.jupiter.api.Assertions.*;

public class HgTreeFilterTest {

    @Test
    public void testTreeFilterAllAcceptsEverything() {
        HgTreeFilter filter = HgTreeFilter.ALL;
        assertTrue(filter.accept("src/main/java/Main.java"));
        assertTrue(filter.accept("libs/external.jar"));
        assertTrue(filter.accept(null));
    }

    @Test
    public void testPathPrefixFilterLogic() {
        // Includes only "src/" and "libs/", excludes "src/test/"
        HgTreeFilter filter = HgTreeFilter.createPathPrefixFilter(
                List.of("src/", "libs/"),
                List.of("src/test/")
        );

        // Path matches include prefix
        assertTrue(filter.accept("src/main/java/Main.java"));
        assertTrue(filter.accept("libs/external.jar"));

        // Path matches exclude prefix
        assertFalse(filter.accept("src/test/java/Test.java"));

        // Path matches neither
        assertFalse(filter.accept("docs/readme.txt"));
        
        // Null checks
        assertFalse(filter.accept(null));
    }

    @Test
    public void testPathPrefixFilterEmptyIncludesAcceptsAllExceptExcludes() {
        HgTreeFilter filter = HgTreeFilter.createPathPrefixFilter(
                List.of(), // Empty includes means accept all by default
                List.of("temp/")
        );

        assertTrue(filter.accept("src/main/java/Main.java"));
        assertFalse(filter.accept("temp/cache.tmp"));
    }

    @Test
    public void testPathPrefixFilterNullCollectionsTreatedAsEmpty() {
        // Passing null for both includes and excludes should behave like empty
        // collections (accept everything), exercising the null branches of the
        // ternaries that build the internal include/exclude sets.
        HgTreeFilter filter = HgTreeFilter.createPathPrefixFilter(null, null);

        assertTrue(filter.accept("src/main/java/Main.java"));
        assertTrue(filter.accept("anything/else.txt"));
        assertFalse(filter.accept(null));
    }

    @Test
    public void testFromPathFilterWithNullReturnsAll() {
        assertSame(HgTreeFilter.ALL, HgTreeFilter.fromPathFilter(null));
    }

    @Test
    public void testFromPathFilterWithHgTreeFilterReturnsSameInstance() {
        HgTreeFilter original = HgTreeFilter.createPathPrefixFilter(List.of("src/"), List.of());
        assertSame(original, HgTreeFilter.fromPathFilter(original));
    }

    @Test
    public void testFromPathFilterWrapsGenericPathFilter() {
        PathFilter generic = path -> path != null && path.startsWith("wrapped/");
        HgTreeFilter wrapped = HgTreeFilter.fromPathFilter(generic);

        assertNotSame(generic, wrapped);
        assertTrue(wrapped.accept("wrapped/file.txt"));
        assertFalse(wrapped.accept("other/file.txt"));
    }

    // --- createNarrowSpecFilter / normalizeNarrowPattern: real hg 7.2 narrowspec semantics ---
    // (mercurial/narrowspec.py, verified against the host's native "hg --config
    // extensions.narrow=" narrow clone; see backlog 28 writeup).

    @Test
    public void testNormalizeNarrowPatternDefaultsToPathKindAndStripsTrailingSlash() {
        NarrowPattern p = HgTreeFilter.normalizeNarrowPattern("srcdir/");
        assertEquals("path", p.kind);
        assertEquals("srcdir", p.path);
        assertEquals("path:srcdir", p.toSpecString());
    }

    @Test
    public void testNormalizeNarrowPatternHonorsExplicitPathAndRootfilesinKinds() {
        assertEquals("path:docs", HgTreeFilter.normalizeNarrowPattern("path:docs/").toSpecString());
        assertEquals("rootfilesin:docs", HgTreeFilter.normalizeNarrowPattern("rootfilesin:docs/").toSpecString());
    }

    @Test
    public void testNormalizeNarrowPatternRejectsUnsupportedKindPrefix() {
        // Real hg aborts narrow clones with "invalid prefix on narrow pattern" for anything
        // other than path:/rootfilesin: -- glob:/re: are legal sparse/fileset prefixes but NOT
        // legal narrowspec prefixes.
        assertThrows(IllegalArgumentException.class, () -> HgTreeFilter.normalizeNarrowPattern("glob:srcdir/*"));
        assertThrows(IllegalArgumentException.class, () -> HgTreeFilter.normalizeNarrowPattern("re:^srcdir/"));
    }

    @Test
    public void testNormalizeNarrowPatternRejectsDotComponents() {
        assertThrows(IllegalArgumentException.class, () -> HgTreeFilter.normalizeNarrowPattern("src/./a"));
        assertThrows(IllegalArgumentException.class, () -> HgTreeFilter.normalizeNarrowPattern("src/../a"));
        assertThrows(IllegalArgumentException.class, () -> HgTreeFilter.normalizeNarrowPattern("src//a"));
    }

    @Test
    public void testNarrowSpecFilterRespectsPathComponentBoundary() {
        // Real bug found against real hg: a naive String#startsWith("srcdir") match would wrongly
        // include a sibling directory "srcdirextra/...". Real hg's narrowspec "path:" matcher
        // requires an exact match or a "/" boundary.
        HgTreeFilter filter = HgTreeFilter.createNarrowSpecFilter(
                List.of(HgTreeFilter.normalizeNarrowPattern("srcdir")),
                List.of());

        assertTrue(filter.accept("srcdir"));
        assertTrue(filter.accept("srcdir/A.java"));
        assertTrue(filter.accept("srcdir/sub/B.java"));
        assertFalse(filter.accept("srcdirextra/x.txt"));
        assertFalse(filter.accept("other/file.txt"));
    }

    @Test
    public void testNarrowSpecFilterEmptyIncludesMatchesNothing() {
        // Real hg: narrowspec.match() returns the "never" matcher when there are no include
        // patterns at all -- confirmed empirically ("hg clone --narrow src dst" with no
        // --include produces a working copy with 0 files). This differs deliberately from
        // createPathPrefixFilter's generic "no includes means accept all" default.
        HgTreeFilter filter = HgTreeFilter.createNarrowSpecFilter(List.of(), List.of());
        assertFalse(filter.accept("anything.txt"));
        assertFalse(filter.accept(""));
    }

    @Test
    public void testNarrowSpecFilterExcludeWinsOverInclude() {
        HgTreeFilter filter = HgTreeFilter.createNarrowSpecFilter(
                List.of(HgTreeFilter.normalizeNarrowPattern("srcdir")),
                List.of(HgTreeFilter.normalizeNarrowPattern("srcdir/sub")));

        assertTrue(filter.accept("srcdir/A.java"));
        assertFalse(filter.accept("srcdir/sub/B.java"));
    }

    @Test
    public void testNarrowSpecFilterRootfilesinMatchesOnlyDirectChildren() {
        HgTreeFilter filter = HgTreeFilter.createNarrowSpecFilter(
                List.of(HgTreeFilter.normalizeNarrowPattern("rootfilesin:docs")),
                List.of());

        assertTrue(filter.accept("docs/readme.txt"));
        assertFalse(filter.accept("docs/sub/nested.txt"), "rootfilesin: must not recurse into subdirectories");
        assertFalse(filter.accept("docs"));
    }

    @Test
    public void testNarrowSpecFilterEmptyPathMatchesEverything() {
        // Real hg: "path:" (empty path) is the whole-repo narrow pattern -- confirmed against
        // real hg ("hg clone --narrow --include path: src dst" clones every file). Note this is
        // NOT the same as "path:." -- real hg's own _validatepattern() rejects "." as a path
        // component (see testNormalizeNarrowPatternRejectsDotComponents), so "path:." is
        // actually an invalid narrow pattern in real hg, not a "whole repo" shorthand.
        HgTreeFilter filter = HgTreeFilter.createNarrowSpecFilter(
                List.of(HgTreeFilter.normalizeNarrowPattern("path:")),
                List.of());

        assertTrue(filter.accept("anything/at/all.txt"));
    }
}
