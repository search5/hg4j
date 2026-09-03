package io.github.search5.hg4j.treewalk;

import org.junit.jupiter.api.Test;

import java.util.List;

import io.github.search5.hg4j.treewalk.PathFilter;

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
}
