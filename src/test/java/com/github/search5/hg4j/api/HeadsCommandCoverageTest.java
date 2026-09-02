package com.github.search5.hg4j.api;

import com.github.search5.hg4j.dirstate.Dirstate;
import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.util.NodeIdUtil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage for {@link HeadsCommand}, targeting branches that
 * {@code PorcelainExtraCommandsTest} doesn't reach: the {@code count == 0}
 * early-return on a brand new repository with no commits, and the
 * {@code p2 != -1} branch of parent tracking, which only fires for a
 * changelog revision recorded with two parents (a merge commit).
 */
public class HeadsCommandCoverageTest {

    private static void write(File repoDir, String name, String content) throws Exception {
        File f = new File(repoDir, name);
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), content);
    }

    private static byte[] commit(HgRepository repo, String message) throws Exception {
        new AddCommand(repo).call();
        return new CommitCommand(repo).setAuthor("u <u@example.com>").setMessage(message).call();
    }

    /**
     * Covers the {@code count == 0} branch of {@link HeadsCommand#call()} (line 36/37):
     * a freshly initialized repository has an empty changelog, so {@code call()} must
     * short-circuit and return an empty head list without touching parent tracking.
     */
    @Test
    public void emptyRepositoryWithNoCommitsHasNoHeads(@TempDir Path tempDir) throws Exception {
        HgRepository repo = Hg.init().setDirectory(tempDir.toFile()).call();

        List<String> heads = new HeadsCommand(repo).call();
        assertTrue(heads.isEmpty(), "a repository with no commits must report no heads");
    }

    /**
     * Covers the {@code p2 != -1} branch of {@link HeadsCommand#call()} (line 47): a merge
     * commit records a real second parent in the changelog, so both branch tips that fed the
     * merge must be excluded from the head list -- the first parent via the already-covered
     * {@code p1 != -1} branch, and the second parent only via this branch.
     */
    @Test
    public void mergeCommitParent2IsExcludedFromHeads(@TempDir Path tempDir) throws Exception {
        File repoDir = tempDir.toFile();
        HgRepository repo = Hg.init().setDirectory(repoDir).call();

        write(repoDir, "base.txt", "base\n");
        byte[] baseNode = commit(repo, "base");

        // Branch 1: advance from base.
        write(repoDir, "b1.txt", "branch one\n");
        byte[] branch1Node = commit(repo, "branch1");

        // Branch 2: fork from base independently.
        Dirstate ds = repo.getDirstate();
        ds.setParents(baseNode, new byte[20]);
        new File(repoDir, "b1.txt").delete();
        ds.removeEntry("b1.txt");
        repo.writeDirstate(ds);
        write(repoDir, "b2.txt", "branch two\n");
        byte[] branch2Node = commit(repo, "branch2");

        // Merge branch1 into branch2 (current checkout) and commit the merge: this records
        // branch1Node as the merge commit's second parent.
        MergeCommand.MergeResult mergeRes = new MergeCommand(repo).setNodeId(branch1Node).call();
        assertFalse(mergeRes.isConflicted());
        byte[] mergeNode = commit(repo, "merge branch1 into branch2");
        String hexMerge = NodeIdUtil.toHex(mergeNode);
        String hexBranch1 = NodeIdUtil.toHex(branch1Node);
        String hexBranch2 = NodeIdUtil.toHex(branch2Node);

        List<String> heads = new HeadsCommand(repo).call();
        assertEquals(1, heads.size(), "only the merge commit should remain a head");
        assertEquals(hexMerge, heads.get(0));
        assertFalse(heads.contains(hexBranch1), "branch1 tip must be excluded via the p2 branch");
        assertFalse(heads.contains(hexBranch2), "branch2 tip must be excluded via the p1 branch");
    }
}
