package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.util.NodeIdUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targeted coverage tests for {@link RevsetCommand}, complementing the broader
 * roundtrip coverage in {@link PorcelainExtraCommandsTest}. Each test isolates one
 * branch of RevsetCommand#call() that the existing suite did not previously exercise:
 * argument validation, the "parents(tip)" symbolic-target preprocessing, the "all"/"tip"
 * literal fast paths (with and without parentheses / on an empty repository), and the
 * exception fallback that treats the whole expression as a raw revision identifier.
 */
public class RevsetCommandCoverageTest {

    private HgRepository initRepoWithTwoCommits(File repoDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        File f1 = new File(repoDir, "a.txt");
        Files.writeString(f1.toPath(), "Content A");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit A").setAuthor("dev").call();

        File f2 = new File(repoDir, "b.txt");
        Files.writeString(f2.toPath(), "Content B");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit B").setAuthor("dev").call();

        return repo;
    }

    @Test
    public void testNullExpressionThrows(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        RevsetCommand cmd = new RevsetCommand(repo).setExpression(null);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, cmd::call);
        assertTrue(ex.getMessage().contains("Expression must be specified"));
    }

    @Test
    public void testEmptyExpressionThrows(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        RevsetCommand cmd = new RevsetCommand(repo).setExpression("");
        assertThrows(IllegalArgumentException.class, cmd::call);
    }

    @Test
    public void testAllWithoutParensMatchesAllWithParens(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = initRepoWithTwoCommits(repoDir);

        List<String> withParens = new RevsetCommand(repo).setExpression("all()").call();
        List<String> withoutParens = new RevsetCommand(repo).setExpression("all").call();

        assertEquals(2, withoutParens.size());
        assertEquals(withParens, withoutParens);
    }

    @Test
    public void testTipTopLevelExpression(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = initRepoWithTwoCommits(repoDir);
        byte[] nodeB = repo.getDirstate().getParent1();
        String hexB = NodeIdUtil.toHex(nodeB);

        List<String> revsTip = new RevsetCommand(repo).setExpression("tip").call();
        assertEquals(1, revsTip.size());
        assertEquals(hexB, revsTip.get(0));
    }

    @Test
    public void testTipOnEmptyRepositoryReturnsEmptyList(@TempDir Path tempDir) throws Exception {
        // No commits at all: changelog.getRevisionCount() == 0, so the "tip" fast path
        // must not add anything (and must not throw) when count is not > 0.
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();

        List<String> revsTip = new RevsetCommand(repo).setExpression("tip").call();
        assertTrue(revsTip.isEmpty());
    }

    @Test
    public void testPlainRevisionNumberWithoutParens(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = initRepoWithTwoCommits(repoDir);

        // Revision 0 refers to the first commit (hexA); expression has no parentheses at
        // all, exercising the branch where the "parents(...)" preprocessing block is
        // skipped entirely because expression.contains("(") is false.
        List<String> revs0 = new RevsetCommand(repo).setExpression("0").call();
        assertEquals(1, revs0.size());

        List<String> revsAll = new RevsetCommand(repo).setExpression("all()").call();
        assertEquals(revsAll.get(0), revs0.get(0));
    }

    @Test
    public void testUnclosedParenSkipsPreprocessingAndYieldsNoMatch(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = initRepoWithTwoCommits(repoDir);

        // "heads(" contains '(' but does not end with ')', so the symbolic-target
        // preprocessing block must be skipped (expression.endsWith(")") is false).
        // The malformed expression is then handed to the underlying engine, which is
        // expected to degrade to "no match" rather than throwing.
        List<String> revs = new RevsetCommand(repo).setExpression("heads(").call();
        assertTrue(revs.isEmpty());
    }

    @Test
    public void testParentsWithUnresolvableTargetFallsBackToOriginalExpression(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = initRepoWithTwoCommits(repoDir);

        // "zzzzznonexistent" is neither "tip", a valid integer, nor a valid hex prefix of
        // any real revision, so NodeIdUtil.resolveRevision(...) returns null inside the
        // "parents(...)" preprocessing block and the original (unresolved) expression
        // must be used to query the engine, which finds nothing.
        List<String> revs = new RevsetCommand(repo).setExpression("parents(zzzzznonexistent)").call();
        assertTrue(revs.isEmpty());
    }

    @Test
    public void testMalformedLimitTriggersExceptionFallback(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = initRepoWithTwoCommits(repoDir);

        // limit(<set>, <n>) parses <n> with Integer.parseInt without guarding against
        // non-numeric input; "abc" therefore makes the underlying engine throw a
        // NumberFormatException, which RevsetCommand#call() must catch and fall back to
        // treating the whole expression as a raw revision/nodeId lookup. Since
        // "limit(all(), abc)" is not itself a resolvable revision, the fallback finds
        // nothing and the call must return an empty list rather than propagating the
        // exception.
        List<String> revs = new RevsetCommand(repo).setExpression("limit(all(), abc)").call();
        assertTrue(revs.isEmpty());
    }
}
