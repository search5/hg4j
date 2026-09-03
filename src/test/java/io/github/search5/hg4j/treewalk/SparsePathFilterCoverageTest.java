package io.github.search5.hg4j.treewalk;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage-focused tests for {@link SparsePathFilter}, exercising branches not
 * touched by {@link TreeWalkTest} / {@link TreeWalkCoverageTest}: empty/null
 * pattern handling for both constructors, the '?' wildcard, '**' bordering a
 * '/', single-trailing-'*' patterns, directory-trailing-slash patterns, and
 * literal escaping of regex metacharacters.
 *
 * <p>Two real behavioral divergences from Mercurial's own glob-to-regex
 * translation ({@code mercurial/match.py}'s {@code _globre}) were found and
 * fixed in the production class, each verified against real {@code hg
 * debugsparse} on a scratch repository (see comments below):
 * <ul>
 *   <li>{@code ?} must match any single character, including a path
 *       separator, not just non-slash characters (confirmed via
 *       mercurial's own {@code _globre} helper, whose doctest maps a bare
 *       {@code ?} glob to the regex fragment {@code .}).</li>
 *   <li>a {@code **} glob segment bordering a path separator must only
 *       match whole path segments, not an unrestricted run of characters
 *       that could fuse mid-segment (confirmed the same way: mercurial's
 *       {@code _globre} doctest maps a {@code **} segment between two path
 *       separators to an optional non-capturing group that always ends in
 *       a separator).</li>
 * </ul>
 */
public class SparsePathFilterCoverageTest {

    // -------------------------------------------------------------
    // Constructors: empty / null pattern handling
    // -------------------------------------------------------------

    @Test
    public void testNoPatternsAcceptsEverything() {
        SparsePathFilter filter = new SparsePathFilter();
        assertTrue(filter.accept("anything.txt"));
        assertTrue(filter.accept(""));
        assertTrue(filter.accept("deeply/nested/path.txt"));
    }

    @Test
    public void testVarargsConstructorWithNullArraySkipsAllPatterns() {
        SparsePathFilter filter = new SparsePathFilter((String[]) null);
        assertTrue(filter.accept("anything.txt"));
    }

    @Test
    public void testVarargsConstructorSkipsNullAndEmptyGlobs() {
        SparsePathFilter filter = new SparsePathFilter(null, "", "a.txt");
        assertTrue(filter.accept("a.txt"));
        assertFalse(filter.accept("random.txt"));
    }

    @Test
    public void testListConstructorBasicMatch() {
        SparsePathFilter filter = new SparsePathFilter(Arrays.asList("a.txt"));
        assertTrue(filter.accept("a.txt"));
        assertFalse(filter.accept("other.txt"));
    }

    @Test
    public void testListConstructorWithNullListSkipsAllPatterns() {
        SparsePathFilter filter = new SparsePathFilter((List<String>) null);
        assertTrue(filter.accept("anything.txt"));
    }

    @Test
    public void testListConstructorSkipsNullAndEmptyEntries() {
        SparsePathFilter filter = new SparsePathFilter(Arrays.asList(null, "", "b.txt"));
        assertTrue(filter.accept("b.txt"));
        assertFalse(filter.accept("random.txt"));
    }

    // -------------------------------------------------------------
    // accept(): multi-pattern loop behavior
    // -------------------------------------------------------------

    @Test
    public void testAcceptChecksAllPatternsBeforeRejecting() {
        SparsePathFilter filter = new SparsePathFilter("x.txt", "y.txt");
        // First pattern doesn't match "y.txt"; loop must continue to the second.
        assertTrue(filter.accept("y.txt"));
        // No pattern matches at all; loop must exhaust and return false.
        assertFalse(filter.accept("z.txt"));
    }

    // -------------------------------------------------------------
    // '?' wildcard: must match exactly one arbitrary character,
    // INCLUDING '/', per real hg's _globre('?') == '.'.
    // -------------------------------------------------------------

    @Test
    public void testQuestionMarkMatchesSingleArbitraryCharacter() {
        SparsePathFilter filter = new SparsePathFilter("a?b");
        assertTrue(filter.accept("aXb"));
        assertTrue(filter.accept("a1b"));
    }

    @Test
    public void testQuestionMarkDoesNotMatchZeroOrMultipleCharacters() {
        SparsePathFilter filter = new SparsePathFilter("a?b");
        assertFalse(filter.accept("ab"));
        assertFalse(filter.accept("aXXb"));
    }

    @Test
    public void testQuestionMarkMatchesPathSeparator() {
        // Verified against real hg: with a scratch repo containing a/b, aXb, ayb,
        // `hg debugsparse --include 'glob:a?b'` kept a/b in the sparse checkout
        // (hg status -A after the include still showed "C a/b"), proving real
        // hg's '?' matches '/' too. SparsePathFilter must do the same.
        SparsePathFilter filter = new SparsePathFilter("a?b");
        assertTrue(filter.accept("a/b"));
    }

    // -------------------------------------------------------------
    // Single '*' must NOT cross a '/', unlike '?'.
    // -------------------------------------------------------------

    @Test
    public void testSingleStarDoesNotCrossPathSeparator() {
        SparsePathFilter filter = new SparsePathFilter("a*b");
        assertTrue(filter.accept("aXYZb"));
        assertFalse(filter.accept("a/b"));
    }

    @Test
    public void testBareSingleStarMatchesOnlyOneSegmentIncludingEmpty() {
        SparsePathFilter filter = new SparsePathFilter("*");
        assertTrue(filter.accept("foo"));
        assertTrue(filter.accept(""));
        assertFalse(filter.accept("foo/bar"));
    }

    // -------------------------------------------------------------
    // '**' bordering '/': must require whole path segments, matching
    // real hg's _globre('a/**/b') == 'a/(?:.*/)?b'.
    // -------------------------------------------------------------

    @Test
    public void testDoubleStarBetweenSlashesMatchesZeroOrMoreWholeSegments() {
        // Verified against real hg on a scratch repo: after
        // `hg debugsparse --include 'glob:a/**/b'`, `hg status -A` showed
        // "C a/b", "C a/x/b", "C a/x/y/b" as included, but did NOT include
        // "a/xb" (it never appeared in the status output at all, confirming
        // Mercurial's translation `a/(?:.*/)?b` requires a full path segment,
        // not a mid-segment fusion).
        SparsePathFilter filter = new SparsePathFilter("a/**/b");
        assertTrue(filter.accept("a/b"));
        assertTrue(filter.accept("a/x/b"));
        assertTrue(filter.accept("a/x/y/b"));
    }

    @Test
    public void testDoubleStarBetweenSlashesDoesNotFuseMidSegment() {
        SparsePathFilter filter = new SparsePathFilter("a/**/b");
        // Bug (now fixed): the old translation used a bare ".*" after
        // consuming the separating '/', so "a/xb" incorrectly matched
        // because ".*" could absorb "x" leaving "b" to match directly
        // with no segment boundary in between. Real hg's
        // "a/(?:.*/)?b" requires "b" to start its own path segment.
        assertFalse(filter.accept("a/xb"));
    }

    @Test
    public void testLeadingDoubleStarSlashMatchesZeroOrMoreWholeSegments() {
        SparsePathFilter filter = new SparsePathFilter("**/a");
        assertTrue(filter.accept("a"));
        assertTrue(filter.accept("x/a"));
        assertTrue(filter.accept("x/y/a"));
    }

    @Test
    public void testLeadingDoubleStarSlashDoesNotFuseMidSegment() {
        SparsePathFilter filter = new SparsePathFilter("**/a");
        // "xa" ends with "a" but is not a whole path segment on its own;
        // real hg's "(?:.*/)?a" would not match it either.
        assertFalse(filter.accept("xa"));
    }

    @Test
    public void testTrailingDoubleStarNotFollowedBySlashMatchesRemainderVerbatim() {
        // Existing/unchanged behavior: a trailing "**" with nothing after it
        // (not immediately followed by '/') falls back to plain ".*", since
        // there is no following segment to anchor.
        SparsePathFilter filter = new SparsePathFilter("src/main/**");
        assertTrue(filter.accept("src/main/a.txt"));
        assertTrue(filter.accept("src/main/sub/b.txt"));
        assertFalse(filter.accept("src/mainX"));
    }

    @Test
    public void testDoubleStarNotBorderingSlashOnEitherSideFallsBackToDotStar() {
        // "**" that is not immediately followed by '/' (nor at the very end)
        // falls into the plain ".*" branch, exactly as an unbounded run - this
        // exercises the inner-if's "false because a non-'/' character follows"
        // sub-branch, distinct from "false because end-of-string".
        SparsePathFilter filter = new SparsePathFilter("ab**cd");
        assertTrue(filter.accept("abXYcd"));
        assertTrue(filter.accept("abcd"));
        assertFalse(filter.accept("abXYcE"));
    }

    // -------------------------------------------------------------
    // Directory-style auto-append suffix logic
    // -------------------------------------------------------------

    @Test
    public void testGlobEndingInSlashRequiresLiteralSlashThenAnything() {
        SparsePathFilter filter = new SparsePathFilter("src/main/");
        assertTrue(filter.accept("src/main/foo.txt"));
        assertTrue(filter.accept("src/main/"));
        // No trailing slash present in the candidate path at all, so the
        // literal "src/main/" prefix required by this pattern cannot match.
        assertFalse(filter.accept("src/main"));
    }

    @Test
    public void testGlobEndingInSingleStarSkipsDirectoryAutoAppend() {
        SparsePathFilter filter = new SparsePathFilter("build*");
        assertTrue(filter.accept("build"));
        assertTrue(filter.accept("build2"));
        // Unlike a literal (non-wildcard-ending) glob, this must NOT
        // auto-include nested contents, since it ends with a bare '*'.
        assertFalse(filter.accept("build/sub"));
        assertFalse(filter.accept("build2/sub"));
    }

    @Test
    public void testGlobWithoutTrailingWildcardAutoIncludesNestedContents() {
        SparsePathFilter filter = new SparsePathFilter("src/main");
        assertTrue(filter.accept("src/main"));
        assertTrue(filter.accept("src/main/a.txt"));
        assertTrue(filter.accept("src/main/sub/b.txt"));
        assertFalse(filter.accept("src/mainx"));
    }

    // -------------------------------------------------------------
    // Literal escaping of regex metacharacters
    // -------------------------------------------------------------

    @Test
    public void testDotIsEscapedAsLiteral() {
        SparsePathFilter filter = new SparsePathFilter("a.b");
        assertTrue(filter.accept("a.b"));
        assertFalse(filter.accept("aXb"));
    }

    @Test
    public void testPlusCaretDollarAreEscapedAsLiterals() {
        SparsePathFilter filter = new SparsePathFilter("a+b^c$d");
        assertTrue(filter.accept("a+b^c$d"));
        assertFalse(filter.accept("abcd"));
    }

    @Test
    public void testParenthesesAreEscapedAsLiterals() {
        SparsePathFilter filter = new SparsePathFilter("a(b)c");
        assertTrue(filter.accept("a(b)c"));
        assertFalse(filter.accept("abc"));
    }

    @Test
    public void testBracketsAreEscapedAsLiterals() {
        SparsePathFilter filter = new SparsePathFilter("a[b]c");
        assertTrue(filter.accept("a[b]c"));
        assertFalse(filter.accept("abc"));
    }

    @Test
    public void testBracesAreEscapedAsLiterals() {
        SparsePathFilter filter = new SparsePathFilter("a{b}c");
        assertTrue(filter.accept("a{b}c"));
        assertFalse(filter.accept("abc"));
    }

    @Test
    public void testPipeIsEscapedAsLiteralNotAlternation() {
        SparsePathFilter filter = new SparsePathFilter("a|b");
        assertTrue(filter.accept("a|b"));
        assertFalse(filter.accept("a"));
        assertFalse(filter.accept("b"));
    }

    @Test
    public void testBackslashIsEscapedAsLiteral() {
        SparsePathFilter filter = new SparsePathFilter("a\\b");
        assertTrue(filter.accept("a\\b"));
        assertFalse(filter.accept("ab"));
    }
}
