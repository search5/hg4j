package com.github.search5.hg4j.api;

import com.github.search5.hg4j.lib.HgRepository;
import com.github.search5.hg4j.storage.Revlog;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code hg branches}-equivalent: one entry per named branch, giving its current head and whether
 * that head is closed. Real hg's own semantics (verified directly against hg 7.2.2, 2026-09-01):
 * when a branch has several heads, the reported head is the highest-revision <em>open</em> one if
 * it has any, else (every head closed) the highest-revision closed one; a branch whose every head
 * is closed is hidden unless {@link #setIncludeClosed} is set, mirroring real hg's own default
 * {@code hg branches} vs {@code hg branches --closed}.
 */
public class BranchesCommand {
    private final HgRepository repository;
    private boolean includeClosed = false;

    public BranchesCommand(HgRepository repository) {
        this.repository = repository;
    }

    public BranchesCommand setIncludeClosed(boolean includeClosed) {
        this.includeClosed = includeClosed;
        return this;
    }

    public static class BranchHead {
        private final String branch;
        private final byte[] node;
        private final int rev;
        private final boolean closed;

        BranchHead(String branch, byte[] node, int rev, boolean closed) {
            this.branch = branch;
            this.node = node;
            this.rev = rev;
            this.closed = closed;
        }

        public String getBranch() {
            return branch;
        }

        public byte[] getNode() {
            return node;
        }

        public int getRev() {
            return rev;
        }

        public boolean isClosed() {
            return closed;
        }
    }

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
            result.add(new BranchHead(e.getKey(), node, chosenRev, allClosed));
        }

        result.sort((a, b) -> b.getRev() - a.getRev());
        return result;
    }
}
