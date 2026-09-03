package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;
import io.github.search5.hg4j.util.NodeIdUtil;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Heads command for querying SCM repository heads.
 *
 * <p>With no branch filter, {@link #call()} returns pure topological heads (revisions with no
 * children anywhere in the repository) -- this matches real hg's {@code hg heads --topo}, verified
 * directly against hg 7.2.2 (2026-09-04). Real hg's plain {@code hg heads} (no {@code --topo}) is
 * actually a <em>different</em>, broader query: the highest-revision open head of <em>every</em>
 * named branch, which can include revisions that are not pure topological leaves (e.g. a branch
 * whose own tip has since been merged into a different branch, leaving it without a same-branch
 * child but with a cross-branch one). This class does not currently reproduce that broader
 * no-argument behavior -- see the class javadoc history / backlog notes for the confirmed
 * repro. {@link #setBranch} below only covers the narrower, explicitly-filtered
 * {@code hg heads <branch>} form, which real hg defines identically whether or not {@code --topo}
 * is layered on top of a branch filter: it returns exactly that branch's own topological heads
 * (per-branch, not repo-wide), open ones only unless {@link #setIncludeClosed} is set.</p>
 */
public class HeadsCommand {
    private final HgRepository repository;
    private String branch;
    private boolean includeClosed = false;

    public HeadsCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Restricts the result to one named branch's own heads (real hg's {@code hg heads <branch>}):
     * every revision on that branch with no same-branch child, not just the repo-wide topological
     * leaves. A branch with several such heads (an internal fork) returns all of them. A branch
     * name unknown to the repository yields an empty result (real hg instead aborts with
     * {@code abort: unknown revision '<name>'} at the CLI layer; this porcelain API returns no
     * heads rather than throwing, consistent with this codebase's other list-returning commands).
     */
    public HeadsCommand setBranch(String branch) {
        this.branch = branch;
        return this;
    }

    /**
     * Real hg's {@code hg heads --closed}: also include heads closed via
     * {@code hg commit --close-branch}. Only meaningful together with {@link #setBranch}; the
     * unfiltered topological {@link #call()} has no notion of "closed" (a closed head with no
     * children is still a topological leaf and is always included).
     */
    public HeadsCommand setIncludeClosed(boolean includeClosed) {
        this.includeClosed = includeClosed;
        return this;
    }

    /**
     * Traverses the changelog and finds all leaf nodes in SCM commit history, or -- when
     * {@link #setBranch} has been called -- that one branch's own heads.
     *
     * @return List of head node IDs in hex representation
     * @throws IOException if changelog IO fails
     */
    public List<String> call() throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        Revlog changelog = repository.getRevlog(clIdx, clDat);

        int count = changelog.getRevisionCount();
        List<String> headList = new ArrayList<>();
        if (count == 0) {
            return headList;
        }

        if (branch != null) {
            return branchHeads(changelog, count);
        }

        // Parent tracking: any revision that is a parent of another revision is not a head
        Set<Integer> parents = new HashSet<>();
        for (int i = 0; i < count; i++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(i);
            int p1 = rec.getParent1();
            int p2 = rec.getParent2();
            if (p1 != -1) parents.add(p1);
            if (p2 != -1) parents.add(p2);
        }

        for (int i = 0; i < count; i++) {
            if (!parents.contains(i)) {
                headList.add(NodeIdUtil.toHex(changelog.getIndexRecord(i).getNodeId()));
            }
        }
        return headList;
    }

    private List<String> branchHeads(Revlog changelog, int count) throws IOException {
        String[] branchOfRev = new String[count];
        for (int i = 0; i < count; i++) {
            branchOfRev[i] = CommitCommand.getBranchOfRevision(changelog, i);
        }

        boolean[] hasBranchChild = new boolean[count];
        for (int i = 0; i < count; i++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(i);
            int p1 = rec.getParent1();
            int p2 = rec.getParent2();
            if (p1 >= 0 && branchOfRev[p1].equals(branchOfRev[i])) {
                hasBranchChild[p1] = true;
            }
            if (p2 >= 0 && branchOfRev[p2].equals(branchOfRev[i])) {
                hasBranchChild[p2] = true;
            }
        }

        // Real hg lists heads highest-revision first (verified against hg 7.2.2, 2026-09-04).
        List<String> result = new ArrayList<>();
        for (int i = count - 1; i >= 0; i--) {
            if (hasBranchChild[i] || !branch.equals(branchOfRev[i])) {
                continue;
            }
            if (!includeClosed && CommitCommand.isRevisionClosingBranch(changelog, i)) {
                continue;
            }
            result.add(NodeIdUtil.toHex(changelog.getIndexRecord(i).getNodeId()));
        }
        return result;
    }
}
