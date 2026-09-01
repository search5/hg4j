package com.github.search5.hg4j.revset;

import com.github.search5.hg4j.util.NodeIdUtil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

import com.github.search5.hg4j.api.AddCommand;
import com.github.search5.hg4j.api.BookmarkCommand;
import com.github.search5.hg4j.api.BranchCommand;
import com.github.search5.hg4j.api.CommitCommand;
import com.github.search5.hg4j.api.Hg;
import com.github.search5.hg4j.api.MergeCommand;
import com.github.search5.hg4j.api.TagCommand;
import com.github.search5.hg4j.api.UpdateCommand;
import com.github.search5.hg4j.lib.HgRepository;

/**
 * Additional coverage for {@link HgRevsetEngine}: targets branches/functions not exercised by
 * {@link HgRevsetTest}, such as date(), tip/hex revision resolution, malformed expressions,
 * empty-result edge cases, quoting variants, and set-operator precedence.
 */
public class HgRevsetEngineCoverageTest {

    @TempDir
    File tempDir;

    private HgRepository initRepo() throws Exception {
        return Hg.init().setDirectory(tempDir).call();
    }

    @Test
    public void testEmptyRepoEdgeCases() throws Exception {
        HgRepository repo = initRepo();
        HgRevsetEngine engine = new HgRevsetEngine(repo);

        // No 00changelog.i data file created until first commit in some layouts, but the
        // index file should exist after init(); either way an empty repo yields empty results.
        assertTrue(engine.query("all()").isEmpty());
        assertTrue(engine.query("heads()").isEmpty());
        assertTrue(engine.query("draft()").isEmpty());
    }

    @Test
    public void testMalformedAndUnmatchedExpressions() throws Exception {
        HgRepository repo = initRepo();
        File file = new File(tempDir, "a.txt");
        Files.writeString(file.toPath(), "content");
        new AddCommand(repo).addFile("a.txt").call();
        new CommitCommand(repo).setAuthor("Alice").setMessage("first").call();

        HgRevsetEngine engine = new HgRevsetEngine(repo);

        // Numeric revision out of range -> empty, not an exception.
        assertTrue(engine.query("999").isEmpty());
        assertTrue(engine.query("-5").isEmpty());

        // Well-formed hex (40 chars, contains a-f so it can't be mistaken for a decimal
        // literal) that doesn't correspond to any commit -> empty.
        String unknownHex = "abcd1234abcd1234abcd1234abcd1234abcd1234";
        assertTrue(engine.query(unknownHex).isEmpty());

        // Garbage that is neither a valid int nor valid hex -> empty, no exception.
        assertTrue(engine.query("totally-not-a-revision!!").isEmpty());

        // Odd-length "hex-ish" garbage also falls through safely.
        assertTrue(engine.query("abcde").isEmpty());

        // Unclosed/unknown function-like syntax falls through the numeric/hex fallback.
        assertTrue(engine.query("author(unclosed").isEmpty());
        assertTrue(engine.query("bogusFunction()").isEmpty());
    }

    @Test
    public void testTipAndHexRevisionResolution() throws Exception {
        HgRepository repo = initRepo();
        File file = new File(tempDir, "a.txt");
        Files.writeString(file.toPath(), "v1");
        new AddCommand(repo).addFile("a.txt").call();
        byte[] c0 = new CommitCommand(repo).setAuthor("Alice").setMessage("first").call();
        Files.writeString(file.toPath(), "v2");
        byte[] c1 = new CommitCommand(repo).setAuthor("Bob").setMessage("second").call();

        HgRevsetEngine engine = new HgRevsetEngine(repo);

        // "tip" resolves to the last revision (rev 1 here).
        assertEquals(List.of(0), engine.query("parents(tip)"));
        assertEquals(List.of(0, 1), engine.query("ancestors(tip)"));
        assertEquals(List.of(1), engine.query("descendants(tip)"));
        assertEquals(List.of(0, 1), engine.query("ancestors(TIP)")); // case-insensitive

        // Hex node id accepted directly as the revision argument of DAG functions.
        String hexC1 = NodeIdUtil.toHex(c1);
        assertEquals(List.of(0), engine.query("parents(" + hexC1 + ")"));
        assertEquals(List.of(0, 1), engine.query("ancestors(" + hexC1 + ")"));

        // Out-of-range / unresolvable revision arguments yield empty results, not exceptions.
        assertTrue(engine.query("parents(999)").isEmpty());
        assertTrue(engine.query("ancestors(999)").isEmpty());
        assertTrue(engine.query("descendants(999)").isEmpty());
        assertTrue(engine.query("ancestors(-1)").isEmpty());
        assertTrue(engine.query("ancestors(not-a-valid-hex-or-int)").isEmpty());
    }

    @Test
    public void testDateFunctionAndSortByDate() throws Exception {
        HgRepository repo = initRepo();
        File file = new File(tempDir, "a.txt");
        Files.writeString(file.toPath(), "v1");
        new AddCommand(repo).addFile("a.txt").call();
        new CommitCommand(repo).setAuthor("Alice").setMessage("first").setDate(1_000_000L, 0).call();

        Files.writeString(file.toPath(), "v2");
        new CommitCommand(repo).setAuthor("Bob").setMessage("second").setDate(2_000_000L, 0).call();

        HgRevsetEngine engine = new HgRevsetEngine(repo);

        assertEquals(List.of(0), engine.query("date(\"1000000\")"));
        assertEquals(List.of(1), engine.query("date(\"2000000\")"));
        assertTrue(engine.query("date(\"9999999\")").isEmpty());

        // Reverse-inserted-order set, sorted by date ascending should read rev0 then rev1.
        List<Integer> byDate = engine.query("sort(1 or 0, \"date\")");
        assertEquals(List.of(0, 1), byDate);
    }

    @Test
    public void testSingleAndDoubleQuoteEquivalence() throws Exception {
        // Verified against real hg: author('Bob') and author("Bob") are equivalent --
        // single and double quotes are interchangeable string delimiters in revset syntax.
        HgRepository repo = initRepo();
        File file = new File(tempDir, "a.txt");
        Files.writeString(file.toPath(), "v1");
        new AddCommand(repo).addFile("a.txt").call();
        new CommitCommand(repo).setAuthor("Alice <alice@example.com>").setMessage("first").call();
        Files.writeString(file.toPath(), "v2");
        new CommitCommand(repo).setAuthor("Bob <bob@example.com>").setMessage("second").call();

        HgRevsetEngine engine = new HgRevsetEngine(repo);

        assertEquals(engine.query("author(\"Bob\")"), engine.query("author('Bob')"));
        assertEquals(List.of(1), engine.query("author('Bob')"));
        assertEquals(List.of(1), engine.query("user('Bob')"));
        assertEquals(List.of(1), engine.query("keyword('second')"));
        assertEquals(List.of(0, 1), engine.query("branch('default')"));
    }

    @Test
    public void testNotOperatorBothForms() throws Exception {
        HgRepository repo = initRepo();
        File file = new File(tempDir, "a.txt");
        Files.writeString(file.toPath(), "v1");
        new AddCommand(repo).addFile("a.txt").call();
        new CommitCommand(repo).setAuthor("Alice").setMessage("first").call();
        Files.writeString(file.toPath(), "v2");
        new CommitCommand(repo).setAuthor("Bob").setMessage("second").call();

        HgRevsetEngine engine = new HgRevsetEngine(repo);

        assertEquals(List.of(1), engine.query("not author(Alice)"));
        assertEquals(List.of(1), engine.query("!author(Alice)"));
        assertEquals(List.of(0), engine.query("NOT author(Bob)")); // case-insensitive "not "
    }

    @Test
    public void testMixedCaseLogicalKeywordsAndQuotedKeywordSafety() throws Exception {
        HgRepository repo = initRepo();
        File file = new File(tempDir, "a.txt");
        Files.writeString(file.toPath(), "v1");
        new AddCommand(repo).addFile("a.txt").call();
        new CommitCommand(repo).setAuthor("Alice").setMessage("first").call();
        Files.writeString(file.toPath(), "v2");
        new CommitCommand(repo).setAuthor("Bob").setMessage("second").call();

        HgRevsetEngine engine = new HgRevsetEngine(repo);

        assertEquals(List.of(1), engine.query("0 AND author(Bob) OR author(Bob)"));
        assertEquals(new TreeSet<>(List.of(0, 1)), new TreeSet<>(engine.query("author(Alice) Or author(Bob)")));

        // A literal "or"/"and" occurring only inside a quoted top-level string (no enclosing
        // function parens) must not be mistaken for the logical operator: findLogicalKeyword
        // toggles quote-tracking state even outside bracket depth.
        assertTrue(engine.query("\"a or b\"").isEmpty());
        assertTrue(engine.query("'a and b'").isEmpty());
    }

    @Test
    public void testSortEdgeCases() throws Exception {
        HgRepository repo = initRepo();
        File file = new File(tempDir, "a.txt");
        Files.writeString(file.toPath(), "v1");
        new AddCommand(repo).addFile("a.txt").call();
        new CommitCommand(repo).setAuthor("Alice").setMessage("first").call();
        Files.writeString(file.toPath(), "v2");
        new CommitCommand(repo).setAuthor("Bob").setMessage("second").call();

        HgRevsetEngine engine = new HgRevsetEngine(repo);

        // No comma inside sort(...) -> empty result (malformed args, not an exception).
        assertTrue(engine.query("sort(all())").isEmpty());

        // Unknown sort field -> none of the rev/date/author branches apply; the function
        // still returns the full, correct element set without sorting or throwing.
        List<Integer> result = engine.query("sort(1 or 0, \"unknownfield\")");
        assertEquals(new TreeSet<>(List.of(0, 1)), new TreeSet<>(result));
    }

    @Test
    public void testLimitEdgeCases() throws Exception {
        HgRepository repo = initRepo();
        File file = new File(tempDir, "a.txt");
        Files.writeString(file.toPath(), "v1");
        new AddCommand(repo).addFile("a.txt").call();
        new CommitCommand(repo).setAuthor("Alice").setMessage("first").call();
        Files.writeString(file.toPath(), "v2");
        new CommitCommand(repo).setAuthor("Bob").setMessage("second").call();

        HgRevsetEngine engine = new HgRevsetEngine(repo);

        // No comma -> empty.
        assertTrue(engine.query("limit(all())").isEmpty());

        // n == 0 -> empty.
        assertTrue(engine.query("limit(all(), 0)").isEmpty());

        // n greater than the available result count -> full set, no exception.
        assertEquals(2, engine.query("limit(all(), 100)").size());
    }

    @Test
    public void testTagAndBookmarkMissingFilesAndNoMatch() throws Exception {
        HgRepository repo = initRepo();
        File file = new File(tempDir, "a.txt");
        Files.writeString(file.toPath(), "v1");
        new AddCommand(repo).addFile("a.txt").call();
        new CommitCommand(repo).setAuthor("Alice").setMessage("first").call();

        HgRevsetEngine engine = new HgRevsetEngine(repo);

        // No .hgtags / bookmarks file has been created yet.
        assertTrue(engine.query("tag(\"v1.0\")").isEmpty());
        assertTrue(engine.query("bookmark(\"anything\")").isEmpty());

        byte[] c0 = new CommitCommand(repo).setAuthor("Alice").setMessage("noop").call();
        new TagCommand(repo).setTagName("v1.0").setNodeId(c0).setCommit(false).call();
        new BookmarkCommand(repo).setBookmarkName("work").setNodeId(c0).call();

        // Files now exist but the queried name doesn't match any entry.
        assertTrue(engine.query("tag(\"nope\")").isEmpty());
        assertTrue(engine.query("bookmark(\"nope\")").isEmpty());

        assertFalse(engine.query("tag(\"v1.0\")").isEmpty());
        assertFalse(engine.query("bookmark(\"work\")").isEmpty());
    }

    @Test
    public void testFilePathSeparatorNormalization() throws Exception {
        HgRepository repo = initRepo();
        File subDir = new File(tempDir, "sub");
        subDir.mkdirs();
        File nested = new File(subDir, "c.txt");
        Files.writeString(nested.toPath(), "nested content");
        new AddCommand(repo).addFile("sub/c.txt").call();
        new CommitCommand(repo).setAuthor("Alice").setMessage("nested file commit").call();

        HgRevsetEngine engine = new HgRevsetEngine(repo);

        // Direct forward-slash match.
        assertEquals(List.of(0), engine.query("file(\"sub/c.txt\")"));
        // Backslash-style path normalizes to forward slash for comparison. The Java string
        // literal "sub\\c.txt" (two source backslashes) yields ONE literal backslash char,
        // i.e. the revset argument is sub\c.txt.
        assertEquals(List.of(0), engine.query("file(\"sub\\c.txt\")"));
        // Non-matching path.
        assertTrue(engine.query("file(\"nope.txt\")").isEmpty());
    }

    @Test
    public void testChildrenOfMultipleAndEmptySets() throws Exception {
        HgRepository repo = initRepo();
        File a = new File(tempDir, "a.txt");
        Files.writeString(a.toPath(), "v1");
        new AddCommand(repo).addFile("a.txt").call();
        byte[] c0 = new CommitCommand(repo).setAuthor("Alice").setMessage("c0").call();
        Files.writeString(a.toPath(), "v2");
        byte[] c1 = new CommitCommand(repo).setAuthor("Bob").setMessage("c1").call();

        new UpdateCommand(repo).setRevision("0").call();
        new BranchCommand(repo).setBranchName("feature").call();
        File b = new File(tempDir, "b.txt");
        Files.writeString(b.toPath(), "feature");
        new AddCommand(repo).addFile("b.txt").call();
        byte[] c2 = new CommitCommand(repo).setAuthor("Alice").setMessage("c2").call();

        new UpdateCommand(repo).setRevision("1").call();
        new BranchCommand(repo).setBranchName("default").call();
        new MergeCommand(repo).setRevision(2).call();
        new CommitCommand(repo).setAuthor("Bob").setMessage("merge").call();

        HgRevsetEngine engine = new HgRevsetEngine(repo);

        // children() of two parents at once (via OR) should union results without duplicates.
        assertEquals(new TreeSet<>(List.of(1, 2)), new TreeSet<>(engine.query("children(0)")));
        assertEquals(List.of(3), engine.query("children(1)"));
        assertEquals(List.of(3), engine.query("children(2)"));

        // children() of a revision with no children -> empty.
        assertTrue(engine.query("children(3)").isEmpty());
    }

    @Test
    public void testSetOperatorPrecedenceAndCombinedExpressions() throws Exception {
        HgRepository repo = initRepo();
        File file = new File(tempDir, "a.txt");
        Files.writeString(file.toPath(), "v1");
        new AddCommand(repo).addFile("a.txt").call();
        new CommitCommand(repo).setAuthor("Alice").setMessage("first").call();
        Files.writeString(file.toPath(), "v2");
        new CommitCommand(repo).setAuthor("Bob").setMessage("second").call();
        Files.writeString(file.toPath(), "v3");
        new CommitCommand(repo).setAuthor("Alice").setMessage("third").call();

        HgRevsetEngine engine = new HgRevsetEngine(repo);

        // The evaluator splits on the outermost "or" before "and" (verified to match real
        // hg's "and binds tighter than or" precedence): "0 or 1 and author(nomatch)" means
        // "0 or (1 and author(nomatch))", i.e. rev 1's author check fails, leaving just {0}.
        List<Integer> precedence = engine.query("0 or 1 and author(nomatch-xyz)");
        assertEquals(List.of(0), precedence);

        // Same precedence rule with named functions instead of bare revision numbers on
        // both sides of "and"/"or".
        List<Integer> combined = engine.query("author(Bob) or author(Alice) and author(nomatch-xyz)");
        assertEquals(List.of(1), combined);

        // Nested ancestors/descendants combined with set ops.
        List<Integer> nested = engine.query("descendants(0) and not merge()");
        assertEquals(new TreeSet<>(List.of(0, 1, 2)), new TreeSet<>(nested));
    }
}
