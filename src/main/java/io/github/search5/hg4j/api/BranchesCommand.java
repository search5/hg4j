package io.github.search5.hg4j.api;

import io.github.search5.hg4j.lib.HgRepository;
import io.github.search5.hg4j.storage.Revlog;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code hg branches}-equivalent: one entry per named branch, giving its current head and whether
 * that head is closed. Matching real hg's own semantics: when a branch has several heads, the
 * reported head is the highest-revision <em>open</em> one if it has any, else (every head closed)
 * the highest-revision closed one; a branch whose every head is closed is hidden unless
 * {@link #setIncludeClosed} is set, mirroring real hg's own default {@code hg branches} vs
 * {@code hg branches --closed}.
 *
 * <p>Ordering (matching real hg's {@code branchmap.branches_info}/{@code commands.branches}):
 * a branch is "active" when its highest-revision <em>open</em> head is also a repo-wide
 * topological head (a revision with <em>no</em> children anywhere in the repo, not just within
 * its own branch) -- this is unrelated to which branch the working directory currently has
 * checked out. Real hg sorts {@code (active, rev, name, isOpen)} all descending, i.e. active
 * branches first (by descending rev), then inactive branches (by descending rev), with branch
 * name (reverse-alphabetical) and open-before-closed as further tie-breaks. Naively sorting by
 * revision alone diverges from real hg whenever an inactive branch's head revision is higher than
 * some other, still-active branch's head revision: branch A forked into branch Z (making A
 * inactive) sorts as {@code Z, Y, A, default} in real hg (Z and Y are active; Y's head revision is
 * lower than A's, yet Y still sorts before A) even though revision numbers alone are
 * {@code Z=3, A=2, Y=1, default=0}.</p>
 *
 * @apiNote Typically obtained via {@link Hg#branches()} on an open {@link Hg}
 *     instance rather than constructed directly.
 */
public class BranchesCommand {
    private final HgRepository repository;
    private boolean includeClosed = false;

    /**
     * Creates a branches command bound to the given repository.
     *
     * @param repository repository whose changelog will be scanned for named branches
     */
    public BranchesCommand(HgRepository repository) {
        this.repository = repository;
    }

    /**
     * Sets whether branches whose only heads are closed should be included in the result.
     *
     * @param includeClosed {@code true} to include all-closed branches, {@code false} (the
     *     default) to hide them, matching {@code hg branches} vs {@code hg branches --closed}
     * @return this command, for chaining
     */
    public BranchesCommand setIncludeClosed(boolean includeClosed) {
        this.includeClosed = includeClosed;
        return this;
    }

    /** One named branch's reported head, as computed by {@link BranchesCommand#call()}. */
    public static class BranchHead {
        private final String branch;
        private final byte[] node;
        private final int rev;
        private final boolean closed;
        private final boolean active;

        BranchHead(String branch, byte[] node, int rev, boolean closed, boolean active) {
            this.branch = branch;
            this.node = node;
            this.rev = rev;
            this.closed = closed;
            this.active = active;
        }

        /**
         * Returns the branch name.
         *
         * @return the branch name
         */
        public String getBranch() {
            return branch;
        }

        /**
         * Returns the node ID of this branch's reported head revision.
         *
         * @return the node ID of this branch's reported head revision
         */
        public byte[] getNode() {
            return node;
        }

        /**
         * Returns the revision number of this branch's reported head revision.
         *
         * @return the revision number of this branch's reported head revision
         */
        public int getRev() {
            return rev;
        }

        /**
         * Returns whether the reported head revision closes the branch.
         *
         * @return {@code true} if the reported head revision closes the branch
         */
        public boolean isClosed() {
            return closed;
        }

        /**
         * Mirrors real hg's "(inactive)" marker: {@code true} when this branch's reported head is
         * also a repo-wide topological head (no revision anywhere in the repository has it as a
         * parent). A branch whose only heads are closed is never active.
         *
         * @return {@code true} if this branch is active
         */
        public boolean isActive() {
            return active;
        }
    }

    /**
     * Executes the command, computing one {@link BranchHead} per named branch.
     *
     * @return branch heads sorted per real hg's {@code branchmap.branches_info()}/{@code
     *     commands.branches()} order (active branches first, by descending revision, then
     *     inactive branches likewise, with branch name and open-before-closed as tie-breaks)
     * @throws IOException if the changelog cannot be read
     */
    public List<BranchHead> call() throws IOException {
        File clIdx = new File(repository.getStoreDir(), "00changelog.i");
        File clDat = new File(repository.getStoreDir(), "00changelog.d");
        if (!clIdx.exists()) {
            return List.of();
        }
        Revlog changelog = repository.getRevlog(clIdx, clDat);
        int count = changelog.getRevisionCount();
        if (count == 0) {
            return List.of();
        }

        String[] branchOfRev = new String[count];
        for (int i = 0; i < count; i++) {
            branchOfRev[i] = CommitCommand.getBranchOfRevision(changelog, i);
        }

        // A revision is a branch-local head unless some other revision on the same branch has it
        // as a parent (topological heads restricted per-branch, not repo-wide like HgLocalClient#getHeads).
        // Separately, a revision is a repo-wide head (real hg's repo.heads()) unless *any* revision
        // anywhere, on any branch, has it as a parent -- this repo-wide notion (not the per-branch
        // one above) is what real hg's "active" branch marker is based on.
        boolean[] hasBranchChild = new boolean[count];
        boolean[] hasAnyChild = new boolean[count];
        for (int i = 0; i < count; i++) {
            Revlog.IndexRecord rec = changelog.getIndexRecord(i);
            int p1 = rec.getParent1();
            int p2 = rec.getParent2();
            if (p1 >= 0) {
                hasAnyChild[p1] = true;
                if (branchOfRev[p1].equals(branchOfRev[i])) {
                    hasBranchChild[p1] = true;
                }
            }
            if (p2 >= 0) {
                hasAnyChild[p2] = true;
                if (branchOfRev[p2].equals(branchOfRev[i])) {
                    hasBranchChild[p2] = true;
                }
            }
        }

        Map<String, List<Integer>> headsByBranch = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            if (!hasBranchChild[i]) {
                headsByBranch.computeIfAbsent(branchOfRev[i], k -> new ArrayList<>()).add(i);
            }
        }

        List<BranchHead> result = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : headsByBranch.entrySet()) {
            Integer bestOpen = null;
            Integer bestClosed = null;
            for (int rev : e.getValue()) {
                if (CommitCommand.isRevisionClosingBranch(changelog, rev)) {
                    if (bestClosed == null || rev > bestClosed) {
                        bestClosed = rev;
                    }
                } else if (bestOpen == null || rev > bestOpen) {
                    bestOpen = rev;
                }
            }
            boolean allClosed = bestOpen == null;
            if (allClosed && !includeClosed) {
                continue;
            }
            int chosenRev = allClosed ? bestClosed : bestOpen;
            byte[] node = changelog.getIndexRecord(chosenRev).getNodeId();
            // Real hg's is_active requires an *open* head that is also a repo-wide topological
            // head; a branch with only closed heads is never active.
            boolean active = !allClosed && !hasAnyChild[bestOpen];
            result.add(new BranchHead(e.getKey(), node, chosenRev, allClosed, active));
        }

        // Matches real hg's branchmap.branches_info()/commands.branches() sort key
        // (active, rev, name, isOpen), all descending.
        result.sort((a, b) -> {
            if (a.isActive() != b.isActive()) {
                return a.isActive() ? -1 : 1;
            }
            if (a.getRev() != b.getRev()) {
                return b.getRev() - a.getRev();
            }
            int nameCmp = b.getBranch().compareTo(a.getBranch());
            if (nameCmp != 0) {
                return nameCmp;
            }
            // isOpen tie-break (open before closed); at this point a/b share a branch head rev,
            // which in practice never happens for two distinct branches, but keep it total.
            return Boolean.compare(a.isClosed(), b.isClosed());
        });
        return result;
    }
}
