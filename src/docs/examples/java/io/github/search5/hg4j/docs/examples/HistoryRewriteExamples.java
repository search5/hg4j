package io.github.search5.hg4j.docs.examples;

import io.github.search5.hg4j.api.Hg;
import io.github.search5.hg4j.api.HisteditCommand;
import io.github.search5.hg4j.api.TreeMergeCommand;
import io.github.search5.hg4j.errors.HgLockException;
import io.github.search5.hg4j.errors.HgMergeConflictException;
import io.github.search5.hg4j.lib.NodeId;

import java.io.IOException;
import java.util.List;

/**
 * Snippets for the "Rewriting History" chapter.
 */
final class HistoryRewriteExamples {

    private HistoryRewriteExamples() {
    }

    void amendTipCommit() throws IOException, HgLockException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::amend-tip-commit[]
            // Replaces the tip commit with a new one on the SAME parent(s) (a sibling, not a
            // child) -- anything not explicitly overridden here (author, close-branch state)
            // is copied from the amended-away commit itself.
            byte[] amendedNode = hg.amend().setMessage("Fixed typo in commit message").call();
            // end::amend-tip-commit[]
        }
    }

    void rewriteRangeWithHistedit() throws IOException, HgLockException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::histedit-rules[]
            // Rules are replayed in order, OLDEST revision first -- like an interactive rebase
            // "todo list". Here two adjacent commits are squashed into one (their file changes
            // are combined and their commit messages concatenated), and the newest commit is
            // recommitted as-is right after.
            String oldestOfRange = "aaaa1111aaaa1111aaaa1111aaaa1111aaaa1111";
            String middleCommit = "bbbb2222bbbb2222bbbb2222bbbb2222bbbb2222";
            String newestCommit = "cccc3333cccc3333cccc3333cccc3333cccc3333";

            hg.histedit()
                    .addRule(HisteditCommand.Action.PICK, oldestOfRange)
                    .addRule(HisteditCommand.Action.FOLD, middleCommit)
                    .addRule(HisteditCommand.Action.PICK, newestCommit)
                    .call();
            // end::histedit-rules[]
        }
    }

    void graftOntoCurrentParent() throws IOException, HgLockException, HgMergeConflictException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::graft-cherry-pick[]
            // Copies revision "42"'s changes onto the working copy's current parent, as a
            // brand-new commit. Unlike amend()/histedit(), graft() writes no obsolescence
            // marker -- the source revision stays fully visible in a plain `hg log` afterward.
            String graftedHex = hg.graft().setSource("42").call();
            System.out.println("Grafted as: " + graftedHex);
            // end::graft-cherry-pick[]
        }
    }

    void resumeOrAbortAPausedGraft() throws IOException, HgLockException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::graft-continue-or-abort[]
            // After resolving every conflicted path reported by the HgMergeConflictException
            // (see the error-handling chapter) and re-staging the resolved content on disk,
            // finish the paused commit:
            String graftedHex = hg.graft().continueGraft();
            System.out.println("Grafted as: " + graftedHex);

            // Or, to give up on the paused graft entirely and restore the pre-graft working copy:
            // hg.graft().abort();
            // end::graft-continue-or-abort[]
        }
    }

    void undoASingleRevision() throws IOException, HgLockException, HgMergeConflictException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::backout-revision[]
            // Creates a brand-new commit that reverses revision 17's changes -- history itself
            // is untouched (no obsolescence marker, no strip). Backing out the working copy's
            // own parent (the common case) can never conflict; backing out an older ancestor
            // performs a genuine 3-way merge and can throw HgMergeConflictException just like
            // graft/rebase (see the error-handling chapter) -- but unlike those commands there
            // is no backout --continue: resolve the conflicted files by hand, then just commit
            // normally.
            byte[] backoutNode = hg.backout().setRevision("17").setMessage("Revert regression from rev 17").call();
            // end::backout-revision[]
        }
    }

    void computeAndRecordMergeWithoutWorkingCopy() throws IOException, HgLockException {
        try (Hg hg = Hg.open("/path/to/repo")) {
            // tag::tree-merge-and-commit[]
            List<String> headHexes = hg.heads().call();
            byte[] ours = NodeId.fromHex(headHexes.get(0)).getBytes();
            byte[] theirs = NodeId.fromHex(headHexes.get(1)).getBytes();

            // Computes the merge purely from the changelog/manifest/filelog store -- the
            // working directory and dirstate are never touched.
            TreeMergeCommand.TreeMergeResult result = hg.treeMerge().setOurs(ours).setTheirs(theirs).call();

            if (result.isConflicted()) {
                for (String conflictedPath : result.getConflicts()) {
                    System.out.println("Would conflict: " + conflictedPath);
                }
            } else {
                // Writes the result directly as a new two-parent changeset -- again without
                // ever checking anything out. Bookmarks are not moved automatically; use
                // bookmark() afterward if the merge should advance one.
                byte[] mergeCommitNode = hg.mergeCommit()
                        .setParents(ours, theirs)
                        .setTreeMergeResult(result)
                        .setMessage("Merge (server-side, no checkout)")
                        .call();
            }
            // end::tree-merge-and-commit[]
        }
    }
}
