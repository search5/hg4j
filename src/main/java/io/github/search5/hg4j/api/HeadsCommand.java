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
 * <p>With no branch filter and {@link #setTopo} left at its default ({@code false}), {@link #call()}
 * reproduces real hg's plain {@code hg heads} (no {@code --topo}): for every named branch that has
 * at least one open head, that branch's own head(s) -- revisions with no <em>same-branch</em> child
 * -- are included, even when such a revision is not a repo-wide topological leaf (e.g. a branch
 * whose own tip has since been merged into or built upon by a different branch, leaving it without
 * a same-branch child but with a cross-branch one). This mirrors real hg's
 * {@code mercurial/commands.py heads()}: {@code for branch in repo.branchmap(): heads +=
 * bm.branchheads(branch, closed=...)}, verified directly against hg 7.2.2 (2026-09-04). Passing
 * {@link #setTopo}{@code (true)} switches to real hg's {@code hg heads --topo}: pure repo-wide
 * topological leaves (revisions with no children anywhere in the repository), ignoring branch
 * mechanics entirely -- this was this class's unconditional behavior before 2026-09-04 and remains
 * available as an explicit opt-in. {@link #setBranch} covers the narrower, explicitly-filtered
 * {@code hg heads <branch>} form, which real hg defines identically whether or not {@code --topo}
 * is layered on top of a branch filter: it returns exactly that branch's own topological heads
 * (per-branch, not repo-wide), open ones only unless {@link #setIncludeClosed} is set.</p>
 */
public class HeadsCommand {
    private final HgRepository repository;
    private String branch;
    private boolean includeClosed = false;
    private boolean topo = false;

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
     * {@code hg commit --close-branch}. Applies both to the unfiltered no-argument default (each
     * branch's closed heads are then also included alongside its open ones, matching real hg's
     * {@code bm.branchheads(branch, closed=True)}) and to {@link #setBranch}-filtered queries.
     * Meaningless together with {@link #setTopo}{@code (true)}: a closed head with no children is
     * still a topological leaf and is always included there, exactly as real hg's
     * {@code --topo --closed} combination behaves (closed is simply a no-op).
     */
    public HeadsCommand setIncludeClosed(boolean includeClosed) {
        this.includeClosed = includeClosed;
        return this;
    }

    /**
     * Real hg's {@code hg heads --topo}: ignore named-branch mechanics and return only pure
     * repo-wide topological heads (revisions with no children anywhere in the repository).
     * Default {@code false}, matching real hg's own default (plain {@code hg heads} is
     * branch-aware, not purely topological).
     */
    public HeadsCommand setTopo(boolean topo) {
        this.topo = topo;
        return this;
    }

    /**
     * Traverses the changelog and finds heads per the configured mode: one explicit branch's own
     * heads ({@link #setBranch}), pure repo-wide topological leaves ({@link #setTopo}), or --
     * the default -- every branch's own open (or, with {@link #setIncludeClosed}, also closed)
     * head(s), matching real hg's plain {@code hg heads}.
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

        if (topo) {
            return topoHeads(changelog, count);
        }

        return allBranchHeads(changelog, count);
    }

    /** Real hg's {@code hg heads --topo}: pure repo-wide topological leaves. */
    private List<String> topoHeads(Revlog changelog, int count) throws IOException {
        // Parent tracking: any revision that is a parent of another revision is not a head
        Set<Integer> parents = new HashSet<>();
        for (int i = 0; i < count; i++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(i);
            int p1 = rec.getParent1();
            int p2 = rec.getParent2();
            if (p1 != -1) parents.add(p1);
            if (p2 != -1) parents.add(p2);
        }

        List<String> headList = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (!parents.contains(i)) {
                headList.add(NodeIdUtil.toHex(changelog.getIndexRecord(i).getNodeId()));
            }
        }
        return headList;
    }

    /**
     * Real hg's plain {@code hg heads} (no arguments): every named branch's own open head(s),
     * aggregated across all branches and sorted by revision descending -- real hg's
     * {@code commands.heads()} does {@code heads = sorted(heads, key=lambda x: -(x.rev()))} over
     * the combined list from every branch, not a per-branch grouping.
     */
    private List<String> allBranchHeads(Revlog changelog, int count) throws IOException {
        String[] branchOfRev = new String[count];
        for (int i = 0; i < count; i++) {
            branchOfRev[i] = CommitCommand.getBranchOfRevision(changelog, i);
        }

        boolean[] hasBranchChild = branchChildFlags(changelog, count, branchOfRev);

        List<String> result = new ArrayList<>();
        for (int i = count - 1; i >= 0; i--) {
            if (hasBranchChild[i]) {
                continue;
            }
            if (!includeClosed && CommitCommand.isRevisionClosingBranch(changelog, i)) {
                continue;
            }
            result.add(NodeIdUtil.toHex(changelog.getIndexRecord(i).getNodeId()));
        }
        return result;
    }

    private List<String> branchHeads(Revlog changelog, int count) throws IOException {
        String[] branchOfRev = new String[count];
        for (int i = 0; i < count; i++) {
            branchOfRev[i] = CommitCommand.getBranchOfRevision(changelog, i);
        }

        boolean[] hasBranchChild = branchChildFlags(changelog, count, branchOfRev);

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

    private static boolean[] branchChildFlags(Revlog changelog, int count, String[] branchOfRev)
            throws IOException {
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
        return hasBranchChild;
    }
}
