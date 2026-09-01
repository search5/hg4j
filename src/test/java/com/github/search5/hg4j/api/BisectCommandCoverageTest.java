package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.errors.HgRepositoryNotFoundException;
import com.github.search5.hg4j.errors.HgRevisionNotFoundException;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage-focused tests for {@link BisectCommand}: precondition validation, error
 * paths (unknown revisions, disconnected DAGs, corrupted store), and the
 * midpoint-selection algorithm on both linear and branching (merge) histories.
 *
 * <p>Bug found and fixed while writing these tests: {@code next()} used to pick
 * {@code range.get(range.size() / 2)} -- the midpoint of the plain revision-number
 * range -- as the next bisect candidate. Real hg's bisect (mercurial/hbisect.py)
 * instead picks whichever candidate best splits the DAG in half by ancestor count,
 * which only differs from the naive index midpoint once history branches (a merge
 * is present). Verified against real hg 7.2 CLI: for a base/yours/theirs/merge/bad
 * history, {@code hg bisect --good <base> --bad <bad>} tests the "yours" changeset
 * next, not the array-midpoint "theirs" changeset that the old code picked. See
 * {@link #nextOnBranchingDagPicksTheRealHgBisectCandidateNotTheArrayMidpoint()}.</p>
 */
public class BisectCommandCoverageTest {

    // ------------------------------------------------------------------
    // Precondition validation
    // ------------------------------------------------------------------

    @Test
    public void nextThrowsIllegalStateExceptionWhenGoodNotSet(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        BisectCommand bisect = new BisectCommand(repo).setBad(new byte[20]);
        assertThrows(IllegalStateException.class, bisect::next);
    }

    @Test
    public void nextThrowsIllegalStateExceptionWhenBadNotSet(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();
        BisectCommand bisect = new BisectCommand(repo).setGood(new byte[20]);
        assertThrows(IllegalStateException.class, bisect::next);
    }

    @Test
    public void nextThrowsIOExceptionWhenGoodNodeUnknown(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        byte[] badNode = new CommitCommand(repo).setMessage("only commit").call();

        byte[] bogusGood = randomNodeId();
        BisectCommand bisect = new BisectCommand(repo).setGood(bogusGood).setBad(badNode);
        IOException ex = assertThrows(IOException.class, bisect::next);
        assertTrue(ex.getMessage().contains("not found in changelog"));
    }

    @Test
    public void nextThrowsIOExceptionWhenBadNodeUnknown(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        byte[] goodNode = new CommitCommand(repo).setMessage("only commit").call();

        byte[] bogusBad = randomNodeId();
        BisectCommand bisect = new BisectCommand(repo).setGood(goodNode).setBad(bogusBad);
        IOException ex = assertThrows(IOException.class, bisect::next);
        assertTrue(ex.getMessage().contains("not found in changelog"));
    }

    // ------------------------------------------------------------------
    // Disconnected DAG (unrelated roots) -- verified against real hg: `hg bisect`
    // against two unrelated roots pulled into one repo aborts with "starting
    // revisions are not directly related".
    // ------------------------------------------------------------------

    @Test
    public void nextThrowsIOExceptionWhenGoodAndBadShareNoTopologicalPath(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        // Root A (rev 0)
        File f = new File(repoDir, "a.txt");
        Files.writeString(f.toPath(), "root A");
        new AddCommand(repo).call();
        byte[] rootA = new CommitCommand(repo).setMessage("root A").call();

        // A second, unrelated root (rev 1): reset dirstate parents to null so the
        // commit gets parent1Rev == parent2Rev == -1, exactly the mechanism real hg
        // uses for a repo's very first commit -- see CommitCommand, which computes
        // parentXRev == -1 whenever the dirstate parent node is the all-zero node.
        Dirstate d = repo.getDirstate();
        d.setParents(new byte[20], new byte[20]);
        repo.writeDirstate(d);
        File f2 = new File(repoDir, "b.txt");
        Files.writeString(f2.toPath(), "root B");
        new AddCommand(repo).call();
        byte[] rootB = new CommitCommand(repo).setMessage("root B (unrelated)").call();

        BisectCommand bisect = new BisectCommand(repo).setGood(rootA).setBad(rootB);
        IOException ex = assertThrows(IOException.class, bisect::next);
        assertTrue(ex.getMessage().contains("no topological path"));
    }

    @Test
    public void nextThrowsIOExceptionWhenGoodAndBadAreTheSameRevision(@TempDir Path tempDir) throws Exception {
        // Verified against real hg 7.2: `hg bisect --good 0 --bad 0` aborts with
        // "inconsistent state, 0:<hash> is good and bad" rather than silently
        // returning that revision as its own bisect candidate.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "only content");
        new AddCommand(repo).call();
        byte[] onlyNode = new CommitCommand(repo).setMessage("only commit").call();

        BisectCommand bisect = new BisectCommand(repo).setGood(onlyNode).setBad(onlyNode);
        IOException ex = assertThrows(IOException.class, bisect::next);
        assertTrue(ex.getMessage().contains("good and bad"));
    }

    // ------------------------------------------------------------------
    // Branching DAG (merge commit) -- midpoint selection algorithm
    // ------------------------------------------------------------------

    @Test
    public void nextOnBranchingDagPicksTheRealHgBisectCandidateNotTheArrayMidpoint(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File f = new File(repoDir, "hello.txt");

        // Rev 0: base (good)
        Files.writeString(f.toPath(), "Line 1\nLine 2\nLine 3\n");
        new AddCommand(repo).call();
        byte[] baseNode = new CommitCommand(repo).setMessage("base").call();

        // Rev 1: "yours" branch
        Files.writeString(f.toPath(), "Line 1 [MINE]\nLine 2\nLine 3\n");
        byte[] yoursNode = new CommitCommand(repo).setMessage("yours").call();

        // Rev 2: "theirs" branch (also a child of base)
        Dirstate d = repo.getDirstate();
        d.setParents(baseNode, new byte[20]);
        repo.writeDirstate(d);
        Files.writeString(f.toPath(), "Line 1\nLine 2\nLine 3 [THEIRS]\n");
        byte[] theirsNode = new CommitCommand(repo).setMessage("theirs").call();

        // Rev 3: merge of yours into theirs (non-conflicting hunks)
        new MergeCommand(repo).setNodeId(yoursNode).call();
        byte[] mergeNode = new CommitCommand(repo)
                .setAuthor("Merger <merger@example.com>")
                .setMessage("merge").call();

        // Rev 4: bad, single child of the merge
        Files.writeString(f.toPath(), "Line 1 [MINE]\nLine 2\nLine 3 [THEIRS]\nLine 4\n");
        byte[] badNode = new CommitCommand(repo).setMessage("child (bad)").call();

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog changelog = repo.getRevlog(clIdx, clDat);
        assertEquals(0, changelog.findRevision(baseNode));
        assertEquals(1, changelog.findRevision(yoursNode));
        assertEquals(2, changelog.findRevision(theirsNode));
        assertEquals(3, changelog.findRevision(mergeNode));
        assertEquals(4, changelog.findRevision(badNode));

        byte[] candidate = new BisectCommand(repo).setGood(baseNode).setBad(badNode).next();

        // Real hg 7.2 (`hg bisect --good <base> --bad <bad>` on the equivalent
        // history) tests "yours" (rev 1) next -- not "theirs" (rev 2), which is
        // what the old array-midpoint code used to (incorrectly) pick.
        assertArrayEquals(yoursNode, candidate,
                "must match real hg's ancestor-count bisection, not the plain array midpoint");
    }

    @Test
    public void nextOnReversedGoodBadOrderMatchesRealHgBisectCandidate(@TempDir Path tempDir) throws Exception {
        // A linear history of 5 commits where "good" is the *newer* revision and
        // "bad" the *older* one (searching for a good-transition instead of a
        // bad-transition). Verified against real hg 7.2: `hg bisect --good 4 --bad 0`
        // on this exact 5-commit linear history tests changeset 2 next.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File f = new File(repoDir, "f.txt");
        byte[][] nodes = new byte[5][];
        for (int i = 0; i < 5; i++) {
            Files.writeString(f.toPath(), "line" + i + "\n", java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            if (i == 0) {
                new AddCommand(repo).call();
            }
            nodes[i] = new CommitCommand(repo).setMessage("commit " + i).call();
        }

        byte[] candidate = new BisectCommand(repo).setGood(nodes[4]).setBad(nodes[0]).next();
        assertArrayEquals(nodes[2], candidate);
    }

    @Test
    public void nextExcludesAnAbandonedSideBranchFromTheCandidateRange(@TempDir Path tempDir) throws Exception {
        // base(good)=0 has two children: an abandoned side branch (1, never
        // touched again) and "theirs" (2), which continues on to "bad" (3). Since
        // rev 1 is not an ancestor of bad, it must never be a bisect candidate.
        // Verified against real hg 7.2: `hg bisect --good 0 --bad 3` on the
        // equivalent history tests changeset 2 ("theirs") next, reporting only
        // "2 changesets remaining" -- i.e. rev 1 is excluded from the count too.
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File f = new File(repoDir, "f.txt");
        File side = new File(repoDir, "side.txt");

        Files.writeString(f.toPath(), "base");
        new AddCommand(repo).call();
        byte[] goodNode = new CommitCommand(repo).setMessage("base").call();

        Files.writeString(side.toPath(), "side");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("abandoned side branch").call();

        Dirstate d = repo.getDirstate();
        d.setParents(goodNode, new byte[20]);
        repo.writeDirstate(d);
        Files.writeString(f.toPath(), "basetheirs");
        byte[] theirsNode = new CommitCommand(repo).setMessage("theirs").call();

        Files.writeString(f.toPath(), "basetheirsbad");
        byte[] badNode = new CommitCommand(repo).setMessage("bad").call();

        byte[] candidate = new BisectCommand(repo).setGood(goodNode).setBad(badNode).next();
        assertArrayEquals(theirsNode, candidate);
    }

    // ------------------------------------------------------------------
    // Corrupted / incomplete store
    // ------------------------------------------------------------------

    @Test
    public void nextThrowsHgRepositoryNotFoundExceptionWhenFilelogIsMissing(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        File f = new File(repoDir, "a.txt");

        Files.writeString(f.toPath(), "v0");
        new AddCommand(repo).call();
        byte[] goodNode = new CommitCommand(repo).setMessage("Commit 1").call();

        Files.writeString(f.toPath(), "v1");
        new CommitCommand(repo).setMessage("Commit 2").call();

        Files.writeString(f.toPath(), "v2");
        byte[] badNode = new CommitCommand(repo).setMessage("Commit 3").call();

        // Simulate store corruption: the filelog backing "a.txt" (referenced by
        // every commit's manifest, including the bisect midpoint's) disappears.
        File flIdx = CommitCommand.getFilelogIndex(repo.getStoreDir(), "a.txt");
        File flDat = new File(flIdx.getPath().substring(0, flIdx.getPath().length() - 2) + ".d");
        assertTrue(flIdx.exists(), "precondition: filelog index must exist before deleting it");
        Files.delete(flIdx.toPath());
        if (flDat.exists()) {
            Files.delete(flDat.toPath());
        }

        BisectCommand bisect = new BisectCommand(repo).setGood(goodNode).setBad(badNode);
        assertThrows(HgRepositoryNotFoundException.class, bisect::next);
    }

    // ------------------------------------------------------------------
    // Private-helper edge cases reached via reflection: these guard against
    // manifest/changelog corruption that next()'s own call sites (always a
    // freshly-looked-up, real revision) can never trigger organically, but the
    // helpers' own defensive behavior is still real, correct, testable logic.
    // ------------------------------------------------------------------

    @Test
    public void getFileRevisionContentThrowsHgRevisionNotFoundExceptionForUnknownHex(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit 1").call();

        Method m = BisectCommand.class.getDeclaredMethod(
                "getFileRevisionContent", HgRepository.class, String.class, String.class);
        m.setAccessible(true);
        BisectCommand bisect = new BisectCommand(repo);
        String bogusHex = "f".repeat(40);
        InvocationTargetException ite = assertThrows(InvocationTargetException.class,
                () -> m.invoke(bisect, repo, "a.txt", bogusHex));
        assertInstanceOf(HgRevisionNotFoundException.class, ite.getCause());
    }

    @Test
    public void getManifestForCommitReturnsEmptyMapForNullOrAllZeroOrUnknownNode(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();
        Files.writeString(new File(repoDir, "a.txt").toPath(), "v0");
        new AddCommand(repo).call();
        new CommitCommand(repo).setMessage("Commit 1").call();

        File clIdx = new File(repo.getStoreDir(), "00changelog.i");
        File clDat = new File(repo.getStoreDir(), "00changelog.d");
        Revlog changelog = repo.getRevlog(clIdx, clDat);
        File mfIdx = new File(repo.getStoreDir(), "00manifest.i");
        File mfDat = new File(repo.getStoreDir(), "00manifest.d");
        Revlog manifestRevlog = repo.getRevlog(mfIdx, mfDat);

        Method m = BisectCommand.class.getDeclaredMethod(
                "getManifestForCommit", Revlog.class, Revlog.class, byte[].class);
        m.setAccessible(true);
        BisectCommand bisect = new BisectCommand(repo);

        @SuppressWarnings("unchecked")
        Map<String, String> forNull = (Map<String, String>) m.invoke(bisect, changelog, manifestRevlog, (byte[]) null);
        assertTrue(forNull.isEmpty());

        @SuppressWarnings("unchecked")
        Map<String, String> forAllZero = (Map<String, String>) m.invoke(bisect, changelog, manifestRevlog, new byte[20]);
        assertTrue(forAllZero.isEmpty());

        @SuppressWarnings("unchecked")
        Map<String, String> forUnknown = (Map<String, String>) m.invoke(bisect, changelog, manifestRevlog, randomNodeId());
        assertTrue(forUnknown.isEmpty());
    }

    private static byte[] randomNodeId() {
        byte[] node = new byte[20];
        new Random().nextBytes(node);
        node[0] |= 1; // avoid the (astronomically unlikely) all-zero collision
        return node;
    }
}
