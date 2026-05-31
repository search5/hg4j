package com.github.search5.hg4j.core;

import org.junit.jupiter.api.Test;

import java.util.List;

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
}
