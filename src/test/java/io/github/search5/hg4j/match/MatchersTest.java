package io.github.search5.hg4j.match;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The doctests of Mercurial's {@code match.py} and the rules the parity test also covers, without needing hg. */
class MatchersTest {

    private final File root = new File("/repo");

    @Test
    void globsBecomeTheRegularExpressionsMercurialPrints() {
        assertEquals(".", Globs.globRe("?"));
        assertEquals("[^/]*", Globs.globRe("*"));
        assertEquals(".*", Globs.globRe("**"));
        assertEquals("(?:.*/)?a", Globs.globRe("**/a"));
        assertEquals("a/(?:.*/)?b", Globs.globRe("a/**/b"));
        assertEquals("[a*?!^][\\^b][^c]", Globs.globRe("[a*?!^][^b][!c]"));
        assertEquals("(?:a|b)", Globs.globRe("{a,b}"));
        assertEquals("\\.\\*\\?", Globs.globRe(".\\*\\?"));
    }

    @Test
    void regexesPerKind() {
        assertEquals("foo/a(?:/|$)", Globs.regex("path", "foo/a", "$"));
        assertEquals("", Globs.regex("path", ".", "$"));
        assertEquals("src/[^/]+$", Globs.regex("rootfilesin", "src", "$"));
        assertEquals("[^/]+$", Globs.regex("rootfilesin", ".", "$"));
        assertEquals(".*\\.c$", Globs.regex("relglob", "*.c", "$"));
        assertEquals("(?:|.*/)deep\\.c$", Globs.regex("relglob", "deep.c", "$"));
        assertEquals(".*\\.c$", Globs.regex("relre", "\\.c$", "$"));
        assertEquals("(?i:.*readme)", Globs.regex("relre", "(?i)readme", "$"));
        assertEquals("^lib", Globs.regex("relre", "^lib", "$"));
        assertEquals("", Globs.regex("glob", "", "$"));
    }

    @Test
    void kindsAreSplitOnlyWhenTheyAreKnown() {
        assertArrayEquals(new String[] {"re", ".*\\.c$"}, Matchers.split("re:.*\\.c$", "glob"));
        assertArrayEquals(new String[] {"glob", "unknown:thing"}, Matchers.split("unknown:thing", "glob"));
        assertArrayEquals(new String[] {"relpath", "main.py"}, Matchers.split("main.py", "relpath"));
    }

    @Test
    void aPatternMatcherAgainstTheDoctestKinds() throws IOException {
        Matcher m = Matchers.match(root, "", List.of("re:.*\\.c$", "path:foo/a", "relpath:b", "glob:*.h"), null, null, "glob", null, null);
        assertTrue(m.matches("main.c"));
        assertFalse(m.matches("b.txt"));
        assertTrue(m.matches("foo/a"));
        assertTrue(m.matches("foo/a/deeper"));
        assertTrue(m.matches("b"));
        assertTrue(m.matches("lib.h"));
        assertFalse(m.matches("sub/lib.h"), "a glob without ** does not cross directories");
        assertTrue(m.exact("foo/a"));
        assertFalse(m.exact("lib.h"));
    }

    @Test
    void noPatternsMeansEverythingAndAnEmptyRelpathToo() throws IOException {
        assertTrue(Matchers.match(root, "", List.of(), null, null, "relpath", null, null).always());
        assertTrue(Matchers.match(root, "", List.of("."), null, null, "relpath", null, null).always());
        assertTrue(Matchers.match(root, "", List.of("glob:"), null, null, "relpath", null, null).always());
    }

    @Test
    void includeAndExcludeCombine() throws IOException {
        Matcher m = Matchers.match(root, "", List.of("re:.*\\.c$"), List.of("path:lib"), List.of("path:lib/gen"), "relpath", null, null);
        assertTrue(m.matches("lib/a.c"));
        assertFalse(m.matches("src/a.c"));
        assertFalse(m.matches("lib/gen/a.c"));
    }

    @Test
    void relativePatternsFollowTheCurrentDirectory() throws IOException {
        Matcher m = Matchers.match(root, "src", List.of("sub", "glob:*.c"), null, null, "relpath", null, null);
        assertTrue(m.matches("src/sub/x"));
        assertTrue(m.matches("src/main.c"));
        assertFalse(m.matches("main.c"));
        IOException outside = assertThrows(IOException.class, () -> Matchers.match(root, "", List.of("relpath:../x"), null, null, "relpath", null, null));
        assertEquals("relpath:../x".substring(8) + " not under root '/repo'", outside.getMessage());
    }

    @Test
    void invalidExpressionsAreRefusedWithMercurialsWords() {
        IOException bad = assertThrows(IOException.class, () -> Matchers.match(root, "", List.of("re:["), null, null, "relpath", null, null));
        assertEquals("invalid pattern (re): [", bad.getMessage());
        IOException unreadable = assertThrows(IOException.class,
                () -> Matchers.match(root, "", List.of("listfile:/no/such/list"), null, null, "relpath", null, null));
        assertEquals("unable to read file list (/no/such/list)", unreadable.getMessage());
        IOException noContext = assertThrows(IOException.class, () -> Matchers.match(root, "", List.of("set:all()"), null, null, "relpath", null, null));
        assertEquals("fileset expression with no context", noContext.getMessage());
    }

    @Test
    void aSetPatternGoesToTheResolver() throws IOException {
        Matcher m = Matchers.match(root, "", List.of("set:EXPR"), null, null, "relpath", (cwd, expr) -> path -> path.endsWith(expr), null);
        assertTrue(m.matches("fooEXPR"));
        assertFalse(m.matches("foo"));
    }

    @Test
    void exactAndNeverAndUnionBehave() {
        Matcher exact = Matchers.exact(List.of("a", "b/c"));
        assertTrue(exact.matches("a"));
        assertFalse(exact.matches("b"));
        assertTrue(exact.isExact());
        assertTrue(exact.visitDir("b"));
        assertFalse(exact.visitDir("x"));
        assertFalse(Matchers.never().matches("a"));
        assertTrue(Matchers.union(List.of(Matchers.never(), exact)).matches("a"));
    }
}
